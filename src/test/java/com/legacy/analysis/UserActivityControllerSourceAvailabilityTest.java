package com.legacy.analysis;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.legacy.auth.Role;
import com.legacy.auth.User;
import com.legacy.core.PresentationGeneratorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TASK-003 (work-order 2026-10-resume-consistency-and-local-guard v1, REQ-004 / 게이트1 D1·D2) —
 * 목록 API({@code GET /api/my/analysis-history})의 <b>원본 존재 상태 3필드</b> 계약.
 *
 * <p>필드는 {@code sourceAvailability}·{@code pendingFileCount}·{@code missingFileCount}이고,
 * <b>PAUSED + 확정 + 세션 행 있음 + 대기 목록 있음</b>일 때만 값이 있다. 그 외에는 전부 {@code null}이며
 * 프런트는 {@code null}을 "표시할 것 없음"으로 읽는다(기존 동작).
 *
 * <p>원본 유무는 <b>실제 파일을 만들거나 만들지 않아서</b> 재현한다({@code @TempDir}) — 가짜 경로
 * 문자열만으로는 경로 해석 쪽 버그를 잡을 수 없다.
 */
class UserActivityControllerSourceAvailabilityTest {

  private static final Long USER_SEQ = 30L;
  private static final String USERNAME = "jhjung";

  @TempDir Path dir;

  private AnalysisHistoryRepository historyRepository;
  private SessionRepository sessionRepository;
  private UserActivityController controller;
  private Authentication ownerAuth;

  private ch.qos.logback.classic.Logger controllerLogger;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    historyRepository = mock(AnalysisHistoryRepository.class);
    sessionRepository = mock(SessionRepository.class);
    controller = new UserActivityController(
        historyRepository, mock(PresentationGeneratorService.class), sessionRepository);

    User owner = new User(USERNAME, USERNAME + "@example.com", "hash");
    owner.setSeq(USER_SEQ);
    owner.setRoles(Set.of(new Role("USER", "일반 사용자")));
    ownerAuth = new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities());

    controllerLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(UserActivityController.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    controllerLogger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    controllerLogger.detachAppender(logAppender);
    logAppender.stop();
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 도우미
  // ────────────────────────────────────────────────────────────────────────────

  private AnalysisHistory history(long id, String sessionId, String status) {
    AnalysisHistory h = new AnalysisHistory(USER_SEQ, sessionId, "/src/" + sessionId, "/out");
    h.setId(id);
    h.setStatus(status);
    return h;
  }

  private SessionState session(String id, Boolean pauseSettled, List<String> pending) {
    SessionState s = new SessionState(id, dir.toString(), dir.resolve("out").toString());
    s.setStatus("PAUSED");
    s.setUsername(USERNAME);
    s.setPauseSettled(pauseSettled);
    s.setPendingFilePaths(pending);
    return s;
  }

  private String real(String name) throws Exception {
    return Files.createFile(dir.resolve(name)).toString();
  }

  private String absent(String name) {
    return dir.resolve(name).toString();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Map<String, Object>> callListBySessionId() {
    ResponseEntity<?> response = controller.getMyAnalysisHistory(ownerAuth);
    assertEquals(200, response.getStatusCode().value(), "목록 API 실패: " + response.getBody());
    Map<String, Map<String, Object>> result = new LinkedHashMap<>();
    for (Map<String, Object> row : (List<Map<String, Object>>) response.getBody()) {
      result.put((String) row.get("sessionId"), row);
    }
    return result;
  }

  private static void assertNoSourceFields(Map<String, Object> row, String label) {
    assertTrue(row.containsKey("sourceAvailability"), label + ": sourceAvailability 키 자체는 있어야 한다");
    assertNull(row.get("sourceAvailability"), label + ": sourceAvailability는 null이어야 한다");
    assertNull(row.get("pendingFileCount"), label + ": pendingFileCount는 null이어야 한다");
    assertNull(row.get("missingFileCount"), label + ": missingFileCount는 null이어야 한다");
  }

  private List<String> errorMessages() {
    return logAppender.list.stream()
        .filter(e -> e.getLevel() == Level.ERROR)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 3상태 + null 조건
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void MISSING_PARTIAL_AVAILABLE_세_상태가_필드로_내려간다() throws Exception {
    SessionState missing = session("s-missing", Boolean.TRUE,
        List.of(absent("m1.java"), absent("m2.java"), absent("m3.java")));
    SessionState partial = session("s-partial", Boolean.TRUE,
        List.of(real("p-keep.java"), absent("p-gone1.java"), absent("p-gone2.java"), real("p-keep2.java")));
    SessionState available = session("s-available", Boolean.TRUE,
        List.of(real("a1.java"), real("a2.java")));
    when(sessionRepository.findAllById(any())).thenReturn(List.of(missing, partial, available));
    when(historyRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ)).thenReturn(List.of(
        history(1L, "s-missing", "PAUSED"),
        history(2L, "s-partial", "PAUSED"),
        history(3L, "s-available", "PAUSED")));

    Map<String, Map<String, Object>> rows = callListBySessionId();
    System.out.println("[목록 JSON] MISSING   = " + rows.get("s-missing"));
    System.out.println("[목록 JSON] PARTIAL   = " + rows.get("s-partial"));
    System.out.println("[목록 JSON] AVAILABLE = " + rows.get("s-available"));

    assertEquals("MISSING", rows.get("s-missing").get("sourceAvailability"));
    assertEquals(3, rows.get("s-missing").get("pendingFileCount"));
    assertEquals(3, rows.get("s-missing").get("missingFileCount"));

    assertEquals("PARTIAL", rows.get("s-partial").get("sourceAvailability"));
    assertEquals(4, rows.get("s-partial").get("pendingFileCount"));
    assertEquals(2, rows.get("s-partial").get("missingFileCount"));

    assertEquals("AVAILABLE", rows.get("s-available").get("sourceAvailability"));
    assertEquals(2, rows.get("s-available").get("pendingFileCount"));
    assertEquals(0, rows.get("s-available").get("missingFileCount"));

    // 기존 필드는 그대로
    assertEquals(Boolean.TRUE, rows.get("s-missing").get("pauseSettled"));
    assertEquals("PAUSED", rows.get("s-missing").get("status"));
  }

  @Test
  void 미확정_PAUSED는_원본_판정을_하지_않고_null이다() throws Exception {
    SessionState unsettled = session("s-unsettled", Boolean.FALSE,
        List.of(absent("x.java"), absent("y.java")));
    when(sessionRepository.findAllById(any())).thenReturn(List.of(unsettled));
    when(historyRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ))
        .thenReturn(List.of(history(1L, "s-unsettled", "PAUSED")));

    Map<String, Object> row = callListBySessionId().get("s-unsettled");
    System.out.println("[목록 JSON] 미확정 = " + row);

    assertEquals(Boolean.FALSE, row.get("pauseSettled"), "미확정 판정(기존 필드)은 그대로 동작해야 한다");
    assertNoSourceFields(row, "미확정 PAUSED");
  }

  @Test
  void 세션_행이_없는_PAUSED_이력은_null이다() {
    when(sessionRepository.findAllById(any())).thenReturn(List.of());
    when(historyRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ))
        .thenReturn(List.of(history(1L, "s-no-session-row", "PAUSED")));

    Map<String, Object> row = callListBySessionId().get("s-no-session-row");
    System.out.println("[목록 JSON] 세션 행 없음 = " + row);

    assertEquals(Boolean.TRUE, row.get("pauseSettled"), "세션 행이 없으면 확정으로 취급(기존 동작)");
    assertNoSourceFields(row, "세션 행 없는 PAUSED");
  }

  @Test
  void 대기_목록이_빈_PAUSED는_NO_PENDING이므로_null이다() {
    SessionState empty = session("s-empty", Boolean.TRUE, List.of());
    when(sessionRepository.findAllById(any())).thenReturn(List.of(empty));
    when(historyRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ))
        .thenReturn(List.of(history(1L, "s-empty", "PAUSED")));

    Map<String, Object> row = callListBySessionId().get("s-empty");
    System.out.println("[목록 JSON] 대기 목록 빔 = " + row);

    assertNoSourceFields(row, "대기 목록이 빈 PAUSED");
  }

  @Test
  void COMPLETED_CANCELLED_IN_PROGRESS_행은_null이다() throws Exception {
    // PAUSED가 아닌 이력은 애초에 세션 배치 조회 대상이 아니다.
    SessionState live = session("s-done", Boolean.TRUE, List.of(real("d1.java")));
    when(sessionRepository.findAllById(any())).thenReturn(List.of(live));
    when(historyRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ)).thenReturn(List.of(
        history(1L, "s-done", "COMPLETED"),
        history(2L, "s-cancelled", "CANCELLED"),
        history(3L, "s-running", "IN_PROGRESS")));

    Map<String, Map<String, Object>> rows = callListBySessionId();
    System.out.println("[목록 JSON] COMPLETED = " + rows.get("s-done"));
    assertNoSourceFields(rows.get("s-done"), "COMPLETED");
    assertNoSourceFields(rows.get("s-cancelled"), "CANCELLED");
    assertNoSourceFields(rows.get("s-running"), "IN_PROGRESS");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 조회 횟수 (RG-4) — 추가 DB 조회 0회
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void PAUSED가_없으면_findAllById를_부르지_않고_있으면_정확히_1회만_부른다() throws Exception {
    // (1) PAUSED 0행 → 0회
    when(historyRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ)).thenReturn(List.of(
        history(1L, "c1", "COMPLETED"), history(2L, "c2", "IN_PROGRESS")));
    callListBySessionId();
    verify(sessionRepository, never()).findAllById(any());

    // (2) PAUSED 4행(세션 3개) → 1회. 원본 판정이 행마다 추가 조회를 하지 않음을 함께 보인다.
    SessionState a = session("q-a", Boolean.TRUE, List.of(real("qa.java")));
    SessionState b = session("q-b", Boolean.TRUE, List.of(absent("qb.java")));
    SessionState c = session("q-c", Boolean.FALSE, List.of(absent("qc.java")));
    when(sessionRepository.findAllById(any())).thenReturn(List.of(a, b, c));
    when(historyRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ)).thenReturn(List.of(
        history(1L, "q-a", "PAUSED"), history(2L, "q-b", "PAUSED"),
        history(3L, "q-c", "PAUSED"), history(4L, "q-d", "PAUSED"),
        history(5L, "q-e", "COMPLETED")));
    Map<String, Map<String, Object>> rows = callListBySessionId();

    verify(sessionRepository, times(1)).findAllById(any());
    assertEquals("AVAILABLE", rows.get("q-a").get("sourceAvailability"));
    assertEquals("MISSING", rows.get("q-b").get("sourceAvailability"));
    assertNoSourceFields(rows.get("q-c"), "미확정");
    assertNoSourceFields(rows.get("q-d"), "세션 행 없음");
    assertNoSourceFields(rows.get("q-e"), "COMPLETED");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 장애 격리 (RG-4) — 강등 방향 불변
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void 세션_배치조회가_예외를_던지면_200에_전_행_pauseSettled_true와_sourceAvailability_null로_강등된다() {
    when(sessionRepository.findAllById(any()))
        .thenThrow(new RuntimeException("Null value was assigned to a property of primitive type"));
    when(historyRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ)).thenReturn(List.of(
        history(1L, "e-1", "PAUSED"), history(2L, "e-2", "PAUSED"), history(3L, "e-3", "COMPLETED")));

    Map<String, Map<String, Object>> rows = callListBySessionId();   // 200이 아니면 여기서 실패
    System.out.println("[목록 JSON] 조회 예외 강등 = " + rows.get("e-1"));

    for (String sid : List.of("e-1", "e-2", "e-3")) {
      assertEquals(Boolean.TRUE, rows.get(sid).get("pauseSettled"), sid + ": 강등 방향은 기존 동작(true)");
      assertNoSourceFields(rows.get(sid), sid);
    }
    assertTrue(errorMessages().stream().anyMatch(m -> m.contains("[내 분석이력 pauseSettled 조회 실패]")),
        "강등 시 기존 ERROR 로그가 남아야 한다. 실제 ERROR 로그=" + errorMessages());
  }

  @Test
  void 한_행의_원본_판정만_실패하면_그_행만_null이고_나머지_행은_정상이다() throws Exception {
    SessionState healthy = session("iso-ok", Boolean.TRUE, List.of(absent("iso.java")));
    // 그 세션의 대기 목록 읽기만 터지게 만든다(다른 행에는 영향이 없어야 한다).
    SessionState broken = new SessionState("iso-broken", dir.toString(), dir.resolve("out").toString()) {
      @Override
      public List<String> getPendingFilePaths() {
        throw new IllegalStateException("대기 목록 파싱 실패(합성)");
      }
    };
    broken.setStatus("PAUSED");
    broken.setUsername(USERNAME);
    broken.setPauseSettled(Boolean.TRUE);
    when(sessionRepository.findAllById(any())).thenReturn(List.of(broken, healthy));
    when(historyRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ)).thenReturn(List.of(
        history(1L, "iso-broken", "PAUSED"), history(2L, "iso-ok", "PAUSED")));

    Map<String, Map<String, Object>> rows = callListBySessionId();   // 200 유지
    System.out.println("[목록 JSON] 한 행 판정 실패 = " + rows.get("iso-broken"));
    System.out.println("[목록 JSON] 같은 응답의 정상 행 = " + rows.get("iso-ok"));

    assertNoSourceFields(rows.get("iso-broken"), "판정 실패 행");
    assertEquals(Boolean.TRUE, rows.get("iso-broken").get("pauseSettled"), "pauseSettled는 그대로 계산된다");
    // 양성 대조군 — 같은 응답의 다른 행은 정상적으로 값이 실린다(격리가 전체를 null로 만들지 않는다).
    assertEquals("MISSING", rows.get("iso-ok").get("sourceAvailability"));
    assertEquals(1, rows.get("iso-ok").get("pendingFileCount"));
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 기존 필드 불변 (RG-4)
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void 기존_필드_이름과_순서가_그대로이고_신규_3필드는_맨_뒤에_붙는다() throws Exception {
    SessionState s = session("k-1", Boolean.TRUE, List.of(real("k.java")));
    when(sessionRepository.findAllById(any())).thenReturn(List.of(s));
    when(historyRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ))
        .thenReturn(List.of(history(1L, "k-1", "PAUSED")));

    Map<String, Object> row = callListBySessionId().get("k-1");
    List<String> keys = new ArrayList<>(row.keySet());
    System.out.println("[목록 JSON] 키 순서 = " + keys);

    assertEquals(List.of("id", "sessionId", "sourcePath", "outputPath", "totalFiles", "successCount",
            "skipCount", "failureCount", "processingTimeMs", "avgTimePerFile", "status", "modelName",
            "inputTokens", "outputTokens", "estimatedCost", "createdAt", "completedAt", "readmePath",
            "hasClaudeMd", "pauseSettled", "sourceAvailability", "pendingFileCount", "missingFileCount"),
        keys, "기존 키 이름·순서가 바뀌었거나 신규 필드가 중간에 끼었다");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 비용 실측 (단언하지 않는다 — 환경 의존)
  // ────────────────────────────────────────────────────────────────────────────

  @Test
  void 비용실측_대기_2000개_PAUSED_1건의_목록_API_1회_호출_소요시간을_출력한다() throws Exception {
    Path big = Files.createDirectory(dir.resolve("big"));
    List<String> pending = new ArrayList<>(2000);
    for (int i = 0; i < 1000; i++) {
      pending.add(Files.createFile(big.resolve("exists-" + i + ".java")).toString());
      pending.add(big.resolve("absent-" + i + ".java").toString());
    }
    SessionState s = session("cost-1", Boolean.TRUE, pending);
    when(sessionRepository.findAllById(any())).thenReturn(List.of(s));
    when(historyRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ))
        .thenReturn(List.of(history(1L, "cost-1", "PAUSED")));

    long startNanos = System.nanoTime();
    Map<String, Map<String, Object>> rows = callListBySessionId();
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

    Map<String, Object> row = rows.get("cost-1");
    System.out.println("[비용 실측] 대기 2000개(존재 1000 / 없음 1000) PAUSED 1건 → 목록 API 1회 호출 "
        + elapsedMs + "ms, sourceAvailability=" + row.get("sourceAvailability")
        + ", pendingFileCount=" + row.get("pendingFileCount")
        + ", missingFileCount=" + row.get("missingFileCount"));

    // 값의 정확성만 단언하고 시간은 단언하지 않는다(환경 의존).
    assertEquals("PARTIAL", row.get("sourceAvailability"));
    assertEquals(2000, row.get("pendingFileCount"));
    assertEquals(1000, row.get("missingFileCount"));
  }
}
