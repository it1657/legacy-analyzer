package com.legacy.api.usage;
import com.legacy.auth.UserRepository;
import com.legacy.auth.User;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController
@RequestMapping("/api/usage")
public class ApiUsageController {

  private static final Logger log = LoggerFactory.getLogger(ApiUsageController.class);

  private final ApiUsageRepository apiUsageRepository;
  private final UserRepository userRepository;

  @Autowired
  public ApiUsageController(ApiUsageRepository apiUsageRepository,
      UserRepository userRepository) {
    this.apiUsageRepository = apiUsageRepository;
    this.userRepository = userRepository;
  }

  // 현재 사용자의 API 사용량 조회
  @GetMapping("/my-usage")
  @ResponseBody
  public ResponseEntity<?> getMyApiUsage(
      @RequestParam(defaultValue = "30") int days,
      Authentication authentication) {
    try {
      User user = (User) authentication.getPrincipal();
      LocalDateTime endTime = LocalDateTime.now();
      LocalDateTime startTime = endTime.minusDays(days);

      List<ApiUsage> usages = apiUsageRepository
          .findByUserIdAndTimestampBetween(user.getId(), startTime, endTime);

      List<Map<String, Object>> response = usages.stream()
          .map(this::convertToMap)
          .toList();

      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("[API 사용량 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "API 사용량 조회 실패: " + e.getMessage()));
    }
  }

  // 현재 사용자의 일일 통계
  @GetMapping("/my-daily-stats")
  @ResponseBody
  public ResponseEntity<?> getMyDailyStats(
      @RequestParam(defaultValue = "30") int days,
      Authentication authentication) {
    try {
      User user = (User) authentication.getPrincipal();
      LocalDateTime endTime = LocalDateTime.now();
      LocalDateTime startTime = endTime.minusDays(days);

      List<Map<String, Object>> stats = apiUsageRepository
          .getUserDailyStats(user.getId(), startTime, endTime);

      Map<String, Object> summary = new HashMap<>();
      summary.put("days", days);
      summary.put("startDate", startTime.toLocalDate());
      summary.put("endDate", endTime.toLocalDate());
      summary.put("daily_stats", stats);

      return ResponseEntity.ok(summary);
    } catch (Exception e) {
      log.error("[일일 통계 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "일일 통계 조회 실패: " + e.getMessage()));
    }
  }

  // 관리자: 전체 사용자 통계 (지정 기간)
  @GetMapping("/admin/user-stats")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> getUserStats(
      @RequestParam(defaultValue = "30") int days) {
    try {
      LocalDateTime endTime = LocalDateTime.now();
      LocalDateTime startTime = endTime.minusDays(days);

      List<Map<String, Object>> stats = apiUsageRepository
          .getUserApiStats(startTime, endTime);

      // 사용자 정보 추가
      List<Map<String, Object>> enrichedStats = new ArrayList<>();
      for (Map<String, Object> stat : stats) {
        Map<String, Object> enriched = new HashMap<>(stat);
        Long userId = ((Number) stat.get("user_id")).longValue();

        userRepository.findById(userId).ifPresent(user -> {
          enriched.put("username", user.getUsername());
          enriched.put("email", user.getEmail());
        });

        enrichedStats.add(enriched);
      }

      return ResponseEntity.ok(enrichedStats);
    } catch (Exception e) {
      log.error("[사용자 통계 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "사용자 통계 조회 실패: " + e.getMessage()));
    }
  }

  // 관리자: 엔드포인트별 통계
  @GetMapping("/admin/endpoint-stats")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> getEndpointStats(
      @RequestParam(defaultValue = "30") int days) {
    try {
      LocalDateTime endTime = LocalDateTime.now();
      LocalDateTime startTime = endTime.minusDays(days);

      List<Map<String, Object>> stats = apiUsageRepository
          .getEndpointStats(startTime, endTime);

      return ResponseEntity.ok(stats);
    } catch (Exception e) {
      log.error("[엔드포인트 통계 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "엔드포인트 통계 조회 실패: " + e.getMessage()));
    }
  }

  // 관리자: 특정 사용자의 상세 사용량
  @GetMapping("/admin/users/{userId}/usage")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> getUserDetailedUsage(
      @PathVariable Long userId,
      @RequestParam(defaultValue = "30") int days) {
    try {
      LocalDateTime endTime = LocalDateTime.now();
      LocalDateTime startTime = endTime.minusDays(days);

      List<ApiUsage> usages = apiUsageRepository
          .findByUserIdAndTimestampBetween(userId, startTime, endTime);

      List<Map<String, Object>> response = usages.stream()
          .map(this::convertToMap)
          .toList();

      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("[사용자 상세 사용량 조회 실패] userId={}", userId, e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "사용자 상세 사용량 조회 실패: " + e.getMessage()));
    }
  }

  // 관리자: 전체 API 사용량 요약
  @GetMapping("/admin/summary")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> getUsageSummary(
      @RequestParam(defaultValue = "30") int days) {
    try {
      LocalDateTime endTime = LocalDateTime.now();
      LocalDateTime startTime = endTime.minusDays(days);

      List<ApiUsage> allUsages = apiUsageRepository
          .findByTimestampBetween(startTime, endTime);

      Map<String, Object> summary = new HashMap<>();
      summary.put("period_days", days);
      summary.put("start_date", startTime.toLocalDate());
      summary.put("end_date", endTime.toLocalDate());
      summary.put("total_requests", allUsages.size());
      summary.put("total_request_bytes", allUsages.stream()
          .mapToLong(ApiUsage::getRequestSize).sum());
      summary.put("total_response_bytes", allUsages.stream()
          .mapToLong(ApiUsage::getResponseSize).sum());
      summary.put("avg_execution_time_ms", allUsages.isEmpty() ? 0 :
          allUsages.stream()
              .mapToLong(ApiUsage::getExecutionTimeMs)
              .average()
              .orElse(0));

      // 사용자 통계
      List<Map<String, Object>> userStats = apiUsageRepository
          .getUserApiStats(startTime, endTime);
      summary.put("user_stats", userStats);

      return ResponseEntity.ok(summary);
    } catch (Exception e) {
      log.error("[API 사용량 요약 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "API 사용량 요약 조회 실패: " + e.getMessage()));
    }
  }

  // ApiUsage를 맵으로 변환
  private Map<String, Object> convertToMap(ApiUsage usage) {
    Map<String, Object> map = new HashMap<>();
    map.put("id", usage.getId());
    map.put("endpoint", usage.getEndpoint());
    map.put("method", usage.getMethod());
    map.put("requestSize", usage.getRequestSize());
    map.put("responseSize", usage.getResponseSize());
    map.put("statusCode", usage.getStatusCode());
    map.put("executionTimeMs", usage.getExecutionTimeMs());
    map.put("timestamp", usage.getTimestamp());
    map.put("ipAddress", usage.getIpAddress());
    return map;
  }
}
