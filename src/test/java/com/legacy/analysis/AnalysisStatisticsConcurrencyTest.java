package com.legacy.analysis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-005 (work-order 2026-09-quick-fixes-batch v1) —
 * <b>{@code AnalysisStatistics} 카운터 갱신의 수정 전 동시성 결함 재현 하네스</b>.
 * 프로덕션 코드 변경은 0줄이며, 이 클래스의 목적은 수정 전 상태의 <b>양성 대조군</b>
 * (STRUCTURE.md 20절 / work-order §0.4)을 확보하는 것이다.
 *
 * <p><b>왜 필요한가</b>: {@code MainApiController}의 병렬 분석 루프는 파일 하나를 끝낼 때마다
 * 여러 스레드에서 동시에 세션 통계를 갱신한다. 그런데 {@code AnalysisStatistics}의
 * {@code successCount}/{@code skipCount}/{@code failureCount}는 <b>plain {@code int}</b>이고
 * {@code volatile}도 아니며, 갱신은 setter 대입 한 줄로 이뤄진다. 즉 갱신이 원자적이지 않다.
 *
 * <p><b>재현하는 두 패턴</b>(둘 다 실소스를 그대로 흉내낸 것이다):
 * <ul>
 *   <li><b>P1 — stale write</b>: 외부 {@code AtomicInteger.incrementAndGet()}으로 얻은 값을
 *       {@code stats.setSuccessCount(값)}으로 <b>대입</b>한다. 증가 자체는 원자적이지만
 *       "증가"와 "대입"이 분리돼 있어, 낮은 값을 받은 스레드가 뒤늦게 대입하면 큰 값을 덮어쓴다.
 *       실소스: {@code runAnalysis()} 1482-1483행({@code setSuccessCount}) / 1487-1488행({@code setSkipCount}),
 *       {@code runAnalysisResume()} 1759행 / 1763행.</li>
 *   <li><b>P2 — read-modify-write</b>: {@code stats.getFailureCount() + 1}을 읽어 계산한 뒤
 *       {@code stats.setFailureCount(값)}으로 되쓴다. 읽기·계산·쓰기 세 단계가 전부 비원자적이라
 *       손실이 P1보다 크게 난다.
 *       실소스: {@code runAnalysis()} 1495-1496행, {@code runAnalysisResume()} 1768행.</li>
 * </ul>
 *
 * <p><b>스레드 수 16의 근거</b>: 운영 기본값 {@code app.analysis.thread-pool-size=16}
 * ({@code application.properties} 119행)이며, {@code MainApiController} 1445~1448행에서
 * 이 값이 0 이하일 때만 CPU 기반 대체값을 쓰고 그 외에는 그대로 분석 스레드 풀 크기가 된다.
 * 즉 <b>실제 운영에서 동시에 카운터를 갱신하는 스레드 수가 16</b>이다.
 *
 * <p><b>이 클래스의 재현 케이스는 단언하지 않는다</b> — 손실은 스케줄링에 의존하는 확률적 현상이라
 * 단언하면 flaky해진다(work-order TASK-005 작업내용 2). 대신 <b>관측값만 출력</b>한다. 출력은
 * {@code build.gradle}의 {@code showStandardStreams = false} 때문에 콘솔에는 안 나오고
 * {@code build/test-results/test/TEST-com.legacy.analysis.AnalysisStatisticsConcurrencyTest.xml}의
 * {@code <system-out>}에 남는다.
 *
 * <p><b>실측 결과 요약(2026-09-10, Windows 10 / 8 logical cores)</b> — 자세한 수치는
 * {@code 05-dev-progress.md} TASK-005 항목에 있다:
 * <ul>
 *   <li><b>P2는 재현된다</b> — 16×2,000에서 <b>5/5 라운드 전부</b> 손실(평균 15,043 손실).</li>
 *   <li><b>P1의 "최종값 손실"은 아래 <u>루프 하네스</u>에서는 관측되지 않았다</b> — 스레드
 *       16/64/256, 반복 2,000/20,000, {@code Thread.yield()}/스핀 지연 조합까지 넓힌
 *       125라운드에서 0건이었다. <b>이것은 "결함이 없다"는 뜻이 아니다</b>(아래 정정 항목 참고).</li>
 *   <li><b>P1의 폴링 stale read는 재현된다</b> — 폴링 관측자(운영의
 *       {@code /api/analysis/status} 역할)를 붙이면 <b>읽는 값이 뒤로 가는 stale read</b>가
 *       다수 관측된다(4/5 라운드, 누적 28건, 최대 15,501 되감김).</li>
 * </ul>
 *
 * <p><b>[2026-09-10 정정] P1의 최종값 손실은 실제로 재현된다 — 루프 하네스가 실경로를
 * 모델링하지 않았을 뿐이다</b> (work-order v2 §0.5.1 / §0.8, {@code 05-dev-progress.md} 1038~1123행):
 * <ul>
 *   <li>실제 {@code runAnalysis()}는 <b>파일 1개 = 태스크 1개 = 증가→대입 1회</b>인데,
 *       아래 P1 최종값 케이스는 스레드당 2,000~20,000회 <b>루프</b>를 돈다. 루프에서는 종반의
 *       대입이 전부 최댓값 근처라 최종값이 자연히 수렴한다 — <b>결함이 없어서 0이 아니라
 *       결함이 드러나는 지점을 비켜가서 0</b>이다.</li>
 *   <li>실경로를 모델링하자 손실이 나왔다: {@code files=2}에서 최대손실 1
 *       (= 최종값이 1로 남음 = {@code bug-suspects.md} 원 등록의 {@code expected: <2> but was: <1>}와
 *       같은 형태), {@code files=8} + 증가-대입 사이 {@code Thread.onSpinWait()} 1회에서
 *       10만 시행 중 12회(0.0120%). <b>근거 원문과 프로브 소스는 이 저장소 안에 보존돼 있다</b> —
 *       {@code src/test/resources/concurrency/}(README.txt / P1Probe.java.txt / P1Probe2.java.txt,
 *       work-order v2 TASK-005 작업 내용 6).</li>
 *   <li>같은 모델을 이 클래스 안에 옮긴 것이
 *       {@link #수정전_패턴P1_실경로모델_파일당_증가대입_1회에서_최종값_손실을_관측한다()}이다
 *       (작업 내용 7).</li>
 * </ul>
 *
 * <p><b>그래서 P1 최종값 손실은 회귀 게이트(단언)로 쓰지 않는다</b> — 발생률이 0.002~0.012%라
 * 단언에 넣는 순간 flaky해지고, 32,000회 규모 1회 실행으로는 사실상 관측되지 않는다.
 * 아래 P1 루프 케이스를 <b>삭제하지 않고 남겨 두는 이유가 이것</b>이다: "왜 이 지표를 게이트로
 * 쓰지 않는가"의 기록이다(work-order v2 TASK-005 작업 내용 4). 수정 후의 회귀 게이트는
 * <b>폴링 stale read 누적 0(결정적)</b>과 <b>최종값 정확히 32,000</b>이 담당한다(TASK-006 DoD 1).
 *
 * <p><b>보고 문구의 범위(work-order v2 §0.8)</b>: 이 클래스의 관측 케이스가 0을 내더라도
 * <b>"이 하네스에서는 관측되지 않았다 + 하네스 형태는 이러하다"</b>까지만 쓴다.
 * <b>"재현되지 않는다" / "설계 전제가 틀렸다"로 넓히지 않는다.</b> 전제를 폐기할지의 판단은 PL이 한다.
 */
class AnalysisStatisticsConcurrencyTest {

  /** 운영 기본값 {@code app.analysis.thread-pool-size=16}과 동일하게 맞춘다. */
  private static final int THREADS = 16;

  /** 스레드당 증가 횟수. */
  private static final int ITERATIONS_PER_THREAD = 2_000;

  /** 손실이 전혀 없을 때의 기대값 = 16 × 2,000 = 32,000. */
  private static final int EXPECTED_TOTAL = THREADS * ITERATIONS_PER_THREAD;

  /** 관측 반복 횟수 — 손실률(N회 중 M회)을 정량으로 내기 위한 표본 수. */
  private static final int ROUNDS = 5;

  // ── 작업 내용 7: 실경로 모델 관측 케이스용 상수 ────────────────────────────────

  /**
   * <b>실경로 모델의 시행 수 — 이 상수 하나만 올리면 재현 확률이 올라간다.</b>
   * 손실률이 0.002~0.012% 수준이므로 여기 있는 값(파일 수 설정당 20,000회)에서는
   * <b>0이 나오는 것이 정상</b>이다. 메인 세션 프로브는 설정당 100,000회를 썼다.
   * 이 값을 올리면 그만큼 테스트 소요가 늘어난다 — 전체 스위트 부담을 고려해
   * 이 케이스 총 소요가 10초 안쪽이 되도록 잡았다(work-order v2 TASK-005 작업 내용 7).
   */
  private static final int REALPATH_TRIALS_PER_FILE_COUNT = 20_000;

  /** 실경로 모델에서 쓰는 "분석 대상 파일 수" = 동시에 카운터를 갱신하는 태스크 수. */
  private static final int[] REALPATH_FILE_COUNTS = {2, 5, 8};

  /** 작업 내용 6으로 저장소에 보존한 프로브 근거(클래스패스 리소스). */
  private static final String PROBE_EVIDENCE_README = "/concurrency/README.txt";

  /** 작업 내용 6으로 보존한 프로브 소스 2종. */
  private static final String[] PROBE_EVIDENCE_SOURCES = {
      "/concurrency/P1Probe.java.txt", "/concurrency/P1Probe2.java.txt"};

  // ────────────────────────────────────────────────────────────────────────────
  // 하네스 (실소스 패턴을 그대로 흉내낸다)
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * <b>패턴 P1 — stale write</b> 재현.
   * 외부 {@code AtomicInteger}를 증가시켜 얻은 값을 세션 통계에 <b>대입</b>한다.
   *
   * @param yieldBetween 증가와 대입 사이에 {@code Thread.yield()}를 넣어 경합 창을 넓힐지 여부
   * @return 최종 관측된 {@code stats.getSuccessCount()}
   */
  private static int runPatternP1StaleWrite(int threads, int iterations, boolean yieldBetween)
      throws InterruptedException {
    AnalysisStatistics stats = new AnalysisStatistics();
    AtomicInteger externalCounter = new AtomicInteger(0);

    runConcurrently(threads, iterations, () -> {
      // 실소스: int sc = successCount.incrementAndGet();
      //         session.getStatistics().setSuccessCount(sc);
      int sc = externalCounter.incrementAndGet();
      if (yieldBetween) {
        Thread.yield();
      }
      stats.setSuccessCount(sc);
    });

    // 외부 AtomicInteger는 항상 정확하다 — 손실이 생긴다면 "대입" 단계에서만 생긴다.
    if (externalCounter.get() != threads * iterations) {
      throw new IllegalStateException("외부 AtomicInteger가 기대값과 다르다: " + externalCounter.get());
    }
    return stats.getSuccessCount();
  }

  /**
   * <b>패턴 P2 — read-modify-write</b> 재현.
   * {@code getFailureCount() + 1}을 읽어 계산한 뒤 되쓴다.
   *
   * @return 최종 관측된 {@code stats.getFailureCount()}
   */
  private static int runPatternP2ReadModifyWrite(int threads, int iterations, boolean yieldBetween)
      throws InterruptedException {
    AnalysisStatistics stats = new AnalysisStatistics();

    runConcurrently(threads, iterations, () -> {
      // 실소스: int fc = session.getStatistics().getFailureCount() + 1;
      //         session.getStatistics().setFailureCount(fc);
      int fc = stats.getFailureCount() + 1;
      if (yieldBetween) {
        Thread.yield();
      }
      stats.setFailureCount(fc);
    });

    return stats.getFailureCount();
  }

  /**
   * {@code threads}개 스레드를 {@link CountDownLatch}로 <b>동시에 출발</b>시켜
   * 각 스레드가 {@code iterations}회씩 {@code body}를 실행하게 한다.
   * 동시 출발이 없으면 스레드 생성 지연 때문에 경합이 거의 발생하지 않는다.
   */
  private static void runConcurrently(int threads, int iterations, Runnable body)
      throws InterruptedException {
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch doneGate = new CountDownLatch(threads);
    try {
      for (int t = 0; t < threads; t++) {
        executor.submit(() -> {
          try {
            startGate.await();
            for (int i = 0; i < iterations; i++) {
              body.run();
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            doneGate.countDown();
          }
        });
      }
      startGate.countDown();
      if (!doneGate.await(120, TimeUnit.SECONDS)) {
        throw new IllegalStateException("동시성 하네스가 120초 안에 끝나지 않았다.");
      }
    } finally {
      executor.shutdownNow();
    }
  }

  /** {@link #runPatternP1StaleWrite}/{@link #runPatternP2ReadModifyWrite}의 공통 시그니처. */
  @FunctionalInterface
  private interface ConcurrencyHarness {
    int run(int threads, int iterations, boolean yieldBetween) throws InterruptedException;
  }

  /**
   * 한 설정을 {@link #ROUNDS}회 반복 관측하고 손실률을 요약해 출력한다.
   *
   * @return 손실이 관측된 라운드 수
   */
  private static int observeRounds(String label, int threads, int iterations, boolean yieldBetween,
      ConcurrencyHarness harness) throws InterruptedException {
    int expected = threads * iterations;
    List<Integer> observed = new ArrayList<>();
    long startNanos = System.nanoTime();
    for (int round = 1; round <= ROUNDS; round++) {
      int actual = harness.run(threads, iterations, yieldBetween);
      observed.add(actual);
      System.out.printf(
          "[%s] round=%d/%d threads=%d iterations=%d yield=%s expected=%d actual=%d loss=%d%n",
          label, round, ROUNDS, threads, iterations, yieldBetween, expected, actual,
          expected - actual);
    }
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

    long lossyRounds = observed.stream().filter(v -> v < expected).count();
    double avgLoss = observed.stream().mapToInt(v -> expected - v).average().orElse(0.0);
    int maxLoss = observed.stream().mapToInt(v -> expected - v).max().orElse(0);
    System.out.printf(
        "[%s] SUMMARY rounds=%d lossyRounds=%d lossRate=%.1f%% avgLoss=%.1f maxLoss=%d "
            + "expectedPerRound=%d elapsedMs=%d%n",
        label, ROUNDS, lossyRounds, (lossyRounds * 100.0) / ROUNDS, avgLoss, maxLoss,
        expected, elapsedMs);
    return (int) lossyRounds;
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 관측 케이스 (단언 없음 — work-order TASK-005 작업내용 2)
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * <b>P1(stale write) 최종값 관측</b> — work-order TASK-005 작업내용 4의 확대 절차를 그대로
   * 코드에 담았다: 기본 설정에서 손실이 안 잡히면 {@code Thread.yield()} 삽입 → 반복 수 증가 →
   * 스레드 수 증가 순으로 창을 넓힌다. <b>단언하지 않는다.</b>
   *
   * <p>2026-09-10 실측에서는 <b>어느 단계에서도 최종값 손실이 관측되지 않았다</b>(125라운드 0건).
   * <b>[정정] 이것은 결함이 없다는 뜻이 아니다</b> — 이 케이스는 <b>루프 하네스</b>라 실제
   * {@code runAnalysis()}의 "파일 1개 = 태스크 1개 = 증가→대입 1회"를 모델링하지 못한다.
   * 실경로를 모델링하면 손실이 관측된다(클래스 Javadoc 정정 항목 /
   * {@link #수정전_패턴P1_실경로모델_파일당_증가대입_1회에서_최종값_손실을_관측한다()}).
   *
   * <p>이 케이스를 <b>남겨 두는 이유</b>: "P1 최종값 손실을 회귀 게이트로 쓰지 않는 이유"의 기록이다
   * (work-order v2 TASK-005 작업 내용 4). 억지로 재현을 만들지 않는다.
   */
  @Test
  void 수정전_패턴P1_외부카운터_대입의_최종값_손실을_관측한다() throws InterruptedException {
    System.out.println("=== [TASK-005] 패턴 P1 (stale write: AtomicInteger.incrementAndGet() "
        + "-> setSuccessCount(값)) 최종값 관측 "
        + "— 실소스 runAnalysis() 1482-1483행 / runAnalysisResume() 1759행 ===");
    System.out.println("[env] availableProcessors=" + Runtime.getRuntime().availableProcessors()
        + " java.version=" + System.getProperty("java.version")
        + " os=" + System.getProperty("os.name") + " " + System.getProperty("os.version"));

    // 1단계: 기본 설정(16스레드 × 2,000회)
    int lossy = observeRounds("P1 stale-write 16x2000", THREADS, ITERATIONS_PER_THREAD, false,
        AnalysisStatisticsConcurrencyTest::runPatternP1StaleWrite);

    // 2~4단계: work-order 작업내용 4가 지시한 확대 — yield 삽입 / 반복 수 증가 / 스레드 수 증가
    if (lossy == 0) {
      System.out.println("[P1] 1단계 손실 0 — Thread.yield()를 넣어 창을 넓혀 재시도한다.");
      lossy = observeRounds("P1 stale-write 16x2000 +yield", THREADS, ITERATIONS_PER_THREAD, true,
          AnalysisStatisticsConcurrencyTest::runPatternP1StaleWrite);
    }
    if (lossy == 0) {
      System.out.println("[P1] 2단계 손실 0 — 반복 수를 20,000회로 올려 재시도한다.");
      lossy = observeRounds("P1 stale-write 16x20000 +yield", THREADS, 20_000, true,
          AnalysisStatisticsConcurrencyTest::runPatternP1StaleWrite);
    }
    if (lossy == 0) {
      System.out.println("[P1] 3단계 손실 0 — 스레드 수를 64개(코어 수 초과 → 강제 선점)로 올려 재시도한다.");
      lossy = observeRounds("P1 stale-write 64x2000 +yield", 64, ITERATIONS_PER_THREAD, true,
          AnalysisStatisticsConcurrencyTest::runPatternP1StaleWrite);
    }
    if (lossy == 0) {
      System.out.println("[P1] 4단계 손실 0 — 스레드 수를 256개로 올려 재시도한다.");
      lossy = observeRounds("P1 stale-write 256x2000", 256, ITERATIONS_PER_THREAD, false,
          AnalysisStatisticsConcurrencyTest::runPatternP1StaleWrite);
    }

    System.out.println("[P1] 이 루프 하네스에서의 최종값 손실 관측: "
        + (lossy > 0 ? "관측됨" : "관측되지 않음(0건)")
        + " — 0건이어도 '재현되지 않는다'로 결론내지 않는다(work-order v2 §0.8). "
        + "이 하네스는 스레드당 " + ITERATIONS_PER_THREAD + "회 루프라 실경로"
        + "(파일 1개 = 태스크 1개 = 증가→대입 1회)를 모델링하지 못한다. "
        + "실경로 모델은 [P1 realpath] 케이스, 결함의 직접 증거는 [P1 stale-read] 케이스를 보라.");
  }

  /**
   * <b>P1(stale write)이 실제로 망가뜨리는 것 — 진행 중 노출값</b>.
   * 운영의 {@code /api/analysis/status} 폴링에 해당하는 <b>관측자 스레드</b>를 하나 붙여
   * {@code stats.getSuccessCount()}를 계속 읽으면서 <b>값이 뒤로 가는 횟수</b>를 센다.
   * 값이 뒤로 간다는 것은 낮은 값을 들고 있던 스레드가 큰 값을 덮어썼다는 뜻이며,
   * 이것이 stale write의 직접 증거다. <b>단언하지 않는다.</b>
   *
   * <p>이 케이스가 위 최종값 케이스와 나뉘어 있는 이유: 최종값은 "최댓값을 받은 스레드가 곧바로
   * 대입한다"는 구조 덕분에 대개 살아남지만, <b>진행 중 값은 살아남지 않는다</b>. 사용자가 보는
   * 진행률·성공 건수는 후자다.
   */
  @Test
  void 수정전_패턴P1_폴링_관측자에게_값이_뒤로_가는_stale_read가_관측된다() throws InterruptedException {
    System.out.println("=== [TASK-005] 패턴 P1 stale read 관측 "
        + "(폴링 관측자 = 운영의 /api/analysis/status 역할) ===");

    for (int round = 1; round <= ROUNDS; round++) {
      AnalysisStatistics stats = new AnalysisStatistics();
      AtomicInteger externalCounter = new AtomicInteger(0);
      AtomicInteger polls = new AtomicInteger(0);
      AtomicInteger staleReads = new AtomicInteger(0);
      AtomicInteger maxBacktrack = new AtomicInteger(0);
      AtomicBoolean observing = new AtomicBoolean(true);

      Thread observer = new Thread(() -> {
        int previous = 0;
        while (observing.get()) {
          int current = stats.getSuccessCount();
          polls.incrementAndGet();
          if (current < previous) {
            staleReads.incrementAndGet();
            maxBacktrack.accumulateAndGet(previous - current, Math::max);
          }
          previous = current;
        }
      }, "stats-poller");
      observer.setDaemon(true);
      observer.start();

      long startNanos = System.nanoTime();
      runConcurrently(THREADS, ITERATIONS_PER_THREAD, () -> {
        int sc = externalCounter.incrementAndGet();
        stats.setSuccessCount(sc);
      });
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

      observing.set(false);
      observer.join(5_000);

      // elapsedMs는 TASK-006 DoD 6(락 경합 리스크)의 수정 전 비교값이다 —
      // 수정 후 같은 구조의 케이스는 [TASK-006 stale-read]에서 잰다.
      System.out.printf(
          "[P1 stale-read] round=%d/%d external=%d finalStats=%d polls=%d "
              + "staleReads=%d maxBacktrack=%d elapsedMs=%d%n",
          round, ROUNDS, externalCounter.get(), stats.getSuccessCount(), polls.get(),
          staleReads.get(), maxBacktrack.get(), elapsedMs);
    }
    System.out.println("[P1 stale-read] staleReads > 0 인 라운드가 있으면 stale write가 실재한다는 직접 증거다 "
        + "(폴링 값이 뒤로 갔다는 뜻).");
  }

  /**
   * <b>P2(read-modify-write) 관측</b> — {@code getFailureCount() + 1} → {@code setFailureCount(값)}
   * 패턴에서 최종값이 32,000에 못 미치는 것을 관측한다. <b>단언하지 않는다.</b>
   */
  @Test
  void 수정전_패턴P2_읽고_더해서_되쓰기는_손실이_관측된다() throws InterruptedException {
    System.out.println("=== [TASK-005] 패턴 P2 (read-modify-write: getFailureCount()+1 "
        + "-> setFailureCount(값)) 관측 "
        + "— 실소스 runAnalysis() 1495-1496행 / runAnalysisResume() 1768행 ===");

    int lossy = observeRounds("P2 read-modify-write 16x2000", THREADS, ITERATIONS_PER_THREAD, false,
        AnalysisStatisticsConcurrencyTest::runPatternP2ReadModifyWrite);
    if (lossy == 0) {
      System.out.println("[P2] 1단계 손실 0 — Thread.yield()를 넣어 창을 넓혀 재시도한다.");
      lossy = observeRounds("P2 read-modify-write 16x2000 +yield", THREADS, ITERATIONS_PER_THREAD,
          true, AnalysisStatisticsConcurrencyTest::runPatternP2ReadModifyWrite);
    }
    System.out.println("[P2] 최종 판정: 손실 관측 " + (lossy > 0 ? "성공" : "실패(0건)")
        + " (기대값 " + EXPECTED_TOTAL + ")");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 작업 내용 7 — 실경로 모델 관측 케이스 (단언 금지, 손실 0도 정상)
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * <b>P1 실경로 모델 관측</b> — 위 루프 하네스가 비켜간 지점을 겨냥한다.
   *
   * <p><b>모델</b>(메인 세션 프로브와 동일, {@code src/test/resources/concurrency/README.txt} 2절):
   * <ul>
   *   <li><b>파일 N개 = 태스크 N개</b>, N ∈ {@code {2, 5, 8}}. 루프가 아니다.</li>
   *   <li>각 태스크는 <b>증가→대입 1회</b>만 한다:
   *       {@code int sc = ext.incrementAndGet(); Thread.onSpinWait(); stats.setSuccessCount(sc);}</li>
   *   <li>{@code Thread.onSpinWait()} 1회는 실소스에서 증가와 대입 사이에 있는
   *       {@code session.getStatistics()} 호출 등 <b>실제 작업</b>을 흉내낸 것이다. 프로브 실측에서
   *       이 한 줄이 손실률과 손실 규모를 함께 키웠다.</li>
   *   <li>{@link CountDownLatch}로 동시 출발 → 전부 끝난 뒤 최종값을 읽는다.
   *       최종값 != N 이면 손실 1회로 센다.</li>
   * </ul>
   *
   * <p><b>프로브와 다른 점(의도적 개선)</b>: 프로브는 static {@code int} 필드를 썼지만
   * 이 케이스는 <b>실클래스 {@link AnalysisStatistics}</b>를 쓴다. 프로브가 남긴 한계
   * ("실클래스로 재현한 것은 아니다", work-order v2 §0.5.1 (4))를 이 케이스가 메운다.
   *
   * <p><b>이 케이스는 단언하지 않는다. 손실 0이어도 Fail이 아니다.</b>
   * 실측 발생률이 <b>0.002~0.012%</b>라 {@link #REALPATH_TRIALS_PER_FILE_COUNT}(=20,000) 규모에서
   * 0이 나오는 것은 정상이다(프로브는 설정당 100,000회를 썼다).
   *
   * <p><b>이 케이스의 목적은 회귀 게이트가 아니다</b> — <b>"후속 세션이 시행 수만 올려
   * 재확인할 수 있는 재현 장치"</b>를 저장소 안에 남기는 것이다(work-order v2 TASK-005 작업 내용 7,
   * §0.7 T3 제외 판단의 대체물). 재확인하려면 {@link #REALPATH_TRIALS_PER_FILE_COUNT}
   * <b>상수 하나만</b> 올리면 된다.
   *
   * <p>함께 하는 일: 작업 내용 6으로 보존한 프로브 근거 파일이 클래스패스에 실제로 있는지 확인하고
   * README의 결과 표를 출력에 실어 둔다. <b>이 확인의 실패는 손실 관측에 대한 단언이 아니라
   * "근거 파일이 사라졌다"는 신호다</b> — 조용히 통과시키면 근거 보존이 무력해진다(§0.6).
   */
  @Test
  void 수정전_패턴P1_실경로모델_파일당_증가대입_1회에서_최종값_손실을_관측한다() throws Exception {
    System.out.println("=== [TASK-005 작업7] 패턴 P1 실경로 모델 관측 "
        + "(파일 N개 = 태스크 N개 = 증가→대입 1회, 루프 아님) "
        + "— 실소스 runAnalysis() 1482-1483행 / runAnalysisResume() 1759행 ===");
    System.out.println("[P1 realpath] 단언 없음 — 손실 0도 정상이다(실측 발생률 0.002~0.012%). "
        + "재확인하려면 REALPATH_TRIALS_PER_FILE_COUNT 상수만 올려라 (현재 "
        + REALPATH_TRIALS_PER_FILE_COUNT + ").");

    printPreservedProbeEvidence();

    long totalStartNanos = System.nanoTime();
    int totalLossy = 0;
    for (int files : REALPATH_FILE_COUNTS) {
      totalLossy += observeRealPathModel(files, REALPATH_TRIALS_PER_FILE_COUNT);
    }
    long totalElapsedMs = (System.nanoTime() - totalStartNanos) / 1_000_000L;

    System.out.printf("[P1 realpath] TOTAL fileCounts=%s trialsPerFileCount=%d "
            + "lossyTrialsTotal=%d elapsedMs=%d%n",
        java.util.Arrays.toString(REALPATH_FILE_COUNTS), REALPATH_TRIALS_PER_FILE_COUNT,
        totalLossy, totalElapsedMs);
    System.out.println("[P1 realpath] 이 실행에서의 관측 결과: "
        + (totalLossy > 0 ? "손실 관측됨 — 실경로에서 P1 최종값 손실이 실재한다는 직접 증거다."
            : "이 시행 수에서는 손실이 관측되지 않았다(정상). "
                + "0건을 '재현되지 않는다'로 넓히지 말 것 — work-order v2 §0.8. "
                + "보존된 프로브 근거(위 [probe-evidence])가 10만 시행에서의 관측을 기록하고 있다."));
  }

  /**
   * 실경로 모델 1개 설정({@code files}개 태스크)을 {@code trials}회 반복 관측한다.
   * 스레드 풀은 시행마다 새로 만들지 않고 <b>한 번만 만들어 재사용</b>한다 — 풀 생성 비용이
   * 시행당 지배적이 되면 정작 재려는 경합 창이 묻힌다(프로브도 같은 방식이다).
   *
   * @return 손실이 발생한 시행 수
   */
  private static int observeRealPathModel(int files, int trials) throws InterruptedException {
    ExecutorService executor = Executors.newFixedThreadPool(Math.min(files, THREADS));
    int lossyTrials = 0;
    int worstLoss = 0;
    long startNanos = System.nanoTime();
    try {
      for (int trial = 0; trial < trials; trial++) {
        AnalysisStatistics stats = new AnalysisStatistics();
        AtomicInteger externalCounter = new AtomicInteger(0);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(files);

        for (int i = 0; i < files; i++) {
          executor.submit(() -> {
            try {
              startGate.await();
              // 실소스: int sc = successCount.incrementAndGet();
              //         session.getStatistics().setSuccessCount(sc);
              int sc = externalCounter.incrementAndGet();
              // 증가와 대입 사이의 실제 작업(session.getStatistics() 호출 등)을 흉내낸다.
              Thread.onSpinWait();
              stats.setSuccessCount(sc);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              doneGate.countDown();
            }
          });
        }
        startGate.countDown();
        if (!doneGate.await(30, TimeUnit.SECONDS)) {
          throw new IllegalStateException("실경로 모델 시행이 30초 안에 끝나지 않았다: files=" + files);
        }

        int finalValue = stats.getSuccessCount();
        if (finalValue != files) {
          lossyTrials++;
          worstLoss = Math.max(worstLoss, files - finalValue);
        }
      }
    } finally {
      executor.shutdownNow();
    }
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

    System.out.printf("[P1 realpath] files=%-3d trials=%-7d 손실=%d회 (%.4f%%) 최대손실=%d "
            + "elapsedMs=%d%n",
        files, trials, lossyTrials, (100.0 * lossyTrials) / trials, worstLoss, elapsedMs);
    return lossyTrials;
  }

  /**
   * 작업 내용 6으로 보존한 프로브 근거가 클래스패스에 실제로 존재하는지 확인하고,
   * README의 실행 결과 표를 출력에 실어 둔다(테스트 리포트만 봐도 근거가 보이도록).
   * 파일이 없으면 <b>조용히 넘어가지 않고 즉시 실패</b>시킨다.
   */
  private static void printPreservedProbeEvidence() throws IOException {
    for (String resource : PROBE_EVIDENCE_SOURCES) {
      byte[] bytes = readClasspathResourceOrFail(resource);
      System.out.println("[probe-evidence] " + resource + " 보존 확인 (" + bytes.length + " bytes)");
    }

    String readme = new String(readClasspathResourceOrFail(PROBE_EVIDENCE_README),
        StandardCharsets.UTF_8);
    System.out.println("[probe-evidence] " + PROBE_EVIDENCE_README + " 보존 확인 ("
        + readme.length() + " chars) — 메인 세션 프로브 실행 결과 원문(10만 시행):");
    for (String line : readme.replace("\r\n", "\n").split("\n", -1)) {
      if (line.startsWith("[간격없음]") || line.startsWith("[증가-대입 사이 작업]")) {
        System.out.println("[probe-evidence]   " + line);
      }
    }
  }

  // ────────────────────────────────────────────────────────────────────────────
  // TASK-006 본 단언 케이스 (수정 후 — 여기서부터는 단언한다)
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * <b>(6-a) 수정 후 최종값 단언</b> — 16스레드 × 2,000회로 신규 증가 메서드
   * ({@code incrementSuccessCount()} / {@code incrementSkipCount()} /
   * {@code incrementFailureCount()})를 호출하고, 세 카운터 최종값이 각각 <b>정확히 32,000</b>인지
   * 단언한다(work-order v2 TASK-006 DoD 1-(c)).
   *
   * <p><b>최종 읽기는 증가에 참여하지 않은 다른 스레드에서 한 번 더</b> 수행한다. 증가만 원자화하고
   * getter가 동기화 도메인 밖이면 "쓴 스레드에서는 맞는데 읽는 스레드에서는 틀린" 상태가 되는데,
   * 그건 운영에서 폴링하는 쪽이 겪는 상황이다. 그래서 <b>가시성까지</b> 확인한다.
   *
   * <p>같은 실행 안에 있는 수정 전 대조군: {@code [P2 ...]}(최종값 손실 100%),
   * {@code [P1 stale-read]}(폴링 되감김). 그쪽이 손실을 보이고 이쪽이 0이어야 대조가 성립한다.
   */
  @Test
  void 수정후_증가메서드는_16스레드_2000회에서_세_카운터_모두_정확히_32000이다() throws Exception {
    System.out.println("=== [TASK-006] 수정 후 최종값 단언 "
        + "(incrementSuccessCount / incrementSkipCount / incrementFailureCount) ===");

    AnalysisStatistics stats = new AnalysisStatistics();
    long startNanos = System.nanoTime();
    runConcurrently(THREADS, ITERATIONS_PER_THREAD, () -> {
      stats.incrementSuccessCount();
      stats.incrementSkipCount();
      stats.incrementFailureCount();
    });
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

    // 증가에 참여하지 않은 별도 스레드에서 최종값을 다시 읽는다(가시성 확인).
    int[] readByOtherThread = new int[3];
    Thread reader = new Thread(() -> {
      readByOtherThread[0] = stats.getSuccessCount();
      readByOtherThread[1] = stats.getSkipCount();
      readByOtherThread[2] = stats.getFailureCount();
    }, "stats-final-reader");
    reader.start();
    reader.join(10_000);

    System.out.printf("[TASK-006 final-value] threads=%d iterations=%d expected=%d "
            + "success=%d skip=%d failure=%d (증가 미참여 스레드 읽기) elapsedMs=%d%n",
        THREADS, ITERATIONS_PER_THREAD, EXPECTED_TOTAL,
        readByOtherThread[0], readByOtherThread[1], readByOtherThread[2], elapsedMs);

    assertEquals(EXPECTED_TOTAL, readByOtherThread[0],
        "successCount가 정확히 " + EXPECTED_TOTAL + "이어야 한다. 어긋나면 incrementSuccessCount()의 "
            + "원자성이나 getSuccessCount()의 가시성이 깨진 것이다.");
    assertEquals(EXPECTED_TOTAL, readByOtherThread[1],
        "skipCount가 정확히 " + EXPECTED_TOTAL + "이어야 한다.");
    assertEquals(EXPECTED_TOTAL, readByOtherThread[2],
        "failureCount가 정확히 " + EXPECTED_TOTAL + "이어야 한다.");
  }

  /**
   * <b>(6-b) 수정 후 폴링 stale read 단언</b> — 수정 전
   * {@link #수정전_패턴P1_폴링_관측자에게_값이_뒤로_가는_stale_read가_관측된다()}와
   * <b>같은 구조</b>(폴링 관측자 스레드가 {@code getSuccessCount()}를 계속 읽으며 값이 뒤로 가는
   * 횟수를 센다)를 신규 증가 메서드 버전으로 한 벌 두고,
   * <b>{@link #ROUNDS}라운드 누적 backtrack == 0</b>을 단언한다(work-order v2 TASK-006 DoD 1-(d)).
   *
   * <p><b>폴링은 반드시 증가 메서드와 같은 동기화 도메인에 속한 getter를 쓴다</b> —
   * 필드 직접 접근이나 별도 비동기화 경로로 읽으면 이 단언은 아무것도 증명하지 못한다.
   *
   * <p><b>이 단언은 확률적이지 않다.</b> 증가와 읽기가 같은 락으로 선형화되면 폴링 스레드가 읽는
   * 값은 <b>단조 비감소</b>가 보장된다. 따라서 0은 운이 좋아서 나온 값이 아니며,
   * <b>1건이라도 관측되면 수정이 불완전하다는 신호</b>다(대표적으로 getter가 동기화 도메인 밖인 경우).
   *
   * <p><b>소요시간을 별도로 기록한다</b>(work-order v2 TASK-006 DoD 6): getter까지
   * {@code synchronized}가 되면 폴링 경로도 락을 타므로, 락 경합 리스크(설계 §9 R3) 판단 근거로
   * 폴링 관측자가 붙은 경우의 수치를 남긴다. 운영 폴링은 초당 1회 수준이고 이 하네스의 관측자는
   * <b>쉬지 않고 도는 최악 조건</b>이라는 점을 함께 봐야 한다.
   */
  @Test
  void 수정후_증가메서드는_폴링_관측자에게_값이_뒤로_가지_않는다() throws InterruptedException {
    System.out.println("=== [TASK-006] 수정 후 폴링 stale read 단언 "
        + "(수정 전 [P1 stale-read] 케이스와 같은 구조, 증가 메서드 버전) ===");

    int totalBacktracks = 0;
    long totalPolls = 0;
    long totalElapsedMs = 0;
    for (int round = 1; round <= ROUNDS; round++) {
      AnalysisStatistics stats = new AnalysisStatistics();
      AtomicInteger polls = new AtomicInteger(0);
      AtomicInteger backtracks = new AtomicInteger(0);
      AtomicInteger maxBacktrack = new AtomicInteger(0);
      AtomicBoolean observing = new AtomicBoolean(true);

      Thread observer = new Thread(() -> {
        int previous = 0;
        while (observing.get()) {
          // 반드시 증가 메서드와 같은 동기화 도메인의 getter로 읽는다.
          int current = stats.getSuccessCount();
          polls.incrementAndGet();
          if (current < previous) {
            backtracks.incrementAndGet();
            maxBacktrack.accumulateAndGet(previous - current, Math::max);
          }
          previous = current;
        }
      }, "stats-poller-after-fix");
      observer.setDaemon(true);
      observer.start();

      long startNanos = System.nanoTime();
      runConcurrently(THREADS, ITERATIONS_PER_THREAD, stats::incrementSuccessCount);
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

      observing.set(false);
      observer.join(5_000);

      totalBacktracks += backtracks.get();
      totalPolls += polls.get();
      totalElapsedMs += elapsedMs;

      System.out.printf("[TASK-006 stale-read] round=%d/%d finalStats=%d polls=%d "
              + "backtracks=%d maxBacktrack=%d elapsedMs=%d%n",
          round, ROUNDS, stats.getSuccessCount(), polls.get(), backtracks.get(),
          maxBacktrack.get(), elapsedMs);

      assertEquals(EXPECTED_TOTAL, stats.getSuccessCount(),
          "라운드 " + round + "의 최종값도 정확히 " + EXPECTED_TOTAL + "이어야 한다.");
    }

    System.out.printf("[TASK-006 stale-read] SUMMARY rounds=%d 누적backtracks=%d 누적polls=%d "
            + "누적elapsedMs=%d (DoD 6 — 폴링 관측자가 붙은 케이스의 소요시간)%n",
        ROUNDS, totalBacktracks, totalPolls, totalElapsedMs);

    assertEquals(0, totalBacktracks,
        "수정 후에는 " + ROUNDS + "라운드 누적 backtrack이 0이어야 한다. 증가(incrementSuccessCount)와 "
            + "읽기(getSuccessCount)가 같은 락으로 선형화되면 폴링 값은 단조 비감소가 보장되므로, "
            + "1건이라도 관측됐다면 수정이 불완전하다(예: getter가 동기화 도메인 밖). "
            + "관측된 누적 backtrack=" + totalBacktracks);
  }

  /** 클래스패스 리소스를 읽는다. 없으면 근거 보존이 깨진 것이므로 즉시 실패시킨다. */
  private static byte[] readClasspathResourceOrFail(String resource) throws IOException {
    try (InputStream in = AnalysisStatisticsConcurrencyTest.class.getResourceAsStream(resource)) {
      if (in == null) {
        return fail("TASK-005 작업 내용 6으로 보존한 프로브 근거가 클래스패스에 없다: " + resource
            + " — 이 파일이 사라지면 P1 최종값 손실의 실증 근거가 저장소에 남지 않는다"
            + "(work-order v2 §0.6). 지우지 말 것.");
      }
      return in.readAllBytes();
    }
  }
}
