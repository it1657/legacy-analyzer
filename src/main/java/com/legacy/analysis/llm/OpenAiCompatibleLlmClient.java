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
import java.util.concurrent.Semaphore;

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
 *
 * <b>동시 호출 수 제한(2026-07-23 실측 기반 추가)</b>: 분석 스레드 풀(`app.analysis.thread-pool-size`,
 * 기본 16)이 파일마다 동시에 이 클라이언트를 호출하는데, CPU 전용 로컬 Ollama는 보통 요청을
 * 한 번에 하나씩만 처리한다. 16개가 한꺼번에 HTTP 요청을 보내면 뒤에 밀린 요청은 실제로
 * 처리되기도 전에 클라이언트 타임아웃(`llm.local.read-timeout-sec`, 기본 300초)을 넘겨버린다
 * (실측: 100파일 중 성공 7·기존 스킵 2·실패 91, 성공률 7%). 이 클래스 안에 세마포어를 둬서
 * 실제 HTTP 요청 전송 자체를 `llm.local.max-concurrent-calls`(기본 1)개로 제한한다 — 대기
 * 중인 호출은 요청을 아직 보내지 않은 상태라 타임아웃 시계가 돌지 않고 안전하게 큐잉된다.
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "local")
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final WebClient webClient;
    private final double temperature;
    private final Semaphore concurrencyGate;

    public OpenAiCompatibleLlmClient(
            @Value("${llm.local.url}") String baseUrl,
            @Value("${llm.local.api-key:}") String apiKey,
            @Value("${llm.local.read-timeout-sec:300}") long readTimeoutSec,
            // 코드 분석·주석 생성은 "사실을 있는 그대로 추출"하는 작업이라 창의성이 필요 없다.
            // OpenAI 호환 서버(Ollama 등)의 기본 temperature(보통 0.7~0.8)를 그대로 쓰면
            // 소형 로컬 모델일수록 시스템 프롬프트의 형식 예시 문장을 창작하듯 베끼거나
            // 근거 없는 내용을 지어내는 경향이 커진다(2026-07-23 실측: qwen2.5-coder:7b가
            // prompt.md의 예시 문장을 거의 그대로 재사용한 사례 확인). 기본값을 낮게 잡아
            // 입력에 더 충실하게(deterministic) 만든다 — 필요하면 시나리오/모델별로 조정.
            @Value("${llm.local.temperature:0.2}") double temperature,
            // 기본 1 — CPU 전용 로컬 Ollama는 보통 요청을 직렬로 처리하므로 안전한 기본값.
            // GPU 서버 등 실제 병렬 서빙이 되는 백엔드라면 시나리오별로 올려서 튜닝 가능.
            @Value("${llm.local.max-concurrent-calls:1}") int maxConcurrentCalls) {
        this.temperature = temperature;
        this.concurrencyGate = new Semaphore(Math.max(1, maxConcurrentCalls));
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
        requestBody.put("temperature", temperature);
        requestBody.put("messages", List.of(systemMessage, userMessage));

        Map<?, ?> response;
        try {
            concurrencyGate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("로컬 LLM 호출 대기 중 인터럽트됨", e);
        }
        try {
            response = webClient.post()
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
        } finally {
            concurrencyGate.release();
        }

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
