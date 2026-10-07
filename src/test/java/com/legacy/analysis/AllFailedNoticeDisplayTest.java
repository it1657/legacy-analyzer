package com.legacy.analysis;

import com.legacy.analysis.llm.AnthropicErrorFixtures;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TASK-006 (work-order 2026-10-counter-integrity-and-failure-reason v1/v2, REQ-003-b / 게이트1 D7) —
 * 전량실패 안내가 사용자에게 <b>어떻게 보이는지</b>를 상태 API 응답으로 확인한다.
 *
 * <p>고치려는 결함: 전량실패 안내 1줄은 {@code addErrorLog()}로 들어가 앞에
 * {@code [2026-10-06T15:04:27.123456]} 같은 타임스탬프가 붙는다. 상태 API의
 * {@code errorMessage}는 {@code errorLog}의 마지막 줄을 그대로 내려주므로, 사용자는
 * "원인"보다 기계적인 시각 문자열을 먼저 읽는다.
 *
 * <p><b>RG-3 회귀 가드</b>: 이번에 바뀌는 것은 <b>전량실패 안내 1줄뿐</b>이다. 같은 테스트 안에
 * 크레딧 소진 안내(타임스탬프 접두사 유지 + 본문 문자 단위 동일)와 사용자 일시정지
 * ({@code errorMessage} null 유지)를 둬서 범위가 한 줄로 한정됐음을 보인다(G-04 ②).
 *
 * <p><b>하네스</b>: TASK-003/004가 찾아낸 충실도 교정 3건을 승계한다 —
 * ① {@code initializeFileList} 목이 세션 총계를 실제 구현처럼 채운다
 * ② 이력 카운터는 nullable {@code Integer}라 목 안에서 언박싱하지 않는다
 * ③ {@code findBySessionId}가 "마지막에 저장된 이력"을 돌려준다.
 * 거기에 {@code phase != FAILED} 가드까지 더해, 스레드가 죽은 상태의 값을 판정에 쓰지 않는다.
 *
 * <p>이름표 문구는 프로덕션 상수를 참조하지 않고 <b>문자열 리터럴</b>로 둔다(사전 RED 가능성 유지).
 */
class AllFailedNoticeDisplayTest {

  private static final String SID = "sid-all-failed-notice";
  private static final String USERNAME = "jhjung";
  private static final Long USER_SEQ = 90L;
  private static final String STUB_COMMENT_PREFIX = "// [AI 주석] 테스트 대역이 생성한 주석";
  private static final int FILE_COUNT = 4;

  /** §0.3 이름표 표 — `API_AUTHENTICATION`. */
  private static final String AUTH_LABEL = "인증 실패(API 키 확인 필요)";

  /** 기존 크레딧 소진 안내 본문 — 한 글자도 바뀌면 안 된다(RG-3). */
  private static final String CREDIT_NOTICE = "Claude API 크레딧 소진으로 분석 중단. 충전 후 재개 가능.";

  /** {@code addErrorLog()}가 붙이는 타임스탬프 접두사. */
  private static final Pattern TIMESTAMP_PREFIX = Pattern.compile("^\\[\\d{4}-\\d{2}-\\d{2}T");

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
    // 승계 ② — nullable Integer를 목 안에서 언박싱하지 않는다(언박싱 NPE가 프로덕션 스레드를 죽인다).
    when(analysisHistoryRepository.save(any(AnalysisHistory.class))).thenAnswer(inv -> {
      AnalysisHistory saved = inv.getArgument(0);
      persistedHistory.set(saved);
      return saved;
    });
    // 승계 ① — 목이 실제 구현처럼 세션 총계를 채운다.
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
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 전량실패 안내 — 이번에 바뀌는 1줄
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void 최초분석_전량실패_안내에_타임스탬프가_없고_유형_이름표가_붙는다() throws Exception {
    failurePlan.set(n -> authFailure(n));

    runAnalysisAndAwait();
    String errorMessage = assertAllFailedNotice("최초 전량실패");
    assertTrue(errorMessage.contains("(" + FILE_COUNT + "건)"),
        "전량실패 안내에 건수가 합산돼 있어야 한다: " + mask(errorMessage));
  }

  /**
   * (G-21, work-order v3 TASK-006 보강) 수정 후에는 <b>최초 실행의 전량실패 안내도</b> 타임스탬프가
   * 없다. 그래서 재개 스레드가 죽어 아무 줄도 추가되지 않아도, 최초 실행이 남긴 안내가 그대로
   * {@code errorMessage}로 내려와 "타임스탬프 없음 + 이름표 포함"이 통과할 수 있다 —
   * 아무 일도 안 일어나서 통과하는 전형이다. 그래서 재개 단계가 실제로 돌았다는 양성 지표
   * 3가지를 함께 단언한다.
   */
  @Test
  void 재개_전량실패_안내에도_타임스탬프가_없고_유형_이름표가_붙는다() throws Exception {
    failurePlan.set(n -> authFailure(n));
    runAnalysisAndAwait();
    assertEquals(FILE_COUNT, session.getPendingFilePaths().size(), "재개용 대기 목록이 확보돼야 한다");

    // G-21-3 — 재개 전 상태를 떠 둔다. 재개 뒤 값이 "이번 단계에서 새로 생긴 것"인지 가르는 기준이다.
    int errorLogSizeBeforeResume = session.getErrorLog().size();
    String errorMessageBeforeResume = statusApi().getErrorMessage();

    llmCalls.set(0);
    resumeAndAwait();

    // G-21-1 실행 증거 — 재개 단계에서 LLM 대역이 실제로 불렸다.
    assertTrue(llmCalls.get() >= 1,
        "G-21-1: 재개 단계에서 LLM 호출이 0회다 — 재개가 돌지 않았으므로 안내를 판정에 쓸 수 없다");
    // G-21-2 스레드 생존 — assertAllFailedNotice()·resumeAndAwait()에서도 단언한다.
    assertNotEquals("FAILED", session.getCurrentPhase(),
        "G-21-2: 재개 스레드가 예외로 끝났다(phase=FAILED)");
    // G-21-3 종단 값의 출처 — 안내 줄이 재개 단계에서 새로 추가됐다.
    List<String> errorLog = session.getErrorLog();
    assertTrue(errorLog.size() > errorLogSizeBeforeResume,
        "G-21-3: 재개 뒤 errorLog 줄 수가 늘지 않았다 — 최초 실행이 남긴 줄을 다시 재고 있다."
            + " 재개 전=" + errorLogSizeBeforeResume + ", 재개 후=" + errorLog.size());

    String errorMessage = assertAllFailedNotice("재개 전량실패");
    assertEquals(errorLog.get(errorLog.size() - 1), errorMessage,
        "G-21-3: 상태 API의 errorMessage가 errorLog 마지막 줄이어야 한다");
    System.out.println("[006 G-21] 재개 LLM 호출=" + llmCalls.get()
        + " / errorLog " + errorLogSizeBeforeResume + "→" + errorLog.size()
        + " / 재개 전 errorMessage=" + mask(errorMessageBeforeResume));
  }

  // ────────────────────────────────────────────────────────────────────────────
  // RG-3 — 이번 변경이 건드리지 않는 안내 문자열
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * 크레딧 소진 안내는 {@code addErrorLog()}를 계속 쓴다 — 타임스탬프 접두사가 그대로 있고
   * 본문은 문자 단위로 같아야 한다. 이 케이스가 PASS라는 것이 "범위가 전량실패 1줄로
   * 한정됐다"는 증거다.
   */
  @Test
  void RG3_크레딧소진_안내는_타임스탬프_접두사와_본문이_그대로다() throws Exception {
    failurePlan.set(n -> n == 1 ? credits() : null);

    runAnalysisAndAwait();

    AnalysisStatusDto dto = statusApi();
    String errorMessage = dto.getErrorMessage();
    System.out.println("[006 RG-3 크레딧] errorMessage=" + errorMessage);

    assertNotNull(errorMessage, "크레딧 소진은 errorMessage가 있어야 한다");
    assertTrue(TIMESTAMP_PREFIX.matcher(errorMessage).find(),
        "크레딧 소진 안내는 타임스탬프 접두사를 그대로 유지해야 한다: " + errorMessage);
    int bracketEnd = errorMessage.indexOf("] ");
    assertTrue(bracketEnd > 0, "타임스탬프 접두사 형식이 아니다: " + errorMessage);
    assertEquals(CREDIT_NOTICE, errorMessage.substring(bracketEnd + 2),
        "크레딧 소진 안내 본문이 한 글자라도 바뀌면 안 된다(RG-3)");
  }

  /** 사용자 일시정지는 {@code errorLog}에 아무것도 넣지 않으므로 {@code errorMessage}가 null로 남는다. */
  @Test
  void RG3_사용자_일시정지는_errorMessage가_null로_남는다() throws Exception {
    failurePlan.set(n -> authFailure(n));
    pauseOnCall.set(2);

    runAnalysisAndAwait();

    AnalysisStatusDto dto = statusApi();
    System.out.println("[006 RG-3 일시정지] phase=" + dto.getPhase()
        + " errorMessage=" + dto.getErrorMessage()
        + " / errorLog 크기=" + session.getErrorLog().size());

    assertEquals("PAUSED", dto.getPhase(), "사용자 일시정지로 끝나야 한다");
    assertNull(dto.getErrorMessage(),
        "사용자 일시정지(부분 실패 포함)는 errorMessage를 쓰지 않는다(RG-3)");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 판정
  // ────────────────────────────────────────────────────────────────────────────

  private String assertAllFailedNotice(String label) {
    assertNotEquals("FAILED", session.getCurrentPhase(),
        label + ": 분석 스레드가 예외로 끝났다(phase=FAILED) — 이 상태의 안내는 판정 근거가 될 수 없다");

    AnalysisStatusDto dto = statusApi();
    String errorMessage = dto.getErrorMessage();
    System.out.println("[006] " + label + " | phase=" + dto.getPhase()
        + " | errorMessage=" + mask(errorMessage)
        + " | 사유요약=" + mask(session.summarizeFileFailureReasons()));

    assertNotNull(errorMessage, label + ": 전량실패는 errorMessage가 있어야 한다");
    assertTrue(errorMessage.startsWith("전체 실패 원인: "),
        label + ": 안내가 '전체 실패 원인: '으로 시작해야 한다(타임스탬프가 앞에 붙으면 안 된다). 실제="
            + mask(errorMessage));
    assertFalse(TIMESTAMP_PREFIX.matcher(errorMessage).find(),
        label + ": 전량실패 안내에 타임스탬프 접두사가 남았다: " + mask(errorMessage));
    assertTrue(errorMessage.contains(AUTH_LABEL),
        label + ": 유형 이름표가 안내에 없다: " + mask(errorMessage));
    assertFalse(errorMessage.contains("req_"),
        label + ": 식별자 req_ 토큰이 안내에 노출됐다: " + mask(errorMessage));
    return errorMessage;
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 도우미
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * 실제 401 응답에서 얻은 예외를 {@code API_AUTHENTICATION}으로 감싸 던진다 —
   * 파일마다 다른 {@code request_id}를 쓰게 해, 같은 원인이 한 종으로 합쳐지는지까지 함께 본다.
   */
  private static RuntimeException authFailure(int callNo) {
    List<AnthropicErrorFixtures.Case> variants = List.of(
        AnthropicErrorFixtures.Case.AUTH_401_1, AnthropicErrorFixtures.Case.AUTH_401_2,
        AnthropicErrorFixtures.Case.AUTH_401_3, AnthropicErrorFixtures.Case.AUTH_401_4);
    AnthropicErrorFixtures.Case fixture = variants.get((callNo - 1) % variants.size());
    return new AnalysisException(ApiErrorHandler.ErrorType.API_AUTHENTICATION,
        AnthropicErrorFixtures.thrownBy(fixture));
  }

  private static RuntimeException credits() {
    return new AnalysisException(ApiErrorHandler.ErrorType.INSUFFICIENT_CREDITS,
        new RuntimeException("테스트 대역: credit balance is too low"));
  }

  private static String mask(String text) {
    return text == null ? "(null)" : AnthropicErrorFixtures.maskRequestIds(text);
  }

  private AnalysisStatusDto statusApi() {
    return controller.getAnalysisStatus(SID, 80, ownerAuth);
  }

  private void setField(String name, Object value) throws Exception {
    Field field = MainApiController.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(controller, value);
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
        assertNotEquals("FAILED", phase, "재개 스레드가 예외로 끝났다 — 안내를 판정에 쓸 수 없다");
        return;
      }
      Thread.sleep(50);
    }
    fail("재개 스레드가 60초 안에 종료 상태에 도달하지 않았다. phase=" + session.getCurrentPhase());
  }
}
