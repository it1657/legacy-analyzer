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
}
