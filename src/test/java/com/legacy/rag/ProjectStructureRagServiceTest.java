package com.legacy.rag;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ProjectStructureRagService}를 실제 {@link ChromaClient}/{@link OpenAiCompatibleEmbeddingClient}
 * 조합으로, 각각을 별도 MockWebServer(임베딩 서버 역할 하나·Chroma 서버 역할 하나)로 흉내내
 * 검증한다. 세 클래스를 함께 엮은 통합 테스트에 가깝지만, 인터페이스 분리 없이도
 * {@code compactPackageGroups()}의 실제 호출 흐름(색인 → 쿼리 → 정리)을 그대로 검증할 수
 * 있어 이 조합을 택했다.
 */
class ProjectStructureRagServiceTest {

    private MockWebServer embeddingServer;
    private MockWebServer chromaServer;

    @BeforeEach
    void setUp() throws IOException {
        embeddingServer = new MockWebServer();
        embeddingServer.start();
        chromaServer = new MockWebServer();
        chromaServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        embeddingServer.shutdown();
        chromaServer.shutdown();
    }

    private ProjectStructureRagService newService(long triggerThresholdChars, int topKPerPackage) {
        EmbeddingClient embeddingClient = new OpenAiCompatibleEmbeddingClient(
                "http://localhost:" + embeddingServer.getPort(), "", 300, "nomic-embed-text");
        ChromaClient chromaClient = new ChromaClient(
                "http://localhost:" + chromaServer.getPort(), "default_tenant", "default_database");
        return new ProjectStructureRagService(chromaClient, embeddingClient, triggerThresholdChars, topKPerPackage);
    }

    private void enqueueEmbedding(int times) {
        for (int i = 0; i < times; i++) {
            embeddingServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("{\"embedding\": [0.1, 0.2]}"));
        }
    }

    @Test
    void 예상_크기가_임계값_이하면_RAG를_거치지_않고_원본을_그대로_반환한다() {
        ProjectStructureRagService service = newService(1_000_000, 30);

        Map<String, List<String>> packageGroups = new LinkedHashMap<>();
        packageGroups.put("com.example", List.of("A.java", "B.java"));

        Map<String, List<String>> result = service.compactPackageGroups("session-1", packageGroups);

        assertSame(packageGroups, result, "임계값 이하면 RAG 개입 없이 원본 참조를 그대로 반환해야 함");
        assertEquals(0, embeddingServer.getRequestCount());
        assertEquals(0, chromaServer.getRequestCount());
    }

    @Test
    void 임계값을_초과하면_패키지당_topK개로_압축하고_컬렉션을_정리한다() throws Exception {
        ProjectStructureRagService service = newService(1, 2); // 임계값 1자 → 무조건 압축 트리거

        Map<String, List<String>> packageGroups = new LinkedHashMap<>();
        packageGroups.put("pkg.a", List.of("A1.java", "A2.java", "A3.java", "A4.java")); // 4개 > topK(2) → 쿼리 발생
        packageGroups.put("pkg.b", List.of("B1.java")); // 1개 <= topK(2) → 원본 그대로, 쿼리 없음

        // index() 단계: pkg.a 4개 + pkg.b 1개 = 5개 문서 임베딩, 이후 pkg.a 압축 쿼리용 임베딩 1개 = 총 6개
        enqueueEmbedding(6);

        chromaServer.enqueue(new MockResponse() // createOrGetCollection
                .setResponseCode(200).addHeader("Content-Type", "application/json")
                .setBody("{\"id\": \"col-1\"}"));
        chromaServer.enqueue(new MockResponse() // upsert(add)
                .setResponseCode(200).addHeader("Content-Type", "application/json")
                .setBody("{}"));
        chromaServer.enqueue(new MockResponse() // query(pkg.a)
                .setResponseCode(200).addHeader("Content-Type", "application/json")
                .setBody("{\"documents\": [[\"pkg.a :: A2.java\", \"pkg.a :: A4.java\"]]}"));
        chromaServer.enqueue(new MockResponse() // deleteCollection
                .setResponseCode(200).setBody("{}"));

        Map<String, List<String>> result = service.compactPackageGroups("session-1", packageGroups);

        assertEquals(List.of("A2.java", "A4.java"), result.get("pkg.a"), "쿼리 결과에서 파일명만 추출해야 함");
        assertEquals(List.of("B1.java"), result.get("pkg.b"), "topK 이하 패키지는 원본 그대로여야 함");
        assertEquals(4, chromaServer.getRequestCount(), "create+add+query+delete = 4회 호출");

        // 호출 순서 검증: create → add → query → delete
        assertTrue(chromaServer.takeRequest().getPath().endsWith("/collections"));
        assertTrue(chromaServer.takeRequest().getPath().endsWith("/collections/col-1/add"));
        assertTrue(chromaServer.takeRequest().getPath().endsWith("/collections/col-1/query"));
        var deleteRequest = chromaServer.takeRequest();
        assertEquals("DELETE", deleteRequest.getMethod());
        assertTrue(deleteRequest.getPath().endsWith("/collections/col-1"));
    }

    @Test
    void 쿼리_단계에서_실패해도_원본을_그대로_반환하고_컬렉션은_정리한다() throws Exception {
        ProjectStructureRagService service = newService(1, 2);

        Map<String, List<String>> packageGroups = new LinkedHashMap<>();
        packageGroups.put("pkg.a", List.of("A1.java", "A2.java", "A3.java"));

        enqueueEmbedding(4); // index() 3개 + 쿼리용 1개(쿼리 자체는 실패하지만 임베딩 호출은 그 전에 일어남)

        chromaServer.enqueue(new MockResponse() // createOrGetCollection
                .setResponseCode(200).addHeader("Content-Type", "application/json")
                .setBody("{\"id\": \"col-1\"}"));
        chromaServer.enqueue(new MockResponse() // upsert(add)
                .setResponseCode(200).addHeader("Content-Type", "application/json")
                .setBody("{}"));
        chromaServer.enqueue(new MockResponse() // query 실패
                .setResponseCode(500).setBody("{\"error\":\"boom\"}"));
        chromaServer.enqueue(new MockResponse() // deleteCollection (finally에서 여전히 호출돼야 함)
                .setResponseCode(200).setBody("{}"));

        Map<String, List<String>> result = service.compactPackageGroups("session-1", packageGroups);

        assertEquals(packageGroups, result, "실패 시 원본 그대로 반환해야 함(안전 fallback)");
        assertEquals(4, chromaServer.getRequestCount(), "실패해도 finally에서 delete까지 호출돼야 함");
    }

    @Test
    void 빈_맵이면_그대로_반환하고_아무_호출도_하지_않는다() {
        ProjectStructureRagService service = newService(1, 2);

        Map<String, List<String>> result = service.compactPackageGroups("session-1", Map.of());

        assertTrue(result.isEmpty());
        assertEquals(0, embeddingServer.getRequestCount());
        assertEquals(0, chromaServer.getRequestCount());
    }
}
