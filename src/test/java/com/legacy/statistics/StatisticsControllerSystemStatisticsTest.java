package com.legacy.statistics;

import com.legacy.analysis.AnalysisHistory;
import com.legacy.analysis.AnalysisHistoryRepository;
import com.legacy.api.usage.ApiUsage;
import com.legacy.api.usage.ApiUsageRepository;
import com.legacy.auth.User;
import com.legacy.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * StatisticsController.getSystemStatistics()/getUserStatistics()의 집계·null 방어·정렬·enrich 분기를 검증한다.
 * 02-design-v1 5.2절 근거. `@PreAuthorize`는 순수 new 호출에서 AOP를 거치지 않으므로 검증 대상이 아니다.
 */
class StatisticsControllerSystemStatisticsTest {

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

  private static String messageOf(ResponseEntity<?> response) {
    return (String) ((Map<?, ?>) response.getBody()).get("message");
  }

  private SystemStatisticsDto callSystemStatistics() {
    ResponseEntity<?> response = statisticsController.getSystemStatistics();
    assertEquals(HttpStatus.OK, response.getStatusCode());
    return (SystemStatisticsDto) response.getBody();
  }

  // ---------- getSystemStatistics ----------

  @Test
  void getSystemStatistics는_전체_사용자수와_활성_사용자수를_집계한다() {
    when(userRepository.findAll()).thenReturn(Arrays.asList(
        StatisticsTestFixtures.newUser(1L, "u1", "u1@example.com", true),
        StatisticsTestFixtures.newUser(2L, "u2", "u2@example.com", true),
        StatisticsTestFixtures.newUser(3L, "u3", "u3@example.com", false),
        StatisticsTestFixtures.newUser(4L, "u4", "u4@example.com", false)));

    SystemStatisticsDto stats = callSystemStatistics();

    assertEquals(4L, stats.getTotalUsers());
    assertEquals(2L, stats.getActiveUsers());
  }

  @Test
  void getSystemStatistics는_상태별_분석_건수를_집계하고_그_외_상태는_skip으로_계산한다() {
    when(analysisHistoryRepository.findAll()).thenReturn(Arrays.asList(
        StatisticsTestFixtures.newAnalysisHistory(1L, 1L, "COMPLETED", 10, 1000L, 100L, 50L),
        StatisticsTestFixtures.newAnalysisHistory(2L, 1L, "COMPLETED", 20, 2000L, 200L, 100L),
        StatisticsTestFixtures.newAnalysisHistory(3L, 2L, "FAILED", 5, 500L, 10L, 5L),
        StatisticsTestFixtures.newAnalysisHistory(4L, 2L, "IN_PROGRESS", 1, 100L, 1L, 1L)));

    SystemStatisticsDto stats = callSystemStatistics();

    assertEquals(4L, stats.getTotalAnalysis());
    assertEquals(2L, stats.getSuccessAnalysis());
    assertEquals(1L, stats.getFailureAnalysis());
    assertEquals(1L, stats.getSkipAnalysis()); // size - success - failure
  }

  @Test
  void getSystemStatistics는_상태별_분포_맵에_COMPLETED_FAILED_OTHER를_담는다() {
    when(analysisHistoryRepository.findAll()).thenReturn(Arrays.asList(
        StatisticsTestFixtures.newAnalysisHistory(1L, 1L, "COMPLETED", 1, 1L, 1L, 1L),
        StatisticsTestFixtures.newAnalysisHistory(2L, 1L, "FAILED", 1, 1L, 1L, 1L),
        StatisticsTestFixtures.newAnalysisHistory(3L, 1L, "IN_PROGRESS", 1, 1L, 1L, 1L)));

    SystemStatisticsDto stats = callSystemStatistics();

    Map<String, Long> distribution = stats.getAnalysisStatusDistribution();
    assertEquals(1L, distribution.get("COMPLETED"));
    assertEquals(1L, distribution.get("FAILED"));
    assertEquals(1L, distribution.get("OTHER"));
  }

  @Test
  void getSystemStatistics는_totalFiles와_processingTimeMs가_null이어도_0으로_처리해_NPE가_나지_않는다() {
    // 이 두 필드는 mapToLong 람다 안에 개별 null 방어(!= null ? ... : 0L)가 있어 getPerformanceStatistics와 대조된다.
    when(analysisHistoryRepository.findAll()).thenReturn(Arrays.asList(
        StatisticsTestFixtures.newAnalysisHistory(1L, 1L, "COMPLETED", 10, 1000L, 1L, 1L),
        StatisticsTestFixtures.newAnalysisHistory(2L, 1L, "COMPLETED", null, null, 1L, 1L)));

    SystemStatisticsDto stats = callSystemStatistics();

    assertEquals(10L, stats.getTotalFilesAnalyzed());
    assertEquals(1000L, stats.getTotalProcessingTimeMs());
    assertEquals(500.0, stats.getAvgProcessingTimeMs(), 0.0001); // calculateMetrics: 1000 / 2건
  }

  @Test
  void getSystemStatistics는_API_요청수와_요청응답_바이트_합계를_집계한다() {
    List<ApiUsage> usages = Arrays.asList(
        StatisticsTestFixtures.newApiUsage(1L, 100L, 1000L),
        StatisticsTestFixtures.newApiUsage(2L, 200L, 2000L));
    when(apiUsageRepository.findAll()).thenReturn(usages);

    SystemStatisticsDto stats = callSystemStatistics();

    assertEquals(2L, stats.getTotalApiRequests());
    assertEquals(3300L, stats.getTotalDataProcessedBytes());
    assertEquals(3300.0 / 2 / 1024, stats.getAvgDataPerRequest(), 0.0001);
  }

  @Test
  void getSystemStatistics는_토큰_집계가_null이면_0으로_처리한다() {
    // 리포지토리 토큰 집계 메서드를 stub하지 않으면 Mockito 기본값 null이 반환된다.
    SystemStatisticsDto stats = callSystemStatistics();

    assertEquals(0L, stats.getTotalInputTokens());
    assertEquals(0L, stats.getTotalOutputTokens());
    assertEquals(0L, stats.getTotalTokens());
    assertEquals(0.0, stats.getTotalApiCost(), 0.0001);
  }

  @Test
  void getSystemStatistics는_토큰_집계가_존재하면_합산값을_그대로_반영한다() {
    when(analysisHistoryRepository.getTotalInputTokensSystem()).thenReturn(1500L);
    when(analysisHistoryRepository.getTotalOutputTokensSystem()).thenReturn(700L);
    when(analysisHistoryRepository.getTotalCostSystem()).thenReturn(3.25);

    SystemStatisticsDto stats = callSystemStatistics();

    assertEquals(1500L, stats.getTotalInputTokens());
    assertEquals(700L, stats.getTotalOutputTokens());
    assertEquals(2200L, stats.getTotalTokens());
    assertEquals(3.25, stats.getTotalApiCost(), 0.0001);
  }

  @Test
  void getSystemStatistics는_모델명이나_값이_null인_모델별_집계_행을_건너뛴다() {
    when(analysisHistoryRepository.getTokensByModel()).thenReturn(Arrays.asList(
        new Object[] {"gpt-4", 1000L},
        new Object[] {null, 500L},
        new Object[] {"claude", null}));
    when(analysisHistoryRepository.getCostByModel()).thenReturn(Arrays.asList(
        new Object[] {"gpt-4", 1.5},
        new Object[] {null, 2.5}));

    SystemStatisticsDto stats = callSystemStatistics();

    assertThat(stats.getTokensByModel()).containsOnlyKeys("gpt-4");
    assertEquals(1000L, stats.getTokensByModel().get("gpt-4"));
    assertThat(stats.getCostByModel()).containsOnlyKeys("gpt-4");
    assertEquals(1.5, stats.getCostByModel().get("gpt-4"), 0.0001);
  }

  @Test
  void getSystemStatistics는_상위_사용자를_분석수_내림차순_5명까지만_반환한다() {
    List<AnalysisHistory> analyses = new ArrayList<>();
    long id = 1L;
    // userSeq 1..6에 대해 각각 1..6건을 만든다(6번 사용자가 최다).
    for (long userSeq = 1; userSeq <= 6; userSeq++) {
      for (long i = 0; i < userSeq; i++) {
        analyses.add(StatisticsTestFixtures.newAnalysisHistory(id++, userSeq, "COMPLETED", 1, 1L,
            1L, 1L));
      }
    }
    when(analysisHistoryRepository.findAll()).thenReturn(analyses);
    when(userRepository.findById(anyLong())).thenReturn(Optional.empty());

    SystemStatisticsDto stats = callSystemStatistics();

    List<Map<String, Object>> topUsers = stats.getTopUsers();
    assertThat(topUsers).hasSize(5);
    assertThat(topUsers).extracting(m -> m.get("analysisCount"))
        .containsExactly(6L, 5L, 4L, 3L, 2L);
    assertThat(topUsers).extracting(m -> m.get("userSeq")).containsExactly(6L, 5L, 4L, 3L, 2L);
  }

  @Test
  void getSystemStatistics의_상위_사용자는_사용자가_존재할_때만_userId_키를_추가한다() {
    when(analysisHistoryRepository.findAll()).thenReturn(Arrays.asList(
        StatisticsTestFixtures.newAnalysisHistory(1L, 1L, "COMPLETED", 1, 1L, 1L, 1L),
        StatisticsTestFixtures.newAnalysisHistory(2L, 1L, "COMPLETED", 1, 1L, 1L, 1L),
        StatisticsTestFixtures.newAnalysisHistory(3L, 2L, "COMPLETED", 1, 1L, 1L, 1L)));
    when(userRepository.findById(1L))
        .thenReturn(Optional.of(StatisticsTestFixtures.newUser(1L, "alice", "a@example.com", true)));
    when(userRepository.findById(2L)).thenReturn(Optional.empty());

    SystemStatisticsDto stats = callSystemStatistics();

    List<Map<String, Object>> topUsers = stats.getTopUsers();
    assertThat(topUsers).hasSize(2);
    assertEquals("alice", topUsers.get(0).get("userId"));
    assertEquals(2L, topUsers.get(0).get("analysisCount"));
    // 사용자가 없으면 userId 키 자체가 추가되지 않는다(값이 null인 것이 아님).
    assertThat(topUsers.get(1).containsKey("userId")).isFalse();
    assertEquals(2L, topUsers.get(1).get("userSeq"));
  }

  @Test
  void getSystemStatistics는_조회_실패_시_500과_시스템_통계_조회_실패_메시지를_반환한다() {
    when(userRepository.findAll()).thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = statisticsController.getSystemStatistics();

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertEquals("시스템 통계 조회 실패: DB 오류", messageOf(response));
  }

  // ---------- getUserStatistics ----------

  @Test
  void getUserStatistics는_사용자별_분석_상태와_파일_처리시간을_집계한다() {
    User alice = StatisticsTestFixtures.newUser(1L, "alice", "a@example.com", true);
    when(userRepository.findAll()).thenReturn(Collections.singletonList(alice));
    when(analysisHistoryRepository.findByUserId(1L)).thenReturn(Arrays.asList(
        StatisticsTestFixtures.newAnalysisHistory(1L, 1L, "COMPLETED", 10, 1000L, 1L, 1L),
        StatisticsTestFixtures.newAnalysisHistory(2L, 1L, "FAILED", 5, 500L, 1L, 1L),
        StatisticsTestFixtures.newAnalysisHistory(3L, 1L, "IN_PROGRESS", null, null, 1L, 1L)));

    ResponseEntity<?> response = statisticsController.getUserStatistics();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    List<?> body = (List<?>) response.getBody();
    UserStatisticsDto stats = (UserStatisticsDto) body.get(0);
    assertEquals(1L, stats.getSeq());
    assertEquals("alice", stats.getUserId());
    assertEquals("a@example.com", stats.getEmail());
    assertEquals(3L, stats.getTotalAnalysis());
    assertEquals(1L, stats.getSuccessAnalysis());
    assertEquals(1L, stats.getFailureAnalysis());
    assertEquals(1L, stats.getSkipAnalysis());
    assertEquals(15L, stats.getTotalFilesAnalyzed());   // null은 0으로 방어됨
    assertEquals(1500L, stats.getTotalProcessingTimeMs());
  }

  @Test
  void getUserStatistics는_사용자별_API_사용량과_토큰_집계를_반영하고_null은_0으로_처리한다() {
    User alice = StatisticsTestFixtures.newUser(1L, "alice", "a@example.com", true);
    User bob = StatisticsTestFixtures.newUser(2L, "bob", "b@example.com", true);
    when(userRepository.findAll()).thenReturn(Arrays.asList(alice, bob));
    when(apiUsageRepository.findByUserId(1L)).thenReturn(Arrays.asList(
        StatisticsTestFixtures.newApiUsage(1L, 100L, 900L),
        StatisticsTestFixtures.newApiUsage(1L, 50L, 50L)));
    when(analysisHistoryRepository.getTotalInputTokensByUser(1L)).thenReturn(300L);
    when(analysisHistoryRepository.getTotalOutputTokensByUser(1L)).thenReturn(200L);
    when(analysisHistoryRepository.getTotalCostByUser(1L)).thenReturn(0.75);
    // bob은 stub하지 않아 전부 null이 반환된다.

    ResponseEntity<?> response = statisticsController.getUserStatistics();

    List<?> body = (List<?>) response.getBody();
    UserStatisticsDto aliceStats = (UserStatisticsDto) body.stream()
        .map(UserStatisticsDto.class::cast).filter(s -> s.getSeq() == 1L).findFirst().orElseThrow();
    UserStatisticsDto bobStats = (UserStatisticsDto) body.stream()
        .map(UserStatisticsDto.class::cast).filter(s -> s.getSeq() == 2L).findFirst().orElseThrow();
    assertEquals(2L, aliceStats.getTotalApiRequests());
    assertEquals(1100L, aliceStats.getTotalDataProcessedBytes());
    assertEquals(300L, aliceStats.getTotalInputTokens());
    assertEquals(200L, aliceStats.getTotalOutputTokens());
    assertEquals(500L, aliceStats.getTotalTokens());
    assertEquals(0.75, aliceStats.getTotalApiCost(), 0.0001);
    assertEquals(0L, bobStats.getTotalApiRequests());
    assertEquals(0L, bobStats.getTotalInputTokens());
    assertEquals(0L, bobStats.getTotalTokens());
    assertEquals(0.0, bobStats.getTotalApiCost(), 0.0001);
  }

  @Test
  void getUserStatistics는_분석_건수_내림차순으로_정렬한다() {
    User alice = StatisticsTestFixtures.newUser(1L, "alice", "a@example.com", true);
    User bob = StatisticsTestFixtures.newUser(2L, "bob", "b@example.com", true);
    User carol = StatisticsTestFixtures.newUser(3L, "carol", "c@example.com", true);
    when(userRepository.findAll()).thenReturn(Arrays.asList(alice, bob, carol));
    when(analysisHistoryRepository.findByUserId(1L)).thenReturn(Collections.singletonList(
        StatisticsTestFixtures.newAnalysisHistory(1L, 1L, "COMPLETED", 1, 1L, 1L, 1L)));
    when(analysisHistoryRepository.findByUserId(2L)).thenReturn(Arrays.asList(
        StatisticsTestFixtures.newAnalysisHistory(2L, 2L, "COMPLETED", 1, 1L, 1L, 1L),
        StatisticsTestFixtures.newAnalysisHistory(3L, 2L, "FAILED", 1, 1L, 1L, 1L),
        StatisticsTestFixtures.newAnalysisHistory(4L, 2L, "COMPLETED", 1, 1L, 1L, 1L)));
    when(analysisHistoryRepository.findByUserId(3L)).thenReturn(Arrays.asList(
        StatisticsTestFixtures.newAnalysisHistory(5L, 3L, "COMPLETED", 1, 1L, 1L, 1L),
        StatisticsTestFixtures.newAnalysisHistory(6L, 3L, "COMPLETED", 1, 1L, 1L, 1L)));

    ResponseEntity<?> response = statisticsController.getUserStatistics();

    List<?> body = (List<?>) response.getBody();
    assertThat(body).extracting(s -> ((UserStatisticsDto) s).getUserId())
        .containsExactly("bob", "carol", "alice");
    assertThat(body).extracting(s -> ((UserStatisticsDto) s).getTotalAnalysis())
        .containsExactly(3L, 2L, 1L);
  }

  @Test
  void getUserStatistics는_성공률_평균처리시간_등_파생값을_계산한다() {
    User alice = StatisticsTestFixtures.newUser(1L, "alice", "a@example.com", true);
    when(userRepository.findAll()).thenReturn(Collections.singletonList(alice));
    when(analysisHistoryRepository.findByUserId(1L)).thenReturn(Arrays.asList(
        StatisticsTestFixtures.newAnalysisHistory(1L, 1L, "COMPLETED", 4, 1000L, 1L, 1L),
        StatisticsTestFixtures.newAnalysisHistory(2L, 1L, "FAILED", 6, 3000L, 1L, 1L)));

    ResponseEntity<?> response = statisticsController.getUserStatistics();

    UserStatisticsDto stats = (UserStatisticsDto) ((List<?>) response.getBody()).get(0);
    assertEquals(50.0, stats.getSuccessRate(), 0.0001);
    assertEquals(2000.0, stats.getAvgProcessingTimeMs(), 0.0001);
  }

  @Test
  void getUserStatistics는_조회_실패_시_500과_사용자_통계_조회_실패_메시지를_반환한다() {
    when(userRepository.findAll()).thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = statisticsController.getUserStatistics();

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertEquals("사용자 통계 조회 실패: DB 오류", messageOf(response));
  }
}
