package com.legacy.analysis;
import com.legacy.analysis.llm.LlmClient;
import com.legacy.analysis.llm.LlmResult;
import com.legacy.core.ApiErrorHandler;
import com.legacy.core.FileIoErrorHandler;
import com.legacy.core.LayerLabels;
import com.legacy.core.ProjectTypeDetector;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ClaudeServiceImpl implements ClaudeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeServiceImpl.class);

    // Jackson ObjectMapper 글로벌 인스턴스 공유로 불필요한 객체 재생성 경고 차단
    private static final ObjectMapper mapper = new ObjectMapper();

    // 스레드 풀에서 호출되므로 AtomicLong으로 스레드 안전하게 토큰 누적
    private final AtomicLong accumulatedInputTokens = new AtomicLong(0);
    private final AtomicLong accumulatedOutputTokens = new AtomicLong(0);
    private final AtomicLong accumulatedCacheReadTokens = new AtomicLong(0);
    private final AtomicLong accumulatedCacheCreationTokens = new AtomicLong(0);
    private volatile String lastModelName = "";

    // API KEY 미설정 가드(isAnthropicMode() 참고)에서만 사용 — 실제 HTTP 호출은 llmClient(AnthropicLlmClient)가 전담
    @Value("${anthropic.api.key}")
    private String apiKey;

    @Value("${anthropic.api.model}")
    private String apiModel;

    // 런타임 모델 오버라이드 (선택한 모델이 있으면 우선 사용)
    private volatile String modelOverride = null;

    // 분석 세션(소스 경로)별로 AI가 생성한 CLAUDE.md 내용을 보관 (세션 종료 시 정리)
    private final Map<String, String> sessionSystemPrompts = new java.util.concurrent.ConcurrentHashMap<>();

    // 지원 모델 목록과 표시명
    public static final Map<String, String> SUPPORTED_MODELS = new java.util.LinkedHashMap<>();
    static {
        SUPPORTED_MODELS.put("claude-sonnet-4-6", "Claude Sonnet ($3/$15 per 1M)");
        SUPPORTED_MODELS.put("claude-opus-4-8", "Claude Opus ($15/$75 per 1M)");
        SUPPORTED_MODELS.put("claude-haiku-4-5-20251001", "Claude Haiku ($0.80/$4 per 1M)");
    }

    @Value("${anthropic.api.max-tokens:4000}")
    private int apiMaxTokens;

    @Value("${app.analysis.custom-spec-filename:custom_spec.txt}")
    private String customSpecFilename;

    @Value("${app.analysis.system-prompt-filename:CLAUDE.md}")
    private String systemPromptFilename;

    // base(prompt-base.md) 안에서 role 콘텐츠가 삽입될 위치를 나타내는 마커.
    // "베끼기 금지 경고"/"응답 포맷(JSON)" 두 섹션은 base의 고정 위치(마커보다 앞/뒤)에 그대로 있으므로
    // role 병합 여부와 무관하게 항상 원형 그대로 유지되고, "응답 포맷" 섹션은 항상 프롬프트 맨 끝에 온다.
    private static final String ROLE_CONTENT_MARKER = "{{ROLE_CONTENT}}";

    // 확장자 → role 파일명 매핑 (2026-07-29 설계안 3.2절). role-js/role-vue는 원본에서 한 블록으로
    // 묶여 있던 JS/TS/Vue를 분리한 것 — Vue 전용 예시가 순수 JS/TS 분석 시 섞여 들어가는 것을 막는다.
    private static final Map<String, String> ROLE_FILE_BY_EXTENSION = Map.ofEntries(
        Map.entry(".java", "role-java.md"),
        Map.entry(".py", "role-python.md"),
        Map.entry(".js", "role-js.md"),
        Map.entry(".ts", "role-js.md"),
        Map.entry(".jsx", "role-js.md"),
        Map.entry(".tsx", "role-js.md"),
        Map.entry(".vue", "role-vue.md"),
        Map.entry(".xml", "role-xml.md"),
        Map.entry(".html", "role-xml.md"),
        Map.entry(".xfdl", "role-nexacro.md"),
        // Phase 1.5(2026-08-11, 설계안 3.4절) — isSupportedFile() 화이트리스트 확장자 중
        // 실제 파일 개수 상위 2개만 우선 추가. .json은 표준 문법상 주석을 지원하지 않아
        // 이번 범위에서 완전히 제외(설계안 3.4절 참고).
        Map.entry(".properties", "role-properties.md"),
        Map.entry(".yml", "role-yaml.md"),
        Map.entry(".yaml", "role-yaml.md"),
        // 2026-08-11 후속 반영 — 최초엔 후속 이슈로 보류했다가, 사용자 요청으로 이번 범위에 포함
        Map.entry(".gradle", "role-gradle.md"),
        Map.entry(".css", "role-css.md")
    );

    // Claude API ↔ 로컬/사내 LLM 전환 스위치 (기본값 anthropic — LlmClient 빈 선택과 동일한 기본값)
    @Value("${llm.provider:anthropic}")
    private String llmProvider;

    // local 모드에서 실제로 호출할 모델명 — SUPPORTED_MODELS 화이트리스트 검증 대상이 아님
    // (서버가 로컬/사내 인프라에 어떤 모델이 서빙 중인지 알 수 없으므로 설정값을 그대로 신뢰)
    @Value("${llm.local.model:}")
    private String llmLocalModel;

    private final ApiErrorHandler apiErrorHandler;
    private final FileIoErrorHandler fileIoErrorHandler;
    private final SessionConfig sessionConfig;
    private final ProjectTypeDetector projectTypeDetector;
    private final LlmClient llmClient;

    @Autowired
    public ClaudeServiceImpl(ApiErrorHandler apiErrorHandler, FileIoErrorHandler fileIoErrorHandler,
        SessionConfig sessionConfig, ProjectTypeDetector projectTypeDetector, LlmClient llmClient) {
      this.apiErrorHandler = apiErrorHandler;
      this.fileIoErrorHandler = fileIoErrorHandler;
      this.sessionConfig = sessionConfig;
      this.projectTypeDetector = projectTypeDetector;
      this.llmClient = llmClient;
    }

    /**
     * Anthropic API 키 미설정 가드는 anthropic 모드에서만 의미가 있다. local 모드에서는
     * anthropic.api.key가 비어 있어도(또는 MOCK 값이어도) llmClient(OpenAiCompatibleLlmClient) 호출을
     * 막으면 안 되므로, 이 가드 앞에 이 메서드로 모드를 먼저 확인한다.
     */
    private boolean isAnthropicMode() {
        return llmProvider == null || "anthropic".equalsIgnoreCase(llmProvider.trim());
    }

    @Override
    public TokenUsage getTotalTokenUsage() {
        long input = accumulatedInputTokens.get();
        long output = accumulatedOutputTokens.get();
        TokenUsage usage = new TokenUsage(input, output, lastModelName);
        usage.setCacheReadTokens(accumulatedCacheReadTokens.get());
        usage.setCacheCreationTokens(accumulatedCacheCreationTokens.get());
        return usage;
    }

    @Override
    public void resetTokenUsage() {
        accumulatedInputTokens.set(0);
        accumulatedOutputTokens.set(0);
        accumulatedCacheReadTokens.set(0);
        accumulatedCacheCreationTokens.set(0);
    }

    @Override
    public String getCurrentModel() {
        // local 모드에서는 Anthropic 모델명을 반환하면 안 됨 — llmClient.call()에 그대로 넘어가
        // 자체 LLM 서버로 "claude-sonnet-4-6" 같은 존재하지 않는 모델명이 전송되는 버그를 방지
        if (!isAnthropicMode()) {
            return llmLocalModel;
        }
        return modelOverride != null ? modelOverride : apiModel;
    }

    @Override
    public void setModel(String model) {
        if (model == null || model.isBlank()) {
            this.modelOverride = null;
            return;
        }
        // 유효 모델만 허용
        if (SUPPORTED_MODELS.containsKey(model)) {
            this.modelOverride = model;
            log.info("[모델 변경] 선택된 모델: {}", model);
        } else {
            log.warn("[모델 변경 실패] 지원하지 않는 모델: {} - 기본값 유지", model);
        }
    }

    @Override
    public void setSessionSystemPrompt(String sourceFolderPath, String claudeMdContent) {
        if (sourceFolderPath == null || claudeMdContent == null || claudeMdContent.isBlank()) return;
        sessionSystemPrompts.put(sourceFolderPath, claudeMdContent);
    }

    @Override
    public void clearSessionSystemPrompt(String sourceFolderPath) {
        if (sourceFolderPath == null) return;
        sessionSystemPrompts.remove(sourceFolderPath);
    }

    @Override
    public String generateSessionClaudeMd(String customRequirements, Set<String> extensions) {
        String baseTemplate = loadBaseSystemPromptTemplate(extensions);
        boolean hasRequirements = customRequirements != null && !customRequirements.isBlank();

        // 추가 요구사항이 없으면 "표준 지침을 그대로 반환하라"고 LLM에 시킬 이유가 없다 —
        // 이미 원문(baseTemplate)을 그대로 갖고 있으니 그걸 쓰면 된다. 이 LLM 호출 자체를
        // 생략하면, 특히 소형 로컬 모델이 긴 문서를 "그대로 베끼라"는 지시를 못 지키고
        // 엉뚱한 걸 뱉는 실패 가능성이 원천 차단된다(2026-07-23 실측: 추가 요구사항 없이
        // 진행했는데도 CLAUDE.md 대신 prompt.md의 "## 응답 포맷" 예시(JSON 배열 반환 지시)를
        // 자기가 지금 수행할 지시로 착각해 가짜 분석 결과 JSON을 반환 — 그 결과가 세션 시스템
        // 프롬프트로 저장되어 이후 모든 파일 분석이 실제 코드와 무관한 출력을 냄).
        if (!hasRequirements) {
            return baseTemplate;
        }

        if (isAnthropicMode() && (apiKey == null || "MOCK_KEY_FOR_TEST".equals(apiKey) || apiKey.startsWith("MOCK") || apiKey.trim().isEmpty())) {
            log.warn("[CLAUDE.md 생성] API KEY 미설정으로 표준 템플릿을 그대로 사용합니다.");
            return baseTemplate;
        }

        String systemPrompt =
            "당신은 레거시 코드 분석 AI에게 내려줄 시스템 프롬프트(CLAUDE.md)를 작성하는 프롬프트 엔지니어입니다.\n" +
            "아래 '표준 기본 지침'의 구조(섹션 제목, 분석 철학, 주석 우선순위, 금지 패턴 등)를 최대한 유지하면서,\n" +
            "'추가 요구사항'을 반영해 관련 섹션을 보강하거나 새 섹션을 추가하여 최종 CLAUDE.md 문서를 작성하세요.\n" +
            "출력은 반드시 마크다운 지침 문서여야 합니다. '표준 기본 지침' 안에 담긴 예시나 응답 형식 지시문(예: " +
            "JSON 배열로 응답하라는 내용)은 어디까지나 '이 문서가 다른 AI에게 지시할 내용'일 뿐, 지금 이 요청에 대한 " +
            "당신의 응답 형식이 아닙니다. 절대 그 예시를 실행하거나 그 형식으로 응답하지 마세요.\n" +
            "마크다운 문서 본문만 출력하세요. 서두·인사말·설명·추가 정보 요청·JSON은 절대 금지입니다.";

        String userContent = "## 표준 기본 지침\n\n" + baseTemplate
            + "\n\n## 추가 요구사항 (사용자 지정)\n\n" + customRequirements;

        try {
            LlmResult result = llmClient.call(systemPrompt, userContent, getCurrentModel(), 4096);
            extractAndStoreTokenUsage(result);
            String generated = result.text();
            // 소형 로컬 모델은 이 생성 단계에서도 지침 문서 대신 다른 형식(JSON 배열/객체 등)을
            // 뱉어내는 경우가 있다. 명백히 마크다운 문서가 아니면 폐기하고 표준 템플릿으로
            // 안전하게 대체한다 — "추가 요구사항 반영 실패"가 "세션 전체 분석 품질 붕괴"로
            // 번지는 것을 막는 마지막 방어선이다.
            if (!looksLikeClaudeMd(generated)) {
                log.warn("[CLAUDE.md 생성 결과 형식 이상, 표준 템플릿으로 대체] 지침 문서 형식이 아닌 결과 수신 (길이={}자)",
                    generated == null ? 0 : generated.length());
                return baseTemplate;
            }
            log.info("[CLAUDE.md 생성 완료] 요구사항 반영={}, 길이={}자", hasRequirements, generated.length());
            return generated;
        } catch (Exception e) {
            log.warn("[CLAUDE.md 생성 API 호출 실패, 표준 템플릿 사용] {}", e.getMessage());
        }
        return baseTemplate;
    }

    // Phase 3.5(a) 검증 강화(2026-08-11, 2026-07-29 설계안 5절 리스크 대응): base의 핵심 섹션
    // 제목을 나타내는 키워드. customRequirements 병합용 시스템 프롬프트가 LLM에게 "표준 기본
    // 지침의 구조(섹션 제목 등)를 최대한 유지"하라고 지시하므로, 정상적인 결과라면 이 중
    // 과반수는 그대로 남아있어야 한다. 소형 로컬 모델이 "마크다운이긴 한데 지침 내용이 통째로
    // 빠진" 저품질 문서를 반환하는 경우(기존 JSON 시작 여부 검사만으로는 못 걸러냄)를 잡아낸다.
    private static final String[] CORE_SECTION_KEYWORDS = {
        "분석 철학", "주석 우선순위", "베끼기", "레거시 코드 특이사항", "주석 삽입 규칙", "응답 포맷"
    };

    /**
     * LLM이 생성한 결과가 CLAUDE.md(마크다운 지침 문서)처럼 보이는지 검증한다. 완벽한 검증은
     * 아니지만 두 가지 실측된 실패 패턴을 걸러내는 데는 충분하다: (1) JSON 배열/객체를 그대로
     * 반환(과거 장애 이력) — JSON으로 시작하면 즉시 거부. (2) 마크다운 형식은 갖췄지만 base의
     * 핵심 섹션 제목이 대부분 사라진 저품질 응답(2026-07-29 설계안 5절 신규 리스크) — 6개 핵심
     * 섹션 키워드 중 과반수(4개) 이상 남아있어야 통과시킨다.
     */
    private boolean looksLikeClaudeMd(String content) {
        if (content == null) return false;
        String trimmed = content.trim();
        if (trimmed.isEmpty()) return false;
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) return false;

        int matchedKeywords = 0;
        for (String keyword : CORE_SECTION_KEYWORDS) {
            if (trimmed.contains(keyword)) matchedKeywords++;
        }
        int required = (CORE_SECTION_KEYWORDS.length / 2) + 1;
        if (matchedKeywords < required) {
            log.warn("[CLAUDE.md 검증 실패] 핵심 섹션 키워드 {}개 중 {}개만 확인됨(최소 {}개 필요) — 표준 템플릿으로 대체",
                CORE_SECTION_KEYWORDS.length, matchedKeywords, required);
            return false;
        }
        return true;
    }

    /**
     * 세션 전용 CLAUDE.md가 등록되어 있으면 그것을, 없으면 base+role(이 파일 확장자 1개) 병합
     * 템플릿을 시스템 프롬프트로 사용한다(Phase 2, 2026-08-11, 설계안 4.1절). 정상 흐름에서는
     * 세션 시작 시 generateSessionClaudeMd(role N개 병합 포함)가 항상 먼저 호출되어 캐시에
     * 저장되므로, 여기서 쓰는 폴백 경로는 세션 CLAUDE.md가 없는 예외 상황을 위한 안전망이다
     * (설계안 4.2절 — Phase 3이 실제 체감 효과의 주력, 여기는 그 폴백).
     */
    private String resolveSystemPrompt(String sourceFolderPath, String extension) {
        String sessionPrompt = sourceFolderPath != null ? sessionSystemPrompts.get(sourceFolderPath) : null;
        return sessionPrompt != null ? sessionPrompt : loadSystemPromptTemplate(extension);
    }

    /**
     * base(prompt-base.md) + 이 파일 확장자 1개에 매칭되는 role 파일을 병합한 시스템 프롬프트
     * 템플릿을 로드한다(Phase 2, 설계안 4.1절). 파일별 분석은 한 번에 확장자 1개만 다루므로
     * (혼합 확장자 배치가 발생하지 않음 — 파일/청크 단위 개별 호출 확인됨) role은 최대 1개만
     * 병합하면 충분하다. 실제 병합 로직은 세션 CLAUDE.md 생성 경로(generateSessionClaudeMd)와
     * 동일한 loadBaseSystemPromptTemplate(Set)을 재사용해 병합 규칙이 두 경로에서 갈라지지 않게 한다.
     */
    private String loadSystemPromptTemplate(String extension) {
        Set<String> extensions = (extension == null || extension.isBlank()) ? Set.of() : Set.of(extension);
        return loadBaseSystemPromptTemplate(extensions);
    }

    /**
     * base(prompt-base.md) 템플릿을 로드하고, extensions에 매칭되는 role 파일(있으면 전부)을 base
     * 안의 {{ROLE_CONTENT}} 마커 위치에 병합해 반환한다. extensions가 null/빈 집합이거나 매칭되는
     * role이 하나도 없으면(예: .gradle/.properties/.yml 등) base만 적용한다 — 2026-07-29 설계안
     * 3.3절 결정(미매칭 확장자 폴백: base만 적용, role 없이 진행).
     * 리소스 폴더에 base 파일이 없거나 비어있으면 간소화된 기본 지침 내용을 반환합니다
     * (analyzeCodeWithClaude 호출 자체는 실패하지 않도록 함).
     */
    private String loadBaseSystemPromptTemplate(Set<String> extensions) {
        // [레거시 시스템 분석 전문가 프롬프트 - 간소화]
        String defaultTemplate = """
                레거시 시스템 분석가: 파일(fileName, 확장자: ${extension})의 비즈니스 로직만 한글 주석으로 설명하자.

                주석 우선순위:
                1. 비즈니스 로직 (왜 존재하는가?)
                2. 복잡한 알고리즘 (의도와 흐름)
                3. API/DB 접근 (의존성)
                4. 예외 처리 (대응 방법)

                규칙: 기존 주석 100% 보존, 새 주석은 하단/옆에 추가

                ⚠️ 파일별 주석 형식 (매우 중요):
                - Java: // 또는 /** */
                - JavaScript: // 또는 /* */
                - Python: #
                - HTML:
                  * <script>...</script> 내부: // 또는 /* */
                  * <style>...</style> 내부: /* */
                  * HTML 본문(태그 사이): <!-- --> (절대 // 금지)
                - XML/JSX: <!-- -->

                응답: 반드시 JSON 배열 형식으로만 응답. 각 객체는 반드시 lineNumber와 comment를 포함:
                [
                  {"lineNumber": 1, "comment": "주석 내용"},
                  {"lineNumber": 5, "comment": "주석 내용"}
                ]
                주의: 마크다운 또는 다른 형식은 절대 금지. JSON만 반환.""";

        String base = loadResourceFile(systemPromptFilename);
        if (base == null || base.trim().isEmpty()) {
            log.warn("[{} 없음] 간소화된 기본 지침으로 대체합니다.", systemPromptFilename);
            return defaultTemplate;
        }

        return mergeRoleContent(base, loadRoleContent(extensions));
    }

    /**
     * extensions에 매칭되는 role 파일들을 전부 로드해 하나로 합친다(세션 CLAUDE.md 생성 경로처럼
     * N개 확장자를 동시에 병합해야 하는 경우 대비). 매칭되는 role이 없으면 빈 문자열을 반환한다.
     */
    private String loadRoleContent(Set<String> extensions) {
        if (extensions == null || extensions.isEmpty()) return "";

        // 같은 role 파일이 여러 확장자에 매핑될 수 있으므로(.js/.ts/.jsx/.tsx → role-js.md 전부 동일)
        // LinkedHashSet으로 중복 로드를 막고 매핑 순서(ROLE_FILE_BY_EXTENSION 삽입 순)를 보존한다.
        Set<String> roleFiles = new LinkedHashSet<>();
        for (String ext : extensions) {
            String roleFile = ext == null ? null : ROLE_FILE_BY_EXTENSION.get(ext.toLowerCase());
            if (roleFile != null) roleFiles.add(roleFile);
        }
        if (roleFiles.isEmpty()) return "";

        StringBuilder merged = new StringBuilder();
        for (String roleFile : roleFiles) {
            String content = loadResourceFile(roleFile);
            if (content == null || content.isBlank()) {
                log.warn("[role 파일 없음] {} — 건너뜁니다.", roleFile);
                continue;
            }
            if (merged.length() > 0) merged.append("\n\n");
            merged.append(content.trim());
        }
        return merged.toString();
    }

    /**
     * base 템플릿의 {{ROLE_CONTENT}} 마커를 실제 role 콘텐츠로 치환한다. 마커를 base 중간(베끼기
     * 금지 경고 뒤, 도메인 용어/응답 포맷 앞)에 둔 이유는 role 병합 여부와 무관하게 "응답 포맷(JSON)"
     * 섹션이 항상 프롬프트 맨 끝에 오도록 하기 위함이다(2026-08-11 결정 — 단순 base+role 이어붙이기는
     * role 내용이 JSON 응답 포맷 뒤로 밀려 순서가 깨지므로 채택하지 않음).
     */
    private String mergeRoleContent(String base, String roleContent) {
        String merged = (roleContent == null || roleContent.isEmpty())
            ? base.replace(ROLE_CONTENT_MARKER, "")
            : base.replace(ROLE_CONTENT_MARKER, roleContent);
        // 마커 제거/치환 과정에서 생기는 빈 줄 뭉침만 정리(내용 자체에는 영향 없음)
        return merged.replaceAll("\n{3,}", "\n\n");
    }

    /** 클래스패스 리소스 파일(UTF-8 텍스트)을 문자열로 읽는다. 파일이 없거나 읽기 실패 시 null. */
    private String loadResourceFile(String filename) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(filename)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("{} 로드 중 예외 발생", filename, e);
            return null;
        }
    }

    /**
     * 중복 코드 제거를 위한 함수
     */
    private boolean isXmlFamily(String extension) {
        return ".html".equals(extension) || ".xml".equals(extension) || ".xfdl".equals(extension);
    }

    /**
     * `#` 한 줄 주석만 허용하는 파일군. role-properties.md/role-yaml.md 신설(Phase 1.5,
     * 2026-08-11)로 Properties/YAML도 Python과 동일하게 `#` 스타일 강제가 필요해졌다 —
     * 이 분기가 없으면 normalizeComment()가 이 파일들을 "마커 없는 텍스트"로 오인해
     * `//` 접두어를 붙여버리는데, `//`는 Properties/YAML 어느 쪽에서도 유효한 주석 마커가
     * 아니라서 실제 분석 결과 파일에 문법상 잘못된 주석이 삽입되는 문제가 있었다.
     */
    private boolean isHashCommentFamily(String extension) {
        return ".py".equals(extension) || ".properties".equals(extension)
            || ".yml".equals(extension) || ".yaml".equals(extension);
    }

    /**
     * `/* ... *&#47;` 블록 주석만 허용하는 파일군(role-css.md 신설, 2026-08-11). 표준 CSS는
     * `//` 한 줄 주석을 지원하지 않는다 — 지원하지 않는 문법을 만나면 파서에 따라 그 줄(또는
     * 그 뒤 규칙까지)이 통째로 무시되거나 깨질 수 있어, properties/yaml과 같은 이유로 별도
     * 처리가 필요하다.
     */
    private boolean isCssFamily(String extension) {
        return ".css".equals(extension);
    }

    /**
     * `//` 스타일 또는 마커 없는 텍스트를 CSS 표준 `/* ... *&#47;` 블록 주석으로 변환한다.
     * 한 줄만 있으면 한 줄 블록으로, 여러 줄이면 XML 계열과 동일한 스타일로 여러 줄 블록으로 감싼다.
     */
    private String toCssBlockComment(String rawText) {
        String[] lines = rawText.split("\n");
        List<String> content = new ArrayList<>();
        for (String line : lines) {
            String l = line.trim();
            if (l.startsWith("//")) l = l.substring(2).trim();
            if (!l.isEmpty()) content.add(l);
        }
        if (content.isEmpty()) return "/* */";
        if (content.size() == 1) return "/* " + content.get(0) + " */";
        StringBuilder fixed = new StringBuilder("/*\n");
        for (String l : content) fixed.append(l).append("\n");
        fixed.append("*/");
        return fixed.toString();
    }

    /**
     * JSON 배열 형식 자동 복구: 잘못된 객체 구조를 수정합니다.
     * 예: "lineNumber": N, "comment": "..." → {"lineNumber": N, "comment": "..."}
     */
    private String repairJsonArray(String jsonStr) {
        try {
            // 먼저 정상 JSON 파싱 시도
            try {
                List<?> list = mapper.readValue(jsonStr, List.class);
                return jsonStr;
            } catch (Exception ignored) {
                // 정상 JSON이 아니면 복구 로직 실행
            }

            // 배열 경계 추출
            int startIdx = jsonStr.indexOf('[');
            int endIdx = jsonStr.lastIndexOf(']');
            if (startIdx == -1 || endIdx == -1 || startIdx >= endIdx) {
                log.debug("[JSON] 배열 경계를 찾을 수 없어 원본 반환");
                return jsonStr;
            }

            String content = jsonStr.substring(startIdx + 1, endIdx).trim();
            if (content.isEmpty()) {
                return "[]";
            }

            List<Map<String, Object>> items = new ArrayList<>();

            // 1차 시도: 완전한 JSON 객체 추출 {lineNumber: ..., comment: ...}
            java.util.regex.Pattern objectPattern = java.util.regex.Pattern.compile(
                "\\{[^}]*?\"?lineNumber\"?\\s*:\\s*(\\d+)[^}]*?\"?comment\"?\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*?)\"[^}]*?\\}",
                java.util.regex.Pattern.DOTALL
            );
            java.util.regex.Matcher objectMatcher = objectPattern.matcher(content);

            while (objectMatcher.find()) {
                try {
                    int lineNumber = Integer.parseInt(objectMatcher.group(1));
                    String comment = objectMatcher.group(2)
                        .replace("\\\"", "\"")
                        .replace("\\n", "\n")
                        .replace("\\\\", "\\");

                    Map<String, Object> item = new HashMap<>();
                    item.put("lineNumber", lineNumber);
                    item.put("comment", comment);
                    items.add(item);
                } catch (Exception e) {
                    log.debug("[JSON 복구] 객체 파싱 실패: {}", e.getMessage());
                }
            }

            // 2차 시도: 분리된 lineNumber와 comment 쌍 추출
            if (items.isEmpty()) {
                java.util.regex.Pattern linePattern = java.util.regex.Pattern.compile(
                    "\"?lineNumber\"?\\s*:\\s*(\\d+)"
                );
                java.util.regex.Pattern commentPattern = java.util.regex.Pattern.compile(
                    "\"?comment\"?\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*?)\""
                );

                java.util.regex.Matcher lineMatcher = linePattern.matcher(content);
                java.util.regex.Matcher commentMatcher = commentPattern.matcher(content);

                List<Integer> lineNumbers = new ArrayList<>();
                List<String> comments = new ArrayList<>();

                while (lineMatcher.find()) {
                    lineNumbers.add(Integer.parseInt(lineMatcher.group(1)));
                }

                while (commentMatcher.find()) {
                    String comment = commentMatcher.group(1)
                        .replace("\\\"", "\"")
                        .replace("\\n", "\n")
                        .replace("\\\\", "\\");
                    comments.add(comment);
                }

                // lineNumber와 comment의 개수가 같으면 쌍으로 묶기
                int pairCount = Math.min(lineNumbers.size(), comments.size());
                for (int i = 0; i < pairCount; i++) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("lineNumber", lineNumbers.get(i));
                    item.put("comment", comments.get(i));
                    items.add(item);
                }
            }

            if (items.isEmpty()) {
                log.debug("[JSON] 파싱 가능한 항목 없음. 원본 반환");
                return jsonStr;
            }

            String result = mapper.writeValueAsString(items);
            log.debug("[JSON] 손상된 데이터 복구 완료 ({}개 항목)", items.size());
            return result;

        } catch (Exception e) {
            log.debug("[JSON] 복구 실패로 원본 반환: {}", e.getMessage());
            return jsonStr;
        }
    }

    /**
     * [세부 지침 파일 로드]: 프로젝트별 특수 상세 지침 텍스트를 안전하게 가져옵니다.
     */
    private String loadCustomSpec(String extension) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(customSpecFilename)) {
            if (is == null) {
                if (".py".equals(extension)) return "# [안내] 별도의 프로젝트 세부 상세 지침 규칙이 지정되지 않았습니다. 기본 규칙으로 분석합니다.";
                if (isXmlFamily(extension)) {
                    return "<!-- [안내] 별도의 프로젝트 세부 상세 지침 규칙이 지정되지 않았습니다. 기본 규칙으로 분석합니다. -->";
                }
                return "// [안내] 별도의 프로젝트 세부 상세 지침 규칙이 지정되지 않았습니다. 기본 규칙으로 분석합니다.";
            }
            byte[] bytes = is.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("특수 지침 파일(custom_spec.txt) 로드 중 실패", e);
            if (".py".equals(extension)) return "# [경고] 규칙 파일 읽기 실패: " + e.getMessage();
            if (isXmlFamily(extension)) {
                return "<!-- [경고] 규칙 파일 읽기 실패: " + e.getMessage() + " -->";
            }
            return "// [경고] 규칙 파일 읽기 실패: " + e.getMessage();
        }
    }

    /**
     * 메인 비즈니스 분석 로직이 비대해지지 않도록 가상 시뮬레이션용 응답 지도를 가공해 주는
     * 독립된 전용 MOCK 가동 메서드로 정밀하게 격리 추출(Extract Method)했습니다.
     */
    private String generateMockResponse(String fileName, String extension) {
        String mockComment1 = " 확장자(" + extension + ") 맞춤형 자동 생성 목업 주석 예시 1";
        String mockComment2 = " 분석 대상 파일명: " + fileName;
        String mockCmtStyle1, mockCmtStyle2;

        if (".py".equals(extension)) {
            mockCmtStyle1 = "#" + mockComment1; mockCmtStyle2 = "#" + mockComment2;
        } else if (isXmlFamily(extension)) {
            mockCmtStyle1 = "<!--" + mockComment1 + " -->"; mockCmtStyle2 = "<!--" + mockComment2 + " -->";
        } else {
            mockCmtStyle1 = "//" + mockComment1; mockCmtStyle2 = "//" + mockComment2;
        }

        return "[\n" +
                "  {\"lineNumber\": 1, \"comment\": \"" + mockCmtStyle1 + "\"},\n" +
                "  {\"lineNumber\": 5, \"comment\": \"" + mockCmtStyle2 + "\"}\n" +
                "]";
    }

    @Override
    public String analyzeCodeWithClaude(String sourceCode, String fileName, String sourceFolderPath) {
        String extension = "";
        int i = fileName.lastIndexOf('.');
        if (i > 0) {
            extension = fileName.substring(i).toLowerCase();
        }

        // 첫 호출 시 API KEY 상태 로깅 (Debug 용도)
        log.info("[API KEY 상태] apiKey={}, isEmpty={}",
            (apiKey == null ? "NULL" : (apiKey.isEmpty() ? "EMPTY" : "설정됨(" + apiKey.length() + "자)")),
            apiKey == null || apiKey.trim().isEmpty());

        // README.md: Claude AI로 실제 프로젝트 분석 보고서 생성
        if ("README.md".equalsIgnoreCase(fileName) || "README_AI_SUMMARY.md".equalsIgnoreCase(fileName)) {
            if (isAnthropicMode() && (apiKey == null || "MOCK_KEY_FOR_TEST".equals(apiKey) || apiKey.startsWith("MOCK") || apiKey.trim().isEmpty())) {
                throw new AnalysisException(ApiErrorHandler.ErrorType.API_AUTHENTICATION,
                    new RuntimeException("Claude API KEY가 설정되지 않았습니다. application.properties를 확인하세요."));
            }
            return generateProjectReadmeWithClaude(sourceCode, sourceFolderPath);
        }

        String customSpecData = loadCustomSpec(extension);

        if (isAnthropicMode() && (apiKey == null || "MOCK_KEY_FOR_TEST".equals(apiKey) || apiKey.startsWith("MOCK") || apiKey.trim().isEmpty())) {
            // API KEY 미설정 시 파일을 수정하지 않고 예외 발생 (원본 보호)
            log.warn("[API KEY 미설정] 파일 처리 건너뜀: {}", fileName);
            throw new AnalysisException(ApiErrorHandler.ErrorType.API_AUTHENTICATION,
                new RuntimeException("Claude API KEY가 설정되지 않았습니다. application.properties를 확인하세요."));
        }

        String baseSystemPrompt = resolveSystemPrompt(sourceFolderPath, extension);

        String finalSystemPrompt = baseSystemPrompt
                .replace("${fileName}", fileName)
                .replace("${extension}", extension)
                .replace("${customSpecData}", customSpecData);

        String userContent = "파일명: " + fileName + "\n\n[소스 코드]:\n" + sourceCode +
                "\n\n⚠️ 절대 중요: 다음 JSON 배열 형식으로만 응답하세요. 마크다운(```), 설명, 쉼표 오류 금지:\n" +
                "[{\"lineNumber\": 숫자, \"comment\": \"내용\"}, {\"lineNumber\": 숫자, \"comment\": \"내용\"}]\n" +
                "- 각 객체는 { }로 완전히 감싸기\n" +
                "- 객체 사이에 쉼표(,) 필수\n" +
                "- JSON 외의 모든 텍스트 금지";

        // 설정값에서 재시도 정책 로드
        int maxRetries = sessionConfig.getMaxRetries();
        long initialRetryDelay = sessionConfig.getInitialRetryDelayMs();
        long maxRetryDelay = sessionConfig.getMaxRetryDelayMs();

        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                LlmResult result = llmClient.call(finalSystemPrompt, userContent, getCurrentModel(), apiMaxTokens);
                String aiJsonResponse = result.text();

                // 토큰 사용량 추출 및 저장
                extractAndStoreTokenUsage(result);

                log.info("[API 분석 성공] 파일명: {}", fileName);
                return mergeCommentsIntoCode(sourceCode, aiJsonResponse, extension);

            } catch (AnalysisException ae) {
                // 이미 분류된 예외는 그대로 재발생
                throw ae;
            } catch (Exception e) {
                // HTTP 상태 코드 추출 (WebClientResponseException인 경우)
                int httpStatus = 0;
                if (e instanceof WebClientResponseException wce) {
                    httpStatus = wce.getStatusCode().value();
                    try {
                        log.error("[API 응답 상세] statusCode={}, body={}",
                            httpStatus, wce.getResponseBodyAsString());
                    } catch (Exception ignored) {}
                }
                log.error("[예외 발생] 예외 타입: {}, HTTP상태: {}, 메시지: {}",
                    e.getClass().getSimpleName(), httpStatus, e.getMessage());

                // 에러 분류 (HTTP 상태 코드 반영)
                ApiErrorHandler.ErrorType errorType = apiErrorHandler.classifyError(e, httpStatus);

                // 재시도 불가능한 에러는 즉시 예외 발생 (파일에 오류 텍스트 기록 방지)
                if (!apiErrorHandler.isRetryable(errorType)) {
                    String userMsg = apiErrorHandler.getUserFriendlyMessage(errorType, fileName);
                    apiErrorHandler.logError(errorType, fileName, e, retry, false);
                    log.error("[비복구 오류 - 파일 처리 중단] {}", userMsg);
                    throw new AnalysisException(errorType, e);
                }

                // 재시도 대기 시간 계산
                boolean isLastAttempt = (retry == maxRetries - 1);
                if (!isLastAttempt) {
                    long retryDelay = apiErrorHandler.calculateRetryDelay(errorType, retry,
                        initialRetryDelay, maxRetryDelay);

                    String userMsg = apiErrorHandler.getUserFriendlyMessage(errorType, fileName);
                    apiErrorHandler.logError(errorType, fileName, e, retry + 1, true);
                    log.info("[자동 재시도] {} | {}ms 대기 후 {}회차 시도", userMsg, retryDelay, retry + 2);

                    try {
                        Thread.sleep(retryDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new AnalysisException(ApiErrorHandler.ErrorType.UNKNOWN_ERROR, ie);
                    }
                } else {
                    // 최종 재시도 실패 - 예외 발생 (파일에 오류 텍스트 기록 방지)
                    String userMsg = apiErrorHandler.getUserFriendlyMessage(errorType, fileName);
                    apiErrorHandler.logError(errorType, fileName, e, maxRetries, false);
                    log.error("[최대 재시도 초과 - 파일 처리 중단] {} | 파일: {}", userMsg, fileName);
                    throw new AnalysisException(errorType, e);
                }
            }
        }

        throw new AnalysisException(ApiErrorHandler.ErrorType.UNKNOWN_ERROR,
            new RuntimeException("알 수 없는 런타임 오류: 재시도 루프 이탈"));
    }

    /**
     * Claude AI로 프로젝트 패키지 구조를 분석하여 고객 납품용 기술 인수인계 README를 생성한다.
     * 응답은 마크다운 텍스트로 직접 반환 (JSON 주석 배열 아님).
     */
    private String generateProjectReadmeWithClaude(String projectStructure, String sourceFolderPath) {
        String projectName = "레거시 프로젝트";
        try {
            if (sourceFolderPath != null && !sourceFolderPath.isBlank()) {
                java.io.File dir = new java.io.File(sourceFolderPath);
                if (!dir.getName().isBlank()) projectName = dir.getName();
            }
        } catch (Exception ignored) {}

        String projectType = projectTypeDetector.detectProjectType(sourceFolderPath);
        String systemPrompt = switch (projectType) {
            case "java" -> buildJavaReadmeSystemPrompt();
            case "react", "nextjs", "vue" -> buildFrontendReadmeSystemPrompt();
            case "python" -> buildPythonReadmeSystemPrompt();
            default -> buildGeneralReadmeSystemPrompt();
        };

        String userContent = "프로젝트명: " + projectName + "\n\n" + projectStructure;

        try {
            LlmResult result = llmClient.call(systemPrompt, userContent, getCurrentModel(), 4096);
            extractAndStoreTokenUsage(result);
            log.info("[README 생성 완료] 프로젝트: {}, 길이: {}자", projectName, result.text().length());
            return result.text();
        } catch (Exception e) {
            log.warn("[README API 호출 실패, 폴백 사용] {}", e.getMessage());
        }
        // API 실패 시 패키지 구조 기반 폴백 README 생성
        return buildFallbackReadme(projectName, projectStructure, projectType);
    }

    /** Java(Spring) 프로젝트용 README 시스템 프롬프트 (기존 프롬프트 그대로 유지) */
    private String buildJavaReadmeSystemPrompt() {
        return "당신은 레거시 소프트웨어를 분석하는 시니어 SW 아키텍트입니다.\n" +
            "제공된 프로젝트 패키지 구조와 파일 목록만으로 완전한 기술 인수인계 문서를 작성하세요.\n" +
            "추가 정보를 요청하거나 '정보를 제공해 주세요'라는 문구는 절대 쓰지 마세요.\n" +
            "파일명·패키지명으로 최대한 유추하여 구체적으로 작성하세요.\n\n" +
            "## 각 섹션 작성 기준\n" +
            "- **시스템 개요**: 프로젝트명, 추정 도메인(패키지명 기준), 주요 기능 2~3문장 요약\n" +
            "- **아키텍처 구조**: Controller→Service→Repository→DB 레이어 흐름 설명.\n" +
            "  각 레이어에 해당하는 실제 패키지/클래스명을 명시할 것\n" +
            "- **도메인별 기능 분석**: 비즈니스 도메인(auth/order/payment 등)별로\n" +
            "  무슨 업무를 처리하는지 구체적으로 설명. 실제 클래스명 언급 필수\n" +
            "- **계층별 역할 정의**: Controller/Service/Repository/Entity 각 계층의\n" +
            "  책임과 해당 실제 클래스 목록을 함께 작성\n" +
            "- **기술 스택**: 파일 목록에서 실제로 확인되는 기술만 작성\n" +
            "  (pom.xml/build.gradle/package.json/설정파일 기반)\n" +
            "- **인수인계 체크리스트**: 이 프로젝트에 특화된 확인 항목만 작성\n" +
            "  (일반적인 항목 금지, 실제 파일명·패키지명 포함)\n\n" +
            "## 필수 출력 형식 (반드시 이 구조로 작성)\n" +
            "## 시스템 개요\n" +
            "## 아키텍처 구조\n" +
            "### 레이어 흐름\n" +
            "### 주요 패키지 역할\n" +
            "## 도메인별 기능 분석\n" +
            "## 계층별 역할 정의\n" +
            "### " + LayerLabels.JAVA[0] + "\n" +
            "### " + LayerLabels.JAVA[1] + "\n" +
            "### " + LayerLabels.JAVA[2] + "\n" +
            "### " + LayerLabels.JAVA[3] + "\n" +
            "## 기술 스택\n" +
            "## 인수인계 주요 체크리스트\n\n" +
            "마크다운만 출력하세요. 서두·인사말·추가 설명·정보 요청은 절대 금지입니다.";
    }

    /** React/Next.js/Vue 프론트엔드 프로젝트용 README 시스템 프롬프트 */
    private String buildFrontendReadmeSystemPrompt() {
        return "당신은 레거시 소프트웨어를 분석하는 시니어 프론트엔드 아키텍트입니다.\n" +
            "제공된 프로젝트 디렉토리 구조와 파일 목록만으로 완전한 기술 인수인계 문서를 작성하세요.\n" +
            "이 프로젝트는 프론트엔드(React/Next.js/Vue) 프로젝트입니다. Controller/Service/Repository 같은\n" +
            "백엔드 레이어드 아키텍처 용어는 절대 사용하지 마세요.\n" +
            "추가 정보를 요청하거나 '정보를 제공해 주세요'라는 문구는 절대 쓰지 마세요.\n" +
            "파일명·디렉토리명으로 최대한 유추하여 구체적으로 작성하세요.\n\n" +
            "## 각 섹션 작성 기준\n" +
            "- **시스템 개요**: 프로젝트명, 사용 프레임워크(React/Next.js/Vue), 주요 화면·기능 2~3문장 요약\n" +
            "- **아키텍처 구조**: Browser → Router/Pages → Components → State/API 흐름 설명.\n" +
            "  각 단계에 해당하는 실제 디렉토리/파일명을 명시할 것\n" +
            "- **도메인별 기능 분석**: 화면·기능 단위(로그인/대시보드/설정 등)로\n" +
            "  무슨 화면을 어떻게 구성하는지 구체적으로 설명. 실제 파일명 언급 필수\n" +
            "- **계층별 역할 정의**: " + String.join(", ", LayerLabels.FRONTEND) + " 각 계층의\n" +
            "  역할과 해당 실제 파일 목록을 함께 작성\n" +
            "- **기술 스택**: 파일 목록에서 실제로 확인되는 기술만 작성\n" +
            "  (package.json 의존성, 상태관리 라이브러리, 스타일링 방식 기반)\n" +
            "- **인수인계 체크리스트**: 이 프로젝트에 특화된 확인 항목만 작성\n" +
            "  (일반적인 항목 금지, 실제 파일명·디렉토리명 포함)\n\n" +
            "## 필수 출력 형식 (반드시 이 구조로 작성)\n" +
            "## 시스템 개요\n" +
            "## 아키텍처 구조\n" +
            "### 화면 흐름\n" +
            "### 주요 디렉토리 역할\n" +
            "## 도메인별 기능 분석\n" +
            "## 계층별 역할 정의\n" +
            "### " + LayerLabels.FRONTEND[0] + "\n" +
            "### " + LayerLabels.FRONTEND[1] + "\n" +
            "### " + LayerLabels.FRONTEND[2] + "\n" +
            "### " + LayerLabels.FRONTEND[3] + "\n" +
            "## 기술 스택\n" +
            "## 인수인계 주요 체크리스트\n\n" +
            "마크다운만 출력하세요. 서두·인사말·추가 설명·정보 요청은 절대 금지입니다.";
    }

    /** Python 프로젝트용 README 시스템 프롬프트 */
    private String buildPythonReadmeSystemPrompt() {
        return "당신은 레거시 소프트웨어를 분석하는 시니어 SW 아키텍트입니다.\n" +
            "제공된 프로젝트 디렉토리 구조와 파일 목록만으로 완전한 기술 인수인계 문서를 작성하세요.\n" +
            "이 프로젝트는 Python 프로젝트(Django/FastAPI/Flask 등)입니다.\n" +
            "추가 정보를 요청하거나 '정보를 제공해 주세요'라는 문구는 절대 쓰지 마세요.\n" +
            "파일명·디렉토리명으로 최대한 유추하여 구체적으로 작성하세요.\n\n" +
            "## 각 섹션 작성 기준\n" +
            "- **시스템 개요**: 프로젝트명, 사용 프레임워크, 주요 기능 2~3문장 요약\n" +
            "- **아키텍처 구조**: Client → URL Router → View/Service → Model/ORM → DB 흐름 설명.\n" +
            "  각 단계에 해당하는 실제 디렉토리/파일명을 명시할 것\n" +
            "- **도메인별 기능 분석**: 비즈니스 도메인별로 무슨 업무를 처리하는지\n" +
            "  구체적으로 설명. 실제 파일명 언급 필수\n" +
            "- **계층별 역할 정의**: " + String.join(", ", LayerLabels.PYTHON) + " 각 계층의\n" +
            "  책임과 해당 실제 파일 목록을 함께 작성\n" +
            "- **기술 스택**: 파일 목록에서 실제로 확인되는 기술만 작성\n" +
            "  (requirements.txt/pyproject.toml/설정파일 기반)\n" +
            "- **인수인계 체크리스트**: 이 프로젝트에 특화된 확인 항목만 작성\n" +
            "  (일반적인 항목 금지, 실제 파일명·디렉토리명 포함)\n\n" +
            "## 필수 출력 형식 (반드시 이 구조로 작성)\n" +
            "## 시스템 개요\n" +
            "## 아키텍처 구조\n" +
            "### 요청 흐름\n" +
            "### 주요 디렉토리 역할\n" +
            "## 도메인별 기능 분석\n" +
            "## 계층별 역할 정의\n" +
            "### " + LayerLabels.PYTHON[0] + "\n" +
            "### " + LayerLabels.PYTHON[1] + "\n" +
            "### " + LayerLabels.PYTHON[2] + "\n" +
            "### " + LayerLabels.PYTHON[3] + "\n" +
            "## 기술 스택\n" +
            "## 인수인계 주요 체크리스트\n\n" +
            "마크다운만 출력하세요. 서두·인사말·추가 설명·정보 요청은 절대 금지입니다.";
    }

    /** Java/프론트엔드/Python 어디에도 해당하지 않는 프로젝트용 README 시스템 프롬프트 */
    private String buildGeneralReadmeSystemPrompt() {
        return "당신은 레거시 소프트웨어를 분석하는 시니어 SW 아키텍트입니다.\n" +
            "제공된 파일 확장자 통계와 최상위 폴더 구조만으로 완전한 기술 인수인계 문서를 작성하세요.\n" +
            "이 프로젝트는 특정 프레임워크로 분류되지 않았습니다. Controller/Service/Repository 같은\n" +
            "특정 아키텍처 패턴을 강제로 갖다 붙이지 말고, 실제 존재하는 확장자·폴더 구성을 근거로만 서술하세요.\n" +
            "추가 정보를 요청하거나 '정보를 제공해 주세요'라는 문구는 절대 쓰지 마세요.\n\n" +
            "## 각 섹션 작성 기준\n" +
            "- **시스템 개요**: 프로젝트명, 확장자 통계로 추정되는 기술 스택, 2~3문장 요약\n" +
            "- **아키텍처 구조**: 실제 존재하는 최상위 폴더/파일 그룹을 기준으로 구조를 설명\n" +
            "- **도메인별 기능 분석**: 최상위 폴더 단위로 무엇을 담당하는지 설명. 실제 파일명 언급 필수\n" +
            "- **계층별 역할 정의**: 제공된 확장자·폴더 통계를 근거로 실제 존재하는 그룹 3~5개만 자유 서식으로 서술\n" +
            "  (없는 계층을 지어내지 말 것)\n" +
            "- **기술 스택**: 파일 목록에서 실제로 확인되는 기술만 작성\n" +
            "- **인수인계 체크리스트**: 이 프로젝트에 특화된 확인 항목만 작성\n\n" +
            "## 필수 출력 형식 (반드시 이 구조로 작성, ### 하위 제목은 자유 서식)\n" +
            "## 시스템 개요\n" +
            "## 아키텍처 구조\n" +
            "## 도메인별 기능 분석\n" +
            "## 계층별 역할 정의\n" +
            "## 기술 스택\n" +
            "## 인수인계 주요 체크리스트\n\n" +
            "마크다운만 출력하세요. 서두·인사말·추가 설명·정보 요청은 절대 금지입니다.";
    }

    /** API 호출 실패 시 패키지 구조 데이터로 기본 README 생성 (프로젝트 타입별 문구 분기) */
    private String buildFallbackReadme(String projectName, String projectStructure, String projectType) {
        String archNote = switch (projectType) {
            case "react", "nextjs", "vue" -> "Pages/Components/Hooks/API 등 프론트엔드 디렉토리 구조를 확인하세요.";
            case "python" -> "View/Service/Model 등 Python 모듈 구조를 확인하세요.";
            case "java" -> "Controller / Service / Repository 패턴이 적용된 경우 각 레이어의 역할을 확인하세요.";
            default -> "아래 확장자·폴더 통계를 참고해 실제 구성을 확인하세요.";
        };
        String componentNote = switch (projectType) {
            case "react", "nextjs", "vue" -> "- `*.tsx` / `*.jsx` / `*.vue` : 화면 컴포넌트\n" +
                "- `hooks/`, `store/`, `context/` : 상태 관리\n" +
                "- `api/`, `services/` : 서버 통신\n" +
                "- `pages/`, `app/`, `router/` : 화면 라우팅\n";
            case "python" -> "- `views.py` / `routers/` : 요청 처리\n" +
                "- `*_service.py` / `services/` : 비즈니스 로직\n" +
                "- `models.py` / `schemas.py` : 데이터 모델\n";
            case "java" -> "- `*Controller.java` : REST API 엔드포인트 (요청 수신 및 응답 처리)\n" +
                "- `*Service.java` : 비즈니스 로직 처리\n" +
                "- `*Repository.java` : 데이터베이스 접근 (CRUD)\n" +
                "- `*Entity.java` / `*DTO.java` : 데이터 모델\n";
            default -> "위 파일 목록을 참고해 코드베이스의 실제 구성을 확인하세요.\n";
        };
        String checklistNote = switch (projectType) {
            case "react", "nextjs", "vue" -> "- [ ] package.json 의존성 및 빌드 스크립트 확인\n" +
                "- [ ] 라우팅 구조 파악 (pages/app/router)\n" +
                "- [ ] 상태 관리 방식 확인 (hooks/store/context)\n" +
                "- [ ] API 연동 지점 확인 (api/services)\n";
            case "python" -> "- [ ] requirements.txt / pyproject.toml 의존성 확인\n" +
                "- [ ] URL 라우팅 구조 파악\n" +
                "- [ ] 데이터 모델(ORM) 구조 확인\n";
            case "java" -> "- [ ] 빌드 도구 및 의존성 확인 (pom.xml / build.gradle)\n" +
                "- [ ] 데이터베이스 연결 설정 확인 (application.properties)\n" +
                "- [ ] 주요 비즈니스 로직 흐름 파악 (Service 계층 중심)\n";
            default -> "- [ ] 빌드/설정 파일 확인\n" +
                "- [ ] 주요 폴더별 역할 파악\n";
        };

        return "## 시스템 개요\n\n" +
            "**프로젝트명**: " + projectName + "\n\n" +
            "본 문서는 레거시 코드 자동 분석 시스템이 수집한 프로젝트 구조 정보를 기반으로 생성된 기술 인수인계 문서입니다.\n" +
            "(AI 분석 생성 실패 — 아래 구조 정보를 참고하세요)\n\n" +
            "## 아키텍처 구조\n\n" +
            "소스 파일 목록 기반으로 파악된 구조입니다.\n" +
            archNote + "\n\n" +
            "## 패키지별 기능 설명\n\n" +
            projectStructure + "\n\n" +
            "## 주요 컴포넌트 및 역할\n\n" +
            componentNote + "\n" +
            "## 기술 스택 (파일 목록 기반 유추)\n\n" +
            "파일 확장자 및 설정 파일 기반으로 기술 스택을 확인하세요.\n\n" +
            "## 인수인계 주요 체크리스트\n\n" +
            "- [ ] 소스 코드 전체 구조 파악 (위 구조 정보 참조)\n" +
            checklistNote +
            "- [ ] 외부 API 및 연동 시스템 목록 확인\n";
    }

    // 주석 문자열을 정규화한다: 마커 누락 보완 + 혼합 스타일 통일
    private String normalizeComment(String comment, String extension) {
        if (comment == null || comment.isBlank()) return comment;
        String trimmed = comment.trim();

        // Python/Properties/YAML 파일: # 주석 형식만 사용
        if (isHashCommentFamily(extension)) {
            if (trimmed.startsWith("#")) return comment;
            // //, /*, /** 등 다른 언어 스타일은 # 스타일로 변환
            String[] pyLines = trimmed.split("\n");
            StringBuilder fixed = new StringBuilder();
            for (String line : pyLines) {
                String l = line.trim();
                if (l.startsWith("//")) {
                    fixed.append("# ").append(l.substring(2).trim()).append("\n");
                } else if (l.startsWith("/**") || l.startsWith("/*")) {
                    String content = l.replaceFirst("^/\\*+\\s*", "").replaceFirst("\\s*\\*/$", "").trim();
                    if (!content.isEmpty()) fixed.append("# ").append(content).append("\n");
                } else if (l.startsWith("*")) {
                    String content = l.replaceFirst("^\\*+/?\\s*", "").trim();
                    if (!content.isEmpty()) fixed.append("# ").append(content).append("\n");
                } else if (l.startsWith("#")) {
                    fixed.append(l).append("\n");
                } else if (!l.isEmpty()) {
                    fixed.append("# ").append(l).append("\n");
                } else {
                    fixed.append("#\n");
                }
            }
            return fixed.toString().stripTrailing();
        }

        // HTML/XML 블록 주석 처리: // <!--로 시작하는 경우 올바른 HTML 형식으로 변환 (HTML/XML 파일에서만)
        if (trimmed.startsWith("// <!--")) {
            if (isXmlFamily(extension)) {
                // "// <!--" 또는 "// -->" 같은 잘못된 형식을 "<!--" 또는 "-->"로 정정
                String fixed = trimmed.replaceAll("^// <!--", "<!--")
                                        .replaceAll("\n// <!--", "\n<!--")
                                        .replaceAll("// -->$", "-->");
                return fixed;
            }
            // Java 등 비-XML 파일: <!--/--> 마커만 제거하고 순수 // 스타일로 정규화
            String stripped = trimmed.replace("<!--", "").replace("-->", "").trim();
            String[] strippedLines = stripped.split("\n");
            StringBuilder fixedNonXml = new StringBuilder();
            for (String line : strippedLines) {
                String l = line.trim();
                if (l.startsWith("//")) l = l.substring(2).trim();
                fixedNonXml.append(l.isEmpty() ? "//\n" : "// " + l + "\n");
            }
            return fixedNonXml.toString().stripTrailing();
        }

        // <!-- --> 블록: HTML/XML 파일에서만 허용, 그 외(Java 등)는 // 스타일로 변환
        if (trimmed.startsWith("<!--")) {
            if (isXmlFamily(extension)) {
                return comment;
            }
            // Java 등 비-XML 파일에서 잘못된 <!-- --> 형식 → // 로 변환
            String innerContent = trimmed.replaceFirst("^<!--\\s*", "").replaceFirst("\\s*-->$", "").trim();
            String[] innerLines = innerContent.split("\n");
            StringBuilder fixed = new StringBuilder();
            for (String line : innerLines) {
                String l = line.trim();
                fixed.append(l.isEmpty() ? "//\n" : "// " + l + "\n");
            }
            return fixed.toString().stripTrailing();
        }

        // JavaScript/CSS 블록 내 // 주석으로 시작하는 HTML 블록 처리 (HTML/XML 파일에서만 해당)
        if (trimmed.startsWith("//") && trimmed.contains("<!--")) {
            if (isCssFamily(extension)) return toCssBlockComment(trimmed);
            if (!isXmlFamily(extension)) {
                // Java 등 비-XML 파일: 이미 // 형식이므로 변환 없이 그대로 사용
                return comment;
            }
            // <style>, <script> 블록 내 실수로 작성된 // <!-- 형식 정정
            String fixed = trimmed.replaceAll("^// <!--", "<!--")
                                    .replaceAll("\n// <!--", "\n<!--");
            return fixed;
        }

        // HTML/XML 본문에서 발견된 // 주석 → HTML 주석으로 변환 (HTML/XML 파일에서만 해당)
        // (HTML 파일의 경우 <script>, <style> 태그 외부에서는 // 주석이 유효하지 않음)
        // CSS는 // 한 줄 주석을 지원하지 않으므로 /* */ 로 변환(role-css.md 신설, 2026-08-11)
        if (trimmed.startsWith("//") && !trimmed.startsWith("// <!--")) {
            if (isCssFamily(extension)) return toCssBlockComment(trimmed);
            if (!isXmlFamily(extension)) {
                // Java 등 비-XML 파일: 이미 올바른 // 형식이므로 변환 없이 그대로 사용
                return comment;
            }
            // 여러 줄의 // 주석을 <!-- --> 형식으로 변환
            String[] lines = trimmed.split("\n");
            if (lines.length == 1) {
                // 한 줄: // 주석 → <!-- 주석 -->
                return "<!-- " + trimmed.substring(2).trim() + " -->";
            } else {
                // 여러 줄: <!-- 형식으로 변환
                StringBuilder fixed = new StringBuilder();
                fixed.append("<!--\n");
                for (String line : lines) {
                    String l = line.trim();
                    if (l.startsWith("//")) {
                        fixed.append(l.substring(2).trim()).append("\n");
                    } else {
                        fixed.append(l).append("\n");
                    }
                }
                fixed.append("-->");
                return fixed.toString();
            }
        }

        // /** */ 블록 안에 // 스타일이 섞인 경우: // 줄을 * 줄로 변환
        if (trimmed.startsWith("/**")) {
            String[] parts = trimmed.split("\n");
            StringBuilder fixed = new StringBuilder();
            for (String part : parts) {
                String p = part.trim();
                if (p.startsWith("//")) {
                    fixed.append(" * ").append(p.substring(2).trim()).append("\n");
                } else {
                    fixed.append(part).append("\n");
                }
            }
            String result = fixed.toString().stripTrailing();
            // Claude가 */ 없이 반환한 경우 강제 닫기
            if (!result.endsWith("*/")) {
                result = result + " */";
            }
            return result;
        }

        // /* */ 블록 안에 // 스타일이 섞인 경우도 동일 처리
        if (trimmed.startsWith("/*") && !trimmed.startsWith("/**")) {
            String[] parts = trimmed.split("\n");
            StringBuilder fixed = new StringBuilder();
            for (String part : parts) {
                String p = part.trim();
                if (p.startsWith("//")) {
                    fixed.append(" * ").append(p.substring(2).trim()).append("\n");
                } else {
                    fixed.append(part).append("\n");
                }
            }
            String result = fixed.toString().stripTrailing();
            // Claude가 */ 없이 반환한 경우 강제 닫기
            if (!result.endsWith("*/")) {
                result = result + " */";
            }
            return result;
        }

        // // 스타일은 그대로 통과 (CSS는 위쪽 // 분기에서 이미 /* */ 로 변환되어 여기 도달하지 않음)
        if (trimmed.startsWith("//")) return comment;

        // 주석 마커가 전혀 없는 순수 텍스트: CSS는 /* */, 그 외에는 // 접두어를 붙여 컴파일 에러 방지
        if (isCssFamily(extension)) return toCssBlockComment(trimmed);
        String[] lines = trimmed.split("\n");
        StringBuilder fixed = new StringBuilder();
        for (String line : lines) {
            String l = line.trim();
            if (l.isEmpty()) {
                fixed.append("//\n");
            } else {
                fixed.append("// ").append(l).append("\n");
            }
        }
        return fixed.toString().stripTrailing();
    }

    /**
     * HTML/XML 파일에서 각 라인이 <script>, <style> 태그 내부인지 확인
     */
    private boolean isLineInsideScriptTag(String[] lines, int lineIndex) {
        boolean insideScript = false;
        for (int i = 0; i <= lineIndex && i < lines.length; i++) {
            String trimmed = lines[i].trim().toLowerCase();
            if (trimmed.contains("<script")) {
                insideScript = true;
            }
            if (trimmed.contains("</script>")) {
                insideScript = false;
            }
        }
        return insideScript && !lines[lineIndex].trim().toLowerCase().contains("</script>");
    }

    private boolean isLineInsideStyleTag(String[] lines, int lineIndex) {
        boolean insideStyle = false;
        for (int i = 0; i <= lineIndex && i < lines.length; i++) {
            String trimmed = lines[i].trim().toLowerCase();
            if (trimmed.contains("<style")) {
                insideStyle = true;
            }
            if (trimmed.contains("</style>")) {
                insideStyle = false;
            }
        }
        return insideStyle && !lines[lineIndex].trim().toLowerCase().contains("</style>");
    }

    /**
     * [하이브리드 결합 엔진]: 반환된 JSON 주석 지도를 한 줄씩 원본 소스에 오차 없이 조립 배포합니다.
     */
    private String mergeCommentsIntoCode(String sourceCode, String jsonResponse, String extension) {
        try {
            String[] lines = sourceCode.split("\\r?\\n");
            Map<Integer, List<String>> commentMap = new HashMap<>();

            // Claude가 마크다운 포맷으로 응답할 수 있으니 제거
            String cleanJson = jsonResponse.trim();
            if (cleanJson.startsWith("```json")) {
                cleanJson = cleanJson.substring(7); // "```json" 제거
            } else if (cleanJson.startsWith("```")) {
                cleanJson = cleanJson.substring(3); // "```" 제거
            }
            if (cleanJson.endsWith("```")) {
                cleanJson = cleanJson.substring(0, cleanJson.length() - 3); // "```" 제거
            }
            cleanJson = cleanJson.trim();

            // JSON 형식 검증 및 자동 복구
            if (!cleanJson.startsWith("[")) {
                cleanJson = "[" + cleanJson;
            }
            if (!cleanJson.endsWith("]")) {
                cleanJson = cleanJson + "]";
            }
            String repairedJson = repairJsonArray(cleanJson);

            // 실제로 복구가 일어난 경우에만 로그 (정상 JSON은 로그 안 함)
            if (!repairedJson.equals(cleanJson)) {
                log.debug("[JSON 변환] 손상된 JSON을 복구하여 정상화했습니다");
            }

            List<?> commentList = mapper.readValue(repairedJson, List.class);

            for (Object obj : commentList) {
                if (obj instanceof Map<?, ?> item) {
                    int lineNum = Integer.parseInt(String.valueOf(item.get("lineNumber")));
                    String commentStr = normalizeComment(String.valueOf(item.get("comment")), extension);
                    commentMap.computeIfAbsent(lineNum, k -> new ArrayList<>()).add(commentStr);
                }
            }

            StringBuilder finalCode = new StringBuilder();

            // Java 파일: package 선언이 최상단에 위치해야 하고 import 사이에 주석 삽입 금지
            if (".java".equals(extension)) {
                int packageLineIdx = -1;
                int lastImportLineIdx = -1;
                for (int i = 0; i < lines.length; i++) {
                    String t = lines[i].trim();
                    if (packageLineIdx < 0 && t.startsWith("package ")) packageLineIdx = i;
                    if (t.startsWith("import ")) lastImportLineIdx = i;
                }

                boolean insideBlockComment = false;
                for (int i = 0; i < lines.length; i++) {
                    int lineIdx1 = i + 1;
                    String trimmed = lines[i].trim();

                    // package 선언 전: 주석 삽입 없이 그대로 출력
                    if (i < packageLineIdx) {
                        finalCode.append(lines[i]).append("\n");
                        continue;
                    }
                    // import 구문 구간: 주석 삽입 금지 (import 행 자체만 출력)
                    if (i <= lastImportLineIdx && (trimmed.startsWith("import ") || trimmed.isEmpty())) {
                        finalCode.append(lines[i]).append("\n");
                        continue;
                    }

                    // 기존 /* */ 또는 /** */ 블록 추적: 블록 안에서는 삽입 금지
                    if (trimmed.startsWith("/**") || (trimmed.startsWith("/*") && !trimmed.startsWith("//"))) {
                        insideBlockComment = true;
                    }
                    if (insideBlockComment) {
                        finalCode.append(lines[i]).append("\n");
                        if (trimmed.endsWith("*/")) insideBlockComment = false;
                        continue;
                    }

                    // 일반 위치: Claude 주석 삽입 허용
                    if (commentMap.containsKey(lineIdx1)) {
                        for (String cmt : commentMap.get(lineIdx1)) {
                            finalCode.append(cmt).append("\n");
                        }
                    }
                    finalCode.append(lines[i]).append("\n");
                }
                return finalCode.toString();
            }

            // Java 외 파일: 주석만 삽입 (마커 없음)
            // HTML/XML 파일은 특별히 처리: 위치에 따라 올바른 주석 형식 적용
            for (int i = 0; i < lines.length; i++) {
                int currentLineIdx = i + 1;
                if (commentMap.containsKey(currentLineIdx)) {
                    for (String cmt : commentMap.get(currentLineIdx)) {
                        // HTML/XML 파일의 경우: 위치에 따라 주석 형식 결정
                        if (isXmlFamily(extension)) {
                            // <script> 또는 <style> 태그 내부인지 확인
                            boolean inScript = isLineInsideScriptTag(lines, i);
                            boolean inStyle = isLineInsideStyleTag(lines, i);

                            if (inScript) {
                                // <script> 내부: // 또는 /* */ 주석 유지 (변환 불필요)
                                // 그대로 통과
                            } else if (inStyle) {
                                // <style> 내부: /* */ 주석 사용
                                // // 주석이 있으면 /* */로 변환
                                if (cmt.startsWith("//")) {
                                    cmt = "/* " + cmt.substring(2).trim() + " */";
                                }
                            } else {
                                // HTML 본문: <!-- --> 형식만 사용
                                // 1. // <!--로 시작하는 경우
                                cmt = cmt.replaceAll("^// <!--", "<!--")
                                        .replaceAll("\n// <!--", "\n<!--");

                                // 2. 단순 //로 시작하는 경우 (<!-- -->로 변환)
                                if (cmt.startsWith("//") && !cmt.startsWith("<!--")) {
                                    String[] cmtLines = cmt.split("\n");
                                    if (cmtLines.length == 1) {
                                        // 한 줄: // 내용 → <!-- 내용 -->
                                        cmt = "<!-- " + cmt.substring(2).trim() + " -->";
                                    } else {
                                        // 여러 줄
                                        StringBuilder cmtFixed = new StringBuilder();
                                        cmtFixed.append("<!--\n");
                                        for (String line : cmtLines) {
                                            String l = line.trim();
                                            if (l.startsWith("//")) {
                                                cmtFixed.append(l.substring(2).trim()).append("\n");
                                            } else {
                                                cmtFixed.append(l).append("\n");
                                            }
                                        }
                                        cmtFixed.append("-->");
                                        cmt = cmtFixed.toString();
                                    }
                                }
                            }
                        }
                        finalCode.append(cmt).append("\n");
                    }
                }
                finalCode.append(lines[i]).append("\n");
            }

            return finalCode.toString();

        } catch (Exception e) {
            log.error("주석 지도 JSON 파일 결합 중 런타임 에러 발생", e);

            return sourceCode;
        }
    }

    /**
     * LlmClient 호출 결과에서 토큰 사용량 정보를 추출하여 누적 저장.
     * (이전엔 Anthropic 원시 응답 Map을 직접 파싱했으나, LlmClient 추상화 도입 이후
     * 각 구현체가 이미 파싱해 담아둔 LlmResult를 받는 형태로 단순화됨)
     */
    private void extractAndStoreTokenUsage(LlmResult result) {
        try {
            if (result == null) return;

            long inputTokens = result.inputTokens();
            long outputTokens = result.outputTokens();
            long cacheReadTokens = result.cacheReadTokens();
            long cacheCreationTokens = result.cacheCreationTokens();

            long totalInput = accumulatedInputTokens.addAndGet(inputTokens);
            long totalOutput = accumulatedOutputTokens.addAndGet(outputTokens);
            accumulatedCacheReadTokens.addAndGet(cacheReadTokens);
            accumulatedCacheCreationTokens.addAndGet(cacheCreationTokens);
            lastModelName = getCurrentModel();

            if (cacheReadTokens > 0) {
                log.info("[토큰 사용량] 입력: {}, 출력: {}, 캐시히트: {} (90% 절약), 누적: {}",
                    inputTokens, outputTokens, cacheReadTokens, totalInput + totalOutput);
            } else if (cacheCreationTokens > 0) {
                log.info("[토큰 사용량] 입력: {}, 출력: {}, 캐시생성: {}, 누적: {}",
                    inputTokens, outputTokens, cacheCreationTokens, totalInput + totalOutput);
            } else {
                log.info("[토큰 사용량] 입력: {}, 출력: {}, 누적 합계: {}",
                    inputTokens, outputTokens, totalInput + totalOutput);
            }
        } catch (Exception e) {
            log.warn("[토큰 추출 실패] {}", e.getMessage());
        }
    }
}
