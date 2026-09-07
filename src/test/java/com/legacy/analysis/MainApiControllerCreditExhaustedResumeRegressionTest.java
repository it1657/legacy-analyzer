package com.legacy.analysis;

import com.legacy.analysis.llm.LlmModelOptionService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 7(2026-08-25, 통합/회귀 검증) — "충전 후 이어서 분석" 기존 기능 회귀 확인.
 *
 * 29차 handOff.md(Phase 4)에서 고친 기존 버그를 재현/확인한다: 크레딧소진 분기가
 * {@code session.cancel()}을 호출해 {@code isCancelled}를 영구 true로 만드는 문제가 있었는데,
 * 이를 되돌리는 코드가 전체 코드베이스에 없어서 재개된 실행이 매 파일에서 즉시 중단 신호
 * ({@link SessionState#shouldStop()})에 걸려 아무것도 처리 못 하는 회귀 위험이 있었다.
 *
 * 이 시나리오의 실제 파일 처리 루프(runAnalysis/runAnalysisResume)는 스레드풀·파일 I/O·
 * LLM 클라이언트가 얽힌 private 메서드라 기존에도(29차 기록) 실제로 스레드를 띄워 끝까지
 * 실행하는 단위테스트 대상이 아니었다 — 여기서도 그 관행을 그대로 따르되, 문제의 핵심인
 * "크레딧소진 처리 → isCancelled 상태 → 재개 시 리셋되는 phase/status 조합으로 계산되는
 * {@code shouldStop()}"만 정확히 프로덕션 코드 그대로(handleCreditExhaustedPause를 리플렉션으로
 * 직접 호출) 재현해서 검증한다. 재개 스레드의 매 파일 처리 루프가 검사하는 조건이 바로
 * 이 {@code shouldStop()}이므로(MainApiController 1520행 근처 {@code cur.shouldStop()}), 이 값이
 * false임을 확인하면 "재개된 실행이 즉시 전부 건너뛰어지는" 그 버그가 재발하지 않았음을 보증한다.
 *
 * 근거: analyzer-plan
 * docs/chat/etc/2026-08-21-llm-model-db-crud-and-credit-exhaustion-failover-design.md §3.
 */
class MainApiControllerCreditExhaustedResumeRegressionTest {

  private MainApiController newController(AnalysisSessionManager sessionManager,
      LlmModelOptionService llmModelOptionService) {
    return new MainApiController(
        null, null, sessionManager, null, null, null,
        null, null, null, null, null, null, null, llmModelOptionService, null);
  }

  private void handleCreditExhaustedPause(MainApiController controller, SessionState session,
      AnalysisHistory history, List<String> pendingPaths, int completedCount) throws Exception {
    Method m = MainApiController.class.getDeclaredMethod(
        "handleCreditExhaustedPause", SessionState.class, AnalysisHistory.class, List.class, int.class);
    m.setAccessible(true);
    m.invoke(controller, session, history, pendingPaths, completedCount);
  }

  /**
   * failover 대상이 지정돼 있지 않은(=이번 이니셔티브 착수 이전과 동일한) 배포에서의 기존 시나리오:
   * 크레딧소진 → 단순 PAUSED → (충전 후) '이어서 분석' 클릭 → 재개.
   *
   * 이 테스트가 실패한다면(session.isCancelled()==true, 또는 재개 이후 shouldStop()==true)
   * 곧 "충전 후 이어서 분석" 기능이 재개된 스레드의 매 파일에서 즉시 중단되는 그 버그가
   * 되살아났다는 뜻이다.
   */
  @Test
  void 크레딧소진으로_PAUSED된_세션은_isCancelled가_영구화되지_않고_재개시_shouldStop이_false다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    LlmModelOptionService llmModelOptionService = mock(LlmModelOptionService.class);
    when(llmModelOptionService.getActiveFailoverTarget()).thenReturn(Optional.empty());
    MainApiController controller = newController(sessionManager, llmModelOptionService);

    SessionState session = new SessionState("sid", "/tmp/src", "/tmp/out");
    assertFalse(session.isCancelled(), "최초 세션은 취소 상태가 아니어야 한다");

    // 1단계: 크레딧 소진 발생 - 기존 버그가 있었다면 여기서 session.cancel()이 호출돼
    // isCancelled가 영구 true가 됐을 것이다.
    handleCreditExhaustedPause(controller, session, null,
        List.of("/tmp/src/A.java", "/tmp/src/B.java"), 3);

    assertEquals("PAUSED", session.getCurrentPhase(), "failover 대상이 없으면 기존과 동일하게 단순 PAUSED로 폴백해야 한다");
    assertFalse(session.isCancelled(),
        "크레딧소진 처리가 session.cancel()을 호출하면 안 된다 — 호출하면 재개 후 매 파일이 shouldStop()에 걸려 중단된다");
    assertTrue(session.shouldStop(), "PAUSED 상태 자체는 여전히 '멈춰야 하는 상태'로 인식돼야 한다(재개 전)");

    // 2단계: 충전 후 '이어서 분석' 클릭 - MainApiController.resumePendingFilesInThread(...)가
    // 재개 스레드 기동 직전(파일 처리를 시작하기도 전에) 동기적으로 수행하는 상태 리셋과
    // 정확히 동일하다(해당 메서드 원문: session.setCurrentPhase("ANALYZING");
    // session.setStatus("IN_PROGRESS");) — 실제 스레드/파일 I/O 없이 바로 이 리셋 결과만으로
    // shouldStop()이 어떻게 계산되는지를 검증한다.
    session.setCurrentPhase("ANALYZING");
    session.setStatus("IN_PROGRESS");

    // 핵심 회귀 확인: 재개 스레드가 매 파일마다 검사하는 바로 그 조건(cur.shouldStop())이 여기서
    // false여야 파일이 실제로 처리된다. isCancelled가 영구화되는 기존 버그가 있었다면 PAUSED에서
    // ANALYZING으로 전이해도(=대부분의 shouldStop() 조건은 해소돼도) isCancelled만은 여전히
    // true로 남아 shouldStop()이 계속 true였을 것이다.
    assertFalse(session.isCancelled(), "재개 이후에도 isCancelled는 계속 false여야 한다");
    assertFalse(session.shouldStop(),
        "재개된 세션은 더 이상 멈춰야 하는 상태가 아니어야 한다 — true라면 재개 스레드가 즉시 모든 파일을 건너뛴다");
  }
}
