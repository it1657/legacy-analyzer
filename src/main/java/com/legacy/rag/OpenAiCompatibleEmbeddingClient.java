package com.legacy.rag;

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
import java.util.List;
import java.util.Map;

/**
 * Ollama의 임베딩 엔드포인트(`POST /api/embeddings`)를 대상으로 하는 {@link EmbeddingClient}
 * 구현체 — plan.md "RAG(Chroma)" 절에서 확정한 설계. Chat 모델과 마찬가지로
 * {@code llm.local.url}(같은 Ollama 서버, 같은 인증)을 재사용하고, 임베딩 전용 모델명만
 * {@code rag.embedding.model}로 별도 설정한다(`docker/ollama-entrypoint.sh`가 이 모델을
 * chat 모델과 별개로 pull해둔다는 전제).
 *
 * rag.enabled=true일 때만 빈으로 등록된다 — 기본값(false)에서는 이 클라이언트 자체가
 * 존재하지 않으므로 RAG 미채택 배포(회사 서버 등)에는 아무 영향이 없다.
 */
@Component
@ConditionalOnProperty(name = "rag.enabled", havingValue = "true")
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingClient.class);

    private final WebClient webClient;
    private final String model;

    public OpenAiCompatibleEmbeddingClient(
            @Value("${llm.local.url}") String baseUrl,
            @Value("${llm.local.api-key:}") String apiKey,
            @Value("${llm.local.read-timeout-sec:300}") long readTimeoutSec,
            @Value("${rag.embedding.model:nomic-embed-text}") String model) {
        this.model = model;

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(readTimeoutSec));

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        this.webClient = builder.build();
    }

    @Override
    public List<Double> embed(String text) {
        Map<String, Object> requestBody = Map.of("model", model, "prompt", text);

        Map<?, ?> response = webClient.post()
                .uri("/api/embeddings")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    int statusCode = clientResponse.statusCode().value();
                                    String msg = String.format("임베딩 API %d 오류: %s", statusCode,
                                            body.isEmpty() ? "응답 없음" : body.substring(0, Math.min(300, body.length())));
                                    log.error("[임베딩 API 응답 오류] {}", msg);
                                    return Mono.error(new WebClientResponseException(
                                            statusCode, msg,
                                            clientResponse.headers().asHttpHeaders(), null, null));
                                }))
                .bodyToMono(Map.class)
                .block();

        if (response == null || !response.containsKey("embedding")) {
            throw new RuntimeException("임베딩 응답 바디에 embedding 필드가 없음");
        }
        List<?> raw = (List<?>) response.get("embedding");
        if (raw == null || raw.isEmpty()) {
            throw new RuntimeException("임베딩 응답의 embedding 배열이 비어있음");
        }
        return raw.stream()
                .map(v -> v instanceof Number number ? number.doubleValue() : 0.0)
                .toList();
    }

    /**
     * Ollama의 배치 임베딩 엔드포인트(`POST /api/embed`, 복수형 — {@link #embed}가 쓰는
     * `/api/embeddings`와는 별개 엔드포인트)를 사용한다. `input`에 텍스트 배열을 한 번에
     * 보내고 `embeddings`(배열의 배열)로 응답받아, 텍스트 수만큼 왕복하던 것을 호출 1번으로
     * 줄인다 — {@code ProjectStructureRagService.index()}가 파일이 많은 프로젝트에서 이
     * 메서드를 쓰는 이유.
     */
    @Override
    public List<List<Double>> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        Map<String, Object> requestBody = Map.of("model", model, "input", texts);

        Map<?, ?> response = webClient.post()
                .uri("/api/embed")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    int statusCode = clientResponse.statusCode().value();
                                    String msg = String.format("배치 임베딩 API %d 오류: %s", statusCode,
                                            body.isEmpty() ? "응답 없음" : body.substring(0, Math.min(300, body.length())));
                                    log.error("[배치 임베딩 API 응답 오류] {}", msg);
                                    return Mono.error(new WebClientResponseException(
                                            statusCode, msg,
                                            clientResponse.headers().asHttpHeaders(), null, null));
                                }))
                .bodyToMono(Map.class)
                .block();

        if (response == null || !response.containsKey("embeddings")) {
            throw new RuntimeException("배치 임베딩 응답 바디에 embeddings 필드가 없음");
        }
        List<?> raw = (List<?>) response.get("embeddings");
        if (raw == null || raw.size() != texts.size()) {
            throw new RuntimeException("배치 임베딩 응답 개수(" + (raw == null ? 0 : raw.size())
                    + ")가 요청 개수(" + texts.size() + ")와 다름");
        }
        return raw.stream()
                .map(row -> ((List<?>) row).stream()
                        .map(v -> v instanceof Number number ? number.doubleValue() : 0.0)
                        .toList())
                .toList();
    }
}
