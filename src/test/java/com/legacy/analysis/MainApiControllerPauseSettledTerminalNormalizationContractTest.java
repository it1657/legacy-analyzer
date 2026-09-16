package com.legacy.analysis;

import com.legacy.analysis.llm.LlmModelOption;
import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.auth.Role;
import com.legacy.auth.User;
import com.legacy.core.ApiErrorHandler;
import com.legacy.core.PresentationGeneratorService;
import com.legacy.rag.CodeContentRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
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
 * TASK-002B (work-order 2026-09-remaining-ux-fixes v4 §0.21.4, REQ-002 백엔드 보강) —
 * <b>"사용자 일시정지 요청이 선행한 뒤 처리 루프가 크레딧 소진/전량실패/취소/정상 완료로 끝나면
 * {@code pauseSettled=false}가 DB 행에 영구히 남는다"는 고착 경합(§0.21.3 (i))을 실행으로 재현하고,
 * 종단 정규화(조건부 승격) 적용 후 사라짐을 못박는 계약 테스트.</b>
 *
 * <p><b>왜 고착되는가</b>: {@code runAnalysis()}/{@code runAnalysisResume()}는 {@code creditExhausted} 분기를
 * {@code pauseDetected} 분기보다 먼저 검사하고 {@code return}한다. 둘이 동시에 서면 {@code pauseDetected} 블록의
 * {@code setPauseSettled(TRUE)}에 도달하지 못하고, {@code handleCreditExhaustedPause()}는 플래그를 건드리지 않은 채
 * {@code saveSessionState()}한다. 전량실패 분기도 같다 — 단 그쪽은 {@code pauseDetected}가 아예 서지 않는 시점
 * (남은 미시작 태스크 없음)에 일시정지가 도착해야 한다.
 *
 * <p><b>하네스</b>: {@link MainApiControllerPauseSettledContractTest}와 같은 구조(실 {@code @TempDir} + LLM 대역 +
 * 리플렉션 기동, 스레드풀 1). LLM 대역이 <b>N번째 호출 도중</b> 실제 {@code pauseSession()} 엔드포인트를 부른 뒤
 * 지정된 예외를 던져 "일시정지 요청 후 같은 배치에서 크레딧 소진/실패"를 만든다. "DB 행"은 {@code saveSessionState()}
 * 호출 시점의 분리 사본이며, 목록 API({@code UserActivityController})는 그 마지막 사본만 본다.
 *
 * <p><b>{@code pauseDetected==false} 단언(C2, §A.2 의무)</b>: {@code pauseDetected}는 루프 지역변수라 직접 읽을 수 없다.
 * 대신 두 관측으로 보인다 — (a) LLM 대역 호출 수 == 파일 수(어느 태스크도 {@code shouldStop()} 조기 반환을 타지 않았다
 * = {@code pauseDetected.set(true)}가 있는 유일한 지점에 도달하지 않았다), (b) recentLogs에 {@code [전체 실패]}가 있고
 * {@code [일시정지 완료]}가 없다({@code pauseDetected} 블록은 {@code return}하므로 그 로그가 없으면 그 블록을 타지 않은 것).
 *
 * <p><b>기존 단언 보호</b>: 조건부 승격이므로 사용자 일시정지가 없던 단독 경로의 raw {@code NULL}은 그대로다 —
 * 그 단언은 {@link MainApiControllerPauseSettledContractTest}의 {@code DoD5_*}/{@code 대조군_*}이 무수정으로 지킨다.
 * 이 클래스의 {@code 대조군_*}은 같은 하네스에서 NULL 유지를 다시 한 번 보인다.
 */
class MainApiControllerPauseSettledTerminalNormalizationContractTest {

  private static final String SID = "sid-pause-settled-terminal";
  private static final String USERNAME = "jhjung";
  private static final Long USER_SEQ = 10L;
  private static final String STUB_COMMENT_PREFIX = "// [AI 주석] 테스트 대역이 생성한 주석";
  private static final int FILE_COUNT = 5;

  /** {@code saveSessionState()} 호출 시점에 뜬 "DB 행" 사본. */
  private record SavedRow(String status, Boolean pauseSettled, String pendingJson) {}

  @TempDir
  Path tempDir;

  private ClaudeService claudeService;
  private AnalysisSessionManager sessionManager;
  private AnalysisHistoryRepository analysisHistoryRepository;
  private SessionRepository sessionRepository;
  private LlmModelOptionService llmModelOptionService;
  private MainApiController controller;
  private UserActivityController userActivityController;

  private Path srcRoot;
  private Path outRoot;
  private SessionState session;
  private AnalysisHistory history;
  private Authentication ownerAuth;

  private final List<SavedRow> savedRows = new CopyOnWriteArrayList<>();
  private final AtomicInteger llmCalls = new AtomicInteger();
  /** LLM 대역이 몇 번째 호출에서 pauseSession()을 부를지. 0이면 부르지 않는다. */
  private final AtomicInteger pauseOnCall = new AtomicInteger(0);
  /** pauseSession() 직후 취소까지 할지(취소 분기 케이스). */
  private final AtomicReference<Boolean> cancelAfterPause = new AtomicReference<>(false);
  /** 호출 번호(1부터) → 던질 예외. null이면 정상 응답. 시나리오마다 갈아 끼운다. */
  private final AtomicReference<IntFunction<RuntimeException>> failurePlan = new AtomicReference<>(n -> null);
  /** pauseSession() 직후(루프가 아직 도는 시점) 관측값. */
  private final AtomicReference<Map<String, Object>> observedRightAfterPause = new AtomicReference<>();

  @BeforeEach
  void setUp() throws Exception {
    srcRoot = tempDir.resolve("myproj");
    outRoot = tempDir.resolve("outroot");
    Files.createDirectories(srcRoot);
    Files.createDirectories(outRoot);

    claudeService = mock(ClaudeService.class);
    sessionManager = mock(AnalysisSessionManager.class);
    analysisHistoryRepository = mock(AnalysisHistoryRepository.class);
    sessionRepository = mock(SessionRepository.class);
    llmModelOptionService = mock(LlmModelOptionService.class);
    CodeContentRagService codeContentRagService = mock(CodeContentRagService.class);

    SessionConfig sessionConfig = new SessionConfig();
    sessionConfig.setMaxRetries(0);
    RetryHandler retryHandler = new RetryHandler(new ApiErrorHandler(), sessionConfig);

    controller = new MainApiController(
        claudeService, null, sessionManager, null, null, retryHandler,
        analysisHistoryRepository, null, null, null, null, null,
        codeContentRagService, llmModelOptionService, null);
    setField("chunkingThresholdBytes", 153600L);
    setField("chunkSizeLines", 1000);
    setField("chunkOverlapLines", 100);
    setField("threadPoolSize", 1);
    setField("uploadStoragePath", tempDir.resolve(".uploads").toString());

    userActivityController = new UserActivityController(
        analysisHistoryRepository, mock(PresentationGeneratorService.class), sessionRepository);

    session = new SessionState(SID, srcRoot.toString(), outRoot.toString());
    session.setUsername(USERNAME);
    session.setUserId(USER_SEQ);
    session.setGenerateReadme(false);
    when(sessionManager.getSession(SID)).thenReturn(session);

    history = new AnalysisHistory(USER_SEQ, SID, srcRoot.toString(), outRoot.toString());
    history.setId(1L);
    when(analysisHistoryRepository.findBySessionId(SID)).thenReturn(history);
    when(analysisHistoryRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ)).thenReturn(List.of(history));
    when(analysisHistoryRepository.save(any(AnalysisHistory.class))).thenAnswer(inv -> inv.getArgument(0));

    doAnswer(inv -> {
      SessionState s = inv.getArgument(0);
      savedRows.add(new SavedRow(s.getStatus(), s.getPauseSettled(), s.getPendingFilePathsJson()));
      return null;
    }).when(sessionManager).saveSessionState(any(SessionState.class));
    when(sessionRepository.findAllById(any())).thenAnswer(inv -> {
      Iterable<String> ids = inv.getArgument(0);
      List<SessionState> rows = new ArrayList<>();
      for (String id : ids) {
        if (SID.equals(id) && !savedRows.isEmpty()) {
          SavedRow last = savedRows.get(savedRows.size() - 1);
          SessionState row = new SessionState(SID, srcRoot.toString(), outRoot.toString());
          row.setStatus(last.status());
          row.setPauseSettled(last.pauseSettled());
          row.setPendingFilePathsJson(last.pendingJson());
          rows.add(row);
        }
      }
      return rows;
    });

    when(llmModelOptionService.getActiveFailoverTarget()).thenReturn(Optional.empty());

    // LLM 대역: pauseOnCall번째 호출 도중 실제 pauseSession()을 부르고(필요하면 취소까지), 그 다음 failurePlan대로 던진다.
    when(claudeService.analyzeCodeWithClaude(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> {
          int n = llmCalls.incrementAndGet();
          if (n == pauseOnCall.get()) {
            Map<String, Object> pauseResponse = controller.pauseSession(Map.of("sessionId", SID), ownerAuth);
            assertEquals(Boolean.TRUE, pauseResponse.get("success"), "pauseSession() 실패: " + pauseResponse);
            if (cancelAfterPause.get()) session.cancel();
            observedRightAfterPause.set(observeNow());
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

  @SuppressWarnings("unchecked")
  private Map<String, Object> observeNow() {
    AnalysisStatusDto dto = controller.getAnalysisStatus(SID, 80, ownerAuth);
    ResponseEntity<?> listResponse = userActivityController.getMyAnalysisHistory(ownerAuth);
    List<Map<String, Object>> rows = (List<Map<String, Object>>) listResponse.getBody();
    Map<String, Object> listRow = rows.stream().filter(r -> SID.equals(r.get("sessionId"))).findFirst().orElseThrow();
    Map<String, Object> o = new java.util.LinkedHashMap<>();
    o.put("session.status", session.getStatus());
    o.put("session.phase", session.getCurrentPhase());
    o.put("session.pauseSettled(raw)", session.getPauseSettled());
    o.put("session.pendingJson", session.getPendingFilePathsJson());
    o.put("dto.pauseSettled", dto.isPauseSettled());
    o.put("history.status", history.getStatus());
    o.put("list.status", listRow.get("status"));
    o.put("list.pauseSettled", listRow.get("pauseSettled"));
    o.put("savedRows.size", savedRows.size());
    return o;
  }

  private void runAnalysisAndAwait() throws Exception {
    Method m = MainApiController.class.getDeclaredMethod("runAnalysis",
        String.class, String.class, String.class, boolean.class, Long.class, String.class,
        Set.class, boolean.class);
    m.setAccessible(true);
    m.invoke(controller, SID, srcRoot.toString(), outRoot.toString(), false, null, USERNAME, null, false);
  }

  @SuppressWarnings("unchecked")
  private void resumeAndAwait() throws Exception {
    Method m = MainApiController.class.getDeclaredMethod("resumePendingFilesInThread", SessionState.class, String.class);
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

  private List<String> recentLogs() {
    return session.getRecentLogLines(1000);
  }

  private static void print(String label, Map<String, Object> o) {
    System.out.println("[002B] " + label + " : " + o);
  }

  /** 일시정지 요청 직후 관측이 실경로(false 미확정)를 모델링했는지 — 이게 아니면 뒤의 단언은 의미가 없다. */
  private void assertPauseWasRequestedAndUnsettled() {
    Map<String, Object> a = observedRightAfterPause.get();
    assertNotNull(a, "LLM 대역이 pauseSession()을 호출하지 못했다 — 하네스가 실경로를 모델링하지 못한 것");
    print("(a) 일시정지 요청 직후", a);
    assertEquals(Boolean.FALSE, a.get("session.pauseSettled(raw)"), "요청 직후 raw는 FALSE여야 한다");
    assertEquals(false, a.get("list.pauseSettled"), "요청 직후 목록 API는 미확정(false)이어야 한다");
  }

  private SavedRow lastSaved() {
    assertFalse(savedRows.isEmpty(), "종단 저장이 한 번도 없었다");
    return savedRows.get(savedRows.size() - 1);
  }

  private void assertTerminalRowSettled(String label) {
    Map<String, Object> o = observeNow();
    print(label + " 종단 후", o);
    SavedRow last = lastSaved();
    assertEquals(Boolean.TRUE, last.pauseSettled(),
        label + ": 종단 저장된 DB 행의 pauseSettled가 TRUE여야 한다(FALSE면 고착 — §0.21.3 (i)). 행=" + last);
    assertEquals(Boolean.TRUE, session.getPauseSettled(), label + ": 메모리 세션 raw도 TRUE(조건부 승격 결과)");
    assertEquals(true, o.get("dto.pauseSettled"));
  }

  // ────────────────────────────────────────────────────────────────────────────
  // C1 — 일시정지 요청 후 같은 배치에서 크레딧 소진 (DoD 1)
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * <b>C1-없음</b>: failover 대상 미지정 → 단순 PAUSED. 2번째 파일 처리 도중 일시정지 요청 → 그 파일이 곧바로
   * INSUFFICIENT_CREDITS로 실패. 3~5번째 태스크는 {@code shouldStop()}으로 조기 반환({@code pauseDetected=true})하지만
   * 크레딧 분기가 먼저 {@code return}한다. 목록 API가 {@code pauseSettled=true} + 재개 가능한 pending을 내려줘야 한다.
   */
  @Test
  void C1_일시정지_직후_같은_배치_크레딧소진_failover없음_종단행은_true이고_pending이_비어있지_않다() throws Exception {
    pauseOnCall.set(2);
    failurePlan.set(n -> n == 2 ? credits() : null);

    runAnalysisAndAwait();

    assertPauseWasRequestedAndUnsettled();
    assertEquals("PAUSED", session.getCurrentPhase(), "크레딧 소진(failover 없음)은 PAUSED로 끝난다. errorLog=" + session.getErrorLog());
    assertTrue(recentLogs().stream().anyMatch(l -> l.contains("[크레딧 소진 일시정지]")),
        "크레딧 소진 분기를 타야 한다: " + recentLogs());
    assertFalse(recentLogs().stream().anyMatch(l -> l.contains("[일시정지 완료]")),
        "pauseDetected 블록은 크레딧 분기가 먼저 return해서 도달하지 못해야 한다(그래야 고착 경합의 실경로다)");
    assertEquals(FILE_COUNT - 1, session.getPendingFilePaths().size(),
        "1개 성공 뒤 실패한 파일 포함 4개가 pending(재개 가능 데이터)이어야 한다: " + session.getPendingFilePaths());
    Map<String, Object> o = observeNow();
    assertEquals("PAUSED", o.get("list.status"));
    assertEquals(true, o.get("list.pauseSettled"), "C1(failover 없음): 목록 API가 true를 내려야 재개 버튼이 보인다. " + o);
    assertTerminalRowSettled("C1-failover없음");
    assertFalse("[]".equals(lastSaved().pendingJson()) || lastSaved().pendingJson() == null,
        "종단 행에 pending이 함께 담겨야 한다: " + lastSaved());
  }

  /**
   * <b>C1-있음</b>: failover 대상 지정 → {@code session.status=AWAITING_FAILOVER_CONFIRM}, {@code history.status=PAUSED}.
   * 목록 API는 history의 PAUSED로 행을 잡으므로 여기서도 {@code pauseSettled=true}여야 failover 컨펌에 도달할 수 있다.
   */
  @Test
  void C1_일시정지_직후_같은_배치_크레딧소진_failover있음_종단행은_true다() throws Exception {
    LlmModelOption local = mock(LlmModelOption.class);
    when(local.getModelKey()).thenReturn("local-llm");
    when(llmModelOptionService.getActiveFailoverTarget()).thenReturn(Optional.of(local));
    pauseOnCall.set(2);
    failurePlan.set(n -> n == 2 ? credits() : null);

    runAnalysisAndAwait();

    assertPauseWasRequestedAndUnsettled();
    assertEquals(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM, session.getStatus());
    assertEquals(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM, session.getCurrentPhase());
    assertEquals("PAUSED", history.getStatus(), "failover 하위 분기도 history는 PAUSED로 저장한다(§0.21.3)");
    assertEquals("local-llm", session.getFailoverModelKey());
    Map<String, Object> o = observeNow();
    assertEquals("PAUSED", o.get("list.status"));
    assertEquals(true, o.get("list.pauseSettled"), "C1(failover 있음): 목록 API가 true여야 컨펌 진입이 막히지 않는다. " + o);
    assertTerminalRowSettled("C1-failover있음");
    assertEquals(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM, lastSaved().status());
    assertFalse(session.getPendingFilePaths().isEmpty());
  }

  /** C1을 재개 루프({@code runAnalysisResume()})에서도 같은 순서(크레딧 → pauseDetected)로 확인한다. */
  @Test
  void C1_재개루프에서도_일시정지_직후_크레딧소진이면_종단행은_true다() throws Exception {
    // 1회차: 정상 일시정지로 PAUSED + pending 확정(기존 TASK-002 경로).
    pauseOnCall.set(2);
    runAnalysisAndAwait();
    assertEquals("PAUSED", session.getCurrentPhase());
    assertEquals(Boolean.TRUE, session.getPauseSettled());
    int pendingBefore = session.getPendingFilePaths().size();
    assertTrue(pendingBefore > 1, "재개할 파일이 2개 이상이어야 재개 루프에서 경합을 만들 수 있다");

    // 2회차: 재개 후 첫 파일 도중 일시정지 → 그 파일이 크레딧 소진.
    observedRightAfterPause.set(null);
    llmCalls.set(0);
    pauseOnCall.set(1);
    failurePlan.set(n -> n == 1 ? credits() : null);
    resumeAndAwait();

    assertPauseWasRequestedAndUnsettled();
    assertEquals("PAUSED", session.getCurrentPhase());
    assertTrue(recentLogs().stream().anyMatch(l -> l.contains("[크레딧 소진 일시정지]")));
    Map<String, Object> o = observeNow();
    assertEquals(true, o.get("list.pauseSettled"), "C1(재개 루프): 목록 API true. " + o);
    assertTerminalRowSettled("C1-재개루프");
    assertEquals(pendingBefore, session.getPendingFilePaths().size(), "실패한 파일이 다시 pending에 남아야 한다");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // C2 — pauseDetected가 서지 않는 시점의 일시정지 + 전량실패 (DoD 2)
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * <b>C2</b>: 전 파일이 일반 예외로 실패하고, <b>마지막 파일</b> 처리 도중 일시정지 요청이 도착한다. 남은 미시작 태스크가
   * 없으므로 {@code pauseDetected}는 서지 않고, 루프는 전량실패 분기로 끝난다. {@code pauseDetected==false} 근거:
   * (a) LLM 호출 수 == 파일 수(조기 반환 0건 — {@code set(true)} 지점 미도달), (b) {@code [전체 실패]} 로그 O / {@code [일시정지 완료]} 로그 X.
   */
  @Test
  void C2_미시작_태스크가_없는_시점의_일시정지_후_전량실패_종단행은_true이고_pauseDetected는_false였다() throws Exception {
    pauseOnCall.set(FILE_COUNT);
    failurePlan.set(n -> new RuntimeException("테스트 대역: 모든 파일 실패 #" + n));

    runAnalysisAndAwait();

    assertPauseWasRequestedAndUnsettled();
    // pauseDetected == false 였다는 단언(§A.2 의무, 두 관측)
    assertEquals(FILE_COUNT, llmCalls.get(),
        "모든 태스크가 LLM까지 도달해야 한다(= shouldStop() 조기 반환 0건 = pauseDetected.set(true) 지점 미도달)");
    assertTrue(recentLogs().stream().anyMatch(l -> l.contains("[전체 실패]")),
        "전량실패 분기로 끝나야 한다: " + recentLogs());
    assertFalse(recentLogs().stream().anyMatch(l -> l.contains("[일시정지 완료]")),
        "pauseDetected 블록의 로그가 있으면 pauseDetected가 섰던 것 — C2 구성이 깨진 것이다");
    assertEquals("PAUSED", session.getCurrentPhase());
    assertEquals(FILE_COUNT, session.getStatistics().getFailureCount());
    history.setStatus("PAUSED"); // userId=null 하네스: 루프 내부 history가 없어 fixture status만 맞춘다(관측 대상은 pauseSettled)
    Map<String, Object> o = observeNow();
    assertEquals("PAUSED", o.get("list.status"));
    assertEquals(true, o.get("list.pauseSettled"), "C2: 목록 API가 true를 내려야 한다. " + o);
    assertTerminalRowSettled("C2-전량실패");
    assertEquals(FILE_COUNT, session.getPendingFilePaths().size(), "전량실패는 전부 재시도용 pending으로 남긴다");
  }

  /**
   * C2를 재개 루프의 전량실패 분기에서도 확인한다. 재개 루프의 전량실패 조건은 <b>누적</b> 성공/스킵 0을 보므로
   * (외부 카운터가 세션 통계로 초기화된다), 1회차를 "일시정지 없는 전량실패"로 두어 누적 성공 0을 만든 뒤
   * 재개 배치의 마지막 파일 도중 일시정지 → 다시 전량실패로 끝나게 한다.
   */
  @Test
  void C2_재개루프에서도_마지막_파일_도중_일시정지_후_전량실패면_종단행은_true다() throws Exception {
    pauseOnCall.set(0);
    failurePlan.set(n -> new RuntimeException("테스트 대역: 1회차 전량 실패 #" + n));
    runAnalysisAndAwait();
    assertEquals("PAUSED", session.getCurrentPhase(), "errorLog=" + session.getErrorLog());
    assertNull(session.getPauseSettled(), "1회차는 일시정지가 없어 raw NULL");
    int pendingBefore = session.getPendingFilePaths().size();
    assertEquals(FILE_COUNT, pendingBefore);

    savedRows.clear();
    llmCalls.set(0);
    pauseOnCall.set(pendingBefore); // 재개 배치의 마지막 파일에서 일시정지
    failurePlan.set(n -> new RuntimeException("테스트 대역: 재개 전량 실패 #" + n));
    resumeAndAwait();

    assertPauseWasRequestedAndUnsettled();
    assertEquals(pendingBefore, llmCalls.get(), "재개 배치 전 태스크가 LLM까지 도달(조기 반환 0건 = pauseDetected 미발생)");
    assertTrue(recentLogs().stream().anyMatch(l -> l.contains("[전체 실패] 재시도한")),
        "재개 루프 전량실패 분기로 끝나야 한다: " + recentLogs());
    assertFalse(recentLogs().stream().anyMatch(l -> l.startsWith("[일시정지]")),
        "재개 루프 pauseDetected 블록 로그가 없어야 한다: " + recentLogs());
    assertEquals("PAUSED", session.getCurrentPhase());
    Map<String, Object> o = observeNow();
    assertEquals(true, o.get("list.pauseSettled"), "C2(재개 루프): 목록 API true. " + o);
    assertTerminalRowSettled("C2-재개루프");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 고아 false 방지 — 취소 분기 / 정상 완료 경로 (작업 내용 3)
  // ────────────────────────────────────────────────────────────────────────────

  /** 일시정지 요청 직후 취소 → 취소 분기. 화면 증상은 없지만(CANCELLED는 PAUSED 필터 밖) 행에 false를 남기지 않는다. */
  @Test
  void 취소분기_일시정지_직후_취소되면_CANCELLED_행에_false가_남지_않는다() throws Exception {
    pauseOnCall.set(2);
    cancelAfterPause.set(true);

    runAnalysisAndAwait();

    assertPauseWasRequestedAndUnsettled();
    assertEquals("CANCELLED", session.getCurrentPhase(), "errorLog=" + session.getErrorLog());
    assertTrue(session.isCancelled());
    assertEquals(Boolean.TRUE, lastSaved().pauseSettled(), "취소 종단 행에 false가 남으면 고아 false다: " + lastSaved());
    assertEquals(Boolean.TRUE, session.getPauseSettled());
  }

  /** 마지막 파일 도중 일시정지가 오지만 그 파일이 성공하고 미시작 태스크가 없어 정상 완료로 흐르는 경우. */
  @Test
  void 정상완료경로_마지막_파일_도중_일시정지가_와도_COMPLETED_저장에_false가_남지_않는다() throws Exception {
    pauseOnCall.set(FILE_COUNT);
    // completeSession()은 mock이라 실제 저장을 안 하므로, 저장 직전 시점의 값을 completeSession 호출로 관측한다.
    AtomicReference<Boolean> rawAtComplete = new AtomicReference<>();
    doAnswer(inv -> { rawAtComplete.set(session.getPauseSettled()); return null; })
        .when(sessionManager).completeSession(SID);

    runAnalysisAndAwait();

    assertPauseWasRequestedAndUnsettled();
    assertEquals(FILE_COUNT, llmCalls.get(), "조기 반환 0건(pauseDetected 미발생)");
    assertEquals("COMPLETED", session.getCurrentPhase(), "errorLog=" + session.getErrorLog());
    assertNotNull(rawAtComplete.get(), "completeSession()이 호출되지 않았다");
    assertEquals(Boolean.TRUE, rawAtComplete.get(), "완료 저장 시점(completeSession)에 false가 남아 있으면 고아 false다");
    assertEquals(Boolean.TRUE, session.getPauseSettled());
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 대조군 — 일시정지가 선행하지 않은 단독 경로는 raw NULL 그대로 (조건부 승격의 근거)
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void 대조군_일시정지_없이_크레딧소진되면_raw는_NULL_그대로다() throws Exception {
    pauseOnCall.set(0);
    failurePlan.set(n -> n == 2 ? credits() : null);
    runAnalysisAndAwait();
    assertEquals("PAUSED", session.getCurrentPhase(), "errorLog=" + session.getErrorLog());
    assertTrue(recentLogs().stream().anyMatch(l -> l.contains("[크레딧 소진 일시정지]")));
    assertNull(session.getPauseSettled(), "일시정지가 없던 크레딧 소진 경로의 raw는 NULL이어야 한다(무조건 TRUE 금지)");
    assertNull(lastSaved().pauseSettled(), "저장 행도 NULL: " + lastSaved());
    assertTrue(session.hasSettledPause());
    assertNull(observedRightAfterPause.get(), "pauseSession()이 호출되지 않아야 한다");
  }

  @Test
  void 대조군_일시정지_없이_전량실패하면_raw는_NULL_그대로다() throws Exception {
    pauseOnCall.set(0);
    failurePlan.set(n -> new RuntimeException("테스트 대역: 전량 실패 #" + n));
    runAnalysisAndAwait();
    assertEquals("PAUSED", session.getCurrentPhase(), "errorLog=" + session.getErrorLog());
    assertTrue(recentLogs().stream().anyMatch(l -> l.contains("[전체 실패]")));
    assertNull(session.getPauseSettled(), "일시정지가 없던 전량실패 경로의 raw는 NULL이어야 한다");
    assertNull(lastSaved().pauseSettled(), "저장 행도 NULL: " + lastSaved());
    assertNull(observedRightAfterPause.get());
  }

  @Test
  void 대조군_일시정지_없이_정상완료되면_raw는_NULL_그대로다() throws Exception {
    pauseOnCall.set(0);
    AtomicReference<Boolean> rawAtComplete = new AtomicReference<>(Boolean.TRUE); // NULL 관측을 구분하려고 기본값을 TRUE로
    doAnswer(inv -> { rawAtComplete.set(session.getPauseSettled()); return null; })
        .when(sessionManager).completeSession(SID);
    runAnalysisAndAwait();
    assertEquals("COMPLETED", session.getCurrentPhase(), "errorLog=" + session.getErrorLog());
    assertEquals(FILE_COUNT, llmCalls.get());
    assertNull(session.getPauseSettled(), "일시정지가 없던 정상 완료 경로의 raw는 NULL이어야 한다");
    assertNull(rawAtComplete.get(), "완료 저장 시점에도 NULL이어야 한다");
    assertNull(observedRightAfterPause.get());
  }
}
