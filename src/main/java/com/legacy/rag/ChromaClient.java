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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chroma REST **v2** API(⚠️ Chroma 1.0.0부터 v1이 완전히 제거되고 `/api/v2/...`로 대체됨 — 지금
 * `docker-compose.yml`이 고정한 `chromadb/chroma:1.5.9`는 v2 전용이므로 이 클라이언트도 v2로만
 * 구현한다)를 감싸는 얇은 WebClient 래퍼. `tenant`/`database`는 Chroma 서버가 기본 제공하는
 * `default_tenant`/`default_database`를 그대로 쓴다는 전제라 별도 생성 API는 호출하지 않는다.
 *
 * rag.enabled=true일 때만 빈으로 등록된다.
 */
@Component
@ConditionalOnProperty(name = "rag.enabled", havingValue = "true")
public class ChromaClient implements VectorStoreClient {

    private static final Logger log = LoggerFactory.getLogger(ChromaClient.class);

    private final WebClient webClient;
    private final String tenant;
    private final String database;

    // 컬렉션 이름 → id 캐시. 같은 JVM 안에서 index()가 만든 컬렉션을 이후 query/delete에서
    // 이름만으로도 다시 찾을 수 있게 한다(호출자가 매번 id를 직접 들고 다니지 않아도 됨).
    private final Map<String, String> collectionIdCache = new ConcurrentHashMap<>();

    public ChromaClient(
            @Value("${rag.chroma.url}") String baseUrl,
            @Value("${rag.chroma.tenant:default_tenant}") String tenant,
            @Value("${rag.chroma.database:default_database}") String database) {
        this.tenant = tenant;
        this.database = database;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private String collectionsPath() {
        return String.format("/api/v2/tenants/%s/databases/%s/collections", tenant, database);
    }

    /**
     * 이름으로 컬렉션을 가져오거나(없으면) 생성하고, id를 반환한다. 결과는 캐시되므로 같은
     * 이름으로 다시 호출해도 원격 호출 없이 즉시 반환된다.
     */
    @Override
    public String createOrGetCollection(String name) {
        String cached = collectionIdCache.get(name);
        if (cached != null) return cached;

        Map<String, Object> body = Map.of("name", name, "get_or_create", true);
        Map<?, ?> response = post(collectionsPath(), body, "컬렉션 생성/조회");
        Object id = response.get("id");
        if (id == null) {
            throw new RuntimeException("Chroma 컬렉션 생성 응답에 id가 없음: " + response);
        }
        String collectionId = String.valueOf(id);
        collectionIdCache.put(name, collectionId);
        return collectionId;
    }

    /** ids/embeddings/documents/metadatas는 모두 같은 길이의 병렬 리스트여야 한다. */
    @Override
    public void upsert(String collectionId, List<String> ids, List<List<Double>> embeddings,
            List<String> documents, List<Map<String, Object>> metadatas) {
        if (ids.isEmpty()) return;

        Map<String, Object> body = new HashMap<>();
        body.put("ids", ids);
        body.put("embeddings", embeddings);
        body.put("documents", documents);
        if (metadatas != null) body.put("metadatas", metadatas);

        post(collectionsPath() + "/" + collectionId + "/add", body, "문서 upsert");
    }

    /**
     * @param where 메타데이터 필터(예: {"package": "com.legacy.rag"}), 필터 없으면 null
     * @return 유사도 상위 topK개 document 텍스트(쿼리 1개 기준 — Chroma는 쿼리 배치를 지원하지만
     *         이 서비스는 항상 쿼리 1개씩만 보내므로 결과의 첫 번째 묶음만 사용한다)
     */
    @Override
    public List<String> query(String collectionId, List<Double> queryEmbedding, int topK, Map<String, Object> where) {
        Map<String, Object> body = new HashMap<>();
        body.put("query_embeddings", List.of(queryEmbedding));
        body.put("n_results", topK);
        if (where != null && !where.isEmpty()) body.put("where", where);

        Map<?, ?> response = post(collectionsPath() + "/" + collectionId + "/query", body, "유사도 검색");
        Object documentsObj = response.get("documents");
        if (!(documentsObj instanceof List<?> outer) || outer.isEmpty()) {
            return List.of();
        }
        Object firstBatch = outer.get(0);
        if (!(firstBatch instanceof List<?> inner)) {
            return List.of();
        }
        return inner.stream().map(String::valueOf).toList();
    }

    /**
     * 실제 Chroma v2 서버(1.5.9 확인)는 다른 엔드포인트(생성/조회/add/query)와 달리 DELETE만
     * id가 아니라 컬렉션 **이름**을 받는다(id로 호출하면 404 NotFoundError) — 실제 서버 대상
     * 재현으로 확인된 동작이라 여기도 이름을 그대로 받는다. 호출부(`createOrGetCollection`을
     * 호출할 때 쓴 이름)를 그대로 넘겨야 한다.
     */
    @Override
    public void deleteCollection(String name) {
        try {
            webClient.delete()
                    .uri(collectionsPath() + "/" + name)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .flatMap(msg -> Mono.error(new WebClientResponseException(
                                            clientResponse.statusCode().value(), "컬렉션 삭제 실패: " + msg,
                                            clientResponse.headers().asHttpHeaders(), null, null))))
                    .toBodilessEntity()
                    .block();
        } finally {
            // 실패해도 다음 index()가 get_or_create로 다시 잡을 수 있으니 예외를 삼켜도
            // 안전(호출부가 cleanup 실패로 전체를 막으면 안 됨).
            collectionIdCache.remove(name);
        }
    }

    private Map<?, ?> post(String uri, Map<String, Object> body, String actionLabel) {
        Map<?, ?> response = webClient.post()
                .uri(uri)
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(msg -> {
                                    int statusCode = clientResponse.statusCode().value();
                                    String errMsg = String.format("Chroma %s %d 오류: %s", actionLabel, statusCode,
                                            msg.isEmpty() ? "응답 없음" : msg.substring(0, Math.min(300, msg.length())));
                                    log.error("[Chroma API 응답 오류] {}", errMsg);
                                    return Mono.error(new WebClientResponseException(
                                            statusCode, errMsg,
                                            clientResponse.headers().asHttpHeaders(), null, null));
                                }))
                .bodyToMono(Map.class)
                .block();
        return response == null ? Map.of() : response;
    }
}
