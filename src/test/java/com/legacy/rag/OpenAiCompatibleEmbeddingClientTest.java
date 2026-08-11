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

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OpenAiCompatibleEmbeddingClient}를 MockWebServer로 Ollama `/api/embeddings`
 * 엔드포인트를 흉내내 검증한다 — 같은 패키지의 {@code OpenAiCompatibleLlmClientTest}와
 * 동일한 패턴.
 */
class OpenAiCompatibleEmbeddingClientTest {

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

    @Test
    void 요청_경로와_바디가_Ollama_임베딩_규격대로_전송된다() throws Exception {
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(baseUrl(), "", 300, "nomic-embed-text");

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"embedding\": [0.1, 0.2, 0.3]}"));

        client.embed("com.legacy.rag :: ChromaClient.java");

        RecordedRequest recorded = server.takeRequest();
        assertEquals("/api/embeddings", recorded.getPath());
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"model\":\"nomic-embed-text\""));
        assertTrue(body.contains("ChromaClient.java"));
    }

    @Test
    void api_key가_있으면_Authorization_헤더가_포함된다() throws Exception {
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(baseUrl(), "secret", 300, "nomic-embed-text");

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"embedding\": [0.1]}"));

        client.embed("text");

        RecordedRequest recorded = server.takeRequest();
        assertEquals("Bearer secret", recorded.getHeader("Authorization"));
    }

    @Test
    void embedding_배열을_double_리스트로_파싱한다() throws Exception {
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(baseUrl(), "", 300, "nomic-embed-text");

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"embedding\": [0.1, -0.5, 2.0]}"));

        List<Double> result = client.embed("text");

        assertEquals(List.of(0.1, -0.5, 2.0), result);
    }

    @Test
    void 오류_응답이면_예외를_던진다() {
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(baseUrl(), "", 300, "nomic-embed-text");

        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"internal error\"}"));

        assertThrows(WebClientResponseException.class, () -> client.embed("text"));
    }

    @Test
    void embedding_필드가_없으면_예외를_던진다() {
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(baseUrl(), "", 300, "nomic-embed-text");

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"unexpected\": true}"));

        assertThrows(RuntimeException.class, () -> client.embed("text"));
    }

    @Test
    void embedBatch_요청_경로와_바디가_배치_규격대로_전송된다() throws Exception {
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(baseUrl(), "", 300, "nomic-embed-text");

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"embeddings\": [[0.1, 0.2], [0.3, 0.4]]}"));

        client.embedBatch(List.of("com.legacy.rag :: A.java", "com.legacy.rag :: B.java"));

        RecordedRequest recorded = server.takeRequest();
        assertEquals("/api/embed", recorded.getPath(), "embed()의 단일 엔드포인트(/api/embeddings)와 달리 배치는 /api/embed(복수형)");
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"model\":\"nomic-embed-text\""));
        assertTrue(body.contains("A.java"));
        assertTrue(body.contains("B.java"));
    }

    @Test
    void embedBatch_embeddings_배열을_입력_순서대로_파싱한다() {
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(baseUrl(), "", 300, "nomic-embed-text");

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"embeddings\": [[0.1, -0.5], [2.0, 3.0]]}"));

        List<List<Double>> result = client.embedBatch(List.of("A.java", "B.java"));

        assertEquals(List.of(List.of(0.1, -0.5), List.of(2.0, 3.0)), result);
    }

    @Test
    void embedBatch_빈_목록이면_호출_없이_빈_리스트를_반환한다() {
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(baseUrl(), "", 300, "nomic-embed-text");

        List<List<Double>> result = client.embedBatch(List.of());

        assertTrue(result.isEmpty());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void embedBatch_오류_응답이면_예외를_던진다() {
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(baseUrl(), "", 300, "nomic-embed-text");

        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"internal error\"}"));

        assertThrows(WebClientResponseException.class, () -> client.embedBatch(List.of("A.java")));
    }

    @Test
    void embedBatch_embeddings_필드가_없으면_예외를_던진다() {
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(baseUrl(), "", 300, "nomic-embed-text");

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"unexpected\": true}"));

        assertThrows(RuntimeException.class, () -> client.embedBatch(List.of("A.java")));
    }

    @Test
    void embedBatch_응답_개수가_요청_개수와_다르면_예외를_던진다() {
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(baseUrl(), "", 300, "nomic-embed-text");

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"embeddings\": [[0.1, 0.2]]}")); // 요청은 2개인데 응답은 1개

        assertThrows(RuntimeException.class, () -> client.embedBatch(List.of("A.java", "B.java")));
    }
}
