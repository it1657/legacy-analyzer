package com.legacy.api.monitoring;

import com.legacy.analysis.AnalysisLogger;
import com.legacy.analysis.AnalysisSessionManager;
import com.legacy.analysis.ApiResponseWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MonitoringController.getSystemMetrics()를 검증한다. 02-design-v1 6.4절 근거.
 * 인증/소유자 개념이 없는 유일한 엔드포인트라 정상/예외 2케이스로 충분하다.
 */
class MonitoringControllerSystemMetricsTest {

  private PerformanceMetricsCollector metricsCollector;
  private MonitoringController monitoringController;

  @BeforeEach
  void setUp() {
    // 나머지 두 의존성은 이 엔드포인트에서 사용되지 않으므로 생성자 전달용으로만 mock을 만든다.
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    AnalysisLogger analysisLogger = mock(AnalysisLogger.class);
    metricsCollector = mock(PerformanceMetricsCollector.class);
    monitoringController = new MonitoringController(sessionManager, analysisLogger,
        metricsCollector);
  }

  @Test
  void getSystemMetrics는_수집기가_반환한_메트릭을_그대로_담아_반환한다() {
    Map<String, Object> metrics = new HashMap<>();
    metrics.put("activeSessions", 2);
    metrics.put("totalFilesProcessed", 11);
    when(metricsCollector.getSystemMetrics()).thenReturn(metrics);

    ApiResponseWrapper<Map<String, Object>> response = monitoringController.getSystemMetrics();

    assertTrue(response.isSuccess());
    assertNull(response.getError());
    assertThat(response.getData()).isSameAs(metrics);
  }

  @Test
  void getSystemMetrics는_수집_중_예외가_나면_METRICS_ERROR를_반환한다() {
    when(metricsCollector.getSystemMetrics()).thenThrow(new RuntimeException("수집 오류"));

    ApiResponseWrapper<Map<String, Object>> response = monitoringController.getSystemMetrics();

    assertFalse(response.isSuccess());
    assertNull(response.getData());
    assertEquals("METRICS_ERROR", response.getError().getCode());
    // 응답 message("메트릭 조회 실패: 수집 오류")는 ErrorInfo에는 원인 메시지만 담긴다.
    assertEquals("수집 오류", response.getError().getMessage());
  }
}
