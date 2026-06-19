package com.legacy.analysis;
import com.legacy.core.ApiErrorHandler;
import com.legacy.core.FileIoErrorHandler;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class ClaudeServiceImpl implements ClaudeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeServiceImpl.class);

    // Jackson ObjectMapper 글로벌 인스턴스 공유로 불필요한 객체 재생성 경고 차단
    private static final ObjectMapper mapper = new ObjectMapper();

    // ThreadLocal을 이용한 토큰 사용량 추적 (세션별 누적)
    private static final ThreadLocal<TokenUsage> tokenUsageHolder = ThreadLocal.withInitial(TokenUsage::new);

    @Value("${anthropic.api.key}")
    private String apiKey;

    @Value("${anthropic.api.url}")
    private String apiUrl;

    @Value("${anthropic.api.model}")
    private String apiModel;

    // 런타임 모델 오버라이드 (선택한 모델이 있으면 우선 사용)
    private volatile String modelOverride = null;

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

    private final ApiErrorHandler apiErrorHandler;
    private final FileIoErrorHandler fileIoErrorHandler;
    private final SessionConfig sessionConfig;

    @Autowired
    public ClaudeServiceImpl(ApiErrorHandler apiErrorHandler, FileIoErrorHandler fileIoErrorHandler,
        SessionConfig sessionConfig) {
      this.apiErrorHandler = apiErrorHandler;
      this.fileIoErrorHandler = fileIoErrorHandler;
      this.sessionConfig = sessionConfig;
    }

    @Override
    public TokenUsage getTotalTokenUsage() {
        return tokenUsageHolder.get();
    }

    @Override
    public void resetTokenUsage() {
        tokenUsageHolder.remove();
    }

    @Override
    public String getCurrentModel() {
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

    /**
     * 최초 가동 자동화 인프라:
     * 리소스 폴더에 CLAUDE.md 설계서가 없거나 비어있으면, 표준 지침 내용을 담아 실물 파일로 자동 복원/생성합니다.
     */
    private String loadSystemPromptFromMd() {
        java.io.File mdFile = new java.io.File("src/main/resources/" + systemPromptFilename);

        // [레거시 시스템 분석 전문가 프롬프트 - 간소화]
        String defaultTemplate = """
                레거시 시스템 분석가: 파일(fileName, 확장자: ${extension})의 비즈니스 로직만 한글 주석으로 설명하자.

                주석 우선순위:
                1. 비즈니스 로직 (왜 존재하는가?)
                2. 복잡한 알고리즘 (의도와 흐름)
                3. API/DB 접근 (의존성)
                4. 예외 처리 (대응 방법)

                규칙: 기존 주석 100% 보존, 새 주석은 하단/옆에 추가
                언어별: Java(/,/**), JS(//), JSX({/**/}), XML(<!---->)

                응답: 반드시 JSON 배열 형식으로만 응답. 각 객체는 반드시 lineNumber와 comment를 포함:
                [
                  {"lineNumber": 1, "comment": "주석 내용"},
                  {"lineNumber": 5, "comment": "주석 내용"}
                ]
                주의: 마크다운 또는 다른 형식은 절대 금지. JSON만 반환.""";

        if (!mdFile.exists() || mdFile.length() == 0) {
            try {
                java.io.File parentDir = mdFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    boolean isCreated = parentDir.mkdirs();
                    if (!isCreated) {
                        log.warn("CLAUDE.md 저장 폴더 생성에 실패했거나 이미 존재합니다. 경로: {}", parentDir.getAbsolutePath());
                    }
                }
                java.nio.file.Files.writeString(mdFile.toPath(), defaultTemplate, java.nio.charset.StandardCharsets.UTF_8);
                log.warn("[시스템 경고] CLAUDE.md 파일 내용이 비어있어 기본 표준 지침서로 내용이 자동 원상복구되었습니다.");
                return defaultTemplate;
            } catch (Exception e) {
                log.error("CLAUDE.md 자동 백업 생성 실패", e);
            }
        }

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(systemPromptFilename)) {
            String content = (is == null) ? java.nio.file.Files.readString(mdFile.toPath(), java.nio.charset.StandardCharsets.UTF_8)
                    : new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return content.trim().isEmpty() ? defaultTemplate : content;
        } catch (Exception e) {
            log.error("CLAUDE.md 로드 중 예외 발생", e);
            return "/* [오류] CLAUDE.md 로드 실패: " + e.getMessage() + " */";
        }
    }

    /**
     * 중복 코드 제거를 위한 함수
     */
    private boolean isXmlFamily(String extension) {
        return ".html".equals(extension) || ".xml".equals(extension) || ".xfdl".equals(extension);
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
            if (apiKey == null || "MOCK_KEY_FOR_TEST".equals(apiKey) || apiKey.startsWith("MOCK") || apiKey.trim().isEmpty()) {
                throw new AnalysisException(ApiErrorHandler.ErrorType.API_AUTHENTICATION,
                    new RuntimeException("Claude API KEY가 설정되지 않았습니다. application.properties를 확인하세요."));
            }
            return generateProjectReadmeWithClaude(sourceCode, sourceFolderPath);
        }

        String customSpecData = loadCustomSpec(extension);

        if (apiKey == null || "MOCK_KEY_FOR_TEST".equals(apiKey) || apiKey.startsWith("MOCK") || apiKey.trim().isEmpty()) {
            // API KEY 미설정 시 파일을 수정하지 않고 예외 발생 (원본 보호)
            log.warn("[API KEY 미설정] 파일 처리 건너뜀: {}", fileName);
            throw new AnalysisException(ApiErrorHandler.ErrorType.API_AUTHENTICATION,
                new RuntimeException("Claude API KEY가 설정되지 않았습니다. application.properties를 확인하세요."));
        }

        WebClient webClient = WebClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        String baseSystemPrompt = loadSystemPromptFromMd();

        // 💡 단순 문자열 치환은 .replace() 표준 메서드로 사용하여 성능 인스펙션을 소독했습니다.
        String finalSystemPrompt = baseSystemPrompt
                .replace("${fileName}", fileName)
                .replace("${extension}", extension)
                .replace("${customSpecData}", customSpecData);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", getCurrentModel());
        requestBody.put("max_tokens", apiMaxTokens);
        requestBody.put("system", finalSystemPrompt);

        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", "파일명: " + fileName + "\n\n[소스 코드]:\n" + sourceCode +
                "\n\n⚠️ 절대 중요: 다음 JSON 배열 형식으로만 응답하세요. 마크다운(```), 설명, 쉼표 오류 금지:\n" +
                "[{\"lineNumber\": 숫자, \"comment\": \"내용\"}, {\"lineNumber\": 숫자, \"comment\": \"내용\"}]\n" +
                "- 각 객체는 { }로 완전히 감싸기\n" +
                "- 객체 사이에 쉼표(,) 필수\n" +
                "- JSON 외의 모든 텍스트 금지");
        requestBody.put("messages", Collections.singletonList(userMessage));

        // 설정값에서 재시도 정책 로드
        int maxRetries = sessionConfig.getMaxRetries();
        long initialRetryDelay = sessionConfig.getInitialRetryDelayMs();
        long maxRetryDelay = sessionConfig.getMaxRetryDelayMs();

        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                Map<?, ?> response = webClient.post()
                        .uri("/v1/messages")
                        .header("anthropic-version", "2023-06-01")
                        .bodyValue(requestBody)
                        .retrieve()
                        .onStatus(
                            status -> !status.is2xxSuccessful(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    int statusCode = clientResponse.statusCode().value();
                                    String msg = String.format("Claude API %d 오류: %s", statusCode,
                                        body.isEmpty() ? "응답 없음" : body.substring(0, Math.min(300, body.length())));
                                    log.error("[Claude API 응답 오류] {}", msg);
                                    return Mono.error(new WebClientResponseException(
                                        statusCode, msg,
                                        clientResponse.headers().asHttpHeaders(), null, null));
                                })
                        )
                        .bodyToMono(Map.class)
                        .block();

                if (response != null && response.containsKey("content")) {
                    List<?> contentList = (List<?>) response.get("content");
                    if (contentList != null && !contentList.isEmpty()) {
                        Map<?, ?> contentMap = (Map<?, ?>) contentList.get(0);
                        String aiJsonResponse = String.valueOf(contentMap.get("text"));

                        // 토큰 사용량 추출 및 저장
                        extractAndStoreTokenUsage(response);

                        log.info("[API 분석 성공] 파일명: {}", fileName);
                        return mergeCommentsIntoCode(sourceCode, aiJsonResponse, extension);
                    }
                }
                throw new RuntimeException("AI 응답 바디 구조 파싱 예외 공정 발생");

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

        String systemPrompt =
            "당신은 레거시 소프트웨어 시스템을 분석하는 시니어 SW 아키텍트입니다.\n" +
            "주어진 프로젝트 패키지 구조와 파일 목록을 분석하여 고객 납품용 기술 인수인계 문서를 작성하세요.\n\n" +
            "## 분석 원칙\n" +
            "- 패키지명(auth, order, payment, user, product 등)에서 비즈니스 도메인을 적극적으로 유추\n" +
            "- Controller/Service/Repository/Entity 레이어 패턴을 파악하여 아키텍처 설명\n" +
            "- 파일명에서 처리하는 업무(주문, 결제, 인증, 재고 등)를 구체적으로 서술\n" +
            "- 후임 개발자가 3시간 안에 시스템 전체 구조를 파악할 수 있도록 작성\n" +
            "- 추측이 필요한 경우 '파일명 기준 유추'임을 명시\n\n" +
            "## 필수 출력 형식 (## 헤더 사용, 서브섹션은 ### 사용)\n" +
            "## 시스템 개요\n" +
            "## 아키텍처 구조\n" +
            "## 패키지별 기능 설명\n" +
            "## 주요 컴포넌트 및 역할\n" +
            "## 기술 스택 (파일 목록 기반 유추)\n" +
            "## 인수인계 주요 체크리스트\n\n" +
            "마크다운만 출력하고, 서두/인사말/추가 설명은 절대 작성하지 마세요.";

        String userContent = "프로젝트명: " + projectName + "\n\n" + projectStructure;

        WebClient readmeWebClient = WebClient.builder()
            .baseUrl(apiUrl)
            .defaultHeader("x-api-key", apiKey)
            .defaultHeader("anthropic-version", "2023-06-01")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", getCurrentModel());
        requestBody.put("max_tokens", 4096);
        requestBody.put("system", systemPrompt);

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userContent);
        requestBody.put("messages", Collections.singletonList(userMsg));

        try {
            Map<?, ?> response = readmeWebClient.post()
                .uri("/v1/messages")
                .header("anthropic-version", "2023-06-01")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(),
                    cr -> cr.bodyToMono(String.class).defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new RuntimeException("README API 오류: " + body))))
                .bodyToMono(Map.class)
                .block();

            if (response != null && response.containsKey("content")) {
                List<?> contentList = (List<?>) response.get("content");
                if (contentList != null && !contentList.isEmpty()) {
                    Map<?, ?> contentMap = (Map<?, ?>) contentList.get(0);
                    String readmeText = String.valueOf(contentMap.get("text"));
                    extractAndStoreTokenUsage(response);
                    log.info("[README 생성 완료] 프로젝트: {}, 길이: {}자", projectName, readmeText.length());
                    return readmeText;
                }
            }
            log.warn("[README API 응답 파싱 실패] 구조 기반 폴백 사용");
        } catch (Exception e) {
            log.warn("[README API 호출 실패, 폴백 사용] {}", e.getMessage());
        }
        // API 실패 시 패키지 구조 기반 폴백 README 생성
        return buildFallbackReadme(projectName, projectStructure);
    }

    /** API 호출 실패 시 패키지 구조 데이터로 기본 README 생성 */
    private String buildFallbackReadme(String projectName, String projectStructure) {
        return "## 시스템 개요\n\n" +
            "**프로젝트명**: " + projectName + "\n\n" +
            "본 문서는 레거시 코드 자동 분석 시스템이 수집한 프로젝트 구조 정보를 기반으로 생성된 기술 인수인계 문서입니다.\n" +
            "(AI 분석 생성 실패 — 아래 구조 정보를 참고하세요)\n\n" +
            "## 아키텍처 구조\n\n" +
            "소스 파일 목록 기반으로 파악된 레이어 구조입니다.\n" +
            "Controller / Service / Repository 패턴이 적용된 경우 각 레이어의 역할을 확인하세요.\n\n" +
            "## 패키지별 기능 설명\n\n" +
            projectStructure + "\n\n" +
            "## 주요 컴포넌트 및 역할\n\n" +
            "위 패키지 구조의 파일명을 기준으로 각 컴포넌트의 역할을 파악하세요.\n" +
            "- `*Controller.java` : REST API 엔드포인트 (요청 수신 및 응답 처리)\n" +
            "- `*Service.java` : 비즈니스 로직 처리\n" +
            "- `*Repository.java` : 데이터베이스 접근 (CRUD)\n" +
            "- `*Entity.java` / `*DTO.java` : 데이터 모델\n\n" +
            "## 기술 스택 (파일 목록 기반 유추)\n\n" +
            "파일 확장자 및 설정 파일 기반으로 기술 스택을 확인하세요.\n\n" +
            "## 인수인계 주요 체크리스트\n\n" +
            "- [ ] 소스 코드 전체 구조 파악 (위 패키지 구조 참조)\n" +
            "- [ ] 빌드 도구 및 의존성 확인 (pom.xml / build.gradle)\n" +
            "- [ ] 데이터베이스 연결 설정 확인 (application.properties)\n" +
            "- [ ] 주요 비즈니스 로직 흐름 파악 (Service 계층 중심)\n" +
            "- [ ] 외부 API 및 연동 시스템 목록 확인\n";
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
                    String commentStr = String.valueOf(item.get("comment"));
                    commentMap.computeIfAbsent(lineNum, k -> new ArrayList<>()).add(commentStr);
                }
            }

            StringBuilder finalCode = new StringBuilder();

            String topBanner = ".py".equals(extension) ? "# [AI 한글 주석 보완 완료]\n" :
                    isXmlFamily(extension) ? "<!-- [AI 한글 주석 보완 완료] -->\n" :
                    "/* [AI 한글 주석 보완 완료] */\n";
            finalCode.append(topBanner);

            for (int i = 0; i < lines.length; i++) {
                int currentLineIdx = i + 1;
                if (commentMap.containsKey(currentLineIdx)) {
                    for (String cmt : commentMap.get(currentLineIdx)) {
                        finalCode.append(cmt).append("\n");
                    }
                }
                finalCode.append(lines[i]).append("\n");
            }

            return finalCode.toString();

        } catch (Exception e) {
            log.error("주석 지도 JSON 파일 결합 중 런타임 에러 발생", e);

            String errorBanner = ".py".equals(extension) ? "# [주의] 초대용량 특수 마킹 주석 예외 자동 결합 모드\n\n" :
                    isXmlFamily(extension) ? "<!-- [주의] 초대용량 특수 마킹 주석 예외 자동 결합 모드 -->\n\n" :
                    "// [주의] 초대용량 특수 마킹 주석 예외 자동 결합 모드\n\n";

            return errorBanner + sourceCode;
        }
    }

    /**
     * Claude API 응답에서 토큰 사용량 정보를 추출하여 누적 저장
     */
    private void extractAndStoreTokenUsage(Map<?, ?> response) {
        try {
            Map<?, ?> usage = (Map<?, ?>) response.get("usage");
            if (usage != null) {
                long inputTokens = 0;
                long outputTokens = 0;

                // 입력 토큰 추출
                Object inputObj = usage.get("input_tokens");
                if (inputObj != null) {
                    inputTokens = ((Number) inputObj).longValue();
                }

                // 출력 토큰 추출
                Object outputObj = usage.get("output_tokens");
                if (outputObj != null) {
                    outputTokens = ((Number) outputObj).longValue();
                }

                // 현재 누적 토큰 정보 조회
                TokenUsage current = tokenUsageHolder.get();
                current.setInputTokens(current.getInputTokens() + inputTokens);
                current.setOutputTokens(current.getOutputTokens() + outputTokens);
                current.setTotalTokens(current.getInputTokens() + current.getOutputTokens());
                current.setModelName(apiModel);

                log.info("[토큰 사용량] 입력: {}, 출력: {}, 누적 합계: {}",
                        inputTokens, outputTokens, current.getTotalTokens());
            }
        } catch (Exception e) {
            log.warn("[토큰 추출 실패] {}", e.getMessage());
        }
    }
}
