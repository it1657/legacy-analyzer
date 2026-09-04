package com.legacy.analysis.llm;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OllamaModelDiscoveryClient}가 Ollama {@code GET /api/tags} 응답을 정상 파싱하고,
 * 조회 실패(HTTP 오류/연결 거부)를 예외 전파 없이 {@code Optional.empty()}로 구분해 돌려주는지
 * 검증한다(REQ-003, 2026-09).
 *
 * "조회 성공 vs 조회 실패"의 구분이 이 기능의 핵심이다 — 게이트1 사람 결정(절충안)에 따라
 * 성공이면 목록에 없는 modelKey를 하드 차단하고, 실패면 자유 입력을 허용하기 때문이다.
 * 즉 실패 경로가 기능의 절반이라 반드시 함께 검증한다.
 *
 * 실제 Ollama 서버 대신 {@link MockWebServer}를 세워 응답을 흉내낸다({@code LlmProviderSwitchTest}와
 * 동일한 패턴). 이 클라이언트는 @Value로만 프로퍼티를 받으므로 스프링 컨테이너 없이 직접 생성한다.
 */
class OllamaModelDiscoveryClientTest {

  private MockWebServer ollamaServer;

  @BeforeEach
  void setUp() throws IOException {
    ollamaServer = new MockWebServer();
    ollamaServer.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    ollamaServer.shutdown();
  }

  private OllamaModelDiscoveryClient newClient() {
    return new OllamaModelDiscoveryClient("http://localhost:" + ollamaServer.getPort(), 5);
  }

  @Test
  void 설치된_모델_목록을_정상_파싱한다() throws Exception {
    ollamaServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("{\"models\":[{\"name\":\"qwen2.5-coder:7b\"},{\"name\":\"llama3:8b\"}]}"));

    Optional<List<String>> result = newClient().listInstalledModels();

    assertThat(result).isPresent();
    assertThat(result.get()).containsExactly("qwen2.5-coder:7b", "llama3:8b");
    RecordedRequest request = ollamaServer.takeRequest();
    assertThat(request.getPath()).isEqualTo("/api/tags");
    assertThat(request.getMethod()).isEqualTo("GET");
  }

  @Test
  void 모델이_하나도_설치되지_않았어도_조회성공으로_처리한다() {
    // Ollama가 응답한 이상 "확실히 연결됨"이므로 빈 목록도 Optional.of(빈 리스트)여야 한다
    // (하드 차단 대상 — Optional.empty()로 뭉뚱그리면 자유 입력이 잘못 허용된다).
    ollamaServer.enqueue(new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("{\"models\":[]}"));

    Optional<List<String>> result = newClient().listInstalledModels();

    assertThat(result).isPresent();
    assertThat(result.get()).isEmpty();
  }

  @Test
  void 서버가_에러를_반환하면_조회실패로_처리한다() {
    ollamaServer.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

    Optional<List<String>> result = newClient().listInstalledModels();

    assertThat(result).isEmpty();
  }

  @Test
  void 연결_자체가_안되면_조회실패로_처리한다() throws Exception {
    // 방금 띄운 MockWebServer를 곧바로 내려 "아무도 듣고 있지 않은 포트"를 만든다
    // (= Ollama가 안 떠 있는 환경). 예외가 밖으로 새면 앱 기동/관리자 화면이 통째로 깨진다.
    int deadPort = ollamaServer.getPort();
    ollamaServer.shutdown();
    OllamaModelDiscoveryClient client = new OllamaModelDiscoveryClient("http://localhost:" + deadPort, 2);

    Optional<List<String>> result = client.listInstalledModels();

    assertThat(result).isEmpty();
    // tearDown()의 두 번째 shutdown()은 멱등이라 안전하다.
  }
}
