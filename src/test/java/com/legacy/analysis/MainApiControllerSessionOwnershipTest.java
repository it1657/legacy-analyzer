package com.legacy.analysis;

import com.legacy.auth.Role;
import com.legacy.auth.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code POST /api/session/pause}·{@code /resume}·{@code /cancel} 3개 엔드포인트에 대한
 * 세션 소유자 검증(2026-08-21 버그 수정)을 검증한다.
 *
 * 배경: 이 3개 + {@code /api/session/failover/confirm}(검증은 {@link MainApiControllerFailoverConfirmTest}에서
 * 함께 다룸) 전부 로그인만 하면 sessionId를 아는 임의 사용자가 남의 세션을 제어할 수 있는 인가 우회
 * 취약점이 있었다(analyzer-plan docs/pipeline/bug-suspects.md). 세션 소유자 검증은
 * {@code com.legacy.api.monitoring.MonitoringController#isOwnerOrAdmin}과 동일한 기존 패턴
 * (세션 소유자 or ADMIN 허용, {@code user.getUserId()} ↔ {@code session.getUsername()} 비교)을
 * {@code MainApiController#isSessionOwnerOrAdmin}으로 재사용해 추가했다.
 */
class MainApiControllerSessionOwnershipTest {

  private MainApiController newController(AnalysisSessionManager sessionManager,
      AnalysisHistoryRepository analysisHistoryRepository) {
    return new MainApiController(
        null, null, sessionManager, null, null, null,
        analysisHistoryRepository, null, null, null, null, null, null, null);
  }

  /** 세션 소유자와 같은 로그인ID를 가진 일반 사용자 인증 객체 */
  private Authentication authAs(String loginId) {
    User user = new User(loginId, loginId + "@example.com", "hash");
    user.setSeq(1L);
    user.setRoles(Set.of(new Role("USER", "일반 사용자")));
    return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
  }

  /** ADMIN 권한을 가진 사용자 인증 객체 (로그인ID는 세션 소유자와 다를 수 있음) */
  private Authentication adminAuth(String loginId) {
    User user = new User(loginId, loginId + "@example.com", "hash");
    user.setSeq(99L);
    user.setRoles(Set.of(new Role("ADMIN", "관리자")));
    return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
  }

  // ===================================================================
  // POST /api/session/pause
  // ===================================================================

  @Test
  void pause_소유자_본인이_호출하면_성공한다() {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);
    SessionState session = new SessionState("sid", "src", "out");
    session.setUsername("owner");
    when(sessionManager.getSession("sid")).thenReturn(session);
    when(historyRepository.findBySessionId("sid")).thenReturn(null);
    MainApiController controller = newController(sessionManager, historyRepository);

    Map<String, Object> response = controller.pauseSession(Map.of("sessionId", "sid"), authAs("owner"));

    assertEquals(true, response.get("success"));
    assertEquals("PAUSED", session.getCurrentPhase());
  }

  @Test
  void pause_다른_사용자가_호출하면_거부된다() {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    SessionState session = new SessionState("sid", "src", "out");
    session.setUsername("owner");
    session.setCurrentPhase("ANALYZING");
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(sessionManager, mock(AnalysisHistoryRepository.class));

    Map<String, Object> response = controller.pauseSession(Map.of("sessionId", "sid"), authAs("attacker"));

    assertEquals(false, response.get("success"));
    assertTrue(((String) response.get("message")).contains("본인 세션만"));
    assertEquals("ANALYZING", session.getCurrentPhase(), "권한이 없으면 세션 상태를 건드리면 안 된다");
  }

  @Test
  void pause_ADMIN이_호출하면_소유자가_아니어도_성공한다() {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);
    SessionState session = new SessionState("sid", "src", "out");
    session.setUsername("owner");
    when(sessionManager.getSession("sid")).thenReturn(session);
    when(historyRepository.findBySessionId("sid")).thenReturn(null);
    MainApiController controller = newController(sessionManager, historyRepository);

    Map<String, Object> response = controller.pauseSession(Map.of("sessionId", "sid"), adminAuth("admin"));

    assertEquals(true, response.get("success"));
  }

  // ===================================================================
  // POST /api/session/resume
  // ===================================================================

  @Test
  void resume_소유자_본인이_호출하면_성공한다() throws InterruptedException {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    SessionState session = new SessionState("sid", "/tmp/src", "/tmp/out");
    session.setUsername("owner");
    session.setPendingFilePaths(java.util.List.of("/tmp/src/A.java"));
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(sessionManager, mock(AnalysisHistoryRepository.class));

    Map<String, Object> response = controller.resumeSession(Map.of("sessionId", "sid"), authAs("owner"));

    assertEquals(true, response.get("success"));
    assertEquals("ANALYZING", session.getCurrentPhase());
    // resumePendingFilesInThread가 기동한 백그라운드 스레드가 세션 상태를 추가로 건드리기 전에
    // 어서션이 끝나도록 잠깐 대기(파일이 실제로 존재하지 않아 즉시 빈 목록으로 종료됨).
    Thread.sleep(100);
  }

  @Test
  void resume_다른_사용자가_호출하면_거부된다() {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    SessionState session = new SessionState("sid", "/tmp/src", "/tmp/out");
    session.setUsername("owner");
    session.setCurrentPhase("PAUSED");
    session.setPendingFilePaths(java.util.List.of("/tmp/src/A.java"));
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(sessionManager, mock(AnalysisHistoryRepository.class));

    Map<String, Object> response = controller.resumeSession(Map.of("sessionId", "sid"), authAs("attacker"));

    assertEquals(false, response.get("success"));
    assertTrue(((String) response.get("message")).contains("본인 세션만"));
    assertEquals("PAUSED", session.getCurrentPhase(), "권한이 없으면 세션 상태를 건드리면 안 된다");
  }

  // ===================================================================
  // POST /api/session/cancel
  // ===================================================================

  @Test
  void cancel_소유자_본인이_호출하면_성공한다() {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);
    SessionState session = new SessionState("sid", "src", "out");
    session.setUsername("owner");
    when(sessionManager.getSession("sid")).thenReturn(session);
    when(historyRepository.findBySessionId("sid")).thenReturn(null);
    MainApiController controller = newController(sessionManager, historyRepository);

    Map<String, Object> response = controller.cancelSession(Map.of("sessionId", "sid"), authAs("owner"));

    assertEquals(true, response.get("success"));
    assertEquals("CANCELLED", session.getCurrentPhase());
  }

  @Test
  void cancel_다른_사용자가_호출하면_거부된다() {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    SessionState session = new SessionState("sid", "src", "out");
    session.setUsername("owner");
    session.setCurrentPhase("ANALYZING");
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(sessionManager, mock(AnalysisHistoryRepository.class));

    Map<String, Object> response = controller.cancelSession(Map.of("sessionId", "sid"), authAs("attacker"));

    assertEquals(false, response.get("success"));
    assertTrue(((String) response.get("message")).contains("본인 세션만"));
    assertEquals("ANALYZING", session.getCurrentPhase(), "권한이 없으면 세션 상태를 건드리면 안 된다");
  }

  @Test
  void cancel_세션이_존재하지_않아도_기존과_동일하게_성공응답을_반환한다() {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    when(sessionManager.getSession("no-such-session")).thenReturn(null);
    MainApiController controller = newController(sessionManager, mock(AnalysisHistoryRepository.class));

    Map<String, Object> response = controller.cancelSession(
        new HashMap<>(Map.of("sessionId", "no-such-session")), authAs("owner"));

    assertEquals(true, response.get("success"), "세션 not-found 케이스의 기존 동작(회귀 없음)을 유지해야 한다");
  }
}
