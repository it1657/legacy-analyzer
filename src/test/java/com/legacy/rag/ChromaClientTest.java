package com.legacy.rag;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChromaClient}를 MockWebServer로 Chroma v2 REST API를 흉내내 검증한다.
 * 실제 Chroma 서버 응답 계약(⚠️ v2 전용, plan.md "RAG(Chroma)" 절에서 확인)을 그대로 흉내낸다.
 */
class ChromaClientTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private String baseUrl() {
        return "http://localhost:" + server.getPort();
    }

    private ChromaClient newClient() {
        return new ChromaClient(baseUrl(), "default_tenant", "default_database", 10485760);
    }

    @Test
    void 컬렉션_생성_요청_경로가_tenant_database_계층을_포함한다() throws Exception {
        ChromaClient client = newClient();

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"id\": \"col-123\", \"name\": \"session-abc\"}"));

        String collectionId = client.createOrGetCollection("session-abc");

        RecordedRequest recorded = server.takeRequest();
        assertEquals("/api/v2/tenants/default_tenant/databases/default_database/collections", recorded.getPath());
        assertTrue(recorded.getBody().readUtf8().contains("\"get_or_create\":true"));
        assertEquals("col-123", collectionId);
    }

    @Test
    void 같은_이름으로_두번_호출하면_두번째는_네트워크_호출없이_캐시된_id를_반환한다() throws Exception {
        ChromaClient client = newClient();

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"id\": \"col-123\"}"));

        String first = client.createOrGetCollection("session-abc");
        String second = client.createOrGetCollection("session-abc");

        assertEquals(1, server.getRequestCount(), "두 번째 호출은 캐시로 처리돼 네트워크 호출이 없어야 함");
        assertEquals(first, second);
    }

    @Test
    void upsert_요청_경로와_바디가_add_엔드포인트_규격대로_전송된다() throws Exception {
        ChromaClient client = newClient();

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{}"));

        client.upsert("col-123",
                List.of("doc-0", "doc-1"),
                List.of(List.of(0.1, 0.2), List.of(0.3, 0.4)),
                List.of("pkg :: A.java", "pkg :: B.java"),
                List.of(Map.of("package", "pkg"), Map.of("package", "pkg")));

        RecordedRequest recorded = server.takeRequest();
        assertEquals("/api/v2/tenants/default_tenant/databases/default_database/collections/col-123/add",
                recorded.getPath());
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("doc-0"));
        assertTrue(body.contains("A.java"));
    }

    @Test
    void upsert_ids가_비어있으면_네트워크_호출을_하지_않는다() throws Exception {
        ChromaClient client = newClient();

        client.upsert("col-123", List.of(), List.of(), List.of(), List.of());

        assertEquals(0, server.getRequestCount());
    }

    @Test
    void query_요청_바디에_where_필터가_포함되고_첫번째_결과_묶음을_반환한다() throws Exception {
        ChromaClient client = newClient();

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"documents\": [[\"pkg :: A.java\", \"pkg :: B.java\"]]}"));

        List<String> results = client.query("col-123", List.of(0.1, 0.2), 5, Map.of("package", "pkg"));

        RecordedRequest recorded = server.takeRequest();
        assertEquals("/api/v2/tenants/default_tenant/databases/default_database/collections/col-123/query",
                recorded.getPath());
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"n_results\":5"));
        assertTrue(body.contains("\"where\""));
        assertEquals(List.of("pkg :: A.java", "pkg :: B.java"), results);
    }

    @Test
    void query_where가_null이면_where_필드를_보내지_않는다() throws Exception {
        ChromaClient client = newClient();

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"documents\": [[]]}"));

        client.query("col-123", List.of(0.1), 5, null);

        RecordedRequest recorded = server.takeRequest();
        assertFalse(recorded.getBody().readUtf8().contains("\"where\""));
    }

    @Test
    void query_documents가_비어있으면_빈_리스트를_반환한다() throws Exception {
        ChromaClient client = newClient();

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"documents\": []}"));

        List<String> results = client.query("col-123", List.of(0.1), 5, null);

        assertTrue(results.isEmpty());
    }

    @Test
    void deleteCollection_요청_경로가_컬렉션_id를_포함한다() throws Exception {
        ChromaClient client = newClient();
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"id\": \"col-999\"}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        client.createOrGetCollection("session-abc");
        client.deleteCollection("col-123");

        server.takeRequest(); // createOrGetCollection 요청 소비
        RecordedRequest recorded = server.takeRequest();
        assertEquals("/api/v2/tenants/default_tenant/databases/default_database/collections/col-123",
                recorded.getPath());
        assertEquals("DELETE", recorded.getMethod());
    }

    @Test
    void 오류_응답이면_예외를_던진다() {
        ChromaClient client = newClient();

        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}"));

        assertThrows(WebClientResponseException.class, () -> client.createOrGetCollection("session-abc"));
    }
}
