package com.legacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.*;

@Service
public class ClaudeService {

    @Value("${anthropic.api.url}")
    private String apiUrl;

    @Value("${anthropic.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String analyzeCodeWithClaude(String sourceCode) {
        // 💡 안전장치: 만약 윈도우 환경 변수에 API 키가 없거나 기본 플레이스홀더 상태라면 가짜 연동 모드로 자동 전환
        if (apiKey == null || apiKey.isEmpty() || apiKey.contains("CLAUDE_API_KEY") || apiKey.contains("MOCK_KEY")) {
            return "/* =========================================\n"
                    + " * [AI 한글 주석 가상 시뮬레이션 완료]\n"
                    + " * 비즈니스 로직 기능 명세 및 아키텍처 가독성 패치 적용\n"
                    + " * ========================================= */\n\n"
                    + sourceCode;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            String systemPrompt = "너는 전 세계 최고의 레거시 소스 코드 분석가이자 소프트웨어 아키텍트이다.\n"
                    + "입력받은 소스 코드의 아키텍처, 비즈니스 로직, 핵심 함수별 기능 명세를 가독성 높은 한글 주석으로 자동 변환하여 코드 내 적절한 위치에 삽입해라.\n"
                    + "기존 소스 코드의 로직, 변수명, 실행 구조는 절대 한 글자도 변형하거나 훼손해서는 안 되며, 오직 주석만 추가해야 한다.\n"
                    + "답변할 때는 Markdown 코드 블록(```)이나 다른 설명 텍스트를 절대 붙이지 말고, 주석이 완벽히 포함된 '전체 소스 코드' 내용만 통째로 반환해라.";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "claude-3-5-sonnet-20241022");
            requestBody.put("max_tokens", 4000);
            requestBody.put("system", systemPrompt);

            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", sourceCode);
            messages.add(message);
            requestBody.put("messages", messages);

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);
            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, entity, String.class);

            Map<String, Object> responseMap = objectMapper.readValue(response.getBody(), Map.class);
            List<Map<String, Object>> contentList = (List<Map<String, Object>>) responseMap.get("content");
            return contentList.get(0).get("text").toString();

        } catch (Exception e) {
            return "/* Claude API 호출 에러 발생: " + e.getMessage() + " */\n" + sourceCode;
        }
    }
}
