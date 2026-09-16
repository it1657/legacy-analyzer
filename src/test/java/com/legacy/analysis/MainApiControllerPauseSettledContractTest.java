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
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
 * TASK-002 (work-order 2026-09-remaining-ux-fixes v1, 설계 02-design-v1 §4.2) —
 * <b>{@code SessionState.pauseSettled} 플래그가 실제 처리 루프에서 어떻게 오르내리는지를 "실행 결과"로 못박는 계약 테스트</b>.
 *
 * <p><b>무엇을 재현하는가</b>: 사용자가 일시정지를 누르면 {@code pauseSession()}이 status/currentPhase를 즉시
 * PAUSED로 바꾸지만, 스레드풀에서 이미 돌고 있던 파일이 끝나 {@code pauseDetected} 블록이 {@code pendingFilePaths}를
 * 기록하기 전까지는 '이어서 분석'을 눌러도 재개할 파일이 없다. 이 "멈추는 중" 구간을 별도 신호({@code pauseSettled=false})로
 * 노출하는 것이 REQ-002 백엔드다 — PAUSED 문자열 자체는 그대로 두고({@code shouldStop()} 무변경, PAUSING 도입 금지) 플래그만 얹는다.
 *
 * <p><b>하네스 형태</b>: {@code MainApiControllerDashboardStatusPathContractTest}와 같은 실제 {@code @TempDir} + LLM 대역 +
 * 리플렉션으로 {@code runAnalysis()}/{@code resumePendingFilesInThread()}를 직접 기동한다. 스레드풀 크기 1로 파일이
 * 순차 처리되게 하고, LLM 대역이 <b>N번째 파일을 처리하는 도중</b> 실제 {@code pauseSession()} 엔드포인트를 호출해
 * "이미 돌고 있던 파일이 끝나야 확정되는" 구간을 재현한다.
 *
 * <p><b>"DB"의 모델링</b>: {@code AnalysisSessionManager}는 mock이지만 {@code saveSessionState()}가 호출될 때마다
 * 그 시점의 (status, pauseSettled, pendingFilePathsJson)을 <b>분리된 사본</b>으로 떠 둔다 — 이것이 이 테스트의 "DB 행"이다.
 * {@code UserActivityController}에 주입한 {@code SessionRepository} 대역은 그 마지막 사본만 돌려준다. 즉 목록 API가
 * 보는 값은 <b>실제로 저장된 것</b>뿐이며, 메모리 세션 객체를 몰래 들여다보지 않는다(pauseSession()이 저장을 빼먹으면
 * (a)가 RED가 나는 구조). 실제 H2 저장/조회는 {@code UserActivityControllerPauseSettledJpaTest}가 맡는다.
 *
 * <p><b>왜 2회차가 핵심인가</b>(work-order TASK-002 DoD 2(c)): 재개 진입점이 재개 시작 시 {@code setPendingFilePaths(new ArrayList<>())}
 * = {@code "[]"}를 미리 써두므로, "json이 null인가"로 확정 여부를 판별하는 구현은 2회차부터 오판한다. 이 테스트는
 * 2회차 일시정지 요청 시점에 json이 이미 {@code "[]"}(non-null)임을 함께 관측해 그 구현을 쓰지 않았음을 보인다.
 */
class MainApiControllerPauseSettledContractTest {

  private static final String SID = "sid-pause-settled-contract";
  private static final String USERNAME = "jhjung";
  private static final Long USER_SEQ = 10L;
  private static final String STUB_COMMENT_PREFIX = "// [AI 주석] 테스트 대역이 생성한 주석";
  /** 분석 대상 파일 수. 스레드풀 1이라 순차 처리된다. */
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

  /** saveSessionState() 호출 순서대로 쌓이는 "DB 행" 사본(스레드 간 공유). */
  private final List<SavedRow> savedRows = new CopyOnWriteArrayList<>();
  /** LLM 대역 호출 횟수(파일 단위). */
  private final AtomicInteger llmCalls = new AtomicInteger();
  /** LLM 대역이 몇 번째 호출에서 pauseSession()을 부를지. 0이면 부르지 않는다. */
  private final AtomicInteger pauseOnCall = new AtomicInteger(0);
  /** pauseSession() 직후(같은 스레드, 루프가 아직 도는 시점) 관측한 값들. */
  private final AtomicReference<Map<String, Object>> observedRightAfterPause = new AtomicReference<>();

  @BeforeEach
  void setUp() throws Exception {
    srcRoot = tempDir.resolve("myproj");
    // 출력 루트 폴더명을 "out"으로 두면 isSupportedFile()이 전부 제외하므로 outroot로 둔다(기존 하네스 조건 승계).
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

    // 세션 + 이력 픽스처
    session = new SessionState(SID, srcRoot.toString(), outRoot.toString());
    session.setUsername(USERNAME);
    session.setUserId(USER_SEQ);
    session.setGenerateReadme(false);
    when(sessionManager.getSession(SID)).thenReturn(session);

    history = new AnalysisHistory(USER_SEQ, SID, srcRoot.toString(), outRoot.toString());
    history.setId(1L);
    when(analysisHistoryRepository.findBySessionId(SID)).thenReturn(history);
    when(analysisHistoryRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ))
        .thenReturn(List.of(history));
    when(analysisHistoryRepository.save(any(AnalysisHistory.class))).thenAnswer(inv -> inv.getArgument(0));

    // "DB": saveSessionState()가 저장한 마지막 사본만 findAllById()가 돌려준다.
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

    // LLM 대역: pauseOnCall번째 호출 도중에 실제 pauseSession() 엔드포인트를 호출한다(= 처리 중인 파일이 있는 상태의 일시정지).
    when(claudeService.analyzeCodeWithClaude(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> {
          int n = llmCalls.incrementAndGet();
          if (n == pauseOnCall.get()) {
            Map<String, Object> pauseResponse = controller.pauseSession(Map.of("sessionId", SID), ownerAuth);
            assertEquals(Boolean.TRUE, pauseResponse.get("success"), "pauseSession()이 실패하면 아무것도 관측하지 못한다: " + pauseResponse);
            observedRightAfterPause.set(observeNow());
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

  private void setField(String name, Object value) throws Exception {
    Field field = MainApiController.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(controller, value);
  }

  /** 세 노출 지점을 한 번에 관측한다: 메모리 세션 원시값 / 폴링 DTO / 목록 API("DB" 경유). */
  @SuppressWarnings("unchecked")
  private Map<String, Object> observeNow() {
    AnalysisStatusDto dto = controller.getAnalysisStatus(SID, 80, ownerAuth);
    ResponseEntity<?> listResponse = userActivityController.getMyAnalysisHistory(ownerAuth);
    List<Map<String, Object>> rows = (List<Map<String, Object>>) listResponse.getBody();
    Map<String, Object> listRow = rows.stream().filter(r -> SID.equals(r.get("sessionId"))).findFirst().orElseThrow();
    Map<String, Object> o = new java.util.LinkedHashMap<>();
    o.put("session.status", session.getStatus());
    o.put("session.pauseSettled(raw)", session.getPauseSettled());
    o.put("session.pendingJson", session.getPendingFilePathsJson());
    o.put("dto.phase", dto.getPhase());
    o.put("dto.pauseSettled", dto.isPauseSettled());
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
    // userId=null: 기존 하네스(DashboardStatusPathContractTest)와 동일하게 루프 내부의 AnalysisHistory 생성/완료 알림 경로를
    // 회피한다(완료 경로가 null 협력자(notificationService 등)에 닿아 FAILED로 끝나는 것을 막기 위함). 목록 API가 보는
    // 이력 행은 setUp()의 fixture(history)이며, pauseSession()이 findBySessionId()로 그 fixture의 status를 PAUSED로 바꾼다.
    m.invoke(controller, SID, srcRoot.toString(), outRoot.toString(), false, null, USERNAME, null, false);
  }

  /** '이어서 분석' 실제 진입점을 호출하고 재개 스레드가 종료 상태에 도달할 때까지 기다린다(기존 ResumeCopyModePathTest 패턴). */
  @SuppressWarnings("unchecked")
  private void resumeAndAwait() throws Exception {
    Method m = MainApiController.class.getDeclaredMethod(
        "resumePendingFilesInThread", SessionState.class, String.class);
    m.setAccessible(true);
    Map<String, Object> response = (Map<String, Object>) m.invoke(controller, session, SID);
    assertEquals(Boolean.TRUE, response.get("success"),
        "재개 진입점이 실패하면 이 테스트는 아무것도 검증하지 못한다: " + response.get("message"));

    long deadline = System.currentTimeMillis() + 60_000L;
    while (System.currentTimeMillis() < deadline) {
      String phase = session.getCurrentPhase();
      if ("COMPLETED".equals(phase) || "PAUSED".equals(phase) || "FAILED".equals(phase)
          || "CANCELLED".equals(phase)) {
        return;
      }
      Thread.sleep(50);
    }
    fail("재개 스레드가 60초 안에 종료 상태에 도달하지 않았다. phase=" + session.getCurrentPhase());
  }

  private static void print(String label, Map<String, Object> o) {
    System.out.println("[P4] " + label + " : " + o);
  }

  // ────────────────────────────────────────────────────────────────────────────

  /**
   * <b>P4 (a)(b)(c) — 1회차·2회차 일시정지를 실제 루프로 관측.</b>
   * (a) 일시정지 요청 직후: 메모리/DTO/목록 API 셋 다 {@code pauseSettled=false} (status/phase는 이미 PAUSED).
   * (b) pendingFilePaths 확정 후: 셋 다 {@code true}, pending 비어있지 않음.
   * (c) 재개 후 2회차 일시정지에서도 (a)(b)가 같이 성립 — 이때 요청 시점의 pendingJson은 이미 {@code "[]"}다.
   */
  @Test
  void P4_일시정지_요청직후는_false이고_pending확정후_true가_되며_재개후_2회차도_동일하다() throws Exception {
    // ── 1회차: 2번째 파일 처리 도중 일시정지 → 2번째 파일은 끝나고 3~5번째가 pending
    pauseOnCall.set(2);
    assertNull(session.getPauseSettled(), "시작 시점 원시값은 NULL(=확정으로 읽힘)이어야 한다");
    assertTrue(session.hasSettledPause());

    runAnalysisAndAwait();

    Map<String, Object> a1 = observedRightAfterPause.get();
    assertNotNull(a1, "LLM 대역이 pauseSession()을 호출하지 못했다 — 하네스가 실경로를 모델링하지 못한 것");
    print("1회차 (a) 일시정지 요청 직후", a1);
    assertEquals("PAUSED", a1.get("session.status"));
    assertEquals(Boolean.FALSE, a1.get("session.pauseSettled(raw)"));
    assertNull(a1.get("session.pendingJson"), "1회차 요청 시점에는 pending이 아직 기록되지 않아 null이다");
    assertEquals("PAUSED", a1.get("dto.phase"));
    assertEquals(false, a1.get("dto.pauseSettled"), "(a) 폴링 DTO가 미확정(false)을 내려줘야 한다");
    assertEquals("PAUSED", a1.get("list.status"));
    assertEquals(false, a1.get("list.pauseSettled"), "(a) 목록 API가 미확정(false)을 내려줘야 한다 — pauseSession()이 저장까지 해야 DB 경유로 보인다");

    Map<String, Object> b1 = observeNow();
    print("1회차 (b) pending 확정 후", b1);
    assertEquals("PAUSED", session.getCurrentPhase(), "루프가 PAUSED로 끝나야 한다. errorLog=" + session.getErrorLog());
    assertEquals(Boolean.TRUE, b1.get("session.pauseSettled(raw)"));
    List<String> pending1 = session.getPendingFilePaths();
    assertEquals(FILE_COUNT - 2, pending1.size(), "2개 처리 후 나머지가 pending이어야 한다: " + pending1);
    assertEquals(true, b1.get("dto.pauseSettled"), "(b) 폴링 DTO가 확정(true)으로 바뀌어야 한다");
    assertEquals(true, b1.get("list.pauseSettled"), "(b) 목록 API가 확정(true)으로 바뀌어야 한다");
    SavedRow last1 = savedRows.get(savedRows.size() - 1);
    assertEquals(Boolean.TRUE, last1.pauseSettled(), "확정 저장 시점의 DB 행은 true여야 한다: " + last1);
    assertFalse("[]".equals(last1.pendingJson()) || last1.pendingJson() == null, "확정 저장 시점의 DB 행은 pending을 함께 담아야 한다: " + last1);

    // ── 2회차: 재개 후 첫 파일 처리 도중 다시 일시정지 → 1개 처리, 2개 pending
    observedRightAfterPause.set(null);
    llmCalls.set(0);
    pauseOnCall.set(1);
    resumeAndAwait();

    Map<String, Object> a2 = observedRightAfterPause.get();
    assertNotNull(a2, "2회차에서 LLM 대역이 pauseSession()을 호출하지 못했다");
    print("2회차 (a) 일시정지 요청 직후", a2);
    assertEquals("PAUSED", a2.get("session.status"));
    assertEquals(Boolean.FALSE, a2.get("session.pauseSettled(raw)"));
    // 핵심: 재개 진입점이 "[]"를 미리 써두므로 json 기준 판별은 여기서 오판한다 — 플래그는 그와 무관하게 false다.
    assertEquals("[]", a2.get("session.pendingJson"), "2회차 요청 시점의 pendingJson은 재개 시 초기화된 \"[]\"(non-null)여야 한다");
    assertEquals(false, a2.get("dto.pauseSettled"), "(c) 2회차 폴링 DTO도 미확정(false)");
    assertEquals(false, a2.get("list.pauseSettled"), "(c) 2회차 목록 API도 미확정(false)");

    Map<String, Object> b2 = observeNow();
    print("2회차 (b) pending 확정 후", b2);
    assertEquals("PAUSED", session.getCurrentPhase(), "2회차 루프가 PAUSED로 끝나야 한다. errorLog=" + session.getErrorLog());
    assertEquals(Boolean.TRUE, b2.get("session.pauseSettled(raw)"));
    List<String> pending2 = session.getPendingFilePaths();
    assertEquals(pending1.size() - 1, pending2.size(), "재개 후 1개 처리됐으니 pending이 1개 줄어야 한다: " + pending2);
    assertEquals(true, b2.get("dto.pauseSettled"), "(c) 2회차 확정 후 DTO true");
    assertEquals(true, b2.get("list.pauseSettled"), "(c) 2회차 확정 후 목록 API true");
  }

  /**
   * <b>DoD 5 — 전량실패 PAUSED는 즉시 확정(true)</b>: 사용자 일시정지를 거치지 않았으므로 원시값은 NULL 그대로이고,
   * 헬퍼/DTO/목록 API 셋 다 true로 읽힌다(이 경로를 수정하지 않았음이 곧 근거).
   */
  @Test
  void DoD5_전량실패로_PAUSED가_된_세션은_pauseSettled가_NULL이지만_즉시_확정true로_내려온다() throws Exception {
    when(claudeService.analyzeCodeWithClaude(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException("테스트 대역: 모든 파일 실패"));

    runAnalysisAndAwait();

    assertEquals("PAUSED", session.getCurrentPhase(), "전량실패는 PAUSED로 끝나야 한다. errorLog=" + session.getErrorLog());
    // 전량실패 분기는 history.status를 PAUSED로 쓰지만(루프 내부 history), 이 하네스는 userId=null이라 그 history가 없다.
    // 목록 API가 PAUSED 행으로 인식해 배치 조회를 타게 하려고 fixture의 status만 같은 값으로 맞춘다(관측 대상은 pauseSettled).
    history.setStatus("PAUSED");
    Map<String, Object> o = observeNow();
    print("DoD5 전량실패", o);
    assertEquals("PAUSED", o.get("list.status"));
    assertNull(session.getPauseSettled(), "전량실패 분기는 플래그를 건드리지 않으므로 원시값은 NULL이어야 한다");
    SavedRow last = savedRows.get(savedRows.size() - 1);
    assertNull(last.pauseSettled(), "전량실패로 저장된 DB 행의 플래그도 NULL(=확정)이어야 한다: " + last);
    assertTrue(session.hasSettledPause());
    assertEquals(true, o.get("dto.pauseSettled"));
    assertEquals(true, o.get("list.pauseSettled"));
    assertFalse(session.getPendingFilePaths().isEmpty(), "전량실패는 재시도용 pending을 남긴다");
  }

  /**
   * <b>DoD 5 — 크레딧 소진 PAUSED도 즉시 확정(true)</b>: {@code handleCreditExhaustedPause()}(failover 대상 없음 → 단순 PAUSED)를
   * 기존 회귀 테스트와 같이 리플렉션으로 직접 호출한다. 플래그 원시값은 NULL 그대로다.
   */
  @Test
  void DoD5_크레딧소진으로_PAUSED가_된_세션은_pauseSettled가_NULL이지만_즉시_확정true로_내려온다() throws Exception {
    Method m = MainApiController.class.getDeclaredMethod(
        "handleCreditExhaustedPause", SessionState.class, AnalysisHistory.class, List.class, int.class);
    m.setAccessible(true);
    m.invoke(controller, session, history, List.of(srcRoot.resolve("com/x/F1.java").toString()), 0);

    Map<String, Object> o = observeNow();
    print("DoD5 크레딧소진", o);
    assertEquals("PAUSED", session.getCurrentPhase());
    assertEquals("PAUSED", history.getStatus());
    assertNull(session.getPauseSettled(), "크레딧 소진 경로는 플래그를 건드리지 않으므로 원시값은 NULL이어야 한다");
    assertTrue(session.hasSettledPause());
    assertEquals(true, o.get("dto.pauseSettled"));
    assertEquals(true, o.get("list.pauseSettled"));
  }

  /**
   * <b>양성 대조군</b> — 일시정지 없이 끝까지 돌면 COMPLETED이고 플래그는 NULL 그대로(true로 읽힘)다.
   * 위 케이스들의 false 관측이 "하네스가 무조건 false를 내는" 것이 아님을 보인다.
   */
  @Test
  void 대조군_일시정지_없이_완료되면_pauseSettled는_NULL이고_true로_읽힌다() throws Exception {
    pauseOnCall.set(0);
    runAnalysisAndAwait();
    assertEquals("COMPLETED", session.getCurrentPhase(), "errorLog=" + session.getErrorLog());
    assertEquals(FILE_COUNT, llmCalls.get());
    assertNull(session.getPauseSettled());
    assertTrue(session.hasSettledPause());
    assertTrue(controller.getAnalysisStatus(SID, 80, ownerAuth).isPauseSettled());
    assertNull(observedRightAfterPause.get(), "pauseSession()이 호출되지 않아야 한다");
    assertTrue(Collections.disjoint(savedRows.stream().map(SavedRow::pauseSettled).toList(), List.of(Boolean.FALSE)),
        "일시정지 없이 저장된 행에 false가 섞이면 안 된다: " + savedRows);
  }
}
