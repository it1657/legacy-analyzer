package com.legacy.analysis;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legacy.auth.Role;
import com.legacy.auth.User;
import com.legacy.core.PresentationGeneratorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TASK-002C (work-order 2026-09-remaining-ux-fixes v5 §0.22, DoD 4·6) — 옛 세션 행의 NULL 컬럼으로
 * {@code GET /api/my/analysis-history}가 전량 500이 됐던 회귀(2026-09-15 실배포 관측)의 <b>두 방어층 중 두 번째</b>와
 * 직렬화 계약을 단언한다. (첫 번째 층 = 엔티티 매핑 정정은 {@code UserActivityControllerPauseSettledJpaTest} C1/C2가
 * 실제 H2로 단언한다 — 그쪽은 이 클래스가 검증하는 catch를 <b>우회</b>하므로 두 층이 서로를 가리지 않는다.)
 *
 * <ul>
 *   <li><b>F1 (DoD 4, 양성 대조군)</b>: 세션 저장소 대역이 <b>예외를 던져도</b> 목록 API는 200이고 전 행
 *       {@code pauseSettled=true}(기존 동작 강등)이며 ERROR 로그에 대상 sessionId가 남는다. 컨트롤러의 국소
 *       try/catch를 되돌리면 500이 되어 RED.</li>
 *   <li><b>F2 (DoD 4 보조)</b>: 대역이 정상이면 catch가 정상 경로를 가리지 않는다 — false 행은 false 그대로.</li>
 *   <li><b>S1 (DoD 6)</b>: 필드가 NULL이어도 Jackson 출력의 {@code generateReadme}/{@code forceActive}는
 *       {@code null}이 아니라 {@code true}/{@code false}로만 나가고, 프로퍼티가 중복 인식되지 않는다(raw getter 없음).</li>
 *   <li><b>S2 (DoD 6 보조)</b>: 세션 JSON에 두 속성이 없거나 있을 때의 역직렬화 기본값/명시값이 유지된다.</li>
 *   <li><b>M1 (DoD 2/7 구조 근거)</b>: 두 필드가 실제로 {@code Boolean} wrapper이고 접근자 시그니처는 primitive 그대로다.</li>
 * </ul>
 */
class PauseSettledNullColumnRegressionContractTest {

  private static final Long USER_SEQ = 10L;

  private AnalysisHistoryRepository analysisHistoryRepository;
  private SessionRepository sessionRepository;
  private UserActivityController controller;
  private Authentication ownerAuth;
  private ListAppender<ILoggingEvent> appender;
  private ch.qos.logback.classic.Logger controllerLogger;

  @BeforeEach
  void setUp() {
    analysisHistoryRepository = mock(AnalysisHistoryRepository.class);
    sessionRepository = mock(SessionRepository.class);
    controller = new UserActivityController(
        analysisHistoryRepository, mock(PresentationGeneratorService.class), sessionRepository);

    User owner = new User("jhjung", "jhjung@example.com", "hash");
    owner.setSeq(USER_SEQ);
    owner.setRoles(Set.of(new Role("USER", "일반 사용자")));
    ownerAuth = new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities());

    controllerLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(UserActivityController.class);
    appender = new ListAppender<>();
    appender.start();
    controllerLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    controllerLogger.detachAppender(appender);
  }

  private AnalysisHistory history(long id, String sessionId, String status) {
    AnalysisHistory h = new AnalysisHistory(USER_SEQ, sessionId, "/src/" + sessionId, "/out");
    h.setId(id);
    h.setStatus(status);
    return h;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Boolean> callListApi() {
    ResponseEntity<?> response = controller.getMyAnalysisHistory(ownerAuth);
    assertEquals(200, response.getStatusCode().value(), "목록 API 실패: " + response.getBody());
    Map<String, Boolean> result = new java.util.LinkedHashMap<>();
    for (Map<String, Object> row : (List<Map<String, Object>>) response.getBody()) {
      result.put((String) row.get("sessionId"), (Boolean) row.get("pauseSettled"));
    }
    return result;
  }

  // ─── DoD 4: 장애 격리(양성 대조군) ──────────────────────────────────────────

  /** F1 — 세션 조회가 예외를 던져도 목록 API는 200 + 전 행 true + ERROR 로그(대상 sessionId 포함). */
  @Test
  void F1_세션_조회가_예외를_던져도_목록API는_200이고_전_행_pauseSettled_true이며_ERROR_로그가_남는다() {
    RuntimeException simulated = new RuntimeException(
        "Null value was assigned to a property [class com.legacy.analysis.SessionState.generateReadme] of primitive type (simulated)");
    when(sessionRepository.findAllById(anyIterable())).thenThrow(simulated);
    when(analysisHistoryRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ)).thenReturn(List.of(
        history(1L, "old-paused-null-row", "PAUSED"),
        history(2L, "another-paused", "PAUSED"),
        history(3L, "done", "COMPLETED")));

    ResponseEntity<?> response = controller.getMyAnalysisHistory(ownerAuth);
    System.out.println("[F1] status=" + response.getStatusCode().value() + ", body=" + response.getBody());
    assertEquals(200, response.getStatusCode().value(),
        "부가 필드(pauseSettled) 조회 실패가 목록 전체를 죽이면 안 된다: " + response.getBody());

    Map<String, Boolean> list = callListApi();
    assertEquals(3, list.size(), "행 수 불변");
    assertEquals(Boolean.TRUE, list.get("old-paused-null-row"), "강등 방향 = 기존 동작(확정=true, 버튼 노출)");
    assertEquals(Boolean.TRUE, list.get("another-paused"));
    assertEquals(Boolean.TRUE, list.get("done"));

    List<ILoggingEvent> errors = appender.list.stream()
        .filter(e -> e.getLevel() == Level.ERROR)
        .filter(e -> e.getFormattedMessage().contains("pauseSettled 조회 실패"))
        .toList();
    System.out.println("[F1] error logs=" + errors.stream().map(ILoggingEvent::getFormattedMessage).toList());
    assertFalse(errors.isEmpty(), "격리 catch는 ERROR 로그를 남겨야 한다(조용히 삼키지 않는다)");
    ILoggingEvent first = errors.get(0);
    assertTrue(first.getFormattedMessage().contains("old-paused-null-row")
        && first.getFormattedMessage().contains("another-paused"), "대상 sessionId 목록이 로그에 남아야 한다");
    assertTrue(first.getThrowableProxy() != null
        && first.getThrowableProxy().getMessage().contains("SessionState.generateReadme"), "원인 예외가 로그에 붙어야 한다");

    // 메서드 단위 catch(500 응답)에는 도달하지 않았다 — "[내 분석이력 조회 실패]" 로그 0건.
    assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains("[내 분석이력 조회 실패]")),
        "국소 격리가 됐으면 메서드 단위 500 catch는 타지 않아야 한다");
  }

  /** F2 — 대역이 정상이면 catch는 정상 경로를 가리지 않는다(false 행은 false). PAUSED 0행이면 조회 0회. */
  @Test
  void F2_세션_조회가_정상이면_격리_catch가_정상_판정을_가리지_않는다() {
    SessionState unsettled = new SessionState("s-unsettled", "/src/s-unsettled", "/out");
    unsettled.setStatus("PAUSED");
    unsettled.setPauseSettled(Boolean.FALSE);
    SessionState settled = new SessionState("s-settled", "/src/s-settled", "/out");
    settled.setStatus("PAUSED");
    settled.setPauseSettled(null);
    when(sessionRepository.findAllById(anyIterable())).thenReturn(List.of(unsettled, settled));
    when(analysisHistoryRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ)).thenReturn(List.of(
        history(1L, "s-unsettled", "PAUSED"),
        history(2L, "s-settled", "PAUSED"),
        history(3L, "s-done", "COMPLETED")));

    Map<String, Boolean> list = callListApi();
    System.out.println("[F2] list=" + list);
    assertEquals(Boolean.FALSE, list.get("s-unsettled"), "정상 경로의 false는 그대로 false여야 한다(강등 아님)");
    assertEquals(Boolean.TRUE, list.get("s-settled"));
    assertEquals(Boolean.TRUE, list.get("s-done"));
    assertTrue(appender.list.stream().noneMatch(e -> e.getLevel() == Level.ERROR), "정상 경로에서는 ERROR 로그 0건");

    // 대조군 — PAUSED 행이 없으면 세션 저장소를 호출하지 않는다(TASK-002 P5 동작 보존).
    when(analysisHistoryRepository.findByUserIdOrderByCreatedAtDesc(USER_SEQ)).thenReturn(List.of(
        history(3L, "s-done", "COMPLETED")));
    sessionRepository = mock(SessionRepository.class);
    controller = new UserActivityController(analysisHistoryRepository, mock(PresentationGeneratorService.class), sessionRepository);
    assertEquals(Boolean.TRUE, callListApi().get("s-done"));
    verify(sessionRepository, never()).findAllById(anyIterable());
  }

  // ─── DoD 6: 직렬화 출력 불변 ────────────────────────────────────────────────

  private static void setRaw(SessionState target, String name, Object value) throws Exception {
    Field f = SessionState.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  private static int countKey(String json, String key) {
    String needle = "\"" + key + "\"";
    int count = 0, idx = 0;
    while ((idx = json.indexOf(needle, idx)) >= 0) { count++; idx += needle.length(); }
    return count;
  }

  /** S1 — 필드가 NULL이어도 JSON에는 null이 새지 않고 true/false로만 나가며, 프로퍼티는 각각 정확히 1개다. */
  @Test
  void S1_필드가_NULL이어도_JSON의_generateReadme_forceActive는_null이_아니라_true_false로만_나간다() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    // 신규 객체(초기값): true / false
    SessionState fresh = new SessionState("s-fresh", "/src", "/out");
    JsonNode freshJson = mapper.readTree(mapper.writeValueAsString(fresh));
    assertTrue(freshJson.get("generateReadme").isBoolean() && freshJson.get("generateReadme").asBoolean());
    assertTrue(freshJson.get("forceActive").isBoolean() && !freshJson.get("forceActive").asBoolean());

    // 옛 행처럼 raw 필드가 NULL인 객체: 여전히 true / false (null 아님)
    SessionState legacy = new SessionState("s-legacy", "/src", "/out");
    setRaw(legacy, "generateReadme", null);
    setRaw(legacy, "forceActive", null);
    String json = mapper.writeValueAsString(legacy);
    JsonNode node = mapper.readTree(json);
    System.out.println("[S1] generateReadme=" + node.get("generateReadme") + ", forceActive=" + node.get("forceActive"));
    assertFalse(node.get("generateReadme").isNull(), "generateReadme가 null로 새면 안 된다");
    assertFalse(node.get("forceActive").isNull(), "forceActive가 null로 새면 안 된다");
    assertEquals(true, node.get("generateReadme").asBoolean(), "NULL → 초기값 true");
    assertEquals(false, node.get("forceActive").asBoolean(), "NULL → 초기값 false");
    assertEquals(1, countKey(json, "generateReadme"), "Jackson이 두 번째 프로퍼티로 인식하면 안 된다(raw getter 금지)");
    assertEquals(1, countKey(json, "forceActive"), "Jackson이 두 번째 프로퍼티로 인식하면 안 된다(raw getter 금지)");

    // 명시값도 그대로: false / true
    SessionState explicit = new SessionState("s-explicit", "/src", "/out");
    explicit.setGenerateReadme(false);
    explicit.setForceActive(true);
    JsonNode explicitJson = mapper.readTree(mapper.writeValueAsString(explicit));
    assertEquals(false, explicitJson.get("generateReadme").asBoolean());
    assertEquals(true, explicitJson.get("forceActive").asBoolean());
  }

  /** S2 — 역직렬화: 속성이 없는 옛 세션 JSON은 초기값(true/false), 있으면 그 값. */
  @Test
  void S2_세션_JSON에_두_속성이_없으면_초기값이고_있으면_그_값으로_역직렬화된다() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    SessionState noProps = mapper.readValue("{\"sessionId\":\"j1\",\"status\":\"PAUSED\"}", SessionState.class);
    assertTrue(noProps.isGenerateReadme(), "속성이 없는 옛 JSON → true");
    assertFalse(noProps.isForceActive(), "속성이 없는 옛 JSON → false");

    SessionState withProps = mapper.readValue(
        "{\"sessionId\":\"j2\",\"status\":\"PAUSED\",\"generateReadme\":false,\"forceActive\":true}", SessionState.class);
    assertFalse(withProps.isGenerateReadme());
    assertTrue(withProps.isForceActive());
  }

  // ─── 구조 근거(DoD 2/7): wrapper 매핑 + 접근자 시그니처 불변 ───────────────────

  /** M1 — 두 필드는 Boolean wrapper, 접근자는 primitive boolean 그대로, raw getter는 없다. */
  @Test
  void M1_두_필드는_Boolean_wrapper이고_접근자_시그니처는_primitive_그대로이며_raw_getter는_없다() throws Exception {
    assertEquals(Boolean.class, SessionState.class.getDeclaredField("generateReadme").getType(),
        "generateReadme는 nullable 컬럼이므로 Boolean wrapper여야 한다(옛 행 NULL 83/94)");
    assertEquals(Boolean.class, SessionState.class.getDeclaredField("forceActive").getType(),
        "forceActive는 나중에 추가된 nullable 컬럼이므로 Boolean wrapper여야 한다(옛 행 NULL 11/94)");
    // pauseSettled 선례와 같은 계열
    assertEquals(Boolean.class, SessionState.class.getDeclaredField("pauseSettled").getType());

    assertEquals(boolean.class, SessionState.class.getMethod("isGenerateReadme").getReturnType());
    assertEquals(boolean.class, SessionState.class.getMethod("isForceActive").getReturnType());
    assertEquals(boolean.class, SessionState.class.getMethod("setGenerateReadme", boolean.class).getParameterTypes()[0]);
    assertEquals(boolean.class, SessionState.class.getMethod("setForceActive", boolean.class).getParameterTypes()[0]);

    List<String> publicRawGetters = Arrays.stream(SessionState.class.getMethods())
        .filter(m -> Modifier.isPublic(m.getModifiers()))
        .map(Method::getName)
        .filter(n -> n.equals("getGenerateReadme") || n.equals("getForceActive"))
        .toList();
    assertTrue(publicRawGetters.isEmpty(), "raw getter가 있으면 Jackson이 프로퍼티를 중복 인식한다: " + publicRawGetters);
  }
}
