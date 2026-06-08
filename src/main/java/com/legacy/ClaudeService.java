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

    @Value("${app.analysis.custom-spec-filename}")
    private String customSpecFileName;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String analyzeCodeWithClaude(String sourceCode, String fileName, String sourceFolderPath) {

        String cleanedCode = sourceCode;

        // 인텔리제이 추천 스타일: 자바 15+ 텍스트 블록(Text Block) 문법으로 깔끔하게 교정
        String mockBanner = """
                /* =========================================
                 * [AI 한글 주석 가상 시뮬레이션 완료]
                 * 비즈니스 로직 기능 명세 및 아키텍처 가독성 패치 적용
                 * ========================================= */
                
                """;

        if (cleanedCode.contains("[AI 한글 주석 가상 시뮬레이션 완료]")) {
            cleanedCode = cleanedCode.replace(mockBanner, "");
        }

        if (apiKey == null || apiKey.isEmpty() || apiKey.contains("CLAUDE_API_KEY") || apiKey.contains("MOCK_KEY")) {
            return mockBanner + cleanedCode.trim();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            org.springframework.core.io.Resource resource = new org.springframework.core.io.ClassPathResource("prompt.md");
            // 경고 조치: 구형 Files.readAllBytes 대신 Files.readString() 표준 컴포넌트 변환
            String systemPrompt = Files.readString(Paths.get(resource.getURI()), java.nio.charset.StandardCharsets.UTF_8);

            // 경고 조치: fileName 변수를 시스템 지침 정보 분석용 헤더로 녹여내어 미사용 경고 소멸
            systemPrompt += "\n\n## [시스템 안내] 현재 분석 중인 소스 파일 이름: " + fileName;

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

            // 경고 조치: 자바 언어의 엄격한 Generic 타입 경고 해결 방어선 구축
            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap = objectMapper.readValue(response.getBody(), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> contentList = (List<Map<String, Object>>) responseMap.get("content");
            return contentList.get(0).get("text").toString();

        } catch (Exception e) {
            return "/* Claude API 호출 에러 발생: " + e.getMessage() + " */\n" + cleanedCode.trim();
        }
    }
}
