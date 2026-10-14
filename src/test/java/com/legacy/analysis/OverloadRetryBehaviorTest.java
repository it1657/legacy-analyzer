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

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-005 (work-order 2026-10-retry-and-classification v3, REQ-001 ②) —
 * ① 상태 코드 <b>529·408</b>이 재시도 대상이 되는지와 ② <b>429·529만</b> 대기가 길어지는지를
 * 실제 요청 시각으로 고정한다.
 *
 * <p>배선은 TASK-004 {@code RetryAttemptPolicyTest}와 같다: 실제 {@link ClaudeServiceImpl}(안쪽
 * 재시도 루프) + 실제 {@link AnthropicLlmClient}({@link MockWebServer} 대상) + 실제
 * {@link RetryHandler}(바깥) + <b>같은 {@link SessionConfig} 인스턴스</b>. 기존 API만 쓴다.
 *
 * <p>요청 수는 {@link LlmClient}를 감싼 위임 카운터와 {@code MockWebServer.getRequestCount()}로
 * 이중으로 센다(이 클래스는 응답이 모두 서버에서 가므로 두 값이 같아야 한다).
 *
 * <p>요청 시각은 {@code Dispatcher.dispatch}에서 {@link System#nanoTime()}으로 기록하고
 * {@code [RC 요청 시각] {ID} #k t=…ms Δ=…ms} 줄로 출력한다.
 *
 * <p><b>무변화 통과 방지</b>(G-21·C-2): 모든 시도 수 단언 앞에 "요청 ≥ 1"을 보고, <b>간격 단언
 * 앞에는 "요청 수 = 4"를 먼저 단언</b>한다 — 그렇지 않으면 529·408이 1회로 끝나는 상태에서
 * "모든 Δ ≤ 300ms"가 공허하게 참이 된다. 또 같은 클래스에 연장 대기(T1·T2)와 일반 대기(T3·T4),
 * 4회(O1·O2)와 1회(O3)를 함께 둬서 어느 쪽도 "항상 같은 값"이 아님을 보인다.
 *
 * <p>외부 호출은 없다 — 대상 주소는 {@code localhost}({@link MockWebServer})뿐이고 키는
 * 가드를 통과하는 무효 문자열({@link AnthropicErrorFixtures#INVALID_KEY})이다.
 */
class OverloadRetryBehaviorTest {

  /** 운영과 같은 재시도 횟수. */
  private static final int MAX_RETRIES = 3;

  /** 재시도 대상의 시도 수 = 첫 시도 + 재시도 3회. */
  private static final int RETRYABLE_ATTEMPTS = 4;

  /** 재시도 불가 오류의 시도 수. */
  private static final int NON_RETRYABLE_ATTEMPTS = 1;

  private static final String MODEL = "claude-sonnet-5";
  private static final String SESSION_ROOT = "/source/overload-retry-session";
  private static final String SESSION_ID = "session-overload-retry";
  private static final String SOURCE_CODE = "public class Sample {}";

  /** 이번 사이클 합성 픽스처(일반 지식 기준 Anthropic 오류 형식, 실측 본문 아님). */
  private static final String FIXTURE_529 = "/retryclassification/http/529-overloaded.json";
  private static final String FIXTURE_408 = "/retryclassification/http/408-request-timeout.json";

  /** 간격 케이스 설정 — 첫 대기 20ms / 상한 1000ms. */
  private static final long GAP_INITIAL_DELAY_MS = 20;
  private static final long GAP_MAX_DELAY_MS = 1000;

  /** 연장 대기 하한 = (첫 대기 20ms × 20) × 2^n. */
  private static final long[] EXTENDED_LOWER_MS = {400L, 800L, 1600L};

  /** 연장 대기 이론 상한 = 하한 + 흔들기 최대(하한/4). 상한 `최대 대기 × 5` = 5000ms에 걸리지 않는다. */
  private static final long[] EXTENDED_UPPER_MS = {500L, 1000L, 2000L};

  /** 요청 왕복·스레드 스케줄링 여유. */
  private static final long GAP_TOLERANCE_MS = 1000;

  /** 일반 대기 상한 — 이론 최대는 100ms 미만(20·40·80 + 흔들기)이고 왕복 여유를 더해 잡았다. */
  private static final long NORMAL_GAP_MAX_MS = 300;

  private final ApiErrorHandler apiErrorHandler = new ApiErrorHandler();

  // ===================================================================
  // O1~O3 — 529·408은 재시도 대상, 409는 대조군
  // ===================================================================

  @Test
  void O1_서버과부하_529는_4회_시도하고_Claude_서버_오류_이름표가_붙는다() throws Exception {
    Outcome outcome = run("O1 529", () -> fixtureResponse(529, FIXTURE_529), operationalConfig());

    assertAttempts("O1", outcome, RETRYABLE_ATTEMPTS, ApiErrorHandler.ErrorType.SERVER_ERROR);

    String label = ApiErrorHandler.failureReasonLabel(outcome.errorType.name());
    System.out.println("[RC 이름표] O1 529 errorType=" + outcome.errorType + " 이름표=" + label);
    assertEquals("Claude 서버 오류", label, "O1: 529의 실패 사유 이름표가 기대와 다르다");
  }

  @Test
  void O2_요청시간초과_408은_4회_시도하고_응답_시간_초과_이름표가_붙는다() throws Exception {
    Outcome outcome = run("O2 408", () -> fixtureResponse(408, FIXTURE_408), operationalConfig());

    assertAttempts("O2", outcome, RETRYABLE_ATTEMPTS, ApiErrorHandler.ErrorType.NETWORK_TIMEOUT);

    String label = ApiErrorHandler.failureReasonLabel(outcome.errorType.name());
    System.out.println("[RC 이름표] O2 408 errorType=" + outcome.errorType + " 이름표=" + label);
    assertEquals("응답 시간 초과", label, "O2: 408의 실패 사유 이름표가 기대와 다르다");
  }

  /** 대조군 — 표에 넣지 않은 409는 변경 전후 모두 1회·{@code UNKNOWN_ERROR}다. */
  @Test
  void O3_대조_409는_1회만_시도한다() throws Exception {
    Outcome outcome = run("O3 409", OverloadRetryBehaviorTest::conflictResponse, operationalConfig());

    assertAttempts("O3", outcome, NON_RETRYABLE_ATTEMPTS, ApiErrorHandler.ErrorType.UNKNOWN_ERROR);
  }

  // ===================================================================
  // T1~T4 — 429·529만 대기가 길어진다
  // ===================================================================

  @Test
  void T1_요청한도초과_429는_연장_대기_간격으로_재시도한다() throws Exception {
    Outcome outcome = run("T1 429", () -> AnthropicErrorFixtures.mockResponse(
        AnthropicErrorFixtures.Case.RATE_LIMIT_429), gapConfig());

    assertExtendedGaps("T1", outcome, ApiErrorHandler.ErrorType.API_RATE_LIMIT);
  }

  @Test
  void T2_서버과부하_529는_연장_대기_간격으로_재시도한다() throws Exception {
    Outcome outcome = run("T2 529", () -> fixtureResponse(529, FIXTURE_529), gapConfig());

    assertExtendedGaps("T2", outcome, ApiErrorHandler.ErrorType.SERVER_ERROR);
  }

  /** 대조군 — 500은 연장 대상이 아니라 종전 간격 그대로다. */
  @Test
  void T3_대조_서버오류_500은_일반_대기_간격을_유지한다() throws Exception {
    Outcome outcome = run("T3 500", () -> serverErrorResponse(500), gapConfig());

    assertNormalGaps("T3", outcome, ApiErrorHandler.ErrorType.SERVER_ERROR);
  }

  /** 대조군 — 408은 재시도 대상이 되지만 연장 대상은 아니다. */
  @Test
  void T4_대조_요청시간초과_408은_일반_대기_간격을_유지한다() throws Exception {
    Outcome outcome = run("T4 408", () -> fixtureResponse(408, FIXTURE_408), gapConfig());

    assertNormalGaps("T4", outcome, ApiErrorHandler.ErrorType.NETWORK_TIMEOUT);
  }

  // ===================================================================
  // 판정 도우미
  // ===================================================================

  /** 시도 수 판정 — "요청 ≥ 1"(G-21) → 위임·서버 교차 → 기대 수 → 최종 분류. */
  private void assertAttempts(String id, Outcome outcome, int expectedAttempts,
      ApiErrorHandler.ErrorType expectedErrorType) {
    // 단언보다 먼저 출력한다 — 기대와 다른 값이어도 실측 원문이 남아야 한다.
    System.out.println("[RC 시도 수] " + id + " 위임=" + outcome.delegateCalls
        + " 서버=" + outcome.serverRequests + " errorType=" + outcome.errorType);

    assertTrue(outcome.delegateCalls >= 1,
        id + ": LLM이 한 번도 불리지 않았다 — 키 가드 등에 막힌 것이므로 시도 수 단언이 무의미하다");
    assertEquals(outcome.delegateCalls, outcome.serverRequests,
        id + ": 위임 카운터와 MockWebServer 요청 수가 다르다");
    assertEquals(expectedAttempts, outcome.delegateCalls, id + ": 시도 수가 기대와 다르다");
    assertEquals(expectedErrorType, outcome.errorType, id + ": 최종 errorType이 다르다");
  }

  /**
   * 연장 대기 판정. <b>간격을 보기 전에 요청 수 = 4를 먼저 단언한다</b>(C-2) — 1회로 끝나면
   * 간격 단언에 쓸 Δ가 없어 "모든 Δ가 조건을 만족"이 공허하게 참이 된다.
   */
  private void assertExtendedGaps(String id, Outcome outcome,
      ApiErrorHandler.ErrorType expectedErrorType) {
    List<Long> gaps = printAndCollectGaps(id, outcome);

    assertAttempts(id, outcome, RETRYABLE_ATTEMPTS, expectedErrorType);
    assertEquals(RETRYABLE_ATTEMPTS - 1, gaps.size(), id + ": 간격 수가 기대와 다르다");

    for (int n = 0; n < gaps.size(); n++) {
      long gap = gaps.get(n);
      assertTrue(gap >= EXTENDED_LOWER_MS[n],
          id + ": Δ" + (n + 1) + "=" + gap + "ms가 연장 대기 하한 " + EXTENDED_LOWER_MS[n] + "ms 미만이다");
      assertTrue(gap <= EXTENDED_UPPER_MS[n] + GAP_TOLERANCE_MS,
          id + ": Δ" + (n + 1) + "=" + gap + "ms가 이론 상한 " + EXTENDED_UPPER_MS[n]
              + "ms + 여유 " + GAP_TOLERANCE_MS + "ms를 넘었다");
    }
  }

  /** 일반 대기 판정 — 같은 설정에서 간격이 짧게 유지되는 대조군. 요청 수 = 4를 먼저 단언한다(C-2). */
  private void assertNormalGaps(String id, Outcome outcome,
      ApiErrorHandler.ErrorType expectedErrorType) {
    List<Long> gaps = printAndCollectGaps(id, outcome);

    assertAttempts(id, outcome, RETRYABLE_ATTEMPTS, expectedErrorType);
    assertEquals(RETRYABLE_ATTEMPTS - 1, gaps.size(), id + ": 간격 수가 기대와 다르다");

    for (int n = 0; n < gaps.size(); n++) {
      assertTrue(gaps.get(n) <= NORMAL_GAP_MAX_MS,
          id + ": Δ" + (n + 1) + "=" + gaps.get(n) + "ms가 일반 대기 상한 "
              + NORMAL_GAP_MAX_MS + "ms를 넘었다 — 연장 대기가 잘못 적용됐다");
    }
  }

  /** 요청 시각 원문을 출력하고 밀리초 간격 목록을 돌려준다. */
  private List<Long> printAndCollectGaps(String id, Outcome outcome) {
    List<Long> gaps = new CopyOnWriteArrayList<>();
    long first = outcome.requestNanos.isEmpty() ? 0L : outcome.requestNanos.get(0);

    for (int k = 0; k < outcome.requestNanos.size(); k++) {
      long elapsedMs = (outcome.requestNanos.get(k) - first) / 1_000_000L;
      String gapText = "-";
      if (k > 0) {
        long gap = (outcome.requestNanos.get(k) - outcome.requestNanos.get(k - 1)) / 1_000_000L;
        gaps.add(gap);
        gapText = String.valueOf(gap);
      }
      System.out.println("[RC 요청 시각] " + id + " #" + (k + 1)
          + " t=" + elapsedMs + "ms Δ=" + gapText + "ms");
    }

    return gaps;
  }

  // ===================================================================
  // 도우미 — 응답
  // ===================================================================

  /**
   * 이번 사이클 합성 픽스처 본문을 돌려주는 응답. {@code AnthropicErrorFixtures.Case}에 추가하지
   * 않는다 — 전 벌을 순회하는 기존 테스트 3개의 기대가 바뀌기 때문이다.
   */
  private static MockResponse fixtureResponse(int status, String resource) {
    return new MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/json")
        .setBody(fixtureBody(resource));
  }

  private static String fixtureBody(String resource) {
    try (InputStream in = OverloadRetryBehaviorTest.class.getResourceAsStream(resource)) {
      if (in == null) {
        return fail("합성 픽스처 본문이 클래스패스에 없다: " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      return fail("합성 픽스처 본문을 읽지 못했다: " + resource + " — " + e);
    }
  }

  /** 5xx 응답. 본문은 500 픽스처를 그대로 쓰고 상태 코드만 바꾼다. */
  private static MockResponse serverErrorResponse(int status) {
    return new MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/json")
        .setBody(AnthropicErrorFixtures.body(AnthropicErrorFixtures.Case.SERVER_500));
  }

  /** 409 — 리소스 파일을 늘리지 않고 이 테스트 안 문자열로 둔다(대조군). */
  private static MockResponse conflictResponse() {
    return new MockResponse()
        .setResponseCode(409)
        .addHeader("Content-Type", "application/json")
        .setBody("{\"type\":\"error\",\"error\":{\"type\":\"conflict_error\","
            + "\"message\":\"Conflicting request\"},"
            + "\"request_id\":\"req_011CQZ6kK7lL8mM9nN0oO1vV\"}");
  }

  // ===================================================================
  // 도우미 — 설정
  // ===================================================================

  /** 운영과 같은 재시도 횟수(대기만 짧게) — 시도 수·분류·이름표 케이스용. */
  private static SessionConfig operationalConfig() {
    SessionConfig config = new SessionConfig();
    config.setMaxRetries(MAX_RETRIES);
    config.setInitialRetryDelayMs(1);
    config.setMaxRetryDelayMs(10);
    return config;
  }

  /** 간격 케이스용 — 첫 대기 20ms / 상한 1000ms. */
  private static SessionConfig gapConfig() {
    SessionConfig config = new SessionConfig();
    config.setMaxRetries(MAX_RETRIES);
    config.setInitialRetryDelayMs(GAP_INITIAL_DELAY_MS);
    config.setMaxRetryDelayMs(GAP_MAX_DELAY_MS);
    return config;
  }

  // ===================================================================
  // 도우미 — 실행
  // ===================================================================

  /** 한 케이스의 측정 결과. */
  private static final class Outcome {
    private final int delegateCalls;
    private final int serverRequests;
    private final List<Long> requestNanos;
    private final ApiErrorHandler.ErrorType errorType;

    private Outcome(int delegateCalls, int serverRequests, List<Long> requestNanos,
        ApiErrorHandler.ErrorType errorType) {
      this.delegateCalls = delegateCalls;
      this.serverRequests = serverRequests;
      this.requestNanos = requestNanos;
      this.errorType = errorType;
    }
  }

  /** 응답은 매 요청마다 새로 만든다 — 같은 {@link MockResponse}를 여러 번 돌려주면 본문이 소진된다. */
  private Outcome run(String id, Supplier<MockResponse> response, SessionConfig config)
      throws Exception {
    List<Long> requestNanos = new CopyOnWriteArrayList<>();

    MockWebServer server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requestNanos.add(System.nanoTime());
        return response.get();
      }
    });
    server.start();

    try {
      CountingDelegate counter = new CountingDelegate(new AnthropicLlmClient(
          AnthropicErrorFixtures.INVALID_KEY, "http://localhost:" + server.getPort()));
      ClaudeServiceImpl service = newClaudeService(counter, config);
      RetryHandler retryHandler = new RetryHandler(apiErrorHandler, config);

      AnalysisException thrown = assertThrows(AnalysisException.class,
          () -> retryHandler.executeWithRetry(SESSION_ID, SESSION_ROOT + "/Sample.java",
              () -> service.analyzeCodeWithClaude(SOURCE_CODE, "Sample.java",
                  SESSION_ROOT, SESSION_ROOT + "/Sample.java")),
          id + ": 재시도 끝에 AnalysisException이 나오지 않았다");

      return new Outcome(counter.callCount, server.getRequestCount(), requestNanos,
          thrown.getErrorType());
    } finally {
      server.shutdown();
    }
  }

  /** 실제 클라이언트에 위임하면서 호출 횟수만 세는 껍데기. 분류·재시도에는 개입하지 않는다. */
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

  /** 안쪽과 바깥이 같은 설정·같은 분류기를 쓰게 한다(운영과 같은 구성). */
  private ClaudeServiceImpl newClaudeService(CountingDelegate counter, SessionConfig sessionConfig) {
    LlmClientResolver resolver = new LlmClientResolver(counter, counter);
    ClaudeServiceImpl service = new ClaudeServiceImpl(
        apiErrorHandler, null, sessionConfig, null, resolver, null, null);
    setField(service, "llmProvider", "anthropic");
    setField(service, "apiModel", MODEL);
    setField(service, "llmLocalModel", MODEL);
    // 키 가드를 통과하는 무효 문자열 — 자격증명이 아니다(G-10).
    setField(service, "apiKey", AnthropicErrorFixtures.INVALID_KEY);
    setField(service, "systemPromptFilename", "prompts/prompt-base.md");
    return service;
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
