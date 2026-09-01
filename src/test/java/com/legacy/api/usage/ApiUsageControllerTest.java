package com.legacy.api.usage;

import com.legacy.auth.User;
import com.legacy.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ApiUsageController 6개 엔드포인트의 기간 계산/응답 매핑/enrich 분기/합산 로직/예외 처리를 검증한다.
 * 02-design-v1 4.2절 근거. `@PreAuthorize`는 순수 new 호출에서는 AOP를 거치지 않으므로 검증 대상이 아니다.
 */
class ApiUsageControllerTest {

  private ApiUsageRepository apiUsageRepository;
  private UserRepository userRepository;
  private Authentication authentication;
  private ApiUsageController apiUsageController;

  @BeforeEach
  void setUp() {
    apiUsageRepository = mock(ApiUsageRepository.class);
    userRepository = mock(UserRepository.class);
    authentication = mock(Authentication.class);
    User currentUser = ApiUsageTestFixtures.newUser(7L, "alice", "alice@example.com");
    when(authentication.getPrincipal()).thenReturn(currentUser);

    apiUsageController = new ApiUsageController(apiUsageRepository, userRepository);
  }

  private static String messageOf(ResponseEntity<?> response) {
    return (String) ((Map<?, ?>) response.getBody()).get("message");
  }

  private static Map<String, Object> stat(long userId, long count) {
    Map<String, Object> map = new HashMap<>();
    map.put("user_id", userId);
    map.put("count", count);
    return map;
  }

  // ---------- getMyApiUsage ----------

  @Test
  void getMyApiUsage는_days만큼의_기간으로_내_사용량을_조회하고_9개_필드를_매핑한다() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 8, 20, 9, 30);
    ApiUsage usage = ApiUsageTestFixtures.newApiUsage(1L, 7L, "/api/analysis", "POST", 120L, 3400L,
        200, 45L, "10.0.0.1", timestamp);
    when(apiUsageRepository.findByUserIdAndTimestampBetween(eq(7L), any(LocalDateTime.class),
        any(LocalDateTime.class))).thenReturn(Collections.singletonList(usage));
    LocalDateTime beforeCall = LocalDateTime.now();

    ResponseEntity<?> response = apiUsageController.getMyApiUsage(30, authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    List<?> body = (List<?>) response.getBody();
    assertThat(body).hasSize(1);
    Map<?, ?> map = (Map<?, ?>) body.get(0);
    assertEquals(1L, map.get("id"));
    assertEquals("/api/analysis", map.get("endpoint"));
    assertEquals("POST", map.get("method"));
    assertEquals(120L, map.get("requestSize"));
    assertEquals(3400L, map.get("responseSize"));
    assertEquals(200, map.get("statusCode"));
    assertEquals(45L, map.get("executionTimeMs"));
    assertEquals(timestamp, map.get("timestamp"));
    assertEquals("10.0.0.1", map.get("ipAddress"));

    // 기간 윈도우: endTime≈now, startTime=endTime-30일 (실행 시각 오차는 초 단위로 허용)
    ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
    ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
    org.mockito.Mockito.verify(apiUsageRepository)
        .findByUserIdAndTimestampBetween(eq(7L), startCaptor.capture(), endCaptor.capture());
    assertThat(endCaptor.getValue()).isAfterOrEqualTo(beforeCall);
    assertThat(ChronoUnit.DAYS.between(startCaptor.getValue(), endCaptor.getValue())).isEqualTo(30);
  }

  @Test
  void getMyApiUsage는_조회_실패_시_400과_API_사용량_조회_실패_메시지를_반환한다() {
    when(apiUsageRepository.findByUserIdAndTimestampBetween(anyLong(), any(LocalDateTime.class),
        any(LocalDateTime.class))).thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = apiUsageController.getMyApiUsage(30, authentication);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("API 사용량 조회 실패: DB 오류", messageOf(response));
  }

  // ---------- getMyDailyStats ----------

  @Test
  void getMyDailyStats는_days_startDate_endDate_daily_stats를_담은_요약을_반환한다() {
    List<Map<String, Object>> stats = Collections.singletonList(stat(7L, 3L));
    when(apiUsageRepository.getUserDailyStats(eq(7L), any(LocalDateTime.class),
        any(LocalDateTime.class))).thenReturn(stats);

    ResponseEntity<?> response = apiUsageController.getMyDailyStats(7, authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<?, ?> body = (Map<?, ?>) response.getBody();
    assertEquals(7, body.get("days"));
    assertEquals(LocalDate.now(), body.get("endDate"));
    assertEquals(LocalDate.now().minusDays(7), body.get("startDate"));
    assertThat(body.get("daily_stats")).isSameAs(stats);
  }

  @Test
  void getMyDailyStats는_조회_실패_시_400과_일일_통계_조회_실패_메시지를_반환한다() {
    when(apiUsageRepository.getUserDailyStats(anyLong(), any(LocalDateTime.class),
        any(LocalDateTime.class))).thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = apiUsageController.getMyDailyStats(7, authentication);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("일일 통계 조회 실패: DB 오류", messageOf(response));
  }

  // ---------- getUserStats ----------

  @Test
  void getUserStats는_사용자가_존재하는_행에만_userId와_email을_추가한다() {
    List<Map<String, Object>> stats = new ArrayList<>(Arrays.asList(stat(7L, 10L), stat(8L, 4L)));
    when(apiUsageRepository.getUserApiStats(any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(stats);
    when(userRepository.findById(7L))
        .thenReturn(Optional.of(ApiUsageTestFixtures.newUser(7L, "alice", "alice@example.com")));
    when(userRepository.findById(8L)).thenReturn(Optional.empty());

    ResponseEntity<?> response = apiUsageController.getUserStats(30);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    List<?> body = (List<?>) response.getBody();
    assertThat(body).hasSize(2);
    Map<?, ?> enriched = (Map<?, ?>) body.get(0);
    assertEquals("alice", enriched.get("userId"));
    assertEquals("alice@example.com", enriched.get("email"));
    assertEquals(10L, enriched.get("count"));
    Map<?, ?> notEnriched = (Map<?, ?>) body.get(1);
    // 사용자가 없으면 키 자체가 추가되지 않는다(값이 null인 것이 아님).
    assertThat(notEnriched.containsKey("userId")).isFalse();
    assertThat(notEnriched.containsKey("email")).isFalse();
    assertEquals(4L, notEnriched.get("count"));
    // 원본 stat Map은 복사본(new HashMap<>(stat))으로 처리되므로 변형되지 않는다.
    assertThat(stats.get(0)).doesNotContainKey("userId");
  }

  @Test
  void getUserStats는_조회_실패_시_400과_사용자_통계_조회_실패_메시지를_반환한다() {
    when(apiUsageRepository.getUserApiStats(any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = apiUsageController.getUserStats(30);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("사용자 통계 조회 실패: DB 오류", messageOf(response));
  }

  // ---------- getEndpointStats ----------

  @Test
  void getEndpointStats는_리포지토리_결과를_그대로_반환한다() {
    List<Map<String, Object>> stats = Collections.singletonList(stat(7L, 2L));
    when(apiUsageRepository.getEndpointStats(any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(stats);

    ResponseEntity<?> response = apiUsageController.getEndpointStats(30);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertThat(response.getBody()).isSameAs(stats);
  }

  @Test
  void getEndpointStats는_조회_실패_시_400과_엔드포인트_통계_조회_실패_메시지를_반환한다() {
    when(apiUsageRepository.getEndpointStats(any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = apiUsageController.getEndpointStats(30);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("엔드포인트 통계 조회 실패: DB 오류", messageOf(response));
  }

  // ---------- getUserDetailedUsage ----------

  @Test
  void getUserDetailedUsage는_인증사용자가_아니라_경로변수_userId로_조회한다() {
    // 인증 principal(seq=7L)과 무관하게 @PathVariable userId(99L)를 그대로 사용한다.
    ApiUsage usage = ApiUsageTestFixtures.newApiUsage(2L, 99L, "/admin/users", "GET", 0L, 512L, 200,
        12L, "10.0.0.2", LocalDateTime.of(2026, 8, 21, 8, 0));
    when(apiUsageRepository.findByUserIdAndTimestampBetween(eq(99L), any(LocalDateTime.class),
        any(LocalDateTime.class))).thenReturn(Collections.singletonList(usage));

    ResponseEntity<?> response = apiUsageController.getUserDetailedUsage(99L, 30);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    List<?> body = (List<?>) response.getBody();
    assertThat(body).hasSize(1);
    Map<?, ?> map = (Map<?, ?>) body.get(0);
    assertEquals(2L, map.get("id"));
    assertEquals("/admin/users", map.get("endpoint"));
    assertEquals("GET", map.get("method"));
    assertEquals(512L, map.get("responseSize"));
    assertEquals("10.0.0.2", map.get("ipAddress"));
  }

  @Test
  void getUserDetailedUsage는_조회_실패_시_400과_사용자_상세_사용량_조회_실패_메시지를_반환한다() {
    when(apiUsageRepository.findByUserIdAndTimestampBetween(anyLong(), any(LocalDateTime.class),
        any(LocalDateTime.class))).thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = apiUsageController.getUserDetailedUsage(99L, 30);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("사용자 상세 사용량 조회 실패: DB 오류", messageOf(response));
  }

  // ---------- getUsageSummary ----------

  @Test
  void getUsageSummary는_사용량이_없으면_요청수_0과_평균실행시간_0을_반환한다() {
    when(apiUsageRepository.findByTimestampBetween(any(LocalDateTime.class),
        any(LocalDateTime.class))).thenReturn(Collections.emptyList());
    when(apiUsageRepository.getUserApiStats(any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(Collections.emptyList());

    ResponseEntity<?> response = apiUsageController.getUsageSummary(30);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<?, ?> body = (Map<?, ?>) response.getBody();
    assertEquals(30, body.get("period_days"));
    assertEquals(0, body.get("total_requests"));
    assertEquals(0L, body.get("total_request_bytes"));
    assertEquals(0L, body.get("total_response_bytes"));
    // 특성화 포인트: 삼항 연산자 `isEmpty() ? 0 : ...average()`의 두 피연산자(int / double)가
    // 이항 수치 승격으로 double이 되므로, 빈 리스트여도 Integer 0이 아니라 Double 0.0이 담긴다.
    assertThat(body.get("avg_execution_time_ms")).isInstanceOf(Double.class);
    assertEquals(0.0, (Double) body.get("avg_execution_time_ms"), 0.0001);
  }

  @Test
  void getUsageSummary는_요청_바이트_합계와_평균_실행시간을_계산한다() {
    List<ApiUsage> usages = Arrays.asList(
        ApiUsageTestFixtures.newApiUsage(1L, 7L, "/api/a", "GET", 100L, 1000L, 200, 10L, "ip",
            LocalDateTime.now()),
        ApiUsageTestFixtures.newApiUsage(2L, 8L, "/api/b", "POST", 200L, 2000L, 201, 30L, "ip",
            LocalDateTime.now()),
        ApiUsageTestFixtures.newApiUsage(3L, 8L, "/api/c", "GET", 300L, 3000L, 500, 50L, "ip",
            LocalDateTime.now()));
    List<Map<String, Object>> userStats = Collections.singletonList(stat(8L, 2L));
    when(apiUsageRepository.findByTimestampBetween(any(LocalDateTime.class),
        any(LocalDateTime.class))).thenReturn(usages);
    when(apiUsageRepository.getUserApiStats(any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(userStats);

    ResponseEntity<?> response = apiUsageController.getUsageSummary(30);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<?, ?> body = (Map<?, ?>) response.getBody();
    assertEquals(3, body.get("total_requests"));
    assertEquals(600L, body.get("total_request_bytes"));
    assertEquals(6000L, body.get("total_response_bytes"));
    assertEquals(30.0, (Double) body.get("avg_execution_time_ms"), 0.0001);
    assertEquals(LocalDate.now(), body.get("end_date"));
    assertEquals(LocalDate.now().minusDays(30), body.get("start_date"));
    assertThat(body.get("user_stats")).isSameAs(userStats);
  }

  @Test
  void getUsageSummary는_조회_실패_시_400과_API_사용량_요약_조회_실패_메시지를_반환한다() {
    when(apiUsageRepository.findByTimestampBetween(any(LocalDateTime.class),
        any(LocalDateTime.class))).thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = apiUsageController.getUsageSummary(30);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("API 사용량 요약 조회 실패: DB 오류", messageOf(response));
  }
}
