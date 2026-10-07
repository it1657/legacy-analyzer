package com.legacy.analysis;

import com.legacy.analysis.llm.LlmModelOptionService;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TASK-004 (work-order 2026-10-counter-integrity-and-failure-reason v1/v2, REQ-001 B / 게이트1 D4) —
 * 분석이 <b>끝나는 모든 지점</b>에서 이력 행의 카운터가 세션 값과 같은지(불변식 I4) 확인한다.
 *
 * <p>고치려는 결함: 재개 루프({@code runAnalysisResume})의 <b>사용자 일시정지</b>·<b>전량실패</b> 분기는
 * {@code history.setStatus("PAUSED")}만 하고 카운터를 쓰지 않는다. 같은 파일의 취소 분기·크레딧 소진
 * 공통 처리·최초 분석 경로에는 카운터 4줄이 있다. 그래서 재개로 끝난 세션의 이력 행은 <b>재개 전 값</b>에
 * 머물고, 목록 화면이 상태 API와 다른 수를 보여준다.
 *
 * <p><b>전제 단언</b>: 재개 경로 케이스마다 "재개 전에 저장돼 있던 이력의 실패 수 ≠ 재개 종료 시점 세션의
 * 실패 수"를 먼저 본다. 두 값이 같으면 수정 전 코드도 우연히 GREEN이 되어 결함을 못 잡는다 —
 * 직전 TASK-003에서 실제로 그런 약한 단언을 만들었다가 지웠다(T1에서 재개 전 12 == 재개 후 12).
 *
 * <p><b>하네스</b>: 선례 {@code MainApiControllerTerminalStatusConsistencyTest} 구조에 TASK-003이 찾아낸
 * 충실도 교정 3건을 승계한다 — ① {@code initializeFileList} 목이 실제 구현처럼 세션 총계를 채운다
 * ② 이력 카운터는 nullable {@code Integer}라 목 안에서 언박싱하지 않는다(언박싱 NPE가 프로덕션 스레드를
 * 죽인다) ③ {@code findBySessionId}가 "마지막에 저장된 이력"을 돌려준다(최초 분석은 자기
 * {@code AnalysisHistory}를 새로 만들어 저장하므로, 고정 객체를 돌려주면 재개 경로가 빈 행을 읽는다).
 */
class ResumeHistoryCounterSyncTest {

  private static final String SID = "sid-history-counter-sync";
  private static final String USERNAME = "jhjung";
  private static final Long USER_SEQ = 80L;
  private static final String STUB_COMMENT_PREFIX = "// [AI 주석] 테스트 대역이 생성한 주석";
  private static final int FILE_COUNT = 6;

  /** 이력 저장 시점의 카운터 사본. */
  private record HistoryRow(String status, int total, int success, int skip, int failure) {
    @Override
    public String toString() {
      return "{status=" + status + " 총계=" + total + " 성공=" + success
          + " 건너뜀=" + skip + " 실패=" + failure + "}";
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
  private Authentication ownerAuth;

  private final List<HistoryRow> savedHistoryRows = new CopyOnWriteArrayList<>();
  private final AtomicInteger llmCalls = new AtomicInteger();
  private final AtomicInteger pauseOnCall = new AtomicInteger(0);
  private final AtomicInteger cancelOnCall = new AtomicInteger(0);
  private final AtomicReference<IntFunction<RuntimeException>> failurePlan =
      new AtomicReference<>(n -> null);
  private final AtomicReference<AnalysisHistory> persistedHistory = new AtomicReference<>();

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

    AnalysisHistory seed = new AnalysisHistory(USER_SEQ, SID, srcRoot.toString(), outRoot.toString());
    seed.setId(1L);
    persistedHistory.set(seed);
    // 승계 ③ — "DB 행"처럼 마지막에 저장된 이력을 돌려준다.
    when(analysisHistoryRepository.findBySessionId(SID)).thenAnswer(inv -> persistedHistory.get());
    when(analysisHistoryRepository.save(any(AnalysisHistory.class))).thenAnswer(inv -> {
      AnalysisHistory saved = inv.getArgument(0);
      persistedHistory.set(saved);
      // 승계 ② — nullable Integer를 목 안에서 언박싱하면 NPE가 프로덕션 스레드를 죽인다.
      savedHistoryRows.add(new HistoryRow(saved.getStatus(), nz(saved.getTotalFiles()),
          nz(saved.getSuccessCount()), nz(saved.getSkipCount()), nz(saved.getFailureCount())));
      return saved;
    });

    // 승계 ① — 목이 실제 구현처럼 세션 총계를 채운다(목으로 두면 총계가 0에 머문다).
    doAnswer(inv -> {
      int total = inv.getArgument(1);
      session.setTotalFiles(total);
      session.getStatistics().setTotalFiles(total);
      return null;
    }).when(sessionManager).initializeFileList(anyString(), anyInt());

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

  // ────────────────────────────────────────────────────────────────────────────
  // 재개 경로 5종 — 이 중 일시정지·전량실패가 이번에 채우는 두 곳이다
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void 재개_사용자_일시정지에서_이력_카운터가_세션과_같다() throws Exception {
    failurePlan.set(n -> ordinaryFailure());
    runAnalysisAndAwait();                       // 1회차: 전량실패 PAUSED(이력 실패 = 6)
    int failureInHistoryBeforeResume = lastHistory().failure();

    llmCalls.set(0);
    pauseOnCall.set(2);                          // 재개 도중 사용자 일시정지
    resumeAndAwait();

    assertResumeTookEffect("재개·사용자 일시정지", failureInHistoryBeforeResume);
    assertHistoryMatchesSession("재개·사용자 일시정지", "PAUSED");
  }

  @Test
  void 재개_전량실패에서_이력_카운터가_세션과_같다() throws Exception {
    failurePlan.set(n -> ordinaryFailure());
    pauseOnCall.set(2);
    runAnalysisAndAwait();                       // 1회차: 2건 실패 후 사용자 일시정지(이력 실패 = 2)
    int failureInHistoryBeforeResume = lastHistory().failure();

    pauseOnCall.set(0);
    llmCalls.set(0);
    resumeAndAwait();                            // 2회차: 전량실패(세션 실패 = 6)

    assertResumeTookEffect("재개·전량실패", failureInHistoryBeforeResume);
    assertHistoryMatchesSession("재개·전량실패", "PAUSED");
  }

  /** 양성 대조군 — 크레딧 소진 공통 처리에는 카운터 4줄이 이미 있다(수정 전후 모두 GREEN). */
  @Test
  void 재개_크레딧소진에서_이력_카운터가_세션과_같다() throws Exception {
    failurePlan.set(n -> ordinaryFailure());
    runAnalysisAndAwait();
    int failureInHistoryBeforeResume = lastHistory().failure();

    llmCalls.set(0);
    failurePlan.set(n -> n == 1 ? credits() : null);
    resumeAndAwait();

    assertResumeTookEffect("재개·크레딧 소진", failureInHistoryBeforeResume);
    assertHistoryMatchesSession("재개·크레딧 소진", "PAUSED");
  }

  /** 양성 대조군 — 취소 분기에는 카운터 4줄이 이미 있다(이번 변경의 선례). */
  @Test
  void 재개_취소에서_이력_카운터가_세션과_같다() throws Exception {
    failurePlan.set(n -> ordinaryFailure());
    runAnalysisAndAwait();
    int failureInHistoryBeforeResume = lastHistory().failure();

    llmCalls.set(0);
    failurePlan.set(n -> null);
    cancelOnCall.set(1);
    resumeAndAwait();

    assertResumeTookEffect("재개·취소", failureInHistoryBeforeResume);
    assertHistoryMatchesSession("재개·취소", "CANCELLED");
  }

  /** 양성 대조군 — 완료는 {@code finalizeAnalysis()}가 카운터를 쓴다(수정 전후 모두 GREEN). */
  @Test
  void 재개_완료에서_이력_카운터가_세션과_같다() throws Exception {
    failurePlan.set(n -> ordinaryFailure());
    pauseOnCall.set(2);
    runAnalysisAndAwait();
    int failureInHistoryBeforeResume = lastHistory().failure();

    pauseOnCall.set(0);
    llmCalls.set(0);
    failurePlan.set(n -> null);
    resumeAndAwait();

    assertResumeTookEffect("재개·완료", failureInHistoryBeforeResume);
    assertHistoryMatchesSession("재개·완료", "COMPLETED");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 최초 분석 경로 5종 — 회귀 가드. 수정 전후 모두 GREEN이어야 한다
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void 회귀_최초_사용자_일시정지에서_이력_카운터가_세션과_같다() throws Exception {
    failurePlan.set(n -> ordinaryFailure());
    pauseOnCall.set(2);
    runAnalysisAndAwait();
    assertHistoryMatchesSession("최초·사용자 일시정지", "PAUSED");
  }

  @Test
  void 회귀_최초_전량실패에서_이력_카운터가_세션과_같다() throws Exception {
    failurePlan.set(n -> ordinaryFailure());
    runAnalysisAndAwait();
    assertHistoryMatchesSession("최초·전량실패", "PAUSED");
  }

  @Test
  void 회귀_최초_크레딧소진에서_이력_카운터가_세션과_같다() throws Exception {
    failurePlan.set(n -> n == 1 ? credits() : null);
    runAnalysisAndAwait();
    assertHistoryMatchesSession("최초·크레딧 소진", "PAUSED");
  }

  @Test
  void 회귀_최초_취소에서_이력_카운터가_세션과_같다() throws Exception {
    cancelOnCall.set(1);
    runAnalysisAndAwait();
    assertHistoryMatchesSession("최초·취소", "CANCELLED");
  }

  @Test
  void 회귀_최초_완료에서_이력_카운터가_세션과_같다() throws Exception {
    failurePlan.set(n -> null);
    runAnalysisAndAwait();
    assertHistoryMatchesSession("최초·완료", "COMPLETED");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 판정
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * 전제 — 재개 전에 저장돼 있던 이력의 실패 수가 재개 종료 시점 세션의 실패 수와 <b>달라야</b> 한다.
   * 같으면 카운터를 쓰지 않는 수정 전 코드도 우연히 통과해 결함을 못 잡는다.
   */
  private void assertResumeTookEffect(String label, int failureInHistoryBeforeResume) {
    int sessionFailure = session.getStatistics().getFailureCount();
    System.out.println("[004 전제] " + label + " | 재개 전 이력 실패=" + failureInHistoryBeforeResume
        + " / 재개 후 세션 실패=" + sessionFailure);
    assertNotEquals(failureInHistoryBeforeResume, sessionFailure,
        label + ": 전제 불성립 — 재개 전 이력 실패 수와 재개 후 세션 실패 수가 같으면"
            + " 이 시나리오는 결함을 잡지 못한다(약한 단언). 시나리오 구성을 바꿔야 한다.");
  }

  /** I4 — 종단 저장 시점 이력 == 세션(success·skip·failure·totalFiles). */
  private void assertHistoryMatchesSession(String label, String expectedStatus) {
    HistoryRow last = lastHistory();
    int total = session.getTotalFiles();
    int success = session.getStatistics().getSuccessCount();
    int skip = session.getStatistics().getSkipCount();
    int failure = session.getStatistics().getFailureCount();

    System.out.println("[004] " + label + " | 이력=" + last
        + " | 세션={총계=" + total + " 성공=" + success + " 건너뜀=" + skip + " 실패=" + failure + "}"
        + " | 이력 저장 전수=" + savedHistoryRows);

    assertEquals(expectedStatus, last.status(), label + ": 이력 status");
    assertEquals(total, last.total(),
        label + " (I4): 이력 총계 == 세션 총계. 이력=" + last);
    assertEquals(success, last.success(),
        label + " (I4): 이력 성공 수 == 세션 성공 수. 이력=" + last);
    assertEquals(skip, last.skip(),
        label + " (I4): 이력 건너뜀 수 == 세션 건너뜀 수. 이력=" + last);
    assertEquals(failure, last.failure(),
        label + " (I4): 이력 실패 수 == 세션 실패 수. 이력=" + last);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 도우미
  // ────────────────────────────────────────────────────────────────────────────

  private static int nz(Integer value) {
    return value == null ? 0 : value;
  }

  private HistoryRow lastHistory() {
    assertFalse(savedHistoryRows.isEmpty(), "이력 save가 한 번도 없었다 — 하네스가 분기에 도달하지 못했다");
    return savedHistoryRows.get(savedHistoryRows.size() - 1);
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
        // 승계 — 스레드가 예외로 죽으면 phase가 FAILED다. 그 상태의 카운터는 판정 근거가 될 수 없다.
        assertNotEquals("FAILED", phase, "재개 스레드가 예외로 끝났다 — 카운터를 판정에 쓸 수 없다");
        return;
      }
      Thread.sleep(50);
    }
    fail("재개 스레드가 60초 안에 종료 상태에 도달하지 않았다. phase=" + session.getCurrentPhase());
  }
}
