package com.legacy.api.monitoring;

import com.legacy.analysis.AnalysisLogger;
import com.legacy.analysis.AnalysisSessionManager;
import com.legacy.analysis.ApiResponseWrapper;
import com.legacy.analysis.SessionDetailDto;
import com.legacy.analysis.SessionState;
import com.legacy.auth.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MonitoringController의 getSessionDetails/getSessionMetrics/deleteSession을 검증한다.
 * 02-design-v1 6.2절 근거. private 메서드 isOwnerOrAdmin은 세 엔드포인트를 통해 간접 검증한다.
 *
 * <p>소유자 조합: 세션 소유자는 "alice".
 * (A) principal=alice, ADMIN 아님 → 허용 / (B) principal=bob + ROLE_ADMIN → 허용 /
 * (C) principal=bob, ADMIN 아님 → ACCESS_DENIED / (D) authentication==null → ACCESS_DENIED(NPE 아님).
 */
class MonitoringControllerSessionAndMetricsTest {

  private static final String SESSION_ID = "session-1";

  private AnalysisSessionManager sessionManager;
  private AnalysisLogger analysisLogger;
  private PerformanceMetricsCollector metricsCollector;
  private MonitoringController monitoringController;

  @BeforeEach
  void setUp() {
    sessionManager = mock(AnalysisSessionManager.class);
    analysisLogger = mock(AnalysisLogger.class);
    metricsCollector = mock(PerformanceMetricsCollector.class);
    monitoringController = new MonitoringController(sessionManager, analysisLogger,
        metricsCollector);
  }

  /** (A) 세션 소유자 본인(관리자 아님) */
  private Authentication ownerAuth() {
    return MonitoringTestFixtures.newAuthentication(MonitoringTestFixtures.newUser("alice"), false);
  }

  /** (B) 다른 사용자지만 ROLE_ADMIN 보유 */
  private Authentication adminAuth() {
    return MonitoringTestFixtures.newAuthentication(MonitoringTestFixtures.newUser("bob"), true);
  }

  /** (C) 제3자(권한 없음) */
  private Authentication strangerAuth() {
    return MonitoringTestFixtures.newAuthentication(MonitoringTestFixtures.newUser("bob"), false);
  }

  private SessionState aliceSession() {
    return MonitoringTestFixtures.newSessionState(SESSION_ID, "alice");
  }

  private static void assertErrorCode(ApiResponseWrapper<?> response, String expectedCode) {
    assertFalse(response.isSuccess());
    assertNull(response.getData());
    assertEquals(expectedCode, response.getError().getCode());
  }

  // ---------- getSessionDetails ----------

  @Test
  void getSessionDetails는_세션이_없으면_SESSION_NOT_FOUND를_반환하고_권한체크를_하지_않는다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(null);

    ApiResponseWrapper<SessionDetailDto> response =
        monitoringController.getSessionDetails(SESSION_ID, null);

    assertErrorCode(response, "SESSION_NOT_FOUND");
    assertEquals("유효하지 않은 세션 ID", response.getError().getMessage());
    // REQ-003: 상위 수준 message도 응답에 함께 남는다(errorInfo.message와 별개).
    assertEquals("세션을 찾을 수 없습니다.", response.getMessage());
  }

  @Test
  void getSessionDetails는_소유자에게_세션_상세를_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());

    ApiResponseWrapper<SessionDetailDto> response =
        monitoringController.getSessionDetails(SESSION_ID, ownerAuth());

    assertTrue(response.isSuccess());
    assertNull(response.getError());
    assertEquals(SESSION_ID, response.getData().getSummary().getSessionId());
  }

  @Test
  void getSessionDetails는_관리자에게도_세션_상세를_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());

    ApiResponseWrapper<SessionDetailDto> response =
        monitoringController.getSessionDetails(SESSION_ID, adminAuth());

    assertTrue(response.isSuccess());
    assertEquals(SESSION_ID, response.getData().getSummary().getSessionId());
  }

  @Test
  void getSessionDetails는_제3자에게_ACCESS_DENIED를_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());

    ApiResponseWrapper<SessionDetailDto> response =
        monitoringController.getSessionDetails(SESSION_ID, strangerAuth());

    assertErrorCode(response, "ACCESS_DENIED");
    assertEquals("본인 세션만 조회할 수 있습니다.", response.getError().getMessage());
    // REQ-003: 상위 수준 message도 응답에 함께 남는다(errorInfo.message와 별개).
    assertEquals("접근 권한이 없습니다.", response.getMessage());
  }

  @Test
  void getSessionDetails는_인증정보가_없으면_NPE없이_ACCESS_DENIED를_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());

    ApiResponseWrapper<SessionDetailDto> response =
        monitoringController.getSessionDetails(SESSION_ID, null);

    assertErrorCode(response, "ACCESS_DENIED");
  }

  @Test
  void getSessionDetails는_조회_중_예외가_나면_SESSION_ERROR를_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenThrow(new RuntimeException("세션 저장소 오류"));

    ApiResponseWrapper<SessionDetailDto> response =
        monitoringController.getSessionDetails(SESSION_ID, ownerAuth());

    assertErrorCode(response, "SESSION_ERROR");
    assertEquals("세션 저장소 오류", response.getError().getMessage());
    // REQ-003: 상위 수준 message도 응답에 함께 남는다(errorInfo.message와 별개).
    assertEquals("세션 조회 실패: 세션 저장소 오류", response.getMessage());
  }

  // ---------- getSessionMetrics ----------

  @Test
  void getSessionMetrics는_세션이_없으면_SESSION_NOT_FOUND를_반환한다() {
    // 2026-09-security-fixes(REQ-003)로 수정된 동작: getSessionDetails와 동일하게
    // session==null이면 권한 체크/메트릭 조회에 도달하지 않고 SESSION_NOT_FOUND로 끊는다.
    // (수정 전에는 `session != null && !isOwnerOrAdmin(...)` 조건이라 인증정보가 없어도
    //  메트릭 조회가 그대로 성공하는 인가 우회 경로가 있었다.)
    when(sessionManager.getSession(SESSION_ID)).thenReturn(null);

    ApiResponseWrapper<Map<String, Object>> response =
        monitoringController.getSessionMetrics(SESSION_ID, null);

    assertErrorCode(response, "SESSION_NOT_FOUND");
    assertEquals("유효하지 않은 세션 ID", response.getError().getMessage());
    // REQ-003: 상위 수준 message도 응답에 함께 남는다(errorInfo.message와 별개).
    assertEquals("세션을 찾을 수 없습니다.", response.getMessage());
    verify(metricsCollector, never()).getSessionMetrics(anyString());
  }

  @Test
  void getSessionMetrics는_소유자에게_메트릭을_반환한다() {
    Map<String, Object> metrics = new HashMap<>();
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());
    when(metricsCollector.getSessionMetrics(SESSION_ID)).thenReturn(metrics);

    ApiResponseWrapper<Map<String, Object>> response =
        monitoringController.getSessionMetrics(SESSION_ID, ownerAuth());

    assertTrue(response.isSuccess());
    assertThat(response.getData()).isSameAs(metrics);
  }

  @Test
  void getSessionMetrics는_관리자에게도_메트릭을_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());
    when(metricsCollector.getSessionMetrics(SESSION_ID)).thenReturn(new HashMap<>());

    ApiResponseWrapper<Map<String, Object>> response =
        monitoringController.getSessionMetrics(SESSION_ID, adminAuth());

    assertTrue(response.isSuccess());
  }

  @Test
  void getSessionMetrics는_세션이_실존할_때_제3자에게_ACCESS_DENIED를_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());

    ApiResponseWrapper<Map<String, Object>> response =
        monitoringController.getSessionMetrics(SESSION_ID, strangerAuth());

    assertErrorCode(response, "ACCESS_DENIED");
    verify(metricsCollector, never()).getSessionMetrics(anyString());
  }

  @Test
  void getSessionMetrics는_인증정보가_없으면_NPE없이_ACCESS_DENIED를_반환한다() {
    // getSessionDetails/getSessionSummary와 대칭을 맞추기 위한 커버리지(04-work-order-v3 TASK-007 보강).
    // 세션은 실제로 존재하고 authentication만 null인 경우 — isOwnerOrAdmin의 null 가드가 동작해
    // NPE 없이 ACCESS_DENIED로 끊기고, 메트릭 조회 실행부에는 도달하지 않아야 한다.
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());

    ApiResponseWrapper<Map<String, Object>> response =
        monitoringController.getSessionMetrics(SESSION_ID, null);

    assertErrorCode(response, "ACCESS_DENIED");
    verify(metricsCollector, never()).getSessionMetrics(anyString());
  }

  @Test
  void getSessionMetrics는_조회_중_예외가_나면_METRICS_ERROR를_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());
    when(metricsCollector.getSessionMetrics(SESSION_ID))
        .thenThrow(new RuntimeException("메트릭 오류"));

    ApiResponseWrapper<Map<String, Object>> response =
        monitoringController.getSessionMetrics(SESSION_ID, ownerAuth());

    assertErrorCode(response, "METRICS_ERROR");
    assertEquals("메트릭 오류", response.getError().getMessage());
    // REQ-003: 상위 수준 message도 응답에 함께 남는다(errorInfo.message와 별개).
    assertEquals("세션 메트릭 조회 실패: 메트릭 오류", response.getMessage());
  }

  // ---------- deleteSession ----------

  @Test
  void deleteSession은_세션이_없으면_SESSION_NOT_FOUND를_반환한다() {
    // 2026-09-security-fixes(REQ-003)로 수정된 동작: 존재하지 않는 세션 ID면 삭제 호출 자체를 하지 않는다.
    // (수정 전에는 인증정보 없이도 deleteSession이 호출되고 data=true로 성공 응답이 나갔다.)
    when(sessionManager.getSession(SESSION_ID)).thenReturn(null);

    ApiResponseWrapper<Boolean> response = monitoringController.deleteSession(SESSION_ID, null);

    assertErrorCode(response, "SESSION_NOT_FOUND");
    assertEquals("유효하지 않은 세션 ID", response.getError().getMessage());
    // REQ-003: 상위 수준 message도 응답에 함께 남는다(errorInfo.message와 별개).
    assertEquals("세션을 찾을 수 없습니다.", response.getMessage());
    verify(sessionManager, never()).deleteSession(anyString());
  }

  @Test
  void deleteSession은_소유자의_삭제를_허용한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());

    ApiResponseWrapper<Boolean> response =
        monitoringController.deleteSession(SESSION_ID, ownerAuth());

    assertTrue(response.isSuccess());
    verify(sessionManager, times(1)).deleteSession(SESSION_ID);
  }

  @Test
  void deleteSession은_관리자의_삭제도_허용한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());

    ApiResponseWrapper<Boolean> response =
        monitoringController.deleteSession(SESSION_ID, adminAuth());

    assertTrue(response.isSuccess());
    verify(sessionManager, times(1)).deleteSession(SESSION_ID);
  }

  @Test
  void deleteSession은_제3자의_삭제를_거부한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());

    ApiResponseWrapper<Boolean> response =
        monitoringController.deleteSession(SESSION_ID, strangerAuth());

    assertErrorCode(response, "ACCESS_DENIED");
    assertEquals("본인 세션만 삭제할 수 있습니다.", response.getError().getMessage());
    // REQ-003: 상위 수준 message도 응답에 함께 남는다(errorInfo.message와 별개).
    assertEquals("접근 권한이 없습니다.", response.getMessage());
    verify(sessionManager, never()).deleteSession(anyString());
  }

  @Test
  void deleteSession은_인증정보가_없으면_NPE없이_ACCESS_DENIED를_반환한다() {
    // getSessionMetrics와 동일 취지의 대칭 커버리지(04-work-order-v3 TASK-007 보강).
    // 세션이 실존해도 인증정보가 없으면 삭제 실행부에 도달하지 않아야 한다.
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());

    ApiResponseWrapper<Boolean> response = monitoringController.deleteSession(SESSION_ID, null);

    assertErrorCode(response, "ACCESS_DENIED");
    verify(sessionManager, never()).deleteSession(anyString());
  }

  @Test
  void deleteSession은_삭제_중_예외가_나면_SESSION_ERROR를_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());
    org.mockito.Mockito.doThrow(new RuntimeException("삭제 오류"))
        .when(sessionManager).deleteSession(SESSION_ID);

    ApiResponseWrapper<Boolean> response =
        monitoringController.deleteSession(SESSION_ID, ownerAuth());

    assertErrorCode(response, "SESSION_ERROR");
    assertEquals("삭제 오류", response.getError().getMessage());
    // REQ-003: 상위 수준 message도 응답에 함께 남는다(errorInfo.message와 별개).
    assertEquals("세션 삭제 실패: 삭제 오류", response.getMessage());
  }
}
