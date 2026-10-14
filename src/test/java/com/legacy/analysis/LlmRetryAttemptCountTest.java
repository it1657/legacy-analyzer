package com.legacy.analysis;

import com.legacy.analysis.llm.AnthropicErrorFixtures;
import com.legacy.analysis.llm.AnthropicLlmClient;
import com.legacy.analysis.llm.LlmClient;
import com.legacy.analysis.llm.LlmClientResolver;
import com.legacy.analysis.llm.LlmResult;
import com.legacy.core.ApiErrorHandler;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-005 (work-order 2026-10-counter-integrity-and-failure-reason v1, REQ-003-a) —
 * 파일 1개를 분석할 때 LLM을 <b>몇 번 부르는지</b>를 실제 호출 수로 고정한다.
 *
 * <p>구성은 운영과 같게 둔다: 실제 {@link ClaudeServiceImpl}(안쪽 재시도 루프) + 실제
 * {@link AnthropicLlmClient}({@link MockWebServer} 대상) + 실제 {@link RetryHandler}(바깥 재시도),
 * 그리고 <b>안쪽과 바깥에 같은 {@link SessionConfig} 인스턴스</b>를 넣는다. 재시도는 안쪽 한 겹이라
 * 시도 수는 첫 시도 + 재시도 {@code maxRetries}번 = <b>4회</b>다.
 *
 * <p>왜 {@code maxRetries}를 명시하는가(C2): 운영 속성과 같은 값을 이 테스트가 직접 못박아,
 * {@code SessionConfig}의 필드 초기값이 나중에 달라져도 재는 수가 흔들리지 않게 한다
 * ({@code new SessionConfig()}를 그대로 쓰면 그 초기값을 재게 된다).
 *
 * <p>시도 수를 무엇으로 세는가(C3): <b>{@link AnthropicLlmClient}를 감싼 위임 카운터</b>의
 * {@code call(...)} 횟수다. {@code MockWebServer.getRequestCount()}로는 <b>연결 거부를 셀 수 없다</b> —
 * 요청이 서버에 닿지 않으므로 항상 0이다. 응답을 받는 케이스에서는 두 값이 같은지 교차 단언한다.
 *
 * <p>전제 단언(C3): 모든 케이스에서 시도 수 ≥ 1을 먼저 본다. API 키 가드
 * ({@code ClaudeServiceImplApiKeyGuardRoutingTest} 선례)에 막혀 LLM이 아예 불리지 않으면
 * "1회"가 우연히 맞을 수 있기 때문이다. 키는 가드를 통과하는 무효 문자열을 쓴다.
 *
 * <p>같은 테스트 안에 <b>재시도가 살아 있어야 하는 경로</b>(429·500·연결 거부 = 4회)를 함께 둬서
 * 단언이 "항상 1"이 아님을 보인다(G-04 ②, RG-12).
 */
class LlmRetryAttemptCountTest {

  /** 운영과 같은 재시도 정책. 대기 시간만 짧게 둔다(C2). */
  private static final int MAX_RETRIES = 3;

  /** 첫 시도 + 재시도 3회. 바깥은 {@code AnalysisException}을 다시 재시도하지 않는다. */
  private static final int RETRYABLE_ATTEMPTS = 4;

  private static final String MODEL = "claude-sonnet-5";
  private static final String SESSION_ROOT = "/source/retry-attempt-count-session";

  /** 실제 클라이언트에 위임하면서 호출 횟수만 세는 껍데기. 분류·재시도에는 전혀 개입하지 않는다. */
  private static final class CountingDelegate implements LlmClient {
    private final LlmClient delegate;
    private int callCount;

    private CountingDelegate(LlmClient delegate) {
      this.delegate = delegate;
    }

    @Override
    public LlmResult call(String systemPrompt, String userContent, String model, int maxTokens) {
      callCount++;
      return delegate.call(systemPrompt, userContent, model, maxTokens);
    }
  }

  // ===================================================================
  // 상태 코드가 있는 오류
  // ===================================================================

  @Test
  void 인증실패_401은_파일당_1회만_시도한다() throws Exception {
    assertAttempts(AnthropicErrorFixtures.Case.AUTH_401_1, 1,
        ApiErrorHandler.ErrorType.API_AUTHENTICATION);
  }

  @Test
  void 권한없음_403은_파일당_1회만_시도한다() throws Exception {
    assertAttempts(AnthropicErrorFixtures.Case.FORBIDDEN_403, 1,
        ApiErrorHandler.ErrorType.API_FORBIDDEN);
  }

  /** 양성 대조군 — 수정 전에도 1회였다. */
  @Test
  void 크레딧소진_일반벌은_파일당_1회만_시도한다() throws Exception {
    assertAttempts(AnthropicErrorFixtures.Case.CREDIT_400, 1,
        ApiErrorHandler.ErrorType.INSUFFICIENT_CREDITS);
  }

  @Test
  void 크레딧소진_requestid에_io가_든_벌도_파일당_1회만_시도한다() throws Exception {
    assertAttempts(AnthropicErrorFixtures.Case.CREDIT_400_REQUEST_ID_IO, 1,
        ApiErrorHandler.ErrorType.INSUFFICIENT_CREDITS);
  }

  /** 양성 대조군 — 일시 오류의 재시도는 그대로 살아 있어야 한다(RG-12). */
  @Test
  void 요청한도초과_429는_4회_시도한다() throws Exception {
    assertAttempts(AnthropicErrorFixtures.Case.RATE_LIMIT_429, RETRYABLE_ATTEMPTS,
        ApiErrorHandler.ErrorType.API_RATE_LIMIT);
  }

  /** 양성 대조군 — 5xx 재시도 유지(RG-12). */
  @Test
  void 서버오류_500은_4회_시도한다() throws Exception {
    assertAttempts(AnthropicErrorFixtures.Case.SERVER_500, RETRYABLE_ATTEMPTS,
        ApiErrorHandler.ErrorType.SERVER_ERROR);
  }

  // ===================================================================
  // 상태 코드가 없는 오류 — 위임 카운터로만 센다(C3)
  // ===================================================================

  /** 양성 대조군 — 연결 실패 재시도 유지(RG-12). {@code getRequestCount()}로는 셀 수 없다. */
  @Test
  void 연결거부는_4회_시도한다() throws Exception {
    int deadPort;
    MockWebServer server = new MockWebServer();
    server.start();
    deadPort = server.getPort();
    server.shutdown();

    Harness harness = newHarness("http://localhost:" + deadPort);

    AnalysisException thrown = assertThrows(AnalysisException.class, () -> harness.analyzeOneFile());

    System.out.println("[005 시도 수] 연결 거부 → 위임 카운터=" + harness.counter.callCount
        + ", errorType=" + thrown.getErrorType());

    assertTrue(harness.counter.callCount >= 1,
        "LLM이 한 번도 불리지 않았다 — 키 가드 등에 막힌 것이므로 시도 수 단언이 무의미하다");
    assertEquals(RETRYABLE_ATTEMPTS, harness.counter.callCount,
        "연결 거부는 안쪽 재시도가 살아 있어야 한다(첫 시도 + 재시도 3)");
    assertEquals(ApiErrorHandler.ErrorType.CONNECT_FAILURE, thrown.getErrorType());
  }

  // ===================================================================
  // 도우미
  // ===================================================================

  private void assertAttempts(AnthropicErrorFixtures.Case fixture, int expectedAttempts,
      ApiErrorHandler.ErrorType expectedErrorType) throws Exception {
    MockWebServer server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        return AnthropicErrorFixtures.mockResponse(fixture);
      }
    });
    server.start();

    try {
      Harness harness = newHarness("http://localhost:" + server.getPort());

      AnalysisException thrown = assertThrows(AnalysisException.class, () -> harness.analyzeOneFile());

      System.out.println("[005 시도 수] " + fixture + " → 위임 카운터=" + harness.counter.callCount
          + ", getRequestCount()=" + server.getRequestCount()
          + ", errorType=" + thrown.getErrorType());

      assertTrue(harness.counter.callCount >= 1,
          fixture + ": LLM이 한 번도 불리지 않았다 — 키 가드 등에 막힌 것이므로 시도 수 단언이 무의미하다");
      assertEquals(expectedAttempts, harness.counter.callCount,
          fixture + ": 시도 수가 기대와 다르다");
      // 응답을 받는 케이스는 서버가 센 요청 수와 같아야 한다(교차 확인 — C3).
      assertEquals(harness.counter.callCount, server.getRequestCount(),
          fixture + ": 위임 카운터와 MockWebServer 요청 수가 다르다");
      assertEquals(expectedErrorType, thrown.getErrorType(), fixture + ": 최종 errorType이 다르다");
    } finally {
      server.shutdown();
    }
  }

  /** 운영과 같은 모양으로 배선한 1회 분석 하네스. */
  private static final class Harness {
    private final RetryHandler retryHandler;
    private final ClaudeService claudeService;
    private final CountingDelegate counter;

    private Harness(RetryHandler retryHandler, ClaudeService claudeService, CountingDelegate counter) {
      this.retryHandler = retryHandler;
      this.claudeService = claudeService;
      this.counter = counter;
    }

    /** {@code MainApiController.analyzeFile}과 같은 형태 — 4인자 호출을 바깥 재시도로 감싼다(C1). */
    private String analyzeOneFile() {
      return retryHandler.executeWithRetry("session-retry-count", SESSION_ROOT + "/Sample.java",
          () -> claudeService.analyzeCodeWithClaude("public class Sample {}", "Sample.java",
              SESSION_ROOT, SESSION_ROOT + "/Sample.java"));
    }
  }

  private Harness newHarness(String apiUrl) throws Exception {
    // 안쪽(ClaudeServiceImpl)과 바깥(RetryHandler)이 같은 설정·같은 분류기를 쓰게 한다(운영과 같은 구성).
    SessionConfig sessionConfig = new SessionConfig();
    sessionConfig.setMaxRetries(MAX_RETRIES);
    sessionConfig.setInitialRetryDelayMs(1);
    sessionConfig.setMaxRetryDelayMs(10);

    ApiErrorHandler apiErrorHandler = new ApiErrorHandler();
    CountingDelegate counter = new CountingDelegate(
        new AnthropicLlmClient(AnthropicErrorFixtures.INVALID_KEY, apiUrl));
    LlmClientResolver resolver = new LlmClientResolver(counter, counter);

    ClaudeServiceImpl service = new ClaudeServiceImpl(
        apiErrorHandler, null, sessionConfig, null, resolver, null, null);
    setField(service, "llmProvider", "anthropic");
    setField(service, "apiModel", MODEL);
    setField(service, "llmLocalModel", MODEL);
    // 키 가드를 통과하는 무효 문자열 — 자격증명이 아니다(G-10). MOCK 접두·빈 값이면 가드에 막혀
    // LLM이 아예 불리지 않으므로 시도 수를 잴 수 없다.
    setField(service, "apiKey", AnthropicErrorFixtures.INVALID_KEY);
    setField(service, "systemPromptFilename", "prompts/prompt-base.md");

    return new Harness(new RetryHandler(apiErrorHandler, sessionConfig), service, counter);
  }

  private static void setField(Object target, String name, Object value) {
    try {
      Field field = ClaudeServiceImpl.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      fail("ClaudeServiceImpl." + name + " 주입 실패 — 필드 이름이 바뀌었는지 확인하라: " + e);
    }
  }
}
