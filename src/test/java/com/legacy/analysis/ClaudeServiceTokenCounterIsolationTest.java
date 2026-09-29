package com.legacy.analysis;

import com.legacy.analysis.llm.LlmResult;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-002 (work-order 2026-09-cost-stats-bugfix v2) —
 * <b>{@code ClaudeServiceImpl} 토큰 카운터가 세션별로 격리됨을 고정하는 회귀 테스트</b>.
 * 프로덕션 코드 변경은 이 파일과 무관하며(0줄), 세 케이스는 REQ-001이 해소했다고 주장하는
 * 바로 그 사실을 실행으로 단언한다.
 *
 * <p><b>이 파일의 이력 — 수정 전 양성 대조군이었다</b>(work-order v2 §0.7-2 / RG-7).
 * 이 클래스는 커밋 {@code 81b5b58}(TASK-001) 시점에 <b>정반대</b>를 단언하는 양성 대조군이었다 —
 * 즉 "수정 전 코드에서는 세션 간 혼입이 실제로 발생한다"를 고정했다. 그 시점의 전문은
 * {@code git show 81b5b58:src/test/java/com/legacy/analysis/ClaudeServiceTokenCounterIsolationTest.java}
 * 로 언제든 복원할 수 있고, 당시 실측 수치(예: 세션 A 기대 1,000 / 실측 1,700)는
 * {@code 05-dev-progress.md}의 TASK-001 항목 대조표에 있다. TASK-002가 work-order v2 §0.6의
 * 화이트리스트(W1~W7) 범위에서 기대값을 뒤집고 세션 키를 전달하도록 전환했으며,
 * <b>측정 모양(세션 키 문자열, 토큰 조합, 스레드 수, 반복 수, 협력자 {@code null} 생성 관례,
 * 리플렉션 접근 방식)은 한 글자도 바꾸지 않았다</b> — 그래야 수정 전/후 대조가 성립한다(G-04).
 *
 * <p><b>무엇이 결함이었나</b>: {@code ClaudeServiceImpl}은 {@code @Service} 싱글턴 빈인데
 * 토큰 카운터 5개(입력·출력·캐시읽기·캐시생성 + {@code lastModelName})가 <b>인스턴스 필드 =
 * 전역 통 하나</b>였다. 누적 지점 {@code extractAndStoreTokenUsage}와 조회/리셋
 * ({@code getTotalTokenUsage} / {@code resetTokenUsage}) 전부 세션 키를 받지 않아,
 * 동시에 분석 중인 두 세션의 토큰을 구분할 방법이 구조적으로 없었다. 이제 세 API 모두
 * {@code sourceFolderPath}를 받고 카운터는 그 키로 격리된다.
 *
 * <p><b>고정하는 세 가지</b>(설계 {@code 02-design-v1} §1.1 표의 두 증상 + 동시 누적):
 * <ul>
 *   <li><b>(i) 남의 리셋이 내 누적치를 지우지 않는다</b> — 세션 A가 누적 중인 상태에서 세션 B가
 *       {@code runAnalysis()}를 시작해 {@code resetTokenUsage(B키)}를 호출해도 A는 그대로다.
 *       실소스: {@code MainApiController.runAnalysis()}의 {@code resetTokenUsage()} 호출
 *       (전 저장소에 단 1곳). 수정 전에는 A의 4개 값이 전부 0이 됐다.</li>
 *   <li><b>(ii) 남의 토큰이 내 조회에 합산되지 않는다</b> — A·B가 각각 누적한 뒤 각자
 *       {@code getTotalTokenUsage(자기 키)}를 읽으면 <b>자기 토큰만</b> 나온다.
 *       {@code modelName}(5번째 격리 대상 필드)도 각자 자기 모델이다. 이 값이 그대로
 *       {@code MainApiController.finalizeAnalysis()}를 거쳐 {@code AnalysisHistory}의
 *       {@code input/output/total_tokens}·{@code estimated_cost}에 기록되고, 그 행을 읽는
 *       통계 API까지 전파되므로(F12) 여기가 정확해지면 통계도 자동으로 정확해진다.</li>
 *   <li><b>(iii) 동시 누적에서도 각 세션이 자기 합계만 갖는다</b> — 두 세션이 스레드 16개로
 *       동시에 누적해도 각 키가 정확히 자기 몫을 갖고, 두 키의 합이 전체와 일치한다
 *       (원자적 누적이라 손실도 없다).</li>
 * </ul>
 *
 * <p><b>흉내낸 클래스가 아니라 실제 프로덕션 클래스를 쓴다</b>(work-order TASK-001 작업내용 3,
 * v2 §0.6 금지 목록으로 고정). 협력자를 {@code null}로 넘겨 {@code ClaudeServiceImpl}을 직접
 * 생성하는 기존 관례({@code ClaudeServiceImplNormalizeCommentTest})를 재사용하고, private 누적
 * 메서드는 리플렉션으로 직접 호출한다. 누적 경로에 협력자가 관여하지 않으므로 {@code null}이어도
 * 안전하다.
 *
 * <p><b>왜 단언해도 되는가</b>: 세 케이스 모두 <b>스케줄링과 무관하게 결정론적</b>이다.
 * 리셋은 {@code AtomicLong.set(0)} 단일 대입이고 누적은 {@code AtomicLong.addAndGet()}이라
 * 손실이 없다 — (iii)의 동시 누적에서도 총합이 항상 정확하다. 확률적 손실을 단언하는 항목이
 * 없으므로 flaky하지 않다.
 *
 * <p><b>관측값 출력 위치</b>: {@code build.gradle}의 {@code showStandardStreams = false} 때문에
 * 콘솔에는 나오지 않고
 * {@code build/test-results/test/TEST-com.legacy.analysis.ClaudeServiceTokenCounterIsolationTest.xml}의
 * {@code <system-out>}에 남는다. 수정 전/후 수치는 {@code 05-dev-progress.md}에 있다.
 *
 * <p><b>TASK-004와의 관계</b>: TASK-004는 이 파일을 <b>다시 고치지 않고</b>(0행 수정) 여기 관측값으로
 * 대조표를 채우고, 하네스가 덮지 못하는 범위(세션 3개 이상, 누적 중 동시 교차 리셋)를 별도 파일에
 * 추가한다(v2 §0.6 판정 4).
 */
class ClaudeServiceTokenCounterIsolationTest {

  /**
   * 세션 격리 키 관례({@code sourceFolderPath}) 상의 서로 다른 두 세션.
   * <b>수정 전 코드는 이 값을 어디에도 쓰지 않았다</b> — 누적/조회/리셋 API가 세션 키를
   * 받는 자리가 없었기 때문이며, 그것이 바로 B1의 원인이었다. 값 자체는 {@code 81b5b58}의
   * 대조군과 동일하게 유지한다(측정 모양 불변 — work-order v2 §0.6 금지 목록).
   */
  private static final String SESSION_A = "C:\\work\\project-a";
  private static final String SESSION_B = "C:\\work\\project-b";

  private static final String MODEL_A = "claude-sonnet-4-6";
  private static final String MODEL_B = "claude-haiku-4-5-20251001";

  /** 세션 A가 쓴 토큰 (input / output / cacheRead / cacheCreation). */
  private static final long A_IN = 1000L, A_OUT = 200L, A_CACHE_READ = 50L, A_CACHE_CREATE = 30L;
  /** 세션 B가 쓴 토큰. */
  private static final long B_IN = 700L, B_OUT = 100L, B_CACHE_READ = 20L, B_CACHE_CREATE = 10L;

  /**
   * 운영 기본값 {@code app.analysis.thread-pool-size=16}과 같은 스레드 수.
   * {@code AnalysisStatisticsConcurrencyTest}가 이 값을 근거로 쓴 선례를 따른다.
   */
  private static final int THREADS = 16;

  private static ClaudeServiceImpl newService() {
    return new ClaudeServiceImpl(null, null, null, null, null, null, null);
  }

  /** 실제 누적 지점을 리플렉션으로 직접 호출한다(전환 후 시그니처 = 세션 키를 받는다). */
  private static void accumulate(ClaudeServiceImpl service, long in, long out,
      long cacheRead, long cacheCreate, String model, String sourceFolderPath) throws Exception {
    Method method = ClaudeServiceImpl.class.getDeclaredMethod(
        "extractAndStoreTokenUsage", LlmResult.class, String.class, String.class);
    method.setAccessible(true);
    method.invoke(service, new LlmResult("dummy-response", in, out, cacheRead, cacheCreate),
        model, sourceFolderPath);
  }

  @Test
  void 세션B의_리셋은_세션A의_누적치를_건드리지_않는다() throws Exception {
    ClaudeServiceImpl service = newService();

    // 세션 A가 runAnalysis()를 시작하고 파일 하나를 분석해 토큰을 누적한 상태
    service.resetTokenUsage(SESSION_A);
    accumulate(service, A_IN, A_OUT, A_CACHE_READ, A_CACHE_CREATE, MODEL_A, SESSION_A);
    long aBeforeOtherSessionStarts = service.getTotalTokenUsage(SESSION_A).getInputTokens();

    // 세션 B가 runAnalysis()를 시작한다 → 그 경로의 resetTokenUsage()는 B의 카운터만 건드린다
    service.resetTokenUsage(SESSION_B);

    TokenUsage aAfter = service.getTotalTokenUsage(SESSION_A);
    System.out.printf(
        "[TASK-002 (i) 리셋 격리] 세션A input 기대 %d / 실측 %d, output 기대 %d / 실측 %d, "
            + "cacheRead 기대 %d / 실측 %d, cacheCreation 기대 %d / 실측 %d%n",
        A_IN, aAfter.getInputTokens(), A_OUT, aAfter.getOutputTokens(),
        A_CACHE_READ, aAfter.getCacheReadTokens(), A_CACHE_CREATE, aAfter.getCacheCreationTokens());

    // 리셋 전 대조점 — 측정 자체가 동작함을 보인다(수정 전에도 이 단언은 통과했다)
    assertEquals(A_IN, aBeforeOtherSessionStarts);
    // 격리 후: 남의 세션이 리셋해도 A의 4개 값이 모두 그대로다(수정 전에는 전부 0이 됐다)
    assertEquals(A_IN, aAfter.getInputTokens());
    assertEquals(A_OUT, aAfter.getOutputTokens());
    assertEquals(A_CACHE_READ, aAfter.getCacheReadTokens());
    assertEquals(A_CACHE_CREATE, aAfter.getCacheCreationTokens());
  }

  @Test
  void 세션A의_조회에_세션B의_토큰이_합산되지_않는다() throws Exception {
    ClaudeServiceImpl service = newService();
    service.resetTokenUsage(SESSION_A);
    service.resetTokenUsage(SESSION_B);

    accumulate(service, A_IN, A_OUT, A_CACHE_READ, A_CACHE_CREATE, MODEL_A, SESSION_A);
    accumulate(service, B_IN, B_OUT, B_CACHE_READ, B_CACHE_CREATE, MODEL_B, SESSION_B);

    TokenUsage asSeenByA = service.getTotalTokenUsage(SESSION_A);
    System.out.printf(
        "[TASK-002 (ii) 합산 격리] 세션A input 기대 %d / 실측 %d, output 기대 %d / 실측 %d, "
            + "cacheRead 기대 %d / 실측 %d, cacheCreation 기대 %d / 실측 %d, "
            + "modelName 기대 %s / 실측 %s%n",
        A_IN, asSeenByA.getInputTokens(), A_OUT, asSeenByA.getOutputTokens(),
        A_CACHE_READ, asSeenByA.getCacheReadTokens(),
        A_CACHE_CREATE, asSeenByA.getCacheCreationTokens(),
        MODEL_A, asSeenByA.getModelName());

    // 격리 후: A는 자기 토큰만 갖는다(수정 전에는 B의 값이 그대로 더해져 있었다)
    assertEquals(A_IN, asSeenByA.getInputTokens());
    assertEquals(A_OUT, asSeenByA.getOutputTokens());
    assertEquals(A_CACHE_READ, asSeenByA.getCacheReadTokens());
    assertEquals(A_CACHE_CREATE, asSeenByA.getCacheCreationTokens());
    // lastModelName도 격리 대상 5번째 필드다(설계 §0.4-1) — 수정 전에는 나중에 누적한 세션 B의
    // 모델명이 A의 조회 결과를 덮어썼다.
    assertEquals(MODEL_A, asSeenByA.getModelName());

    // B쪽도 함께 단언한다 — A만 보면 "양쪽 다 0"인 고장도 통과해 버린다(work-order v2 §0.6 W4 보강).
    TokenUsage asSeenByB = service.getTotalTokenUsage(SESSION_B);
    System.out.printf(
        "[TASK-002 (ii) 합산 격리] 세션B input 기대 %d / 실측 %d, output 기대 %d / 실측 %d, "
            + "cacheRead 기대 %d / 실측 %d, cacheCreation 기대 %d / 실측 %d, "
            + "modelName 기대 %s / 실측 %s%n",
        B_IN, asSeenByB.getInputTokens(), B_OUT, asSeenByB.getOutputTokens(),
        B_CACHE_READ, asSeenByB.getCacheReadTokens(),
        B_CACHE_CREATE, asSeenByB.getCacheCreationTokens(),
        MODEL_B, asSeenByB.getModelName());

    assertEquals(B_IN, asSeenByB.getInputTokens());
    assertEquals(B_OUT, asSeenByB.getOutputTokens());
    assertEquals(B_CACHE_READ, asSeenByB.getCacheReadTokens());
    assertEquals(B_CACHE_CREATE, asSeenByB.getCacheCreationTokens());
    assertEquals(MODEL_B, asSeenByB.getModelName());
  }

  @Test
  void 두_세션이_동시에_누적해도_각_세션이_자기_합계만_갖는다() throws Exception {
    ClaudeServiceImpl service = newService();
    service.resetTokenUsage(SESSION_A);
    service.resetTokenUsage(SESSION_B);

    long perCall = 100L;
    int callsPerThread = 50;
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(THREADS);
    try {
      for (int i = 0; i < THREADS; i++) {
        // 절반은 세션 A, 절반은 세션 B로 동시에 분석하는 상황 — 각 스레드는 자기 세션 키로 누적한다
        boolean isSessionA = (i % 2 == 0);
        String model = isSessionA ? MODEL_A : MODEL_B;
        String sessionKey = isSessionA ? SESSION_A : SESSION_B;
        pool.submit(() -> {
          try {
            start.await();
            for (int c = 0; c < callsPerThread; c++) {
              accumulate(service, perCall, 0L, 0L, 0L, model, sessionKey);
            }
          } catch (Exception e) {
            throw new IllegalStateException(e);
          } finally {
            done.countDown();
          }
        });
      }
      start.countDown();
      assertTrue(done.await(30, TimeUnit.SECONDS), "동시 누적이 30초 안에 끝나야 한다");
    } finally {
      pool.shutdownNow();
    }

    long perSessionExpected = (long) (THREADS / 2) * callsPerThread * perCall;
    long globalExpected = (long) THREADS * callsPerThread * perCall;
    TokenUsage observedA = service.getTotalTokenUsage(SESSION_A);
    TokenUsage observedB = service.getTotalTokenUsage(SESSION_B);
    System.out.printf(
        "[TASK-002 (iii) 동시 누적] 스레드 %d(세션 2개 × 8) × %d회 × %d토큰 / "
            + "세션당 기대 %d / 세션A 실측 %d / 세션B 실측 %d / 합계 기대 %d / 합계 실측 %d%n",
        THREADS, callsPerThread, perCall, perSessionExpected,
        observedA.getInputTokens(), observedB.getInputTokens(),
        globalExpected, observedA.getInputTokens() + observedB.getInputTokens());

    // 격리 후: 각 키가 정확히 자기 몫만 갖는다(수정 전에는 전역 조회가 정확히 두 배를 반환했다).
    assertEquals(perSessionExpected, observedA.getInputTokens());
    assertEquals(perSessionExpected, observedB.getInputTokens());
    // 두 키의 합이 전체와 일치한다 — AtomicLong.addAndGet()이라 손실이 없다는 사실도 함께 고정한다
    // (수정 전 관측값 80,000이라는 수치의 의미를 버리지 않는다).
    assertEquals(globalExpected, observedA.getInputTokens() + observedB.getInputTokens());
  }
}
