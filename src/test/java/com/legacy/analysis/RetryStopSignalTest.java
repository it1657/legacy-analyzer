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
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * TASK-005 (work-order 2026-10-retry-and-classification v3, REQ-001 ② / D2) —
 * 재시도 <b>대기 중</b>에 일시정지·취소를 확인해 다음 요청을 보내지 않는지, 그리고 그 장치가
 * 문맥을 깔끔히 정리하는지를 고정한다.
 *
 * <p>왜 깃발인가: 사용자의 일시정지·취소는 세션 상태 값만 바꾸고 작업 스레드를 인터럽트하지
 * 않는다. 그래서 기존 인터럽트 확인만으로는 대기 중 중단이 잡히지 않는다(S4가 인터럽트 경로도
 * 그대로 남아 있음을 함께 보인다).
 *
 * <p><b>무변화 통과 방지</b>(G-21): 중단 케이스(S2·S5 일시정지·S5 취소·S6)마다 <b>중단 없는
 * 대조</b>를 같은 설정·같은 서버에서 함께 재서 "요청 1회"가 "원래 1회여서"가 아님을 보인다
 * (S3·S5 진행 상태·S7 직접 호출 = 4회). 요청 수는 위임 카운터와 서버 요청 수로 이중으로 센다.
 *
 * <p>외부 호출은 없다 — 대상 주소는 {@code localhost}({@link MockWebServer})뿐이고 키는
 * 가드를 통과하는 무효 문자열({@link AnthropicErrorFixtures#INVALID_KEY})이다.
 */
class RetryStopSignalTest {

  private static final int MAX_RETRIES = 3;
  private static final int RETRYABLE_ATTEMPTS = 4;
  private static final String MODEL = "claude-sonnet-5";
  private static final String SESSION_ROOT = "/source/retry-stop-signal-session";
  private static final String SESSION_ID = "session-retry-stop-signal";
  private static final String SOURCE_CODE = "public class Sample {}";

  /** 중단 케이스 설정 — 첫 연장 대기가 3초를 넘게 하려고 첫 대기를 150ms로 둔다. */
  private static final long STOP_INITIAL_DELAY_MS = 150;

  /** 한 번 대기 상한이 `× 5` = 5000ms라 3초 대기를 깎지 않는다. */
  private static final long STOP_MAX_DELAY_MS = 1000;

  /** 깃발을 올리기까지 기다리는 시간 — 그 사이 작업 스레드는 연장 대기 안에 있다. */
  private static final long FLAG_AFTER_MS = 200;

  /** 중단 확인은 조각 길이(100ms) 단위라 1초 안에 끝나야 한다. */
  private static final long STOP_WITHIN_MS = 1000;

  @TempDir
  Path tempDir;

  private final ApiErrorHandler apiErrorHandler = new ApiErrorHandler();

  // ===================================================================
  // S1 — 운영 기본값에서의 대기 값 범위 (F-15)
  // ===================================================================

  /** 표본 1000회로 운영 기본값(3 / 100ms / 2000ms)의 대기 범위를 잰다. */
  @Test
  void S1_운영_기본값의_연장_대기와_일반_대기가_예측_범위에_들어온다() {
    SessionConfig config = new SessionConfig();
    long initial = config.getInitialRetryDelayMs();
    long max = config.getMaxRetryDelayMs();

    assertEquals(100L, initial, "S1 전제: 기본 첫 대기가 100ms가 아니다");
    assertEquals(2000L, max, "S1 전제: 기본 최대 대기가 2000ms가 아니다");

    long[][] extendedRange = {{2000, 2500}, {4000, 5000}, {8000, 10000}};
    for (int n = 0; n < extendedRange.length; n++) {
      long[] observed = sample(n, initial, max, true);
      System.out.println("[RC 대기 범위] 연장 n" + n + " 표본=1000 최소=" + observed[0]
          + "ms 최대=" + observed[1] + "ms 기대=[" + extendedRange[n][0] + "," + extendedRange[n][1] + ")");
      assertTrue(observed[0] >= extendedRange[n][0],
          "S1: 연장 n" + n + " 최소 " + observed[0] + "ms가 하한보다 작다");
      assertTrue(observed[1] < extendedRange[n][1],
          "S1: 연장 n" + n + " 최대 " + observed[1] + "ms가 상한 이상이다");
    }

    for (int n = 3; n <= 5; n++) {
      long[] observed = sample(n, initial, max, true);
      System.out.println("[RC 대기 범위] 연장 n" + n + " 표본=1000 최소=" + observed[0]
          + "ms 최대=" + observed[1] + "ms 기대=10000ms 고정");
      assertEquals(10000L, observed[0], "S1: 연장 n" + n + "의 최소가 한 번 상한(10000ms)이 아니다");
      assertEquals(10000L, observed[1], "S1: 연장 n" + n + "의 최대가 한 번 상한(10000ms)이 아니다");
    }

    long[][] normalRange = {{100, 125}, {200, 250}, {400, 500}};
    for (int n = 0; n < normalRange.length; n++) {
      long[] observed = sample(n, initial, max, false);
      System.out.println("[RC 대기 범위] 일반 n" + n + " 표본=1000 최소=" + observed[0]
          + "ms 최대=" + observed[1] + "ms 기대=[" + normalRange[n][0] + "," + normalRange[n][1] + ")");
      assertTrue(observed[0] >= normalRange[n][0],
          "S1: 일반 n" + n + " 최소 " + observed[0] + "ms가 하한보다 작다");
      assertTrue(observed[1] < normalRange[n][1],
          "S1: 일반 n" + n + " 최대 " + observed[1] + "ms가 상한 이상이다");
    }
  }

  // ===================================================================
  // S2·S3 — 깃발로 중단 / 깃발 없음 대조
  // ===================================================================

  /**
   * 연장 대기 중에 깃발이 올라가면 다음 요청을 보내지 않는다. 깃발 시각·종료 시각을 원문으로 남긴다.
   */
  @Test
  void S2_연장_대기_중_깃발이_올라가면_다음_요청을_보내지_않는다() throws Exception {
    long firstExtendedDelayMs =
        apiErrorHandler.calculateOverloadRetryDelay(0, STOP_INITIAL_DELAY_MS, STOP_MAX_DELAY_MS);
    System.out.println("[RC 전제] S2 첫 연장 대기=" + firstExtendedDelayMs + "ms (설정 "
        + STOP_INITIAL_DELAY_MS + "ms / " + STOP_MAX_DELAY_MS + "ms)");
    assertTrue(firstExtendedDelayMs >= 3000,
        "S2 전제: 첫 연장 대기가 3초 미만이면 '깃발 후 1초 안 종료' 단언이 대기 길이와 구별되지 않는다");

    AtomicBoolean flag = new AtomicBoolean(false);
    SessionConfig config = stopConfig();

    Server server = startRateLimitServer();
    try {
      CountingDelegate counter = newCounter(server);
      ClaudeServiceImpl service = newClaudeService(counter, config);
      RetryHandler retryHandler = new RetryHandler(apiErrorHandler, config);
      retryHandler.setStopCheckFactory(sessionId -> flag::get);

      Worker worker = runInWorker(retryHandler, service);
      assertTrue(server.firstRequest.await(10, TimeUnit.SECONDS), "S2: 첫 요청이 서버에 오지 않았다");
      Thread.sleep(FLAG_AFTER_MS);

      long flagAtMs = System.currentTimeMillis();
      flag.set(true);
      worker.thread.join(STOP_WITHIN_MS + 4000);
      long endedAtMs = System.currentTimeMillis();

      System.out.println("[RC 중단] S2 깃발=" + flagAtMs + "ms 종료=" + endedAtMs
          + "ms 경과=" + (endedAtMs - flagAtMs) + "ms 위임=" + counter.callCount
          + " 서버=" + server.server.getRequestCount()
          + " errorType=" + (worker.thrown.get() == null ? "(없음)" : worker.thrown.get().getErrorType()));

      assertFalse(worker.thread.isAlive(), "S2: 깃발 뒤에도 작업 스레드가 살아 있다");
      assertTrue(endedAtMs - flagAtMs <= STOP_WITHIN_MS,
          "S2: 깃발 후 종료까지 " + (endedAtMs - flagAtMs) + "ms 걸렸다 — 1초를 넘었다");
      assertNotNull(worker.thrown.get(), "S2: AnalysisException으로 끝나지 않았다");
      assertEquals(1, counter.callCount, "S2: 중단 뒤에도 요청을 더 보냈다");
      assertEquals(counter.callCount, server.server.getRequestCount(),
          "S2: 위임 카운터와 서버 요청 수가 다르다");
      assertEquals(ApiErrorHandler.ErrorType.API_RATE_LIMIT, worker.thrown.get().getErrorType(),
          "S2: 마지막으로 분류한 오류 종류가 그대로 올라오지 않았다");
    } finally {
      server.shutdown();
    }
  }

  /** 대조군 — 깃발이 없으면 같은 배선에서 4회 요청한다(짧은 대기). */
  @Test
  void S3_대조_깃발이_없으면_4회_요청한다() throws Exception {
    AtomicBoolean flag = new AtomicBoolean(false);
    SessionConfig config = fastConfig();

    Server server = startRateLimitServer();
    try {
      CountingDelegate counter = newCounter(server);
      ClaudeServiceImpl service = newClaudeService(counter, config);
      RetryHandler retryHandler = new RetryHandler(apiErrorHandler, config);
      retryHandler.setStopCheckFactory(sessionId -> flag::get);

      AnalysisException thrown = assertThrows(AnalysisException.class,
          () -> analyzeOneFile(retryHandler, service));

      System.out.println("[RC 중단] S3 대조(깃발 없음) 위임=" + counter.callCount
          + " 서버=" + server.server.getRequestCount() + " errorType=" + thrown.getErrorType());

      assertTrue(counter.callCount >= 1, "S3: LLM이 한 번도 불리지 않았다");
      assertEquals(RETRYABLE_ATTEMPTS, counter.callCount, "S3: 중단이 없는데 요청 수가 4가 아니다");
      assertEquals(ApiErrorHandler.ErrorType.API_RATE_LIMIT, thrown.getErrorType());
    } finally {
      server.shutdown();
    }
  }

  // ===================================================================
  // S4 — 인터럽트(기존 동작)
  // ===================================================================

  /** 연장 대기 중 인터럽트는 종전과 같이 {@code UNKNOWN_ERROR}로 끝난다. */
  @Test
  void S4_연장_대기_중_인터럽트는_UNKNOWN_ERROR로_끝난다() throws Exception {
    SessionConfig config = stopConfig();

    Server server = startRateLimitServer();
    try {
      CountingDelegate counter = newCounter(server);
      ClaudeServiceImpl service = newClaudeService(counter, config);
      RetryHandler retryHandler = new RetryHandler(apiErrorHandler, config);

      Worker worker = runInWorker(retryHandler, service);
      assertTrue(server.firstRequest.await(10, TimeUnit.SECONDS), "S4: 첫 요청이 서버에 오지 않았다");
      Thread.sleep(FLAG_AFTER_MS);
      worker.thread.interrupt();
      worker.thread.join(STOP_WITHIN_MS + 4000);

      System.out.println("[RC 중단] S4 인터럽트 위임=" + counter.callCount
          + " 서버=" + server.server.getRequestCount()
          + " errorType=" + (worker.thrown.get() == null ? "(없음)" : worker.thrown.get().getErrorType()));

      assertFalse(worker.thread.isAlive(), "S4: 인터럽트 뒤에도 작업 스레드가 살아 있다");
      assertNotNull(worker.thrown.get(), "S4: AnalysisException으로 끝나지 않았다");
      assertEquals(1, counter.callCount, "S4: 인터럽트 뒤에도 요청을 더 보냈다");
      assertEquals(ApiErrorHandler.ErrorType.UNKNOWN_ERROR, worker.thrown.get().getErrorType(),
          "S4: 인터럽트의 기존 분류(UNKNOWN_ERROR)가 바뀌었다");
    } finally {
      server.shutdown();
    }
  }

  // ===================================================================
  // S5 — 실제 세션 관리자 배선
  // ===================================================================

  /**
   * 실제 {@link AnalysisSessionManager}를 선택 주입하면 세션 상태로 중단을 판정한다.
   * 상태 변경은 컨트롤러가 하는 그대로 한다(일시정지 = 상태·단계 값, 취소 = 관리자 호출).
   */
  @Test
  void S5_세션_관리자_배선에서_일시정지와_취소만_중단으로_본다() throws Exception {
    // 일시정지 — MainApiController.pauseSession과 같은 두 줄
    CountingSessionManager paused = newSessionManager();
    SessionState pausedSession = paused.createSession(SESSION_ID, SESSION_ROOT, SESSION_ROOT);
    pausedSession.setCurrentPhase("PAUSED");
    pausedSession.setStatus("PAUSED");
    int pausedRequests = requestsWithSessionManager("S5 일시정지", paused);

    // 취소 — MainApiController.cancelSession과 같은 관리자 호출
    CountingSessionManager cancelled = newSessionManager();
    cancelled.createSession(SESSION_ID, SESSION_ROOT, SESSION_ROOT);
    cancelled.cancelSession(SESSION_ID);
    int cancelledRequests = requestsWithSessionManager("S5 취소", cancelled);

    // 대조 — 진행 중 상태는 중단이 아니다
    CountingSessionManager running = newSessionManager();
    SessionState runningSession = running.createSession(SESSION_ID, SESSION_ROOT, SESSION_ROOT);
    runningSession.setStatus("IN_PROGRESS");
    runningSession.setCurrentPhase("IN_PROGRESS");
    int runningRequests = requestsWithSessionManager("S5 진행중", running);

    System.out.println("[RC 중단] S5 요청 수 일시정지=" + pausedRequests + " 취소=" + cancelledRequests
        + " 진행중=" + runningRequests + " | 세션 조회 횟수 일시정지=" + paused.lookups.get()
        + " 취소=" + cancelled.lookups.get() + " 진행중=" + running.lookups.get());

    assertEquals(1, pausedRequests, "S5: 일시정지인데 요청을 더 보냈다");
    assertEquals(1, cancelledRequests, "S5: 취소인데 요청을 더 보냈다");
    assertEquals(RETRYABLE_ATTEMPTS, runningRequests, "S5: 진행 중 상태인데 재시도가 멈췄다");

    assertEquals(1, paused.lookups.get(), "S5: 세션 조회가 1회가 아니다(일시정지)");
    assertEquals(1, cancelled.lookups.get(), "S5: 세션 조회가 1회가 아니다(취소)");
    assertEquals(1, running.lookups.get(), "S5: 세션 조회가 1회가 아니다(진행중)");
  }

  // ===================================================================
  // S6 — 실제 analyzeFile 경유 (C-1 파일 단위 근거)
  // ===================================================================

  /** 대기 중 일시정지하면 그 파일은 {@code FAILED}로 기록된다 — 파일 단위 결과(C-1). */
  @Test
  void S6_analyzeFile_경유_대기_중_일시정지는_FAILED로_기록된다() throws Exception {
    SessionConfig config = stopConfig();

    Server server = startRateLimitServer();
    try {
      CountingSessionManager sessionManager = newSessionManager();
      Path sourceRoot = Files.createDirectories(tempDir.resolve("src"));
      Path outputRoot = Files.createDirectories(tempDir.resolve("out"));
      SessionState session =
          sessionManager.createSession(SESSION_ID, sourceRoot.toString(), outputRoot.toString());
      session.setStatus("IN_PROGRESS");

      CountingDelegate counter = newCounter(server);
      ClaudeServiceImpl claudeService = newClaudeService(counter, config);
      RetryHandler retryHandler = new RetryHandler(apiErrorHandler, config);
      retryHandler.setSessionManager(sessionManager);

      MainApiController controller = new MainApiController(
          claudeService, null, sessionManager, null, null, retryHandler,
          null, null, null, null, null, null, null, null, null);
      setControllerField(controller, "chunkingThresholdBytes", 153600L);
      setControllerField(controller, "chunkSizeLines", 1000);
      setControllerField(controller, "chunkOverlapLines", 100);

      Path sourceFile = sourceRoot.resolve("Sample.java");
      Files.writeString(sourceFile, SOURCE_CODE, StandardCharsets.UTF_8);
      Path targetPath = outputRoot.resolve(sourceRoot.relativize(sourceFile));
      Files.createDirectories(targetPath.getParent());

      AtomicReference<FileAnalysisState> state = new AtomicReference<>();
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread worker = new Thread(() -> {
        try {
          state.set(invokeAnalyzeFile(controller, sourceFile, targetPath, sourceRoot, outputRoot));
        } catch (Throwable t) {
          failure.set(t);
        }
      }, "s6-analyze-file");
      worker.start();

      assertTrue(server.firstRequest.await(10, TimeUnit.SECONDS), "S6: 첫 요청이 서버에 오지 않았다");
      Thread.sleep(FLAG_AFTER_MS);

      long flagAtMs = System.currentTimeMillis();
      session.setCurrentPhase("PAUSED");
      session.setStatus("PAUSED");
      worker.join(STOP_WITHIN_MS + 4000);
      long endedAtMs = System.currentTimeMillis();

      System.out.println("[RC 중단] S6 일시정지=" + flagAtMs + "ms 종료=" + endedAtMs
          + "ms 경과=" + (endedAtMs - flagAtMs) + "ms 위임=" + counter.callCount
          + " 서버=" + server.server.getRequestCount()
          + " status=" + (state.get() == null ? "(없음)" : state.get().getStatus())
          + " errorType=" + (state.get() == null ? "(없음)" : state.get().getErrorType()));

      assertFalse(worker.isAlive(), "S6: 일시정지 뒤에도 작업 스레드가 살아 있다");
      assertNull(failure.get(), "S6: analyzeFile이 예외로 끝났다 — " + failure.get());
      assertNotNull(state.get(), "S6: fileState가 돌아오지 않았다");
      assertTrue(counter.callCount >= 1, "S6: LLM이 한 번도 불리지 않았다");
      assertEquals(1, counter.callCount, "S6: 중단 뒤에도 요청을 더 보냈다");
      assertEquals("FAILED", state.get().getStatus(), "S6: 파일 상태가 FAILED가 아니다");
      assertEquals("API_RATE_LIMIT", state.get().getErrorType(), "S6: 실패 유형이 API_RATE_LIMIT이 아니다");
    } finally {
      server.shutdown();
    }
  }

  // ===================================================================
  // S7 — 문맥 정리 / 문맥 없을 때
  // ===================================================================

  /** 실행이 끝나면 문맥이 남지 않고, 중첩 실행 뒤에는 바깥 문맥이 되돌아온다. */
  @Test
  void S7a_실행이_끝나면_문맥이_남지_않고_중첩_뒤에는_바깥_문맥이_복원된다() throws Exception {
    SessionConfig config = fastConfig();
    RetryHandler retryHandler = new RetryHandler(apiErrorHandler, config);
    AtomicBoolean innerStop = new AtomicBoolean(true);
    retryHandler.setStopCheckFactory(sessionId -> "inner".equals(sessionId)
        ? innerStop::get : () -> false);

    assertFalse(RetryStopSignal.hasContext(), "S7: 실행 전에 이미 문맥이 걸려 있다");

    String outer = retryHandler.executeWithRetry("outer", SESSION_ROOT + "/Sample.java", () -> {
      assertTrue(RetryStopSignal.hasContext(), "S7: 실행 중에 문맥이 걸려 있지 않다");
      assertFalse(RetryStopSignal.sleepOrStop(1), "S7: 바깥 문맥이 중단으로 판정됐다");

      String inner = retryHandler.executeWithRetry("inner", SESSION_ROOT + "/Sample.java",
          () -> {
            assertTrue(RetryStopSignal.sleepOrStop(1), "S7: 중첩 문맥이 중단으로 판정되지 않았다");
            return "inner";
          });

      assertFalse(RetryStopSignal.sleepOrStop(1), "S7: 중첩 실행 뒤 바깥 문맥이 복원되지 않았다");
      return "outer(" + inner + ")";
    });

    assertEquals("outer(inner)", outer);
    assertFalse(RetryStopSignal.hasContext(), "S7: 정상 반환 뒤에 문맥이 남았다");

    assertThrows(AnalysisException.class, () -> retryHandler.executeWithRetry(
        "outer", SESSION_ROOT + "/Sample.java", () -> {
          throw new AnalysisException(ApiErrorHandler.ErrorType.SERVER_ERROR,
              new RuntimeException("S7 예외 종료 경로"));
        }));
    assertFalse(RetryStopSignal.hasContext(), "S7: 예외 종료 뒤에 문맥이 남았다");

    System.out.println("[RC 정리] S7a 실행 전/후 문맥 잔존=false, 중첩 복원 확인");
  }

  /** {@link RetryHandler}를 거치지 않으면 판정 없이 대기를 전부 수행한다(요청 4회). */
  @Test
  void S7b_RetryHandler_없이_직접_부르면_판정_없이_대기를_전부_수행한다() throws Exception {
    SessionConfig config = new SessionConfig();
    config.setMaxRetries(MAX_RETRIES);
    config.setInitialRetryDelayMs(20);
    config.setMaxRetryDelayMs(1000);

    long lowerBoundMs = 400 + 800 + 1600;

    Server server = startRateLimitServer();
    try {
      CountingDelegate counter = newCounter(server);
      ClaudeServiceImpl service = newClaudeService(counter, config);

      long startedAt = System.currentTimeMillis();
      AnalysisException thrown = assertThrows(AnalysisException.class,
          () -> service.analyzeCodeWithClaude(SOURCE_CODE, "Sample.java",
              SESSION_ROOT, SESSION_ROOT + "/Sample.java"));
      long elapsedMs = System.currentTimeMillis() - startedAt;

      System.out.println("[RC 정리] S7b 직접 호출 위임=" + counter.callCount
          + " 서버=" + server.server.getRequestCount() + " 경과=" + elapsedMs
          + "ms 대기 합 하한=" + lowerBoundMs + "ms errorType=" + thrown.getErrorType()
          + " 문맥 잔존=" + RetryStopSignal.hasContext());

      assertTrue(counter.callCount >= 1, "S7b: LLM이 한 번도 불리지 않았다");
      assertEquals(RETRYABLE_ATTEMPTS, counter.callCount, "S7b: 요청 수가 4가 아니다");
      assertTrue(elapsedMs >= lowerBoundMs,
          "S7b: 경과 " + elapsedMs + "ms가 대기 합 하한 " + lowerBoundMs + "ms 미만이다 — 대기를 건너뛰었다");
      assertFalse(RetryStopSignal.hasContext(), "S7b: 문맥이 걸려 있지 않아야 한다");
    } finally {
      server.shutdown();
    }
  }

  // ===================================================================
  // 도우미
  // ===================================================================

  /** 표본 1000회의 [최소, 최대]. */
  private long[] sample(int retryCount, long initialDelayMs, long maxDelayMs, boolean overload) {
    long min = Long.MAX_VALUE;
    long max = Long.MIN_VALUE;
    for (int i = 0; i < 1000; i++) {
      long delay = overload
          ? apiErrorHandler.calculateOverloadRetryDelay(retryCount, initialDelayMs, maxDelayMs)
          : apiErrorHandler.calculateRetryDelay(ApiErrorHandler.ErrorType.SERVER_ERROR,
              retryCount, initialDelayMs, maxDelayMs);
      min = Math.min(min, delay);
      max = Math.max(max, delay);
    }
    return new long[] {min, max};
  }

  /** 429만 돌려주는 가짜 서버 + 첫 요청 도착 신호. */
  private static final class Server {
    private final MockWebServer server;
    private final CountDownLatch firstRequest = new CountDownLatch(1);

    private Server(MockWebServer server) {
      this.server = server;
    }

    private void shutdown() throws Exception {
      server.shutdown();
    }
  }

  private static Server startRateLimitServer() throws Exception {
    MockWebServer mockWebServer = new MockWebServer();
    Server wrapper = new Server(mockWebServer);
    mockWebServer.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        wrapper.firstRequest.countDown();
        return AnthropicErrorFixtures.mockResponse(AnthropicErrorFixtures.Case.RATE_LIMIT_429);
      }
    });
    mockWebServer.start();
    return wrapper;
  }

  /** 중단 케이스용 — 첫 연장 대기 3초 이상. */
  private static SessionConfig stopConfig() {
    SessionConfig config = new SessionConfig();
    config.setMaxRetries(MAX_RETRIES);
    config.setInitialRetryDelayMs(STOP_INITIAL_DELAY_MS);
    config.setMaxRetryDelayMs(STOP_MAX_DELAY_MS);
    return config;
  }

  /** 대조군·배선 확인용 — 대기만 짧게. */
  private static SessionConfig fastConfig() {
    SessionConfig config = new SessionConfig();
    config.setMaxRetries(MAX_RETRIES);
    config.setInitialRetryDelayMs(1);
    config.setMaxRetryDelayMs(10);
    return config;
  }

  /** 세션 조회 횟수를 세는 실제 세션 관리자. */
  private static final class CountingSessionManager extends AnalysisSessionManager {
    private final AtomicInteger lookups = new AtomicInteger();

    private CountingSessionManager(SessionConfig sessionConfig, SessionRepository sessionRepository) {
      super(sessionConfig, sessionRepository);
    }

    @Override
    public SessionState getSession(String sessionId) {
      lookups.incrementAndGet();
      return super.getSession(sessionId);
    }
  }

  private static CountingSessionManager newSessionManager() {
    return new CountingSessionManager(new SessionConfig(), mock(SessionRepository.class));
  }

  /** 같은 설정(짧은 대기)·같은 서버에서 세션 상태만 달리하고 요청 수를 센다. */
  private int requestsWithSessionManager(String id, CountingSessionManager sessionManager)
      throws Exception {
    SessionConfig config = fastConfig();
    Server server = startRateLimitServer();
    try {
      CountingDelegate counter = newCounter(server);
      ClaudeServiceImpl service = newClaudeService(counter, config);
      RetryHandler retryHandler = new RetryHandler(apiErrorHandler, config);
      retryHandler.setSessionManager(sessionManager);

      AnalysisException thrown = assertThrows(AnalysisException.class,
          () -> analyzeOneFile(retryHandler, service));

      assertTrue(counter.callCount >= 1, id + ": LLM이 한 번도 불리지 않았다");
      assertEquals(counter.callCount, server.server.getRequestCount(),
          id + ": 위임 카운터와 서버 요청 수가 다르다");
      assertEquals(ApiErrorHandler.ErrorType.API_RATE_LIMIT, thrown.getErrorType(),
          id + ": 최종 errorType이 다르다");
      return counter.callCount;
    } finally {
      server.shutdown();
    }
  }

  /** 작업 스레드 하나와 그 스레드가 받은 예외. */
  private static final class Worker {
    private final Thread thread;
    private final AtomicReference<AnalysisException> thrown = new AtomicReference<>();

    private Worker(Thread thread) {
      this.thread = thread;
    }
  }

  private static Worker runInWorker(RetryHandler retryHandler, ClaudeServiceImpl service) {
    Worker[] holder = new Worker[1];
    Thread thread = new Thread(() -> {
      try {
        analyzeOneFile(retryHandler, service);
      } catch (AnalysisException e) {
        holder[0].thrown.set(e);
      }
    }, "retry-stop-signal-worker");
    holder[0] = new Worker(thread);
    thread.start();
    return holder[0];
  }

  /** {@code MainApiController.analyzeFile}과 같은 형태 — 4인자 호출을 바깥 재시도로 감싼다. */
  private static String analyzeOneFile(RetryHandler retryHandler, ClaudeServiceImpl service) {
    return retryHandler.executeWithRetry(SESSION_ID, SESSION_ROOT + "/Sample.java",
        () -> service.analyzeCodeWithClaude(SOURCE_CODE, "Sample.java",
            SESSION_ROOT, SESSION_ROOT + "/Sample.java"));
  }

  private static FileAnalysisState invokeAnalyzeFile(MainApiController controller, Path sourceFile,
      Path targetPath, Path sourceRoot, Path outputRoot) throws Exception {
    Method method = MainApiController.class.getDeclaredMethod("analyzeFile",
        String.class, Path.class, Path.class, Path.class, boolean.class, String.class);
    method.setAccessible(true);
    try {
      return (FileAnalysisState) method.invoke(controller, SESSION_ID, sourceFile, targetPath,
          sourceRoot, false, outputRoot.toString());
    } catch (InvocationTargetException e) {
      throw new IllegalStateException("analyzeFile 호출이 예외로 끝났다", e.getCause());
    }
  }

  /** 실제 클라이언트에 위임하면서 호출 횟수만 세는 껍데기. */
  private static final class CountingDelegate implements LlmClient {
    private final LlmClient delegate;
    private volatile int callCount;

    private CountingDelegate(LlmClient delegate) {
      this.delegate = delegate;
    }

    @Override
    public LlmResult call(String systemPrompt, String userContent, String model, int maxTokens) {
      callCount++;
      return delegate.call(systemPrompt, userContent, model, maxTokens);
    }
  }

  private static CountingDelegate newCounter(Server server) {
    return new CountingDelegate(new AnthropicLlmClient(
        AnthropicErrorFixtures.INVALID_KEY, "http://localhost:" + server.server.getPort()));
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
