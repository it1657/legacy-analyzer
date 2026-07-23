package com.legacy.analysis.llm;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OpenAiCompatibleLlmClient}를 실제 Ollama/vLLM 없이, MockWebServer로 흉내낸
 * OpenAI 호환 `/v1/chat/completions` 엔드포인트를 대상으로 검증한다.
 */
class OpenAiCompatibleLlmClientTest {

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
    void api_key가_있으면_Authorization_Bearer_헤더가_포함된다() throws Exception {
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(baseUrl(), "secret-key", 300, 0.2, 5);

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                    {"choices":[{"message":{"role":"assistant","content":"hi"}}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1}}
                    """));

        client.call("sys", "user", "qwen-test", 2048);

        RecordedRequest recorded = server.takeRequest();
        assertEquals("Bearer secret-key", recorded.getHeader("Authorization"));
    }

    @Test
    void api_key가_비어있으면_Authorization_헤더가_생략된다() throws Exception {
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(baseUrl(), "", 300, 0.2, 5);

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                    {"choices":[{"message":{"role":"assistant","content":"hi"}}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1}}
                    """));

        client.call("sys", "user", "llama3", 2048);

        RecordedRequest recorded = server.takeRequest();
        assertNull(recorded.getHeader("Authorization"));
    }

    @Test
    void 요청_경로와_바디가_OpenAI_호환_규격대로_전송된다() throws Exception {
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(baseUrl(), "", 300, 0.2, 5);

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                    {"choices":[{"message":{"role":"assistant","content":"ok"}}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1}}
                    """));

        client.call("system prompt", "user content", "qwen3-32b", 1024);

        RecordedRequest recorded = server.takeRequest();
        assertEquals("/v1/chat/completions", recorded.getPath());

        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"model\":\"qwen3-32b\""));
        assertTrue(body.contains("\"max_tokens\":1024"));
        assertTrue(body.contains("\"role\":\"system\""));
        assertTrue(body.contains("\"role\":\"user\""));
        assertTrue(body.contains("system prompt"));
        assertTrue(body.contains("user content"));
        assertTrue(body.contains("\"temperature\":0.2"));
    }

    @Test
    void temperature_생성자_값이_요청_바디에_그대로_반영된다() throws Exception {
        // 소형 로컬 모델의 할루시네이션/예시 문장 베끼기 완화를 위해 기본값을 낮게(0.2) 뒀는데
        // (2026-07-23 scenario_1 실측 기반), 이 값이 실제 요청에 반영되는지 검증한다.
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(baseUrl(), "", 300, 0.35, 5);

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                    {"choices":[{"message":{"role":"assistant","content":"ok"}}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1}}
                    """));

        client.call("sys", "user", "qwen-test", 1024);

        RecordedRequest recorded = server.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"temperature\":0.35"));
    }

    @Test
    void 응답의_텍스트와_토큰사용량을_파싱하고_캐시토큰은_항상_0이다() throws Exception {
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(baseUrl(), "", 300, 0.2, 5);

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                    {"choices":[{"message":{"role":"assistant","content":"생성된 응답"}}],
                     "usage":{"prompt_tokens":200,"completion_tokens":80}}
                    """));

        LlmResult result = client.call("sys", "user", "qwen3-32b", 1024);

        assertEquals("생성된 응답", result.text());
        assertEquals(200, result.inputTokens());
        assertEquals(80, result.outputTokens());
        assertEquals(0, result.cacheReadTokens());
        assertEquals(0, result.cacheCreationTokens());
    }

    @Test
    void 오류_응답이면_상태코드를_포함한_WebClientResponseException을_던진다() {
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(baseUrl(), "", 300, 0.2, 5);

        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"internal error\"}"));

        WebClientResponseException ex = assertThrows(WebClientResponseException.class,
                () -> client.call("sys", "user", "qwen3-32b", 1024));

        assertEquals(500, ex.getStatusCode().value());
        assertTrue(ex.getMessage().contains("500"));
    }

    @Test
    void max_concurrent_calls가_1이면_동시에_보내지는_실제_HTTP_요청이_한_개로_제한된다() throws Exception {
        // 2026-07-23 실측 버그 재현/회귀 방지용: 분석 스레드 풀(기본 16)이 동시에 이 클라이언트를
        // 호출해도, CPU 전용 로컬 Ollama처럼 요청을 하나씩만 처리하는 백엔드를 상대로는 실제
        // 전송되는 HTTP 요청 수가 max-concurrent-calls를 넘으면 안 된다(넘으면 뒤에 밀린
        // 요청이 처리되기도 전에 클라이언트 타임아웃을 넘겨 실패했음 — 100개 중 91개 실패 사례).
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxObservedInFlight = new AtomicInteger(0);

        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                int current = inFlight.incrementAndGet();
                maxObservedInFlight.updateAndGet(prev -> Math.max(prev, current));
                try {
                    Thread.sleep(150); // 로컬 LLM 추론 지연을 흉내냄
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                }
                return new MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Type", "application/json")
                        .setBody("""
                            {"choices":[{"message":{"role":"assistant","content":"ok"}}],
                             "usage":{"prompt_tokens":1,"completion_tokens":1}}
                            """);
            }
        });

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(baseUrl(), "", 300, 0.2, 1);

        int callCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(callCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(callCount);
        for (int i = 0; i < callCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    client.call("sys", "user", "qwen-test", 1024);
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "모든 호출이 제한 시간 안에 끝나야 함");
        executor.shutdownNow();

        assertEquals(1, maxObservedInFlight.get(), "max-concurrent-calls=1이면 실제 동시 HTTP 요청은 항상 1개여야 함");
    }
}
