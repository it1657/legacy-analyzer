package com.legacy.api.monitoring;

import com.legacy.analysis.AnalysisLogEntry;
import com.legacy.analysis.AnalysisLogger;
import com.legacy.analysis.AnalysisSessionManager;
import com.legacy.analysis.ApiResponseWrapper;
import com.legacy.analysis.SessionState;
import com.legacy.analysis.SessionSummaryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
 * MonitoringController의 getSessionLogs/getSessionSummary를 검증한다.
 * 02-design-v1 6.3절 근거.
 */
class MonitoringControllerLogsAndSummaryTest {

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

  private Authentication ownerAuth() {
    return MonitoringTestFixtures.newAuthentication(MonitoringTestFixtures.newUser("alice"), false);
  }

  private Authentication adminAuth() {
    return MonitoringTestFixtures.newAuthentication(MonitoringTestFixtures.newUser("bob"), true);
  }

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

  /** 메시지가 "log-0" ... "log-(n-1)"인 로그 n건을 만든다. */
  private static List<AnalysisLogEntry> logs(int count) {
    List<AnalysisLogEntry> entries = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      AnalysisLogEntry entry = new AnalysisLogEntry();
      entry.setSessionId(SESSION_ID);
      entry.setMessage("log-" + i);
      entries.add(entry);
    }
    return entries;
  }

  // ---------- getSessionLogs ----------

  @Test
  void getSessionLogs는_세션이_없어도_권한체크를_건너뛰고_로그를_조회한다() {
    // 비대칭 동작: `session != null && !isOwnerOrAdmin(...)` 조건이라 세션이 없으면 체크 자체가 스킵된다.
    when(sessionManager.getSession(SESSION_ID)).thenReturn(null);
    when(analysisLogger.getSessionLogs(SESSION_ID)).thenReturn(logs(2));

    ApiResponseWrapper<List<AnalysisLogEntry>> response =
        monitoringController.getSessionLogs(SESSION_ID, 100, 0, null);

    assertTrue(response.isSuccess());
    assertThat(response.getData()).hasSize(2);
    verify(analysisLogger, times(1)).getSessionLogs(SESSION_ID);
  }

  @Test
  void getSessionLogs는_소유자에게_로그를_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());
    when(analysisLogger.getSessionLogs(SESSION_ID)).thenReturn(logs(3));

    ApiResponseWrapper<List<AnalysisLogEntry>> response =
        monitoringController.getSessionLogs(SESSION_ID, 100, 0, ownerAuth());

    assertTrue(response.isSuccess());
    assertThat(response.getData()).hasSize(3);
  }

  @Test
  void getSessionLogs는_관리자에게도_로그를_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());
    when(analysisLogger.getSessionLogs(SESSION_ID)).thenReturn(logs(3));

    ApiResponseWrapper<List<AnalysisLogEntry>> response =
        monitoringController.getSessionLogs(SESSION_ID, 100, 0, adminAuth());

    assertTrue(response.isSuccess());
    assertThat(response.getData()).hasSize(3);
  }

  @Test
  void getSessionLogs는_세션이_실존할_때_제3자에게_ACCESS_DENIED를_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());

    ApiResponseWrapper<List<AnalysisLogEntry>> response =
        monitoringController.getSessionLogs(SESSION_ID, 100, 0, strangerAuth());

    assertErrorCode(response, "ACCESS_DENIED");
    verify(analysisLogger, never()).getSessionLogs(anyString());
  }

  @Test
  void getSessionLogs는_기본_페이지네이션에서_전체_로그를_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());
    when(analysisLogger.getSessionLogs(SESSION_ID)).thenReturn(logs(10));

    ApiResponseWrapper<List<AnalysisLogEntry>> response =
        monitoringController.getSessionLogs(SESSION_ID, 100, 0, ownerAuth());

    assertThat(response.getData()).hasSize(10);
    assertEquals("log-0", response.getData().get(0).getMessage());
    assertEquals("log-9", response.getData().get(9).getMessage());
  }

  @Test
  void getSessionLogs는_limit과_offset으로_구간을_잘라_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());
    when(analysisLogger.getSessionLogs(SESSION_ID)).thenReturn(logs(10));

    ApiResponseWrapper<List<AnalysisLogEntry>> response =
        monitoringController.getSessionLogs(SESSION_ID, 3, 5, ownerAuth());

    assertThat(response.getData()).extracting(AnalysisLogEntry::getMessage)
        .containsExactly("log-5", "log-6", "log-7");
  }

  @Test
  void getSessionLogs는_offset이_전체_크기를_넘으면_예외없이_빈_리스트를_반환한다() {
    // Math.min 클램핑으로 IndexOutOfBoundsException 없이 안전하게 처리된다(특성화 포인트).
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());
    when(analysisLogger.getSessionLogs(SESSION_ID)).thenReturn(logs(10));

    ApiResponseWrapper<List<AnalysisLogEntry>> response =
        monitoringController.getSessionLogs(SESSION_ID, 100, 20, ownerAuth());

    assertTrue(response.isSuccess());
    assertThat(response.getData()).isEmpty();
  }

  @Test
  void getSessionLogs는_limit이_0이면_빈_리스트를_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());
    when(analysisLogger.getSessionLogs(SESSION_ID)).thenReturn(logs(10));

    ApiResponseWrapper<List<AnalysisLogEntry>> response =
        monitoringController.getSessionLogs(SESSION_ID, 0, 0, ownerAuth());

    assertTrue(response.isSuccess());
    assertThat(response.getData()).isEmpty();
  }

  @Test
  void getSessionLogs는_조회_중_예외가_나면_LOG_ERROR를_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());
    when(analysisLogger.getSessionLogs(SESSION_ID)).thenThrow(new RuntimeException("로그 오류"));

    ApiResponseWrapper<List<AnalysisLogEntry>> response =
        monitoringController.getSessionLogs(SESSION_ID, 100, 0, ownerAuth());

    assertErrorCode(response, "LOG_ERROR");
    assertEquals("로그 오류", response.getError().getMessage());
  }

  // ---------- getSessionSummary ----------

  @Test
  void getSessionSummary는_세션이_없으면_SESSION_NOT_FOUND를_반환한다() {
    // getSessionLogs와 달리 비대칭 그룹이 아니다(세션이 없으면 곧바로 SESSION_NOT_FOUND).
    when(sessionManager.getSession(SESSION_ID)).thenReturn(null);

    ApiResponseWrapper<SessionSummaryDto> response =
        monitoringController.getSessionSummary(SESSION_ID, ownerAuth());

    assertErrorCode(response, "SESSION_NOT_FOUND");
    assertEquals("유효하지 않은 세션 ID", response.getError().getMessage());
  }

  @Test
  void getSessionSummary는_소유자에게_세션이_생성한_요약을_그대로_반환한다() {
    SessionState session = aliceSession();
    session.setTotalFiles(10);
    session.setStatus("COMPLETED");
    session.getStatistics().setSuccessCount(7);
    session.getStatistics().setFailureCount(2);
    session.getStatistics().setSkipCount(1);
    session.getStatistics().setTotalProcessingTimeMs(4000L);
    when(sessionManager.getSession(SESSION_ID)).thenReturn(session);

    ApiResponseWrapper<SessionSummaryDto> response =
        monitoringController.getSessionSummary(SESSION_ID, ownerAuth());

    assertTrue(response.isSuccess());
    SessionSummaryDto summary = response.getData();
    assertEquals(SESSION_ID, summary.getSessionId());
    assertEquals(10, summary.getTotalFiles());
    assertEquals(7, summary.getSuccessCount());
    assertEquals(2, summary.getFailureCount());
    assertEquals(1, summary.getSkipCount());
    assertEquals(4000L, summary.getTotalProcessingTimeMs());
    assertEquals("COMPLETED", summary.getStatus());
  }

  @Test
  void getSessionSummary는_관리자에게도_요약을_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());

    ApiResponseWrapper<SessionSummaryDto> response =
        monitoringController.getSessionSummary(SESSION_ID, adminAuth());

    assertTrue(response.isSuccess());
    assertEquals(SESSION_ID, response.getData().getSessionId());
  }

  @Test
  void getSessionSummary는_제3자에게_ACCESS_DENIED를_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());

    ApiResponseWrapper<SessionSummaryDto> response =
        monitoringController.getSessionSummary(SESSION_ID, strangerAuth());

    assertErrorCode(response, "ACCESS_DENIED");
    assertEquals("본인 세션만 조회할 수 있습니다.", response.getError().getMessage());
  }

  @Test
  void getSessionSummary는_인증정보가_없으면_NPE없이_ACCESS_DENIED를_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());

    ApiResponseWrapper<SessionSummaryDto> response =
        monitoringController.getSessionSummary(SESSION_ID, null);

    assertErrorCode(response, "ACCESS_DENIED");
  }

  @Test
  void getSessionSummary는_조회_중_예외가_나면_SESSION_ERROR를_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenThrow(new RuntimeException("세션 저장소 오류"));

    ApiResponseWrapper<SessionSummaryDto> response =
        monitoringController.getSessionSummary(SESSION_ID, ownerAuth());

    assertErrorCode(response, "SESSION_ERROR");
    assertEquals("세션 저장소 오류", response.getError().getMessage());
  }

  @Test
  void getSessionLogs는_로그가_한_건도_없으면_빈_리스트를_반환한다() {
    when(sessionManager.getSession(SESSION_ID)).thenReturn(aliceSession());
    when(analysisLogger.getSessionLogs(SESSION_ID)).thenReturn(Collections.emptyList());

    ApiResponseWrapper<List<AnalysisLogEntry>> response =
        monitoringController.getSessionLogs(SESSION_ID, 100, 0, ownerAuth());

    assertTrue(response.isSuccess());
    assertThat(response.getData()).isEmpty();
  }
}
