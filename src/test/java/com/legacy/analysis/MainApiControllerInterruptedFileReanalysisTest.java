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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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
 * TASK-001 (work-order 2026-10-interrupted-file-reanalysis v2, REQ-001·REQ-004) — "분석되지 않은
 * 파일이 다시 분석되지 않는다"는 두 길을 <b>순서를 강제해</b> 결정적으로 재현한다.
 *
 * <p><b>고치려는 결함 두 가지</b>(설계 §3·§4):
 * ㉠ (O15) {@code analyzeFile} 입구에서 정지 신호를 보고 처리하지 않은 파일(= 루프 맨 앞 검사 통과
 * 직후 ~ 입구 검사 전 "틈"에 걸린 파일)이 {@code SKIPPED}+{@code CANCELLED}로 돌아오면서도 완료
 * 목록에 들어가 대기 목록에서 빠진다 → 이어서 분석해도 영영 분석되지 않는다.
 * ㉡ (O43·O56) 루프 본문의 {@code analyzeFile} 밖에서 끝난 파일(예외·{@code Error})이 어느 칸에도
 * 세어지지 않는다 → 전부 실패한 실행조차 "완료"로 보인다.
 *
 * <p><b>순서 강제 방법</b>(설계 §6.2 — 지난 재현 시험
 * {@code MainApiControllerHistoryTotalReproductionTest}의 하네스를 <b>이 파일에 다시 썼다</b>.
 * 상속·공유하지 않는다): 시험 전용 {@link SessionState} 하위 클래스가 {@code shouldStop()}을 재정의해
 * ① 원래 값을 계산하고 ② <b>부른 자리</b>(호출 스택에서 처음 만나는 {@code MainApiController} 프레임의
 * 메서드 이름 — 루프 맨 앞은 {@code lambda$runAnalysis$}·{@code lambda$runAnalysisResume$} 접두,
 * 입구는 {@code analyzeFile})와 결과를 순서 기록에 남기고 ③ 지정한 루프 맨 앞 호출이면 실제
 * {@code pauseSession}을 부른 뒤 {@code false}를 돌려주거나(= 틈), 표지 문구의
 * {@code RuntimeException} 또는 시험 전용 {@link HarnessInjectedError}를 던진다. 지정은 <b>그 자리 안
 * 순번</b>으로만 센다(스레드 풀 크기 1이라 파일 처리 순서가 정해진다).
 *
 * <p><b>단언을 거는 순서</b>(설계 §6.2, IR-16): 단계(최초 실행·재개 n)마다 <b>하네스 유효성 →
 * 결과</b> 순서로 단언하고 다음 단계는 그 뒤에 시작한다. 틈 순서 기록 단언은 <b>그 틈이 일어난 단계
 * 직후</b>에 건다 — 뒤 재개 단계에서 같은 파일이 루프 맨 앞을 다시 지나면 "=false" 기록이 하나 더
 * 생기기 때문이다. 결과 단언 = 종료 phase · 대기 목록 · 이력 행(+ 마지막 행의 칸 합).
 *
 * <p><b>기대값은 고친 뒤 값</b>이다 — 고치기 전에는 IG1~IG4 · IX1~IX5 9건이 <b>결과 단언</b>에서
 * RED여야 하고, IN1~IN4 · IM1 5건은 고치기 전·후 모두 GREEN이어야 한다(설계 §6.3).
 */
class MainApiControllerInterruptedFileReanalysisTest {

  private static final String SID = "sid-interrupted-file-reanalysis";
  private static final String USERNAME = "jhjung";
  private static final Long USER_SEQ = 81L;
  private static final String STUB_COMMENT_PREFIX = "// [AI 주석] 테스트 대역이 생성한 주석";
  private static final int FILE_COUNT = 6;
  private static final long RESUME_WAIT_MS = 60_000L;

  /** 이미 처리됨 표지 — {@code isAlreadyPatched}의 하위 호환 경로가 인식하는 문자열. */
  private static final String PATCHED_MARKER = "[AI 한글 주석 보완 완료]";

  /** 부른 자리 — 스택의 메서드 이름을 이 세 가지로 가린다. */
  private static final String SITE_LOOP_HEAD = "루프 맨 앞";
  private static final String SITE_ANALYZE_FILE_ENTRY = "analyzeFile 입구";
  private static final String SITE_OTHER = "기타 자리";

  /** 루프 본문 {@code analyzeFile} 밖에서 던진 것임을 로그·순서 기록에서 알아보기 위한 표지. */
  private static final String MARKER = "테스트 대역 표지: 루프 본문 analyzeFile 밖에서 던진 예외";
  private static final String ERROR_MARKER = "테스트 대역 표지: 루프 본문 analyzeFile 밖에서 던진 Error";

  private static final String ORDER_SUFFIX_THROW = "→ 표지 예외";
  private static final String ORDER_SUFFIX_ERROR = "→ 표지 Error";

  /**
   * (2026-10-resume-tail-fixes TASK-001) 하네스 추가 1종 — "LLM 지정 호출 1회 실패". 지정한 순번의
   * LLM 호출에서만 이 표지 예외를 던진다. {@code maxRetries=0}이라 재시도 없이 그 파일만
   * {@code FAILED}로 끝난다 — TF1이 "재개 틈에 걸린 파일의 이전 실패 기록이 남는가"를 보기 위해
   * 최초 실행에서 실패 파일 1개를 만드는 데 쓴다.
   */
  private static final String LLM_FAILURE_MARKER = "테스트 대역 표지: LLM 지정 호출 1회 실패";

  /** 시험 전용 {@code Error} 하위 클래스 — {@code catch (Exception)}을 지나친다(O56). */
  static class HarnessInjectedError extends Error {
    HarnessInjectedError(String message) {
      super(message);
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
  private final AtomicInteger errorAtLoopHead = new AtomicInteger(0);
  /** (V4-1 ②) 지정한 루프 맨 앞 호출에서 실제 취소 API를 부른다(0 = 개입 없음). */
  private final AtomicInteger cancelAtLoopHead = new AtomicInteger(0);
  /** 모든 루프 맨 앞에서 표지 예외를 던진다(IX4). */
  private final AtomicBoolean throwAtEveryLoopHead = new AtomicBoolean(false);
  private final AtomicInteger interventions = new AtomicInteger(0);

  private final AtomicInteger llmCalls = new AtomicInteger();
  private final AtomicInteger llmCallsTotal = new AtomicInteger();
  private final AtomicInteger pauseOnCall = new AtomicInteger(0);
  private final AtomicInteger llmPauseRequests = new AtomicInteger(0);
  private final List<String> llmCalledFiles = new CopyOnWriteArrayList<>();
  /** (TASK-001) 지정: 이 단계의 LLM 호출 몇 번째에서 표지 예외를 던질지(0 = 개입 없음). */
  private final AtomicInteger failAtLlmCall = new AtomicInteger(0);
  /** (TASK-001) 실제로 던진 LLM 실패 개입 수와 그 파일 — 하네스 유효성 단언의 입력. */
  private final AtomicInteger llmInjectedFailures = new AtomicInteger(0);
  private final List<String> llmFailedFiles = new CopyOnWriteArrayList<>();

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

    // (V4-1 ④) 세션 관리자 목의 취소가 실제 구현(AnalysisSessionManager.cancelSession —
    // session.cancel() + setStatus("CANCELLED"))처럼 세션을 취소 상태로 만든다. 목은 void라 doAnswer
    // 형태다. 이 대역이 no-op이면 취소가 일시정지로 끝나 사례가 무의미해지므로, 첫 사용 자리(아래
    // 취소 개입)에서 바로 isCancelled()를 양성 대조한다.
    doAnswer(inv -> {
      session.cancel();
      session.setStatus("CANCELLED");
      return null;
    }).when(sessionManager).cancelSession(SID);

    when(llmModelOptionService.getActiveFailoverTarget()).thenReturn(Optional.empty());

    when(claudeService.analyzeCodeWithClaude(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> {
          int n = llmCalls.incrementAndGet();
          llmCallsTotal.incrementAndGet();
          // IR-16 — String.valueOf(invocation.getArgument(1))로 쓰면 안 된다. 오버로드 해소가
          // valueOf(char[])를 고르면서 제네릭 T가 char[]로 추론되고, 목 안에서 String을 char[]로
          // 캐스팅하다 ClassCastException이 나 모든 파일이 실패한다. 타입 지정 지역 변수로 먼저 받는다.
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
          // (TASK-001) 지정한 LLM 호출 1회만 실패시킨다 — 그 파일은 FAILED로 끝나 실패 목록에 남는다.
          if (n == failAtLlmCall.get()) {
            llmInjectedFailures.incrementAndGet();
            llmFailedFiles.add(fileName);
            callOrder.add("LLM 호출 " + n + "회차(" + fileName + ") " + LLM_FAILURE_MARKER);
            throw new RuntimeException(LLM_FAILURE_MARKER + " — 파일=" + fileName);
          }
          return STUB_COMMENT_PREFIX + "\n" + invocation.getArgument(0);
        });

    User owner = new User(USERNAME, USERNAME + "@example.com", "hash");
    owner.setSeq(USER_SEQ);
    owner.setRoles(Set.of(new Role("USER", "일반 사용자")));
    ownerAuth = new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities());

    for (int i = 1; i <= FILE_COUNT; i++) {
      writeSourceFile(i, "public class F" + i + " { void m() {} }\n");
    }
  }

  // ────────────────────────────────────────────────────────────────────────────
  // IG — 틈에 걸린 파일(O15). 고치기 전 RED
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void IG1_최초실행_마지막파일이_틈에_걸리면_일시정지로_끝나고_이어서_분석된다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    pauseAtLoopHead.set(FILE_COUNT);        // 6번째(마지막) 파일의 루프 맨 앞 — 그 자리 안 순번
    invokeRunAnalysis();

    printObservation("IG1 1단계(최초 실행)");
    assertGapOrder("IG1 1단계", "F6.java");
    assertHarnessValid("IG1 1단계", 1, 0, fileNames(1, 5));
    assertPhase("IG1 1단계", "PAUSED");
    assertPendingFileNames("IG1 1단계", List.of("F6.java"));
    assertHistoryRow("IG1 1단계", "PAUSED", FILE_COUNT, 5, 0, 0);

    beginPhase("재개", List.of("F6.java"));
    joinResume("재개");

    printObservation("IG1 2단계(재개)");
    assertHarnessValid("IG1 2단계", 1, 0, fileNames(1, FILE_COUNT));
    assertPhase("IG1 2단계", "COMPLETED");
    assertHistoryRow("IG1 2단계", "COMPLETED", FILE_COUNT, FILE_COUNT, 0, 0);
    assertColumnSum("IG1 2단계");
  }

  @Test
  void IG2_최초실행_중간파일이_틈에_걸리면_대기목록에_남아_이어서_분석된다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    pauseAtLoopHead.set(3);                 // 3번째 파일(F3)의 루프 맨 앞 — 뒤 파일은 루프 맨 앞에서 멈춘다
    invokeRunAnalysis();

    printObservation("IG2 1단계(최초 실행)");
    assertGapOrder("IG2 1단계", "F3.java");
    assertHarnessValid("IG2 1단계", 1, 0, fileNames(1, 2));
    assertPhase("IG2 1단계", "PAUSED");
    assertPendingFileNames("IG2 1단계", fileNames(3, FILE_COUNT));
    assertHistoryRow("IG2 1단계", "PAUSED", FILE_COUNT, 2, 0, 0);

    beginPhase("재개", fileNames(3, FILE_COUNT));
    joinResume("재개");

    printObservation("IG2 2단계(재개)");
    assertHarnessValid("IG2 2단계", 1, 0, fileNames(1, FILE_COUNT));
    assertPhase("IG2 2단계", "COMPLETED");
    assertHistoryRow("IG2 2단계", "COMPLETED", FILE_COUNT, FILE_COUNT, 0, 0);
    assertColumnSum("IG2 2단계");
  }

  @Test
  void IG3_재개의_첫파일이_틈에_걸리면_다음_재개에서_분석된다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    pauseOnCall.set(2);                     // F1·F2 성공 후 사용자 일시정지 → 대기 F3~F6
    invokeRunAnalysis();

    printObservation("IG3 1단계(최초 실행)");
    assertHarnessValid("IG3 1단계", 0, 1, fileNames(1, 2));
    assertPhase("IG3 1단계", "PAUSED");
    assertPendingFileNames("IG3 1단계", fileNames(3, FILE_COUNT));
    assertHistoryRow("IG3 1단계", "PAUSED", FILE_COUNT, 2, 0, 0);

    beginPhase("재개 1", fileNames(3, FILE_COUNT));
    pauseAtLoopHead.set(1);                 // 첫 재개 파일(F3)의 루프 맨 앞
    joinResume("재개 1");

    printObservation("IG3 2단계(재개 1)");
    assertGapOrder("IG3 2단계", "F3.java");
    assertHarnessValid("IG3 2단계", 1, 1, fileNames(1, 2));
    assertPhase("IG3 2단계", "PAUSED");
    assertPendingFileNames("IG3 2단계", fileNames(3, FILE_COUNT));
    assertHistoryRow("IG3 2단계", "PAUSED", FILE_COUNT, 2, 0, 0);

    beginPhase("재개 2", fileNames(3, FILE_COUNT));
    joinResume("재개 2");

    printObservation("IG3 3단계(재개 2)");
    assertHarnessValid("IG3 3단계", 1, 1, fileNames(1, FILE_COUNT));
    assertPhase("IG3 3단계", "COMPLETED");
    assertHistoryRow("IG3 3단계", "COMPLETED", FILE_COUNT, FILE_COUNT, 0, 0);
    assertColumnSum("IG3 3단계");
  }

  @Test
  void IG4_재개의_마지막파일이_틈에_걸리면_용량초과패스로_세지_않고_다음_재개에서_분석된다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    pauseOnCall.set(2);
    invokeRunAnalysis();

    printObservation("IG4 1단계(최초 실행)");
    assertHarnessValid("IG4 1단계", 0, 1, fileNames(1, 2));
    assertPhase("IG4 1단계", "PAUSED");
    assertPendingFileNames("IG4 1단계", fileNames(3, FILE_COUNT));
    assertHistoryRow("IG4 1단계", "PAUSED", FILE_COUNT, 2, 0, 0);

    beginPhase("재개 1", fileNames(3, FILE_COUNT));
    pauseAtLoopHead.set(4);                 // 이 재개 단계의 마지막 파일(F6)의 루프 맨 앞
    joinResume("재개 1");

    printObservation("IG4 2단계(재개 1)");
    assertGapOrder("IG4 2단계", "F6.java");
    assertHarnessValid("IG4 2단계", 1, 1, fileNames(1, 5));
    assertPhase("IG4 2단계", "PAUSED");
    assertPendingFileNames("IG4 2단계", List.of("F6.java"));
    assertHistoryRow("IG4 2단계", "PAUSED", FILE_COUNT, 5, 0, 0);

    beginPhase("재개 2", List.of("F6.java"));
    joinResume("재개 2");

    printObservation("IG4 3단계(재개 2)");
    assertHarnessValid("IG4 3단계", 1, 1, fileNames(1, FILE_COUNT));
    assertPhase("IG4 3단계", "COMPLETED");
    assertHistoryRow("IG4 3단계", "COMPLETED", FILE_COUNT, FILE_COUNT, 0, 0);
    assertColumnSum("IG4 3단계");
    assertFinalSummaryContains("IG4 3단계", "- 용량 초과 패스: 0개");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // IG5·IG6 — 틈에 걸렸는데 그 정지 신호가 "취소"인 경우. 고치기 전(93724a4)에도 GREEN이어야 할
  // 성질이고(취소는 취소로 끝난다) 지금도 GREEN이다. 틈 블록의 isCancelled() 조건이 지워지면 RED가 된다.
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void IG5_최초실행_마지막파일이_틈에_걸렸는데_취소면_일시정지가_아니라_취소로_끝난다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    cancelAtLoopHead.set(FILE_COUNT);       // 6번째(마지막) 파일의 루프 맨 앞 — 그 자리 안 순번
    invokeRunAnalysis();

    printObservation("IG5 최초 실행");
    assertCancelGapOrder("IG5", "F6.java");
    assertHarnessValid("IG5", 1, 0, fileNames(1, 5));
    assertPhase("IG5", "CANCELLED");
    assertHistoryRow("IG5", "CANCELLED", FILE_COUNT, 5, 0, 0);
    // 틈 블록이 pauseDetected를 무조건 세우면 일시정지 분기로 흘러 이 로그가 남는다(IV-15).
    assertFalse(
        session.getRecentLogLines(50).stream().anyMatch(line -> line.contains("[일시정지 완료]")),
        "IG5 (결과): 최근 로그에 [일시정지 완료]가 있다 — 취소가 일시정지로 끝났다. 최근 로그="
            + session.getRecentLogLines(50));
  }

  @Test
  void IG6_재개의_마지막파일이_틈에_걸렸는데_취소면_일시정지가_아니라_취소로_끝난다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    pauseOnCall.set(2);                     // F1·F2 성공 후 사용자 일시정지 → 대기 F3~F6
    invokeRunAnalysis();

    printObservation("IG6 1단계(최초 실행)");
    assertHarnessValid("IG6 1단계", 0, 1, fileNames(1, 2));
    assertPhase("IG6 1단계", "PAUSED");
    assertPendingFileNames("IG6 1단계", fileNames(3, FILE_COUNT));
    assertHistoryRow("IG6 1단계", "PAUSED", FILE_COUNT, 2, 0, 0);

    beginPhase("재개", fileNames(3, FILE_COUNT));
    cancelAtLoopHead.set(4);                // 이 재개 단계의 마지막 파일(F6)의 루프 맨 앞
    joinResume("재개");

    printObservation("IG6 2단계(재개)");
    assertCancelGapOrder("IG6 2단계", "F6.java");
    assertHarnessValid("IG6 2단계", 1, 1, fileNames(1, 5));
    assertPhase("IG6 2단계", "CANCELLED");
    assertHistoryRow("IG6 2단계", "CANCELLED", FILE_COUNT, 5, 0, 0);
    // 재개 쪽 틈 블록이 pauseDetected를 무조건 세우면 재개의 일시정지 분기로 흘러 이 로그가 남는다(IV-16).
    assertFalse(
        session.getRecentLogLines(50).stream().anyMatch(line -> line.contains("[일시정지]")),
        "IG6 (결과): 최근 로그에 [일시정지]가 있다 — 재개의 취소가 일시정지로 끝났다. 최근 로그="
            + session.getRecentLogLines(50));
  }

  // ────────────────────────────────────────────────────────────────────────────
  // IX — 루프 본문 analyzeFile 밖에서 끝난 파일(O43·O56). 고치기 전 RED
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void IX1_최초실행_루프본문_예외로_끝난_파일은_실패로_세어진다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    throwAtLoopHead.set(3);                 // 3번째 파일(F3)의 루프 맨 앞에서 표지 예외
    invokeRunAnalysis();

    printObservation("IX1 최초 실행");
    assertThrowOrder("IX1", "F3.java", ORDER_SUFFIX_THROW);
    assertHarnessValid("IX1", 1, 0, List.of("F1.java", "F2.java", "F4.java", "F5.java", "F6.java"));
    assertPhase("IX1", "COMPLETED");
    assertHistoryRow("IX1", "COMPLETED", FILE_COUNT, 5, 0, 1);
    assertFailedFileNames("IX1", List.of("F3.java"));
    assertColumnSum("IX1");
  }

  @Test
  void IX2_재개_루프본문_예외로_끝난_파일은_실패로_세어진다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    pauseOnCall.set(2);
    invokeRunAnalysis();

    printObservation("IX2 1단계(최초 실행)");
    assertHarnessValid("IX2 1단계", 0, 1, fileNames(1, 2));
    assertPhase("IX2 1단계", "PAUSED");
    assertPendingFileNames("IX2 1단계", fileNames(3, FILE_COUNT));
    assertHistoryRow("IX2 1단계", "PAUSED", FILE_COUNT, 2, 0, 0);

    beginPhase("재개", fileNames(3, FILE_COUNT));
    throwAtLoopHead.set(2);                 // 2번째 재개 파일(F4)의 루프 맨 앞에서 표지 예외
    joinResume("재개");

    printObservation("IX2 2단계(재개)");
    assertThrowOrder("IX2 2단계", "F4.java", ORDER_SUFFIX_THROW);
    assertHarnessValid("IX2 2단계", 1, 1,
        List.of("F1.java", "F2.java", "F3.java", "F5.java", "F6.java"));
    assertPhase("IX2 2단계", "COMPLETED");
    assertHistoryRow("IX2 2단계", "COMPLETED", FILE_COUNT, 5, 0, 1);
    assertFailedFileNames("IX2 2단계", List.of("F4.java"));
    assertColumnSum("IX2 2단계");
  }

  @Test
  void IX3_최초실행_루프본문_Error로_끝난_파일도_실패로_세어진다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    errorAtLoopHead.set(3);                 // 3번째 파일(F3)의 루프 맨 앞에서 시험 전용 Error
    invokeRunAnalysis();

    printObservation("IX3 최초 실행");
    assertThrowOrder("IX3", "F3.java", ORDER_SUFFIX_ERROR);
    assertHarnessValid("IX3", 1, 0, List.of("F1.java", "F2.java", "F4.java", "F5.java", "F6.java"));
    assertPhase("IX3", "COMPLETED");
    assertHistoryRow("IX3", "COMPLETED", FILE_COUNT, 5, 0, 1);
    assertFailedFileNames("IX3", List.of("F3.java"));
    assertColumnSum("IX3");
  }

  @Test
  void IX4_최초실행_모든파일이_루프본문_예외면_전량실패_일시정지로_끝난다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    throwAtEveryLoopHead.set(true);         // 6개 전부 루프 맨 앞에서 표지 예외
    invokeRunAnalysis();

    printObservation("IX4 최초 실행");
    assertThrowOrderAll("IX4", fileNames(1, FILE_COUNT), ORDER_SUFFIX_THROW);
    assertHarnessValid("IX4", FILE_COUNT, 0, List.of());
    assertPhase("IX4", "PAUSED");
    assertPendingFileNames("IX4", fileNames(1, FILE_COUNT));
    assertHistoryRow("IX4", "PAUSED", FILE_COUNT, 0, 0, FILE_COUNT);
    assertRecentLogContains("IX4", "[전체 실패]");
    assertColumnSum("IX4");
  }

  @Test
  void IX5_재개_루프본문_Error로_끝난_파일도_실패로_세어진다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    pauseOnCall.set(2);
    invokeRunAnalysis();

    printObservation("IX5 1단계(최초 실행)");
    assertHarnessValid("IX5 1단계", 0, 1, fileNames(1, 2));
    assertPhase("IX5 1단계", "PAUSED");
    assertPendingFileNames("IX5 1단계", fileNames(3, FILE_COUNT));
    assertHistoryRow("IX5 1단계", "PAUSED", FILE_COUNT, 2, 0, 0);

    beginPhase("재개", fileNames(3, FILE_COUNT));
    errorAtLoopHead.set(2);                 // 2번째 재개 파일(F4)의 루프 맨 앞에서 시험 전용 Error
    joinResume("재개");

    printObservation("IX5 2단계(재개)");
    assertThrowOrder("IX5 2단계", "F4.java", ORDER_SUFFIX_ERROR);
    assertHarnessValid("IX5 2단계", 1, 1,
        List.of("F1.java", "F2.java", "F3.java", "F5.java", "F6.java"));
    assertPhase("IX5 2단계", "COMPLETED");
    assertHistoryRow("IX5 2단계", "COMPLETED", FILE_COUNT, 5, 0, 1);
    assertFailedFileNames("IX5 2단계", List.of("F4.java"));
    assertColumnSum("IX5 2단계");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // IN·IM — 정상 경로·이미 처리됨 집계·재개 완료 총계. 고치기 전·후 모두 GREEN
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void IN1_최초실행_전부_성공하면_완료이력이_그대로다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    invokeRunAnalysis();

    printObservation("IN1 최초 실행");
    assertHarnessValid("IN1", 0, 0, fileNames(1, FILE_COUNT));
    assertPhase("IN1", "COMPLETED");
    assertPendingFileNames("IN1", List.of());
    assertHistoryRow("IN1", "COMPLETED", FILE_COUNT, FILE_COUNT, 0, 0);
    assertColumnSum("IN1");
  }

  @Test
  void IN2_일시정지_후_재개로_전부_성공하면_완료이력이_그대로다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    pauseOnCall.set(2);
    invokeRunAnalysis();

    printObservation("IN2 1단계(최초 실행)");
    assertHarnessValid("IN2 1단계", 0, 1, fileNames(1, 2));
    assertPhase("IN2 1단계", "PAUSED");
    assertPendingFileNames("IN2 1단계", fileNames(3, FILE_COUNT));
    assertHistoryRow("IN2 1단계", "PAUSED", FILE_COUNT, 2, 0, 0);

    beginPhase("재개", fileNames(3, FILE_COUNT));
    joinResume("재개");

    printObservation("IN2 2단계(재개)");
    assertHarnessValid("IN2 2단계", 0, 1, fileNames(1, FILE_COUNT));
    assertPhase("IN2 2단계", "COMPLETED");
    assertHistoryRow("IN2 2단계", "COMPLETED", FILE_COUNT, FILE_COUNT, 0, 0);
    assertColumnSum("IN2 2단계");
  }

  @Test
  void IN3_최초실행_이미처리됨_표지_파일은_건너뜀으로_세어진다() throws Exception {
    markAlreadyPatched(5, 6);
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    invokeRunAnalysis();

    printObservation("IN3 최초 실행");
    assertHarnessValid("IN3", 0, 0, fileNames(1, 4));
    assertAlreadyPatchedDetected("IN3", 2);
    assertPhase("IN3", "COMPLETED");
    assertHistoryRow("IN3", "COMPLETED", FILE_COUNT, 4, 2, 0);
    assertColumnSum("IN3");
  }

  @Test
  void IN4_재개에서_이미처리됨_표지_파일은_건너뜀으로_세어진다() throws Exception {
    markAlreadyPatched(5, 6);
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    pauseOnCall.set(2);
    invokeRunAnalysis();

    printObservation("IN4 1단계(최초 실행)");
    assertHarnessValid("IN4 1단계", 0, 1, fileNames(1, 2));
    assertPhase("IN4 1단계", "PAUSED");
    assertPendingFileNames("IN4 1단계", fileNames(3, FILE_COUNT));
    assertHistoryRow("IN4 1단계", "PAUSED", FILE_COUNT, 2, 0, 0);

    beginPhase("재개", fileNames(3, FILE_COUNT));
    joinResume("재개");

    printObservation("IN4 2단계(재개)");
    assertHarnessValid("IN4 2단계", 0, 1, fileNames(1, 4));
    assertAlreadyPatchedDetected("IN4 2단계", 2);
    assertPhase("IN4 2단계", "COMPLETED");
    assertHistoryRow("IN4 2단계", "COMPLETED", FILE_COUNT, 4, 2, 0);
    assertColumnSum("IN4 2단계");
  }

  @Test
  void IM1_재개_완료는_저장된_총계가_틀려도_세션_총계를_스스로_쓴다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    pauseOnCall.set(2);
    invokeRunAnalysis();

    printObservation("IM1 1단계(최초 실행)");
    assertHarnessValid("IM1 1단계", 0, 1, fileNames(1, 2));
    assertPhase("IM1 1단계", "PAUSED");
    assertPendingFileNames("IM1 1단계", fileNames(3, FILE_COUNT));
    assertHistoryRow("IM1 1단계", "PAUSED", FILE_COUNT, 2, 0, 0);

    // 저장된 이력 행의 총계를 99로 바꿔 둔다 — 재개 완료가 이 값을 그대로 물려받으면 RED다.
    AnalysisHistory stored = persistedHistory.get();
    assertNotNull(stored, "IM1: 일시정지 시점에 저장된 이력이 없다. 이력 저장 전수=" + savedHistoryRows);
    stored.setTotalFiles(99);
    callOrder.add("저장된 이력 총계를 99로 바꿈");
    assertEquals(99, nz(stored.getTotalFiles()),
        "IM1 (하네스): 저장된 이력 총계를 99로 바꾸지 못했다. 이력 저장 전수=" + savedHistoryRows);

    beginPhase("재개", fileNames(3, FILE_COUNT));
    joinResume("재개");

    printObservation("IM1 2단계(재개)");
    assertHarnessValid("IM1 2단계", 0, 1, fileNames(1, FILE_COUNT));
    assertPhase("IM1 2단계", "COMPLETED");
    assertHistoryRow("IM1 2단계", "COMPLETED", FILE_COUNT, FILE_COUNT, 0, 0);
    assertColumnSum("IM1 2단계");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // TP — 예외로 끝난 파일의 세션 처리 수(O60). 고치기 전 RED
  //
  // (v2 §V2-1) TP1·TP2는 세션 처리 수 · 종료 phase · 마지막 이력 행 · 칸 합만 단언하고
  // 실패 목록은 단언하지 않는다 — 실패 목록 줄은 IX1·IX2·IX3·IX5와 TF2가 지킨다. 그래서
  // RV-2(최초 루프 catch의 실패 목록 줄 삭제)의 기대 집합에 TP가 끼지 않는다.
  // 처리 수 단언을 맨 마지막에 둔다 — 고치기 전 RED 자리가 그 한 줄이 되게 하려는 것이다(RS-2).
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void TP1_최초실행_루프본문_예외로_끝난_파일도_세션_처리수에_세어진다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    throwAtLoopHead.set(FILE_COUNT);        // 6번째(마지막) 파일의 루프 맨 앞에서 표지 예외
    invokeRunAnalysis();

    printObservation("TP1 최초 실행");
    assertThrowOrder("TP1", "F6.java", ORDER_SUFFIX_THROW);
    assertHarnessValid("TP1", 1, 0, fileNames(1, 5));
    assertPhase("TP1", "COMPLETED");
    assertHistoryRow("TP1", "COMPLETED", FILE_COUNT, 5, 0, 1);
    assertColumnSum("TP1");
    assertSessionProcessedFiles("TP1", FILE_COUNT);
  }

  @Test
  void TP2_재개_루프본문_예외로_끝난_파일도_세션_처리수에_세어진다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    pauseOnCall.set(2);                     // F1·F2 성공 후 사용자 일시정지 → 대기 F3~F6
    invokeRunAnalysis();

    printObservation("TP2 1단계(최초 실행)");
    assertHarnessValid("TP2 1단계", 0, 1, fileNames(1, 2));
    assertPhase("TP2 1단계", "PAUSED");
    assertPendingFileNames("TP2 1단계", fileNames(3, FILE_COUNT));
    assertHistoryRow("TP2 1단계", "PAUSED", FILE_COUNT, 2, 0, 0);

    beginPhase("재개", fileNames(3, FILE_COUNT));
    throwAtLoopHead.set(4);                 // 이 재개 단계의 마지막 파일(F6)의 루프 맨 앞
    joinResume("재개");

    printObservation("TP2 2단계(재개)");
    assertThrowOrder("TP2 2단계", "F6.java", ORDER_SUFFIX_THROW);
    assertHarnessValid("TP2 2단계", 1, 1, fileNames(1, 5));
    assertPhase("TP2 2단계", "COMPLETED");
    assertHistoryRow("TP2 2단계", "COMPLETED", FILE_COUNT, 5, 0, 1);
    assertColumnSum("TP2 2단계");
    assertSessionProcessedFiles("TP2 2단계", FILE_COUNT);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // TF — 시험 공백 메우기(R2·IX4). 고치기 전·후 모두 GREEN, 변이에서 RED
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void TF1_재개_틈에_걸린_파일은_이전_실패_기록이_걷어내지지_않고_남는다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    failAtLlmCall.set(2);                   // F2의 LLM 호출 1회 실패 → F2는 FAILED
    pauseOnCall.set(3);                     // F3의 LLM 호출 중 사용자 일시정지
    invokeRunAnalysis();

    printObservation("TF1 1단계(최초 실행)");
    assertHarnessValid("TF1 1단계", 0, 1, fileNames(1, 3));
    assertLlmFailureInjected("TF1 1단계", 1, List.of("F2.java"));
    assertPhase("TF1 1단계", "PAUSED");
    assertPendingFileNames("TF1 1단계", List.of("F2.java", "F4.java", "F5.java", "F6.java"));
    assertFailedFileNames("TF1 1단계", List.of("F2.java"));
    assertHistoryRow("TF1 1단계", "PAUSED", FILE_COUNT, 2, 0, 1);

    beginPhase("재개 1", List.of("F2.java", "F4.java", "F5.java", "F6.java"));
    pauseAtLoopHead.set(1);                 // 첫 재개 파일(F2)의 루프 맨 앞 → 틈
    joinResume("재개 1");

    printObservation("TF1 2단계(재개 1)");
    assertGapOrderInPhase("TF1 2단계", "재개 1", "F2.java");
    assertHarnessValid("TF1 2단계", 1, 1, fileNames(1, 3));
    assertLlmFailureInjected("TF1 2단계", 1, List.of("F2.java"));
    assertPhase("TF1 2단계", "PAUSED");
    assertPendingFileNames("TF1 2단계", List.of("F2.java", "F4.java", "F5.java", "F6.java"));
    // 핵심 — 틈 블록이 "이전 실패 기록 걷어내기"보다 앞이라 F2의 실패 기록이 그대로 남는다(R2).
    assertFailedFileNames("TF1 2단계", List.of("F2.java"));

    beginPhase("재개 2", List.of("F2.java", "F4.java", "F5.java", "F6.java"));
    joinResume("재개 2");

    printObservation("TF1 3단계(재개 2)");
    assertHarnessValid("TF1 3단계", 1, 1,
        List.of("F1.java", "F2.java", "F3.java", "F2.java", "F4.java", "F5.java", "F6.java"));
    assertPhase("TF1 3단계", "COMPLETED");
    assertFailedFileNames("TF1 3단계", List.of());
    assertHistoryRow("TF1 3단계", "COMPLETED", FILE_COUNT, FILE_COUNT, 0, 0);
    assertColumnSum("TF1 3단계");
  }

  @Test
  void TF2_최초실행_전량실패_일시정지는_실패목록에_여섯개가_전부_남는다() throws Exception {
    beginPhase("최초 실행", fileNames(1, FILE_COUNT));
    throwAtEveryLoopHead.set(true);         // 6개 전부 루프 맨 앞에서 표지 예외
    invokeRunAnalysis();

    printObservation("TF2 최초 실행");
    assertThrowOrderAll("TF2", fileNames(1, FILE_COUNT), ORDER_SUFFIX_THROW);
    assertHarnessValid("TF2", FILE_COUNT, 0, List.of());
    assertPhase("TF2", "PAUSED");
    assertPendingFileNames("TF2", fileNames(1, FILE_COUNT));
    // 핵심 — 전량실패로 끝나는 경로에서도 실패 목록이 6개 전부다(IX4가 비우는 칸).
    assertFailedFileNames("TF2", fileNames(1, FILE_COUNT));
    assertHistoryRow("TF2", "PAUSED", FILE_COUNT, 0, 0, FILE_COUNT);
    assertColumnSum("TF2");
    // (O60) 전량 예외로 끝나도 세션 처리 수는 여섯 — 되돌림(0)·올리기 전 값 쓰기(5)와 갈린다.
    assertSessionProcessedFiles("TF2", FILE_COUNT);
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

        if (throwAtEveryLoopHead.get() || ordinal == throwAtLoopHead.get()) {
          recordThrowSite(site, fileLabel, ordinal, caller, original, ORDER_SUFFIX_THROW);
          throw new RuntimeException(MARKER + " — 파일=" + fileLabel);
        }

        if (ordinal == errorAtLoopHead.get()) {
          recordThrowSite(site, fileLabel, ordinal, caller, original, ORDER_SUFFIX_ERROR);
          throw new HarnessInjectedError(ERROR_MARKER + " — 파일=" + fileLabel);
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

        if (ordinal == cancelAtLoopHead.get()) {
          if (original) {
            harnessProblems.add("지정한 루프 맨 앞 호출(순번 " + ordinal + ", " + fileLabel
                + ")에서 원래 shouldStop()이 이미 true였다 — 틈이 성립하지 않는다");
          }
          interventions.incrementAndGet();
          callOrder.add(site + "(" + fileLabel + ") 순번" + ordinal + " [" + caller + "]=false");
          Map<String, Object> cancelResponse =
              controller.cancelSession(Map.of("sessionId", SID), ownerAuth);
          if (Boolean.TRUE.equals(cancelResponse.get("success"))) {
            callOrder.add("취소 요청 성공(" + fileLabel + ")");
          } else {
            callOrder.add("취소 요청 실패(" + fileLabel + "): " + cancelResponse);
            harnessProblems.add("지정한 자리의 취소 요청이 실패했다: " + cancelResponse);
          }
          // (V4-1 ④) 양성 대조 — 세션 관리자 목의 대역이 실제로 세션을 취소 상태로 만들었는가.
          if (!isCancelled()) {
            harnessProblems.add("취소 요청 뒤에도 세션이 취소 상태가 아니다 — 세션 관리자 목의"
                + " cancelSession 대역이 동작하지 않았다(" + fileLabel + ")");
          }
          callOrder.add("취소 대역 확인(" + fileLabel + "): isCancelled=" + isCancelled());
          // 일시정지 개입과 같은 모양 — 루프 맨 앞은 통과시키고 입구 검사가 중단을 본다(= 틈).
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

  /** 던지기 개입 1건을 순서 기록에 남긴다 — 원래 값이 이미 true면 전제 위반이다(그 파일은 어차피 멈춘다). */
  private void recordThrowSite(String site, String fileLabel, int ordinal, String caller,
      boolean original, String suffix) {
    if (original) {
      harnessProblems.add("지정한 루프 맨 앞 호출(순번 " + ordinal + ", " + fileLabel
          + ")에서 원래 shouldStop()이 이미 true였다 — 던지기가 결과를 바꾸지 않는다");
    }
    interventions.incrementAndGet();
    callOrder.add(site + "(" + fileLabel + ") 순번" + ordinal + " [" + caller + "] " + suffix);
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
  // 단계 운영
  // ────────────────────────────────────────────────────────────────────────────

  /** 한 단계(최초 실행 / 각 재개)를 시작하기 전에 자리별 순번·LLM 순번·지정을 0으로 되돌린다. */
  private void beginPhase(String phaseLabel, List<String> fileNamesOfPhase) {
    siteCounters.clear();
    currentLoopFile.remove();
    llmCalls.set(0);
    pauseOnCall.set(0);
    failAtLlmCall.set(0);
    pauseAtLoopHead.set(0);
    throwAtLoopHead.set(0);
    errorAtLoopHead.set(0);
    cancelAtLoopHead.set(0);
    throwAtEveryLoopHead.set(false);
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

  private void writeSourceFile(int index, String content) throws Exception {
    Path file = srcRoot.resolve("com/x/F" + index + ".java");
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  /**
   * 이미 처리됨 표지를 원본에 넣는다 — copy 모드라 {@code performCopy}가 사본에 그대로 옮기고,
   * {@code isAlreadyPatched}의 하위 호환 경로가 사본 내용에서 이 문자열을 찾는다.
   */
  private void markAlreadyPatched(int... indexes) throws Exception {
    for (int index : indexes) {
      writeSourceFile(index, "// " + PATCHED_MARKER + "\npublic class F" + index
          + " { void m() {} }\n");
      callOrder.add("이미 처리됨 표지 삽입: F" + index + ".java");
    }
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

  // ────────────────────────────────────────────────────────────────────────────
  // 하네스 유효성 단언 (결과 단언보다 먼저, 단계마다 — IR-16)
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * 하네스 유효성 ① (틈 사례) — "루프 맨 앞(Fk)=false → 일시정지 요청 성공 → analyzeFile 입구(Fk)=true"가
   * 이 순서로 정확히 1번. <b>그 틈이 일어난 단계 직후에만</b> 부른다(뒤 단계에서 같은 파일이 루프 맨
   * 앞을 다시 지나면 "=false" 기록이 하나 더 생긴다).
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

  /**
   * 하네스 유효성 ① (취소 틈 사례, V4-1 ⑤) — "루프 맨 앞(Fk)=false → 취소 요청 성공 →
   * analyzeFile 입구(Fk)=true"가 이 순서로 정확히 1번. {@link #assertGapOrder}와 같은 모양이고
   * 가운데 기록만 취소다. <b>그 틈이 일어난 단계 직후에만</b> 부른다.
   */
  private void assertCancelGapOrder(String label, String fileLabel) {
    List<String> order = List.copyOf(callOrder);
    int head = indexOfOnly(order,
        line -> line.startsWith(SITE_LOOP_HEAD + "(" + fileLabel + ")") && line.endsWith("=false"),
        label + " (하네스 ①): 루프 맨 앞(" + fileLabel + ")=false 기록");
    int cancel = indexOfOnly(order,
        line -> line.startsWith("취소 요청 성공(" + fileLabel + ")"),
        label + " (하네스 ①): 취소 요청 성공(" + fileLabel + ") 기록");
    int entry = indexOfOnly(order,
        line -> line.startsWith(SITE_ANALYZE_FILE_ENTRY + "(" + fileLabel + ")")
            && line.endsWith("=true"),
        label + " (하네스 ①): analyzeFile 입구(" + fileLabel + ")=true 기록");
    assertTrue(head < cancel && cancel < entry, label + " (하네스 ①): 세 기록이 "
        + "루프 맨 앞=false → 취소 요청 성공 → analyzeFile 입구=true 순서가 아니다"
        + " (자리 " + head + "·" + cancel + "·" + entry + "). 순서 기록=" + order);
  }

  /** 하네스 유효성 ① (던지기 사례) — "루프 맨 앞(Fk) → 표지"가 1번이고 그 파일은 입구에 닿지 않는다. */
  private void assertThrowOrder(String label, String fileLabel, String suffix) {
    List<String> order = List.copyOf(callOrder);
    indexOfOnly(order,
        line -> line.startsWith(SITE_LOOP_HEAD + "(" + fileLabel + ")") && line.endsWith(suffix),
        label + " (하네스 ①): 루프 맨 앞(" + fileLabel + ") " + suffix + " 기록");
    assertEquals(0, count(order,
        line -> line.startsWith(SITE_ANALYZE_FILE_ENTRY + "(" + fileLabel + ")")),
        label + " (하네스 ①): 던진 파일이 analyzeFile 입구에 닿았다. 순서 기록=" + order);
  }

  /** 하네스 유효성 ① (IX4) — 단계의 모든 파일이 루프 맨 앞에서 던져졌고 입구에 닿은 파일이 0개다. */
  private void assertThrowOrderAll(String label, List<String> fileLabels, String suffix) {
    for (String fileLabel : fileLabels) {
      assertThrowOrder(label, fileLabel, suffix);
    }
    List<String> order = List.copyOf(callOrder);
    assertEquals(0, count(order, line -> line.startsWith(SITE_ANALYZE_FILE_ENTRY)),
        label + " (하네스 ①): analyzeFile 입구 기록이 있다. 순서 기록=" + order);
  }

  /** 하네스 유효성 ②③ — 전제 위반 없음 · 지정 개입 수 · LLM 호출(파일과 순서, 누적). */
  private void assertHarnessValid(String label, int expectedInterventions,
      int expectedLlmPauseRequests, List<String> expectedLlmFiles) {
    assertTrue(harnessProblems.isEmpty(),
        label + " 하네스 전제 위반: " + harnessProblems + " | 순서 기록=" + callOrder);
    assertEquals(expectedInterventions, interventions.get(),
        label + " (하네스 ②): 지정한 개입 횟수(누적). 순서 기록=" + callOrder);
    assertEquals(expectedLlmPauseRequests, llmPauseRequests.get(),
        label + " (하네스 ②): LLM 호출 중 일시정지 요청 횟수(누적). 순서 기록=" + callOrder);
    assertEquals(expectedLlmFiles, List.copyOf(llmCalledFiles),
        label + " (하네스 ③): LLM에 보낸 파일과 그 순서(누적). 순서 기록=" + callOrder);
    assertEquals(expectedLlmFiles.size(), llmCallsTotal.get(),
        label + " (하네스 ③): LLM 호출 수(누적). LLM에 보낸 파일=" + llmCalledFiles);
  }

  /**
   * 하네스 유효성 ④ (IN3·IN4) — 이미 처리됨 표지가 실제로 {@code ALREADY_PATCHED}를 만들었다.
   * 세션 통계의 건너뜀 칸은 두 루프 모두 {@code ALREADY_PATCHED}에서만 올라간다.
   */
  private void assertAlreadyPatchedDetected(String label, int expectedAlreadyPatched) {
    assertEquals(expectedAlreadyPatched, session.getStatistics().getSkipCount(),
        label + " (하네스 ④): 이미 처리됨 표지가 ALREADY_PATCHED를 만들지 못했다(세션 건너뜀 칸)."
            + " LLM에 보낸 파일=" + llmCalledFiles + " | 순서 기록=" + callOrder);
  }

  /**
   * (TASK-001) 하네스 유효성 ① 의 <b>단계 한정판</b> — {@link #assertGapOrder}와 같은 세 기록을
   * 보지만, 그 단계의 "단계 시작" 기록 이후만 본다. 새 도우미다(기존 도우미 본문은 건드리지 않는다).
   *
   * <p>왜 필요한가: {@link #assertGapOrder}는 "루프 맨 앞(Fk)=false"가 순서 기록 <b>전체</b>에서
   * 정확히 1번일 것을 요구한다. TF1은 최초 실행에서 F2를 한 번 처리해 실패시키고(그때 F2의 루프 맨
   * 앞이 =false) 재개 1에서 같은 F2를 틈에 걸리게 하므로(또 =false) 전체로 세면 2번이 된다 —
   * 실측으로 확인했다. 단계 한정으로 보면 그 단계 안에서는 정확히 1번이다.
   *
   * <p>단계 구간은 {@code subList} 같은 잘라내기 없이 시작 자리부터 새 목록에 담아 만든다.
   * 구간 시작 마커도 세 기록과 같이 {@code indexOfOnly}로 <b>정확히 1번</b>을 요구한다 — 같은
   * 단계 이름이 두 번 나오면 의도와 다른 구간을 잡게 되므로, 구간 시작만 개수를 안 보는 것은
   * 세 기록에 "1번"을 요구하는 것과 일관되지 않는다.
   */
  private void assertGapOrderInPhase(String label, String phaseLabel, String fileLabel) {
    List<String> order = List.copyOf(callOrder);
    String marker = "── 단계 시작: " + phaseLabel + " (";
    int start = indexOfOnly(order, line -> line.startsWith(marker),
        label + " (하네스 ①): \"" + phaseLabel + "\" 단계 시작 기록");
    List<String> phaseOrder = new ArrayList<>();
    for (int i = start; i < order.size(); i++) {
      phaseOrder.add(order.get(i));
    }
    int head = indexOfOnly(phaseOrder,
        line -> line.startsWith(SITE_LOOP_HEAD + "(" + fileLabel + ")") && line.endsWith("=false"),
        label + " (하네스 ①, " + phaseLabel + " 단계): 루프 맨 앞(" + fileLabel + ")=false 기록");
    int pause = indexOfOnly(phaseOrder,
        line -> line.startsWith("일시정지 요청 성공(" + fileLabel + ")"),
        label + " (하네스 ①, " + phaseLabel + " 단계): 일시정지 요청 성공(" + fileLabel + ") 기록");
    int entry = indexOfOnly(phaseOrder,
        line -> line.startsWith(SITE_ANALYZE_FILE_ENTRY + "(" + fileLabel + ")")
            && line.endsWith("=true"),
        label + " (하네스 ①, " + phaseLabel + " 단계): analyzeFile 입구(" + fileLabel + ")=true 기록");
    assertTrue(head < pause && pause < entry, label + " (하네스 ①, " + phaseLabel + " 단계): 세 기록이 "
        + "루프 맨 앞=false → 일시정지 요청 성공 → analyzeFile 입구=true 순서가 아니다"
        + " (자리 " + head + "·" + pause + "·" + entry + "). 단계 구간 기록=" + phaseOrder);
  }

  /**
   * (TASK-001) 하네스 유효성 ⑤ — 지정한 "LLM 호출 1회 실패"가 실제로 그 파일에서만 일어났다.
   * 새 도우미다(기존 도우미 본문은 건드리지 않는다).
   */
  private void assertLlmFailureInjected(String label, int expectedCount,
      List<String> expectedFiles) {
    assertEquals(expectedCount, llmInjectedFailures.get(),
        label + " (하네스 ⑤): 지정한 LLM 실패 개입 수(누적). 순서 기록=" + callOrder);
    assertEquals(expectedFiles, List.copyOf(llmFailedFiles),
        label + " (하네스 ⑤): LLM 실패를 준 파일과 그 순서(누적). 순서 기록=" + callOrder);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 결과 단언 — 종료 phase · 대기 목록 · 이력 행(+ 칸 합)
  // ────────────────────────────────────────────────────────────────────────────

  private void assertPhase(String label, String expectedPhase) {
    assertEquals(expectedPhase, session.getCurrentPhase(),
        label + " (결과): 종료 phase. 순서 기록=" + callOrder);
  }

  private void assertPendingFileNames(String label, List<String> expected) {
    assertEquals(expected, fileNamesOf(session.getPendingFilePaths()),
        label + " (결과): 대기 목록. 원문=" + session.getPendingFilePaths());
  }

  private void assertFailedFileNames(String label, List<String> expected) {
    assertEquals(expected, fileNamesOf(session.getFailedFilePaths()),
        label + " (결과): 실패 목록. 원문=" + session.getFailedFilePaths());
  }

  private void assertHistoryRow(String label, String expectedStatus, int expectedTotal,
      int expectedSuccess, int expectedSkip, int expectedFailure) {
    HistoryRow last = lastHistory();
    assertEquals(expectedStatus, last.status(),
        label + " (결과): 이력 status. 이력=" + last + " | 이력 저장 전수=" + savedHistoryRows);
    assertEquals(expectedSuccess, last.success(), label + " (결과): 이력 성공 수. 이력=" + last);
    assertEquals(expectedSkip, last.skip(), label + " (결과): 이력 건너뜀 수. 이력=" + last);
    assertEquals(expectedFailure, last.failure(), label + " (결과): 이력 실패 수. 이력=" + last);
    assertEquals(expectedTotal, last.total(), label + " (결과): 이력 총계. 기대 " + expectedTotal
        + " / 실제 " + last.total() + " (세션 총계=" + session.getTotalFiles() + ", 이력=" + last + ")");
  }

  /**
   * (TASK-001, O60) 세션 처리 수 — 진행 화면의 "처리 n/N"과 재개 시작값, README 구조 요약이
   * 함께 읽는 값이다. 예외로 끝난 파일을 두 {@code catch}가 세션에 기록하지 않으면 여기서 RED다.
   * 새 도우미다(기존 도우미 본문은 건드리지 않는다).
   */
  private void assertSessionProcessedFiles(String label, int expected) {
    assertEquals(expected, session.getProcessedFiles(),
        label + " (결과): 세션 처리 수. 기대 " + expected + " / 실제 " + session.getProcessedFiles()
            + " (세션 총계=" + session.getTotalFiles()
            + ", 성공=" + session.getStatistics().getSuccessCount()
            + ", 건너뜀=" + session.getStatistics().getSkipCount()
            + ", 실패=" + session.getStatistics().getFailureCount() + ")");
  }

  /** 마지막 행의 칸 합(O59) — 총계 == 성공 + 건너뜀 + 실패. */
  private void assertColumnSum(String label) {
    HistoryRow last = lastHistory();
    assertEquals(last.total(), last.success() + last.skip() + last.failure(),
        label + " (결과): 칸 합이 총계와 다르다 — 어느 칸에도 세어지지 않은 파일이 있다. 이력=" + last);
  }

  private void assertFinalSummaryContains(String label, String expectedLine) {
    Object finalSummary = session.getMetadata().get("finalSummary");
    assertNotNull(finalSummary, label + " (결과): finalSummary 메타데이터가 없다. 메타데이터 키="
        + session.getMetadata().keySet());
    assertTrue(finalSummary.toString().contains(expectedLine),
        label + " (결과): 완료 요약에 \"" + expectedLine + "\"가 없다. 완료 요약=" + finalSummary);
  }

  private void assertRecentLogContains(String label, String expectedFragment) {
    List<String> lines = session.getRecentLogLines(50);
    assertTrue(lines.stream().anyMatch(line -> line.contains(expectedFragment)),
        label + " (결과): 최근 로그에 \"" + expectedFragment + "\"가 없다. 최근 로그=" + lines);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 관찰 출력 — 결과 단언 전에 "지금 무엇이 틀렸는가"를 남긴다
  // ────────────────────────────────────────────────────────────────────────────

  private void printObservation(String label) {
    System.out.println("[" + label + " 순서 기록] " + callOrder);
    System.out.println("[" + label + " LLM에 보낸 파일] " + llmCalledFiles);
    System.out.println("[" + label + " 이력 저장 전수] " + savedHistoryRows);
    System.out.println("[" + label + " 세션] 총계=" + session.getTotalFiles()
        + " 성공=" + session.getStatistics().getSuccessCount()
        + " 건너뜀=" + session.getStatistics().getSkipCount()
        + " 실패=" + session.getStatistics().getFailureCount()
        + " 처리=" + session.getProcessedFiles() + " phase=" + session.getCurrentPhase());
    System.out.println("[" + label + " 세션 대기 목록] " + fileNamesOf(session.getPendingFilePaths()));
    System.out.println("[" + label + " 세션 실패 목록] " + fileNamesOf(session.getFailedFilePaths()));
    if (!savedHistoryRows.isEmpty()) {
      HistoryRow last = savedHistoryRows.get(savedHistoryRows.size() - 1);
      int gap = last.total() - (last.success() + last.skip() + last.failure());
      System.out.println("[" + label + " 칸 합 차이] 총계 " + last.total() + " − (성공 "
          + last.success() + " + 건너뜀 " + last.skip() + " + 실패 " + last.failure() + ") = " + gap);
    }
    System.out.println("[" + label + " 완료 요약] " + session.getMetadata().get("finalSummary"));
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 도우미
  // ────────────────────────────────────────────────────────────────────────────

  private static int nz(Integer value) {
    return value == null ? 0 : value;
  }

  private static List<String> fileNamesOf(java.util.Collection<String> paths) {
    return paths.stream()
        .map(p -> Path.of(p).getFileName().toString())
        .sorted()
        .collect(Collectors.toList());
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
