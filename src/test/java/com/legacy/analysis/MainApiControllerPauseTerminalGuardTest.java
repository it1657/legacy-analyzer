package com.legacy.analysis;

import com.legacy.auth.Role;
import com.legacy.auth.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TASK-004 (work-order 2026-10-resume-consistency-and-local-guard v1, REQ-001 ②) —
 * {@code POST /api/session/pause}의 <b>종단 거부·중복 무시 가드</b>.
 *
 * <p>고치려는 결함(bug-suspects ⑧): 이미 끝난 분석에 일시정지가 들어오면 종전 코드는 상태를 먼저
 * 바꿔 세션을 PAUSED로 덮고 {@code pause_settled}에 FALSE를 썼다. 그 결과 목록 화면이 그 기록을
 * "⏸️ 일시정지 처리 중입니다"로 영구히 보여 주고, 재개 버튼도 뜨지 않았다. 재시작 후에는 세션
 * {@code currentPhase}가 "STARTING"으로 되살아나 세션 판정만으로는 걸러지지 않으므로 <b>이력
 * 상태까지</b> 함께 본다.
 *
 * <p>가드는 <b>어떤 상태 변경보다 먼저</b> 동작해야 한다 — "거부는 됐는데 세션은 이미 더럽혀진"
 * 상태가 생기면 고친 의미가 없다. 그래서 거부 케이스마다 {@code pauseSettled} raw 값·{@code pausedAt}·
 * {@code status}가 호출 전과 같은지, 저장·이력 save가 0회인지를 함께 단언한다.
 */
class MainApiControllerPauseTerminalGuardTest {

  private MainApiController newController(AnalysisSessionManager sessionManager,
      AnalysisHistoryRepository historyRepository) {
    return new MainApiController(
        null, null, sessionManager, null, null, null,
        historyRepository, null, null, null, null, null, null, null, null);
  }

  private Authentication authAs(String loginId) {
    User user = new User(loginId, loginId + "@example.com", "hash");
    user.setSeq(1L);
    user.setRoles(Set.of(new Role("USER", "일반 사용자")));
    return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
  }

  /** 엔티티 필드의 raw 값을 리플렉션으로 읽는다(raw getter를 새로 만들지 않는 기존 관례). */
  private static Object rawField(Object target, String name) throws Exception {
    Field f = SessionState.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  private SessionState session(String status, String phase, Boolean pauseSettled) {
    SessionState s = new SessionState("sid", "/src", "/out");
    s.setUsername("owner");
    s.setStatus(status);
    s.setCurrentPhase(phase);
    s.setPauseSettled(pauseSettled);
    return s;
  }

  private AnalysisHistory history(String status) {
    AnalysisHistory h = new AnalysisHistory(1L, "sid", "/src", "/out");
    h.setId(7L);
    h.setStatus(status);
    return h;
  }

  /** 거부(또는 무시) 케이스 공통 단언 — 호출 전후 값이 같고 저장이 0회다. */
  private void assertNothingTouched(SessionState session, AnalysisSessionManager sessionManager,
      AnalysisHistoryRepository historyRepository, String statusBefore, String phaseBefore,
      Object pauseSettledBefore, LocalDateTime pausedAtBefore, String label) throws Exception {
    assertEquals(statusBefore, session.getStatus(), label + ": status가 바뀌었다");
    assertEquals(phaseBefore, session.getCurrentPhase(), label + ": currentPhase가 바뀌었다");
    assertEquals(pauseSettledBefore, rawField(session, "pauseSettled"),
        label + ": pauseSettled raw 값이 바뀌었다(NULL이 FALSE로 덮이는 것이 바로 이 버그다)");
    assertEquals(pausedAtBefore, session.getPausedAt(), label + ": pausedAt이 바뀌었다");
    verify(sessionManager, never()).saveSessionState(any());
    verify(historyRepository, never()).save(any());
  }

  // ===================================================================
  // 종단 거부 — 세션 status / 세션 phase / 이력 status 세 경로
  // ===================================================================

  @Test
  void 세션_status가_종단이면_거부하고_아무것도_바꾸지_않는다() throws Exception {
    for (String terminal : java.util.List.of("COMPLETED", "CANCELLED", "FAILED")) {
      AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
      AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);
      SessionState session = session(terminal, "FINALIZING", null);
      when(sessionManager.getSession("sid")).thenReturn(session);
      when(historyRepository.findBySessionId("sid")).thenReturn(null);
      MainApiController controller = newController(sessionManager, historyRepository);

      Map<String, Object> response =
          controller.pauseSession(Map.of("sessionId", "sid"), authAs("owner"));
      System.out.println("[종단 거부/세션 status=" + terminal + "] response=" + response);

      assertEquals(false, response.get("success"), terminal);
      assertEquals("SESSION_TERMINAL", response.get("reason"), terminal);
      assertEquals("이미 끝난 분석은 일시정지할 수 없습니다.", response.get("message"), terminal);
      assertNothingTouched(session, sessionManager, historyRepository,
          terminal, "FINALIZING", null, null, "세션 status=" + terminal);
    }
  }

  @Test
  void 세션_currentPhase가_종단이면_거부한다() throws Exception {
    for (String terminal : java.util.List.of("COMPLETED", "CANCELLED", "FAILED")) {
      AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
      AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);
      SessionState session = session("IN_PROGRESS", terminal, null);
      when(sessionManager.getSession("sid")).thenReturn(session);
      when(historyRepository.findBySessionId("sid")).thenReturn(null);
      MainApiController controller = newController(sessionManager, historyRepository);

      Map<String, Object> response =
          controller.pauseSession(Map.of("sessionId", "sid"), authAs("owner"));
      System.out.println("[종단 거부/세션 phase=" + terminal + "] response=" + response);

      assertEquals(false, response.get("success"), terminal);
      assertEquals("SESSION_TERMINAL", response.get("reason"), terminal);
      assertNothingTouched(session, sessionManager, historyRepository,
          "IN_PROGRESS", terminal, null, null, "세션 phase=" + terminal);
    }
  }

  /**
   * bug-suspects ⑧의 실제 모양 — <b>재시작 후</b>에는 세션이 DB에서 복원돼 {@code currentPhase}가
   * "STARTING", {@code status}가 "IN_PROGRESS"인데 이력은 이미 COMPLETED다. 세션 판정만으로는
   * 걸러지지 않으므로 이력 상태까지 봐야 거부된다.
   */
  @Test
  void 세션은_진행중처럼_보이지만_이력이_종단이면_거부한다_재시작_후_모양() throws Exception {
    for (String terminal : java.util.List.of("COMPLETED", "CANCELLED", "FAILED")) {
      AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
      AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);
      SessionState session = session("IN_PROGRESS", "STARTING", null);
      when(sessionManager.getSession("sid")).thenReturn(session);
      when(historyRepository.findBySessionId("sid")).thenReturn(history(terminal));
      MainApiController controller = newController(sessionManager, historyRepository);

      Map<String, Object> response =
          controller.pauseSession(Map.of("sessionId", "sid"), authAs("owner"));
      System.out.println("[종단 거부/이력 status=" + terminal + "] response=" + response);

      assertEquals(false, response.get("success"), terminal);
      assertEquals("SESSION_TERMINAL", response.get("reason"), terminal);
      assertNothingTouched(session, sessionManager, historyRepository,
          "IN_PROGRESS", "STARTING", null, null, "이력 status=" + terminal);
      // 이력 조회는 1회만(판정용으로 당겨온 결과를 아래 갱신에서 재사용한다)
      verify(historyRepository, times(1)).findBySessionId("sid");
    }
  }

  // ===================================================================
  // 중복 무시 — 이미 PAUSED / AWAITING_FAILOVER_CONFIRM
  // ===================================================================

  @Test
  void 이미_일시정지된_세션은_값을_그대로_두고_성공으로_돌려준다() throws Exception {
    // raw TRUE(확정됨)와 raw NULL(옛 행) 둘 다 — 재호출로 FALSE로 되돌아가면 안 된다.
    for (Boolean raw : java.util.Arrays.asList(Boolean.TRUE, null)) {
      for (String paused : java.util.List.of("PAUSED", SessionState.STATUS_AWAITING_FAILOVER_CONFIRM)) {
        AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
        AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);
        SessionState session = session(paused, paused, raw);
        LocalDateTime pausedAt = LocalDateTime.of(2026, 10, 1, 9, 0);
        session.setPausedAt(pausedAt);
        when(sessionManager.getSession("sid")).thenReturn(session);
        when(historyRepository.findBySessionId("sid")).thenReturn(history("PAUSED"));
        MainApiController controller = newController(sessionManager, historyRepository);

        Map<String, Object> response =
            controller.pauseSession(Map.of("sessionId", "sid"), authAs("owner"));
        System.out.println("[중복 무시/" + paused + ", raw=" + raw + "] response=" + response
            + ", pauseSettled(raw 호출 후)=" + rawField(session, "pauseSettled"));

        assertEquals(true, response.get("success"));
        assertEquals(true, response.get("alreadyPaused"));
        assertEquals("이미 일시정지된 분석입니다.", response.get("message"));
        assertNull(response.get("reason"), "중복 무시는 거부가 아니므로 reason이 없다");
        assertNothingTouched(session, sessionManager, historyRepository,
            paused, paused, raw, pausedAt, "중복 " + paused + "/raw=" + raw);
      }
    }
  }

  // ===================================================================
  // 양성 대조군 — 진행 중 세션은 기존 동작 그대로
  // ===================================================================

  @Test
  void 양성대조군_진행중_ANALYZING_세션은_기존대로_FALSE를_저장하고_이력을_PAUSED로_바꾼다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);
    SessionState session = session("IN_PROGRESS", "ANALYZING", null);
    AnalysisHistory h = history("IN_PROGRESS");
    when(sessionManager.getSession("sid")).thenReturn(session);
    when(historyRepository.findBySessionId("sid")).thenReturn(h);
    MainApiController controller = newController(sessionManager, historyRepository);

    Map<String, Object> response =
        controller.pauseSession(Map.of("sessionId", "sid"), authAs("owner"));
    System.out.println("[대조군 진행중] response=" + response
        + ", status=" + session.getStatus() + ", phase=" + session.getCurrentPhase()
        + ", pauseSettled(raw)=" + rawField(session, "pauseSettled")
        + ", historyStatus=" + h.getStatus());

    assertEquals(true, response.get("success"));
    assertNull(response.get("alreadyPaused"), "진행 중 세션은 alreadyPaused를 붙이지 않는다");
    assertNull(response.get("reason"));
    assertEquals("분석이 일시 중지되었습니다.", response.get("message"),
        "정상 경로 문구는 착수 커밋과 글자 그대로 같아야 한다");
    assertEquals("PAUSED", session.getStatus());
    assertEquals("PAUSED", session.getCurrentPhase());
    assertEquals(Boolean.FALSE, rawField(session, "pauseSettled"), "정상 경로는 FALSE를 쓴다");
    assertEquals("PAUSED", h.getStatus());
    verify(sessionManager, times(1)).saveSessionState(session);
    verify(historyRepository, times(1)).save(h);
    verify(historyRepository, times(1)).findBySessionId("sid");
  }

  @Test
  void 양성대조군_이력_조회가_예외를_던져도_진행중_세션은_기존대로_FALSE를_저장한다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);
    SessionState session = session("IN_PROGRESS", "ANALYZING", null);
    when(sessionManager.getSession("sid")).thenReturn(session);
    when(historyRepository.findBySessionId("sid"))
        .thenThrow(new RuntimeException("Null value was assigned to a property of primitive type"));
    MainApiController controller = newController(sessionManager, historyRepository);

    Map<String, Object> response =
        controller.pauseSession(Map.of("sessionId", "sid"), authAs("owner"));
    System.out.println("[대조군 이력 조회 예외] response=" + response
        + ", status=" + session.getStatus() + ", pauseSettled(raw)=" + rawField(session, "pauseSettled"));

    assertEquals(true, response.get("success"), "이력 조회 실패가 일시정지를 막아서는 안 된다");
    assertEquals("PAUSED", session.getStatus());
    assertEquals(Boolean.FALSE, rawField(session, "pauseSettled"));
    verify(sessionManager, times(1)).saveSessionState(session);
    verify(historyRepository, never()).save(any());
  }

  // ===================================================================
  // 가드가 기존 앞단 검사를 가리지 않는다
  // ===================================================================

  @Test
  void 가드는_기존_앞단_검사_뒤에_있다_sessionId없음_세션없음_소유자아님() {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);
    MainApiController controller = newController(sessionManager, historyRepository);

    // sessionId 없음
    Map<String, Object> noId = controller.pauseSession(Map.of(), authAs("owner"));
    assertEquals(false, noId.get("success"));
    assertTrue(((String) noId.get("message")).contains("세션 ID가 필요합니다"));
    assertNull(noId.get("reason"));

    // 세션 없음
    when(sessionManager.getSession("sid")).thenReturn(null);
    Map<String, Object> noSession = controller.pauseSession(Map.of("sessionId", "sid"), authAs("owner"));
    assertEquals(false, noSession.get("success"));
    assertTrue(((String) noSession.get("message")).contains("세션을 찾을 수 없습니다"));

    // 소유자 아님(세션은 종단이지만 소유권 메시지가 먼저 나와야 한다)
    SessionState terminal = session("COMPLETED", "COMPLETED", null);
    when(sessionManager.getSession("sid")).thenReturn(terminal);
    Map<String, Object> notOwner = controller.pauseSession(Map.of("sessionId", "sid"), authAs("attacker"));
    assertEquals(false, notOwner.get("success"));
    assertTrue(((String) notOwner.get("message")).contains("본인 세션만"),
        "소유권 검사가 종단 가드보다 앞이어야 한다. 실제: " + notOwner);

    // 앞단에서 걸렸으므로 이력 조회조차 하지 않는다(조회 횟수 불변).
    verify(historyRepository, never()).findBySessionId(anyString());
  }
}
