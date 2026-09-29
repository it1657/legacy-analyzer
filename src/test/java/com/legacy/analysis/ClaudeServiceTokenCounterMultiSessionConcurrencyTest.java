package com.legacy.analysis;

import com.legacy.analysis.llm.LlmResult;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-004 (work-order 2026-09-cost-stats-bugfix v2) —
 * <b>{@code ClaudeServiceTokenCounterIsolationTest}(하네스)가 덮지 못하는 두 경로를 검증한다</b>.
 * 프로덕션 코드 변경 0행이다.
 *
 * <p><b>하네스와의 분업</b>(v2 §0.6 판정 4): 하네스는 수정 전/후 대조를 위해 측정 모양이 동결돼
 * 있어(세션 2개, 순차 교차 리셋) 케이스를 보탤 수 없다. 이 파일이 그 두 빈칸을 채운다.
 *
 * <ul>
 *   <li><b>세션 3개 이상</b> — 하네스는 2개까지만 본다. 키가 둘일 때는 "서로 뒤바뀜"과 "정확히 격리됨"을
 *       구분하기 어려운 조합도 있으므로, 세션 4개에 <b>서로 다른 토큰량</b>을 주어 어떤 교차 오염도
 *       숫자로 드러나게 한다.</li>
 *   <li><b>누적 도중의 교차 리셋</b> — 하네스 (i)은 "A 누적 완료 → B 리셋 → A 조회"의 <b>순차</b>
 *       교차이므로, 리셋이 <b>A의 누적이 진행 중인 동안</b> 끼어드는 경로는 아직 미검증이다.
 *       {@code MainApiController}에서 실제로 일어나는 형태는 이쪽이다(세션 A가 16개 스레드로
 *       파일을 분석하는 중에 다른 사용자가 세션 B의 분석을 시작한다).</li>
 * </ul>
 *
 * <h2>무엇을 이 테스트의 "증거"로 삼는가</h2>
 *
 * <p>두 번째 케이스는 <b>GREEN만으로는 아무것도 증명하지 못한다.</b> 리셋 스레드가 A의 누적이 전부
 * 끝난 뒤에 돌았다면 그건 하네스 (i)과 똑같은 순차 교차이고, 그래도 테스트는 통과한다. 그래서
 * "A의 합계가 정확하다"는 단언과 <b>별도로</b> 아래를 계측해 단언한다.
 *
 * <ul>
 *   <li>{@code activeAccumulators} — A의 누적 호출에 들어가 있는 스레드 수. 리셋 스레드는 매 리셋
 *       시점에 이 값을 읽는다.</li>
 *   <li>{@code resetsDuringAccumulation} — 그 값이 {@code > 0}인 상태에서 실행된 리셋 횟수.
 *       <b>이 값이 0이면 교차가 일어나지 않았다는 뜻이므로 테스트를 실패시킨다.</b></li>
 *   <li>{@code resetsThatActuallyCleared} — 리셋 직전 B의 누적치가 {@code > 0}이었던 횟수.
 *       리셋이 "빈 카운터를 지우는 무의미한 호출"만 반복한 게 아님을 보인다.</li>
 * </ul>
 *
 * <p><b>B의 최종값은 단언하지 않는다</b> — 누적과 리셋이 경쟁하므로 스케줄링에 따라 달라지는
 * 확률적 값이다. 확률적 값을 단언하면 CI가 불안정해진다는 기존 교훈(`AnalysisStatisticsConcurrencyTest`)에
 * 따라 <b>관측값만 출력</b>한다. 반대로 A의 합계는 격리가 성립하면 스케줄링과 무관하게 결정론적이므로
 * 정확히 단언한다 — 그것이 이 테스트가 잡으려는 대상이다.
 *
 * <p><b>스레드 16의 근거</b>: 운영 기본값 {@code app.analysis.thread-pool-size=16}
 * ({@code AnalysisStatisticsConcurrencyTest}·하네스가 쓴 선례와 동일).
 *
 * <p>관측값은 {@code showStandardStreams = false} 때문에 콘솔이 아니라
 * {@code build/test-results/test/TEST-com.legacy.analysis.ClaudeServiceTokenCounterMultiSessionConcurrencyTest.xml}의
 * {@code <system-out>}에 남는다. 수정 전/후 대조표는 {@code 05-dev-progress.md}에 있다.
 */
class ClaudeServiceTokenCounterMultiSessionConcurrencyTest {

  /** 운영 기본값 {@code app.analysis.thread-pool-size}와 같은 스레드 수. */
  private static final int THREADS = 16;

  private static ClaudeServiceImpl newService() {
    return new ClaudeServiceImpl(null, null, null, null, null, null, null);
  }

  private static void accumulate(ClaudeServiceImpl service, long in, long out,
      long cacheRead, long cacheCreate, String model, String sourceFolderPath) throws Exception {
    Method method = ClaudeServiceImpl.class.getDeclaredMethod(
        "extractAndStoreTokenUsage", LlmResult.class, String.class, String.class);
    method.setAccessible(true);
    method.invoke(service, new LlmResult("dummy-response", in, out, cacheRead, cacheCreate),
        model, sourceFolderPath);
  }

  @Test
  void 세션_4개가_동시에_누적해도_각_키가_자기_토큰만_갖는다() throws Exception {
    ClaudeServiceImpl service = newService();

    // 세션마다 토큰량과 모델을 다르게 준다 — 값이 같으면 "서로 뒤바뀜"이 숫자로 드러나지 않는다.
    Map<String, Long> perCallBySession = new LinkedHashMap<>();
    perCallBySession.put("C:\\work\\multi-session-1", 100L);
    perCallBySession.put("C:\\work\\multi-session-2", 200L);
    perCallBySession.put("C:\\work\\multi-session-3", 300L);
    perCallBySession.put("C:\\work\\multi-session-4", 400L);
    Map<String, String> modelBySession = new LinkedHashMap<>();
    modelBySession.put("C:\\work\\multi-session-1", "claude-sonnet-4-6");
    modelBySession.put("C:\\work\\multi-session-2", "claude-opus-4-8");
    modelBySession.put("C:\\work\\multi-session-3", "claude-haiku-4-5-20251001");
    modelBySession.put("C:\\work\\multi-session-4", "claude-sonnet-4-6");

    String[] sessions = perCallBySession.keySet().toArray(new String[0]);
    int threadsPerSession = THREADS / sessions.length;   // 16 / 4 = 4
    int callsPerThread = 40;

    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(THREADS);
    try {
      for (String session : sessions) {
        long perCall = perCallBySession.get(session);
        String model = modelBySession.get(session);
        for (int t = 0; t < threadsPerSession; t++) {
          pool.submit(() -> {
            try {
              start.await();
              for (int c = 0; c < callsPerThread; c++) {
                accumulate(service, perCall, perCall / 10, 0L, 0L, model, session);
              }
            } catch (Exception e) {
              throw new IllegalStateException(e);
            } finally {
              done.countDown();
            }
          });
        }
      }
      start.countDown();
      assertTrue(done.await(60, TimeUnit.SECONDS), "4개 세션 동시 누적이 60초 안에 끝나야 한다");
    } finally {
      pool.shutdownNow();
    }

    long grandTotalExpected = 0L, grandTotalObserved = 0L;
    for (String session : sessions) {
      long perCall = perCallBySession.get(session);
      long expectedIn = (long) threadsPerSession * callsPerThread * perCall;
      long expectedOut = (long) threadsPerSession * callsPerThread * (perCall / 10);
      TokenUsage observed = service.getTotalTokenUsage(session);
      System.out.printf(
          "[TASK-004 세션 4개 동시 누적] %s (스레드 %d × %d회 × %d토큰) → "
              + "input 기대 %d / 실측 %d, output 기대 %d / 실측 %d, modelName 기대 %s / 실측 %s%n",
          session, threadsPerSession, callsPerThread, perCall,
          expectedIn, observed.getInputTokens(), expectedOut, observed.getOutputTokens(),
          modelBySession.get(session), observed.getModelName());

      assertEquals(expectedIn, observed.getInputTokens(), session + " 의 input이 자기 몫과 일치해야 한다");
      assertEquals(expectedOut, observed.getOutputTokens(), session + " 의 output이 자기 몫과 일치해야 한다");
      assertEquals(modelBySession.get(session), observed.getModelName(),
          session + " 의 modelName이 자기 세션 모델이어야 한다(5번째 격리 필드)");
      grandTotalExpected += expectedIn;
      grandTotalObserved += observed.getInputTokens();
    }

    // 손실도 중복도 없음을 함께 고정한다 — 어느 한 세션이 남의 토큰을 먹었다면 개별 단언이,
    // 원자적 누적에 손실이 있었다면 이 총합 단언이 잡는다.
    System.out.printf("[TASK-004 세션 4개 동시 누적] 전체 합계 기대 %d / 실측 %d%n",
        grandTotalExpected, grandTotalObserved);
    assertEquals(grandTotalExpected, grandTotalObserved);
  }

  @Test
  void 세션A가_누적하는_도중에_세션B를_리셋해도_세션A의_합계가_정확하다() throws Exception {
    ClaudeServiceImpl service = newService();

    String sessionA = "C:\\work\\concurrent-reset-a";
    String sessionB = "C:\\work\\concurrent-reset-b";
    String modelA = "claude-sonnet-4-6";
    String modelB = "claude-haiku-4-5-20251001";

    int accumulatorThreadsA = 8;
    int accumulatorThreadsB = 4;
    int callsPerThread = 300;
    long perCall = 100L;

    // "경로를 실제로 탔는가"를 재는 계측기 — GREEN만으로는 순차 교차와 구별되지 않는다.
    AtomicInteger activeAccumulatorsA = new AtomicInteger(0);
    AtomicInteger resetTotal = new AtomicInteger(0);
    AtomicInteger resetsDuringAccumulation = new AtomicInteger(0);
    AtomicInteger resetsThatActuallyCleared = new AtomicInteger(0);
    AtomicInteger maxInFlightSeenAtReset = new AtomicInteger(0);
    AtomicBoolean accumulationFinished = new AtomicBoolean(false);

    ExecutorService pool = Executors.newFixedThreadPool(accumulatorThreadsA + accumulatorThreadsB + 1);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch accumulatorsDone = new CountDownLatch(accumulatorThreadsA + accumulatorThreadsB);
    CountDownLatch resetterDone = new CountDownLatch(1);
    try {
      for (int t = 0; t < accumulatorThreadsA; t++) {
        pool.submit(() -> {
          try {
            start.await();
            for (int c = 0; c < callsPerThread; c++) {
              activeAccumulatorsA.incrementAndGet();
              try {
                accumulate(service, perCall, 0L, 0L, 0L, modelA, sessionA);
              } finally {
                activeAccumulatorsA.decrementAndGet();
              }
            }
          } catch (Exception e) {
            throw new IllegalStateException(e);
          } finally {
            accumulatorsDone.countDown();
          }
        });
      }
      for (int t = 0; t < accumulatorThreadsB; t++) {
        pool.submit(() -> {
          try {
            start.await();
            for (int c = 0; c < callsPerThread; c++) {
              accumulate(service, perCall, 0L, 0L, 0L, modelB, sessionB);
            }
          } catch (Exception e) {
            throw new IllegalStateException(e);
          } finally {
            accumulatorsDone.countDown();
          }
        });
      }
      // 리셋 스레드 — 세션 B의 분석을 반복해서 "처음부터 새로 시작"하는 사용자에 해당한다.
      pool.submit(() -> {
        try {
          start.await();
          int safetyCap = 5_000_000;
          while (!accumulationFinished.get() && safetyCap-- > 0) {
            int inFlight = activeAccumulatorsA.get();
            boolean bHadTokens = service.getTotalTokenUsage(sessionB).getInputTokens() > 0;
            service.resetTokenUsage(sessionB);
            resetTotal.incrementAndGet();
            if (inFlight > 0) {
              resetsDuringAccumulation.incrementAndGet();
              maxInFlightSeenAtReset.accumulateAndGet(inFlight, Math::max);
            }
            if (bHadTokens) {
              resetsThatActuallyCleared.incrementAndGet();
            }
            Thread.yield();
          }
        } catch (Exception e) {
          throw new IllegalStateException(e);
        } finally {
          resetterDone.countDown();
        }
      });

      start.countDown();
      assertTrue(accumulatorsDone.await(120, TimeUnit.SECONDS), "누적이 120초 안에 끝나야 한다");
      accumulationFinished.set(true);
      assertTrue(resetterDone.await(30, TimeUnit.SECONDS), "리셋 스레드가 30초 안에 끝나야 한다");
    } finally {
      pool.shutdownNow();
    }

    long expectedA = (long) accumulatorThreadsA * callsPerThread * perCall;
    TokenUsage observedA = service.getTotalTokenUsage(sessionA);
    TokenUsage observedB = service.getTotalTokenUsage(sessionB);

    System.out.printf(
        "[TASK-004 누적 중 교차 리셋] 세션A(스레드 %d × %d회 × %d토큰) input 기대 %d / 실측 %d / "
            + "modelName 기대 %s / 실측 %s%n",
        accumulatorThreadsA, callsPerThread, perCall, expectedA, observedA.getInputTokens(),
        modelA, observedA.getModelName());
    System.out.printf(
        "[TASK-004 누적 중 교차 리셋 / 경로 증거] 리셋 총 %d회 / 그중 세션A 누적 진행 중에 실행된 리셋 %d회 / "
            + "리셋 시점에 관측된 세션A 동시 누적 스레드 최대 %d개(A 누적 스레드 %d개 중) / "
            + "실제로 값을 지운 리셋 %d회%n",
        resetTotal.get(), resetsDuringAccumulation.get(), maxInFlightSeenAtReset.get(),
        accumulatorThreadsA, resetsThatActuallyCleared.get());
    System.out.printf(
        "[TASK-004 누적 중 교차 리셋 / 참고(단언 안 함)] 세션B 최종 input 실측 %d "
            + "(누적과 리셋이 경쟁하므로 확률적 값이다 / 단언하면 flaky해진다)%n",
        observedB.getInputTokens());

    // ① 격리 단언: A의 합계는 남의 리셋과 무관하게 정확해야 한다(결정론적).
    assertEquals(expectedA, observedA.getInputTokens(),
        "세션A 누적 중에 세션B를 리셋해도 A의 합계는 정확해야 한다");
    assertEquals(modelA, observedA.getModelName(),
        "세션A의 modelName이 세션B의 모델로 덮이지 않아야 한다");

    // ② 경로 증거 단언: 위 ①이 "순차 교차"를 통과한 것이 아님을 보증한다.
    //    이 단언이 없으면 리셋이 A의 누적이 끝난 뒤에만 돌았어도 테스트가 통과한다.
    assertTrue(resetsDuringAccumulation.get() > 0,
        "세션A의 누적이 진행 중인 동안 실행된 리셋이 1회도 없었다 — 교차 경로를 타지 못했으므로 "
            + "이 테스트는 하네스 (i)의 순차 교차와 다를 바가 없다");
    assertTrue(maxInFlightSeenAtReset.get() > 0,
        "리셋 시점에 세션A 누적 스레드가 동시에 진행 중이었음이 관측돼야 한다");
    assertTrue(resetsThatActuallyCleared.get() > 0,
        "리셋이 빈 카운터만 반복해 지운 것이 아니라 실제로 세션B의 누적값을 지운 적이 있어야 한다");
  }
}
