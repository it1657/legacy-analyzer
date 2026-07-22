package com.legacy.analysis.llm;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AnthropicLlmClient}를 실제 Anthropic 서버 없이, MockWebServer로 흉내낸
 * `/v1/messages` 엔드포인트를 대상으로 검증한다.
 */
class AnthropicLlmClientTest {

    private MockWebServer server;
    private AnthropicLlmClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new AnthropicLlmClient("test-api-key", "http://localhost:" + server.getPort());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void 요청_헤더와_바디가_Anthropic_규격대로_전송된다() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                    {"content":[{"type":"text","text":"hello"}],
                     "usage":{"input_tokens":10,"output_tokens":5,
                               "cache_read_input_tokens":0,"cache_creation_input_tokens":0}}
                    """));

        client.call("system prompt", "user content", "claude-sonnet-5", 4096);

        RecordedRequest recorded = server.takeRequest();
        assertEquals("/v1/messages", recorded.getPath());
        assertEquals("test-api-key", recorded.getHeader("x-api-key"));
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"));
        assertEquals("prompt-caching-2024-07-31", recorded.getHeader("anthropic-beta"));

        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"model\":\"claude-sonnet-5\""));
        assertTrue(body.contains("\"max_tokens\":4096"));
        assertTrue(body.contains("\"cache_control\":{\"type\":\"ephemeral\"}"));
        assertTrue(body.contains("system prompt"));
        assertTrue(body.contains("user content"));
    }

    @Test
    void 응답의_텍스트와_토큰사용량을_파싱한다() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                    {"content":[{"type":"text","text":"생성된 답변"}],
                     "usage":{"input_tokens":123,"output_tokens":45,
                               "cache_read_input_tokens":67,"cache_creation_input_tokens":89}}
                    """));

        LlmResult result = client.call("sys", "user", "claude-sonnet-5", 4096);

        assertEquals("생성된 답변", result.text());
        assertEquals(123, result.inputTokens());
        assertEquals(45, result.outputTokens());
        assertEquals(67, result.cacheReadTokens());
        assertEquals(89, result.cacheCreationTokens());
    }

    @Test
    void usage_필드가_없으면_토큰값은_0으로_처리된다() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                    {"content":[{"type":"text","text":"답변"}]}
                    """));

        LlmResult result = client.call("sys", "user", "claude-sonnet-5", 4096);

        assertEquals("답변", result.text());
        assertEquals(0, result.inputTokens());
        assertEquals(0, result.outputTokens());
        assertEquals(0, result.cacheReadTokens());
        assertEquals(0, result.cacheCreationTokens());
    }

    @Test
    void 오류_응답이면_상태코드를_포함한_WebClientResponseException을_던진다() {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"rate limited\"}}"));

        WebClientResponseException ex = assertThrows(WebClientResponseException.class,
                () -> client.call("sys", "user", "claude-sonnet-5", 4096));

        assertEquals(429, ex.getStatusCode().value());
        assertTrue(ex.getMessage().contains("429"));
    }

    @Test
    void 크레딧_부족_메시지가_예외_메시지에_그대로_보존된다() {
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"Your credit balance is too low to access the API\"}}"));

        WebClientResponseException ex = assertThrows(WebClientResponseException.class,
                () -> client.call("sys", "user", "claude-sonnet-5", 4096));

        assertTrue(ex.getMessage().toLowerCase().contains("credit balance"));
    }
}
