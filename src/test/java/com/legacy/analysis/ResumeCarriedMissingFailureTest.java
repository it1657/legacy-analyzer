package com.legacy.analysis;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.auth.Role;
import com.legacy.auth.User;
import com.legacy.core.ApiErrorHandler;
import com.legacy.core.PresentationGeneratorService;
import com.legacy.rag.CodeContentRagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TASK-010 (work-order 2026-10-counter-integrity-and-failure-reason v3, REQ-001 A 보완 / D1′) —
 * <b>이전 재개에서 원본 소실로 확정된 실패</b>가 그다음 재개의 재기준 뒤에도 실패로 남는지 확인한다.
 *
 * <p>고치려는 결함(v3 §0.10, PL 설계 자기 정정): 재기준은 실패 수를 0으로 되돌린다. 그 근거는
 * "지난번 실패는 전부 대기 목록 안에 있으므로 다시 시도되면서 다시 세진다"였는데, <b>PARTIAL 재개에서
 * 소실로 확정된 파일은 실패 목록에는 남고 대기 목록에서는 빠진다</b>(재개 대상이
 * {@code availability.existing()}뿐이고 종료 시 대기 목록이 그 목록에서 만들어지기 때문 — I7′).
 * 그래서 PARTIAL 재개를 거친 세션을 <b>한 번 더</b> 재개하면 그 실패가 사라진다. 처리 수는 맞다
 * (재기준이 "총계 − 대기 수"에 이미 넣어 뒀다) — <b>틀리는 것은 실패 수와 사유 집계뿐</b>이다.
 *
 * <p><b>하네스</b>: {@code ResumeCounterInvariantTest}의 충실도 교정 3건과 G-21 장치를 승계하고,
 * 이 TASK에 특유한 함정을 하나 더 막는다 — 이월 블록은 <b>{@code rebase.applied()}가 참일 때만</b>
 * 돌기 때문에 "재기준이 적용되지 않아 아무 일도 안 일어났는데 통과"가 구조적으로 가능하다. 그래서
 * 재개 단계마다 서버 로그에서 <b>{@code [재개 카운터 재기준]} 1건 / {@code [재개 카운터 재기준 생략]}
 * 0건</b>을 단언해 "재기준이 실제로 적용됐다"를 양성 지표로 쓴다(T10은 반대 방향으로 단언한다).
 */
class ResumeCarriedMissingFailureTest {

  private static final String SID = "sid-carried-missing";
  private static final String USERNAME = "jhjung";
  private static final Long USER_SEQ = 110L;
  private static final String STUB_COMMENT_PREFIX = "// [AI 주석] 테스트 대역이 생성한 주석";
  private static final int FILE_COUNT = 12;
  private static final int MISSING_COUNT = 5;

  /** TASK-003이 남기는 재기준 로그 접두사 — "재기준이 실제로 적용됐다"의 양성 지표. */
  private static final String REBASE_APPLIED_PREFIX = "[재개 카운터 재기준]";
  private static final String REBASE_SKIPPED_PREFIX = "[재개 카운터 재기준 생략]";
  /** 이 TASK가 추가하는 로그 접두사(확정 — TASK-008이 이 접두사로 센다). */
  private static final String CARRIED_PREFIX = "[재개 이월 실패]";

  /** 소실 사유 — PARTIAL 블록이 쓰는 것과 같은 문자열. */
  private static final String MISSING_REASON = "원본 파일 없음";

  private static final Pattern REASON_COUNT = Pattern.compile("(\\d+)건");

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

  private final AtomicInteger llmCalls = new AtomicInteger();
  private final AtomicInteger pauseOnCall = new AtomicInteger(0);
  private final AtomicInteger savedSessionCount = new AtomicInteger();
  private final AtomicReference<IntFunction<RuntimeException>> failurePlan =
      new AtomicReference<>(n -> null);
  private final AtomicReference<AnalysisHistory> persistedHistory = new AtomicReference<>();
  /** 이력 저장 시점의 총계 사본(마지막 값). nullable Integer를 목 안에서 언박싱하지 않는다. */
  private final List<String> savedHistoryRows = new ArrayList<>();
  /** 원본이 영구히 사라진 경로(누적) — I7′의 "누적 소실". */
  private final Set<String> lostPaths = new LinkedHashSet<>();

  private ch.qos.logback.classic.Logger controllerLogger;
  private ListAppender<ILoggingEvent> logAppender;

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
    // 승계 ③ (G-21-5) — "DB 행"처럼 마지막에 저장된 이력을 돌려준다.
    when(analysisHistoryRepository.findBySessionId(SID)).thenAnswer(inv -> persistedHistory.get());
    when(analysisHistoryRepository.save(any(AnalysisHistory.class))).thenAnswer(inv -> {
      AnalysisHistory saved = inv.getArgument(0);
      persistedHistory.set(saved);
      // 승계 ② (G-21-2) — 목 안에서 nullable Integer를 언박싱하지 않는다.
      savedHistoryRows.add("status=" + saved.getStatus() + " 총계=" + saved.getTotalFiles()
          + " 성공=" + saved.getSuccessCount() + " 건너뜀=" + saved.getSkipCount()
          + " 실패=" + saved.getFailureCount());
      return saved;
    });
    doAnswer(inv -> {
      savedSessionCount.incrementAndGet();
      return null;
    }).when(sessionManager).saveSessionState(any(SessionState.class));
    // 승계 ① (G-21-5) — 목이 실제 구현처럼 세션 총계를 채운다.
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

    // 재기준·이월 로그를 센다(서버 로그만 남기므로 appender로 관측한다 — 기존 ListAppender 관례).
    controllerLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MainApiController.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    controllerLogger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    if (controllerLogger != null && logAppender != null) {
      controllerLogger.detachAppender(logAppender);
      logAppender.stop();
    }
  }

  // ────────────────────────────────────────────────────────────────────────────
  // T7 — PARTIAL 재개 뒤 한 번 더 재개(전량실패)
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void T7_PARTIAL_재개_뒤_한_번_더_재개해도_소실_확정_실패가_유지된다() throws Exception {
    failurePlan.set(n -> ordinaryFailure());

    runAnalysisAndAwait();
    assertStage("T7 최초 전량실패");
    assertEquals(FILE_COUNT, session.getPendingFilePaths().size(), "T7 전제: 12개가 대기 목록에 있어야 한다");

    deletePendingFiles(MISSING_COUNT);

    resumeAndAwait("T7 재개 1회차(PARTIAL)");
    assertStage("T7 재개 1회차(PARTIAL)");
    assertEquals(FILE_COUNT, session.getStatistics().getFailureCount(),
        "T7 재개 1회차: 소실 5 + 남은 7 = 12가 실패여야 한다");
    assertEquals(FILE_COUNT - MISSING_COUNT, session.getPendingFilePaths().size(),
        "T7 재개 1회차: 대기 목록은 남은 7개여야 한다(소실분은 빠진다 — I7′)");

    // 핵심 — 한 번 더 재개한다. 이 시점 대기 목록(7)에는 소실 5가 없다.
    int failureBeforeSecondResume = session.getStatistics().getFailureCount();
    resumeAndAwait("T7 재개 2회차(AVAILABLE)");
    assertStage("T7 재개 2회차(AVAILABLE)");

    assertEquals(FILE_COUNT, session.getProcessedFiles(), "T7 재개 2회차: 처리 = 총계");
    assertEquals(FILE_COUNT, session.getStatistics().getFailureCount(),
        "T7 재개 2회차: 이전 재개에서 소실로 확정된 " + MISSING_COUNT + "개가 실패에서 빠지면 안 된다"
            + "(재기준이 실패를 0으로 되돌리는데 그 파일들은 대기 목록에 없어 다시 세지지 않는다)");
    assertEquals(FILE_COUNT, reasonSum(), "T7 재개 2회차: 사유 건수 합 = 12 (I6)");
    assertEquals(MISSING_COUNT, reasonCountOf(MISSING_REASON),
        "T7 재개 2회차: 사유 요약에 '" + MISSING_REASON + "' " + MISSING_COUNT + "건이 남아야 한다. 요약="
            + session.summarizeFileFailureReasons());
    assertEquals(session.getFailedFilePaths().size(), session.getStatistics().getFailureCount(),
        "T7 재개 2회차: 실패 수 == 실패 목록 크기");
    // 이월이 실제로 일어났다(G-21-1 양성 지표)
    assertEquals(1, logCount(CARRIED_PREFIX),
        "T7: 재개 2회차에서 이월 로그 1건이 있어야 한다. 로그=" + logLines(CARRIED_PREFIX));
    assertNotEquals(failureBeforeSecondResume, 0, "전제: 재개 전 실패 수가 0이 아니어야 한다");

    assertInvariants("T7 재개 2회차(AVAILABLE)", MISSING_COUNT);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // T8 — PARTIAL 재개 중 일시정지 → 재개 → 완료
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void T8_PARTIAL_뒤_일시정지하고_재개해_완료하면_이력_총계가_세션_총계와_같다() throws Exception {
    failurePlan.set(n -> ordinaryFailure());
    runAnalysisAndAwait();
    assertStage("T8 최초 전량실패");

    deletePendingFiles(MISSING_COUNT);

    // 재개 1회차(PARTIAL) — 남은 7개 중 2개만 성공시키고 사용자 일시정지
    failurePlan.set(n -> null);
    pauseOnCall.set(2);
    resumeAndAwait("T8 재개 1회차(PARTIAL, 일시정지)");
    assertStage("T8 재개 1회차(PARTIAL, 일시정지)");
    int successAfterFirstResume = session.getStatistics().getSuccessCount();
    int pendingAfterFirstResume = session.getPendingFilePaths().size();
    assertTrue(successAfterFirstResume > 0 && successAfterFirstResume < FILE_COUNT - MISSING_COUNT,
        "T8 전제: 남은 7개 중 일부만 성공해야 한다. 실제 성공=" + successAfterFirstResume);
    assertEquals(MISSING_COUNT, session.getStatistics().getFailureCount(),
        "T8 재개 1회차: 소실 " + MISSING_COUNT + "개만 실패여야 한다");

    // 재개 2회차 — 남은 파일 전부 성공 → 완료
    pauseOnCall.set(0);
    resumeAndAwait("T8 재개 2회차(완료)");
    assertStage("T8 재개 2회차(완료)");

    assertEquals(FILE_COUNT - MISSING_COUNT, session.getStatistics().getSuccessCount(),
        "T8: 성공 = 7 (재개 1회차 " + successAfterFirstResume + " + 2회차 " + pendingAfterFirstResume + ")");
    assertEquals(MISSING_COUNT, session.getStatistics().getFailureCount(),
        "T8: 소실 " + MISSING_COUNT + "개가 실패로 유지돼야 한다");
    assertEquals(FILE_COUNT, session.getProcessedFiles(), "T8: 처리 = 총계");
    assertEquals(session.getProcessedFiles(),
        session.getStatistics().getSuccessCount() + session.getStatistics().getSkipCount()
            + session.getStatistics().getFailureCount(),
        "T8 (I3): 성공+건너뜀+실패 == 처리 수");
    assertEquals("총계=" + FILE_COUNT, "총계=" + historyTotalFiles(),
        "T8 (I4): 이력 총계가 세션 총계와 같아야 한다 — finalizeAnalysis가 카운터 합으로 쓰므로"
            + " 실패가 빠지면 여기서 어긋난다. 이력=" + lastHistory());
    assertEquals(1, logCount(CARRIED_PREFIX),
        "T8: 재개 2회차에서 이월 로그 1건. 로그=" + logLines(CARRIED_PREFIX));
  }

  // ────────────────────────────────────────────────────────────────────────────
  // T9 대조군 — 소실 없이 재개 2회. 수정 전후 모두 GREEN
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void T9_대조군_소실이_없으면_이월이_일어나지_않는다() throws Exception {
    failurePlan.set(n -> ordinaryFailure());

    runAnalysisAndAwait();
    assertStage("T9 최초 전량실패");
    for (int round = 1; round <= 2; round++) {
      resumeAndAwait("T9 재개 " + round + "회차");
      assertStage("T9 재개 " + round + "회차");
      assertEquals(FILE_COUNT, session.getProcessedFiles(), "T9 재개 " + round + "회차: 처리 = 총계");
      assertEquals(FILE_COUNT, session.getStatistics().getFailureCount(),
          "T9 재개 " + round + "회차: 실패 = 총계");
    }

    assertEquals(0, logCount(CARRIED_PREFIX),
        "T9: 소실이 없으면 이월 로그가 남지 않아야 한다. 로그=" + logLines(CARRIED_PREFIX));
    assertEquals(FILE_COUNT, reasonSum(), "T9: 사유 건수 합 = 12");
    assertEquals(0, reasonCountOf(MISSING_REASON),
        "T9: 소실 사유가 생기면 안 된다. 요약=" + session.summarizeFileFailureReasons());
    assertInvariants("T9 재개 2회차", 0);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // T10 대조군(이중 계산 방지) — 재기준 생략 경로에서는 이월하지 않는다
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * 재기준을 생략했으면(총계 &lt; 대기 수, R-4) 실패 수가 리셋되지 않았으므로 이월분을 더하면
   * <b>이중 계산</b>이 된다. 픽스처는 {@code MainApiControllerResumeSourceMissingTest}와 같은 형태
   * (총계 기본값 0 + 대기 목록을 직접 심은 세션)라 처리 수·총계 불변식은 재지 않는다.
   */
  @Test
  void T10_대조군_재기준을_생략하면_이월하지_않는다() throws Exception {
    SessionState skipped = new SessionState("sid-skip-rebase", srcRoot.toString(), outRoot.toString());
    skipped.setUsername(USERNAME);
    skipped.setUserId(USER_SEQ);
    skipped.setGenerateReadme(false);
    when(sessionManager.getSession("sid-skip-rebase")).thenReturn(skipped);

    List<String> pending = new ArrayList<>();
    for (int i = 1; i <= 4; i++) {
      pending.add(srcRoot.resolve("com/x/F" + i + ".java").toString());
    }
    skipped.setPendingFilePaths(pending);
    // 실패 목록에 "대기 목록 밖" 경로를 심는다 — 이월 대상이 될 수 있는 모양이다.
    String outsidePending = srcRoot.resolve("com/x/F9.java").toString();
    skipped.addFailedFilePath(outsidePending);
    skipped.getStatistics().setFailureCount(3);
    // 총계는 기본값 0 → 0 < 4이므로 재기준은 생략된다.
    assertEquals(0, skipped.getTotalFiles(), "T10 전제: 총계가 0이어야 재기준이 생략된다");

    failurePlan.set(n -> null);
    resumeOtherSessionAndAwait(skipped, "sid-skip-rebase");

    System.out.println("[010] T10 재기준 생략 | 실패=" + skipped.getStatistics().getFailureCount()
        + " 성공=" + skipped.getStatistics().getSuccessCount()
        + " 처리=" + skipped.getProcessedFiles() + "/" + skipped.getTotalFiles()
        + " | 재기준 적용 로그=" + logCount(REBASE_APPLIED_PREFIX)
        + " 생략 로그=" + logCount(REBASE_SKIPPED_PREFIX)
        + " 이월 로그=" + logCount(CARRIED_PREFIX)
        + " | LLM 호출=" + llmCalls.get());

    // G-21-1 — 재개가 실제로 돌았고, 생략 경로를 탔다("아무 일도 안 일어나서 통과"를 막는다)
    assertTrue(llmCalls.get() >= 1, "T10: 재개 단계 LLM 호출이 0회다 — 재개가 돌지 않았다");
    assertEquals(1, logCount(REBASE_SKIPPED_PREFIX),
        "T10: 재기준 생략 로그 1건이 있어야 한다(생략 경로를 탔다는 증거). 로그=" + logLines(REBASE_SKIPPED_PREFIX));
    assertEquals(0, logCount(REBASE_APPLIED_PREFIX),
        "T10: 재기준 적용 로그가 있으면 생략 경로가 아니다. 로그=" + logLines(REBASE_APPLIED_PREFIX));

    assertEquals(3, skipped.getStatistics().getFailureCount(),
        "T10: 재기준을 생략했으면 이월하지 않는다(더하면 이중 계산). 실패 수가 그대로여야 한다");
    assertEquals(0, logCount(CARRIED_PREFIX),
        "T10: 생략 경로에서는 이월 로그가 남지 않아야 한다. 로그=" + logLines(CARRIED_PREFIX));
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 판정 도우미 — G-21
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * 단계마다 "그 단계가 실제로 돌았다"를 먼저 본다(G-21-1·2·3).
   * 종단 값은 항상 살아 있는 세션에서 읽는다.
   */
  private void assertStage(String label) {
    assertNotEquals("FAILED", session.getCurrentPhase(),
        label + " (G-21-2): 분석 스레드가 예외로 끝났다(phase=FAILED) — 이 상태의 값은 판정 근거가 될 수 없다");
    assertTrue(session.getTotalFiles() > 0,
        label + " (G-21-1): 세션 총계가 0이다 — initializeFileList 스텁이 동작하지 않았다");
    assertTrue(llmCalls.get() >= 1,
        label + " (G-21-1): 이 단계에서 LLM 호출이 0회다 — 경로가 돌지 않았다");

    System.out.println("[010] " + label
        + " | 처리=" + session.getProcessedFiles() + "/" + session.getTotalFiles()
        + " 성공=" + session.getStatistics().getSuccessCount()
        + " 건너뜀=" + session.getStatistics().getSkipCount()
        + " 실패=" + session.getStatistics().getFailureCount()
        + " 실패목록=" + session.getFailedFilePaths().size()
        + " 대기=" + session.getPendingFilePaths().size()
        + " 사유합=" + reasonSum()
        + " 누적소실=" + lostPaths.size()
        + " | 이력=" + lastHistory()
        + " | 로그(재기준/생략/이월)=" + logCount(REBASE_APPLIED_PREFIX)
        + "/" + logCount(REBASE_SKIPPED_PREFIX) + "/" + logCount(CARRIED_PREFIX)
        + " | 사유요약=" + session.summarizeFileFailureReasons());
  }

  /** I3·I6·I7′ — 일시정지 계열 종단에서. */
  private void assertInvariants(String label, int cumulativeMissing) {
    int success = session.getStatistics().getSuccessCount();
    int skip = session.getStatistics().getSkipCount();
    int failure = session.getStatistics().getFailureCount();

    assertTrue(session.getProcessedFiles() >= 0 && session.getProcessedFiles() <= session.getTotalFiles(),
        label + " (I1): 0 ≤ 처리 수 ≤ 총계");
    assertEquals(session.getProcessedFiles(), success + skip + failure,
        label + " (I3): 성공+건너뜀+실패 == 처리 수");
    assertEquals(failure, reasonSum(),
        label + " (I6): 사유 건수 합 == 실패 수(크레딧 소진 0건)");
    assertEquals(session.getTotalFiles(),
        session.getPendingFilePaths().size() + success + skip + cumulativeMissing,
        label + " (I7′ ②): |대기| + 성공 + 건너뜀 + 누적 소실 == 총계");
    Set<String> retriable = new LinkedHashSet<>(session.getPendingFilePaths());
    retriable.addAll(lostPaths);
    assertTrue(retriable.containsAll(session.getFailedFilePaths()),
        label + " (I7′ ③): 실패 목록 ⊆ 대기 목록 ∪ 소실 확정 파일(누적)");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 도우미
  // ────────────────────────────────────────────────────────────────────────────

  private void deletePendingFiles(int count) throws Exception {
    List<String> pending = session.getPendingFilePaths();
    for (int i = 0; i < count; i++) {
      String lost = pending.get(i);
      Files.delete(Path.of(lost));
      lostPaths.add(lost);
    }
    System.out.println("[010] 대기 파일 " + count + "개 삭제 — 누적 소실=" + lostPaths.size());
  }

  private int logCount(String prefix) {
    return logLines(prefix).size();
  }

  private List<String> logLines(String prefix) {
    List<String> result = new ArrayList<>();
    for (ILoggingEvent event : new ArrayList<>(logAppender.list)) {
      if (event.getLevel() != Level.TRACE && event.getFormattedMessage().startsWith(prefix)) {
        result.add(event.getFormattedMessage());
      }
    }
    return result;
  }

  /** 사유 요약의 모든 {@code (n건)} 합. */
  private int reasonSum() {
    String summary = session.summarizeFileFailureReasons();
    if (summary == null) {
      return 0;
    }
    int sum = 0;
    Matcher m = REASON_COUNT.matcher(summary);
    while (m.find()) {
      sum += Integer.parseInt(m.group(1));
    }
    return sum;
  }

  /** 사유 요약에서 특정 표시 문자열의 건수(없으면 0). */
  private int reasonCountOf(String display) {
    String summary = session.summarizeFileFailureReasons();
    if (summary == null) {
      return 0;
    }
    Matcher m = Pattern.compile(Pattern.quote(display) + " \\((\\d+)건\\)").matcher(summary);
    return m.find() ? Integer.parseInt(m.group(1)) : 0;
  }

  private String lastHistory() {
    return savedHistoryRows.isEmpty() ? "(없음)" : savedHistoryRows.get(savedHistoryRows.size() - 1);
  }

  /** 마지막 이력 행의 총계 값(문자열에서 뽑는다 — 목 안에서 언박싱하지 않기 위함). */
  private int historyTotalFiles() {
    Matcher m = Pattern.compile("총계=(\\d+)").matcher(lastHistory());
    return m.find() ? Integer.parseInt(m.group(1)) : -1;
  }

  private void setField(String name, Object value) throws Exception {
    Field field = MainApiController.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(controller, value);
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

  private void resumeAndAwait(String label) throws Exception {
    // 단계별 양성 지표를 재려면 단계 경계에서 카운터를 리셋한다(G-21-1).
    llmCalls.set(0);
    int rebaseLogsBefore = logCount(REBASE_APPLIED_PREFIX);
    invokeResume(session, SID);
    // 이 TASK의 이월 블록은 rebase.applied()일 때만 돈다 — 재기준이 실제로 적용됐음을 양성 지표로 본다.
    assertEquals(rebaseLogsBefore + 1, logCount(REBASE_APPLIED_PREFIX),
        label + " (G-21-1): 이 재개에서 재기준 적용 로그가 1건 늘어야 한다(생략됐다면 이월 블록이 아예"
            + " 돌지 않아 '아무 일도 안 일어나서 통과'가 된다). 로그=" + logLines(REBASE_APPLIED_PREFIX));
    assertEquals(0, logCount(REBASE_SKIPPED_PREFIX),
        label + " (G-21-1): 재기준 생략 로그가 있으면 이 시나리오의 전제가 깨진다. 로그="
            + logLines(REBASE_SKIPPED_PREFIX));
  }

  private void resumeOtherSessionAndAwait(SessionState target, String sessionId) throws Exception {
    llmCalls.set(0);
    invokeResume(target, sessionId);
  }

  @SuppressWarnings("unchecked")
  private void invokeResume(SessionState target, String sessionId) throws Exception {
    Method m = MainApiController.class.getDeclaredMethod(
        "resumePendingFilesInThread", SessionState.class, String.class);
    m.setAccessible(true);
    Map<String, Object> response = (Map<String, Object>) m.invoke(controller, target, sessionId);
    assertEquals(Boolean.TRUE, response.get("success"), "재개 진입점 실패: " + response.get("message"));
    long deadline = System.currentTimeMillis() + 60_000L;
    while (System.currentTimeMillis() < deadline) {
      String phase = target.getCurrentPhase();
      if ("COMPLETED".equals(phase) || "PAUSED".equals(phase) || "FAILED".equals(phase)
          || "CANCELLED".equals(phase) || SessionState.STATUS_AWAITING_FAILOVER_CONFIRM.equals(phase)) {
        assertNotEquals("FAILED", phase,
            "재개 스레드가 예외로 끝났다(G-21-2) — 이 상태의 카운터는 판정 근거가 될 수 없다");
        return;
      }
      Thread.sleep(50);
    }
    fail("재개 스레드가 60초 안에 종료 상태에 도달하지 않았다. phase=" + target.getCurrentPhase());
  }
}
