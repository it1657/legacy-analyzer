package com.legacy.analysis;

import com.legacy.analysis.llm.LlmResult;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TASK-003 (work-order 2026-09-cost-stats-bugfix v2) —
 * <b>일시정지 → 재개 세션의 토큰/비용 시맨틱을 "이어서 전체 합계"로 고정한다</b>(게이트1 확정 D1).
 * 프로덕션 코드 변경 0행이며 이 클래스는 검증 전용이다.
 *
 * <h2>왜 "이어서 누적"인가 — 반대로 바꾸면 무엇이 깨지는가</h2>
 *
 * <p>재개 경로({@code MainApiController.runAnalysisResume()})에는 {@code resetTokenUsage()}를
 * <b>의도적으로 넣지 않았다</b>. 근거는 다음 두 가지이며, 이 테스트가 그 결정을 숫자로 못박는다.
 *
 * <ol>
 *   <li><b>일시정지 구간의 토큰은 DB 어디에도 저장되지 않는다.</b> {@code AnalysisHistory}에 토큰을
 *       기록하는 지점은 {@code MainApiController.finalizeAnalysis()} 한 곳뿐이고, PAUSED/CANCELLED로
 *       끝나는 경로는 그 지점에 도달하지 않는다. 즉 일시정지 시점까지 쓴 토큰은
 *       <b>메모리의 세션 카운터에만</b> 있다. 재개 시점에 리셋하면 그만큼이 <b>영구 소실</b>되고,
 *       {@code finalizeAnalysis()}가 절대값 대입으로 history를 덮어쓰므로 비용이 과소 기록된다 —
 *       REQ-002가 고치려는 "비용이 0원으로 기록되는" 오류와 <b>정확히 같은 방향</b>의 새 오류를
 *       만드는 셈이다.</li>
 *   <li><b>혼입은 리셋이 아니라 세션 키로 해소됐다.</b> 과거에 재개 시 리셋이 필요해 보였던 이유는
 *       카운터가 전역 한 통이라 남의 토큰이 섞여 있었기 때문이다. 카운터가
 *       {@code sourceFolderPath}로 격리된 뒤에는 "리셋하지 않음"이 더 이상 혼입을 유발하지 않는다.</li>
 * </ol>
 *
 * <p><b>그러면 리셋은 어디에 필요한가</b>: {@code runAnalysis()}(= 같은 소스 경로로 <b>처음부터</b>
 * 새 분석을 시작하는 경로)에는 리셋이 그대로 남아 있어야 한다. 이전 세션이 CANCELLED/PAUSED로
 * 끝나 카운터가 남아 있을 수 있고(세션 종료 정리는 FAILED/COMPLETED에만 일어난다), 그 잔존값을
 * 이어받으면 새 분석이 과대 기록된다. 아래 두 테스트가 이 <b>두 경로의 차이</b>를 각각 고정한다.
 *
 * <p><b>이 테스트가 반대로 바뀌면</b>(재개도 리셋하도록) 위 1번의 영구 소실이 되살아난다.
 * 숫자만 고정해 두면 다음 사이클이 이유를 모르고 뒤집을 수 있으므로 근거를 여기 남긴다.
 */
class ClaudeServiceResumeTokenSemanticsTest {

  /** 하나의 분석 세션(= 하나의 {@code sourceFolderPath}). 재개는 같은 키로 이어진다. */
  private static final String SESSION = "C:\\work\\paused-then-resumed";

  private static final String MODEL = "claude-sonnet-4-6";

  /** 일시정지 이전(1차 분석 구간)에 쓴 토큰. */
  private static final long FIRST_IN = 1000L, FIRST_OUT = 200L,
      FIRST_CACHE_READ = 50L, FIRST_CACHE_CREATE = 30L;
  /** 재개 이후(2차 분석 구간)에 쓴 토큰. */
  private static final long SECOND_IN = 400L, SECOND_OUT = 80L,
      SECOND_CACHE_READ = 10L, SECOND_CACHE_CREATE = 5L;

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
  void 재개하면_일시정지_이전_토큰까지_이어서_전체_합계가_나온다_리셋하면_영구_소실되므로() throws Exception {
    ClaudeServiceImpl service = newService();

    // [1차 분석] runAnalysis()가 리셋한 뒤 파일 몇 개를 분석한 상태
    service.resetTokenUsage(SESSION);
    accumulate(service, FIRST_IN, FIRST_OUT, FIRST_CACHE_READ, FIRST_CACHE_CREATE, MODEL, SESSION);
    assertEquals(FIRST_IN, service.getTotalTokenUsage(SESSION).getInputTokens(),
        "1차 분석 구간의 누적이 먼저 성립해야 한다(이후 단언의 전제)");

    // [일시정지 상당 구간] 크레딧 소진/사용자 일시정지로 PAUSED가 된다. 이 경로에서는
    //   - finalizeAnalysis()에 도달하지 않으므로 이 토큰은 DB에 기록되지 않고,
    //   - 세션 종료 정리(clearSessionSystemPrompt)도 FAILED/COMPLETED에만 일어나므로 호출되지 않는다.
    // 즉 이 시점의 누적치는 메모리 카운터에만 존재한다 — 그래서 여기서 리셋하면 영구 소실이다.
    assertEquals(FIRST_IN, service.getTotalTokenUsage(SESSION).getInputTokens(),
        "일시정지 구간을 지나도 카운터는 보존돼야 한다(PAUSED는 정리 대상이 아니다)");

    // [재개] runAnalysisResume()은 resetTokenUsage()를 호출하지 않고 이어서 누적한다(D1)
    accumulate(service, SECOND_IN, SECOND_OUT, SECOND_CACHE_READ, SECOND_CACHE_CREATE, MODEL, SESSION);

    TokenUsage usage = service.getTotalTokenUsage(SESSION);
    System.out.printf(
        "[TASK-003 재개=이어서 누적] 1차 %d + 2차 %d → input 기대 %d / 실측 %d, "
            + "output 기대 %d / 실측 %d, total 기대 %d / 실측 %d, "
            + "cacheRead 기대 %d / 실측 %d, cacheCreation 기대 %d / 실측 %d%n",
        FIRST_IN, SECOND_IN, FIRST_IN + SECOND_IN, usage.getInputTokens(),
        FIRST_OUT + SECOND_OUT, usage.getOutputTokens(),
        FIRST_IN + SECOND_IN + FIRST_OUT + SECOND_OUT, usage.getTotalTokens(),
        FIRST_CACHE_READ + SECOND_CACHE_READ, usage.getCacheReadTokens(),
        FIRST_CACHE_CREATE + SECOND_CACHE_CREATE, usage.getCacheCreationTokens());

    // 재개분만 따로 세지 않는다 — history 1행 = 분석 1건이고 total_tokens/estimated_cost는
    // 그 분석 "전체"의 총량을 뜻한다(finalizeAnalysis()의 절대값 대입이 이를 전제한다).
    assertEquals(FIRST_IN + SECOND_IN, usage.getInputTokens());
    assertEquals(FIRST_OUT + SECOND_OUT, usage.getOutputTokens());
    assertEquals(FIRST_IN + SECOND_IN + FIRST_OUT + SECOND_OUT, usage.getTotalTokens());
    assertEquals(FIRST_CACHE_READ + SECOND_CACHE_READ, usage.getCacheReadTokens());
    assertEquals(FIRST_CACHE_CREATE + SECOND_CACHE_CREATE, usage.getCacheCreationTokens());

    // 재개분만 세는 구현이었다면 이 값이 나왔을 것이다 — 그 차이가 곧 영구 소실될 토큰량이다.
    assertEquals(FIRST_IN, usage.getInputTokens() - SECOND_IN,
        "이어서 누적하지 않으면 1차 분석 구간의 토큰이 통째로 사라진다");
  }

  @Test
  void 같은_키로_처음부터_새_분석을_시작하면_초기화_이후_분만_집계된다() throws Exception {
    ClaudeServiceImpl service = newService();

    // 이전 세션이 CANCELLED/PAUSED로 끝나 카운터가 남아 있는 상태
    accumulate(service, FIRST_IN, FIRST_OUT, FIRST_CACHE_READ, FIRST_CACHE_CREATE, MODEL, SESSION);
    assertEquals(FIRST_IN, service.getTotalTokenUsage(SESSION).getInputTokens());

    // [새 분석 시작] runAnalysis()는 같은 키로 처음부터 시작하므로 리셋한다 —
    // 잔존값을 이어받으면 새 분석이 과대 기록된다.
    service.resetTokenUsage(SESSION);

    accumulate(service, SECOND_IN, SECOND_OUT, SECOND_CACHE_READ, SECOND_CACHE_CREATE, MODEL, SESSION);

    TokenUsage usage = service.getTotalTokenUsage(SESSION);
    System.out.printf(
        "[TASK-003 새 분석=초기화 후 집계] 잔존 %d 리셋 후 2차 %d → input 기대 %d / 실측 %d, "
            + "output 기대 %d / 실측 %d, cacheRead 기대 %d / 실측 %d, cacheCreation 기대 %d / 실측 %d%n",
        FIRST_IN, SECOND_IN, SECOND_IN, usage.getInputTokens(),
        SECOND_OUT, usage.getOutputTokens(),
        SECOND_CACHE_READ, usage.getCacheReadTokens(),
        SECOND_CACHE_CREATE, usage.getCacheCreationTokens());

    // 2차분만 나온다 — 재개(위 테스트)와 새 분석(이 테스트)이 같은 키에서도 서로 다르게 동작한다는
    // 것이 D1의 핵심이다. 리셋을 재개 경로에 추가하면 이 두 동작을 구분할 수 없게 된다.
    assertEquals(SECOND_IN, usage.getInputTokens());
    assertEquals(SECOND_OUT, usage.getOutputTokens());
    assertEquals(SECOND_IN + SECOND_OUT, usage.getTotalTokens());
    assertEquals(SECOND_CACHE_READ, usage.getCacheReadTokens());
    assertEquals(SECOND_CACHE_CREATE, usage.getCacheCreationTokens());
  }

  @Test
  void 일시정지_세션을_정리_대상에_넣으면_이전_누적치가_사라진다는_것이_PAUSED를_제외한_이유다() throws Exception {
    // 위 두 테스트의 근거에 대한 양성 대조군(G-04): "PAUSED는 정리 대상이 아니다"가 실제로
    // 무엇을 지키고 있는지 보인다. 프로덕션의 정리 호출부 2곳은 모두 FAILED/COMPLETED 가드
    // 아래에 있으므로 PAUSED 세션에서 이 경로는 실행되지 않는다 — 여기서는 만약 실행됐다면
    // 어떻게 되는지를 측정만 한다.
    ClaudeServiceImpl service = newService();

    accumulate(service, FIRST_IN, FIRST_OUT, FIRST_CACHE_READ, FIRST_CACHE_CREATE, MODEL, SESSION);
    assertEquals(FIRST_IN, service.getTotalTokenUsage(SESSION).getInputTokens());

    // 만약 일시정지 시점에도 세션 종료 정리를 호출했다면(= PAUSED를 정리 대상에 넣었다면)
    service.clearSessionSystemPrompt(SESSION);

    TokenUsage afterCleanup = service.getTotalTokenUsage(SESSION);
    System.out.printf(
        "[TASK-003 근거 대조군] 일시정지 시점에 정리를 호출했다면: input 1차 %d → 정리 후 실측 %d "
            + "(재개 시 이 %d 토큰이 영구 소실되고 비용이 과소 기록된다)%n",
        FIRST_IN, afterCleanup.getInputTokens(), FIRST_IN);

    assertEquals(0L, afterCleanup.getInputTokens(),
        "정리는 카운터를 제거하므로, PAUSED를 정리 대상에 넣으면 일시정지 이전 토큰이 사라진다");
    assertEquals(0L, afterCleanup.getTotalTokens());

    // 그 상태에서 재개해 2차분을 누적하면 1차분 없이 2차분만 남는다 = 과소 기록
    accumulate(service, SECOND_IN, SECOND_OUT, SECOND_CACHE_READ, SECOND_CACHE_CREATE, MODEL, SESSION);
    assertEquals(SECOND_IN, service.getTotalTokenUsage(SESSION).getInputTokens(),
        "일시정지 이전 1차분이 소실된 채 재개분만 기록된다");
  }
}
