package com.legacy.analysis.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API(`/v1/messages`)를 호출하는 {@link LlmClient} 구현체.
 *
 * {@code ClaudeServiceImpl}의 3개 호출 지점(analyzeCodeWithClaude, generateSessionClaudeMd,
 * generateProjectReadmeWithClaude)이 각각 만들던 WebClient 호출 로직을 그대로 옮긴 것이다.
 * 프롬프트 캐싱(`cache_control: ephemeral`)은 세 호출 지점 중 하나에만 있었으나, 전체 응답에
 * 해가 없고 캐시 히트 기회를 넓히므로 모든 호출에 동일하게 적용한다.
 *
 * 재시도는 이 클래스의 책임이 아니다 — 실패 시 {@link WebClientResponseException}을 그대로 던지므로,
 * 호출부(ClaudeServiceImpl)의 기존 재시도 루프·{@code ApiErrorHandler} 에러 분류 로직이
 * 변경 없이 그대로 재사용될 수 있다.
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "anthropic", matchIfMissing = true)
public class AnthropicLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String ANTHROPIC_BETA_PROMPT_CACHING = "prompt-caching-2024-07-31";

    private final WebClient webClient;

    public AnthropicLlmClient(
            @Value("${anthropic.api.key}") String apiKey,
            @Value("${anthropic.api.url}") String apiUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                .defaultHeader("anthropic-beta", ANTHROPIC_BETA_PROMPT_CACHING)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public LlmResult call(String systemPrompt, String userContent, String model, int maxTokens) {
        Map<String, Object> systemBlock = new HashMap<>();
        systemBlock.put("type", "text");
        systemBlock.put("text", systemPrompt);
        systemBlock.put("cache_control", Collections.singletonMap("type", "ephemeral"));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("system", Collections.singletonList(systemBlock));

        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", userContent);
        requestBody.put("messages", Collections.singletonList(userMessage));

        Map<?, ?> response = webClient.post()
                .uri("/v1/messages")
                .header("anthropic-version", ANTHROPIC_VERSION)
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
                                }))
                .bodyToMono(Map.class)
                .block();

        if (response == null || !response.containsKey("content")) {
            throw new RuntimeException("AI 응답 바디 구조 파싱 예외 공정 발생");
        }
        List<?> contentList = (List<?>) response.get("content");
        if (contentList == null || contentList.isEmpty()) {
            throw new RuntimeException("AI 응답 바디 구조 파싱 예외 공정 발생");
        }
        Map<?, ?> contentMap = (Map<?, ?>) contentList.get(0);
        String text = String.valueOf(contentMap.get("text"));

        return new LlmResult(text,
                extractLong(response, "input_tokens"),
                extractLong(response, "output_tokens"),
                extractLong(response, "cache_read_input_tokens"),
                extractLong(response, "cache_creation_input_tokens"));
    }

    private long extractLong(Map<?, ?> response, String usageKey) {
        Object usageObj = response.get("usage");
        if (!(usageObj instanceof Map<?, ?> usage)) {
            return 0L;
        }
        Object value = usage.get(usageKey);
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
