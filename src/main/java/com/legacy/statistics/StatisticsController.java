package com.legacy.statistics;
import com.legacy.api.usage.ApiUsageRepository;
import com.legacy.api.usage.ApiUsage;
import com.legacy.analysis.AnalysisHistoryRepository;
import com.legacy.analysis.AnalysisHistory;
import com.legacy.auth.UserRepository;
import com.legacy.auth.User;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

  private static final Logger log = LoggerFactory.getLogger(StatisticsController.class);

  private final UserRepository userRepository;
  private final AnalysisHistoryRepository analysisHistoryRepository;
  private final ApiUsageRepository apiUsageRepository;

  @Autowired
  public StatisticsController(
      UserRepository userRepository,
      AnalysisHistoryRepository analysisHistoryRepository,
      ApiUsageRepository apiUsageRepository) {
    this.userRepository = userRepository;
    this.analysisHistoryRepository = analysisHistoryRepository;
    this.apiUsageRepository = apiUsageRepository;
  }

  // 관리자: 전체 시스템 통계
  @GetMapping("/admin/system")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> getSystemStatistics() {
    try {
      SystemStatisticsDto stats = new SystemStatisticsDto();

      // 사용자 통계
      List<User> allUsers = userRepository.findAll();
      stats.setTotalUsers(allUsers.size());
      stats.setActiveUsers(allUsers.stream().filter(User::isActive).count());

      // 분석 통계
      List<AnalysisHistory> allAnalysis = analysisHistoryRepository.findAll();
      long success = allAnalysis.stream().filter(a -> "COMPLETED".equals(a.getStatus())).count();
      long failure = allAnalysis.stream().filter(a -> "FAILED".equals(a.getStatus())).count();
      long skip = allAnalysis.size() - success - failure;

      stats.setTotalAnalysis(allAnalysis.size());
      stats.setSuccessAnalysis(success);
      stats.setFailureAnalysis(failure);
      stats.setSkipAnalysis(skip);
      stats.setTotalFilesAnalyzed(allAnalysis.stream().mapToLong(AnalysisHistory::getTotalFiles).sum());
      stats.setTotalProcessingTimeMs(
          allAnalysis.stream().mapToLong(AnalysisHistory::getProcessingTimeMs).sum());

      // API 사용량 통계
      List<ApiUsage> allApiUsages = apiUsageRepository.findAll();
      stats.setTotalApiRequests(allApiUsages.size());
      stats.setTotalDataProcessedBytes(
          allApiUsages.stream()
              .mapToLong(u -> u.getRequestSize() + u.getResponseSize())
              .sum());

      // 계산
      stats.calculateMetrics();

      // 상태별 분포
      Map<String, Long> statusDist = new HashMap<>();
      statusDist.put("COMPLETED", success);
      statusDist.put("FAILED", failure);
      statusDist.put("OTHER", skip);
      stats.setAnalysisStatusDistribution(statusDist);

      // 상위 사용자 (분석 수 기준)
      Map<Long, Long> userAnalysisCount = new HashMap<>();
      allAnalysis.forEach(a -> {
        userAnalysisCount.put(a.getUserId(),
            userAnalysisCount.getOrDefault(a.getUserId(), 0L) + 1);
      });

      List<Map<String, Object>> topUsers = new ArrayList<>();
      userAnalysisCount.entrySet()
          .stream()
          .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
          .limit(5)
          .forEach(entry -> {
            Map<String, Object> userTop = new HashMap<>();
            userTop.put("userId", entry.getKey());
            userRepository.findById(entry.getKey()).ifPresent(user -> {
              userTop.put("username", user.getUsername());
            });
            userTop.put("analysisCount", entry.getValue());
            topUsers.add(userTop);
          });

      stats.setTopUsers(topUsers);

      return ResponseEntity.ok(stats);
    } catch (Exception e) {
      log.error("[시스템 통계 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Collections.singletonMap("message",
              "시스템 통계 조회 실패: " + e.getMessage()));
    }
  }

  // 관리자: 사용자별 상세 통계
  @GetMapping("/admin/users")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> getUserStatistics() {
    try {
      List<User> allUsers = userRepository.findAll();
      List<UserStatisticsDto> userStats = new ArrayList<>();

      for (User user : allUsers) {
        UserStatisticsDto stats = new UserStatisticsDto(user.getId(), user.getUsername(),
            user.getEmail());

        // 분석 통계
        List<AnalysisHistory> userAnalysis = analysisHistoryRepository
            .findByUserId(user.getId());

        long success = userAnalysis.stream()
            .filter(a -> "COMPLETED".equals(a.getStatus()))
            .count();
        long failure = userAnalysis.stream()
            .filter(a -> "FAILED".equals(a.getStatus()))
            .count();
        long skip = userAnalysis.size() - success - failure;

        stats.setTotalAnalysis(userAnalysis.size());
        stats.setSuccessAnalysis(success);
        stats.setFailureAnalysis(failure);
        stats.setSkipAnalysis(skip);
        stats.setTotalFilesAnalyzed(
            userAnalysis.stream().mapToLong(AnalysisHistory::getTotalFiles).sum());
        stats.setTotalProcessingTimeMs(
            userAnalysis.stream().mapToLong(AnalysisHistory::getProcessingTimeMs).sum());

        // API 사용량
        List<ApiUsage> userApiUsage = apiUsageRepository.findByUserId(user.getId());
        stats.setTotalApiRequests(userApiUsage.size());
        stats.setTotalDataProcessedBytes(
            userApiUsage.stream()
                .mapToLong(u -> u.getRequestSize() + u.getResponseSize())
                .sum());

        stats.calculateMetrics();
        userStats.add(stats);
      }

      // 분석 수로 정렬
      userStats.sort((a, b) -> Long.compare(b.getTotalAnalysis(), a.getTotalAnalysis()));

      return ResponseEntity.ok(userStats);
    } catch (Exception e) {
      log.error("[사용자 통계 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Collections.singletonMap("message",
              "사용자 통계 조회 실패: " + e.getMessage()));
    }
  }

  // 관리자: 분석 성공률 통계
  @GetMapping("/admin/success-rate")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> getSuccessRateStatistics() {
    try {
      List<AnalysisHistory> allAnalysis = analysisHistoryRepository.findAll();

      Map<String, Object> stats = new HashMap<>();
      long total = allAnalysis.size();
      long success = allAnalysis.stream().filter(a -> "COMPLETED".equals(a.getStatus())).count();
      long failed = allAnalysis.stream().filter(a -> "FAILED".equals(a.getStatus())).count();

      stats.put("total", total);
      stats.put("success", success);
      stats.put("failed", failed);
      stats.put("success_rate", total > 0 ? (double) success / total * 100 : 0);
      stats.put("failure_rate", total > 0 ? (double) failed / total * 100 : 0);

      return ResponseEntity.ok(stats);
    } catch (Exception e) {
      log.error("[성공률 통계 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Collections.singletonMap("message",
              "성공률 통계 조회 실패: " + e.getMessage()));
    }
  }

  // 관리자: 성능 통계
  @GetMapping("/admin/performance")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> getPerformanceStatistics() {
    try {
      List<AnalysisHistory> allAnalysis = analysisHistoryRepository.findAll();

      Map<String, Object> stats = new HashMap<>();

      // 평균 처리 시간
      double avgProcessingTime = allAnalysis.isEmpty() ? 0 :
          allAnalysis.stream()
              .mapToLong(AnalysisHistory::getProcessingTimeMs)
              .average()
              .orElse(0);

      // 최소/최대 처리 시간
      long minProcessingTime = allAnalysis.isEmpty() ? 0 :
          allAnalysis.stream()
              .mapToLong(AnalysisHistory::getProcessingTimeMs)
              .min()
              .orElse(0);

      long maxProcessingTime = allAnalysis.isEmpty() ? 0 :
          allAnalysis.stream()
              .mapToLong(AnalysisHistory::getProcessingTimeMs)
              .max()
              .orElse(0);

      // 평균 파일 수
      double avgFilesPerAnalysis = allAnalysis.isEmpty() ? 0 :
          allAnalysis.stream()
              .mapToLong(AnalysisHistory::getTotalFiles)
              .average()
              .orElse(0);

      stats.put("avg_processing_time_ms", Math.round(avgProcessingTime));
      stats.put("min_processing_time_ms", minProcessingTime);
      stats.put("max_processing_time_ms", maxProcessingTime);
      stats.put("avg_files_per_analysis", Math.round(avgFilesPerAnalysis * 100) / 100.0);
      stats.put("total_analyses", allAnalysis.size());

      return ResponseEntity.ok(stats);
    } catch (Exception e) {
      log.error("[성능 통계 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Collections.singletonMap("message",
              "성능 통계 조회 실패: " + e.getMessage()));
    }
  }
}
