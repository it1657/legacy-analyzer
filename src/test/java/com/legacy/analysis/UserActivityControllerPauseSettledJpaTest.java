package com.legacy.analysis;

import com.legacy.auth.Role;
import com.legacy.auth.User;
import com.legacy.core.PresentationGeneratorService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TASK-002 (work-order 2026-09-remaining-ux-fixes v1, 설계 02-design-v1 §4.2 / §13 P3·P5) —
 * <b>{@code pauseSettled}를 실제 H2 + 실제 {@code SessionRepository}로 저장·조회했을 때의 계약</b>.
 *
 * <ul>
 *   <li><b>P3</b>: {@code ddl-auto=update}로 컬럼이 추가되면 <b>기존 행은 NULL</b>이고, wrapper 타입이라 읽기에 실패하지 않으며
 *       확정(true)으로 해석된다. 재현 방법: {@code pausesettled/legacy-analysis-sessions.sql}이 Hibernate보다 먼저
 *       ({@code spring.jpa.defer-datasource-initialization=false}) {@code pause_settled} <b>없는</b> 테이블과 기존 행을 만들고,
 *       Hibernate가 그 위에 컬럼만 ALTER로 얹는다 — 배포 시 실제로 일어나는 순서 그대로다.</li>
 *   <li><b>P5</b>: 목록 API 1회 호출당 세션 조회 SQL이 <b>정확히 1문장</b>이다(PAUSED 행 수와 무관). Hibernate
 *       {@link Statistics#getPrepareStatementCount()}로 센다. PAUSED 행이 없으면 0문장.</li>
 *   <li><b>P4(a)(b) 실 저장소판</b>: 실제 {@code AnalysisSessionManager} + H2로 {@code pauseSession()} → 목록 API false,
 *       확정 저장 → 목록 API true. (1회차·2회차를 실제 루프로 도는 것은 {@code MainApiControllerPauseSettledContractTest}.)</li>
 *   <li><b>C1/C2 (TASK-002C, work-order v5 §0.22 / DoD 2·3)</b>: 픽스처의 옛 PAUSED 행은 실배포처럼
 *       {@code force_active}/{@code generate_readme}도 <b>NULL</b>이다. C1은 컨트롤러의 장애 격리 catch를 <b>우회</b>해
 *       리포지토리 층에서 직접 "예외 없이 로드 + NULL은 Java 초기값(generateReadme=true / forceActive=false)으로 읽힌다"를
 *       단언하고(매핑을 primitive로 되돌리면 이 단언이 RED), C2는 목록 API 층에서 200 + {@code pauseSettled=true}를 단언한다.</li>
 * </ul>
 *
 * <p>클래스 레벨 {@code @Transactional(NOT_SUPPORTED)}: 저장소 호출마다 자체 트랜잭션으로 커밋되게 해 목록 API가 항상
 * "DB에 실제로 쓰인 값"을 읽게 한다(테스트 트랜잭션 안의 영속성 컨텍스트 캐시를 보지 않는다).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("h2")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:pauseSettledJpaTest;DB_CLOSE_DELAY=-1;MODE=MySQL",
    "spring.datasource.driverClassName=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=update",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:pausesettled/legacy-analysis-sessions.sql",
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "spring.jpa.show-sql=true"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserActivityControllerPauseSettledJpaTest {

  /**
   * 애플리케이션 클래스({@code com.legacy.core})가 이 테스트 패키지의 상위에 있지 않아 기본 탐색이 실패하므로
   * 빈 로컬 설정을 둔다. {@code @AutoConfigurationPackage}로 이 패키지({@code com.legacy.analysis})를 엔티티/저장소
   * 스캔 기준 패키지로 등록한다(외부 패키지와의 엔티티 관계 없음).
   */
  @SpringBootConfiguration
  @AutoConfigurationPackage
  static class TestConfig {
  }

  private static final Long USER_SEQ = 10L;
  private static final String USERNAME = "jhjung";

  @Autowired SessionRepository sessionRepository;
  @Autowired EntityManagerFactory entityManagerFactory;
  @Autowired JdbcTemplate jdbcTemplate;

  private AnalysisHistoryRepository analysisHistoryRepository;
  private UserActivityController userActivityController;
  private Authentication ownerAuth;
  private Statistics statistics;

  @BeforeEach
  void setUp() {
    analysisHistoryRepository = mock(AnalysisHistoryRepository.class);
    userActivityController = new UserActivityController(
        analysisHistoryRepository, mock(PresentationGeneratorService.class), sessionRepository);

    User owner = new User(USERNAME, USERNAME + "@example.com", "hash");
    owner.setSeq(USER_SEQ);
    owner.setRoles(Set.of(new Role("USER", "일반 사용자")));
    ownerAuth = new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities());

    statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.setStatisticsEnabled(true);
  }

  private AnalysisHistory history(long id, String sessionId, String status) {
    AnalysisHistory h = new AnalysisHistory(USER_SEQ, sessionId, "/src/" + sessionId, "/out");
    h.setId(id);
    h.setStatus(status);
    return h;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Boolean> listPauseSettledBySessionId() {
    ResponseEntity<?> response = userActivityController.getMyAnalysisHistory(ownerAuth);
    assertEquals(200, response.getStatusCode().value(), "목록 API 실패: " + response.getBody());
    Map<String, Boolean> result = new java.util.LinkedHashMap<>();
    for (Map<String, Object> row : (List<Map<String, Object>>) response.getBody()) {
      result.put((String) row.get("sessionId"), (Boolean) row.get("pauseSettled"));
    }
    return result;
  }

  private SessionState newPausedSession(String id, Boolean pauseSettled) {
    SessionState s = new SessionState(id, "/src/" + id, "/out");
    s.setStatus("PAUSED");
    s.setUsername(USERNAME);
    s.setPendingFilePaths(List.of("/src/" + id + "/A.java"));
    s.setPauseSettled(pauseSettled);
    return s;
  }

  // ────────────────────────────────────────────────────────────────────────────

  /** P3 — 컬럼 추가 전 행은 NULL로 읽히고(예외 없음) 확정(true)으로 해석되며, 목록 API도 true를 내려준다. */
  @Test
  void P3_컬럼추가_이전에_저장된_기존_PAUSED_행은_NULL로_읽히며_확정true로_해석된다() {
    // DDL 근거: 스크립트에는 없던 컬럼이 Hibernate update로 실제 추가됐고, 기존 행의 값은 NULL이다.
    Integer columnCount = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME)='ANALYSIS_SESSIONS' AND UPPER(COLUMN_NAME)='PAUSE_SETTLED'",
        Integer.class);
    assertEquals(1, columnCount, "ddl-auto=update가 pause_settled 컬럼을 추가해야 한다");
    Boolean rawDbValue = jdbcTemplate.queryForObject(
        "SELECT pause_settled FROM analysis_sessions WHERE session_id='legacy-paused-1'", Boolean.class);
    assertNull(rawDbValue, "컬럼 추가 전에 있던 행의 pause_settled는 NULL이어야 한다");
    System.out.println("[P3] legacy-paused-1 pause_settled(DB raw)=" + rawDbValue);

    // 엔티티 읽기: wrapper 타입이라 예외 없이 NULL로 읽히고, 헬퍼는 확정(true)으로 해석한다.
    Optional<SessionState> legacy = sessionRepository.findById("legacy-paused-1");
    assertTrue(legacy.isPresent(), "기존 행을 읽지 못했다");
    assertNull(legacy.get().getPauseSettled());
    assertTrue(legacy.get().hasSettledPause(), "NULL은 확정(settled)으로 읽혀야 한다 — 기존 동작 보존이 기본값");
    assertEquals(2, legacy.get().getPendingFilePaths().size(), "기존 pending 목록도 그대로 읽혀야 한다");

    // 목록 API: 기존 PAUSED 행은 pauseSettled=true (재개 버튼이 그대로 뜨는 쪽).
    when(analysisHistoryRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ))
        .thenReturn(List.of(history(1L, "legacy-paused-1", "PAUSED")));
    Map<String, Boolean> list = listPauseSettledBySessionId();
    System.out.println("[P3] list=" + list);
    assertEquals(Boolean.TRUE, list.get("legacy-paused-1"));
  }

  /** P5 — PAUSED 행이 여럿이어도 세션 조회 SQL은 1문장, PAUSED 행이 없으면 0문장. */
  @Test
  void P5_목록API_1회당_세션_조회_SQL은_PAUSED행_수와_무관하게_정확히_1문장이다() {
    sessionRepository.saveAll(List.of(
        newPausedSession("p5-unsettled", Boolean.FALSE),
        newPausedSession("p5-settled-true", Boolean.TRUE),
        newPausedSession("p5-settled-null", null)));
    when(analysisHistoryRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ)).thenReturn(List.of(
        history(1L, "p5-unsettled", "PAUSED"),
        history(2L, "p5-settled-true", "PAUSED"),
        history(3L, "p5-settled-null", "PAUSED"),
        history(4L, "p5-completed", "COMPLETED"),
        history(5L, "p5-paused-no-session-row", "PAUSED")));

    statistics.clear();
    Map<String, Boolean> list = listPauseSettledBySessionId();
    long statements = statistics.getPrepareStatementCount();
    long queries = statistics.getQueryExecutionCount();
    System.out.println("[P5] PAUSED 4행(세션 행 3개) → prepareStatementCount=" + statements
        + ", queryExecutionCount=" + queries + ", list=" + list);

    assertEquals(1, statements, "PAUSED 행이 4개여도 세션 조회는 findAllById 1문장이어야 한다(행별 개별 조회 금지)");
    assertEquals(Boolean.FALSE, list.get("p5-unsettled"));
    assertEquals(Boolean.TRUE, list.get("p5-settled-true"));
    assertEquals(Boolean.TRUE, list.get("p5-settled-null"), "NULL은 확정");
    assertEquals(Boolean.TRUE, list.get("p5-completed"), "PAUSED가 아닌 행은 조회 대상이 아니며 true");
    assertEquals(Boolean.TRUE, list.get("p5-paused-no-session-row"), "세션 행이 없는 PAUSED 이력은 확정으로 취급");

    // 대조군 — PAUSED 행이 없으면 세션 조회를 아예 하지 않는다(0문장).
    when(analysisHistoryRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ)).thenReturn(List.of(
        history(4L, "p5-completed", "COMPLETED"),
        history(6L, "p5-in-progress", "IN_PROGRESS")));
    statistics.clear();
    Map<String, Boolean> listNoPaused = listPauseSettledBySessionId();
    long statementsNoPaused = statistics.getPrepareStatementCount();
    System.out.println("[P5] PAUSED 0행 → prepareStatementCount=" + statementsNoPaused + ", list=" + listNoPaused);
    assertEquals(0, statementsNoPaused, "PAUSED 행이 없으면 DB를 조회하지 않아야 한다");
    assertEquals(Boolean.TRUE, listNoPaused.get("p5-completed"));
    assertEquals(Boolean.TRUE, listNoPaused.get("p5-in-progress"));
  }

  /**
   * P4(a)(b) 실 저장소판 — 실제 {@code AnalysisSessionManager}+H2 경유. {@code pauseSession()}이 false를 <b>DB에</b> 쓰고
   * 목록 API가 그것을 읽으며, pauseDetected 블록과 같은 순서(pending → true → 저장)로 확정하면 true로 바뀐다.
   */
  @Test
  void P4_실저장소_pauseSession은_false를_DB에_쓰고_확정_저장_후_목록API가_true로_바뀐다() {
    String sid = "p4-real-store";
    AnalysisSessionManager sessionManager = new AnalysisSessionManager(new SessionConfig(), sessionRepository);
    SessionState session = sessionManager.createSession(sid, "/src/" + sid, "/out");
    session.setUsername(USERNAME);

    MainApiController controller = new MainApiController(
        null, null, sessionManager, null, null, null,
        analysisHistoryRepository, null, null, null, null, null, null, null, null);
    AnalysisHistory h = history(7L, sid, "IN_PROGRESS");
    when(analysisHistoryRepository.findBySessionId(sid)).thenReturn(h);
    when(analysisHistoryRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ)).thenReturn(List.of(h));

    // 시작: 새 세션의 DB 행은 NULL(=확정)
    assertNull(sessionRepository.findById(sid).orElseThrow().getPauseSettled());
    assertEquals(Boolean.TRUE, listPauseSettledBySessionId().get(sid), "일시정지 전에는 true");

    // (a) 일시정지 요청 → DB 행 false, 이력 PAUSED, 목록 API false
    Map<String, Object> pauseResponse = controller.pauseSession(Map.of("sessionId", sid), ownerAuth);
    assertEquals(Boolean.TRUE, pauseResponse.get("success"), "pauseSession 실패: " + pauseResponse);
    SessionState dbAfterPause = sessionRepository.findById(sid).orElseThrow();
    System.out.println("[P4-real] (a) 요청 직후 DB: status=" + dbAfterPause.getStatus()
        + ", pauseSettled=" + dbAfterPause.getPauseSettled() + ", pendingJson=" + dbAfterPause.getPendingFilePathsJson());
    assertEquals("PAUSED", dbAfterPause.getStatus());
    assertEquals(Boolean.FALSE, dbAfterPause.getPauseSettled(), "pauseSession()은 false를 DB까지 반영해야 목록 API가 본다");
    assertEquals("PAUSED", h.getStatus());
    assertEquals(Boolean.FALSE, listPauseSettledBySessionId().get(sid), "(a) 목록 API false");

    // (b) pauseDetected 블록과 같은 순서로 확정 → DB 행 true, 목록 API true
    session.setPendingFilePaths(new ArrayList<>(List.of("/src/" + sid + "/A.java")));
    session.setPauseSettled(Boolean.TRUE);
    sessionManager.saveSessionState(session);
    SessionState dbAfterSettle = sessionRepository.findById(sid).orElseThrow();
    System.out.println("[P4-real] (b) 확정 후 DB: status=" + dbAfterSettle.getStatus()
        + ", pauseSettled=" + dbAfterSettle.getPauseSettled() + ", pendingJson=" + dbAfterSettle.getPendingFilePathsJson());
    assertEquals(Boolean.TRUE, dbAfterSettle.getPauseSettled());
    assertEquals(1, dbAfterSettle.getPendingFilePaths().size());
    assertEquals(Boolean.TRUE, listPauseSettledBySessionId().get(sid), "(b) 목록 API true");
  }

  // ─── TASK-002C (v5 §0.22) ─────────────────────────────────────────────────────

  /** 리플렉션으로 엔티티 필드의 raw 값을 읽는다 — raw getter를 추가하지 않기로 했으므로(Jackson 프로퍼티 중복 인식 회피). */
  private static Object rawField(Object target, String name) throws Exception {
    Field f = SessionState.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  /**
   * C1(층 1, DoD 3) — 컨트롤러 catch를 우회해 {@code SessionRepository}로 직접 읽는다. 옛 행의 {@code generate_readme}/
   * {@code force_active}가 DB에서 실제로 NULL인데도 로드에 예외가 없고, 접근자는 NULL을 Java 초기값으로 해석한다.
   * 매핑을 primitive로 되돌리면 {@code findAllById}에서
   * {@code Null value was assigned to a property [...SessionState.generateReadme] of primitive type}이 나며 RED가 된다.
   */
  @Test
  void C1_옛_행의_generate_readme와_force_active가_NULL이어도_리포지토리가_예외없이_로드하고_초기값으로_읽는다() throws Exception {
    // 픽스처가 실배포와 같은 모양인지(두 컬럼 nullable + 옛 행 NULL) 먼저 확인한다 — NOT NULL이면 이 회귀는 재현되지 않는다.
    Integer notNullCount = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME)='ANALYSIS_SESSIONS'"
            + " AND UPPER(COLUMN_NAME) IN ('GENERATE_README','FORCE_ACTIVE') AND IS_NULLABLE='NO'",
        Integer.class);
    assertEquals(0, notNullCount, "generate_readme/force_active는 실배포처럼 nullable이어야 한다");
    Map<String, Object> raw = jdbcTemplate.queryForMap(
        "SELECT generate_readme, force_active FROM analysis_sessions WHERE session_id='legacy-paused-1'");
    System.out.println("[C1] legacy-paused-1 DB raw=" + raw);
    assertNull(raw.get("GENERATE_README"), "옛 행의 generate_readme는 NULL이어야 한다(실배포 83/94)");
    assertNull(raw.get("FORCE_ACTIVE"), "옛 행의 force_active는 NULL이어야 한다(실배포 11/94)");

    // 층 1: 리포지토리 직접 호출(컨트롤러 catch 밖) — 예외 없이 로드돼야 한다.
    List<SessionState> loaded = sessionRepository.findAllById(List.of("legacy-paused-1"));
    assertEquals(1, loaded.size(), "findAllById가 옛 행을 예외 없이 로드해야 한다");
    SessionState legacy = loaded.get(0);
    Optional<SessionState> byId = sessionRepository.findById("legacy-paused-1");
    assertTrue(byId.isPresent(), "findById도 예외 없이 로드해야 한다");

    // raw 값은 NULL 그대로(옛 행을 UPDATE하지 않는다 — §A.2), 접근자는 Java 초기값으로 해석한다.
    assertNull(rawField(legacy, "generateReadme"), "raw generateReadme는 NULL(소급 정규화 없음)");
    assertNull(rawField(legacy, "forceActive"), "raw forceActive는 NULL(소급 정규화 없음)");
    assertTrue(legacy.isGenerateReadme(), "generateReadme NULL은 초기값 true로 읽는다(컬럼 도입 전 분석은 전부 README 생성)");
    assertFalse(legacy.isForceActive(), "forceActive NULL은 초기값 false로 읽는다(강제 재분석은 기본 비활성)");
    assertTrue(legacy.hasSettledPause(), "pauseSettled NULL은 확정 — TASK-002 동작 그대로");
    System.out.println("[C1] loaded: isGenerateReadme=" + legacy.isGenerateReadme()
        + ", isForceActive=" + legacy.isForceActive() + ", raw=(" + rawField(legacy, "generateReadme")
        + "," + rawField(legacy, "forceActive") + ")");

    // 대조군: 실제 값이 저장된 행은 NULL 해석과 무관하게 저장값 그대로 읽힌다(wrapper 전환이 값 경로를 바꾸지 않는다).
    SessionState explicit = newPausedSession("c1-explicit", Boolean.TRUE);
    explicit.setGenerateReadme(false);
    explicit.setForceActive(true);
    sessionRepository.save(explicit);
    SessionState reloaded = sessionRepository.findById("c1-explicit").orElseThrow();
    assertEquals(Boolean.FALSE, rawField(reloaded, "generateReadme"));
    assertEquals(Boolean.TRUE, rawField(reloaded, "forceActive"));
    assertFalse(reloaded.isGenerateReadme(), "저장된 false는 false로");
    assertTrue(reloaded.isForceActive(), "저장된 true는 true로");
  }

  /**
   * C2(층 2, DoD 3) — NULL 컬럼 행이 섞인 상태에서 목록 API가 200이고 그 PAUSED 행의 {@code pauseSettled}가 true다.
   * (2026-09-15 실배포에서는 이 호출이 500 — "분석이력 조회 실패: Null value was assigned to a property
   * [class com.legacy.analysis.SessionState.generateReadme] of primitive type" — 이었다.)
   */
  @Test
  void C2_NULL_컬럼_행이_섞여_있어도_목록API는_200이고_그_PAUSED_행의_pauseSettled는_true다() {
    sessionRepository.save(newPausedSession("c2-unsettled", Boolean.FALSE));
    when(analysisHistoryRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ)).thenReturn(List.of(
        history(1L, "legacy-paused-1", "PAUSED"),   // 옛 행(generate_readme/force_active NULL)
        history(2L, "c2-unsettled", "PAUSED"),      // 같은 배치 안의 멈추는 중 행 — 격리 catch가 정상 경로를 가리지 않는지
        history(3L, "c2-completed", "COMPLETED")));

    ResponseEntity<?> response = userActivityController.getMyAnalysisHistory(ownerAuth);
    System.out.println("[C2] status=" + response.getStatusCode().value() + ", body=" + response.getBody());
    assertEquals(200, response.getStatusCode().value(), "NULL 행이 섞여도 목록 API는 200이어야 한다: " + response.getBody());

    Map<String, Boolean> list = listPauseSettledBySessionId();
    assertEquals(Boolean.TRUE, list.get("legacy-paused-1"), "옛 PAUSED 행은 확정(true)");
    assertEquals(Boolean.FALSE, list.get("c2-unsettled"), "같은 배치의 false 행은 그대로 false(강등이 아니라 정상 경로)");
    assertEquals(Boolean.TRUE, list.get("c2-completed"));
  }
}
