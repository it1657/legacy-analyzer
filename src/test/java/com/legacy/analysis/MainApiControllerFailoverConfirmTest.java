package com.legacy.analysis;

import com.legacy.analysis.llm.LlmModelOption;
import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.analysis.llm.LlmProvider;
import com.legacy.auth.Role;
import com.legacy.auth.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.mockito.stubbing.Answer;

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

  /**
   * 재개 성공을 단언하는 테스트의 <b>대기 경로 픽스처용 실제 파일</b>을 둘 디렉터리.
   * 2026-10 REQ-004로 재개·failover 컨펌이 원본 존재를 확인하게 됐으므로, 존재하지 않는 경로
   * ("/tmp/src/A.java")로는 재개가 정상 거부된다. 세션 {@code sourcePath}와 단언은 그대로 두고
   * 대기 경로만 실제 파일로 바꾼다.
   */
  @TempDir
  Path pendingSourceDir;

  private MainApiController newController(ClaudeService claudeService,
      AnalysisSessionManager sessionManager, AnalysisHistoryRepository analysisHistoryRepository,
      LlmModelOptionService llmModelOptionService) throws Exception {
    return new MainApiController(
        claudeService, null, sessionManager, null, null, null,
        analysisHistoryRepository, null, null, null, null, null, null, llmModelOptionService, null);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> confirmFailover(MainApiController controller, Map<String, String> request)
      throws Exception {
    return confirmFailover(controller, request, ownerAuthentication("owner"));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> confirmFailover(MainApiController controller, Map<String, String> request,
      Authentication authentication) throws Exception {
    Method m = MainApiController.class.getDeclaredMethod("confirmFailover", Map.class, Authentication.class);
    m.setAccessible(true);
    return (Map<String, Object>) m.invoke(controller, request, authentication);
  }

  /**
   * 세션 소유자 검증(2026-08-21 버그 수정)용 기본 인증 객체 — 로그인ID "owner".
   * 기존 confirmFailover 테스트들은 세션 소유자 검증 이전부터 있던 것들이라, 이 헬퍼가 만들어주는 "owner"
   * 인증을 그대로 쓰고 각 테스트의 SessionState에 {@code setUsername("owner")}만 맞춰주면 그대로 통과한다.
   */
  private Authentication ownerAuthentication(String loginId) {
    User user = new User(loginId, loginId + "@example.com", "hash");
    user.setSeq(1L);
    user.setRoles(Set.of(new Role("USER", "일반 사용자")));
    return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
  }

  private void handleCreditExhaustedPause(MainApiController controller, SessionState session,
      AnalysisHistory history, List<String> pendingPaths, int completedCount) throws Exception {
    Method m = MainApiController.class.getDeclaredMethod(
        "handleCreditExhaustedPause", SessionState.class, AnalysisHistory.class, List.class, int.class);
    m.setAccessible(true);
    m.invoke(controller, session, history, pendingPaths, completedCount);
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
    session.setUsername("owner");
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
    session.setUsername("owner");
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
    session.setUsername("owner");
    session.setCurrentPhase(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM);
    session.setFailoverModelKey("qwen3-32b");
    Path realA = Files.createFile(pendingSourceDir.resolve("A.java"));
    Path realB = Files.createFile(pendingSourceDir.resolve("B.java"));
    session.setPendingFilePaths(List.of(realA.toString(), realB.toString()));
    // 재개 스레드의 첫 getSession을 붙잡아 둔다 — 아래 상태 단언이 재개 스레드의 세션 쓰기와
    // 겹치지 않는 것은 이 붙잡기 때문이다(대기 상한 10초).
    ResumeThreadGate gate = new ResumeThreadGate();
    when(sessionManager.getSession("sid")).thenAnswer(gate.answer(session));
    MainApiController controller = newController(claudeService, sessionManager, null, null);

    Map<String, Object> response = confirmFailover(controller, Map.of("sessionId", "sid"));

    assertEquals(true, response.get("success"));
    assertEquals("sid", response.get("sessionId"));
    assertEquals("qwen3-32b", response.get("failoverModelKey"));
    assertNotNull(session.getFailoverConfirmedAt(), "컨펌 시각이 기록돼야 함");
    assertTrue(gate.awaitArrival(), "재개 스레드가 기동해 getSession에 도착하지 않았다(대기 상한 초과)");
    assertEquals("ANALYZING", session.getCurrentPhase(), "기존 resume과 동일하게 재개 스레드 기동 직전 ANALYZING으로 전이해야 함");
    assertEquals("IN_PROGRESS", session.getStatus());
    assertTrue(gate.releaseAndJoin(), "놓아준 재개 스레드가 종료하지 않았다(대기 상한 초과 또는 생존)");
    assertEquals("ANALYZING", session.getCurrentPhase(), "재개 스레드가 끝난 뒤에도 세션에 아무것도 쓰지 않았어야 함");
    assertEquals(1, gate.resumeThreadCalls(), "재개 스레드가 첫 getSession에서 끝나지 않았다");
    verify(claudeService, times(1)).setModel(eq(Path.of("/tmp/src").toString()), eq("qwen3-32b"));
  }

  @Test
  void 재개할_pending_파일이_없으면_모델전환없이_실패한다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    ClaudeService claudeService = mock(ClaudeService.class);
    SessionState session = new SessionState("sid", "/tmp/src", "/tmp/out");
    session.setUsername("owner");
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

  // ===================================================================
  // 세션 소유자 검증 (2026-08-21 버그 수정 — bug-suspects.md)
  // ===================================================================

  @Test
  void 세션_소유자가_아니면_거부되고_모델전환없이_실패한다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    ClaudeService claudeService = mock(ClaudeService.class);
    SessionState session = new SessionState("sid", "/tmp/src", "/tmp/out");
    session.setUsername("owner");
    session.setCurrentPhase(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM);
    session.setFailoverModelKey("qwen3-32b");
    session.setPendingFilePaths(List.of("/tmp/src/A.java"));
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(claudeService, sessionManager, null, null);

    Map<String, Object> response = confirmFailover(controller, Map.of("sessionId", "sid"), ownerAuthentication("attacker"));

    assertEquals(false, response.get("success"));
    assertTrue(((String) response.get("message")).contains("본인 세션만"));
    verify(claudeService, never()).setModel(anyString(), anyString());
    assertNull(session.getFailoverConfirmedAt(), "소유자가 아니면 모델 전환/컨펌 시각 기록 같은 부작용이 없어야 함");
    assertEquals(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM, session.getCurrentPhase(),
        "권한이 없으면 세션 상태를 건드리면 안 된다");
  }

  @Test
  void ADMIN은_다른_사용자의_세션도_컨펌할_수_있다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    ClaudeService claudeService = mock(ClaudeService.class);
    SessionState session = new SessionState("sid", "/tmp/src", "/tmp/out");
    session.setUsername("owner");
    session.setCurrentPhase(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM);
    session.setFailoverModelKey("qwen3-32b");
    session.setPendingFilePaths(List.of(Files.createFile(pendingSourceDir.resolve("A.java")).toString()));
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(claudeService, sessionManager, null, null);

    User admin = new User("admin", "admin@example.com", "hash");
    admin.setSeq(2L);
    admin.setRoles(Set.of(new Role("ADMIN", "관리자")));
    Authentication adminAuth = new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities());

    Map<String, Object> response = confirmFailover(controller, Map.of("sessionId", "sid"), adminAuth);

    assertEquals(true, response.get("success"));
    verify(claudeService, times(1)).setModel(eq(Path.of("/tmp/src").toString()), eq("qwen3-32b"));
  }
}
