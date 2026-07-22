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
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 호환 `/v1/chat/completions` 규격을 구현한 임의의 로컬/사내 LLM 서버(Ollama, vLLM,
 * LocalAI 등)를 대상으로 하는 {@link LlmClient} 구현체. 특정 제품 전용이 아니라 이 표준
 * 인터페이스를 쓰는 모든 백엔드에 그대로 동작한다.
 *
 * 인증: {@code llm.local.api-key}가 비어 있으면 {@code Authorization} 헤더 자체를 생략해
 * 인증 없는 백엔드(예: 기본 Ollama)를 그대로 지원하고, 값이 있으면 {@code Bearer} 토큰으로 붙인다.
 *
 * 캐시 토큰({@code cacheReadTokens}/{@code cacheCreationTokens})은 OpenAI 호환 API가
 * 프롬프트 캐싱 개념을 제공하지 않으므로 항상 0이다.
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "local")
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final WebClient webClient;

    public OpenAiCompatibleLlmClient(
            @Value("${llm.local.url}") String baseUrl,
            @Value("${llm.local.api-key:}") String apiKey,
            @Value("${llm.local.read-timeout-sec:300}") long readTimeoutSec) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(readTimeoutSec));

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        // 비어 있으면 Authorization 헤더 자체를 생략(Ollama 등 무인증 백엔드 대응)
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        this.webClient = builder.build();
    }

    @Override
    public LlmResult call(String systemPrompt, String userContent, String model, int maxTokens) {
        Map<String, String> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", systemPrompt);

        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", userContent);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("messages", List.of(systemMessage, userMessage));

        Map<?, ?> response = webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    int statusCode = clientResponse.statusCode().value();
                                    String msg = String.format("로컬 LLM API %d 오류: %s", statusCode,
                                            body.isEmpty() ? "응답 없음" : body.substring(0, Math.min(300, body.length())));
                                    log.error("[로컬 LLM API 응답 오류] {}", msg);
                                    return Mono.error(new WebClientResponseException(
                                            statusCode, msg,
                                            clientResponse.headers().asHttpHeaders(), null, null));
                                }))
                .bodyToMono(Map.class)
                .block();

        if (response == null || !response.containsKey("choices")) {
            throw new RuntimeException("로컬 LLM 응답 바디 구조 파싱 예외 공정 발생");
        }
        List<?> choices = (List<?>) response.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("로컬 LLM 응답 바디 구조 파싱 예외 공정 발생");
        }
        Map<?, ?> firstChoice = (Map<?, ?>) choices.get(0);
        Map<?, ?> message = (Map<?, ?>) firstChoice.get("message");
        String text = String.valueOf(message.get("content"));

        return new LlmResult(text,
                extractLong(response, "prompt_tokens"),
                extractLong(response, "completion_tokens"),
                0L,
                0L);
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
