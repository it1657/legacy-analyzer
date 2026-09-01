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
  }

  // ---------- getSessionMetrics ----------

  @Test
  void getSessionMetrics는_세션이_없어도_권한체크를_건너뛰고_메트릭을_조회한다() {
    // 비대칭 동작(최우선 확인 시나리오): getSessionDetails는 session==null이면 SESSION_NOT_FOUND지만,
    // getSessionMetrics는 `session != null && !isOwnerOrAdmin(...)` 조건이라 존재하지 않는 세션 ID여도
    // 권한 체크 없이 곧바로 메트릭 조회가 성공한다(인증정보가 아예 없어도 마찬가지).
    Map<String, Object> metrics = new HashMap<>();
    metrics.put("processedFiles", 3);
    when(sessionManager.getSession(SESSION_ID)).thenReturn(null);
    when(metricsCollector.getSessionMetrics(SESSION_ID)).thenReturn(metrics);

    ApiResponseWrapper<Map<String, Object>> response =
        monitoringController.getSessionMetrics(SESSION_ID, null);

    assertTrue(response.isSuccess());
    assertThat(response.getData()).isSameAs(metrics);
    verify(metricsCollector, times(1)).getSessionMetrics(SESSION_ID);
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
  void getSessionMetrics는_조회_중_예외가_나면_METRICS_ERROR를_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());
    when(metricsCollector.getSessionMetrics(SESSION_ID))
        .thenThrow(new RuntimeException("메트릭 오류"));

    ApiResponseWrapper<Map<String, Object>> response =
        monitoringController.getSessionMetrics(SESSION_ID, ownerAuth());

    assertErrorCode(response, "METRICS_ERROR");
    assertEquals("메트릭 오류", response.getError().getMessage());
  }

  // ---------- deleteSession ----------

  @Test
  void deleteSession은_세션이_없어도_권한체크를_건너뛰고_삭제를_진행한다() {
    // getSessionMetrics와 동일한 비대칭 구조: 존재하지 않는 세션 ID여도 삭제 호출이 그대로 진행된다.
    when(sessionManager.getSession(SESSION_ID)).thenReturn(null);

    ApiResponseWrapper<Boolean> response = monitoringController.deleteSession(SESSION_ID, null);

    assertTrue(response.isSuccess());
    assertEquals(Boolean.TRUE, response.getData());
    verify(sessionManager, times(1)).deleteSession(SESSION_ID);
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
  }
}
