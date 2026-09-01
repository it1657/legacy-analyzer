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
 * <p>getPerformanceStatistics는 null 방어가 없는 메서드 참조(`AnalysisHistory::getProcessingTimeMs`,
 * `AnalysisHistory::getTotalFiles`)를 mapToLong에 넘기므로, 해당 필드가 null이면 언박싱 지점에서
 * NPE가 발생할 수 있다(NPE 관찰 #1). 아래 관찰 케이스 A/B는 실제 실행 결과를 있는 그대로 기록한 것이며,
 * 정상/버그 여부는 이 사이클에서 확정하지 않는다(게이트2에서 사람이 판단).
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
  void 관찰케이스A_getPerformanceStatistics는_processingTimeMs가_null이_섞이면_500을_반환한다() {
    // 관찰 목적 테스트(정상/버그 판단 아님, 게이트2 판단 대상).
    // 실측 결과(2026-09-01): `mapToLong(AnalysisHistory::getProcessingTimeMs)` 언박싱 지점에서
    // NullPointerException이 실제로 발생했고, 메서드 전체를 감싼 try-catch가 이를 잡아
    // HTTP 500 + message = "성능 통계 조회 실패: null"을 반환했다
    // (NPE의 getMessage()가 null이라 문자열 연결 결과가 "...실패: null"이 된다).
    // 이 케이스는 totalFiles가 전부 non-null이므로 NPE 발생 지점은 processingTimeMs 파이프라인이다.
    when(analysisHistoryRepository.findAll()).thenReturn(Arrays.asList(
        StatisticsTestFixtures.newAnalysisHistory(1L, 1L, "COMPLETED", 4, 1000L, 1L, 1L),
        StatisticsTestFixtures.newAnalysisHistory(2L, 1L, "COMPLETED", 7, null, 1L, 1L)));

    ResponseEntity<?> response = statisticsController.getPerformanceStatistics();

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertEquals("성능 통계 조회 실패: null", bodyOf(response).get("message"));
  }

  @Test
  void 관찰케이스B_getPerformanceStatistics는_totalFiles가_null이_섞이면_500을_반환한다() {
    // 관찰 목적 테스트. processingTimeMs와는 별개의 스트림 파이프라인(`AnalysisHistory::getTotalFiles`)에서
    // 독립적으로 언박싱 NPE가 발생하는지 확인한다(케이스 A와 분리해서 관찰).
    // 실측 결과(2026-09-01): processingTimeMs는 전부 non-null이라 avg/min/max 계산은 정상 통과했고,
    // totalFiles 평균 계산 지점에서 NullPointerException이 발생해 케이스 A와 동일하게
    // HTTP 500 + message = "성능 통계 조회 실패: null"이 반환됐다.
    when(analysisHistoryRepository.findAll()).thenReturn(Arrays.asList(
        StatisticsTestFixtures.newAnalysisHistory(1L, 1L, "COMPLETED", 4, 1000L, 1L, 1L),
        StatisticsTestFixtures.newAnalysisHistory(2L, 1L, "COMPLETED", null, 3000L, 1L, 1L)));

    ResponseEntity<?> response = statisticsController.getPerformanceStatistics();

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertEquals("성능 통계 조회 실패: null", bodyOf(response).get("message"));
  }

  @Test
  void getPerformanceStatistics는_조회_실패_시_500과_성능_통계_조회_실패_메시지를_반환한다() {
    when(analysisHistoryRepository.findAll()).thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = statisticsController.getPerformanceStatistics();

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertEquals("성능 통계 조회 실패: DB 오류", bodyOf(response).get("message"));
  }
}
