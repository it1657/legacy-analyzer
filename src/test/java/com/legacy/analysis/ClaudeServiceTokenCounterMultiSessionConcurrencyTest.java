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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * "A의 합계가 정확하다"는 단언과 <b>별도로</b> 교차가 실제로 일어났음을 계측해 단언한다.
 *
 * <p>처음에는 그 겹침을 스케줄링 운에 맡기고 계측 단언으로 걸러 내기만 했다. 그러면 겹침이 생기지
 * 않은 실행은 정확성이 맞았는데도 경로 증거 단언에서 빨개진다. 그래서 <b>겹침 1회는 테스트가 직접
 * 만들고, 나머지는 그대로 자연 경합에 둔다.</b>
 *
 * <ul>
 *   <li><b>강제 겹침 1회</b> — A 지정 스레드는 두 번째 누적에서 {@code activeAccumulatorsA}를 올린
 *       직후 멈춰 서고, B 지정 스레드는 첫 누적 직후 "지울 값이 생겼다"를 알린다. 리셋 스레드는 그
 *       두 사건을 기다린 뒤 <b>첫 리셋 1회</b>를 기존 계측 코드 그대로 수행하고 A를 놓아준다. 래치
 *       셋에는 각각 대기 상한이 있어, 초과가 무한 대기가 아니라 실패로 드러난다.</li>
 *   <li><b>나머지는 자연 경합</b> — 그 뒤의 리셋 루프와 나머지 누적 스레드는 바뀌지 않았다. 자연
 *       겹침 횟수는 관측값으로만 출력하고 <b>단언하지 않는다</b>(확률적 값).</li>
 * </ul>
 *
 * <p>단언 순서는 ⓪ → ① → ② → ③ 이다.
 *
 * <ul>
 *   <li><b>⓪ 작업 실패 표시 없음</b> — 래치 상한 초과와 작업 스레드 예외를 {@code AtomicReference}에
 *       담아 맨 먼저 단언한다. {@code Future}를 회수하지 않으므로, 이 표시를 보지 않으면 "아무 일도
 *       일어나지 않았는데 통과"를 구별할 수 없다.</li>
 *   <li><b>① 정확성</b> — A의 합계와 {@code modelName}. 격리가 성립하면 스케줄링과 무관하게
 *       결정론적이므로 정확히 단언한다 — 그것이 이 테스트가 잡으려는 대상이다.</li>
 *   <li><b>② 경로 증거</b> — {@code resetsDuringAccumulation}(A 누적 진행 중에 실행된 리셋 횟수) ·
 *       {@code maxInFlightSeenAtReset}(리셋 시점에 관측된 A 동시 누적 스레드 최대) ·
 *       {@code resetsThatActuallyCleared}(리셋 직전 B의 누적치가 {@code > 0}이었던 횟수)가 모두
 *       {@code > 0}. 단언과 메시지를 그대로 두었고, 강제 1회가 있으므로 이제 매 실행 성립한다.</li>
 *   <li><b>③ 강제 지점</b> — 강제 리셋 그 시점의 {@code inFlight}가 1 이상이고 {@code bHadTokens}가
 *       참. ②의 {@code > 0}이 강제 1회에서 나왔다는 것을 못 박는다.</li>
 * </ul>
 *
 * <p><b>B의 최종값은 단언하지 않는다</b> — 누적과 리셋이 경쟁하므로 스케줄링에 따라 달라지는
 * 확률적 값이다. 확률적 값을 단언하면 CI가 불안정해진다는 기존 교훈(`AnalysisStatisticsConcurrencyTest`)에
 * 따라 <b>관측값만 출력</b>한다.
 *
 * <p><b>원 설계와의 관계</b>: 이 파일을 만든 {@code 2026-09-cost-stats-bugfix} 사이클(커밋
 * {@code 4ffbebf})이 단언 대상으로 삼은 것은 "A가 누적하는 <b>도중에</b> 교차 리셋이 끼어들어도 A의
 * 합계가 정확하다"였고, 그 겹침이 자연 스케줄링에서 나왔다는 사실은 관측값이지 단언 대상이 아니었다.
 * 강제 겹침은 그 "도중에"를 운에서 보장으로 바꾼 것이므로, 재는 대상은 그대로다.
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
    // 래치 대기 상한 — 기다리는 사건은 지정 스레드의 첫 1~2회 호출뿐이라 이 정도면 넉넉하다.
    int latchTimeoutSeconds = 30;

    // "경로를 실제로 탔는가"를 재는 계측기 — GREEN만으로는 순차 교차와 구별되지 않는다.
    AtomicInteger activeAccumulatorsA = new AtomicInteger(0);
    AtomicInteger resetTotal = new AtomicInteger(0);
    AtomicInteger resetsDuringAccumulation = new AtomicInteger(0);
    AtomicInteger resetsThatActuallyCleared = new AtomicInteger(0);
    AtomicInteger maxInFlightSeenAtReset = new AtomicInteger(0);
    AtomicBoolean accumulationFinished = new AtomicBoolean(false);

    // 강제 겹침 1회를 만드는 래치 셋 — A가 누적 구간에 들어감 / B에 지울 값이 생김 /
    // 리셋 스레드가 그 겹침 지점에서 첫 리셋을 끝냄.
    CountDownLatch aAccumulating = new CountDownLatch(1);
    CountDownLatch bHasTokens = new CountDownLatch(1);
    CountDownLatch forcedResetDone = new CountDownLatch(1);
    // 강제 리셋 그 시점의 계측값만 따로 보관한다 — 뒤이은 자연 경합 리셋에 섞이지 않게.
    AtomicInteger forcedResetInFlight = new AtomicInteger(-1);
    AtomicBoolean forcedResetBHadTokens = new AtomicBoolean(false);
    // 상한 초과·예외를 담는다. Future를 회수하지 않으므로 이 표시가 ⓪ 단언의 유일한 통로다.
    AtomicReference<String> workerFailure = new AtomicReference<>(null);

    ExecutorService pool = Executors.newFixedThreadPool(accumulatorThreadsA + accumulatorThreadsB + 1);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch accumulatorsDone = new CountDownLatch(accumulatorThreadsA + accumulatorThreadsB);
    CountDownLatch resetterDone = new CountDownLatch(1);
    try {
      for (int t = 0; t < accumulatorThreadsA; t++) {
        boolean designatedA = (t == 0);
        pool.submit(() -> {
          try {
            start.await();
            for (int c = 0; c < callsPerThread; c++) {
              activeAccumulatorsA.incrementAndGet();
              try {
                // 지정 스레드만 두 번째 반복에서 멈춰 서서 리셋 스레드에 겹침 지점을 넘긴다.
                if (designatedA && c == 1) {
                  aAccumulating.countDown();
                  if (!forcedResetDone.await(latchTimeoutSeconds, TimeUnit.SECONDS)) {
                    workerFailure.compareAndSet(null, "세션A 지정 스레드가 강제 리셋 완료를 "
                        + latchTimeoutSeconds + "초 안에 받지 못했다");
                  }
                }
                accumulate(service, perCall, 0L, 0L, 0L, modelA, sessionA);
              } finally {
                activeAccumulatorsA.decrementAndGet();
              }
            }
          } catch (Exception e) {
            workerFailure.compareAndSet(null, "세션A 누적 스레드 예외: " + e);
            throw new IllegalStateException(e);
          } finally {
            accumulatorsDone.countDown();
          }
        });
      }
      for (int t = 0; t < accumulatorThreadsB; t++) {
        boolean designatedB = (t == 0);
        pool.submit(() -> {
          try {
            start.await();
            for (int c = 0; c < callsPerThread; c++) {
              accumulate(service, perCall, 0L, 0L, 0L, modelB, sessionB);
              // 기다리지 않는다 — 리셋 스레드에 "지울 값이 생겼다"만 알린다.
              if (designatedB && c == 0) {
                bHasTokens.countDown();
              }
            }
          } catch (Exception e) {
            workerFailure.compareAndSet(null, "세션B 누적 스레드 예외: " + e);
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
          boolean forcedDone = false;
          int safetyCap = 5_000_000;
          while (!accumulationFinished.get() && safetyCap-- > 0) {
            if (!forcedDone) {
              // 첫 바퀴만 겹침 지점을 기다린다. 두 사건은 이미 일어난 것들이라 순환이 없다.
              boolean aReady = aAccumulating.await(latchTimeoutSeconds, TimeUnit.SECONDS);
              boolean bReady = bHasTokens.await(latchTimeoutSeconds, TimeUnit.SECONDS);
              if (!aReady || !bReady) {
                workerFailure.compareAndSet(null, "리셋 스레드의 겹침 대기가 " + latchTimeoutSeconds
                    + "초 상한을 넘었다 — aAccumulating=" + aReady + ", bHasTokens=" + bReady);
              }
            }
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
            if (!forcedDone) {
              // 그 첫 리셋이 강제 겹침 1회다. 값을 따로 보관하고 A를 놓아준다.
              forcedResetInFlight.set(inFlight);
              forcedResetBHadTokens.set(bHadTokens);
              forcedResetDone.countDown();
              forcedDone = true;
            }
            Thread.yield();
          }
        } catch (Exception e) {
          workerFailure.compareAndSet(null, "리셋 스레드 예외: " + e);
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
    System.out.printf(
        "[TASK-004 누적 중 교차 리셋 / 겹침 출처(단언 안 함)] 강제 1회 / 자연 겹침 %d회"
            + "(= 누적 중 리셋 %d회 - 강제 1회) / 강제 지점 inFlight %d · bHadTokens %s%n",
        resetsDuringAccumulation.get() - 1, resetsDuringAccumulation.get(),
        forcedResetInFlight.get(), forcedResetBHadTokens.get());

    // ⓪ 작업 실패 표시 단언: Future를 회수하지 않으므로 상한 초과·예외는 이 표시로만 드러난다.
    //    맨 먼저 본다 — 아래 단언들은 이 표시가 없을 때만 의미가 있다.
    assertNull(workerFailure.get(),
        "작업 스레드가 래치 상한 초과나 예외를 보고했다 — 강제 겹침이 성립하지 않았다");

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

    // ③ 강제 지점 단언: 위 ②의 "> 0"이 운이 아니라 강제 겹침 1회에서 나왔음을 못 박는다.
    assertTrue(forcedResetInFlight.get() >= 1,
        "강제 리셋 시점에 세션A 누적 스레드가 진행 중(inFlight ≥ 1)이어야 한다 — 관측 "
            + forcedResetInFlight.get());
    assertTrue(forcedResetBHadTokens.get(),
        "강제 리셋 시점에 세션B에 지울 누적값이 있어야 한다(bHadTokens = true)");
  }
}
