package com.legacy.analysis;

import com.legacy.auth.Role;
import com.legacy.auth.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.mockito.stubbing.Answer;

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

  /**
   * 재개 성공을 단언하는 테스트의 <b>대기 경로 픽스처용 실제 파일</b>을 둘 디렉터리.
   * 2026-10 REQ-004로 재개가 원본 존재를 확인하게 됐으므로, 존재하지 않는 경로("/tmp/src/A.java")로는
   * 재개가 정상 거부된다. 거부를 단언하는 테스트들은 소유권 검사가 원본 판정보다 앞이라 그대로 둔다.
   */
  @TempDir
  Path pendingSourceDir;

  private MainApiController newController(AnalysisSessionManager sessionManager,
      AnalysisHistoryRepository analysisHistoryRepository) {
    return new MainApiController(
        null, null, sessionManager, null, null, null,
        analysisHistoryRepository, null, null, null, null, null, null, null, null);
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

  /**
   * 재개 스레드의 <b>첫 {@code getSession} 호출</b>을 붙잡아, 테스트 단언과 재개 스레드의 세션 쓰기가
   * 겹치지 않게 만드는 도우미.
   *
   * <p>호출한 스레드가 테스트 스레드면 세션을 그대로 돌려준다(기존 {@code thenReturn}과 같다).
   * 그 밖의 스레드(= 재개 스레드)면 도착을 알리고 놓아줄 때까지 기다린 뒤 {@code null}을 돌려준다.
   * 재개 로직은 첫 문장이 {@code getSession}이고 세션에 쓰는 지점은 모두 그 반환 뒤에 있으므로,
   * {@code null}을 받은 재개 스레드는 세션에 아무것도 쓰지 않고 끝난다.
   *
   * <p>대기 상한은 셋 다 10초다. 정상 경로에서 기다리는 사건은 스레드 시작 한 번뿐이라, 상한은
   * 무한 대기를 막는 용도이며 초과는 실패로 드러난다.
   */
  private static final class ResumeThreadGate {
    private static final long TIMEOUT_SECONDS = 10L;

    private final Thread testThread = Thread.currentThread();
    private final CountDownLatch arrived = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final AtomicInteger resumeThreadCalls = new AtomicInteger();
    private volatile Thread resumeThread;

    private Answer<SessionState> answer(SessionState session) {
      return invocation -> {
        if (Thread.currentThread() == testThread) {
          return session;
        }
        resumeThread = Thread.currentThread();
        resumeThreadCalls.incrementAndGet();
        arrived.countDown();
        if (!released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          throw new IllegalStateException("재개 스레드 놓아줌 대기 " + TIMEOUT_SECONDS + "초 초과");
        }
        return null;
      };
    }

    private boolean awaitArrival() throws InterruptedException {
      return arrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private boolean releaseAndJoin() throws InterruptedException {
      released.countDown();
      Thread t = resumeThread;
      if (t == null) {
        return false;
      }
      t.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
      return !t.isAlive();
    }

    private int resumeThreadCalls() {
      return resumeThreadCalls.get();
    }
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
  void resume_소유자_본인이_호출하면_성공한다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    SessionState session = new SessionState("sid", "/tmp/src", "/tmp/out");
    session.setUsername("owner");
    session.setPendingFilePaths(
        java.util.List.of(Files.createFile(pendingSourceDir.resolve("A.java")).toString()));
    // 재개 스레드의 첫 getSession을 붙잡아 둔다 — 아래 상태 단언이 재개 스레드의 세션 쓰기와
    // 겹치지 않는 것은 이 붙잡기 때문이다(대기 상한 10초).
    ResumeThreadGate gate = new ResumeThreadGate();
    when(sessionManager.getSession("sid")).thenAnswer(gate.answer(session));
    MainApiController controller = newController(sessionManager, mock(AnalysisHistoryRepository.class));

    Map<String, Object> response = controller.resumeSession(Map.of("sessionId", "sid"), authAs("owner"));

    assertEquals(true, response.get("success"));
    assertTrue(gate.awaitArrival(), "재개 스레드가 기동해 getSession에 도착하지 않았다(대기 상한 초과)");
    assertEquals("ANALYZING", session.getCurrentPhase(), "재개 스레드 기동 직전 ANALYZING으로 전이해야 함");
    assertTrue(gate.releaseAndJoin(), "놓아준 재개 스레드가 종료하지 않았다(대기 상한 초과 또는 생존)");
    assertEquals("ANALYZING", session.getCurrentPhase(), "재개 스레드가 끝난 뒤에도 세션에 아무것도 쓰지 않았어야 함");
    assertEquals(1, gate.resumeThreadCalls(), "재개 스레드가 첫 getSession에서 끝나지 않았다");
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
