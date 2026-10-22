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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TASK-001 (work-order 2026-10-history-total-and-mfc v1/v2, REQ-001) — 정상 완료 이력 행의
 * <b>총계</b>가 세션 전체 파일 수보다 작아지는 세 길을 <b>순서를 강제해</b> 결정적으로 재현한다.
 *
 * <p>고치려는 결함: {@code finalizeAnalysis}의 총계 한 줄만 세션 총계가 아니라 그 턴에 센 카운터
 * 합(성공 + 이미처리 + 용량초과 + 실패)을 쓴다. 나머지 일곱 곳(일시정지·취소·전량실패·크레딧 소진)은
 * 모두 세션 총계를 쓴다. 그래서 어떤 파일이 "성공도 실패도 아닌" 상태로 완료 목록에 들어가면
 * 합계가 세션 총계보다 작아지고, 목록 화면의 "N/M"이 영구히 줄어든 M을 보여준다.
 *
 * <p><b>세 길</b>(설계 §2.2): ㉠ 최초 실행에서 정지 신호가 "루프 맨 앞 검사 통과 직후 ~
 * {@code analyzeFile} 입구 검사 전" 틈에 걸림 ㉡ 재개 중 같은 틈 → 일시정지 → 다음 재개에서 완료
 * ㉢ 루프 본문의 {@code analyzeFile} 밖에서 예외.
 *
 * <p><b>순서 강제 방법</b>(설계 §5.2): 시험 전용 {@link SessionState} 하위 클래스가
 * {@code shouldStop()}을 재정의해 ① 원래 값을 계산하고 ② <b>부른 자리</b>(호출 스택에서 처음 만나는
 * {@code MainApiController} 프레임의 메서드 이름 — 루프 맨 앞은 {@code lambda$runAnalysis$}·
 * {@code lambda$runAnalysisResume$} 접두, 입구는 {@code analyzeFile})와 결과를 순서 기록에 남기고
 * ③ 지정한 루프 맨 앞 호출이면 실제 {@code pauseSession}을 부른 뒤 {@code false}를 돌려주거나
 * 표지 문구의 {@code RuntimeException}을 던진다. 지정은 <b>그 자리 안 순번</b>으로만 센다
 * (스레드 풀 크기가 1이라 파일 처리 순서가 정해진다).
 *
 * <p><b>단언을 거는 곳</b>: 총계 단언은 <b>고친 뒤 기대값(세션 총계 6)</b>에 건다 — 고치기 전에는
 * 이 한 줄에서만 RED여야 하고, 성공·건너뜀·실패 3칸과 하네스 유효성 단언은 고치기 전·후 모두
 * GREEN이어야 한다("그 열은 안 바뀐다"의 증거). 틈에 걸린 파일이 다시 분석되지 않는다는 모습(O15)은
 * {@code System.out} 출력으로만 남기고 단언하지 않는다(게이트1 ⑤).
 *
 * <p><b>하네스 충실도</b>: 선례 {@code ResumeHistoryCounterSyncTest}의 교정 3건을 승계한다 —
 * ① {@code initializeFileList} 목이 실제 구현처럼 세션 총계를 채운다 ② 이력 카운터는 nullable
 * {@code Integer}라 목 안에서 언박싱하지 않는다 ③ {@code findBySessionId}가 "마지막에 저장된 이력"을
 * 돌려준다. 다만 종료 대기는 선례의 {@code currentPhase} 폴링을 쓰지 않고 <b>재개 스레드를 붙잡아
 * {@code join}</b> 한다 — 틈에서 부른 {@code pauseSession}이 먼저 {@code PAUSED}를 써 버리면 폴링이
 * 재개 스레드가 끝나기 전에 풀린다(선례 하네스의 약점).
 */
class MainApiControllerHistoryTotalReproductionTest {

  private static final String SID = "sid-history-total-reproduction";
  private static final String USERNAME = "jhjung";
  private static final Long USER_SEQ = 81L;
  private static final String STUB_COMMENT_PREFIX = "// [AI 주석] 테스트 대역이 생성한 주석";
  private static final int FILE_COUNT = 6;
  private static final long RESUME_WAIT_MS = 60_000L;

  /** 부른 자리 — 스택의 메서드 이름을 이 세 가지로 가린다. */
  private static final String SITE_LOOP_HEAD = "루프 맨 앞";
  private static final String SITE_ANALYZE_FILE_ENTRY = "analyzeFile 입구";
  private static final String SITE_OTHER = "기타 자리";

  /** ㉢ 재현용 예외의 표지 문구 — 루프 본문의 analyzeFile 밖에서 던져졌음을 로그에서 알아보기 위한 것. */
  private static final String MARKER = "테스트 대역 표지: 루프 본문 analyzeFile 밖에서 던진 예외";

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
  private RecordingSessionState session;
  private Authentication ownerAuth;

  private final List<HistoryRow> savedHistoryRows = new CopyOnWriteArrayList<>();
  private final AtomicReference<AnalysisHistory> persistedHistory = new AtomicReference<>();

  /** 부른 자리와 결과의 순서 기록 — 판정 근거이자 하네스 유효성 단언의 입력. */
  private final List<String> callOrder = new CopyOnWriteArrayList<>();
  /** 하네스가 전제를 못 지킨 사실 — 풀 스레드에서는 단언을 던질 수 없으므로 모아 두고 시험 스레드가 본다. */
  private final List<String> harnessProblems = new CopyOnWriteArrayList<>();
  /** 자리별 호출 순번(단계마다 0으로 되돌린다). */
  private final Map<String, AtomicInteger> siteCounters = new ConcurrentHashMap<>();
  /** 같은 풀 스레드에서 루프 맨 앞 → analyzeFile 입구로 이어지는 같은 파일을 잇는다. */
  private final ThreadLocal<String> currentLoopFile = new ThreadLocal<>();
  private final AtomicReference<List<String>> phaseFileNames = new AtomicReference<>(List.of());

  /** 지정: 이 단계의 루프 맨 앞 몇 번째 호출에서 개입할지(0 = 개입 없음). */
  private final AtomicInteger pauseAtLoopHead = new AtomicInteger(0);
  private final AtomicInteger throwAtLoopHead = new AtomicInteger(0);
  private final AtomicInteger interventions = new AtomicInteger(0);

  private final AtomicInteger llmCalls = new AtomicInteger();
  private final AtomicInteger llmCallsTotal = new AtomicInteger();
  private final AtomicInteger pauseOnCall = new AtomicInteger(0);
  private final AtomicInteger llmPauseRequests = new AtomicInteger(0);
  private final List<String> llmCalledFiles = new CopyOnWriteArrayList<>();

  /** 기동된 재개 스레드 — 도착 순서대로 쌓고, 그 스레드를 join해서 종료를 기다린다. */
  private final List<Thread> resumeThreads = new CopyOnWriteArrayList<>();

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

    session = new RecordingSessionState(SID, srcRoot.toString(), outRoot.toString());
    session.setUsername(USERNAME);
    session.setUserId(USER_SEQ);
    session.setGenerateReadme(false);

    // 재개 로직의 첫 문장이 getSession이고 그 프레임은 람다가 아니라 runAnalysisResume 그대로다 —
    // 풀 스레드의 루프 맨 앞 호출은 lambda$runAnalysisResume$… 프레임이라 여기 걸리지 않는다.
    when(sessionManager.getSession(SID)).thenAnswer(invocation -> {
      if (calledFromResumeBody()) {
        Thread current = Thread.currentThread();
        if (!resumeThreads.contains(current)) {
          resumeThreads.add(current);
        }
      }
      return session;
    });

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
          llmCallsTotal.incrementAndGet();
          // String.valueOf(invocation.getArgument(1))로 쓰면 안 된다 — 오버로드 해소가
          // valueOf(char[])를 고르면서 제네릭 T가 char[]로 추론되고, 목 안에서 String을
          // char[]로 캐스팅하다 ClassCastException이 나 모든 파일이 실패한다.
          String fileName = invocation.getArgument(1);
          llmCalledFiles.add(fileName);
          if (n == pauseOnCall.get()) {
            Map<String, Object> pauseResponse =
                controller.pauseSession(Map.of("sessionId", SID), ownerAuth);
            if (Boolean.TRUE.equals(pauseResponse.get("success"))) {
              llmPauseRequests.incrementAndGet();
              callOrder.add("LLM 호출 " + n + "회차(" + fileName + ") 중 일시정지 요청 성공");
            } else {
              harnessProblems.add("LLM 호출 " + n + "회차의 일시정지 요청 실패: " + pauseResponse);
            }
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
  // ㉠ 최초 실행의 틈
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void HT1_최초실행_마지막파일의_틈에_일시정지가_걸려도_완료이력_총계는_세션_총계다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    pauseAtLoopHead.set(FILE_COUNT);        // 6번째(마지막) 파일의 루프 맨 앞 — 그 자리 안 순번
    invokeRunAnalysis();

    printObservation("HT1 ㉠ 최초 실행");
    assertGapOrder("HT1", "F6.java");
    assertHarnessValid("HT1", 1, 0, List.of("F1.java", "F2.java", "F3.java", "F4.java", "F5.java"));
    assertHistoryTotals("HT1", FILE_COUNT, 5, 0, 0);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // ㉡ 재개의 틈 → 일시정지 → 다음 재개에서 완료
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void HT2_재개의_틈에_걸린_파일이_다음_재개_완료이력_총계에서_빠지지_않는다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    pauseOnCall.set(2);                     // F1·F2 성공 후 사용자 일시정지 → 대기 F3~F6
    invokeRunAnalysis();
    assertEquals("PAUSED", session.getCurrentPhase(),
        "HT2 1단계: 최초 실행이 일시정지로 끝나지 않았다. 순서 기록=" + callOrder);

    beginPhase("재개 1", fileNames(3, FILE_COUNT));
    pauseAtLoopHead.set(1);                 // 첫 재개 파일(F3)의 루프 맨 앞
    joinResume("재개 1");
    System.out.println("[HT2 재개 1 종료 뒤 대기 목록] " + session.getPendingFilePaths());

    beginPhase("재개 2", fileNames(4, FILE_COUNT));
    joinResume("재개 2");

    printObservation("HT2 ㉡ 재개의 틈");
    assertGapOrder("HT2", "F3.java");
    assertHarnessValid("HT2", 1, 1,
        List.of("F1.java", "F2.java", "F4.java", "F5.java", "F6.java"));
    assertHistoryTotals("HT2", FILE_COUNT, 5, 0, 0);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // ㉢ 루프 본문의 analyzeFile 밖 예외
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void HT3_최초실행_루프본문_analyzeFile_밖_예외도_완료이력_총계에서_빠지지_않는다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    throwAtLoopHead.set(3);                 // 3번째 파일(F3)의 루프 맨 앞에서 예외
    invokeRunAnalysis();

    printObservation("HT3 ㉢ 최초 실행");
    assertThrowOrder("HT3", "F3.java");
    assertHarnessValid("HT3", 1, 0,
        List.of("F1.java", "F2.java", "F4.java", "F5.java", "F6.java"));
    assertHistoryTotals("HT3", FILE_COUNT, 5, 0, 0);
  }

  @Test
  void HT4_재개_루프본문_analyzeFile_밖_예외도_완료이력_총계에서_빠지지_않는다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    pauseOnCall.set(2);                     // F1·F2 성공 후 사용자 일시정지 → 대기 F3~F6
    invokeRunAnalysis();
    assertEquals("PAUSED", session.getCurrentPhase(),
        "HT4 1단계: 최초 실행이 일시정지로 끝나지 않았다. 순서 기록=" + callOrder);

    beginPhase("재개", fileNames(3, FILE_COUNT));
    throwAtLoopHead.set(2);                 // 2번째 재개 파일(F4)의 루프 맨 앞에서 예외
    joinResume("재개");

    printObservation("HT4 ㉢ 재개");
    assertThrowOrder("HT4", "F4.java");
    assertHarnessValid("HT4", 1, 1,
        List.of("F1.java", "F2.java", "F3.java", "F5.java", "F6.java"));
    assertHistoryTotals("HT4", FILE_COUNT, 5, 0, 0);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 정상 대조 — 고치기 전·후 모두 GREEN이어야 한다(총계 줄 수정이 정상 경로를 건드리지 않는다)
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void HN1_최초실행_전부_성공하면_완료이력_총계는_지금도_세션_총계다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    invokeRunAnalysis();

    printObservation("HN1 정상 대조");
    assertHarnessValid("HN1", 0, 0, fileNames(1, FILE_COUNT));
    assertHistoryTotals("HN1", FILE_COUNT, FILE_COUNT, 0, 0);
  }

  @Test
  void HN2_일시정지_후_재개로_전부_성공하면_완료이력_총계는_지금도_세션_총계다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    pauseOnCall.set(2);
    invokeRunAnalysis();
    assertEquals("PAUSED", session.getCurrentPhase(),
        "HN2 1단계: 최초 실행이 일시정지로 끝나지 않았다. 순서 기록=" + callOrder);

    beginPhase("재개", fileNames(3, FILE_COUNT));
    joinResume("재개");

    printObservation("HN2 정상 대조");
    assertHarnessValid("HN2", 0, 1, fileNames(1, FILE_COUNT));
    assertHistoryTotals("HN2", FILE_COUNT, FILE_COUNT, 0, 0);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 시험 전용 세션 — 부른 자리를 스택으로 가리고 지정한 자리에서만 개입한다
  // ────────────────────────────────────────────────────────────────────────────

  private class RecordingSessionState extends SessionState {

    RecordingSessionState(String sessionId, String sourcePath, String outputPath) {
      super(sessionId, sourcePath, outputPath);
    }

    @Override
    public boolean shouldStop() {
      boolean original = super.shouldStop();
      String caller = callerMethodName();
      String site = siteOf(caller);
      int ordinal = siteCounters.computeIfAbsent(site, key -> new AtomicInteger()).incrementAndGet();

      if (SITE_LOOP_HEAD.equals(site)) {
        String fileLabel = phaseFileLabel(ordinal);
        currentLoopFile.set(fileLabel);

        if (ordinal == throwAtLoopHead.get()) {
          interventions.incrementAndGet();
          callOrder.add(site + "(" + fileLabel + ") 순번" + ordinal + " [" + caller + "] → 표지 예외");
          throw new RuntimeException(MARKER + " — 파일=" + fileLabel);
        }

        if (ordinal == pauseAtLoopHead.get()) {
          if (original) {
            harnessProblems.add("지정한 루프 맨 앞 호출(순번 " + ordinal + ", " + fileLabel
                + ")에서 원래 shouldStop()이 이미 true였다 — 틈이 성립하지 않는다");
          }
          interventions.incrementAndGet();
          callOrder.add(site + "(" + fileLabel + ") 순번" + ordinal + " [" + caller + "]=false");
          Map<String, Object> pauseResponse =
              controller.pauseSession(Map.of("sessionId", SID), ownerAuth);
          if (Boolean.TRUE.equals(pauseResponse.get("success"))) {
            callOrder.add("일시정지 요청 성공(" + fileLabel + ")");
          } else {
            callOrder.add("일시정지 요청 실패(" + fileLabel + "): " + pauseResponse);
            harnessProblems.add("지정한 자리의 일시정지 요청이 실패했다: " + pauseResponse);
          }
          // 루프 맨 앞은 통과시킨다 — 바로 뒤 analyzeFile 입구 검사가 중단을 본다(= 틈).
          return false;
        }

        callOrder.add(site + "(" + fileLabel + ") 순번" + ordinal + " [" + caller + "]=" + original);
        return original;
      }

      if (SITE_ANALYZE_FILE_ENTRY.equals(site)) {
        String fileLabel = currentLoopFile.get();
        callOrder.add(site + "(" + fileLabel + ") 순번" + ordinal + " [" + caller + "]=" + original);
        return original;
      }

      callOrder.add(site + " 순번" + ordinal + " [" + caller + "]=" + original);
      return original;
    }
  }

  /** 호출 스택에서 처음 만나는 {@code MainApiController} 프레임의 메서드 이름. */
  private static String callerMethodName() {
    return StackWalker.getInstance()
        .walk(frames -> frames
            .filter(frame -> MainApiController.class.getName().equals(frame.getClassName()))
            .map(StackWalker.StackFrame::getMethodName)
            .findFirst())
        .orElse("(MainApiController 프레임 없음)");
  }

  private static String siteOf(String callerMethodName) {
    if (callerMethodName.startsWith("lambda$runAnalysis$")
        || callerMethodName.startsWith("lambda$runAnalysisResume$")) {
      return SITE_LOOP_HEAD;
    }
    if ("analyzeFile".equals(callerMethodName)) {
      return SITE_ANALYZE_FILE_ENTRY;
    }
    return SITE_OTHER;
  }

  /** 재개 스레드 판정 — 람다가 아닌 {@code runAnalysisResume} 프레임이 그대로 있는 호출. */
  private static boolean calledFromResumeBody() {
    return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
        MainApiController.class.getName().equals(frame.getClassName())
            && "runAnalysisResume".equals(frame.getMethodName())));
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 단계 운영 · 하네스 유효성 · 판정
  // ────────────────────────────────────────────────────────────────────────────

  /** 한 단계(최초 실행 / 각 재개)를 시작하기 전에 자리별 순번·LLM 순번·지정을 0으로 되돌린다. */
  private void beginPhase(String phaseLabel, List<String> fileNamesOfPhase) {
    siteCounters.clear();
    currentLoopFile.remove();
    llmCalls.set(0);
    pauseOnCall.set(0);
    pauseAtLoopHead.set(0);
    throwAtLoopHead.set(0);
    phaseFileNames.set(List.copyOf(fileNamesOfPhase));
    callOrder.add("── 단계 시작: " + phaseLabel + " (대상 " + fileNamesOfPhase + ") ──");
  }

  private String phaseFileLabel(int ordinal) {
    List<String> names = phaseFileNames.get();
    if (ordinal < 1 || ordinal > names.size()) {
      return "단계 대상 밖(순번" + ordinal + ")";
    }
    return names.get(ordinal - 1);
  }

  private static List<String> fileNames(int from, int to) {
    List<String> names = new ArrayList<>();
    for (int i = from; i <= to; i++) {
      names.add("F" + i + ".java");
    }
    return names;
  }

  private void invokeRunAnalysis() throws Exception {
    Method method = MainApiController.class.getDeclaredMethod("runAnalysis",
        String.class, String.class, String.class, boolean.class, Long.class, String.class,
        Set.class, boolean.class);
    method.setAccessible(true);
    method.invoke(controller, SID, srcRoot.toString(), outRoot.toString(), false,
        USER_SEQ, USERNAME, null, false);
  }

  /**
   * 재개를 반사로 기동하고 <b>그 재개 스레드를 join</b> 해서 끝까지 기다린다. {@code currentPhase}를
   * 폴링하지 않는다 — 틈에서 부른 {@code pauseSession}이 먼저 {@code PAUSED}를 쓰므로 폴링은
   * 재개 스레드가 끝나기 전에 풀린다.
   */
  @SuppressWarnings("unchecked")
  private void joinResume(String phaseLabel) throws Exception {
    int before = resumeThreads.size();
    Method method = MainApiController.class.getDeclaredMethod(
        "resumePendingFilesInThread", SessionState.class, String.class);
    method.setAccessible(true);
    Map<String, Object> response = (Map<String, Object>) method.invoke(controller, session, SID);
    assertEquals(Boolean.TRUE, response.get("success"),
        phaseLabel + " 진입점 실패: " + response.get("message"));

    long deadline = System.currentTimeMillis() + RESUME_WAIT_MS;
    Thread resumeThread = null;
    while (System.currentTimeMillis() < deadline) {
      if (resumeThreads.size() > before) {
        resumeThread = resumeThreads.get(before);
        break;
      }
      Thread.sleep(5L);
    }
    assertNotNull(resumeThread, phaseLabel + ": 재개 스레드가 " + (RESUME_WAIT_MS / 1000)
        + "초 안에 첫 문장(getSession)에 도착하지 않았다. 도착한 스레드=" + resumeThreads);
    resumeThread.join(RESUME_WAIT_MS);
    assertFalse(resumeThread.isAlive(), phaseLabel + ": 재개 스레드가 " + (RESUME_WAIT_MS / 1000)
        + "초 안에 끝나지 않았다(join 상한 초과). name=" + resumeThread.getName());
  }

  /**
   * 하네스 유효성 ① (틈 사례) — "루프 맨 앞(Fk)=false → 일시정지 요청 성공 → analyzeFile 입구(Fk)=true"가
   * 이 순서로 정확히 1번.
   */
  private void assertGapOrder(String label, String fileLabel) {
    List<String> order = List.copyOf(callOrder);
    int head = indexOfOnly(order,
        line -> line.startsWith(SITE_LOOP_HEAD + "(" + fileLabel + ")") && line.endsWith("=false"),
        label + " (하네스 ①): 루프 맨 앞(" + fileLabel + ")=false 기록");
    int pause = indexOfOnly(order,
        line -> line.startsWith("일시정지 요청 성공(" + fileLabel + ")"),
        label + " (하네스 ①): 일시정지 요청 성공(" + fileLabel + ") 기록");
    int entry = indexOfOnly(order,
        line -> line.startsWith(SITE_ANALYZE_FILE_ENTRY + "(" + fileLabel + ")")
            && line.endsWith("=true"),
        label + " (하네스 ①): analyzeFile 입구(" + fileLabel + ")=true 기록");
    assertTrue(head < pause && pause < entry, label + " (하네스 ①): 세 기록이 "
        + "루프 맨 앞=false → 일시정지 요청 성공 → analyzeFile 입구=true 순서가 아니다"
        + " (자리 " + head + "·" + pause + "·" + entry + "). 순서 기록=" + order);
  }

  /** 하네스 유효성 ① (예외 사례) — "루프 맨 앞(Fk) → 표지 예외"가 1번이고 그 파일은 입구에 닿지 않는다. */
  private void assertThrowOrder(String label, String fileLabel) {
    List<String> order = List.copyOf(callOrder);
    indexOfOnly(order,
        line -> line.startsWith(SITE_LOOP_HEAD + "(" + fileLabel + ")")
            && line.endsWith("→ 표지 예외"),
        label + " (하네스 ①): 루프 맨 앞(" + fileLabel + ") → 표지 예외 기록");
    assertEquals(0, count(order,
        line -> line.startsWith(SITE_ANALYZE_FILE_ENTRY + "(" + fileLabel + ")")),
        label + " (하네스 ①): 예외를 던진 파일이 analyzeFile 입구에 닿았다. 순서 기록=" + order);
  }

  /** 하네스 유효성 ②③④ — 지정 개입 횟수 · LLM 호출(파일과 순서) · 종료 phase. */
  private void assertHarnessValid(String label, int expectedInterventions,
      int expectedLlmPauseRequests, List<String> expectedLlmFiles) {
    assertTrue(harnessProblems.isEmpty(),
        label + " 하네스 전제 위반: " + harnessProblems + " | 순서 기록=" + callOrder);
    assertEquals(expectedInterventions, interventions.get(),
        label + " (하네스 ②): 지정한 개입 횟수. 순서 기록=" + callOrder);
    assertEquals(expectedLlmPauseRequests, llmPauseRequests.get(),
        label + " (하네스 ②): LLM 호출 중 일시정지 요청 횟수. 순서 기록=" + callOrder);
    assertEquals(expectedLlmFiles, List.copyOf(llmCalledFiles),
        label + " (하네스 ③): LLM에 보낸 파일과 그 순서. 순서 기록=" + callOrder);
    assertEquals(expectedLlmFiles.size(), llmCallsTotal.get(),
        label + " (하네스 ③): LLM 호출 수. LLM에 보낸 파일=" + llmCalledFiles);
    assertEquals("COMPLETED", session.getCurrentPhase(), label + " (하네스 ④): 종료 phase가 "
        + "COMPLETED가 아니다 — 카운터를 판정 근거로 쓸 수 없다. 순서 기록=" + callOrder);
  }

  /**
   * 판정 — 성공·건너뜀·실패 3칸은 <b>현재 값</b>(고치기 전·후 불변)이고, 총계는
   * <b>고친 뒤 기대값</b>(세션 전체 파일 수)이다. 3칸을 먼저 봐서 고치기 전 RED가 총계 한 줄에서만
   * 나는지 알 수 있게 한다.
   */
  private void assertHistoryTotals(String label, int expectedTotal, int expectedSuccess,
      int expectedSkip, int expectedFailure) {
    HistoryRow last = lastHistory();
    assertEquals("COMPLETED", last.status(),
        label + ": 완료 이력 status. 이력=" + last + " | 이력 저장 전수=" + savedHistoryRows);
    assertEquals(expectedSuccess, last.success(), label + ": 이력 성공 수(현재 값). 이력=" + last);
    assertEquals(expectedSkip, last.skip(), label + ": 이력 건너뜀 수(현재 값). 이력=" + last);
    assertEquals(expectedFailure, last.failure(), label + ": 이력 실패 수(현재 값). 이력=" + last);
    assertEquals(expectedTotal, last.total(), label + ": 완료 이력 총계는 세션 전체 파일 수여야 한다."
        + " 기대 " + expectedTotal + " / 실제 " + last.total()
        + " (세션 총계=" + session.getTotalFiles() + ", 이력=" + last + ")");
  }

  /** O15 모습을 포함한 관찰 출력 — 단언하지 않는다(게이트1 ⑤). */
  private void printObservation(String label) {
    System.out.println("[" + label + " 순서 기록] " + callOrder);
    System.out.println("[" + label + " LLM에 보낸 파일] " + llmCalledFiles);
    System.out.println("[" + label + " 이력 저장 전수] " + savedHistoryRows);
    System.out.println("[" + label + " 세션] 총계=" + session.getTotalFiles()
        + " 성공=" + session.getStatistics().getSuccessCount()
        + " 건너뜀=" + session.getStatistics().getSkipCount()
        + " 실패=" + session.getStatistics().getFailureCount()
        + " 처리=" + session.getProcessedFiles() + " phase=" + session.getCurrentPhase());
    System.out.println("[" + label + " 세션 대기 목록] " + session.getPendingFilePaths());
    System.out.println("[" + label + " 세션 실패 목록] " + session.getFailedFilePaths());
    System.out.println("[" + label + " O15 관찰(단언 없음)] 틈·예외에 걸린 파일은 LLM에 가지 않았고"
        + " 대기 목록에도 남지 않는다 — 다시 분석되지 않는다는 뜻이다. LLM에 간 파일 수="
        + llmCalledFiles.size() + ", 세션 총계=" + session.getTotalFiles());
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 도우미
  // ────────────────────────────────────────────────────────────────────────────

  private static int nz(Integer value) {
    return value == null ? 0 : value;
  }

  private HistoryRow lastHistory() {
    assertFalse(savedHistoryRows.isEmpty(),
        "이력 save가 한 번도 없었다 — 하네스가 마무리 분기에 도달하지 못했다. 순서 기록=" + callOrder);
    return savedHistoryRows.get(savedHistoryRows.size() - 1);
  }

  private static int indexOfOnly(List<String> order, Predicate<String> match, String what) {
    List<Integer> hits = new ArrayList<>();
    for (int i = 0; i < order.size(); i++) {
      if (match.test(order.get(i))) {
        hits.add(i);
      }
    }
    assertEquals(1, hits.size(),
        what + "가 정확히 1번이 아니다(" + hits.size() + "번). 순서 기록=" + order);
    return hits.get(0);
  }

  private static int count(List<String> order, Predicate<String> match) {
    int found = 0;
    for (String line : order) {
      if (match.test(line)) {
        found++;
      }
    }
    return found;
  }

  private void setField(String name, Object value) throws Exception {
    Field field = MainApiController.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(controller, value);
  }
}
