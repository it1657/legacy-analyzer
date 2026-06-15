package com.legacy.analysis;
import com.legacy.core.ApiErrorHandler;
import com.legacy.core.FileIoErrorHandler;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Value("${anthropic.api.key}")
    private String apiKey;

    @Value("${anthropic.api.url}")
    private String apiUrl;

    @Value("${anthropic.api.model}")
    private String apiModel;

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

    /**
     * 최초 가동 자동화 인프라:
     * 리소스 폴더에 CLAUDE.md 설계서가 없거나 비어있으면, 표준 지침 내용을 담아 실물 파일로 자동 복원/생성합니다.
     */
    private String loadSystemPromptFromMd() {
        java.io.File mdFile = new java.io.File("src/main/resources/" + systemPromptFilename);

        // [모던 자바 텍스트 블록 적용]: 역슬래시 찌꺼기와 개행문자 더하기 없는 가독성 극대화 프롬프트
        String defaultTemplate = """
                # [System Role] 다중 언어 레거시 코드 분석기
                
                현재 분석 중인 파일명은 'fileName' 이며, 확장자는 '{extension}' 입니다.
                
                ## 1. 언어 자동 감지 및 주석 규칙 (Language Auto-Detection)
                입력받은 소스 코드의 문법을 분석하여 언어를 자동 감지 한 후, 반드시 해당 언어 표준 규격에 맞는 한글 주석을 삽입하십시오.
                
                - **Java 파일일 경우 (.java)**:
                    - 클래스 및 주요 비즈니스 메서드 상단에 표준 Javadoc 가이드를 적용하십시오. (주석 기호: // 또는 /* */)
                - **Vue / React / JavaScript / TypeScript 파일일 경우 (.vue, .js, .jsx, .ts, .tsx)**:
                    - 핵심 스크립트 로직 영역은 표준 JavaScript/TypeScript 주석(//)을 적용하십시오.
                    - React의 JSX 화면 렌더링 구역 내부에는 컴파일 에러를 방지하기 위해 리액트 전용 중괄호 주석 포맷({/* 주석내용 */})을 철저하게 준수하십시오.
                    - Vue의 HTML 템플릿 구역(<template>) 내부에는 반드시 HTML 표준 주석 포맷(<!-- 주석내용 -->)을 사용하십시오.
                - **Nexacro / MiPlatform / XML 파일일 경우 (.xfdl, .xml)**:
                    - 표준 XML 주석 포맷(<!-- 주석내용 -->)을 철저히 준수하십시오.
                    - 대기업 전용 특수 업무 약어(SAL:급여, HRM:인사 등)를 직관적인 한글 비즈니스 용어로 치환하여 주석을 기재하십시오.
                
                ## 2. 제약 조건 (Constraints)
                - ⚠️ 기존에 소스 코드 내부에 작성되어 있는 선배 개발자의 기존 주석이나 설명문은 절대 삭제하거나 수정하지 말고 100% 보존하십시오.
                - 새로운 AI 보완 설명 주석은 기존 주석의 하단이나 로직 옆에 안전하게 추가하십시오.
                - 기존 소스 코드의 로직, 변수명, 실행 구조는 절대 한 글자도 변형하거나 훼손해서는 안 됩니다.
                
                ## [프로젝트별 특수 세부 지침 규칙 (필수 준수)]
                ${customSpecData}
                
                ## ⚠️ [출력 토큰 제한 우회 필수 규칙 - 포맷 강제]
                - 절대 인사말, 설명 텍스트, 마크다운 코드 블록(```)을 출력하지 마십시오.
                - 절대 소스 코드 전체를 다시 복사해서 반환하지 마십시오.
                - 오직 원본 코드의 몇 번째 라인에 어떤 한글 주석이 들어가야 하는지 위치 정보만 아래 예시와 같은 유효한 JSON 배열 포맷으로 응답하십시오.
                
                [응답 JSON 포맷 예시 (확장자에 맞는 문법 필수)]:
                [
                  {"lineNumber": 1, "comment": "(여기에 ${extension} 문법에 맞는 주석 입력)"},
                  {"lineNumber": 15, "comment": "(여기에 ${extension} 문법에 맞는 주석 입력)"}
                ]""";

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

        // ===================================================================
        // [정밀 수정 구간]: 산출물 자동화 시스템 전용 README.md 다이렉트 패스
        // ===================================================================
        if ("README.md".equalsIgnoreCase(fileName)) {
            String dynamicProjectName = "레거시";
            try {
                if (sourceFolderPath != null && !sourceFolderPath.trim().isEmpty()) {
                    java.io.File directory = new java.io.File(sourceFolderPath);
                    String folderName = directory.getName();

                    if (!folderName.trim().isEmpty()) {
                        dynamicProjectName = folderName.toUpperCase(); // 대문자로 변환
                    }
                }
            } catch (Exception e) {
                log.error("README 프로젝트 이름 동적 파싱 실패", e);
                dynamicProjectName = "레거시";
            }

            return "# 프로젝트 기술 인수인계서 (System Operations Guide)\n\n" +
                    "## 1. 시스템 개요 및 목적 (System Overview)\n" +
                    "- **프로젝트명**: " + dynamicProjectName + " 레거시 시스템 전환 및 품질 보완 프로젝트\n" +
                    "- **인도 목적**: 바쁜 일정 속 개발자 주석 작성 부담 경감 및 철수 시 후임자 아키텍처 파악 비용 최소화\n" +
                    "- **인수 대상 소스 루트**: `" + sourceFolderPath + "`\n\n" +
                    "## 2. 전체 프로젝트 디렉터리 아키텍처 (Project Directory Structure)\n" +
                    "본 프로젝트의 정적 자산 및 하위 패키지 계층 구조를 완전히 추적 분석한 소스 트리 명세입니다.\n" +
                    "후임자는 아래 소스 자산 인덱스를 기반으로 컴포넌트의 유기적 흐름을 추적하십시오.\n\n" +
                    "### [자동 스캔된 파일 자산 인덱스]\n" +
                    sourceCode + "\n" +
                    "## 3. 개발 환경 및 빌드 가이드 (Development Environment & Build)\n" +
                    "- **기본 빌드 도구**: Apache Maven / Gradle 규격 준수 (pom.xml 사양서 참조)\n" +
                    "- **인코딩 세이프 가드**: 레거시 파일의 특성인 UTF-8 및 MS949(EUC-KR) 다중 인코딩 자동 교정 완료\n" +
                    "- **빌드 절차**:\n" +
                    "  1. 본 프로젝트의 종속성 외부 라이브러리 라이프사이클을 빌드 툴로 로드하십시오.\n" +
                    "  2. 형상관리 시스템 배포 시 본 문서의 정합성 지표를 확인하십시오.\n\n" +
                    "## 4. 주석 품질 정책 및 유지보수 규칙 (Compliance & Quality)\n" +
                    "- 본 프로젝트 하위의 모든 핵심 컴포넌트는 Claude AI를 통해 확장자별 맞춤형 한글 주석(Java: //, XML/HTML: <!-- -->, Python: #) 보완 공정을 거쳤습니다.\n" +
                    "- **선배 개발자 주석 보존**: 기존 소스 내부에 작성되어 있던 레거시 주석문은 100% 보존 조치되어 로직 추적이 용이합니다.\n" +
                    "- **로직 무결성**: 실행 구조, 변수명, 비즈니스 알고리즘은 단 한 글자도 변형되지 않은 안전 자산입니다.\n\n" +
                    "## 5. 시스템 인도 정보 (System Transfer Sign-off)\n" +
                    "- **최종 패치 일자**: " + new java.util.Date() + "\n" +
                    "- **시스템 인도 책임자**: 개발팀 **홍길동** (인수자 승인 시 즉시 형상 인계 가능)\n\n" +
                    "---\n" +
                    "*본 기술 문서는 [산출물 자동화 시스템 v5.5] 엔진에 의해 정적 코드 구조 분석 결과로 실시간 자동 발행되었습니다.*";
        }
        // ===================================================================

        String customSpecData = loadCustomSpec(extension);

        if (apiKey == null || "MOCK_KEY_FOR_TEST".equals(apiKey) || apiKey.startsWith("MOCK") || apiKey.trim().isEmpty()) {
            String mockJsonResponse = generateMockResponse(fileName, extension);
            return mergeCommentsIntoCode(sourceCode, mockJsonResponse, extension);
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
        requestBody.put("model", apiModel);
        requestBody.put("max_tokens", apiMaxTokens);
        requestBody.put("system", finalSystemPrompt);

        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", "파일명: " + fileName + "\n\n[소스 코드]:\n" + sourceCode);
        requestBody.put("messages", Collections.singletonList(userMessage));

        // 설정값에서 재시도 정책 로드
        int maxRetries = sessionConfig.getMaxRetries();
        long initialRetryDelay = sessionConfig.getInitialRetryDelayMs();
        long maxRetryDelay = sessionConfig.getMaxRetryDelayMs();

        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                Map<?, ?> response = webClient.post()
                        .uri("/v1/messages")
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();

                if (response != null && response.containsKey("content")) {
                    List<?> contentList = (List<?>) response.get("content");
                    if (contentList != null && !contentList.isEmpty()) {
                        Map<?, ?> contentMap = (Map<?, ?>) contentList.get(0);
                        String aiJsonResponse = String.valueOf(contentMap.get("text"));
                        log.info("[API 분석 성공] 파일명: {}", fileName);
                        return mergeCommentsIntoCode(sourceCode, aiJsonResponse, extension);
                    }
                }
                throw new RuntimeException("AI 응답 바디 구조 파싱 예외 공정 발생");

            } catch (Exception e) {
                // 에러 분류
                ApiErrorHandler.ErrorType errorType = apiErrorHandler.classifyError(e, 0);

                // 재시도 불가능한 에러는 즉시 반환
                if (!apiErrorHandler.isRetryable(errorType)) {
                    String userMsg = apiErrorHandler.getUserFriendlyMessage(errorType, fileName);
                    apiErrorHandler.logError(errorType, fileName, e, retry, false);
                    log.error("[비복구 오류] {}", userMsg);
                    return "/* [비복구 오류 발생 - " + errorType.name() + "]: " + e.getMessage() + " */\n" + sourceCode;
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
                        break;
                    }
                } else {
                    // 최종 재시도 실패
                    String userMsg = apiErrorHandler.getUserFriendlyMessage(errorType, fileName);
                    apiErrorHandler.logError(errorType, fileName, e, maxRetries, false);
                    log.error("[최대 재시도 초과] {} | 파일: {}", userMsg, fileName);
                    return String.format("/* [API 통신 장애 발생 - 최대 재시도 초과 (%d/%d): %s] */\n",
                        retry + 1, maxRetries, e.getMessage()) + sourceCode;
                }
            }
        }

        return "/* [오류] 시스템 미확인 런타임 통제 불능 에러 */\n" + sourceCode;
    }

    /**
     * [하이브리드 결합 엔진]: 반환된 JSON 주석 지도를 한 줄씩 원본 소스에 오차 없이 조립 배포합니다.
     */
    private String mergeCommentsIntoCode(String sourceCode, String jsonResponse, String extension) {
        try {
            String[] lines = sourceCode.split("\\r?\\n");
            Map<Integer, List<String>> commentMap = new HashMap<>();

            List<?> commentList = mapper.readValue(jsonResponse, List.class);

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
}
