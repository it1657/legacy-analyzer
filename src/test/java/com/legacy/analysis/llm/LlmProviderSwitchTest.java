package com.legacy.analysis.llm;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scenario_0.md의 핵심 목표: "설정 프로퍼티(llm.provider) 하나만 바꾸면 코드 재빌드 없이
 * Claude API 대신 자체 호스팅 LLM으로 요청이 나간다"를 실제로 증명하는 테스트.
 *
 * 실제 Anthropic 서버와 실제 자체 호스팅 LLM 서버(vLLM/Ollama 등) 대신, 각각을 흉내내는
 * MockWebServer 두 대를 세워두고 llm.provider 값에 따라 어느 쪽에 요청이 도착하는지를 센다.
 * "진짜 LLM이 좋은 답을 주는지"는 이 테스트의 범위가 아니다 — 그건 실제 서버가 필요한
 * scenario_1/2/3.md 단계의 검증이고, 여기서는 "설정값만으로 요청 목적지가 실제로 바뀐다"는
 * 배선(라우팅) 자체를 증명한다. 그래서 실제 자체 LLM 서버 정보 없이도 통과할 수 있다.
 */
class LlmProviderSwitchTest {

    private MockWebServer anthropicServer;
    private MockWebServer localLlmServer;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            // AnthropicLlmClient/OpenAiCompatibleLlmClient는 @Value("${...}")로 프로퍼티를 직접
            // 주입받으므로, ApplicationContextRunner에 플레이스홀더 해석기를 명시적으로 등록해야 한다.
            .withBean(PropertySourcesPlaceholderConfigurer.class)
            .withUserConfiguration(AnthropicLlmClient.class, OpenAiCompatibleLlmClient.class);

    @BeforeEach
    void setUp() throws IOException {
        anthropicServer = new MockWebServer();
        anthropicServer.start();
        localLlmServer = new MockWebServer();
        localLlmServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        anthropicServer.shutdown();
        localLlmServer.shutdown();
    }

    @Test
    void llm_provider_미설정시_기본값_anthropic이_활성화되고_요청은_anthropic_서버로만_간다() throws Exception {
        anthropicServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":{}}"));

        contextRunner
                .withPropertyValues(
                        // llm.provider 자체를 설정하지 않음 — matchIfMissing=true 기본값 확인용
                        "anthropic.api.key=test-key",
                        "anthropic.api.url=http://localhost:" + anthropicServer.getPort(),
                        // 로컬 쪽 값도 실제 운영처럼 채워두되, provider가 anthropic이므로 안 쓰여야 함
                        "llm.local.url=http://localhost:" + localLlmServer.getPort(),
                        "llm.local.api-key=",
                        "llm.local.read-timeout-sec=300")
                .run(context -> {
                    assertThat(context).hasSingleBean(LlmClient.class);
                    LlmClient client = context.getBean(LlmClient.class);
                    assertThat(client).isInstanceOf(AnthropicLlmClient.class);

                    client.call("system prompt", "user content", "claude-sonnet-5", 100);
                });

        assertThat(anthropicServer.getRequestCount()).isEqualTo(1);
        assertThat(localLlmServer.getRequestCount()).isEqualTo(0);
    }

    @Test
    void llm_provider를_local로_바꾸면_같은_호출이_anthropic_대신_자체_LLM_서버로_간다() throws Exception {
        localLlmServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":{}}"));

        contextRunner
                .withPropertyValues(
                        "llm.provider=local",
                        "anthropic.api.key=test-key",
                        "anthropic.api.url=http://localhost:" + anthropicServer.getPort(),
                        "llm.local.url=http://localhost:" + localLlmServer.getPort(),
                        "llm.local.api-key=",
                        "llm.local.read-timeout-sec=300")
                .run(context -> {
                    assertThat(context).hasSingleBean(LlmClient.class);
                    LlmClient client = context.getBean(LlmClient.class);
                    assertThat(client).isInstanceOf(OpenAiCompatibleLlmClient.class);

                    // AnalyzeCodeWithClaude 등이 호출하던 것과 동일한 형태의 호출 —
                    // 코드는 그대로인데 설정값만 바뀐 상태에서 실제로 어디로 나가는지 확인
                    client.call("system prompt", "user content", "qwen3-32b", 100);
                });

        assertThat(localLlmServer.getRequestCount()).isEqualTo(1);
        assertThat(anthropicServer.getRequestCount()).isEqualTo(0);
    }
}
