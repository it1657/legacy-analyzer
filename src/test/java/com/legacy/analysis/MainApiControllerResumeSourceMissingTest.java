package com.legacy.analysis;

import com.legacy.auth.Role;
import com.legacy.auth.User;
import com.legacy.rag.CodeContentRagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TASK-002 (work-order 2026-10-resume-consistency-and-local-guard v1, REQ-004 / 게이트1 D1·D2) —
 * <b>원본이 사라진 세션의 재개를 거부</b>하고, 일부만 사라진 세션은 남은 파일로 이어서 처리한다.
 *
 * <p>고치려는 결함: 업로드 원본은 자동 정리기가 4시간 뒤 지운다. 그 뒤 "이어서 분석"을 누르면
 * 종전 코드는 없는 파일을 조용히 버리고 0개 파일로 재개해, 세션·이력이 완료로 바뀌거나 대기 목록이
 * 빈 목록으로 덮어써졌다 — 분석 기록이 영구 손상됐다.
 *
 * <p>"파일이 없다"는 상태는 <b>실제로 파일을 만들지 않거나 지워서</b> 만든다({@code @TempDir}).
 * 가짜 경로 문자열로 때우면 경로 해석 쪽 버그를 잡을 수 없다.
 */
class MainApiControllerResumeSourceMissingTest {

  @TempDir
  Path dir;

  private MainApiController newController(ClaudeService claudeService,
      AnalysisSessionManager sessionManager, AnalysisHistoryRepository historyRepository,
      CodeContentRagService codeContentRagService) {
    return new MainApiController(
        claudeService, null, sessionManager, null, null, null,
        historyRepository, null, null, null, null, null, codeContentRagService, null, null);
  }

  private Authentication authAs(String loginId) {
    User user = new User(loginId, loginId + "@example.com", "hash");
    user.setSeq(1L);
    user.setRoles(Set.of(new Role("USER", "일반 사용자")));
    return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
  }

  private SessionState pausedSession(List<String> pending) {
    SessionState session = new SessionState("sid", dir.toString(), dir.resolve("out").toString());
    session.setUsername("owner");
    session.setStatus("PAUSED");
    session.setCurrentPhase("PAUSED");
    session.setPauseSettled(Boolean.TRUE);
    session.setPausedAt(LocalDateTime.of(2026, 10, 1, 9, 0));
    session.setPendingFilePaths(pending);
    return session;
  }

  private String real(String name) throws Exception {
    return Files.createFile(dir.resolve(name)).toString();
  }

  private String absent(String name) {
    return dir.resolve(name).toString();
  }

  /** 엔티티 필드의 raw 값을 리플렉션으로 읽는다(raw getter를 새로 만들지 않는 기존 관례). */
  private static Object rawField(Object target, String name) throws Exception {
    Field f = SessionState.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> confirmFailover(MainApiController controller, Map<String, String> request,
      Authentication authentication) throws Exception {
    Method m = MainApiController.class.getDeclaredMethod("confirmFailover", Map.class, Authentication.class);
    m.setAccessible(true);
    return (Map<String, Object>) m.invoke(controller, request, authentication);
  }

  private void runAnalysisResume(MainApiController controller, String sessionId, List<Path> fileList)
      throws Exception {
    Method m = MainApiController.class.getDeclaredMethod("runAnalysisResume", String.class, List.class);
    m.setAccessible(true);
    m.invoke(controller, sessionId, fileList);
  }

  // ===================================================================
  // ⓐ MISSING — 거부하고 아무것도 건드리지 않는다
  // ===================================================================

  @Test
  void 케이스A_대기파일이_전부_사라졌으면_재개를_거부하고_세션을_1비트도_바꾸지_않는다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    ClaudeService claudeService = mock(ClaudeService.class);
    AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);
    SessionState session = pausedSession(List.of(absent("A.java"), absent("B.java"), absent("C.java")));
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller =
        newController(claudeService, sessionManager, historyRepository, mock(CodeContentRagService.class));

    // 호출 전 스냅샷
    String statusBefore = session.getStatus();
    String phaseBefore = session.getCurrentPhase();
    String pendingJsonBefore = session.getPendingFilePathsJson();
    LocalDateTime pausedAtBefore = session.getPausedAt();
    LocalDateTime resumedAtBefore = session.getResumedAt();
    Object pauseSettledBefore = rawField(session, "pauseSettled");

    Map<String, Object> response = controller.resumeSession(Map.of("sessionId", "sid"), authAs("owner"));

    System.out.println("[ⓐ MISSING] response=" + response);
    assertEquals(false, response.get("success"));
    assertEquals("SOURCE_MISSING", response.get("reason"));
    assertEquals(3, response.get("pendingCount"));
    assertEquals(3, response.get("missingCount"));
    String message = (String) response.get("message");
    assertTrue(message.contains("원본 소실 — 재개 불가"), "문구에 '원본 소실 — 재개 불가'가 있어야 한다: " + message);
    assertTrue(message.contains("삭제되지 않고"), "기록이 보존된다는 안내가 있어야 한다: " + message);
    assertTrue(message.contains("처음부터 새로 분석"), "새로 분석하라는 안내가 있어야 한다: " + message);
    // C10 — 이 문구는 화면 alert에 그대로 실린다. 내부 경로·세션ID·예외 문자열이 섞이면 안 된다.
    assertFalse(message.contains(dir.toString()), "message에 내부 경로가 들어가면 안 된다: " + message);
    assertFalse(message.contains("sid"), "message에 sessionId가 들어가면 안 된다: " + message);
    assertFalse(message.contains("Exception"), "message에 예외 문자열이 들어가면 안 된다: " + message);

    // 세션은 호출 전후 동일
    assertEquals(statusBefore, session.getStatus());
    assertEquals(phaseBefore, session.getCurrentPhase());
    assertEquals(pendingJsonBefore, session.getPendingFilePathsJson(), "대기 목록이 비워지면 기록이 손상된다");
    assertEquals(pausedAtBefore, session.getPausedAt());
    assertEquals(resumedAtBefore, session.getResumedAt());
    assertNull(session.getResumedAt(), "거부된 재개는 resumedAt을 쓰지 않는다");
    assertEquals(pauseSettledBefore, rawField(session, "pauseSettled"));
    assertEquals(0, session.getStatistics().getFailureCount(), "MISSING은 실패 카운터를 올리지 않는다(거부일 뿐)");
    assertTrue(session.getFailedFilePaths().isEmpty());

    // 저장·이력·재개 스레드(LLM 대역) 모두 0회
    verify(sessionManager, never()).saveSessionState(any());
    verify(sessionManager, never()).completeSession(anyString());
    verify(historyRepository, never()).save(any());
    Thread.sleep(150);   // 스레드가 떴다면 이 사이에 LLM 대역을 불렀을 시간
    verify(claudeService, never()).generateSessionClaudeMd(any(), any(), anyString());
    verify(claudeService, never()).setSessionSystemPrompt(anyString(), anyString());
  }

  // ===================================================================
  // ⓑ PARTIAL — 남은 파일로 이어서, 사라진 파일은 실패로 기록
  // ===================================================================

  @Test
  void 케이스B_대기파일이_일부만_사라졌으면_남은_파일로_재개하고_사라진_파일을_실패로_기록한다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    ClaudeService claudeService = mock(ClaudeService.class);
    AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);
    String keep1 = real("Keep1.java");
    String goneA = absent("GoneA.java");
    String keep2 = real("Keep2.java");
    String goneB = absent("GoneB.java");
    SessionState session = pausedSession(List.of(keep1, goneA, keep2, goneB));
    when(sessionManager.getSession("sid")).thenReturn(session);

    CountDownLatch threadStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(claudeService.generateSessionClaudeMd(any(), any(), anyString())).thenAnswer(inv -> {
      threadStarted.countDown();
      release.await(5, TimeUnit.SECONDS);
      return "claude-md";
    });
    MainApiController controller =
        newController(claudeService, sessionManager, historyRepository, mock(CodeContentRagService.class));

    int processedBefore = session.getProcessedFiles();
    Map<String, Object> response = controller.resumeSession(Map.of("sessionId", "sid"), authAs("owner"));

    System.out.println("[ⓑ PARTIAL] response=" + response);
    assertEquals(true, response.get("success"));
    assertEquals(2, response.get("pendingCount"), "재개 대상은 남아 있는 2개");
    assertEquals(2, response.get("missingCount"));
    assertTrue(((String) response.get("message")).contains("제외: 2개"),
        "응답 문구에 제외 개수가 있어야 한다: " + response.get("message"));

    // 사라진 파일은 실패로 기록된다(원문 경로 그대로).
    assertEquals(Set.of(goneA, goneB), session.getFailedFilePaths());
    assertEquals(2, session.getStatistics().getFailureCount());
    // C4 — processedFiles/totalFiles는 건드리지 않는다.
    assertEquals(processedBefore, session.getProcessedFiles(), "processedFiles는 바뀌지 않는다");

    // 터미널 안내 1줄
    List<String> logs = session.getRecentLogLines(50);
    System.out.println("[ⓑ PARTIAL] recentLogs=" + logs);
    assertEquals(1, logs.stream().filter(l -> l.startsWith("[재개] 원본 소실로 제외:")).count(),
        "'[재개] 원본 소실로 제외' 안내가 정확히 1줄이어야 한다: " + logs);

    // 재개 스레드가 실제로 떴고, 그 시점의 세션 상태는 ANALYZING/IN_PROGRESS다.
    assertTrue(threadStarted.await(5, TimeUnit.SECONDS), "재개 스레드가 기동해야 한다");
    assertEquals("ANALYZING", session.getCurrentPhase());
    assertEquals("IN_PROGRESS", session.getStatus());
    release.countDown();
  }

  // ===================================================================
  // ⓒ AVAILABLE — 기존 동작 그대로 (양성 대조군)
  // ===================================================================

  @Test
  void 케이스C_대기파일이_전부_있으면_기존과_같이_상태를_바꾸고_스레드를_기동한다_양성대조군() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    ClaudeService claudeService = mock(ClaudeService.class);
    AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);
    SessionState session = pausedSession(List.of(real("A.java"), real("B.java")));
    when(sessionManager.getSession("sid")).thenReturn(session);

    CountDownLatch threadStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(claudeService.generateSessionClaudeMd(any(), any(), anyString())).thenAnswer(inv -> {
      threadStarted.countDown();
      release.await(5, TimeUnit.SECONDS);
      return "claude-md";
    });
    MainApiController controller =
        newController(claudeService, sessionManager, historyRepository, mock(CodeContentRagService.class));

    Map<String, Object> response = controller.resumeSession(Map.of("sessionId", "sid"), authAs("owner"));

    System.out.println("[ⓒ AVAILABLE] response=" + response);
    assertEquals(true, response.get("success"));
    assertEquals(2, response.get("pendingCount"));
    assertNull(response.get("reason"), "정상 경로에는 reason이 없다");
    assertNull(response.get("missingCount"), "전부 있으면 missingCount 필드를 붙이지 않는다(기존 응답 모양 보존)");
    assertEquals("분석을 재개합니다. (남은 파일: 2개)", response.get("message"),
        "AVAILABLE 문구는 착수 커밋과 글자 그대로 같아야 한다");

    assertTrue(threadStarted.await(5, TimeUnit.SECONDS), "재개 스레드가 기동해야 한다(대조군)");
    assertEquals("ANALYZING", session.getCurrentPhase());
    assertEquals("IN_PROGRESS", session.getStatus());
    assertNotNull(session.getResumedAt());
    assertNull(session.getPausedAt());
    assertTrue(session.getPendingFilePaths().isEmpty(), "재개 시작 시 대기 목록은 비워진다(기존 동작)");
    assertEquals(0, session.getStatistics().getFailureCount(), "AVAILABLE은 실패 카운터를 올리지 않는다");
    release.countDown();
  }

  // ===================================================================
  // ⓓ confirmFailover + MISSING — 모델 전환 전에 거부
  // ===================================================================

  @Test
  void 케이스D_failover_컨펌도_원본이_없으면_모델전환_없이_거부한다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    ClaudeService claudeService = mock(ClaudeService.class);
    SessionState session = pausedSession(List.of(absent("A.java"), absent("B.java")));
    session.setCurrentPhase(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM);
    session.setStatus(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM);
    session.setFailoverModelKey("qwen3-32b");
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(
        claudeService, sessionManager, mock(AnalysisHistoryRepository.class), mock(CodeContentRagService.class));

    Map<String, Object> response =
        confirmFailover(controller, Map.of("sessionId", "sid"), authAs("owner"));

    System.out.println("[ⓓ confirmFailover MISSING] response=" + response);
    assertEquals(false, response.get("success"));
    assertEquals("SOURCE_MISSING", response.get("reason"));
    assertEquals(2, response.get("pendingCount"));
    assertEquals(2, response.get("missingCount"));
    assertTrue(((String) response.get("message")).contains("원본 소실 — 재개 불가"));

    verify(claudeService, never()).setModel(anyString(), anyString());
    assertNull(session.getFailoverConfirmedAt(), "거부된 컨펌은 컨펌 시각을 남기지 않는다");
    assertEquals(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM, session.getCurrentPhase());
    assertEquals(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM, session.getStatus());
    verify(sessionManager, never()).saveSessionState(any());
  }

  /** ⓓ 대조군 — 같은 컨펌 경로에서 원본이 실제로 있으면 모델 전환·재개가 일어난다. */
  @Test
  void 케이스D대조군_failover_컨펌에_원본이_있으면_모델을_전환하고_재개한다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    ClaudeService claudeService = mock(ClaudeService.class);
    SessionState session = pausedSession(List.of(real("A.java")));
    session.setCurrentPhase(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM);
    session.setFailoverModelKey("qwen3-32b");
    when(sessionManager.getSession("sid")).thenReturn(session);

    CountDownLatch threadStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(claudeService.generateSessionClaudeMd(any(), any(), anyString())).thenAnswer(inv -> {
      threadStarted.countDown();
      release.await(5, TimeUnit.SECONDS);
      return "claude-md";
    });
    MainApiController controller = newController(
        claudeService, sessionManager, mock(AnalysisHistoryRepository.class), mock(CodeContentRagService.class));

    Map<String, Object> response =
        confirmFailover(controller, Map.of("sessionId", "sid"), authAs("owner"));

    System.out.println("[ⓓ대조군 AVAILABLE] response=" + response);
    assertEquals(true, response.get("success"));
    assertEquals("qwen3-32b", response.get("failoverModelKey"));
    verify(claudeService).setModel(Path.of(session.getSourcePath()).toString(), "qwen3-32b");
    assertNotNull(session.getFailoverConfirmedAt());
    assertTrue(threadStarted.await(5, TimeUnit.SECONDS));
    release.countDown();
  }

  // ===================================================================
  // ⓔ 2차 안전장치 — 빈 목록으로 재개 루프에 들어와도 완료 처리하지 않는다
  // ===================================================================

  @Test
  void 케이스E_재개_루프에_빈_목록이_들어오면_완료처리_없이_PAUSED로_되돌린다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);
    ClaudeService claudeService = mock(ClaudeService.class);
    // 세션의 대기 목록은 DB에 그대로 남아 있는 상태를 모사한다(resumePendingFilesInThread가 이미
    // 메모리 목록을 비웠더라도, DB 행을 덮어쓰지 않는지가 핵심이다).
    SessionState session = pausedSession(List.of(absent("A.java"), absent("B.java")));
    session.setStatus("IN_PROGRESS");
    session.setCurrentPhase("ANALYZING");
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(
        claudeService, sessionManager, historyRepository, mock(CodeContentRagService.class));

    String pendingJsonBefore = session.getPendingFilePathsJson();

    runAnalysisResume(controller, "sid", List.of());

    System.out.println("[ⓔ 2차 안전장치] status=" + session.getStatus()
        + ", phase=" + session.getCurrentPhase()
        + ", pendingJson=" + session.getPendingFilePathsJson());
    assertEquals("PAUSED", session.getStatus());
    assertEquals("PAUSED", session.getCurrentPhase());
    assertEquals(pendingJsonBefore, session.getPendingFilePathsJson(),
        "2차 안전장치가 대기 목록을 비우면 스스로 기록을 손상시킨다");

    verify(sessionManager, never()).completeSession(anyString());
    verify(sessionManager, never()).saveSessionState(any());
    verify(sessionManager, never()).failSession(anyString(), anyString());
    verify(historyRepository, never()).save(any());
    verify(historyRepository, never()).findBySessionId(anyString());
    verify(claudeService, never()).generateSessionClaudeMd(any(), any(), anyString());

    List<String> logs = session.getRecentLogLines(50);
    assertEquals(1, logs.stream().filter(l -> l.startsWith("[재개 중단]")).count(),
        "'[재개 중단]' 안내가 1줄 남아야 한다: " + logs);
  }

  /** ⓔ 대조군 — 같은 리플렉션 호출에 파일 1개를 주면 2차 안전장치를 통과해 재개 본문으로 들어간다. */
  @Test
  void 케이스E대조군_재개_루프에_파일이_있으면_2차_안전장치를_통과한다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);
    ClaudeService claudeService = mock(ClaudeService.class);
    SessionState session = pausedSession(List.of());
    session.setStatus("IN_PROGRESS");
    session.setCurrentPhase("ANALYZING");
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(
        claudeService, sessionManager, historyRepository, mock(CodeContentRagService.class));

    runAnalysisResume(controller, "sid", List.of(Path.of(real("A.java"))));

    // 2차 안전장치를 통과했다는 증거: 이력 조회가 실제로 일어났다(안전장치는 이력 조회 전에 return한다).
    verify(historyRepository).findBySessionId("sid");
    verify(claudeService).generateSessionClaudeMd(any(), any(), anyString());
    System.out.println("[ⓔ대조군] 이력 조회·CLAUDE.md 생성이 호출됨 → 안전장치를 통과했다. phase="
        + session.getCurrentPhase());
  }

  @Test
  void 케이스E_fileList가_null이어도_NPE없이_PAUSED로_되돌린다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    SessionState session = pausedSession(List.of(absent("A.java")));
    session.setStatus("IN_PROGRESS");
    session.setCurrentPhase("ANALYZING");
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(
        mock(ClaudeService.class), sessionManager, mock(AnalysisHistoryRepository.class),
        mock(CodeContentRagService.class));

    runAnalysisResume(controller, "sid", null);

    assertEquals("PAUSED", session.getStatus());
    assertEquals("PAUSED", session.getCurrentPhase());
    verify(sessionManager, never()).saveSessionState(any());
  }
}
