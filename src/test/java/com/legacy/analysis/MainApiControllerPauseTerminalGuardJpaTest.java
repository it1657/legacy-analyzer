package com.legacy.analysis;

import com.legacy.auth.Role;
import com.legacy.auth.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * TASK-004 (work-order 2026-10-resume-consistency-and-local-guard v1, REQ-001 ②) —
 * bug-suspects ⑧ 재현: <b>완료된 분석에 일시정지를 걸면 DB 행이 더럽혀지던 문제</b>를
 * H2 + 실제 {@code AnalysisSessionManager}/{@code SessionRepository}/{@code AnalysisHistoryRepository}로
 * 확인한다.
 *
 * <p>목 테스트는 "메모리 객체를 안 건드린다"까지만 보인다. 실제로 지켜야 하는 것은 DB 행
 * ({@code analysis_sessions.pause_settled}가 NULL → FALSE로 덮이면 목록이 영구히 "멈추는 중"으로
 * 보인다)이므로 {@code JdbcTemplate}로 직접 읽어 호출 전후를 대조한다.
 *
 * <p>{@code currentPhase}는 {@code @Transient}라 {@code analysis_sessions}에 컬럼이 없다(C11) —
 * DB 단언은 영속 컬럼만 보고, phase는 메모리 세션에서 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("h2")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:pauseTerminalGuardJpaTest;DB_CLOSE_DELAY=-1;MODE=MySQL",
    "spring.datasource.driverClassName=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=update",
    "spring.jpa.show-sql=true"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MainApiControllerPauseTerminalGuardJpaTest {

  @SpringBootConfiguration
  @AutoConfigurationPackage
  static class TestConfig {
  }

  private static final Long USER_SEQ = 40L;
  private static final String USERNAME = "jhjung";

  @Autowired SessionRepository sessionRepository;
  @Autowired AnalysisHistoryRepository analysisHistoryRepository;
  @Autowired JdbcTemplate jdbcTemplate;

  private AnalysisSessionManager sessionManager;
  private MainApiController controller;
  private Authentication ownerAuth;

  @BeforeEach
  void setUp() {
    sessionManager = new AnalysisSessionManager(new SessionConfig(), sessionRepository);
    controller = new MainApiController(
        null, null, sessionManager, null, null, null,
        analysisHistoryRepository, null, null, null, null, null, null, null, null);

    User owner = new User(USERNAME, USERNAME + "@example.com", "hash");
    owner.setSeq(USER_SEQ);
    owner.setRoles(Set.of(new Role("USER", "일반 사용자")));
    ownerAuth = new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities());
  }

  /** {@code analysis_sessions}의 영속 컬럼만 SQL로 직접 읽는다(C11 — current_phase 컬럼은 없다). */
  private Map<String, Object> sessionRow(String sid) {
    return new LinkedHashMap<>(jdbcTemplate.queryForMap(
        "SELECT status, pause_settled, paused_at, resumed_at, pending_file_paths_json"
            + " FROM analysis_sessions WHERE session_id = ?", sid));
  }

  private Map<String, Object> historyRow(String sid) {
    return new LinkedHashMap<>(jdbcTemplate.queryForMap(
        "SELECT status, completed_at FROM analysis_history WHERE session_id = ?", sid));
  }

  /** 세션 행을 DB에 직접 써 넣는다 — pause_settled는 명시하지 않아 옛 행처럼 NULL로 남는다. */
  private void seedSession(String sid, String status) {
    SessionState s = new SessionState(sid, "/src/" + sid, "/out");
    s.setUsername(USERNAME);
    s.setUserId(USER_SEQ);
    s.setStatus(status);
    s.setPendingFilePaths(List.of("/src/" + sid + "/A.java"));
    sessionRepository.save(s);
  }

  private void seedHistory(String sid, String status) {
    AnalysisHistory h = new AnalysisHistory(USER_SEQ, sid, "/src/" + sid, "/out");
    h.setStatus(status);
    analysisHistoryRepository.save(h);
  }

  // ────────────────────────────────────────────────────────────────────────────

  /**
   * bug-suspects ⑧ — {@code status=COMPLETED}, {@code pause_settled=NULL}인 세션 행 + COMPLETED 이력에
   * 일시정지를 걸면 거부되고 두 행이 그대로 남는다. (종전에는 {@code status}가 PAUSED로 덮이고
   * {@code pause_settled}에 FALSE가 쓰여 목록이 영구히 "멈추는 중"으로 보였다.)
   */
  @Test
  void COMPLETED_세션에_일시정지를_걸면_거부되고_DB_행이_그대로_남는다() {
    String sid = "jpa-completed";
    seedSession(sid, "COMPLETED");
    seedHistory(sid, "COMPLETED");

    // 전제 확인 — 옛 행처럼 pause_settled가 NULL이다.
    Map<String, Object> before = sessionRow(sid);
    Map<String, Object> historyBefore = historyRow(sid);
    System.out.println("[JPA ⑧] 호출 전  session=" + before);
    System.out.println("[JPA ⑧] 호출 전  history=" + historyBefore);
    assertNull(before.get("PAUSE_SETTLED"), "픽스처 전제: pause_settled가 NULL이어야 한다(옛 행 모양)");
    assertEquals("COMPLETED", before.get("STATUS"));

    Map<String, Object> response = controller.pauseSession(Map.of("sessionId", sid), ownerAuth);
    System.out.println("[JPA ⑧] response=" + response);

    Map<String, Object> after = sessionRow(sid);
    Map<String, Object> historyAfter = historyRow(sid);
    System.out.println("[JPA ⑧] 호출 후  session=" + after);
    System.out.println("[JPA ⑧] 호출 후  history=" + historyAfter);

    assertEquals(false, response.get("success"));
    assertEquals("SESSION_TERMINAL", response.get("reason"));
    assertEquals("이미 끝난 분석은 일시정지할 수 없습니다.", response.get("message"));

    assertEquals("COMPLETED", after.get("STATUS"), "세션 status가 PAUSED로 덮였다");
    assertNull(after.get("PAUSE_SETTLED"), "pause_settled가 NULL에서 FALSE로 덮였다(바로 이 결함)");
    assertEquals(before, after, "세션 행이 호출 전후로 완전히 같아야 한다");
    assertEquals("COMPLETED", historyAfter.get("STATUS"));
    assertEquals(historyBefore, historyAfter, "이력 행이 호출 전후로 완전히 같아야 한다");
  }

  /**
   * 재시작 후 모양 — 세션 행은 {@code status=IN_PROGRESS}로 되살아났지만 이력은 COMPLETED다.
   * 세션 판정만으로는 못 걸러지므로 이력까지 봐야 거부된다.
   */
  @Test
  void 세션은_IN_PROGRESS인데_이력이_COMPLETED면_거부되고_DB_행이_그대로_남는다() {
    String sid = "jpa-restarted";
    seedSession(sid, "IN_PROGRESS");
    seedHistory(sid, "COMPLETED");

    Map<String, Object> before = sessionRow(sid);
    System.out.println("[JPA 재시작 후] 호출 전  session=" + before + ", history=" + historyRow(sid));

    Map<String, Object> response = controller.pauseSession(Map.of("sessionId", sid), ownerAuth);
    Map<String, Object> after = sessionRow(sid);
    System.out.println("[JPA 재시작 후] response=" + response);
    System.out.println("[JPA 재시작 후] 호출 후  session=" + after);

    assertEquals(false, response.get("success"));
    assertEquals("SESSION_TERMINAL", response.get("reason"));
    assertEquals(before, after, "세션 행 무변경");
    assertNull(after.get("PAUSE_SETTLED"));
    assertEquals("COMPLETED", historyRow(sid).get("STATUS"), "이력도 무변경");
  }

  /**
   * 양성 대조군 — 같은 절차에서 진행 중 세션에 일시정지를 걸면 DB 행의 {@code pause_settled}가
   * 실제로 FALSE로 바뀌고 이력이 PAUSED가 된다. 위 "무변경"이 테스트가 아무것도 하지 않아서 나온
   * 결과가 아님을 보인다.
   */
  @Test
  void 대조군_IN_PROGRESS_세션은_pause_settled가_FALSE로_저장되고_이력이_PAUSED가_된다() {
    String sid = "jpa-in-progress";
    SessionState live = sessionManager.createSession(sid, "/src/" + sid, "/out");
    live.setUsername(USERNAME);
    live.setUserId(USER_SEQ);
    live.setStatus("IN_PROGRESS");
    live.setCurrentPhase("ANALYZING");
    sessionManager.saveSessionState(live);
    seedHistory(sid, "IN_PROGRESS");

    Map<String, Object> before = sessionRow(sid);
    System.out.println("[JPA 대조군] 호출 전  session=" + before + ", history=" + historyRow(sid));
    assertNull(before.get("PAUSE_SETTLED"), "전제: 새 세션의 pause_settled는 NULL");

    Map<String, Object> response = controller.pauseSession(Map.of("sessionId", sid), ownerAuth);
    Map<String, Object> after = sessionRow(sid);
    System.out.println("[JPA 대조군] response=" + response);
    System.out.println("[JPA 대조군] 호출 후  session=" + after + ", history=" + historyRow(sid));

    assertEquals(true, response.get("success"));
    assertNull(response.get("reason"));
    assertEquals("PAUSED", after.get("STATUS"));
    assertEquals(Boolean.FALSE, after.get("PAUSE_SETTLED"),
        "진행 중 세션은 종전대로 FALSE가 DB까지 저장돼야 한다");
    assertEquals("PAUSED", historyRow(sid).get("STATUS"));
    // phase는 DB 컬럼이 없으므로(C11) 메모리 세션에서 확인한다.
    assertEquals("PAUSED", sessionManager.getSession(sid).getCurrentPhase());
  }

  /** 이미 일시정지된(확정된) 세션에 다시 일시정지를 걸어도 DB의 TRUE가 FALSE로 되돌아가지 않는다. */
  @Test
  void 이미_확정된_PAUSED_세션에_다시_일시정지를_걸어도_pause_settled_TRUE가_유지된다() {
    String sid = "jpa-already-paused";
    SessionState s = new SessionState(sid, "/src/" + sid, "/out");
    s.setUsername(USERNAME);
    s.setUserId(USER_SEQ);
    s.setStatus("PAUSED");
    s.setPauseSettled(Boolean.TRUE);
    s.setPendingFilePaths(List.of("/src/" + sid + "/A.java"));
    sessionRepository.save(s);
    seedHistory(sid, "PAUSED");

    Map<String, Object> before = sessionRow(sid);
    System.out.println("[JPA 중복] 호출 전  session=" + before);

    Map<String, Object> response = controller.pauseSession(Map.of("sessionId", sid), ownerAuth);
    Map<String, Object> after = sessionRow(sid);
    System.out.println("[JPA 중복] response=" + response);
    System.out.println("[JPA 중복] 호출 후  session=" + after);

    assertEquals(true, response.get("success"));
    assertEquals(true, response.get("alreadyPaused"));
    assertEquals(Boolean.TRUE, after.get("PAUSE_SETTLED"),
        "확정된 TRUE가 FALSE로 되돌아가면 목록이 다시 '멈추는 중'으로 보인다");
    assertEquals(before, after, "세션 행 무변경");
  }
}
