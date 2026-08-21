package com.legacy.analysis;

import com.legacy.analysis.llm.LlmModelOption;
import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.analysis.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 4(2026-08-21, 크레딧소진 컨펌 기반 failover) 백엔드 검증.
 *
 * - 크레딧 소진 공통 처리({@code handleCreditExhaustedPause})가 failover 대상 유무에 따라
 *   AWAITING_FAILOVER_CONFIRM ↔ 기존 PAUSED로 올바르게 분기하는지
 * - 신규 {@code POST /api/session/failover/confirm}의 상태 검증/모델 전환/재개 스레드 기동을
 *   {@code POST /api/session/resume}과 동일한 리플렉션 패턴으로 검증한다.
 *
 * 근거: analyzer-plan
 * docs/chat/etc/2026-08-21-llm-model-db-crud-and-credit-exhaustion-failover-design.md §4.
 */
class MainApiControllerFailoverConfirmTest {

  private MainApiController newController(ClaudeService claudeService,
      AnalysisSessionManager sessionManager, AnalysisHistoryRepository analysisHistoryRepository,
      LlmModelOptionService llmModelOptionService) throws Exception {
    return new MainApiController(
        claudeService, null, sessionManager, null, null, null,
        analysisHistoryRepository, null, null, null, null, null, llmModelOptionService);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> confirmFailover(MainApiController controller, Map<String, String> request)
      throws Exception {
    Method m = MainApiController.class.getDeclaredMethod("confirmFailover", Map.class);
    m.setAccessible(true);
    return (Map<String, Object>) m.invoke(controller, request);
  }

  private void handleCreditExhaustedPause(MainApiController controller, SessionState session,
      AnalysisHistory history, List<String> pendingPaths, int completedCount) throws Exception {
    Method m = MainApiController.class.getDeclaredMethod(
        "handleCreditExhaustedPause", SessionState.class, AnalysisHistory.class, List.class, int.class);
    m.setAccessible(true);
    m.invoke(controller, session, history, pendingPaths, completedCount);
  }

  // ===================================================================
  // handleCreditExhaustedPause
  // ===================================================================

  @Test
  void failover_대상이_지정돼있으면_AWAITING_FAILOVER_CONFIRM으로_전이한다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);
    LlmModelOptionService llmModelOptionService = mock(LlmModelOptionService.class);
    LlmModelOption localTarget = new LlmModelOption("qwen3-32b", "자체 LLM", LlmProvider.LOCAL, 0);
    when(llmModelOptionService.getActiveFailoverTarget()).thenReturn(Optional.of(localTarget));
    MainApiController controller = newController(null, sessionManager, historyRepository, llmModelOptionService);

    SessionState session = new SessionState("sid", "src", "out");
    AnalysisHistory history = new AnalysisHistory();

    handleCreditExhaustedPause(controller, session, history, List.of("a.java", "b.java"), 3);

    assertEquals(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM, session.getStatus());
    assertEquals(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM, session.getCurrentPhase());
    assertEquals("qwen3-32b", session.getFailoverModelKey());
    assertEquals("PAUSED", history.getStatus(), "AnalysisHistory(내 분석 이력 목록)는 기존과 동일하게 PAUSED로 남겨 프런트 회귀를 막는다");
    verify(sessionManager, times(1)).saveSessionState(session);
    verify(historyRepository, times(1)).save(history);
  }

  @Test
  void failover_대상이_없으면_기존과_동일하게_단순_PAUSED로_폴백한다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    LlmModelOptionService llmModelOptionService = mock(LlmModelOptionService.class);
    when(llmModelOptionService.getActiveFailoverTarget()).thenReturn(Optional.empty());
    MainApiController controller = newController(null, sessionManager, null, llmModelOptionService);

    SessionState session = new SessionState("sid", "src", "out");

    handleCreditExhaustedPause(controller, session, null, List.of("a.java"), 5);

    assertEquals("PAUSED", session.getCurrentPhase());
    assertNull(session.getFailoverModelKey(), "failover 대상이 없으면 failoverModelKey를 세팅하면 안 됨");
    // 기존 코드도 이 폴백 경로에서 session.setStatus(...)는 호출하지 않았다(currentPhase만 갱신) —
    // 이번 리팩터링이 기존 동작을 바꾸지 않았는지 함께 확인한다.
    assertNotEquals(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM, session.getStatus());
    verify(sessionManager, times(1)).saveSessionState(session);
  }

  // ===================================================================
  // POST /api/session/failover/confirm
  // ===================================================================

  @Test
  void sessionId가_없으면_실패_응답을_반환한다() throws Exception {
    MainApiController controller = newController(null, mock(AnalysisSessionManager.class), null, null);

    Map<String, Object> response = confirmFailover(controller, new HashMap<>());

    assertEquals(false, response.get("success"));
  }

  @Test
  void 세션을_찾을수없으면_실패_응답을_반환한다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    when(sessionManager.getSession("sid")).thenReturn(null);
    MainApiController controller = newController(null, sessionManager, null, null);

    Map<String, Object> response = confirmFailover(controller, Map.of("sessionId", "sid"));

    assertEquals(false, response.get("success"));
    assertTrue(((String) response.get("message")).contains("세션을 찾을 수 없습니다"));
  }

  @Test
  void AWAITING_FAILOVER_CONFIRM_상태가_아니면_거부한다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    SessionState session = new SessionState("sid", "src", "out");
    session.setCurrentPhase("ANALYZING");
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(null, sessionManager, null, null);

    Map<String, Object> response = confirmFailover(controller, Map.of("sessionId", "sid"));

    assertEquals(false, response.get("success"));
    assertTrue(((String) response.get("message")).contains("컨펌 대기 상태가 아닙니다"));
  }

  @Test
  void failoverModelKey가_없으면_거부한다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    SessionState session = new SessionState("sid", "src", "out");
    session.setCurrentPhase(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM);
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(null, sessionManager, null, null);

    Map<String, Object> response = confirmFailover(controller, Map.of("sessionId", "sid"));

    assertEquals(false, response.get("success"));
    assertTrue(((String) response.get("message")).contains("failover 대상 모델"));
  }

  @Test
  void 정상_컨펌시_모델을_전환하고_재개를_기동한다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    ClaudeService claudeService = mock(ClaudeService.class);
    SessionState session = new SessionState("sid", "/tmp/src", "/tmp/out");
    session.setCurrentPhase(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM);
    session.setFailoverModelKey("qwen3-32b");
    session.setPendingFilePaths(List.of("/tmp/src/A.java", "/tmp/src/B.java"));
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(claudeService, sessionManager, null, null);

    Map<String, Object> response = confirmFailover(controller, Map.of("sessionId", "sid"));

    assertEquals(true, response.get("success"));
    assertEquals("sid", response.get("sessionId"));
    assertEquals("qwen3-32b", response.get("failoverModelKey"));
    assertNotNull(session.getFailoverConfirmedAt(), "컨펌 시각이 기록돼야 함");
    assertEquals("ANALYZING", session.getCurrentPhase(), "기존 resume과 동일하게 재개 스레드 기동 직전 ANALYZING으로 전이해야 함");
    assertEquals("IN_PROGRESS", session.getStatus());
    verify(claudeService, times(1)).setModel(eq(Path.of("/tmp/src").toString()), eq("qwen3-32b"));
  }

  @Test
  void 재개할_pending_파일이_없으면_모델전환없이_실패한다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    ClaudeService claudeService = mock(ClaudeService.class);
    SessionState session = new SessionState("sid", "/tmp/src", "/tmp/out");
    session.setCurrentPhase(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM);
    session.setFailoverModelKey("qwen3-32b");
    session.setPendingFilePaths(List.of());
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(claudeService, sessionManager, null, null);

    Map<String, Object> response = confirmFailover(controller, Map.of("sessionId", "sid"));

    assertEquals(false, response.get("success"));
    assertTrue(((String) response.get("message")).contains("재개할 파일이 없습니다"));
    verify(claudeService, never()).setModel(anyString(), anyString());
    assertNull(session.getFailoverConfirmedAt(), "재개할 파일이 없어 거부된 경우 모델 전환/컨펌 시각 기록 같은 부작용이 없어야 함");
  }
}
