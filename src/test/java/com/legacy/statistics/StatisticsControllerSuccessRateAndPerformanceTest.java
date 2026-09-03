package com.legacy.statistics;

import com.legacy.analysis.AnalysisHistoryRepository;
import com.legacy.api.usage.ApiUsageRepository;
import com.legacy.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * StatisticsController.getSuccessRateStatistics()/getPerformanceStatistics()를 검증한다.
 * 02-design-v1 5.3절 근거.
 *
 * <p>getPerformanceStatistics는 2026-09-remaining-bugfixes 사이클(REQ-004)에서 언박싱 NPE가 수정되어,
 * `processingTimeMs`/`totalFiles`가 null인 이력을 0으로 취급해 정상 계산한다. 기존 관찰 케이스 A/B는
 * 수정 후의 확정 동작(200 OK + null을 0으로 취급한 계산 결과)을 검증하도록 갱신됐다.
 */
class StatisticsControllerSuccessRateAndPerformanceTest {

  private UserRepository userRepository;
  private AnalysisHistoryRepository analysisHistoryRepository;
  private ApiUsageRepository apiUsageRepository;
  private StatisticsController statisticsController;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    analysisHistoryRepository = mock(AnalysisHistoryRepository.class);
    apiUsageRepository = mock(ApiUsageRepository.class);
    statisticsController = new StatisticsController(userRepository, analysisHistoryRepository,
        apiUsageRepository);
  }

  private static Map<?, ?> bodyOf(ResponseEntity<?> response) {
    return (Map<?, ?>) response.getBody();
  }

  // ---------- getSuccessRateStatistics ----------

  @Test
  void getSuccessRateStatistics는_성공률과_실패율을_백분율로_계산한다() {
    when(analysisHistoryRepository.findAll()).thenReturn(Arrays.asList(
        StatisticsTestFixtures.newAnalysisHistory(1L, 1L, "COMPLETED", 1, 1L, 1L, 1L),
        StatisticsTestFixtures.newAnalysisHistory(2L, 1L, "COMPLETED", 1, 1L, 1L, 1L),
        StatisticsTestFixtures.newAnalysisHistory(3L, 1L, "FAILED", 1, 1L, 1L, 1L),
        StatisticsTestFixtures.newAnalysisHistory(4L, 1L, "IN_PROGRESS", 1, 1L, 1L, 1L)));

    ResponseEntity<?> response = statisticsController.getSuccessRateStatistics();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<?, ?> body = bodyOf(response);
    assertEquals(4L, body.get("total"));
    assertEquals(2L, body.get("success"));
    assertEquals(1L, body.get("failed"));
    assertEquals(50.0, (Double) body.get("success_rate"), 0.0001);
    assertEquals(25.0, (Double) body.get("failure_rate"), 0.0001);
  }

  @Test
  void getSuccessRateStatistics는_분석이_한_건도_없으면_비율을_0으로_반환한다() {
    // total==0이면 삼항 연산자 방어로 NaN이 아니라 0이 담긴다.
    when(analysisHistoryRepository.findAll()).thenReturn(Collections.emptyList());

    ResponseEntity<?> response = statisticsController.getSuccessRateStatistics();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<?, ?> body = bodyOf(response);
    assertEquals(0L, body.get("total"));
    assertEquals(0.0, ((Number) body.get("success_rate")).doubleValue(), 0.0001);
    assertEquals(0.0, ((Number) body.get("failure_rate")).doubleValue(), 0.0001);
  }

  @Test
  void getSuccessRateStatistics는_조회_실패_시_500과_성공률_통계_조회_실패_메시지를_반환한다() {
    when(analysisHistoryRepository.findAll()).thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = statisticsController.getSuccessRateStatistics();

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertEquals("성공률 통계 조회 실패: DB 오류", bodyOf(response).get("message"));
  }

  // ---------- getPerformanceStatistics ----------

  @Test
  void getPerformanceStatistics는_분석이_없으면_모든_통계를_0으로_반환한다() {
    when(analysisHistoryRepository.findAll()).thenReturn(Collections.emptyList());

    ResponseEntity<?> response = statisticsController.getPerformanceStatistics();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<?, ?> body = bodyOf(response);
    assertEquals(0L, body.get("avg_processing_time_ms"));
    assertEquals(0L, body.get("min_processing_time_ms"));
    assertEquals(0L, body.get("max_processing_time_ms"));
    assertEquals(0.0, ((Number) body.get("avg_files_per_analysis")).doubleValue(), 0.0001);
    assertEquals(0, body.get("total_analyses"));
  }

  @Test
  void getPerformanceStatistics는_평균_최소_최대_처리시간과_평균_파일수를_계산한다() {
    when(analysisHistoryRepository.findAll()).thenReturn(Arrays.asList(
        StatisticsTestFixtures.newAnalysisHistory(1L, 1L, "COMPLETED", 4, 1000L, 1L, 1L),
        StatisticsTestFixtures.newAnalysisHistory(2L, 1L, "COMPLETED", 7, 3000L, 1L, 1L)));

    ResponseEntity<?> response = statisticsController.getPerformanceStatistics();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<?, ?> body = bodyOf(response);
    assertEquals(2000L, body.get("avg_processing_time_ms")); // Math.round(2000.0)
    assertEquals(1000L, body.get("min_processing_time_ms"));
    assertEquals(3000L, body.get("max_processing_time_ms"));
    // Math.round(5.5 * 100) / 100.0 == 5.5
    assertEquals(5.5, ((Number) body.get("avg_files_per_analysis")).doubleValue(), 0.0001);
    assertEquals(2, body.get("total_analyses"));
  }

  @Test
  void getPerformanceStatistics는_processingTimeMs가_null이_섞여도_0으로_처리해_정상_계산한다() {
    // REQ-004 수정 후 확정 동작: null인 processingTimeMs는 0으로 취급하고 계산에 포함한다(제외가 아님).
    // 픽스처: processingTimeMs = [1000, null→0], totalFiles = [4, 7]
    //  - avg  = (1000 + 0) / 2 = 500.0        → Math.round(500.0) = 500
    //  - min  = min(1000, 0)  = 0
    //  - max  = max(1000, 0)  = 1000
    //  - avg_files = (4 + 7) / 2 = 5.5        → Math.round(5.5 * 100) / 100.0 = 5.5
    when(analysisHistoryRepository.findAll()).thenReturn(Arrays.asList(
        StatisticsTestFixtures.newAnalysisHistory(1L, 1L, "COMPLETED", 4, 1000L, 1L, 1L),
        StatisticsTestFixtures.newAnalysisHistory(2L, 1L, "COMPLETED", 7, null, 1L, 1L)));

    ResponseEntity<?> response = statisticsController.getPerformanceStatistics();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<?, ?> body = bodyOf(response);
    assertEquals(500L, body.get("avg_processing_time_ms"));
    assertEquals(0L, body.get("min_processing_time_ms"));
    assertEquals(1000L, body.get("max_processing_time_ms"));
    assertEquals(5.5, ((Number) body.get("avg_files_per_analysis")).doubleValue(), 0.0001);
    assertEquals(2, body.get("total_analyses"));
  }

  @Test
  void getPerformanceStatistics는_totalFiles가_null이_섞여도_0으로_처리해_정상_계산한다() {
    // REQ-004 수정 후 확정 동작: processingTimeMs와 별개 파이프라인인 totalFiles도 null을 0으로 취급한다.
    // 픽스처: processingTimeMs = [1000, 3000], totalFiles = [4, null→0]
    //  - avg  = (1000 + 3000) / 2 = 2000.0    → Math.round(2000.0) = 2000
    //  - min  = 1000, max = 3000 (processingTimeMs는 전부 non-null이라 기존 계산과 동일)
    //  - avg_files = (4 + 0) / 2 = 2.0        → Math.round(2.0 * 100) / 100.0 = 2.0
    when(analysisHistoryRepository.findAll()).thenReturn(Arrays.asList(
        StatisticsTestFixtures.newAnalysisHistory(1L, 1L, "COMPLETED", 4, 1000L, 1L, 1L),
        StatisticsTestFixtures.newAnalysisHistory(2L, 1L, "COMPLETED", null, 3000L, 1L, 1L)));

    ResponseEntity<?> response = statisticsController.getPerformanceStatistics();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<?, ?> body = bodyOf(response);
    assertEquals(2000L, body.get("avg_processing_time_ms"));
    assertEquals(1000L, body.get("min_processing_time_ms"));
    assertEquals(3000L, body.get("max_processing_time_ms"));
    assertEquals(2.0, ((Number) body.get("avg_files_per_analysis")).doubleValue(), 0.0001);
    assertEquals(2, body.get("total_analyses"));
  }

  @Test
  void getPerformanceStatistics는_조회_실패_시_500과_성능_통계_조회_실패_메시지를_반환한다() {
    when(analysisHistoryRepository.findAll()).thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = statisticsController.getPerformanceStatistics();

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertEquals("성능 통계 조회 실패: DB 오류", bodyOf(response).get("message"));
  }
}
