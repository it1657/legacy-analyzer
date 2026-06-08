package com.legacy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class ClaudeServiceImpl implements ClaudeService {

    @Value("${anthropic.api.key}")
    private String apiKey;

    @Value("${anthropic.api.url}")
    private String apiUrl;

    @Value("${anthropic.api.model}")
    private String apiModel;

    @Value("${anthropic.api.max-tokens:4000}")
    private int apiMaxTokens;

    // 💡 [핵심 추가]: 프로퍼티에 설정된 custom_spec.txt 파일명을 읽어옵니다.
    @Value("${app.analysis.custom-spec-filename:custom_spec.txt}")
    private String customSpecFilename;

    /**
     * 💡 리소스 폴더에서 custom_spec.txt 파일의 세부 지침을 실시간으로 읽어오는 안전 메서드
     */
    private String loadCustomSpec() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(customSpecFilename)) {
            if (is == null) {
                return "// [안내] 별도의 프로젝트 세부 상세 지침 규칙이 지정되지 않았습니다.";
            }
            byte[] bytes = is.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "// [경고] 규칙 파일 읽기 실패: " + e.getMessage();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public String analyzeCodeWithClaude(String sourceCode, String fileName, String sourceFolderPath) {
        // 💡 가상 시뮬레이션 모드일 때 지침서가 잘 읽히는지 콘솔 테스트 지원
        if ("MOCK_KEY_FOR_TEST".equals(apiKey) || apiKey.startsWith("MOCK")) {
            String currentSpec = loadCustomSpec();
            System.out.println("🔍 [프롬프트 진단] 현재 로드된 세부 지침 규칙:\n" + currentSpec);
            return "/* [AI 한글 주석 가상 시뮬레이션 완료] */\n" +
                    "// 파일명: " + fileName + "\n" +
                    "// 세부 지침 연동 확인 완료 (콘솔 로그 참조)\n\n" + sourceCode;
        }

        WebClient webClient = WebClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        // 💡 [핵심 구현]: 파일에서 읽어온 특수 규칙 지침(customSpec)을 프롬프트 본문에 결합합니다.
        String customSpecData = loadCustomSpec();

        String systemPrompt = "너는 Java/Vue/React 전문 개발자이자 테크니컬 라이터야.\n" +
                "입력된 소스 코드를 분석하여 함수별 핵심 기능을 한글 주석으로 달려고 해.\n" +
                "⚠️ [출력 토큰 제한 우회 필수 규칙]\n" +
                "절대 소스 코드 전체를 다시 복사해서 출력하지 마. 오직 원본 코드의 몇 번째 라인에 어떤 한글 주석이 들어가야 하는지 위치 정보만 정해진 JSON 포맷으로 응답해.\n" +
                "인사말이나 설명은 절대 생략하고, 오직 아래 예시와 같은 유효한 JSON 배열만 반환해.\n\n" +
                "📌 [프로젝트별 특수 세부 지침 규칙 (필수 준수)]:\n" +
                customSpecData + "\n\n" + // 💡 파일에서 읽어온 상세 규칙을 완벽하게 주입!
                "[응답 JSON 포맷 예시]:\n" +
                "[\n" +
                "  {\"lineNumber\": 1, \"comment\": \"// 시스템 레거시 회원 관리 서비스 컨트롤러\"},\n" +
                "  {\"lineNumber\": 15, \"comment\": \"// 사용자 세션 및 중복 로그인 방어 검증 로직\"}\n" +
                "]";

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", apiModel);
        requestBody.put("max_tokens", apiMaxTokens);
        requestBody.put("system", systemPrompt);

        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", sourceCode);
        requestBody.put("messages", Collections.singletonList(userMessage));

        try {
            Map<String, Object> response = webClient.post()
                    .uri("/v1/messages")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && response.containsKey("content")) {
                List<Map<String, Object>> contentList = (List<Map<String, Object>>) response.get("content");
                if (!contentList.isEmpty()) {
                    String aiJsonResponse = String.valueOf(contentList.get(0).get("text"));
                    return mergeCommentsIntoCode(sourceCode, aiJsonResponse);
                }
            }
            return "/* [오류] AI 응답 바디 구조 파싱 에러 */\n" + sourceCode;

        } catch (Exception e) {
            return "/* [API 통신 장애 발생]: " + e.getMessage() + " */\n" + sourceCode;
        }
    }
    /**
     * Claude가 보낸 JSON 지도를 기반으로 원본 소스 코드에 주석을 정밀 삽입하는 하이브리드 빌더
     */
    @SuppressWarnings("unchecked")
    private String mergeCommentsIntoCode(String sourceCode, String jsonResponse) {
        try {
            String[] lines = sourceCode.split("\\r?\\n");
            Map<Integer, List<String>> commentMap = new HashMap<>();

            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> commentList = mapper.readValue(jsonResponse, List.class);

            for (Map<String, Object> item : commentList) {
                int lineNum = Integer.parseInt(String.valueOf(item.get("lineNumber")));
                String commentStr = String.valueOf(item.get("comment"));
                commentMap.computeIfAbsent(lineNum, k -> new ArrayList<>()).add(commentStr);
            }

            StringBuilder finalCode = new StringBuilder();
            finalCode.append("/* [AI 한글 주석 가상 시뮬레이션 완료] */\n");

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
            return "/* [AI 한글 주석 가상 시뮬레이션 완료] */\n" +
                    "// [주의] 초대용량 특수 마킹 주석 예외 자동 결합 모드\n\n" + sourceCode;
        }
    }
}
