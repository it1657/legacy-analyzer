package com.legacy.analysis;

import com.legacy.analysis.llm.LlmModelOptionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GET /api/analysis/status/{sessionId} — Phase 5(failover 컨펌 프런트) 착수 전 확인 항목
 * (29차 handOff.md에서 남긴 "필수 확인 항목")을 검증한다.
 *
 * - AWAITING_FAILOVER_CONFIRM 상태에서 completed는 여전히 false여야 한다(Phase 4 결정 회귀 확인 —
 *   여기서 true를 반환하면 dashboard.js 폴링이 컨펌 대기를 "분석 완료"로 오인한다).
 * - AWAITING_FAILOVER_CONFIRM일 때만 failoverModelKey가 응답에 실려야 한다(Phase 5 신규).
 */
class MainApiControllerAnalysisStatusTest {

  private MainApiController newController(AnalysisSessionManager sessionManager) {
    return new MainApiController(
        null, null, sessionManager, null, null, null,
        null, null, null, null, null, null, null, mock(LlmModelOptionService.class), null);
  }

  @Test
  void AWAITING_FAILOVER_CONFIRM_상태는_completed가_false이고_failoverModelKey를_함께_내려준다() {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    SessionState session = new SessionState("sid", "/tmp/src", "/tmp/out");
    session.setCurrentPhase(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM);
    session.setFailoverModelKey("qwen3-32b");
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(sessionManager);

    AnalysisStatusDto dto = controller.getAnalysisStatus("sid", 80, null);

    assertFalse(dto.isCompleted(), "컨펌 대기 상태를 분석 완료로 오인하면 안 된다(Phase 4 결정 회귀 확인)");
    assertEquals(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM, dto.getPhase());
    assertEquals("qwen3-32b", dto.getFailoverModelKey());
  }

  @Test
  void 다른_phase에서는_failoverModelKey를_내려주지_않는다() {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    SessionState session = new SessionState("sid", "/tmp/src", "/tmp/out");
    session.setCurrentPhase("ANALYZING");
    session.setFailoverModelKey("qwen3-32b"); // 과거 컨펌 대기였다가 이미 전환된 이후 잔값이 남아있는 경우를 가정
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(sessionManager);

    AnalysisStatusDto dto = controller.getAnalysisStatus("sid", 80, null);

    assertNull(dto.getFailoverModelKey(), "AWAITING_FAILOVER_CONFIRM이 아니면 failoverModelKey를 노출하면 안 된다");
  }

  @Test
  void PAUSED_상태는_기존과_동일하게_completed가_true다() {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    SessionState session = new SessionState("sid", "/tmp/src", "/tmp/out");
    session.setCurrentPhase("PAUSED");
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(sessionManager);

    AnalysisStatusDto dto = controller.getAnalysisStatus("sid", 80, null);

    assertTrue(dto.isCompleted(), "기존 PAUSED 폴링 종료 처리는 이번 변경과 무관하게 그대로 유지돼야 한다");
    assertNull(dto.getFailoverModelKey());
  }
}
