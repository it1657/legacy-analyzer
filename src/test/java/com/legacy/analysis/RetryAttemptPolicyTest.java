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
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TASK-004 (work-order 2026-10-retry-and-classification v2, REQ-001 ①) —
 * 파일 1개를 분석할 때 LLM을 <b>몇 번 부르는지</b>를 "설정한 재시도 횟수 = 실제 재시도 횟수"로 고정한다.
 *
 * <p>배선은 운영과 같게 둔다: 실제 {@link ClaudeServiceImpl}(안쪽 재시도 루프) + 실제
 * {@link AnthropicLlmClient}({@link MockWebServer} 대상) + 실제 {@link RetryHandler}(바깥 재시도),
 * 그리고 <b>안쪽과 바깥에 같은 {@link SessionConfig} 인스턴스</b>를 넣는다. 지금까지는 재시도가 두 겹이라
 * 시도 수가 곱이었다({@code maxRetries=3}이면 안쪽 3회 × 바깥 4회 = 12회). 이 TASK 뒤에는
 * 안쪽만 재시도하고(첫 시도 + 재시도 {@code maxRetries}번 = 4회) 바깥은 안쪽이 이미 최종 판정한
 * {@code AnalysisException}을 다시 돌리지 않는다.
 *
 * <p>시도 수를 무엇으로 세는가: <b>{@link LlmClient}를 감싼 위임 카운터</b>의 {@code call(...)} 횟수다.
 * {@code MockWebServer.getRequestCount()}로는 연결 거부·시간 초과를 셀 수 없다(요청이 서버에 닿지 않는다).
 * 응답을 받는 케이스에서는 두 값이 같은지 교차 단언한다.
 *
 * <p>전제 단언(G-21): 모든 시도 수 단언 앞에 "요청 ≥ 1"을 먼저 본다. API 키 가드
 * ({@code ClaudeServiceImplApiKeyGuardRoutingTest} 선례)에 막혀 LLM이 아예 불리지 않으면 "1회"가
 * 우연히 맞을 수 있기 때문이다. 키는 가드를 통과하는 무효 문자열({@link AnthropicErrorFixtures#INVALID_KEY})이다.
 * 재시도 불가 묶음(B1~B6 = 1회) 옆에 재시도 대상 묶음(A1~A7 = 4회)을 같은 클래스에 둬서 "항상 1"이
 * 아님을 보인다.
 *
 * <p>외부 호출은 없다 — 대상 주소는 {@code localhost}({@link MockWebServer})뿐이고, A7은 서버를 쓰지 않고
 * 대역이 JDK 시간 초과 예외를 직접 던진다.
 */
class RetryAttemptPolicyTest {

  /** 운영과 같은 재시도 정책(대기만 짧게). 기본값 자체를 재는 C1·C2·C3·Z0는 이 값을 쓰지 않는다. */
  private static final int MAX_RETRIES = 3;

  /** 재시도 대상의 시도 수 = 첫 시도 + 재시도 3회. 바깥은 다시 돌리지 않는다. */
  private static final int RETRYABLE_ATTEMPTS = 4;

  /** 재시도 불가 오류의 시도 수. */
  private static final int NON_RETRYABLE_ATTEMPTS = 1;

  private static final String MODEL = "claude-sonnet-5";
  private static final String SESSION_ROOT = "/source/retry-attempt-policy-session";
  private static final String SESSION_ID = "session-retry-attempt-policy";
  private static final String SOURCE_CODE = "public class Sample {}";

  /** 운영 속성 파일 — C2가 기본값과 대조한다. */
  private static final String PROPERTIES_PATH = "src/main/resources/application.properties";

  /** 봉투는 정상이고 주석 JSON이 아닌 응답. 분석은 성공으로 끝나고 원본이 그대로 돌아온다. */
  private static final String SUCCESS_BODY =
      "{\"content\":[{\"type\":\"text\",\"text\":\"주석 없음\"}]}";

  @TempDir
  Path tempDir;

  private final ApiErrorHandler apiErrorHandler = new ApiErrorHandler();

  // ===================================================================
  // A1~A7 — 재시도 대상 = 4회
  // ===================================================================

  @Test
  void A1_요청한도초과_429는_4회_시도한다() throws Exception {
    assertAttempts("A1 429", () -> AnthropicErrorFixtures.mockResponse(
            AnthropicErrorFixtures.Case.RATE_LIMIT_429),
        RETRYABLE_ATTEMPTS, ApiErrorHandler.ErrorType.API_RATE_LIMIT);
  }

  @Test
  void A2_서버오류_500은_4회_시도한다() throws Exception {
    assertAttempts("A2 500", () -> serverErrorResponse(500),
        RETRYABLE_ATTEMPTS, ApiErrorHandler.ErrorType.SERVER_ERROR);
  }

  @Test
  void A3_서버오류_502는_4회_시도한다() throws Exception {
    assertAttempts("A3 502", () -> serverErrorResponse(502),
        RETRYABLE_ATTEMPTS, ApiErrorHandler.ErrorType.SERVER_ERROR);
  }

  @Test
  void A4_서버오류_503은_4회_시도한다() throws Exception {
    assertAttempts("A4 503", () -> serverErrorResponse(503),
        RETRYABLE_ATTEMPTS, ApiErrorHandler.ErrorType.SERVER_ERROR);
  }

  @Test
  void A5_서버오류_504는_4회_시도한다() throws Exception {
    assertAttempts("A5 504", () -> serverErrorResponse(504),
        RETRYABLE_ATTEMPTS, ApiErrorHandler.ErrorType.SERVER_ERROR);
  }

  /** 응답이 서버에 닿지 않으므로 위임 카운터로만 센다. */
  @Test
  void A6_연결거부는_4회_시도한다() throws Exception {
    Harness harness = newHarness(new AnthropicLlmClient(
        AnthropicErrorFixtures.INVALID_KEY, "http://localhost:" + deadPort()), operationalConfig());

    AnalysisException thrown = assertThrows(AnalysisException.class, harness::analyzeOneFile);

    System.out.println("[RC 시도 수] A6 연결거부 위임=" + harness.counter.callCount
        + " 서버=(해당없음 — 요청이 서버에 닿지 않음)"
        + " errorType=" + thrown.getErrorType());

    assertTrue(harness.counter.callCount >= 1,
        "A6: LLM이 한 번도 불리지 않았다 — 키 가드 등에 막힌 것이므로 시도 수 단언이 무의미하다");
    assertEquals(RETRYABLE_ATTEMPTS, harness.counter.callCount, "A6: 시도 수가 기대와 다르다");
    assertEquals(ApiErrorHandler.ErrorType.CONNECT_FAILURE, thrown.getErrorType());
  }

  /**
   * 읽기 시간 초과 — 실제 JDK {@link SocketTimeoutException}을 원인으로 담은 비검사 예외를 대역이 던진다.
   * {@link AnthropicLlmClient}에는 응답 시간 제한이 없어 실제 클라이언트로는 이 예외가 생기지 않는다.
   */
  @Test
  void A7_읽기시간초과는_4회_시도한다() throws Exception {
    UncheckedIOException probe =
        new UncheckedIOException(new SocketTimeoutException("Read timed out"));
    ApiErrorHandler.ErrorType probeType = apiErrorHandler.classifyError(probe, 0);

    System.out.println("[RC 전제] A7 예외=" + probe
        + " | 메시지 원문=" + probe.getMessage()
        + " | 원인=" + probe.getCause()
        + " | classifyError(e, 0)=" + probeType
        + " | isRetryable=" + apiErrorHandler.isRetryable(probeType));

    assertEquals(ApiErrorHandler.ErrorType.NETWORK_TIMEOUT, probeType,
        "A7 전제: 읽기 시간 초과 예외가 NETWORK_TIMEOUT으로 분류되지 않는다 — 이 케이스로 시간 초과 경로를 재지 못한다");
    assertTrue(apiErrorHandler.isRetryable(probeType),
        "A7 전제: NETWORK_TIMEOUT이 재시도 대상이 아니다 — 4회 단언이 성립하지 않는다");

    Harness harness = newHarness(new ReadTimeoutClient(), operationalConfig());

    AnalysisException thrown = assertThrows(AnalysisException.class, harness::analyzeOneFile);

    System.out.println("[RC 시도 수] A7 읽기시간초과 위임=" + harness.counter.callCount
        + " 서버=(해당없음 — 서버를 쓰지 않는 대역)"
        + " errorType=" + thrown.getErrorType());

    assertTrue(harness.counter.callCount >= 1, "A7: 대역이 한 번도 불리지 않았다");
    assertEquals(RETRYABLE_ATTEMPTS, harness.counter.callCount, "A7: 시도 수가 기대와 다르다");
    assertEquals(ApiErrorHandler.ErrorType.NETWORK_TIMEOUT, thrown.getErrorType());
  }

  // ===================================================================
  // B1~B6 — 재시도 불가 = 1회
  // ===================================================================

  @Test
  void B1_인증실패_401은_1회만_시도한다() throws Exception {
    assertAttempts("B1 401", () -> AnthropicErrorFixtures.mockResponse(
            AnthropicErrorFixtures.Case.AUTH_401_1),
        NON_RETRYABLE_ATTEMPTS, ApiErrorHandler.ErrorType.API_AUTHENTICATION);
  }

  @Test
  void B2_권한없음_403은_1회만_시도한다() throws Exception {
    assertAttempts("B2 403", () -> AnthropicErrorFixtures.mockResponse(
            AnthropicErrorFixtures.Case.FORBIDDEN_403),
        NON_RETRYABLE_ATTEMPTS, ApiErrorHandler.ErrorType.API_FORBIDDEN);
  }

  @Test
  void B3_크레딧소진_일반벌은_1회만_시도한다() throws Exception {
    assertAttempts("B3 크레딧 일반", () -> AnthropicErrorFixtures.mockResponse(
            AnthropicErrorFixtures.Case.CREDIT_400),
        NON_RETRYABLE_ATTEMPTS, ApiErrorHandler.ErrorType.INSUFFICIENT_CREDITS);
  }

  @Test
  void B4_크레딧소진_requestid에_io가_든_벌도_1회만_시도한다() throws Exception {
    assertAttempts("B4 크레딧 io", () -> AnthropicErrorFixtures.mockResponse(
            AnthropicErrorFixtures.Case.CREDIT_400_REQUEST_ID_IO),
        NON_RETRYABLE_ATTEMPTS, ApiErrorHandler.ErrorType.INSUFFICIENT_CREDITS);
  }

  /** 400 일반 요청 오류 — 본문은 리소스 파일을 늘리지 않고 이 테스트 안 문자열로 둔다. */
  @Test
  void B5_잘못된요청_400은_1회만_시도한다() throws Exception {
    assertAttempts("B5 400 일반", () -> new MockResponse()
            .setResponseCode(400)
            .addHeader("Content-Type", "application/json")
            .setBody("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"max_tokens must be a positive integer\"},"
                + "\"request_id\":\"req_011CQZ9nN0oO1pP2qQ3rR4bR\"}"),
        NON_RETRYABLE_ATTEMPTS, ApiErrorHandler.ErrorType.INVALID_REQUEST);
  }

  /**
   * 응답 전 끊김 — 현행 동작 기록(C-16). 빈 응답이라 봉투 검사가 먼저 걸려 {@code UNKNOWN_ERROR}가 되고
   * 재시도 대상이 아니다. 변경 전후 모두 1회다.
   */
  @Test
  void B6_응답_전_끊김은_1회만_시도한다() throws Exception {
    assertAttempts("B6 응답 전 끊김",
        () -> new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
        NON_RETRYABLE_ATTEMPTS, ApiErrorHandler.ErrorType.UNKNOWN_ERROR);
  }

  // ===================================================================
  // C1~C3 — 기본값이 운영 속성과 같다
  // ===================================================================

  @Test
  void C1_기본_생성한_설정은_재시도_3회_대기_100ms_상한_2000ms다() {
    SessionConfig config = new SessionConfig();

    System.out.println("[RC 기본값] C1 maxRetries=" + config.getMaxRetries()
        + " initialRetryDelayMs=" + config.getInitialRetryDelayMs()
        + " maxRetryDelayMs=" + config.getMaxRetryDelayMs());

    assertEquals(3, config.getMaxRetries(), "C1: 기본 maxRetries가 운영 속성과 다르다");
    assertEquals(100L, config.getInitialRetryDelayMs(), "C1: 기본 initialRetryDelayMs가 운영 속성과 다르다");
    assertEquals(2000L, config.getMaxRetryDelayMs(), "C1: 기본 maxRetryDelayMs가 운영 속성과 다르다");
  }

  @Test
  void C2_운영_속성_세_값이_기본값과_같다() throws Exception {
    String properties = Files.readString(Path.of(PROPERTIES_PATH), StandardCharsets.UTF_8);
    int maxRetries = Integer.parseInt(propertyValue(properties, "app.analysis.max-retries"));
    long initialDelay = Long.parseLong(propertyValue(properties, "app.analysis.initial-retry-delay-ms"));
    long maxDelay = Long.parseLong(propertyValue(properties, "app.analysis.max-retry-delay-ms"));

    SessionConfig config = new SessionConfig();

    System.out.println("[RC 기본값] C2 속성=" + maxRetries + "/" + initialDelay + "/" + maxDelay
        + " 기본값=" + config.getMaxRetries() + "/" + config.getInitialRetryDelayMs()
        + "/" + config.getMaxRetryDelayMs());

    assertEquals(maxRetries, config.getMaxRetries(),
        "C2: application.properties의 max-retries와 필드 초기값이 다르다");
    assertEquals(initialDelay, config.getInitialRetryDelayMs(),
        "C2: application.properties의 initial-retry-delay-ms와 필드 초기값이 다르다");
    assertEquals(maxDelay, config.getMaxRetryDelayMs(),
        "C2: application.properties의 max-retry-delay-ms와 필드 초기값이 다르다");
  }

  /** 속성을 덮지 않은 기본 설정(대기만 짧게)에서도 4회여야 한다 — 지금은 5 × 6 = 30이 된다. */
  @Test
  void C3_기본_설정에서_서버오류_500은_4회_시도한다() throws Exception {
    SessionConfig config = new SessionConfig();
    config.setInitialRetryDelayMs(1);
    config.setMaxRetryDelayMs(10);

    assertAttempts("C3 기본 설정 500", () -> serverErrorResponse(500),
        RETRYABLE_ATTEMPTS, ApiErrorHandler.ErrorType.SERVER_ERROR, config);
  }

  // ===================================================================
  // D1·D2 — 실제 analyzeFile 경유
  // ===================================================================

  /** 단일 분석(청크 아님) — 실제 {@code MainApiController.analyzeFile}이 FAILED를 남긴다. */
  @Test
  void D1_단일분석_500은_요청_4회_뒤_FAILED가_된다() throws Exception {
    MockWebServer server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        return serverErrorResponse(500);
      }
    });
    server.start();

    try {
      ControllerHarness harness = newControllerHarness(
          "http://localhost:" + server.getPort(), 153600L, 1000, 100);
      Path sourceFile = harness.sourceRoot.resolve("Sample.java");
      Files.writeString(sourceFile, SOURCE_CODE, StandardCharsets.UTF_8);

      FileAnalysisState state = harness.analyzeFile(sourceFile);

      System.out.println("[RC 시도 수] D1 단일분석 500 위임=" + harness.counter.callCount
          + " 서버=" + server.getRequestCount()
          + " errorType=" + state.getErrorType()
          + " | status=" + state.getStatus());

      assertTrue(harness.counter.callCount >= 1,
          "D1: LLM이 한 번도 불리지 않았다 — 키 가드·수집 단계에 막힌 것이므로 시도 수 단언이 무의미하다");
      assertEquals(harness.counter.callCount, server.getRequestCount(),
          "D1: 위임 카운터와 MockWebServer 요청 수가 다르다");
      assertEquals(RETRYABLE_ATTEMPTS, harness.counter.callCount, "D1: 요청 수가 기대와 다르다");
      assertEquals("FAILED", state.getStatus(), "D1: 파일 상태가 FAILED가 아니다");
      assertEquals("SERVER_ERROR", state.getErrorType(), "D1: 실패 유형이 SERVER_ERROR가 아니다");
    } finally {
      server.shutdown();
    }
  }

  /**
   * 청크 3개 중 2번째만 실패 — 앞 청크를 바깥 재시도가 다시 유료로 부르지 않아야 한다.
   * 청크별 요청 수는 요청 본문의 {@code (청크 k/3)} 표기로 집계한다.
   */
  @Test
  void D2_청크_3개중_2번째만_500이면_청크별_요청이_1_4_0이다() throws Exception {
    int[] perChunk = new int[3];
    int[] unattributed = {0};

    MockWebServer server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        String body = request.getBody().readUtf8();
        int chunk = 0;
        for (int k = 1; k <= 3; k++) {
          if (body.contains("(청크 " + k + "/3)")) {
            chunk = k;
            break;
          }
        }
        if (chunk == 0) {
          unattributed[0]++;
          return serverErrorResponse(500);
        }
        perChunk[chunk - 1]++;
        return chunk == 2 ? serverErrorResponse(500) : successResponse();
      }
    });
    server.start();

    try {
      ControllerHarness harness = newControllerHarness(
          "http://localhost:" + server.getPort(), 10L, 2, 0);
      Path sourceFile = harness.sourceRoot.resolve("Chunked.java");
      Files.writeString(sourceFile,
          "class L1 {}\nclass L2 {}\nclass L3 {}\nclass L4 {}\nclass L5 {}\nclass L6 {}",
          StandardCharsets.UTF_8);

      FileAnalysisState state = harness.analyzeFile(sourceFile);

      System.out.println("[RC 시도 수] D2 청크분석 500 위임=" + harness.counter.callCount
          + " 서버=" + server.getRequestCount()
          + " errorType=" + state.getErrorType()
          + " | status=" + state.getStatus());
      System.out.println("[RC 청크별] D2 청크1=" + perChunk[0] + " 청크2=" + perChunk[1]
          + " 청크3=" + perChunk[2] + " 미귀속=" + unattributed[0]);

      assertTrue(harness.counter.callCount >= 1,
          "D2: LLM이 한 번도 불리지 않았다 — 시도 수 단언이 무의미하다");
      assertEquals(0, unattributed[0],
          "D2: 청크 표기로 귀속하지 못한 요청이 있다 — 청크별 집계가 성립하지 않는다");
      assertEquals(harness.counter.callCount, perChunk[0] + perChunk[1] + perChunk[2],
          "D2: 청크별 합이 위임 카운터와 다르다 — 집계가 요청 전부를 덮지 못했다");
      assertEquals(1, perChunk[0], "D2: 성공한 앞 청크가 다시 불렸다");
      assertEquals(RETRYABLE_ATTEMPTS, perChunk[1], "D2: 실패한 청크의 시도 수가 기대와 다르다");
      assertEquals(0, perChunk[2], "D2: 실패 뒤 남은 청크가 불렸다");
      assertEquals("FAILED", state.getStatus(), "D2: 파일 상태가 FAILED가 아니다");
      assertEquals("SERVER_ERROR", state.getErrorType(), "D2: 실패 유형이 SERVER_ERROR가 아니다");
    } finally {
      server.shutdown();
    }
  }

  // ===================================================================
  // Z0 — max-retries=0
  // ===================================================================

  /** 재시도를 끄면 1번만 시도해야 한다 — 지금은 루프를 한 번도 돌지 않고 "재시도 루프 이탈"로 끝난다. */
  @Test
  void Z0_재시도_0회_설정이면_500도_1회만_시도한다() throws Exception {
    SessionConfig config = new SessionConfig();
    config.setMaxRetries(0);
    config.setInitialRetryDelayMs(1);
    config.setMaxRetryDelayMs(10);

    assertAttempts("Z0 maxRetries=0", () -> serverErrorResponse(500),
        NON_RETRYABLE_ATTEMPTS, ApiErrorHandler.ErrorType.SERVER_ERROR, config);
  }

  // ===================================================================
  // 도우미 — 응답
  // ===================================================================

  /** 5xx 응답. 502·503·504 본문은 500 픽스처 본문을 그대로 쓰고 상태 코드만 바꾼다. */
  private static MockResponse serverErrorResponse(int status) {
    return new MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/json")
        .setBody(AnthropicErrorFixtures.body(AnthropicErrorFixtures.Case.SERVER_500));
  }

  private static MockResponse successResponse() {
    return new MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(SUCCESS_BODY);
  }

  private static int deadPort() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    int port = server.getPort();
    server.shutdown();
    return port;
  }

  private static String propertyValue(String properties, String key) {
    for (String line : properties.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.startsWith(key + "=")) {
        return trimmed.substring(key.length() + 1).trim();
      }
    }
    return fail("application.properties에 " + key + " 설정이 없다");
  }

  // ===================================================================
  // 도우미 — 1회 분석 하네스 (A·B·C3·Z0)
  // ===================================================================

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

  /** A7 전용 대역 — 실제 JDK 읽기 시간 초과 예외를 원인으로 담은 비검사 예외를 던진다. */
  private static final class ReadTimeoutClient implements LlmClient {
    @Override
    public LlmResult call(String systemPrompt, String userContent, String model, int maxTokens) {
      throw new UncheckedIOException(new SocketTimeoutException("Read timed out"));
    }
  }

  /** 운영과 같은 재시도 정책(대기만 짧게). */
  private static SessionConfig operationalConfig() {
    SessionConfig config = new SessionConfig();
    config.setMaxRetries(MAX_RETRIES);
    config.setInitialRetryDelayMs(1);
    config.setMaxRetryDelayMs(10);
    return config;
  }

  private void assertAttempts(String id, Supplier<MockResponse> response, int expectedAttempts,
      ApiErrorHandler.ErrorType expectedErrorType) throws Exception {
    assertAttempts(id, response, expectedAttempts, expectedErrorType, operationalConfig());
  }

  /** 응답은 매 요청마다 새로 만든다 — 같은 {@link MockResponse}를 여러 번 돌려주면 본문 버퍼가 소진된다. */
  private void assertAttempts(String id, Supplier<MockResponse> response, int expectedAttempts,
      ApiErrorHandler.ErrorType expectedErrorType, SessionConfig config) throws Exception {
    MockWebServer server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        return response.get();
      }
    });
    server.start();

    try {
      Harness harness = newHarness(new AnthropicLlmClient(
          AnthropicErrorFixtures.INVALID_KEY, "http://localhost:" + server.getPort()), config);

      AnalysisException thrown = assertThrows(AnalysisException.class, harness::analyzeOneFile);

      System.out.println("[RC 시도 수] " + id + " 위임=" + harness.counter.callCount
          + " 서버=" + server.getRequestCount()
          + " errorType=" + thrown.getErrorType());

      assertTrue(harness.counter.callCount >= 1,
          id + ": LLM이 한 번도 불리지 않았다 — 키 가드 등에 막힌 것이므로 시도 수 단언이 무의미하다");
      assertEquals(harness.counter.callCount, server.getRequestCount(),
          id + ": 위임 카운터와 MockWebServer 요청 수가 다르다");
      assertEquals(expectedAttempts, harness.counter.callCount, id + ": 시도 수가 기대와 다르다");
      assertEquals(expectedErrorType, thrown.getErrorType(), id + ": 최종 errorType이 다르다");
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

    /** {@code MainApiController.analyzeFile}과 같은 형태 — 4인자 호출을 바깥 재시도로 감싼다. */
    private String analyzeOneFile() {
      return retryHandler.executeWithRetry(SESSION_ID, SESSION_ROOT + "/Sample.java",
          () -> claudeService.analyzeCodeWithClaude(SOURCE_CODE, "Sample.java",
              SESSION_ROOT, SESSION_ROOT + "/Sample.java"));
    }
  }

  private Harness newHarness(LlmClient client, SessionConfig sessionConfig) throws Exception {
    CountingDelegate counter = new CountingDelegate(client);
    ClaudeServiceImpl service = newClaudeService(counter, sessionConfig);
    return new Harness(new RetryHandler(apiErrorHandler, sessionConfig), service, counter);
  }

  /** 안쪽(ClaudeServiceImpl)과 바깥(RetryHandler)이 같은 설정·같은 분류기를 쓰게 한다(운영과 같은 구성). */
  private ClaudeServiceImpl newClaudeService(CountingDelegate counter, SessionConfig sessionConfig) {
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

  // ===================================================================
  // 도우미 — 실제 analyzeFile 하네스 (D1·D2)
  // ===================================================================

  /** 실제 {@code MainApiController.analyzeFile}을 리플렉션으로 부르는 하네스. */
  private final class ControllerHarness {
    private final MainApiController controller;
    private final CountingDelegate counter;
    private final Path sourceRoot;
    private final Path outputRoot;

    private ControllerHarness(MainApiController controller, CountingDelegate counter,
        Path sourceRoot, Path outputRoot) {
      this.controller = controller;
      this.counter = counter;
      this.sourceRoot = sourceRoot;
      this.outputRoot = outputRoot;
    }

    private FileAnalysisState analyzeFile(Path sourceFile) throws Exception {
      Method method = MainApiController.class.getDeclaredMethod("analyzeFile",
          String.class, Path.class, Path.class, Path.class, boolean.class, String.class);
      method.setAccessible(true);
      Path targetPath = outputRoot.resolve(sourceRoot.relativize(sourceFile));
      Files.createDirectories(targetPath.getParent());
      try {
        return (FileAnalysisState) method.invoke(controller, SESSION_ID, sourceFile, targetPath,
            sourceRoot, false, outputRoot.toString());
      } catch (InvocationTargetException e) {
        throw new IllegalStateException("analyzeFile 호출이 예외로 끝났다", e.getCause());
      }
    }
  }

  private ControllerHarness newControllerHarness(String apiUrl, long chunkingThresholdBytes,
      int chunkSizeLines, int chunkOverlapLines) throws Exception {
    Path sourceRoot = Files.createDirectories(tempDir.resolve("src"));
    Path outputRoot = Files.createDirectories(tempDir.resolve("out"));

    SessionConfig sessionConfig = operationalConfig();
    CountingDelegate counter = new CountingDelegate(
        new AnthropicLlmClient(AnthropicErrorFixtures.INVALID_KEY, apiUrl));
    ClaudeServiceImpl claudeService = newClaudeService(counter, sessionConfig);

    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    SessionState session =
        new SessionState(SESSION_ID, sourceRoot.toString(), outputRoot.toString());
    when(sessionManager.getSession(anyString())).thenReturn(session);

    MainApiController controller = new MainApiController(
        claudeService, null, sessionManager, null, null,
        new RetryHandler(apiErrorHandler, sessionConfig),
        null, null, null, null, null, null, null, null, null);
    setControllerField(controller, "chunkingThresholdBytes", chunkingThresholdBytes);
    setControllerField(controller, "chunkSizeLines", chunkSizeLines);
    setControllerField(controller, "chunkOverlapLines", chunkOverlapLines);

    return new ControllerHarness(controller, counter, sourceRoot, outputRoot);
  }

  private static void setControllerField(MainApiController target, String name, Object value) {
    try {
      Field field = MainApiController.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      fail("MainApiController." + name + " 주입 실패 — 필드 이름이 바뀌었는지 확인하라: " + e);
    }
  }
}
