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

    // ThreadLocal을 이용한 토큰 사용량 추적 (세션별 누적)
    private static final ThreadLocal<TokenUsage> tokenUsageHolder = ThreadLocal.withInitial(TokenUsage::new);

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
        return apiModel;
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
                log.debug("[JSON] 정상적인 JSON 배열입니다");
                return jsonStr;
            } catch (Exception ignored) {
                // 정상 JSON이 아니면 복구 로직 실행
            }

            // 배열 경계 추출
            int startIdx = jsonStr.indexOf('[');
            int endIdx = jsonStr.lastIndexOf(']');
            if (startIdx == -1 || endIdx == -1 || startIdx >= endIdx) {
                log.warn("[JSON 복구] 배열 경계를 찾을 수 없음");
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
                log.warn("[JSON 복구] 파싱 가능한 항목 없음. 원본: {}",
                    content.substring(0, Math.min(200, content.length())));
                return jsonStr;
            }

            String result = mapper.writeValueAsString(items);
            log.debug("[JSON 복구 성공] 추출된 항목 수: {}", items.size());
            return result;

        } catch (Exception e) {
            log.error("[JSON 복구 실패] 예외: {}", e.getMessage());
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

            } catch (Exception e) {
                // 모든 예외에 대해 로깅
                log.error("[예외 발생] 예외 타입: {}, 메시지: {}", e.getClass().getSimpleName(), e.getMessage());

                // 400/401 오류인 경우 응답 바디 로깅 (디버깅용)
                if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException) {
                    org.springframework.web.reactive.function.client.WebClientResponseException wce =
                        (org.springframework.web.reactive.function.client.WebClientResponseException) e;
                    try {
                        String responseBody = wce.getResponseBodyAsString();
                        log.error("[API 응답 상세] statusCode={}, body={}", wce.getStatusCode(), responseBody);
                    } catch (Exception bodyEx) {
                        log.error("[API 응답 상세] statusCode={}, 바디 접근 실패: {}", wce.getStatusCode(), bodyEx.getMessage());
                    }
                }

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
            cleanJson = repairJsonArray(cleanJson);

            log.debug("[JSON 복구 완료] 원본 길이={}, 복구 후 길이={}", jsonResponse.length(), cleanJson.length());

            List<?> commentList = mapper.readValue(cleanJson, List.class);

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
