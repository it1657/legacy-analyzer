package com.legacy.analysis;

import com.legacy.auth.Role;
import com.legacy.auth.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TASK-001 (work-order 2026-10-resume-entry-guard v3, REQ-001·REQ-002 / O40·O41) —
 * <b>이어서 분석 입구의 동시 진입(O40)과 취소된 분석 재개(O41)</b>를 고치기 전에 재현하고,
 * 고친 뒤의 기대 동작에 단언을 걸어 두는 전용 근거.
 *
 * <p><b>고치려는 결함</b>
 * <ul>
 *   <li>O40: {@code resumeSession}·{@code confirmFailover} 두 입구에 상태 검사가 없고, 공통 재개
 *       ({@code resumePendingFilesInThread})가 대기 목록을 "읽는 지점"과 "비우는 지점" 사이를 묶어
 *       두지 않는다. 그래서 두 요청이 겹치면 둘 다 비어 있지 않은 목록을 보고 둘 다 통과해,
 *       같은 파일 묶음에 재개 스레드가 두 개 뜬다.</li>
 *   <li>O41: 취소 표시는 한 번 켜지면 되돌리는 코드가 없는데도 재개 입구가 이를 보지 않는다.
 *       그래서 취소된 분석에 재개가 들어오면 상태 전환까지 간 뒤 취소 분기로 끝난다(대기 목록이
 *       빈 채로 남는다).</li>
 * </ul>
 *
 * <p><b>겹침은 확률이 아니라 순서로 강제한다</b>(설계 v3 §4). 요청 A를 "대기 목록을 읽은 직후·상태를
 * 바꾸기 전" 자리에 세워 둔 채로 요청 B를 처음부터 끝까지 실행시키고, 그 다음 A를 놓아준다.
 * 자리를 잡는 방법은 시험 전용 {@link GatedSessionState}가 {@code getPendingFilePaths()}를 재정의해
 * <b>부른 쪽이 {@code MainApiController#resumePendingFilesInThread}이고 부른 스레드가 A일 때만</b>
 * 값을 읽어 둔 뒤 멈추는 것이다 — 호출 횟수에 기대지 않으므로 {@code confirmFailover}가 같은 목록을
 * 몇 번 읽든 늘 같은 자리에 선다. 반복 실행으로 순서를 맞히는 방식은 쓰지 않는다.
 *
 * <p>단언은 전부 <b>고친 뒤 기대 동작</b>에 걸려 있다. 고치기 전 실행에서는 RED 메시지가 현재 동작을
 * 그대로 보여 준다(선례: {@code MainApiControllerStartAnalysisPathOfReproductionTest}).
 */
class MainApiControllerResumeEntryGuardTest {

  // work-order 0절 "거절 문구·구분 코드" 표 — 한 글자도 바꾸지 않는다.
  private static final String REASON_CANCELLED = "SESSION_CANCELLED";
  private static final String MESSAGE_CANCELLED =
      "취소된 분석은 이어서 분석할 수 없습니다. 처음부터 새로 분석해 주세요.";
  private static final String REASON_IN_PROGRESS = "RESUME_IN_PROGRESS";
  private static final String MESSAGE_IN_PROGRESS =
      "이미 이어서 분석이 진행 중입니다. 잠시 후 진행 상황을 확인해 주세요.";

  /** 모든 대기의 상한. 정상 경로에서 기다리는 사건은 스레드 기동 한 번뿐이라 초과는 실패로 드러난다. */
  private static final long TIMEOUT_SECONDS = 10L;

  private static final String FAILOVER_MODEL = "qwen3-32b";

  @TempDir
  Path dir;

  private ClaudeService claudeService;
  private AnalysisSessionManager sessionManager;
  private AnalysisHistoryRepository historyRepository;
  private MainApiController controller;

  /** 하네스 유효성(양성 대조)용 순서 기록 — "A 도착 &lt; B 응답 &lt; A 놓아줌"이 강제됐다는 증거. */
  private final List<String> order = Collections.synchronizedList(new ArrayList<>());

  /** {@code claudeService.setModel} 호출을 "부른 스레드 이름"으로 기록한다(요청별 부작용 귀속용). */
  private final List<String> setModelCallerThreads = Collections.synchronizedList(new ArrayList<>());

  /** 요청 스레드 집합(테스트 스레드 + 요청 A). 그 밖의 스레드는 재개 스레드로 센다. */
  private final Set<Thread> requestThreads = Collections.synchronizedSet(new HashSet<>());

  /** 재개 스레드가 첫 {@code getSession}에 도착한 집합(= 기동된 재개 스레드). */
  private final Set<Thread> resumeThreads = Collections.synchronizedSet(new LinkedHashSet<>());

  /** 재개 스레드 도착 1건 = 허가 1개. "성공 응답 수만큼 도착"을 기다리는 데 쓴다. */
  private final Semaphore resumeArrivals = new Semaphore(0);

  private String testThreadName;

  @BeforeEach
  void setUp() {
    claudeService = mock(ClaudeService.class);
    sessionManager = mock(AnalysisSessionManager.class);
    historyRepository = mock(AnalysisHistoryRepository.class);
    controller = new MainApiController(
        claudeService, null, sessionManager, null, null, null,
        historyRepository, null, null, null, null, null, null, null, null);
    requestThreads.add(Thread.currentThread());
    testThreadName = Thread.currentThread().getName();
    doAnswer(invocation -> {
      setModelCallerThreads.add(Thread.currentThread().getName());
      return null;
    }).when(claudeService).setModel(anyString(), anyString());
  }

  // ===================================================================
  // 하네스
  // ===================================================================

  /**
   * 요청 A를 "대기 목록을 읽은 직후"에 세우는 시험 전용 세션.
   *
   * <p>{@code getPendingFilePaths()}는 부른 스레드가 A이고 호출 스택에
   * {@code MainApiController#resumePendingFilesInThread} 프레임이 있을 때만 값을 읽어 둔 뒤
   * "도착"을 알리고 "놓아줌"까지 기다렸다가 <b>읽어 둔 값</b>을 돌려준다. 그 밖의 호출은 원래대로다.
   */
  private final class GatedSessionState extends SessionState {
    private final CountDownLatch arrived = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final AtomicBoolean gatedOnce = new AtomicBoolean(false);
    private volatile Thread gatedThread;

    private GatedSessionState(String sessionId, String sourcePath, String outputPath) {
      super(sessionId, sourcePath, outputPath);
    }

    private void gateOn(Thread thread) {
      this.gatedThread = thread;
    }

    @Override
    public List<String> getPendingFilePaths() {
      List<String> value = super.getPendingFilePaths();
      if (Thread.currentThread() != gatedThread || !calledFromCommonResume()) {
        return value;
      }
      if (!gatedOnce.compareAndSet(false, true)) {
        return value;
      }
      order.add("A 도착");
      arrived.countDown();
      try {
        if (!released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          throw new IllegalStateException("요청 A 놓아줌 대기 " + TIMEOUT_SECONDS + "초 초과");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("요청 A 대기가 중단됐다", e);
      }
      return value;
    }

    private boolean awaitArrival() throws InterruptedException {
      return arrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void release() {
      released.countDown();
    }
  }

  /** 호출 스택에 공통 재개 프레임이 있는지 — 호출 횟수에 기대지 않고 자리를 잡는 수단. */
  private static boolean calledFromCommonResume() {
    return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
        MainApiController.class.getName().equals(frame.getClassName())
            && "resumePendingFilesInThread".equals(frame.getMethodName())));
  }

  /** 요청 스레드에는 같은 세션을, 재개 스레드에는 도착을 기록하고 {@code null}을 돌려주는 mock. */
  private void stubGetSession(SessionState session) {
    when(sessionManager.getSession("sid")).thenAnswer(invocation -> {
      Thread current = Thread.currentThread();
      if (requestThreads.contains(current)) {
        return session;
      }
      // 재개 로직의 첫 문장이 getSession이고 세션에 쓰는 지점은 모두 그 뒤에 있으므로,
      // null을 받은 재개 스레드는 세션에 아무것도 쓰지 않고 끝난다(K2 ResumeThreadGate 선례).
      resumeThreads.add(current);
      resumeArrivals.release();
      return null;
    });
  }

  @FunctionalInterface
  private interface Request {
    Map<String, Object> call() throws Exception;
  }

  /** 겹침 강제 결과 — A·B 응답과 "B 호출 전후" 세션 스냅샷. */
  private record Overlap(Map<String, Object> aResponse, Map<String, Object> bResponse,
      String snapshotBeforeB, String snapshotAfterB) {
  }

  /**
   * 요청 A를 세워 둔 채로 요청 B를 처음부터 끝까지 실행시키고, 그 다음 A를 놓아준다.
   * A가 자리에 서지 못하면(대기 상한 초과) 결과 판정 전에 실패한다 — 하네스 유효성 단언.
   */
  private Overlap forceOverlap(GatedSessionState session, Request a, Request b) throws Exception {
    AtomicReference<Map<String, Object>> aResponse = new AtomicReference<>();
    AtomicReference<Throwable> aFailure = new AtomicReference<>();
    Thread threadA = new Thread(() -> {
      try {
        aResponse.set(a.call());
      } catch (Throwable t) {
        aFailure.set(t);
      }
    }, "요청A");
    requestThreads.add(threadA);
    session.gateOn(threadA);
    threadA.start();

    assertTrue(session.awaitArrival(),
        "요청 A가 공통 재개의 대기 목록 읽기 지점에 서지 않았다(대기 상한 " + TIMEOUT_SECONDS + "초 초과)");

    String before = snapshot(session);
    Map<String, Object> bResponse = b.call();
    String after = snapshot(session);
    order.add("B 응답");

    session.release();
    order.add("A 놓아줌");
    threadA.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    assertFalse(threadA.isAlive(), "놓아준 요청 A가 끝나지 않았다(대기 상한 초과 또는 생존)");
    if (aFailure.get() != null) {
      throw new IllegalStateException("요청 A가 예외로 끝났다: " + aFailure.get(), aFailure.get());
    }
    return new Overlap(aResponse.get(), bResponse, before, after);
  }

  /**
   * 거절된 요청이 세션을 1비트도 바꾸지 않았는지 보는 스냅샷 10항목.
   * (status · currentPhase · 대기 목록 JSON · 처리 수 · 성공/건너뜀/실패 수 · 재개 시각 ·
   * 일시정지 시각 · 컨펌 시각 · 취소 표시 · 일시정지 확정 표시)
   */
  private static String snapshot(SessionState s) {
    return "status=" + s.getStatus()
        + " | currentPhase=" + s.getCurrentPhase()
        + " | pendingJson=" + s.getPendingFilePathsJson()
        + " | processed=" + s.getProcessedFiles()
        + " | 성공/건너뜀/실패=" + s.getStatistics().getSuccessCount()
        + "/" + s.getStatistics().getSkipCount()
        + "/" + s.getStatistics().getFailureCount()
        + " | resumedAt=" + s.getResumedAt()
        + " | pausedAt=" + s.getPausedAt()
        + " | failoverConfirmedAt=" + s.getFailoverConfirmedAt()
        + " | isCancelled=" + s.isCancelled()
        + " | pauseSettled=" + s.getPauseSettled();
  }

  private Authentication authAs(String loginId) {
    User user = new User(loginId, loginId + "@example.com", "hash");
    user.setSeq(1L);
    user.setRoles(Set.of(new Role("USER", "일반 사용자")));
    return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
  }

  private String realFile(String name) throws Exception {
    return Files.createFile(dir.resolve(name)).toString();
  }

  private void fillFixture(SessionState session, String phase, List<String> pending) {
    session.setUsername("owner");
    session.setTotalFiles(2);
    session.setProcessedFiles(0);
    session.setStatus("PAUSED");
    session.setCurrentPhase(phase);
    session.setPauseSettled(Boolean.TRUE);
    session.setPausedAt(LocalDateTime.of(2026, 10, 21, 9, 0));
    session.setPendingFilePaths(pending);
  }

  private GatedSessionState gatedSession(String phase) throws Exception {
    GatedSessionState session =
        new GatedSessionState("sid", dir.toString(), dir.resolve("out").toString());
    fillFixture(session, phase, List.of(realFile("A.java")));
    return session;
  }

  private SessionState plainSession(String phase, String fileName) throws Exception {
    SessionState session = new SessionState("sid", dir.toString(), dir.resolve("out").toString());
    fillFixture(session, phase, List.of(realFile(fileName)));
    return session;
  }

  private Map<String, Object> resume() {
    return controller.resumeSession(Map.of("sessionId", "sid"), authAs("owner"));
  }

  private Map<String, Object> confirm() {
    return controller.confirmFailover(Map.of("sessionId", "sid"), authAs("owner"));
  }

  private int successCount(Map<String, Object> first, Map<String, Object> second) {
    int count = 0;
    if (Boolean.TRUE.equals(first.get("success"))) {
      count++;
    }
    if (Boolean.TRUE.equals(second.get("success"))) {
      count++;
    }
    return count;
  }

  private void assertRejectedAsInProgress(Map<String, Object> response, String label) {
    assertEquals(false, response.get("success"), label + " 응답=" + response);
    assertEquals(REASON_IN_PROGRESS, response.get("reason"), label + " 응답=" + response);
    assertEquals(MESSAGE_IN_PROGRESS, response.get("message"), label + " 응답=" + response);
  }

  private void assertRejectedAsCancelled(Map<String, Object> response, String label) {
    assertEquals(false, response.get("success"), label + " 응답=" + response);
    assertEquals(REASON_CANCELLED, response.get("reason"), label + " 응답=" + response);
    assertEquals(MESSAGE_CANCELLED, response.get("message"), label + " 응답=" + response);
  }

  /**
   * 성공 응답 수만큼의 재개 스레드가 첫 {@code getSession}에 도착하기를 기다린다.
   *
   * <p>재개 스레드는 성공 경로의 마지막 문장에서만 기동되므로(상태 전환 직후) "성공 응답 1건 =
   * 스레드 1개"가 구조적으로 성립한다. 여기서 성공 수만큼 도착을 기다려 두면, 그 뒤의
   * {@code resumeThreads.size()} 비교는 기다림 없이도 결정적이다 — 추가 도착은 추가 성공을
   * 뜻하는데 그건 성공 수 단언이 이미 막는다. 성공이 0건이면 기다릴 것이 없다.
   */
  private void awaitResumeThreadsFor(int successes) throws Exception {
    assertTrue(resumeArrivals.tryAcquire(successes, TIMEOUT_SECONDS, TimeUnit.SECONDS),
        "성공 응답 " + successes + "건에 대응하는 재개 스레드가 모두 도착하지 않았다(대기 상한 초과): "
            + resumeThreads);
  }

  private long setModelCallsFrom(String threadName) {
    return setModelCallerThreads.stream().filter(threadName::equals).count();
  }

  // ===================================================================
  // C1~C4 — O40 동시 진입(겹침을 순서로 강제)
  // ===================================================================

  @Test
  void C1_이어서와_이어서가_겹치면_하나만_성공하고_나머지는_이미_진행중으로_거절된다() throws Exception {
    GatedSessionState session = gatedSession("PAUSED");
    stubGetSession(session);

    Overlap result = forceOverlap(session, this::resume, this::resume);
    int successes = successCount(result.aResponse(), result.bResponse());
    awaitResumeThreadsFor(successes);

    System.out.println("[C1 순서 기록] " + order);
    System.out.println("[C1 A 응답] " + result.aResponse());
    System.out.println("[C1 B 응답] " + result.bResponse());
    System.out.println("[C1 성공 수] " + successes
        + " / 기동된 재개 스레드 " + resumeThreads.size() + "개 " + resumeThreads);
    assertEquals(List.of("A 도착", "B 응답", "A 놓아줌"), order,
        "겹침이 순서로 강제되지 않았다(하네스 유효성)");
    assertEquals(1, successes,
        "겹친 두 요청 중 성공은 1건이어야 한다. A=" + result.aResponse() + " / B=" + result.bResponse());
    assertEquals(true, result.aResponse().get("success"), "먼저 들어온 A가 성공해야 한다");
    assertRejectedAsInProgress(result.bResponse(), "C1 B");
    assertEquals(1, resumeThreads.size(), "기동된 재개 스레드 수가 1이 아니다: " + resumeThreads);
    assertEquals(result.snapshotBeforeB(), result.snapshotAfterB(), "거절된 B가 세션을 바꿨다");
    verify(sessionManager, never()).saveSessionState(any());
  }

  @Test
  void C2_컨펌과_컨펌이_겹치면_하나만_성공하고_거절된_쪽은_모델전환도_하지_않는다() throws Exception {
    GatedSessionState session = gatedSession(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM);
    session.setFailoverModelKey(FAILOVER_MODEL);
    stubGetSession(session);

    Overlap result = forceOverlap(session, this::confirm, this::confirm);
    int successes = successCount(result.aResponse(), result.bResponse());
    awaitResumeThreadsFor(successes);

    System.out.println("[C2 순서 기록] " + order);
    System.out.println("[C2 setModel 호출 스레드] " + setModelCallerThreads);
    System.out.println("[C2 A 응답] " + result.aResponse());
    System.out.println("[C2 B 응답] " + result.bResponse());
    System.out.println("[C2 성공 수] " + successes
        + " / 기동된 재개 스레드 " + resumeThreads.size() + "개 " + resumeThreads);
    assertEquals(List.of("A 도착", "B 응답", "A 놓아줌"), order,
        "겹침이 순서로 강제되지 않았다(하네스 유효성)");
    assertEquals(1, successes,
        "겹친 두 컨펌 중 성공은 1건이어야 한다. A=" + result.aResponse() + " / B=" + result.bResponse());
    assertRejectedAsInProgress(result.bResponse(), "C2 B");
    assertEquals(1, resumeThreads.size(), "기동된 재개 스레드 수가 1이 아니다: " + resumeThreads);
    // 음성 결과(B 0회)의 양성 대조는 같은 실행의 A 1회다.
    assertEquals(0, setModelCallsFrom(testThreadName),
        "거절된 B가 모델 전환을 했다: " + setModelCallerThreads);
    assertEquals(1, setModelCallsFrom("요청A"),
        "성공한 A의 모델 전환이 1회가 아니다(대조군): " + setModelCallerThreads);
  }

  @Test
  void C3_이어서가_선점한_동안_들어온_컨펌은_이미_진행중으로_거절된다() throws Exception {
    GatedSessionState session = gatedSession(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM);
    session.setFailoverModelKey(FAILOVER_MODEL);
    stubGetSession(session);

    Overlap result = forceOverlap(session, this::resume, this::confirm);
    int successes = successCount(result.aResponse(), result.bResponse());
    awaitResumeThreadsFor(successes);

    System.out.println("[C3 순서 기록] " + order);
    System.out.println("[C3 A 응답] " + result.aResponse());
    System.out.println("[C3 B 응답] " + result.bResponse());
    System.out.println("[C3 성공 수] " + successes
        + " / 기동된 재개 스레드 " + resumeThreads.size() + "개 " + resumeThreads);
    assertEquals(List.of("A 도착", "B 응답", "A 놓아줌"), order,
        "겹침이 순서로 강제되지 않았다(하네스 유효성)");
    assertEquals(1, successes,
        "겹친 이어서+컨펌 중 성공은 1건이어야 한다. A=" + result.aResponse() + " / B=" + result.bResponse());
    assertRejectedAsInProgress(result.bResponse(), "C3 B");
    assertEquals(1, resumeThreads.size(), "기동된 재개 스레드 수가 1이 아니다: " + resumeThreads);
  }

  @Test
  void C4_컨펌이_선점한_동안_들어온_이어서는_이미_진행중으로_거절된다() throws Exception {
    GatedSessionState session = gatedSession(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM);
    session.setFailoverModelKey(FAILOVER_MODEL);
    stubGetSession(session);

    Overlap result = forceOverlap(session, this::confirm, this::resume);
    int successes = successCount(result.aResponse(), result.bResponse());
    awaitResumeThreadsFor(successes);

    System.out.println("[C4 순서 기록] " + order);
    System.out.println("[C4 A 응답] " + result.aResponse());
    System.out.println("[C4 B 응답] " + result.bResponse());
    System.out.println("[C4 성공 수] " + successes
        + " / 기동된 재개 스레드 " + resumeThreads.size() + "개 " + resumeThreads);
    assertEquals(List.of("A 도착", "B 응답", "A 놓아줌"), order,
        "겹침이 순서로 강제되지 않았다(하네스 유효성)");
    assertEquals(1, successes,
        "겹친 컨펌+이어서 중 성공은 1건이어야 한다. A=" + result.aResponse() + " / B=" + result.bResponse());
    assertRejectedAsInProgress(result.bResponse(), "C4 B");
    assertEquals(1, resumeThreads.size(), "기동된 재개 스레드 수가 1이 아니다: " + resumeThreads);
  }

  // ===================================================================
  // C5 — 순차 두 번(겹침 없음)
  // ===================================================================

  @Test
  void C5_이어서가_끝난_뒤_다시_이어서를_누르면_이미_진행중으로_거절된다() throws Exception {
    SessionState session = plainSession("PAUSED", "A.java");
    stubGetSession(session);

    Map<String, Object> first = resume();
    Map<String, Object> second = resume();
    int successes = successCount(first, second);
    awaitResumeThreadsFor(successes);

    System.out.println("[C5 첫 응답] " + first);
    System.out.println("[C5 둘째 응답] " + second);
    System.out.println("[C5 성공 수] " + successes
        + " / 기동된 재개 스레드 " + resumeThreads.size() + "개 " + resumeThreads);
    assertEquals(true, first.get("success"), "첫 재개는 성공해야 한다: " + first);
    assertRejectedAsInProgress(second, "C5 둘째");
    assertEquals(1, resumeThreads.size(), "기동된 재개 스레드 수가 1이 아니다: " + resumeThreads);
  }

  // ===================================================================
  // C6~C8 — O41 취소된 분석 재개
  // ===================================================================

  @Test
  void C6_취소된_세션에_이어서를_누르면_취소된_분석으로_거절되고_세션은_그대로다() throws Exception {
    SessionState session = plainSession("PAUSED", "A.java");
    session.cancel();
    session.setStatus("CANCELLED");
    stubGetSession(session);

    String before = snapshot(session);
    Map<String, Object> response = resume();
    awaitResumeThreadsFor(successCount(response, Map.of()));
    String after = snapshot(session);

    System.out.println("[C6 응답] " + response);
    System.out.println("[C6 스냅샷 전] " + before);
    System.out.println("[C6 스냅샷 후] " + after);
    System.out.println("[C6 기동된 재개 스레드] " + resumeThreads.size() + "개 " + resumeThreads);
    assertRejectedAsCancelled(response, "C6");
    assertEquals(before, after, "거절된 재개가 세션을 바꿨다");
    assertTrue(resumeThreads.isEmpty(), "거절됐는데 재개 스레드가 기동됐다: " + resumeThreads);
    verify(sessionManager, never()).saveSessionState(any());
  }

  @Test
  void C7_취소표시가_켜진_세션의_컨펌은_취소된_분석으로_거절되고_모델전환도_없다() throws Exception {
    SessionState session = plainSession(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM, "A.java");
    session.setFailoverModelKey(FAILOVER_MODEL);
    session.cancel();
    stubGetSession(session);

    String before = snapshot(session);
    Map<String, Object> response = confirm();
    awaitResumeThreadsFor(successCount(response, Map.of()));
    String after = snapshot(session);

    System.out.println("[C7 응답] " + response);
    System.out.println("[C7 setModel 호출 스레드] " + setModelCallerThreads);
    System.out.println("[C7 스냅샷 전] " + before);
    System.out.println("[C7 스냅샷 후] " + after);
    System.out.println("[C7 기동된 재개 스레드] " + resumeThreads.size() + "개 " + resumeThreads);
    assertRejectedAsCancelled(response, "C7");
    verify(claudeService, never()).setModel(anyString(), anyString());
    assertEquals(before, after, "거절된 컨펌이 세션을 바꿨다");
    assertTrue(resumeThreads.isEmpty(), "거절됐는데 재개 스레드가 기동됐다: " + resumeThreads);
    verify(sessionManager, never()).saveSessionState(any());
  }

  @Test
  void C8_취소표시가_켜진_옛_일시정지_기록에_이어서를_누르면_취소된_분석으로_거절된다() throws Exception {
    SessionState session = plainSession("PAUSED", "A.java");
    session.setStatus("PAUSED");
    session.cancel();
    stubGetSession(session);

    String before = snapshot(session);
    Map<String, Object> response = resume();
    awaitResumeThreadsFor(successCount(response, Map.of()));
    String after = snapshot(session);

    System.out.println("[C8 응답] " + response);
    System.out.println("[C8 스냅샷 전] " + before);
    System.out.println("[C8 스냅샷 후] " + after);
    System.out.println("[C8 기동된 재개 스레드] " + resumeThreads.size() + "개 " + resumeThreads);
    assertRejectedAsCancelled(response, "C8");
    assertEquals(before, after, "거절된 재개가 세션을 바꿨다");
    assertTrue(resumeThreads.isEmpty(), "거절됐는데 재개 스레드가 기동됐다: " + resumeThreads);
    verify(sessionManager, never()).saveSessionState(any());
  }
  // ===================================================================
  // C9 — 점유 해제(재개 입구를 떠날 때 점유표를 비운다)
  // ===================================================================

  /** C9 전용 — 어디에도 담아 두지 않은, 그래서 {@code getSession}이 null을 주는 세션 ID. */
  private static final String C9_MISSING_SESSION_ID = "missing-sid";

  /** C9 전용 — 세션을 찾지 못했을 때의 기존 응답 문구(거절 문구 3종과는 별개의 기존 계약). */
  private static final String C9_MESSAGE_NOT_FOUND = "세션을 찾을 수 없습니다.";

  /** C9 전용 — 존재하지 않는 세션 ID로 이어서 분석 입구를 부른다. */
  private Map<String, Object> c9Resume() {
    return controller.resumeSession(
        Map.of("sessionId", C9_MISSING_SESSION_ID), authAs("owner"));
  }

  /** C9 전용 — 존재하지 않는 세션 ID로 컨펌 입구를 부른다. */
  private Map<String, Object> c9Confirm() {
    return controller.confirmFailover(
        Map.of("sessionId", C9_MISSING_SESSION_ID), authAs("owner"));
  }

  /**
   * C9 전용 — "세션을 찾을 수 없습니다."로 응답했는지 본다.
   *
   * <p>점유표를 비우지 않으면 두 번째 호출이 점유 실패로 ② 거절({@code RESUME_IN_PROGRESS})로
   * 바뀌므로, 그 경우를 따로 집어 메시지에 남긴다.
   */
  private void assertSessionNotFound(Map<String, Object> response, String label) {
    assertEquals(false, response.get("success"), label + " 응답=" + response);
    assertEquals(C9_MESSAGE_NOT_FOUND, response.get("message"), label + " 응답=" + response);
    assertFalse(REASON_IN_PROGRESS.equals(response.get("reason")),
        label + " 점유가 풀리지 않아 ② 거절로 바뀌었다: " + response);
  }

  @Test
  void C9_존재하지_않는_세션에_두_입구를_연달아_두_번씩_불러도_네_번_모두_세션을_찾을_수_없습니다로_끝난다() {
    Map<String, Object> firstResume = c9Resume();
    Map<String, Object> secondResume = c9Resume();
    Map<String, Object> firstConfirm = c9Confirm();
    Map<String, Object> secondConfirm = c9Confirm();

    System.out.println("[C9 이어서 첫 응답] " + firstResume);
    System.out.println("[C9 이어서 둘째 응답] " + secondResume);
    System.out.println("[C9 컨펌 첫 응답] " + firstConfirm);
    System.out.println("[C9 컨펌 둘째 응답] " + secondConfirm);

    assertSessionNotFound(firstResume, "C9 이어서 첫");
    assertSessionNotFound(secondResume, "C9 이어서 둘째");
    assertSessionNotFound(firstConfirm, "C9 컨펌 첫");
    assertSessionNotFound(secondConfirm, "C9 컨펌 둘째");
  }

  // ===================================================================
  // C10 — 점유 해제(성공 경로로 입구를 떠난 뒤에도 점유표가 비어 있다)
  // ===================================================================

  /**
   * C10 전용 — 4·6단계 실패 메시지에 붙이는 판정.
   *
   * <p>가림의 세 원천(① 1단계 뒤 {@code currentPhase}가 {@code ANALYZING}이라 "진행 중" 규칙이
   * 먼저 거절함 ② 1단계가 대기 목록을 비움 ③ 컨펌은 컨펌 대기 상태여야 함)을 2·3·5단계에서 모두
   * 막았다. 그래서 이 자리의 {@code RESUME_IN_PROGRESS}는 점유 누수 말고는 원인이 없다.
   */
  private static final String C10_LEAK_VERDICT =
      "바로 앞 전제 단언이 GREEN이므로 이 상태에서 RESUME_IN_PROGRESS는 점유 누수뿐이다";

  /**
   * C10 전용 — 루프의 일시정지 분기가 했을 일(대기 목록 다시 쓰기 · 일시정지 확정 표시)을 시험이
   * 대신 한다. 재개 스레드는 mock이 {@code null}을 줘서 아무 일도 안 하고 끝나기 때문이다.
   */
  private void c10SettlePause(SessionState session, List<String> pending) {
    session.setPendingFilePaths(pending);
    session.setPauseSettled(Boolean.TRUE);
  }

  /** C10 전용 — 5·6단계의 컨펌 대기 상태(모델 키·대기 목록 포함)로 맞춘다. */
  private void c10ArmFailoverConfirm(SessionState session, List<String> pending) {
    session.setCurrentPhase(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM);
    session.setStatus(SessionState.STATUS_AWAITING_FAILOVER_CONFIRM);
    session.setFailoverModelKey(FAILOVER_MODEL);
    session.setPendingFilePaths(pending);
  }

  /**
   * C10 전용 — 가림 방지 전제 단언. 거절 규칙(취소 · 이미 진행 중)과 "재개할 파일이 없습니다"가
   * 걸릴 수 없는 상태인지를 판정 단언보다 <b>먼저</b> 확인한다. 점유표 필드는 들여다보지 않고
   * 입구의 응답으로만 판정한다(C9와 같은 행동 관찰 방식).
   */
  private void c10AssertNotMasked(SessionState session, String expectedPhase, String label) {
    assertEquals(expectedPhase, session.getCurrentPhase(),
        label + " 전제: currentPhase. 스냅샷=" + snapshot(session));
    assertFalse(session.isCancelled(),
        label + " 전제: 취소 표시가 켜져 있다. 스냅샷=" + snapshot(session));
    assertFalse("CANCELLED".equals(session.getStatus()),
        label + " 전제: status가 CANCELLED다. 스냅샷=" + snapshot(session));
    List<String> pending = session.getPendingFilePaths();
    assertFalse(pending.isEmpty(),
        label + " 전제: 대기 목록이 비어 있다. 스냅샷=" + snapshot(session));
    for (String path : pending) {
      assertTrue(Files.exists(Path.of(path)), label + " 전제: 대기 파일이 서버에 없다: " + path);
    }
  }

  @Test
  void C10_성공한_이어서와_컨펌이_입구를_떠난_뒤에도_점유가_풀려_같은_세션에_다시_성공한다() throws Exception {
    SessionState session = plainSession("PAUSED", "C10.java");
    List<String> c10Pending = List.copyOf(session.getPendingFilePaths());
    stubGetSession(session);

    // 1단계 — 일시정지 세션에 이어서 분석. 성공 경로로 입구를 떠난다.
    Map<String, Object> firstResume = resume();
    System.out.println("[C10 1단계 이어서 응답] " + firstResume);
    assertEquals(true, firstResume.get("success"), "C10 1단계 이어서가 성공해야 한다: " + firstResume);
    awaitResumeThreadsFor(1);

    // 2단계 — 실제 일시정지 입구를 부르고, 루프가 했을 대기 목록 확정을 시험이 대신 한다.
    Map<String, Object> pause =
        controller.pauseSession(Map.of("sessionId", "sid"), authAs("owner"));
    System.out.println("[C10 2단계 일시정지 응답] " + pause);
    assertEquals(true, pause.get("success"), "C10 2단계 일시정지가 성공해야 한다: " + pause);
    c10SettlePause(session, c10Pending);

    // 3단계 — 가림 방지 전제.
    c10AssertNotMasked(session, "PAUSED", "C10 3단계");
    System.out.println("[C10 3단계 전제 스냅샷] " + snapshot(session));

    // 4단계 — 이어서 분석을 다시. 1단계 점유가 남아 있으면 여기서 ② 거절로 바뀐다.
    Map<String, Object> secondResume = resume();
    System.out.println("[C10 4단계 이어서 응답] " + secondResume);
    assertEquals(true, secondResume.get("success"),
        "C10 4단계 이어서가 성공해야 한다 — " + C10_LEAK_VERDICT + ". 응답=" + secondResume);
    awaitResumeThreadsFor(1);

    // 5단계 — 컨펌 대기로 맞춘 뒤 컨펌. 성공 경로로 컨펌 입구를 떠난다.
    c10ArmFailoverConfirm(session, c10Pending);
    c10AssertNotMasked(session, SessionState.STATUS_AWAITING_FAILOVER_CONFIRM, "C10 5단계");
    Map<String, Object> firstConfirm = confirm();
    System.out.println("[C10 5단계 컨펌 응답] " + firstConfirm);
    assertEquals(true, firstConfirm.get("success"), "C10 5단계 컨펌이 성공해야 한다: " + firstConfirm);
    awaitResumeThreadsFor(1);

    // 6단계 — 같은 맞춤을 한 번 더. 5단계 점유가 남아 있으면 여기서 ② 거절로 바뀐다.
    c10ArmFailoverConfirm(session, c10Pending);
    c10AssertNotMasked(session, SessionState.STATUS_AWAITING_FAILOVER_CONFIRM, "C10 6단계");
    Map<String, Object> secondConfirm = confirm();
    System.out.println("[C10 6단계 컨펌 응답] " + secondConfirm);
    assertEquals(true, secondConfirm.get("success"),
        "C10 6단계 컨펌이 성공해야 한다 — " + C10_LEAK_VERDICT + ". 응답=" + secondConfirm);
    awaitResumeThreadsFor(1);

    System.out.println("[C10 기동된 재개 스레드] " + resumeThreads.size() + "개 " + resumeThreads);
    assertEquals(4, resumeThreads.size(),
        "성공 4건(1·4·5·6단계)에 대응하는 재개 스레드 수가 4가 아니다: " + resumeThreads);
  }
}
