package com.legacy.analysis;

import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.auth.Role;
import com.legacy.auth.User;
import com.legacy.core.ApiErrorHandler;
import com.legacy.core.PresentationGeneratorService;
import com.legacy.notification.NotificationService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
 * TASK-006 (work-order 2026-10-resume-consistency-and-local-guard v1, REQ-002 ③④) —
 * <b>전량실패 완료 패널이 "진짜 원인"과 {@code historyId}를 받는지</b>를 실제 처리 루프로 확인한다.
 *
 * <p>고치려는 결함 2개:
 * <ul>
 *   <li><b>③</b> {@code historyId}를 {@code finalizeAnalysis()}(정상 완료)에서만 세션 메타데이터에
 *       넣었고 폴링 응답도 COMPLETED에서만 실었다 → 전량실패 PAUSED의 완료 패널에서 'PPT 다운로드'가
 *       동작하지 않았다.</li>
 *   <li><b>④</b> 전량실패 안내의 사유를 {@code errorLog} 마지막 줄에서 가져왔는데, 파일 실패는
 *       {@code errorLog}에 남지 않아 대개 비어 있었다 → 사용자에게 "알 수 없는 오류"만 보이고
 *       정작 원인(예: 로컬 LLM 주소 해석 실패)은 어디에도 표시되지 않았다.</li>
 * </ul>
 *
 * <p><b>하네스</b>: 선례 {@code MainApiControllerPauseSettledTerminalNormalizationContractTest} /
 * TASK-005 {@code MainApiControllerTerminalStatusConsistencyTest}와 같은 구조 — 실제 {@code @TempDir}
 * 파일 + LLM 대역 + 리플렉션 기동, 스레드풀 1. 이력 저장소 목은 <b>실제 JPA처럼 save 시점에 id를
 * 부여</b>한다(그래야 {@code historyId} 경로를 모델링할 수 있다).
 *
 * <p>각 케이스에서 카운터 5종(§0.5)을 함께 출력한다.
 */
class MainApiControllerAllFailedReasonAndHistoryIdTest {

  private static final String SID = "sid-allfailed-reason";
  private static final String USERNAME = "jhjung";
  private static final Long USER_SEQ = 60L;
  private static final String STUB_COMMENT_PREFIX = "// [AI 주석] 테스트 대역이 생성한 주석";
  private static final int FILE_COUNT = 3;

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

  /** 재개 경로가 {@code findBySessionId}로 가져가는 이력 행. */
  private AnalysisHistory resumeHistory;

  private final List<String> savedHistoryStatuses = new CopyOnWriteArrayList<>();
  private final List<AnalysisHistory> savedHistories = new CopyOnWriteArrayList<>();
  private final AtomicLong nextHistoryId = new AtomicLong(97L);
  private final AtomicInteger llmCalls = new AtomicInteger();
  private final AtomicInteger pauseOnCall = new AtomicInteger(0);
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

    SessionConfig sessionConfig = new SessionConfig();
    sessionConfig.setMaxRetries(0);
    RetryHandler retryHandler = new RetryHandler(new ApiErrorHandler(), sessionConfig);

    controller = new MainApiController(
        claudeService, null, sessionManager, null, null, retryHandler,
        analysisHistoryRepository, null, mock(NotificationService.class), null,
        mock(PresentationGeneratorService.class), null,
        mock(CodeContentRagService.class), llmModelOptionService, null);
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

    resumeHistory = new AnalysisHistory(USER_SEQ, SID, srcRoot.toString(), outRoot.toString());
    resumeHistory.setId(55L);
    when(analysisHistoryRepository.findBySessionId(SID)).thenReturn(resumeHistory);

    // 실제 JPA(IDENTITY)처럼 save 시점에 id를 부여한다 — id가 없으면 historyId 경로를 모델링할 수 없다.
    when(analysisHistoryRepository.save(any(AnalysisHistory.class))).thenAnswer(inv -> {
      AnalysisHistory h = inv.getArgument(0);
      if (h.getId() == null) h.setId(nextHistoryId.getAndIncrement());
      savedHistoryStatuses.add(h.getStatus());
      savedHistories.add(h);
      return h;
    });

    doAnswer(inv -> null).when(sessionManager).saveSessionState(any(SessionState.class));
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

  private void setField(String name, Object value) throws Exception {
    Field field = MainApiController.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(controller, value);
  }

  private static RuntimeException credits() {
    return new AnalysisException(ApiErrorHandler.ErrorType.INSUFFICIENT_CREDITS,
        new RuntimeException("credit balance is too low"));
  }

  private void runAnalysisAndAwait() throws Exception {
    Method m = MainApiController.class.getDeclaredMethod("runAnalysis",
        String.class, String.class, String.class, boolean.class, Long.class, String.class,
        Set.class, boolean.class);
    m.setAccessible(true);
    m.invoke(controller, SID, srcRoot.toString(), outRoot.toString(), false, USER_SEQ, USERNAME, null, false);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> resumeAndAwait() throws Exception {
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
        return response;
      }
      Thread.sleep(50);
    }
    return fail("재개 스레드가 60초 안에 종료 상태에 도달하지 않았다. phase=" + session.getCurrentPhase());
  }

  private List<String> recentLogs() {
    return session.getRecentLogLines(1000);
  }

  private String terminalAllFailedLine() {
    return recentLogs().stream().filter(l -> l.contains("[전체 실패]")).reduce((a, b) -> b).orElse(null);
  }

  private Map<String, Object> counters() {
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("successCount", session.getStatistics().getSuccessCount());
    c.put("failureCount", session.getStatistics().getFailureCount());
    c.put("skipCount", session.getStatistics().getSkipCount());
    c.put("processedFiles", session.getProcessedFiles());
    c.put("totalFiles", session.getTotalFiles());
    return c;
  }

  private AnalysisStatusDto poll() {
    return controller.getAnalysisStatus(SID, 80, ownerAuth);
  }

  private void report(String label, AnalysisStatusDto dto) {
    System.out.println("[006] " + label
        + " | phase=" + dto.getPhase() + ", completed=" + dto.isCompleted()
        + ", historyId=" + dto.getHistoryId()
        + " | errorMessage=" + dto.getErrorMessage()
        + " | 터미널=" + terminalAllFailedLine()
        + " | 카운터=" + counters());
  }

  /** 분석 대기 목록을 세션에 직접 심는다(재개 케이스용). 실제 파일이 있어야 재개가 진행된다. */
  private void seedPending(List<Path> files) {
    List<String> paths = new ArrayList<>();
    for (Path p : files) paths.add(p.toString());
    session.setPendingFilePaths(paths);
    session.setStatus("PAUSED");
    session.setCurrentPhase("PAUSED");
  }

  private List<Path> sourceFiles() {
    List<Path> files = new ArrayList<>();
    for (int i = 1; i <= FILE_COUNT; i++) files.add(srcRoot.resolve("com/x/F" + i + ".java"));
    return files;
  }

  // ────────────────────────────────────────────────────────────────────────────
  // ⓐ 전량실패(최초) — 진짜 원인 + historyId
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void 케이스A_전량실패시_폴링_errorMessage와_터미널에_진짜_원인과_건수가_실리고_historyId가_내려간다() throws Exception {
    failurePlan.set(n -> new RuntimeException("Failed to resolve 'ollama'"));

    runAnalysisAndAwait();
    AnalysisStatusDto dto = poll();
    report("케이스A 전량실패(최초)", dto);

    assertEquals("PAUSED", dto.getPhase());
    assertTrue(dto.isCompleted(), "PAUSED는 폴링을 멈추는 종료 상태다");

    // ④ 진짜 원인 + 건수
    String errorMessage = dto.getErrorMessage();
    assertNotNull(errorMessage, "전량실패인데 errorMessage가 없다");
    assertTrue(errorMessage.contains("Failed to resolve 'ollama'"),
        "errorMessage에 진짜 원인이 없다: " + errorMessage);
    assertTrue(errorMessage.contains("(3건)"), "errorMessage에 건수가 없다: " + errorMessage);
    assertTrue(errorMessage.contains("전체 실패 원인:"), "요약 접두사가 없다: " + errorMessage);
    assertFalse(errorMessage.contains("알 수 없는 오류"),
        "'알 수 없는 오류'가 그대로 남았다(바로 이 결함): " + errorMessage);

    String terminal = terminalAllFailedLine();
    assertNotNull(terminal, "터미널에 [전체 실패] 줄이 없다: " + recentLogs());
    assertTrue(terminal.contains("Failed to resolve 'ollama'"),
        "터미널 [전체 실패] 줄에 진짜 원인이 없다: " + terminal);
    assertFalse(terminal.contains("알 수 없는 오류"), terminal);

    // ③ historyId — 이 실행에서 저장된 이력의 id와 같아야 한다
    AnalysisHistory created = savedHistories.get(0);
    assertNotNull(created.getId(), "하네스 전제: save가 id를 부여해야 한다");
    assertEquals(created.getId(), dto.getHistoryId(),
        "PAUSED 폴링 응답의 historyId가 이력 id와 다르다");
    assertEquals("PAUSED", savedHistoryStatuses.get(savedHistoryStatuses.size() - 1));
  }

  // ────────────────────────────────────────────────────────────────────────────
  // ⓑ 재개 전량실패 — 메타데이터가 빈 세션(재시작 모사)도 historyId가 채워진다
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void 케이스B_메타데이터가_빈_세션의_재개_전량실패에서도_historyId가_채워진다() throws Exception {
    // 재시작 모사: 세션 메타데이터에 historyId가 없는 상태에서 재개한다.
    assertNull(session.getMetadata().get("historyId"), "전제: 메타데이터가 비어 있어야 한다");
    seedPending(sourceFiles());
    failurePlan.set(n -> new RuntimeException("Read timed out"));

    resumeAndAwait();
    AnalysisStatusDto dto = poll();
    report("케이스B 전량실패(재개)", dto);

    assertEquals("PAUSED", dto.getPhase());
    assertEquals(55L, dto.getHistoryId(), "재개 경로가 findBySessionId 결과의 id로 채워야 한다");
    assertEquals("55", session.getMetadata().get("historyId"),
        "세션 메타데이터에도 남아야 한다(이후 폴링에서도 쓰인다)");
    assertTrue(dto.getErrorMessage().contains("Read timed out"), dto.getErrorMessage());
    assertTrue(dto.getErrorMessage().contains("(3건)"), dto.getErrorMessage());
  }

  // ────────────────────────────────────────────────────────────────────────────
  // ⓒ 서로 다른 메시지 20종 → errorLog 증가 정확히 1줄
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void 케이스C_사유가_여러_종류여도_errorLog는_정확히_1줄만_늘어난다() throws Exception {
    // 파일 20개 + 파일마다 다른 메시지
    for (int i = 4; i <= 20; i++) {
      Path file = srcRoot.resolve("com/x/F" + i + ".java");
      Files.writeString(file, "public class F" + i + " { void m() {} }\n", StandardCharsets.UTF_8);
    }
    failurePlan.set(n -> new RuntimeException("서로 다른 사유 #" + n));
    int errorLogBefore = session.getErrorLog().size();

    runAnalysisAndAwait();
    AnalysisStatusDto dto = poll();
    report("케이스C 사유 20종", dto);

    int added = session.getErrorLog().size() - errorLogBefore;
    System.out.println("[006] 케이스C errorLog 증가 = " + added + "줄, errorLog=" + session.getErrorLog());
    assertEquals(1, added, "사유가 몇 종류든 errorLog는 1줄만 늘어나야 한다(안내가 로그를 뒤덮지 않게)");

    String errorMessage = dto.getErrorMessage();
    assertTrue(errorMessage.contains("기타"), "6종 이상이면 '기타'로 묶여야 한다: " + errorMessage);
    assertEquals(20, session.getStatistics().getFailureCount());
  }

  // ────────────────────────────────────────────────────────────────────────────
  // ⓓ getMessage()==null 인 예외 → NPE 없음, errorType이 사유로 표시
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void 케이스D_메시지가_null인_예외도_NPE없이_errorType이_사유로_표시된다() throws Exception {
    failurePlan.set(n -> new RuntimeException((String) null));

    assertDoesNotThrow(this::runAnalysisAndAwait);
    AnalysisStatusDto dto = poll();
    report("케이스D 메시지 null", dto);

    assertEquals("PAUSED", dto.getPhase());
    String errorMessage = dto.getErrorMessage();
    assertNotNull(errorMessage, "메시지가 null이어도 안내는 있어야 한다");
    assertTrue(errorMessage.contains("UNKNOWN_ERROR"),
        "메시지가 없으면 errorType이 사유로 표시돼야 한다: " + errorMessage);
    assertTrue(errorMessage.contains("(3건)"), errorMessage);
    assertEquals(3, session.getStatistics().getFailureCount());
  }

  // ────────────────────────────────────────────────────────────────────────────
  // ⓔ RG-3 — 사용자 일시정지(일부 실패 후)의 errorMessage는 착수 전과 같다
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void 케이스E_RG3_사용자_일시정지는_errorMessage가_착수_전과_같다() throws Exception {
    // 1번째 파일 실패 → 2번째 처리 도중 일시정지 → 나머지는 조기 반환(= 전량실패 분기로 가지 않는다)
    failurePlan.set(n -> n == 1 ? new RuntimeException("한 건만 실패") : null);
    pauseOnCall.set(2);

    runAnalysisAndAwait();
    AnalysisStatusDto dto = poll();
    report("케이스E 사용자 일시정지(부분 실패)", dto);

    assertEquals("PAUSED", dto.getPhase());
    assertTrue(recentLogs().stream().anyMatch(l -> l.contains("[일시정지 완료]")),
        "일시정지 확정 분기를 타야 한다: " + recentLogs());
    assertNull(terminalAllFailedLine(), "사용자 일시정지는 전량실패 분기를 타지 않는다");

    // 착수 전 동작: 파일 실패는 errorLog에 남지 않으므로 errorLog가 비어 errorMessage도 비었다.
    assertTrue(session.getErrorLog().isEmpty(),
        "사용자 일시정지 경로에 errorLog가 새로 생겼다(RG-3 위반): " + session.getErrorLog());
    assertNull(dto.getErrorMessage(),
        "사용자 일시정지의 errorMessage가 착수 전(null)과 달라졌다: " + dto.getErrorMessage());
  }

  // ────────────────────────────────────────────────────────────────────────────
  // ⓕ 정상 완료 — historyId는 기존과 동일
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void 케이스F_정상_완료의_historyId는_기존과_동일하다() throws Exception {
    failurePlan.set(n -> null);

    runAnalysisAndAwait();
    AnalysisStatusDto dto = poll();
    report("케이스F 정상 완료", dto);

    assertEquals("COMPLETED", dto.getPhase());
    AnalysisHistory created = savedHistories.get(0);
    assertEquals(created.getId(), dto.getHistoryId(), "정상 완료의 historyId가 이력 id와 다르다");
    assertEquals(FILE_COUNT, session.getStatistics().getSuccessCount());
    assertNull(dto.getErrorMessage(), "정상 완료에는 errorMessage가 없다: " + dto.getErrorMessage());
    assertTrue(session.getErrorLog().isEmpty(),
        "정상 완료 경로에 errorLog가 생겼다: " + session.getErrorLog());
  }

  // ────────────────────────────────────────────────────────────────────────────
  // ⓖ 크레딧 소진 전량 — 기존 크레딧 문구 그대로, 사유 요약 미혼입
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void 케이스G_크레딧_소진은_기존_문구_그대로이고_사유_요약이_섞이지_않는다() throws Exception {
    failurePlan.set(n -> credits());

    runAnalysisAndAwait();
    AnalysisStatusDto dto = poll();
    report("케이스G 크레딧 소진", dto);

    assertEquals("PAUSED", dto.getPhase());
    String errorMessage = dto.getErrorMessage();
    assertNotNull(errorMessage);
    assertTrue(errorMessage.contains("Claude API 크레딧 소진으로 분석 중단. 충전 후 재개 가능."),
        "기존 크레딧 문구가 그대로여야 한다: " + errorMessage);
    assertFalse(errorMessage.contains("전체 실패 원인:"),
        "크레딧 소진 안내에 사유 요약이 섞였다: " + errorMessage);
    assertNull(session.summarizeFileFailureReasons(),
        "INSUFFICIENT_CREDITS는 사유 집계에 넣지 않아야 한다");
    assertTrue(recentLogs().stream().anyMatch(l -> l.contains("[크레딧 소진")),
        "크레딧 소진 분기 로그가 없다: " + recentLogs());
  }

  // ────────────────────────────────────────────────────────────────────────────
  // ⓗ PARTIAL 재개 후 전량실패 → 요약에 SOURCE_MISSING 포함
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void 케이스H_PARTIAL_재개후_전량실패하면_요약에_SOURCE_MISSING이_함께_보인다() throws Exception {
    // 대기 목록 = 실재 파일 1개 + 없는 경로 2개 → PARTIAL
    List<String> pending = List.of(
        srcRoot.resolve("com/x/F1.java").toString(),
        srcRoot.resolve("com/x/GONE1.java").toString(),
        srcRoot.resolve("com/x/GONE2.java").toString());
    for (int i = 1; i < 3; i++) {
      assertFalse(Files.exists(Path.of(pending.get(i))), "전제: 소실 경로가 실제로 없어야 한다");
    }
    session.setPendingFilePaths(pending);
    session.setStatus("PAUSED");
    session.setCurrentPhase("PAUSED");
    failurePlan.set(n -> new RuntimeException("남은 파일도 실패"));

    Map<String, Object> resumeResponse = resumeAndAwait();
    AnalysisStatusDto dto = poll();
    System.out.println("[006] 케이스H 재개 응답=" + resumeResponse);
    report("케이스H PARTIAL 재개 후 전량실패", dto);

    assertEquals(2, resumeResponse.get("missingCount"));
    assertEquals("PAUSED", dto.getPhase());

    String summary = session.summarizeFileFailureReasons();
    System.out.println("[006] 케이스H 사유 요약=" + summary);
    assertTrue(summary.contains("원본 파일 없음"), "요약에 소실 사유가 없다: " + summary);
    assertTrue(summary.contains("(2건)"), "소실 2건이 집계돼야 한다: " + summary);
    assertTrue(summary.contains("남은 파일도 실패"), "남은 파일의 실패 사유도 있어야 한다: " + summary);

    String errorMessage = dto.getErrorMessage();
    assertTrue(errorMessage.contains("원본 파일 없음"),
        "안내에 소실 사유가 보여야 한다: " + errorMessage);
    // 실패 카운터 = 소실 2 + 처리 실패 1
    assertEquals(3, session.getStatistics().getFailureCount());
    assertFalse(errorMessage.contains(srcRoot.toString()),
        "안내에 내부 경로가 들어가면 안 된다(C10): " + errorMessage);
  }
}
