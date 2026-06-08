package com.legacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

@Service
public class ClaudeService {

    @Value("${anthropic.api.url}")
    private String apiUrl;

    @Value("${anthropic.api.key}")
    private String apiKey;

    // 💡 [4주차 클린 코드]: application.properties에서 설정한 세부 지침 파일 이름을 동적으로 주입받습니다.
    @Value("${app.analysis.custom-spec-filename}")
    private String customSpecFileName;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String analyzeCodeWithClaude(String sourceCode, String fileName, String sourceFolderPath) {

        // [안전장치] 누적되던 옛날 가상 목업 주석을 청소하여 중복 적체 버그 차단
        String cleanedCode = sourceCode;
        String mockBanner = "/* =========================================\n"
                + " * [AI 한글 주석 가상 시뮬레이션 완료]\n"
                + " * 비즈니스 로직 기능 명세 및 아키텍처 가독성 패치 적용\n"
                + " * ========================================= */\n\n";

        if (cleanedCode.contains("[AI 한글 주석 가상 시뮬레이션 완료]")) {
            cleanedCode = cleanedCode.replace(mockBanner, "");
        }

        // 안전장치: 환경 변수에 비밀키가 없으면 가상 시뮬레이션 주석 모드로 강제 원복 우회
        if (apiKey == null || apiKey.isEmpty() || apiKey.contains("CLAUDE_API_KEY") || apiKey.contains("MOCK_KEY")) {
            return mockBanner + cleanedCode.trim();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            // 1. 공통 뼈대 마크다운 지침 로드 (resources/prompt.md)
            org.springframework.core.io.Resource resource = new org.springframework.core.io.ClassPathResource("prompt.md");
            String systemPrompt = new String(Files.readAllBytes(Paths.get(resource.getURI())), java.nio.charset.StandardCharsets.UTF_8);

            // 2. 💡 [유연성 확보]: 프로퍼티에서 읽어온 주입 변수(customSpecFileName)를 사용하여 세부 장부 파일을 안전하게 추적 및 조립합니다.
            java.io.File customSpecFile = new java.io.File(sourceFolderPath, customSpecFileName);
            if (customSpecFile.exists()) {
                String customDetails = Files.readString(customSpecFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                systemPrompt += "\n\n## 3. 해당 프로젝트 전용 특수 비즈니스 규칙 명세 (Custom Project Rules)\n" + customDetails;
            }

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "claude-3-5-sonnet-20241022");
            requestBody.put("max_tokens", 4000);
            requestBody.put("system", systemPrompt);

            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", cleanedCode.trim());
            messages.add(message);
            requestBody.put("messages", messages);

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);
            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, entity, String.class);

            Map<String, Object> responseMap = objectMapper.readValue(response.getBody(), Map.class);
            List<Map<String, Object>> contentList = (List<Map<String, Object>>) responseMap.get("content");
            return contentList.get(0).get("text").toString();

        } catch (Exception e) {
            return "/* Claude API 호출 에러 발생: " + e.getMessage() + " */\n" + cleanedCode.trim();
        }
    }
}
