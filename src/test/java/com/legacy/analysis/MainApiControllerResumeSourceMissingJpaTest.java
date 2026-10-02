package com.legacy.analysis;

import com.legacy.auth.Role;
import com.legacy.auth.User;
import com.legacy.rag.CodeContentRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TASK-002 (work-order 2026-10-resume-consistency-and-local-guard v1, REQ-004) —
 * <b>원본 소실 세션의 재개 거부가 DB 행을 실제로 보존하는지</b>를 H2 + 실제
 * {@code AnalysisSessionManager}/{@code SessionRepository}/{@code AnalysisHistoryRepository}로 확인한다.
 *
 * <p>목 테스트({@code MainApiControllerResumeSourceMissingTest})는 "메모리 객체를 안 건드린다"까지만
 * 보일 수 있다. 실제로 지켜야 하는 것은 <b>DB에 남아 있는 두 테이블 행</b>이므로, 여기서는
 * {@code JdbcTemplate}로 {@code analysis_sessions}·{@code analysis_history}를 직접 읽어
 * 호출 전후를 대조한다.
 *
 * <p>클래스 레벨 {@code @Transactional(NOT_SUPPORTED)}: 저장소 호출마다 자체 트랜잭션으로 커밋되게 해
 * 단언이 "DB에 실제로 쓰인 값"을 읽게 한다(선례 {@code UserActivityControllerPauseSettledJpaTest}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("h2")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:resumeSourceMissingJpaTest;DB_CLOSE_DELAY=-1;MODE=MySQL",
    "spring.datasource.driverClassName=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=update",
    "spring.jpa.show-sql=true"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MainApiControllerResumeSourceMissingJpaTest {

  /**
   * 애플리케이션 클래스({@code com.legacy.core})가 이 테스트 패키지의 상위에 없어 기본 탐색이 실패하므로
   * 빈 로컬 설정을 둔다(선례 {@code UserActivityControllerPauseSettledJpaTest}).
   */
  @SpringBootConfiguration
  @AutoConfigurationPackage
  static class TestConfig {
  }

  private static final Long USER_SEQ = 20L;
  private static final String USERNAME = "jhjung";

  @Autowired SessionRepository sessionRepository;
  @Autowired AnalysisHistoryRepository analysisHistoryRepository;
  @Autowired JdbcTemplate jdbcTemplate;

  @TempDir Path dir;

  private AnalysisSessionManager sessionManager;
  private Authentication ownerAuth;

  @BeforeEach
  void setUp() {
    sessionManager = new AnalysisSessionManager(new SessionConfig(), sessionRepository);

    User owner = new User(USERNAME, USERNAME + "@example.com", "hash");
    owner.setSeq(USER_SEQ);
    owner.setRoles(Set.of(new Role("USER", "일반 사용자")));
    ownerAuth = new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities());
  }

  private MainApiController newController(ClaudeService claudeService) {
    return new MainApiController(
        claudeService, null, sessionManager, null, null, null,
        analysisHistoryRepository, null, null, null, null, null,
        mock(CodeContentRagService.class), null, null);
  }

  /** 세션 행을 DB에 직접 써 넣고(PAUSED·확정) 같은 sessionId의 PAUSED 이력 행도 만든다. */
  private void seedPausedSession(String sid, List<String> pending) {
    SessionState session = new SessionState(sid, dir.toString(), dir.resolve("out").toString());
    session.setUsername(USERNAME);
    session.setUserId(USER_SEQ);
    session.setStatus("PAUSED");
    session.setCurrentPhase("PAUSED");
    session.setPauseSettled(Boolean.TRUE);
    session.setPendingFilePaths(pending);
    sessionRepository.save(session);

    AnalysisHistory history = new AnalysisHistory(USER_SEQ, sid, dir.toString(), dir.resolve("out").toString());
    history.setStatus("PAUSED");
    analysisHistoryRepository.save(history);
  }

  /**
   * analysis_sessions 행을 SQL로 직접 읽는다(영속성 컨텍스트 캐시를 거치지 않는다).
   * {@code currentPhase}는 {@code @Transient}(메모리 전용)이라 DB 행에 없다 — 폴링용 값이므로
   * 여기서는 영속 컬럼만 본다.
   */
  private Map<String, Object> sessionRow(String sid) {
    Map<String, Object> row = jdbcTemplate.queryForMap(
        "SELECT status, pause_settled, pending_file_paths_json, resumed_at, paused_at"
            + " FROM analysis_sessions WHERE session_id = ?", sid);
    return new LinkedHashMap<>(row);
  }

  /** analysis_history 행을 SQL로 직접 읽는다. */
  private Map<String, Object> historyRow(String sid) {
    Map<String, Object> row = jdbcTemplate.queryForMap(
        "SELECT status, success_count, failure_count, completed_at"
            + " FROM analysis_history WHERE session_id = ?", sid);
    return new LinkedHashMap<>(row);
  }

  private String real(String name) throws Exception {
    return Files.createFile(dir.resolve(name)).toString();
  }

  private String absent(String name) {
    return dir.resolve(name).toString();
  }

  // ────────────────────────────────────────────────────────────────────────────

  /**
   * MISSING — 대기 3개가 <b>실제로 없는</b> 경로인 PAUSED 세션에 재개를 호출하면, 두 테이블 행이
   * 호출 전후로 한 글자도 바뀌지 않는다. (종전 코드는 0개 파일로 재개해 대기 목록을 빈 목록으로
   * 덮어쓰거나 세션·이력을 완료로 바꿨다.)
   */
  @Test
  void MISSING_재개_거부는_세션행과_이력행을_호출_전후_그대로_남긴다() {
    String sid = "jpa-missing";
    List<String> pending = List.of(absent("A.java"), absent("B.java"), absent("C.java"));
    seedPausedSession(sid, pending);

    // 전제 확인 — 대기 경로가 실제로 존재하지 않는다(파일 API로 확인, git 명령 아님).
    for (String p : pending) {
      assertFalse(Files.exists(Path.of(p)), "픽스처 전제: 대기 경로가 없어야 한다 — " + p);
    }

    Map<String, Object> sessionBefore = sessionRow(sid);
    Map<String, Object> historyBefore = historyRow(sid);
    System.out.println("[JPA MISSING] 호출 전  session=" + sessionBefore);
    System.out.println("[JPA MISSING] 호출 전  history=" + historyBefore);

    ClaudeService claudeService = mock(ClaudeService.class);
    Map<String, Object> response =
        newController(claudeService).resumeSession(Map.of("sessionId", sid), ownerAuth);
    System.out.println("[JPA MISSING] response=" + response);

    Map<String, Object> sessionAfter = sessionRow(sid);
    Map<String, Object> historyAfter = historyRow(sid);
    System.out.println("[JPA MISSING] 호출 후  session=" + sessionAfter);
    System.out.println("[JPA MISSING] 호출 후  history=" + historyAfter);

    assertEquals(false, response.get("success"));
    assertEquals("SOURCE_MISSING", response.get("reason"));
    assertEquals(3, response.get("missingCount"));

    assertEquals("PAUSED", sessionAfter.get("STATUS"));
    assertEquals(Boolean.TRUE, sessionAfter.get("PAUSE_SETTLED"));
    assertEquals(sessionBefore, sessionAfter, "세션 행이 호출 전후로 완전히 같아야 한다");
    assertEquals("PAUSED", historyAfter.get("STATUS"));
    assertEquals(historyBefore, historyAfter, "이력 행이 호출 전후로 완전히 같아야 한다");

    // 대기 목록이 살아 있는지 엔티티 층에서도 다시 확인한다(재개 가능성이 보존됐다는 의미).
    SessionState reloaded = sessionRepository.findById(sid).orElseThrow();
    assertEquals(3, reloaded.getPendingFilePaths().size(), "대기 목록 3개가 그대로 남아야 한다");
    assertEquals(pending, reloaded.getPendingFilePaths());
    assertNull(reloaded.getResumedAt(), "거부된 재개는 resumedAt을 남기지 않는다");
  }

  /**
   * 양성 대조군 — 같은 절차에서 대기 파일을 <b>실제로 만들어 두면</b> 재개가 진행돼 세션 행의
   * {@code status}가 {@code IN_PROGRESS}로 바뀐다. 즉 위 "무변경"은 테스트가 아무것도 하지 않아서
   * 나온 결과가 아니다.
   */
  @Test
  void AVAILABLE_대조군_대기파일이_실재하면_세션행_status가_IN_PROGRESS로_바뀐다() throws Exception {
    String sid = "jpa-available";
    List<String> pending = List.of(real("Live1.java"), real("Live2.java"));
    seedPausedSession(sid, pending);
    for (String p : pending) {
      assertTrue(Files.exists(Path.of(p)), "대조군 전제: 대기 경로가 실재해야 한다 — " + p);
    }

    Map<String, Object> sessionBefore = sessionRow(sid);
    System.out.println("[JPA AVAILABLE] 호출 전  session=" + sessionBefore);
    assertEquals("PAUSED", sessionBefore.get("STATUS"));

    // 재개 스레드가 세션을 저장한 시점을 붙잡기 위해 LLM 대역에서 대기한다(선례: 재개 스레드 대기).
    CountDownLatch threadStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ClaudeService claudeService = mock(ClaudeService.class);
    when(claudeService.generateSessionClaudeMd(any(), any(), anyString())).thenAnswer(inv -> {
      threadStarted.countDown();
      release.await(10, TimeUnit.SECONDS);
      return "claude-md";
    });

    Map<String, Object> response =
        newController(claudeService).resumeSession(Map.of("sessionId", sid), ownerAuth);
    System.out.println("[JPA AVAILABLE] response=" + response);
    assertEquals(true, response.get("success"));
    assertEquals(2, response.get("pendingCount"));

    assertTrue(threadStarted.await(10, TimeUnit.SECONDS), "재개 스레드가 기동해야 한다");

    // 재개 루프가 이력 행을 IN_PROGRESS로 올렸다(안전장치를 통과했다는 DB 증거).
    Map<String, Object> historyDuring = historyRow(sid);
    System.out.println("[JPA AVAILABLE] 스레드 진입 후 history=" + historyDuring);
    assertEquals("IN_PROGRESS", historyDuring.get("STATUS"),
        "원본이 있으면 재개 루프가 이력을 IN_PROGRESS로 올린다");

    // 메모리 세션은 ANALYZING/IN_PROGRESS로 전이해 있다.
    SessionState live = sessionManager.getSession(sid);
    System.out.println("[JPA AVAILABLE] 메모리 세션 status=" + live.getStatus()
        + ", phase=" + live.getCurrentPhase());
    assertEquals("IN_PROGRESS", live.getStatus());
    assertEquals("ANALYZING", live.getCurrentPhase());

    release.countDown();
  }

  /**
   * 재개 중 원본이 사라지는 경우 — 목록 화면에서 보기엔 멀쩡했는데 재개 버튼을 누르는 사이에
   * 정리기가 지운 상황이다. 판정을 저장하지 않고 재개 시점에 다시 보기 때문에(게이트1 D2) 이
   * 경우에도 거부되고 기록이 남는다.
   */
  @Test
  void 조회시점에는_있었지만_재개시점에_사라졌으면_그_시점_판정으로_거부된다() throws Exception {
    String sid = "jpa-vanished";
    String a = real("Vanish1.java");
    String b = real("Vanish2.java");
    seedPausedSession(sid, List.of(a, b));

    // 목록을 열었을 때는 있었다.
    assertEquals(SessionSourceAvailability.Status.AVAILABLE,
        SessionSourceAvailability.check(List.of(a, b)).status());

    // 재개 버튼을 누르기 직전에 정리기가 지웠다.
    Files.delete(Path.of(a));
    Files.delete(Path.of(b));

    Map<String, Object> sessionBefore = sessionRow(sid);
    Map<String, Object> response =
        newController(mock(ClaudeService.class)).resumeSession(Map.of("sessionId", sid), ownerAuth);
    Map<String, Object> sessionAfter = sessionRow(sid);
    System.out.println("[JPA VANISHED] response=" + response);
    System.out.println("[JPA VANISHED] session 전=" + sessionBefore + " / 후=" + sessionAfter);

    assertEquals(false, response.get("success"));
    assertEquals("SOURCE_MISSING", response.get("reason"));
    assertEquals(sessionBefore, sessionAfter, "세션 행 무변경");
    assertEquals("PAUSED", historyRow(sid).get("STATUS"));
  }
}
