package com.legacy.analysis;

import com.legacy.analysis.llm.LlmModelOption;
import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.analysis.llm.LlmProvider;
import com.legacy.auth.Role;
import com.legacy.auth.User;
import com.legacy.core.ApiErrorHandler;
import com.legacy.core.PresentationGeneratorService;
import com.legacy.rag.CodeContentRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TASK-005 (work-order 2026-10-resume-consistency-and-local-guard v1, REQ-001 ⑤ + 게이트1 D7) —
 * <b>종단 분기가 저장 직전에 세션 {@code status}를 이력 {@code status}와 같게 맞추는지</b>를
 * 실제 처리 루프로 확인한다.
 *
 * <p>고치려는 결함: `runAnalysis()`/`runAnalysisResume()`의 전량실패·취소 분기와 크레딧 소진
 * (failover 대상 없음) 분기는 {@code currentPhase}만 바꾸고 {@code status}를 쓰지 않았다.
 * {@code currentPhase}는 {@code @Transient}(메모리 전용)라 DB에 남지 않으므로, 세션 행은
 * {@code IN_PROGRESS}로 남고 이력은 {@code PAUSED}/{@code CANCELLED}가 되어 두 테이블이 어긋난다 —
 * 재시작 후 "영원히 진행 중"으로 보이는 원인이다.
 *
 * <p><b>하네스</b>: 선례 {@code MainApiControllerPauseSettledTerminalNormalizationContractTest}와 같은 구조 —
 * 실제 {@code @TempDir} 파일 + LLM 대역 + 리플렉션 기동, 스레드풀 1.
 * "DB 행"은 {@code saveSessionState()} 호출 <b>시점</b>의 분리 사본이다(호출 후 메모리 변경에 오염되지 않는다).
 *
 * <p><b>통계 무영향(§0.5 PL 지시)</b>: 분기마다 카운터 5종
 * ({@code successCount}/{@code failureCount}/{@code skipCount}/{@code processedFiles}/{@code totalFiles})을
 * 함께 출력해 {@code setStatus} 추가가 카운터를 바꾸지 않았음을 보인다.
 */
class MainApiControllerTerminalStatusConsistencyTest {

  private static final String SID = "sid-terminal-status";
  private static final String USERNAME = "jhjung";
  private static final Long USER_SEQ = 50L;
  private static final String STUB_COMMENT_PREFIX = "// [AI 주석] 테스트 대역이 생성한 주석";
  private static final int FILE_COUNT = 3;

  /** {@code saveSessionState()} 호출 시점에 뜬 "DB 행" 사본(영속 컬럼만 — currentPhase는 DB에 없다). */
  private record SavedRow(String status, Boolean pauseSettled, String pendingJson) {
    @Override
    public String toString() {
      return "{status=" + status + ", pauseSettled=" + pauseSettled + ", pendingJson=" + pendingJson + "}";
    }
  }

  @TempDir
  Path tempDir;

  private ClaudeService claudeService;
  private AnalysisSessionManager sessionManager;
  private AnalysisHistoryRepository analysisHistoryRepository;
  private LlmModelOptionService llmModelOptionService;
  private MainApiController controller;

  private Path srcRoot;
  private Path outRoot;
  private SessionState session;
  private AnalysisHistory history;
  private Authentication ownerAuth;

  private final List<SavedRow> savedRows = new CopyOnWriteArrayList<>();
  /**
   * 이력 {@code save()} 호출 시점의 {@code status} 사본. 최초 분석({@code runAnalysis})은
   * {@code findBySessionId}를 쓰지 않고 자기 {@code AnalysisHistory}를 새로 만들어 저장하므로,
   * 테스트가 들고 있는 {@code history} 필드로는 그 경로를 관측할 수 없다 — 저장 시점 스냅샷으로 본다.
   */
  private final List<String> savedHistoryStatuses = new CopyOnWriteArrayList<>();
  private final AtomicInteger llmCalls = new AtomicInteger();
  /** 몇 번째 LLM 호출에서 세션을 취소할지. 0이면 취소하지 않는다. */
  private final AtomicInteger cancelOnCall = new AtomicInteger(0);
  /** 몇 번째 LLM 호출에서 실제 pauseSession()을 부를지. 0이면 부르지 않는다. */
  private final AtomicInteger pauseOnCall = new AtomicInteger(0);
  /** 호출 번호(1부터) → 던질 예외. null이면 정상 응답. */
  private final AtomicReference<IntFunction<RuntimeException>> failurePlan = new AtomicReference<>(n -> null);

  @BeforeEach
  void setUp() throws Exception {
    srcRoot = tempDir.resolve("myproj");
    outRoot = tempDir.resolve("outroot");
    Files.createDirectories(srcRoot);
    Files.createDirectories(outRoot);

    claudeService = mock(ClaudeService.class);
    sessionManager = mock(AnalysisSessionManager.class);
    analysisHistoryRepository = mock(AnalysisHistoryRepository.class);
    llmModelOptionService = mock(LlmModelOptionService.class);
    CodeContentRagService codeContentRagService = mock(CodeContentRagService.class);

    SessionConfig sessionConfig = new SessionConfig();
    sessionConfig.setMaxRetries(0);
    RetryHandler retryHandler = new RetryHandler(new ApiErrorHandler(), sessionConfig);

    controller = new MainApiController(
        claudeService, null, sessionManager, null, null, retryHandler,
        analysisHistoryRepository, null, mock(com.legacy.notification.NotificationService.class),
        null, mock(PresentationGeneratorService.class), null,
        codeContentRagService, llmModelOptionService, null);
    setField("chunkingThresholdBytes", 153600L);
    setField("chunkSizeLines", 1000);
    setField("chunkOverlapLines", 100);
    setField("threadPoolSize", 1);
    setField("uploadStoragePath", tempDir.resolve(".uploads").toString());

    session = new SessionState(SID, srcRoot.toString(), outRoot.toString());
    session.setUsername(USERNAME);
    session.setUserId(USER_SEQ);
    session.setGenerateReadme(false);
    when(sessionManager.getSession(SID)).thenReturn(session);

    history = new AnalysisHistory(USER_SEQ, SID, srcRoot.toString(), outRoot.toString());
    history.setId(1L);
    when(analysisHistoryRepository.findBySessionId(SID)).thenReturn(history);
    when(analysisHistoryRepository.save(any(AnalysisHistory.class))).thenAnswer(inv -> {
      AnalysisHistory saved = inv.getArgument(0);
      savedHistoryStatuses.add(saved.getStatus());
      return saved;
    });

    doAnswer(inv -> {
      SessionState s = inv.getArgument(0);
      savedRows.add(new SavedRow(s.getStatus(), s.getPauseSettled(), s.getPendingFilePathsJson()));
      return null;
    }).when(sessionManager).saveSessionState(any(SessionState.class));

    when(llmModelOptionService.getActiveFailoverTarget()).thenReturn(Optional.empty());

    when(claudeService.analyzeCodeWithClaude(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> {
          int n = llmCalls.incrementAndGet();
          if (n == pauseOnCall.get()) {
            Map<String, Object> pauseResponse = controller.pauseSession(Map.of("sessionId", SID), ownerAuth);
            assertEquals(Boolean.TRUE, pauseResponse.get("success"), "pauseSession() 실패: " + pauseResponse);
          }
          if (n == cancelOnCall.get()) {
            session.cancel();
          }
          RuntimeException toThrow = failurePlan.get().apply(n);
          if (toThrow != null) throw toThrow;
          return STUB_COMMENT_PREFIX + "\n" + invocation.getArgument(0);
        });

    User owner = new User(USERNAME, USERNAME + "@example.com", "hash");
    owner.setSeq(USER_SEQ);
    owner.setRoles(Set.of(new Role("USER", "일반 사용자")));
    ownerAuth = new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities());

    for (int i = 1; i <= FILE_COUNT; i++) {
      Path file = srcRoot.resolve("com/x/F" + i + ".java");
      Files.createDirectories(file.getParent());
      Files.writeString(file, "public class F" + i + " { void m() {} }\n", StandardCharsets.UTF_8);
    }
  }

  private void setField(String name, Object value) throws Exception {
    Field field = MainApiController.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(controller, value);
  }

  private static RuntimeException credits() {
    return new AnalysisException(ApiErrorHandler.ErrorType.INSUFFICIENT_CREDITS,
        new RuntimeException("테스트 대역: credit balance is too low"));
  }

  private static RuntimeException ordinaryFailure() {
    return new RuntimeException("테스트 대역: Failed to resolve 'ollama'");
  }

  private void runAnalysisAndAwait() throws Exception {
    Method m = MainApiController.class.getDeclaredMethod("runAnalysis",
        String.class, String.class, String.class, boolean.class, Long.class, String.class,
        Set.class, boolean.class);
    m.setAccessible(true);
    // userId를 넘겨야 runAnalysis()가 AnalysisHistory를 실제로 만들어 저장한다(userId == null이면 이력 0건).
    m.invoke(controller, SID, srcRoot.toString(), outRoot.toString(), false, USER_SEQ, USERNAME, null, false);
  }

  @SuppressWarnings("unchecked")
  private void resumeAndAwait() throws Exception {
    Method m = MainApiController.class.getDeclaredMethod(
        "resumePendingFilesInThread", SessionState.class, String.class);
    m.setAccessible(true);
    Map<String, Object> response = (Map<String, Object>) m.invoke(controller, session, SID);
    assertEquals(Boolean.TRUE, response.get("success"), "재개 진입점 실패: " + response.get("message"));
    long deadline = System.currentTimeMillis() + 60_000L;
    while (System.currentTimeMillis() < deadline) {
      String phase = session.getCurrentPhase();
      if ("COMPLETED".equals(phase) || "PAUSED".equals(phase) || "FAILED".equals(phase)
          || "CANCELLED".equals(phase) || SessionState.STATUS_AWAITING_FAILOVER_CONFIRM.equals(phase)) {
        return;
      }
      Thread.sleep(50);
    }
    fail("재개 스레드가 60초 안에 종료 상태에 도달하지 않았다. phase=" + session.getCurrentPhase());
  }

  private SavedRow lastSaved() {
    assertFalse(savedRows.isEmpty(), "종단 저장이 한 번도 없었다 — 하네스가 분기에 도달하지 못했다");
    return savedRows.get(savedRows.size() - 1);
  }

  /** 이력 {@code save()}가 마지막으로 기록한 status(= 이력 테이블 행의 최종 값). */
  private String lastSavedHistoryStatus() {
    assertFalse(savedHistoryStatuses.isEmpty(), "이력 save가 한 번도 없었다 — 하네스가 분기에 도달하지 못했다");
    return savedHistoryStatuses.get(savedHistoryStatuses.size() - 1);
  }

  /** 카운터 5종(§0.5 통계 무영향) — 분기마다 출력해 setStatus 추가가 카운터를 바꾸지 않았음을 보인다. */
  private Map<String, Object> counters() {
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("successCount", session.getStatistics().getSuccessCount());
    c.put("failureCount", session.getStatistics().getFailureCount());
    c.put("skipCount", session.getStatistics().getSkipCount());
    c.put("processedFiles", session.getProcessedFiles());
    c.put("totalFiles", session.getTotalFiles());
    return c;
  }

  /** 한 분기의 결론을 한 줄로 찍는다 — 05-dev-progress.md 표의 원문이 된다. */
  private void report(String label) {
    System.out.println("[005] " + label
        + " | 마지막 저장 스냅샷=" + (savedRows.isEmpty() ? "(세션 저장 없음)" : lastSaved().toString())
        + " | 이력 저장 status=" + savedHistoryStatuses
        + " | 메모리 phase=" + session.getCurrentPhase()
        + " | 카운터=" + counters());
  }

  private void assertTerminalStatusMatchesHistory(String expected, String label) {
    SavedRow last = lastSaved();
    assertEquals(expected, last.status(),
        label + ": 마지막 저장 스냅샷의 status가 " + expected + "여야 한다(DB 행이 IN_PROGRESS로 남으면 이력과 어긋난다). 행="
            + last);
    assertEquals(expected, lastSavedHistoryStatus(), label + ": 이력 저장 status");
    assertEquals(last.status(), lastSavedHistoryStatus(),
        label + ": 세션 행 status == 이력 status (이것이 이 TASK의 핵심 단언)");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 전량실패 (최초 / 재개)
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void 전량실패_최초분석_마지막_저장_status는_PAUSED이고_이력과_같다() throws Exception {
    failurePlan.set(n -> ordinaryFailure());

    runAnalysisAndAwait();
    report("전량실패(최초)");

    assertTerminalStatusMatchesHistory("PAUSED", "전량실패(최초)");
    assertNull(lastSaved().pauseSettled(),
        "전량실패는 pauseSettled를 쓰지 않는다 — raw NULL 그대로여야 한다(설계값)");
    assertEquals("PAUSED", session.getCurrentPhase(), "phase는 기존대로 PAUSED");
    assertTrue(recentLogs().stream().anyMatch(l -> l.contains("[전체 실패]")),
        "전량실패 분기를 탔다는 증거(터미널 로그)가 없다: " + recentLogs());
    // 카운터 — 전량실패이므로 성공 0, 실패 = 파일 수
    assertEquals(0, session.getStatistics().getSuccessCount());
    assertEquals(FILE_COUNT, session.getStatistics().getFailureCount());
    assertEquals(0, session.getStatistics().getSkipCount());
  }

  @Test
  void 전량실패_재개_마지막_저장_status는_PAUSED이고_이력과_같다() throws Exception {
    // 1회차: 전량실패로 PAUSED + pending 확보
    failurePlan.set(n -> ordinaryFailure());
    runAnalysisAndAwait();
    assertEquals(FILE_COUNT, session.getPendingFilePaths().size(), "재개용 대기 목록이 확보돼야 한다");
    int savedBefore = savedRows.size();

    // 2회차: 재개했는데 또 전부 실패
    resumeAndAwait();
    report("전량실패(재개)");
    assertTrue(savedRows.size() > savedBefore, "재개 종단에서 추가 저장이 있어야 한다");

    assertTerminalStatusMatchesHistory("PAUSED", "전량실패(재개)");
    assertNull(lastSaved().pauseSettled(), "재개 전량실패도 raw NULL 그대로");
    assertEquals("PAUSED", session.getCurrentPhase());
    assertTrue(recentLogs().stream().anyMatch(l -> l.contains("[전체 실패] 재시도한")),
        "재개 전량실패 분기 로그가 없다: " + recentLogs());
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 크레딧 소진 — failover 대상 없음 (게이트1 D7)
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void 크레딧소진_failover없음_마지막_저장_status는_PAUSED이고_이력과_같다() throws Exception {
    when(llmModelOptionService.getActiveFailoverTarget()).thenReturn(Optional.empty());
    failurePlan.set(n -> n == 1 ? credits() : null);

    runAnalysisAndAwait();
    report("크레딧 소진(failover 없음)");

    assertTerminalStatusMatchesHistory("PAUSED", "크레딧 소진(failover 없음)");
    assertNull(lastSaved().pauseSettled(),
        "크레딧 소진은 pauseSettled를 쓰지 않는다 — raw NULL 그대로여야 한다(설계값)");
    assertEquals("PAUSED", session.getCurrentPhase());
    assertTrue(recentLogs().stream().anyMatch(l -> l.contains("[크레딧 소진 일시정지]")),
        "크레딧 소진(failover 없음) 분기 로그가 없다: " + recentLogs());
  }

  /**
   * 회귀 확인 — failover <b>있음</b> 분기는 이번 변경 대상이 아니다. 종전처럼
   * {@code status = AWAITING_FAILOVER_CONFIRM}로 저장된다(이력은 PAUSED가 정상 — 프런트 회귀 방지).
   */
  @Test
  void 회귀_크레딧소진_failover있음_분기는_착수_전과_같은_값으로_저장된다() throws Exception {
    when(llmModelOptionService.getActiveFailoverTarget())
        .thenReturn(Optional.of(new LlmModelOption("qwen3-32b", "자체 LLM", LlmProvider.LOCAL, 0)));
    failurePlan.set(n -> n == 1 ? credits() : null);

    runAnalysisAndAwait();
    report("회귀: 크레딧 소진(failover 있음)");

    assertEquals(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM, lastSaved().status(),
        "failover 있음 분기의 저장 status는 착수 전과 같아야 한다");
    assertEquals("PAUSED", lastSavedHistoryStatus(),
        "이력은 기존과 동일하게 PAUSED(프런트 회귀 방지 — 기존 계약)");
    assertNull(lastSaved().pauseSettled(), "raw NULL 그대로");
    assertEquals(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM, session.getCurrentPhase());
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 취소 (최초 / 재개)
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void 취소_최초분석_마지막_저장_status는_CANCELLED이고_이력과_같다() throws Exception {
    cancelOnCall.set(1);

    runAnalysisAndAwait();
    report("취소(최초)");

    assertTerminalStatusMatchesHistory("CANCELLED", "취소(최초)");
    assertEquals("CANCELLED", session.getCurrentPhase());
    assertNull(lastSaved().pauseSettled(),
        "취소는 pauseSettled를 쓰지 않는다 — 일시정지가 선행하지 않았으므로 raw NULL");
  }

  @Test
  void 취소_재개_마지막_저장_status는_CANCELLED이고_이력과_같다() throws Exception {
    // 1회차: 전량실패로 PAUSED + pending 확보
    failurePlan.set(n -> ordinaryFailure());
    runAnalysisAndAwait();
    assertEquals(FILE_COUNT, session.getPendingFilePaths().size());

    // 2회차: 재개 도중 취소
    failurePlan.set(n -> null);
    llmCalls.set(0);
    cancelOnCall.set(1);
    int savedBefore = savedRows.size();
    resumeAndAwait();
    report("취소(재개)");
    assertTrue(savedRows.size() > savedBefore, "재개 종단에서 추가 저장이 있어야 한다");

    assertTerminalStatusMatchesHistory("CANCELLED", "취소(재개)");
    assertEquals("CANCELLED", session.getCurrentPhase());
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 회귀 — 사용자 일시정지 확정 경로는 변경 대상이 아니다 (RG-1)
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * 일시정지 확정 경로({@code pauseDetected} 블록)는 이번 변경 대상이 아니다. 이 경로는
   * {@code pauseSession()}이 이미 {@code status="PAUSED"}를 쓴 뒤 들어오므로 저장 스냅샷의
   * {@code status}는 종전과 같이 PAUSED이고, {@code pauseSettled}는 그 블록이 TRUE로 확정한다.
   */
  @Test
  void 회귀_사용자_일시정지_확정_경로는_착수_전과_같은_값으로_저장된다() throws Exception {
    pauseOnCall.set(1);   // 1번째 파일 처리 도중 일시정지 → 2·3번째 태스크가 shouldStop으로 조기 반환

    runAnalysisAndAwait();
    report("회귀: 사용자 일시정지 확정");

    SavedRow last = lastSaved();
    assertEquals("PAUSED", last.status(), "일시정지 확정 경로의 저장 status는 종전대로 PAUSED");
    assertEquals(Boolean.TRUE, last.pauseSettled(),
        "확정 경로는 저장 전에 pauseSettled=TRUE를 쓴다(기존 계약 — 이번 변경이 건드리지 않았다)");
    assertEquals("PAUSED", lastSavedHistoryStatus());
    assertEquals("PAUSED", session.getCurrentPhase());
    assertNotNull(last.pendingJson(), "확정 경로는 대기 목록을 저장한다");
    assertTrue(recentLogs().stream().anyMatch(l -> l.contains("[일시정지 완료]")),
        "일시정지 확정 분기 로그가 없다: " + recentLogs());
  }

  /** 정상 완료 경로도 변경 대상이 아니다 — 저장 스냅샷에 PAUSED/CANCELLED가 섞이지 않는다. */
  @Test
  void 회귀_정상_완료_경로는_종단_분기를_타지_않는다() throws Exception {
    failurePlan.set(n -> null);

    runAnalysisAndAwait();
    report("회귀: 정상 완료");

    // 정상 완료 경로는 종단 분기를 타지 않으므로 saveSessionState 스냅샷이 없다
    // (저장은 finalizeAnalysis 안의 completeSession이 맡는다 — 이 테스트에서는 목이라 no-op).
    assertEquals(FILE_COUNT, session.getStatistics().getSuccessCount(), "3개 모두 성공해야 한다");
    assertEquals(0, session.getStatistics().getFailureCount());
    for (SavedRow row : savedRows) {
      assertFalse("CANCELLED".equals(row.status()),
          "정상 완료 경로에 취소 저장이 섞였다: " + row);
    }
    assertTrue(recentLogs().stream().noneMatch(l -> l.contains("[전체 실패]")),
        "정상 완료인데 전량실패 로그가 있다: " + recentLogs());
    assertTrue(recentLogs().stream().noneMatch(l -> l.contains("[크레딧 소진")),
        "정상 완료인데 크레딧 소진 로그가 있다: " + recentLogs());
  }

  private List<String> recentLogs() {
    return session.getRecentLogLines(1000);
  }
}
