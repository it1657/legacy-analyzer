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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * TASK-003 (work-order 2026-10-counter-integrity-and-failure-reason v1, REQ-001 A / 게이트1 D1·D9) —
 * 재개를 거친 세션에서 <b>카운터 정합 불변식</b>(설계 §4 I1~I7)이 끝나는 모든 지점에서 유지되는지
 * 실제 처리 루프로 확인한다.
 *
 * <p>고치려는 결함: 재개 루프는 처리 수를 <b>저장돼 있던 값에서 이어서</b> 올린다. 그런데 일시정지·
 * 전량실패로 돌아간 파일은 대기 목록에 그대로 남아 재개 때 <b>다시</b> 처리되므로, 같은 파일이 처리 수를
 * 두 번 올린다. 재개를 두 번 하면 12개 파일 세션의 처리 수가 24가 되고 진행률이 100%를 넘는다
 * (운영 실측: 처리 수 > 총계인 행이 존재한다 — TASK-001).
 *
 * <p><b>하네스</b>: 선례 {@code MainApiControllerTerminalStatusConsistencyTest}와 같은 구조 —
 * 실제 {@code @TempDir} 파일 + LLM 대역 + 리플렉션 기동, 스레드풀 1. "DB 행"은
 * {@code saveSessionState()}/{@code analysisHistoryRepository.save()} 호출 <b>시점</b>의 분리 사본이다.
 *
 * <p><b>I5</b>는 상태 API({@code getAnalysisStatus})를 실제로 불러 세션 값과 대조하고 진행률이 100%를
 * 넘지 않는지 본다. <b>I6</b>의 사유 건수 합은 {@code summarizeFileFailureReasons()} 문자열에서
 * {@code (n건)}을 긁어 더한다(크레딧 소진 실패는 사유 집계에서 제외되므로 그 수를 빼고 비교한다).
 *
 * <p><b>T6은 양성 대조군</b>이다 — 재개를 거치지 않는 최초 분석 경로에서 같은 불변식을 재므로
 * 수정 전후 모두 GREEN이어야 한다. 단언이 "항상 실패"가 아님을 같은 테스트 안에서 보인다(G-04 ②).
 */
class ResumeCounterInvariantTest {

  private static final String SID = "sid-resume-counter";
  private static final String USERNAME = "jhjung";
  private static final Long USER_SEQ = 70L;
  private static final String STUB_COMMENT_PREFIX = "// [AI 주석] 테스트 대역이 생성한 주석";
  private static final int FILE_COUNT = 12;

  /** {@code (n건)} / {@code 기타 n종 n건}에서 건수만 긁는다. */
  private static final Pattern REASON_COUNT = Pattern.compile("(\\d+)건");

  /** 세션 저장 시점의 카운터·목록 사본. */
  private record Snapshot(String label, String status, int processed, int total,
                          int success, int failure, int skip,
                          List<String> pending, Set<String> failed, int reasonSum) {
    @Override
    public String toString() {
      return "{" + label + " status=" + status + " 처리=" + processed + "/" + total
          + " 성공=" + success + " 실패=" + failure + " 건너뜀=" + skip
          + " 대기=" + pending.size() + " 실패목록=" + failed.size() + " 사유합=" + reasonSum + "}";
    }
  }

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
  private AnalysisHistory history;
  private Authentication ownerAuth;

  private final List<Snapshot> savedRows = new CopyOnWriteArrayList<>();
  private final List<HistoryRow> savedHistoryRows = new CopyOnWriteArrayList<>();
  private final AtomicInteger llmCalls = new AtomicInteger();
  private final AtomicInteger pauseOnCall = new AtomicInteger(0);
  /** 크레딧 소진으로 실패한 파일 수 — I6에서 사유 집계 제외분으로 뺀다. */
  private final AtomicInteger creditFailures = new AtomicInteger();
  private final AtomicReference<IntFunction<RuntimeException>> failurePlan =
      new AtomicReference<>(n -> null);
  /** "DB에 들어 있는 이력 행" 역할 — 마지막으로 저장된 객체를 findBySessionId가 돌려준다. */
  private final AtomicReference<AnalysisHistory> persistedHistory = new AtomicReference<>();
  /** 원본이 영구히 사라진 경로 — D1에서 이 파일들은 "끝난 파일(실패)"이고 대기 목록에 남을 수 없다. */
  private final Set<String> lostPaths = new LinkedHashSet<>();

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
    persistedHistory.set(history);
    // 최초 분석(runAnalysis)은 findBySessionId를 쓰지 않고 자기 AnalysisHistory를 새로 만들어 저장한다.
    // 선례 하네스처럼 findBySessionId가 "테스트가 들고 있는 객체"를 고정 반환하면, 재개 경로가 보는
    // 이력은 최초 분석이 실제로 쓴 행이 아니라 손대지 않은 빈 객체가 된다(실측: 총계 0으로 관측됐다).
    // 운영에서는 DB 행을 읽으므로, 여기서도 "마지막에 저장된 이력"을 돌려줘 같은 조건을 만든다.
    when(analysisHistoryRepository.findBySessionId(SID)).thenAnswer(inv -> persistedHistory.get());
    when(analysisHistoryRepository.save(any(AnalysisHistory.class))).thenAnswer(inv -> {
      AnalysisHistory saved = inv.getArgument(0);
      persistedHistory.set(saved);
      // AnalysisHistory의 카운터 컬럼은 nullable(Integer)이다. 여기서 int로 그냥 언박싱하면
      // null일 때 이 목 answer가 NPE를 던지고, 그 NPE가 save()를 부른 "프로덕션 스레드"를 죽인다
      // (실측: 재개 스레드가 파일을 하나도 처리하지 못하고 phase=FAILED로 끝났다). 0으로 읽는다.
      savedHistoryRows.add(new HistoryRow(saved.getStatus(), nz(saved.getTotalFiles()),
          nz(saved.getSuccessCount()), nz(saved.getSkipCount()), nz(saved.getFailureCount())));
      return saved;
    });

    doAnswer(inv -> {
      SessionState s = inv.getArgument(0);
      savedRows.add(snapshotOf("저장", s));
      return null;
    }).when(sessionManager).saveSessionState(any(SessionState.class));

    // runAnalysis()는 파일 목록을 센 뒤 sessionManager.initializeFileList(sessionId, fileList.size())로
    // 세션 총계를 채운다. 목으로 두면 총계가 0에 머물러 "처리 수 ≤ 총계" 같은 불변식을 아예 잴 수 없다
    // (선례 하네스는 총계를 단언하지 않아 드러나지 않았던 구멍이다). 실제 구현과 같은 일을 하게 둔다.
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
          if (toThrow != null) {
            if (toThrow instanceof AnalysisException ae
                && ae.getErrorType() == ApiErrorHandler.ErrorType.INSUFFICIENT_CREDITS) {
              creditFailures.incrementAndGet();
            }
            throw toThrow;
          }
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
  // T1~T3 — 재개 경로. 수정 전에는 처리 수가 총계를 넘는다
  // ────────────────────────────────────────────────────────────────────────────

  /** T1 — 전량실패 → 재개 → 전량실패 → 재개 → 전량실패(재개 2회). 매 종단 처리 = 총계, 실패 = 총계. */
  @Test
  void T1_전량실패_후_재개를_두_번_해도_처리수가_총계를_넘지_않는다() throws Exception {
    failurePlan.set(n -> ordinaryFailure());

    runAnalysisAndAwait();
    Snapshot first = terminal("T1 최초 전량실패", true);
    assertEquals(FILE_COUNT, first.processed(), "T1 최초: 처리 = 총계");
    assertEquals(FILE_COUNT, first.failure(), "T1 최초: 실패 = 총계");

    for (int round = 1; round <= 2; round++) {
      llmCalls.set(0);
      resumeAndAwait();
      Snapshot after = terminal("T1 재개 " + round + "회차", true);
      assertEquals(FILE_COUNT, after.processed(),
          "T1 재개 " + round + "회차: 처리 수가 총계와 같아야 한다(두 번 세지 않는다)");
      assertEquals(FILE_COUNT, after.failure(),
          "T1 재개 " + round + "회차: 실패 수는 0에서 다시 세어 총계와 같아야 한다");
    }
  }

  /** T2 — 일부 실패 후 사용자 일시정지 → 재개 전량실패. */
  @Test
  void T2_일부_실패_후_일시정지하고_재개해도_처리수가_총계를_넘지_않는다() throws Exception {
    failurePlan.set(n -> ordinaryFailure());
    pauseOnCall.set(3);

    runAnalysisAndAwait();
    Snapshot paused = terminal("T2 사용자 일시정지", true);
    assertTrue(paused.failure() > 0 && paused.failure() < FILE_COUNT,
        "T2 전제: 일부만 실패한 상태에서 멈춰야 한다. 실제=" + paused);

    pauseOnCall.set(0);
    llmCalls.set(0);
    resumeAndAwait();
    Snapshot after = terminal("T2 재개 전량실패", true);
    assertEquals(FILE_COUNT, after.processed(), "T2 재개: 처리 = 총계");
    assertEquals(FILE_COUNT, after.failure(), "T2 재개: 실패 = 총계");
  }

  /** T3 — 일부 실패 후 일시정지 → 대기 파일 일부 삭제(PARTIAL) → 재개 전량실패. 소실도 처리됨(실패)으로 센다(D1·D9). */
  @Test
  void T3_대기파일_일부가_사라진_뒤_재개해도_처리수가_총계와_같다() throws Exception {
    failurePlan.set(n -> ordinaryFailure());

    runAnalysisAndAwait();
    Snapshot first = terminal("T3 최초 전량실패", true);
    assertEquals(FILE_COUNT, first.pending().size(), "T3 전제: 12개가 대기 목록에 있어야 한다");

    // 대기 목록이 가리키는 실제 파일 5개를 지운다 → PARTIAL 경로
    int missing = 5;
    for (int i = 0; i < missing; i++) {
      String lost = first.pending().get(i);
      Files.delete(Path.of(lost));
      lostPaths.add(lost);
    }
    System.out.println("[003 T3] 대기 파일 " + missing + "개 삭제 — PARTIAL 경로로 재개한다");

    llmCalls.set(0);
    resumeAndAwait();
    Snapshot after = terminal("T3 재개(PARTIAL) 전량실패", true);

    assertEquals(FILE_COUNT, after.processed(),
        "T3: 사라진 파일도 '끝난 파일(실패)'로 세므로 처리 = 총계여야 한다(D1·D9)");
    assertEquals(FILE_COUNT, after.failure(), "T3: 실패 = 총계");
    assertTrue(summary().contains("원본 파일 없음"),
        "T3: 사유 요약에 소실 사유가 있어야 한다. 실제=" + summary());
  }

  // ────────────────────────────────────────────────────────────────────────────
  // T4·T5 — 재개 후 완료 / 크레딧 소진 후 재개
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * T4 — 일부 성공 + 일부 실패 → 일시정지 → 재개 → 완료.
   *
   * <p>여기서 <b>이력 총계 == 세션 총계</b>를 함께 단언한다. {@code finalizeAnalysis()}는 이력 총계를
   * {@code 성공 + 건너뜀 + 기타건너뜀 + 실패}의 <b>합</b>으로 쓰므로, 재개가 카운터를 부풀리면 이력 총계가
   * 세션 총계보다 커진다 — 운영에서 실제로 관측된 어긋남(세션 총계 8 / 이력 총계 13, TASK-001)의 형태다.
   */
  @Test
  void T4_일부_성공_후_재개해서_완료하면_이력_총계가_세션_총계와_같다() throws Exception {
    failurePlan.set(n -> n <= 2 ? ordinaryFailure() : null);
    pauseOnCall.set(4);

    runAnalysisAndAwait();
    Snapshot paused = terminal("T4 사용자 일시정지", true);
    int successBeforeResume = paused.success();
    assertTrue(successBeforeResume > 0, "T4 전제: 재개 전에 성공이 1건 이상 있어야 한다. 실제=" + paused);
    assertTrue(paused.failure() > 0, "T4 전제: 재개 전에 실패도 1건 이상 있어야 한다. 실제=" + paused);

    pauseOnCall.set(0);
    failurePlan.set(n -> null);
    llmCalls.set(0);
    int pendingAtResume = paused.pending().size();
    resumeAndAwait();

    Snapshot after = terminal("T4 재개 후 완료", false);
    assertEquals(FILE_COUNT, after.processed(), "T4: 처리 = 총계");
    assertEquals(successBeforeResume + pendingAtResume, after.success(),
        "T4: 성공 수 = 재개 전 값(" + successBeforeResume + ") + 재개 성공 수(" + pendingAtResume + ")");

    HistoryRow last = lastHistory();
    assertEquals("COMPLETED", last.status(), "T4: 완료로 끝나야 한다. 이력=" + last);
    assertEquals(after.total(), last.total(),
        "T4 (I4): 이력 총계가 세션 총계와 같아야 한다 — 재개가 카운터를 부풀리면 여기서 어긋난다. 이력=" + last);
    assertEquals(after.success(), last.success(), "T4 (I4): 이력 성공 수 == 세션");
    assertEquals(after.skip(), last.skip(), "T4 (I4): 이력 건너뜀 수 == 세션");
    assertEquals(after.failure(), last.failure(), "T4 (I4): 이력 실패 수 == 세션");
  }

  /** T5 — 크레딧 소진으로 PAUSED(failover 대상 없음) → 재개. */
  @Test
  void T5_크레딧소진으로_멈춘_세션을_재개해도_카운터가_정합이다() throws Exception {
    when(llmModelOptionService.getActiveFailoverTarget()).thenReturn(Optional.empty());
    failurePlan.set(n -> n == 1 ? credits() : null);

    runAnalysisAndAwait();
    Snapshot paused = terminal("T5 크레딧 소진 PAUSED", true);
    assertTrue(paused.failure() > 0, "T5 전제: 크레딧 소진 실패가 1건 이상 있어야 한다. 실제=" + paused);

    failurePlan.set(n -> null);
    llmCalls.set(0);
    resumeAndAwait();

    Snapshot after = terminal("T5 재개 후 완료", false);
    assertEquals(FILE_COUNT, after.processed(), "T5: 처리 = 총계");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // T6 — 양성 대조군. 최초 분석 경로(재개 없음)는 수정 전후 모두 GREEN이어야 한다
  // ────────────────────────────────────────────────────────────────────────────

  /** T6a — 최초 분석 전량실패. 재개를 거치지 않으므로 이번 변경의 영향이 없다. */
  @Test
  void T6a_대조군_최초분석_전량실패에서도_같은_불변식이_성립한다() throws Exception {
    failurePlan.set(n -> ordinaryFailure());

    runAnalysisAndAwait();
    Snapshot last = terminal("T6a 최초 전량실패", true);

    assertEquals(FILE_COUNT, last.processed(), "T6a: 처리 = 총계");
    assertEquals(FILE_COUNT, last.failure(), "T6a: 실패 = 총계");
    assertEquals(0, last.success(), "T6a: 성공 0");
  }

  /** T6b — 최초 분석 정상 완료. */
  @Test
  void T6b_대조군_최초분석_완료에서도_같은_불변식이_성립한다() throws Exception {
    failurePlan.set(n -> null);

    runAnalysisAndAwait();
    Snapshot last = terminal("T6b 최초 완료", false);

    assertEquals(FILE_COUNT, last.processed(), "T6b: 처리 = 총계");
    assertEquals(FILE_COUNT, last.success(), "T6b: 성공 = 총계");
    assertEquals(0, last.failure(), "T6b: 실패 0");

    HistoryRow lastHistory = lastHistory();
    assertEquals(last.total(), lastHistory.total(), "T6b (I4): 이력 총계 == 세션 총계");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 불변식 판정
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * 한 단계가 끝난 시점의 판정 — 모든 저장 스냅샷에서 I1·I2, 마지막 시점에서 I3·I6(·I7)를 본다.
   * 정상 완료 경로는 종단 분기를 타지 않아 새 저장 스냅샷이 없을 수 있으므로, 그 경우 살아 있는
   * 세션에서 뜬 사본을 쓴다.
   *
   * @param pauseLike 일시정지 계열(대기 목록이 남는 경로)이면 I7까지 본다
   */
  private Snapshot terminal(String label, boolean pauseLike) {
    // 종단 값은 항상 "살아 있는 세션"에서 뜬다. 마지막 저장 스냅샷을 쓰면, 이번 단계가 저장에
    // 도달하지 못한 경우(예: 스레드가 예외로 죽은 경우) 이전 단계의 값을 그대로 다시 재서
    // 거짓 PASS가 난다 — 실제로 한 번 당했다.
    Snapshot last = snapshotOf(label + "(살아 있는 세션)", session);

    AnalysisStatusDto api = statusApi();
    System.out.println("[003] " + label + " | " + last
        + " | 이력=" + savedHistoryRows
        + " | 사유요약=" + summary()
        + " | 크레딧실패=" + creditFailures.get()
        + " | 상태API=처리 " + api.getProcessedFiles() + "/" + api.getTotalFiles()
        + " 성공 " + api.getSuccessCount() + " 실패 " + api.getFailedCount()
        + " 건너뜀 " + api.getAlreadyCount() + " phase=" + api.getPhase()
        + " | LLM호출=" + llmCalls.get());
    System.out.println("[003 저장이력] " + label + " | " + savedRows);
    HistoryRow lastHistoryRow =
        savedHistoryRows.isEmpty() ? null : savedHistoryRows.get(savedHistoryRows.size() - 1);
    System.out.println("[003 이력-세션 경계] " + label
        + " | 이력 마지막=" + lastHistoryRow
        + " | 세션=총계 " + last.total() + " 성공 " + last.success()
        + " 건너뜀 " + last.skip() + " 실패 " + last.failure()
        + " | 일치=" + (lastHistoryRow != null && lastHistoryRow.total() == last.total()
            && lastHistoryRow.success() == last.success() && lastHistoryRow.skip() == last.skip()
            && lastHistoryRow.failure() == last.failure()));
    System.out.println("[003 터미널로그] " + label + " | "
        + session.getRecentLogLines(6));

    // 분석/재개 스레드가 예외로 죽으면 phase가 FAILED가 된다. 그 상태로 카운터를 재면
    // "아무 일도 일어나지 않아서 불변식이 성립하는" 거짓 PASS가 되므로 먼저 막는다.
    assertNotEquals("FAILED", session.getCurrentPhase(),
        label + ": 분석 스레드가 예외로 끝났다(phase=FAILED) — 이 상태의 카운터는 판정 근거가 될 수 없다."
            + " 스냅샷=" + last);

    // I1·I2 — 모든 저장 스냅샷에서
    for (Snapshot s : savedRows) {
      assertTrue(s.processed() >= 0 && s.processed() <= s.total(),
          label + " (I1 위반): 0 ≤ 처리 수 ≤ 총계여야 한다. 스냅샷=" + s);
      assertTrue(s.success() + s.skip() + s.failure() <= s.total(),
          label + " (I2 위반): 성공+건너뜀+실패 ≤ 총계여야 한다. 스냅샷=" + s);
    }
    // 이력 스냅샷에도 I2
    for (HistoryRow h : savedHistoryRows) {
      assertTrue(h.success() + h.skip() + h.failure() <= h.total(),
          label + " (I2 위반, 이력): 성공+건너뜀+실패 ≤ 총계여야 한다. 이력=" + h);
    }

    // I1·I2 — 종단 시점(살아 있는 세션 포함)
    assertTrue(last.processed() >= 0 && last.processed() <= last.total(),
        label + " (I1 위반, 종단): 0 ≤ 처리 수 ≤ 총계여야 한다. 실제=" + last);
    assertTrue(last.success() + last.skip() + last.failure() <= last.total(),
        label + " (I2 위반, 종단): 성공+건너뜀+실패 ≤ 총계여야 한다. 실제=" + last);

    // I3 — 종단
    assertEquals(last.processed(), last.success() + last.skip() + last.failure(),
        label + " (I3 위반): 성공+건너뜀+실패 == 처리 수여야 한다. 실제=" + last);

    // I6 — 사유 건수 합 == 실패 − 크레딧 소진 실패
    assertEquals(last.failure() - creditFailures.get(), last.reasonSum(),
        label + " (I6 위반): 사유 건수 합 == 실패 − 크레딧 소진 실패. 실제=" + last
            + ", 크레딧실패=" + creditFailures.get() + ", 사유요약=" + summary());

    // I7 — 일시정지 계열에서만
    if (pauseLike) {
      // 끝난 파일 = 성공 + 건너뜀 + 원본이 영구히 사라진 파일(D1에서 "끝난 파일(실패)"로 센다).
      // 소실 파일은 다시 처리할 수 없으므로 대기 목록에 남지 않는다 — 그 몫을 따로 더해야 등식이 성립한다.
      assertEquals(last.total(),
          last.pending().size() + last.success() + last.skip() + lostPaths.size(),
          label + " (I7 위반): 대기 목록 + 끝난 파일(성공+건너뜀+소실) == 전체여야 한다. 실제=" + last
              + ", 소실=" + lostPaths.size());
      Set<String> retriable = new LinkedHashSet<>(last.pending());
      retriable.addAll(lostPaths);
      assertTrue(retriable.containsAll(last.failed()),
          label + " (I7 위반): 실패 파일은 대기 목록이나 소실 목록에 들어 있어야 한다. 실패목록="
              + last.failed().size() + ", 대기=" + last.pending().size()
              + ", 소실=" + lostPaths.size());
    }

    // I5 — 상태 API == 세션, 진행률 ≤ 100%
    AnalysisStatusDto dto = statusApi();
    assertEquals(session.getProcessedFiles(), dto.getProcessedFiles(), label + " (I5): 처리 수");
    assertEquals(session.getTotalFiles(), dto.getTotalFiles(), label + " (I5): 총계");
    assertEquals(session.getStatistics().getSuccessCount(), dto.getSuccessCount(), label + " (I5): 성공");
    assertEquals(session.getStatistics().getFailureCount(), dto.getFailedCount(), label + " (I5): 실패");
    assertEquals(session.getStatistics().getSkipCount(), dto.getAlreadyCount(), label + " (I5): 건너뜀");
    if (dto.getTotalFiles() > 0) {
      int percent = dto.getProcessedFiles() * 100 / dto.getTotalFiles();
      assertTrue(percent <= 100,
          label + " (I5 위반): 화면 진행률이 100%를 넘는다 — " + percent + "% ("
              + dto.getProcessedFiles() + "/" + dto.getTotalFiles() + ")");
    }

    return last;
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 도우미
  // ────────────────────────────────────────────────────────────────────────────

  private Snapshot snapshotOf(String label, SessionState s) {
    return new Snapshot(label, s.getStatus(), s.getProcessedFiles(), s.getTotalFiles(),
        s.getStatistics().getSuccessCount(), s.getStatistics().getFailureCount(),
        s.getStatistics().getSkipCount(),
        new ArrayList<>(s.getPendingFilePaths()), new LinkedHashSet<>(s.getFailedFilePaths()),
        reasonSum(s.summarizeFileFailureReasons()));
  }

  /** nullable 카운터를 0으로 읽는다 — 목 answer 안에서 언박싱 NPE를 내지 않기 위한 것. */
  private static int nz(Integer value) {
    return value == null ? 0 : value;
  }

  private String summary() {
    String s = session.summarizeFileFailureReasons();
    return s == null ? "(없음)" : s;
  }

  /** 사유 요약 문자열에서 {@code (n건)}·{@code 기타 n종 n건}의 건수를 모두 더한다. */
  private static int reasonSum(String summary) {
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

  private AnalysisStatusDto statusApi() {
    return controller.getAnalysisStatus(SID, 80, ownerAuth);
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
    // 재기준(D1)은 재개 시작 때 실패 수를 0에서 다시 세므로, I6의 "크레딧 소진 제외분"도 이번 실행
    // 기준으로 다시 센다 — 지난 실행의 크레딧 실패는 실패 수·사유 집계 양쪽에서 함께 사라진다.
    creditFailures.set(0);
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
}
