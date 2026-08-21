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
 * MockWebServer 두 대를 세워두고 provider 값에 따라 어느 쪽에 요청이 도착하는지를 센다.
 *
 * 2026-08-21(모델 목록 DB화 + 크레딧소진 failover) 후속수정: 기존에는 {@code llm.provider}
 * 프로퍼티가 {@code @ConditionalOnProperty}로 두 LlmClient 구현체 중 "정확히 하나만" 스프링
 * 빈으로 등록되게 만들었다(그래서 이 테스트도 {@code hasSingleBean(LlmClient.class)}로
 * 검증했었다). 이제는 세션별로 Anthropic/로컬 모델을 런타임에 동시에 골라 써야 해서 두 빈이
 * 항상 함께 등록되고, "어느 쪽으로 요청이 나가는가"를 결정하는 책임이
 * {@code @ConditionalOnProperty}(스프링 컨테이너 기동 시점)에서 {@link LlmClientResolver}
 * (매 호출 시점, provider 파라미터 기반)로 옮겨갔다. 이 테스트도 그에 맞춰 "두 빈이 항상
 * 공존"하고 "LlmClientResolver가 provider 값에 맞는 구현체로 정확히 라우팅"하는지를
 * 검증하도록 갱신했다 — "설정값만으로 요청 목적지가 실제로 바뀐다"는 원래 목표 자체는 동일하다.
 */
class LlmProviderSwitchTest {

    private MockWebServer anthropicServer;
    private MockWebServer localLlmServer;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            // AnthropicLlmClient/OpenAiCompatibleLlmClient는 @Value("${...}")로 프로퍼티를 직접
            // 주입받으므로, ApplicationContextRunner에 플레이스홀더 해석기를 명시적으로 등록해야 한다.
            .withBean(PropertySourcesPlaceholderConfigurer.class)
            .withUserConfiguration(AnthropicLlmClient.class, OpenAiCompatibleLlmClient.class, LlmClientResolver.class);

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

    private ApplicationContextRunner withBothServerProperties() {
        return contextRunner.withPropertyValues(
                "anthropic.api.key=test-key",
                "anthropic.api.url=http://localhost:" + anthropicServer.getPort(),
                "llm.local.url=http://localhost:" + localLlmServer.getPort(),
                "llm.local.api-key=",
                "llm.local.read-timeout-sec=300");
    }

    @Test
    void 두_LlmClient_구현체가_항상_함께_스프링_빈으로_등록된다() {
        // @ConditionalOnProperty 제거(2026-08-21) 확인 — llm.provider 값과 무관하게 둘 다 뜬다.
        withBothServerProperties().run(context -> {
            assertThat(context.getBeansOfType(LlmClient.class)).hasSize(2);
            assertThat(context).hasSingleBean(AnthropicLlmClient.class);
            assertThat(context).hasSingleBean(OpenAiCompatibleLlmClient.class);
            assertThat(context).hasSingleBean(LlmClientResolver.class);
        });
    }

    @Test
    void LlmClientResolver가_ANTHROPIC을_넘기면_anthropic_서버로만_요청이_간다() throws Exception {
        anthropicServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":{}}"));

        withBothServerProperties().run(context -> {
            LlmClientResolver resolver = context.getBean(LlmClientResolver.class);
            LlmClient client = resolver.resolve(LlmProvider.ANTHROPIC);
            assertThat(client).isInstanceOf(AnthropicLlmClient.class);

            client.call("system prompt", "user content", "claude-sonnet-5", 100);
        });

        assertThat(anthropicServer.getRequestCount()).isEqualTo(1);
        assertThat(localLlmServer.getRequestCount()).isEqualTo(0);
    }

    @Test
    void LlmClientResolver가_LOCAL을_넘기면_같은_호출도_자체_LLM_서버로_간다() throws Exception {
        localLlmServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":{}}"));

        withBothServerProperties().run(context -> {
            LlmClientResolver resolver = context.getBean(LlmClientResolver.class);
            LlmClient client = resolver.resolve(LlmProvider.LOCAL);
            assertThat(client).isInstanceOf(OpenAiCompatibleLlmClient.class);

            // AnalyzeCodeWithClaude 등이 호출하던 것과 동일한 형태의 호출 —
            // 코드는 그대로인데 resolver에 넘긴 provider 값만 바뀐 상태에서 실제로 어디로 나가는지 확인
            client.call("system prompt", "user content", "qwen3-32b", 100);
        });

        assertThat(localLlmServer.getRequestCount()).isEqualTo(1);
        assertThat(anthropicServer.getRequestCount()).isEqualTo(0);
    }
}
