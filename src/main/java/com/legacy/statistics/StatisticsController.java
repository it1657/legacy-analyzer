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
      stats.setTotalFilesAnalyzed(allAnalysis.stream().mapToLong(h -> h.getTotalFiles() != null ? h.getTotalFiles() : 0L).sum());
      stats.setTotalProcessingTimeMs(
          allAnalysis.stream().mapToLong(h -> h.getProcessingTimeMs() != null ? h.getProcessingTimeMs() : 0L).sum());

      // API 사용량 통계
      List<ApiUsage> allApiUsages = apiUsageRepository.findAll();
      stats.setTotalApiRequests(allApiUsages.size());
      stats.setTotalDataProcessedBytes(
          allApiUsages.stream()
              .mapToLong(u -> u.getRequestSize() + u.getResponseSize())
              .sum());

      // 토큰 통계
      Long totalInputTokens = analysisHistoryRepository.getTotalInputTokensSystem();
      Long totalOutputTokens = analysisHistoryRepository.getTotalOutputTokensSystem();
      Double totalCost = analysisHistoryRepository.getTotalCostSystem();

      stats.setTotalInputTokens(totalInputTokens != null ? totalInputTokens : 0);
      stats.setTotalOutputTokens(totalOutputTokens != null ? totalOutputTokens : 0);
      stats.setTotalTokens(totalInputTokens != null && totalOutputTokens != null ?
          totalInputTokens + totalOutputTokens : 0);
      stats.setTotalApiCost(totalCost != null ? totalCost : 0.0);

      // 모델별 토큰 및 비용 통계
      List<Object[]> tokensByModel = analysisHistoryRepository.getTokensByModel();
      List<Object[]> costByModel = analysisHistoryRepository.getCostByModel();

      for (Object[] row : tokensByModel) {
        if (row[0] != null && row[1] != null) {
          stats.getTokensByModel().put((String) row[0], ((Number) row[1]).longValue());
        }
      }

      for (Object[] row : costByModel) {
        if (row[0] != null && row[1] != null) {
          stats.getCostByModel().put((String) row[0], ((Number) row[1]).doubleValue());
        }
      }

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
            userTop.put("userSeq", entry.getKey());
            userRepository.findById(entry.getKey()).ifPresent(user -> {
              userTop.put("userId", user.getUserId());
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
        UserStatisticsDto stats = new UserStatisticsDto(user.getSeq(), user.getUserId(),
            user.getEmail());

        // 분석 통계
        List<AnalysisHistory> userAnalysis = analysisHistoryRepository
            .findByUserId(user.getSeq());

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
            userAnalysis.stream().mapToLong(h -> h.getTotalFiles() != null ? h.getTotalFiles() : 0L).sum());
        stats.setTotalProcessingTimeMs(
            userAnalysis.stream().mapToLong(h -> h.getProcessingTimeMs() != null ? h.getProcessingTimeMs() : 0L).sum());

        // API 사용량
        List<ApiUsage> userApiUsage = apiUsageRepository.findByUserId(user.getSeq());
        stats.setTotalApiRequests(userApiUsage.size());
        stats.setTotalDataProcessedBytes(
            userApiUsage.stream()
                .mapToLong(u -> u.getRequestSize() + u.getResponseSize())
                .sum());

        // 토큰 통계
        Long userInputTokens = analysisHistoryRepository.getTotalInputTokensByUser(user.getSeq());
        Long userOutputTokens = analysisHistoryRepository.getTotalOutputTokensByUser(user.getSeq());
        Double userCost = analysisHistoryRepository.getTotalCostByUser(user.getSeq());

        stats.setTotalInputTokens(userInputTokens != null ? userInputTokens : 0);
        stats.setTotalOutputTokens(userOutputTokens != null ? userOutputTokens : 0);
        stats.setTotalTokens(userInputTokens != null && userOutputTokens != null ?
            userInputTokens + userOutputTokens : 0);
        stats.setTotalApiCost(userCost != null ? userCost : 0.0);

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

  // 관리자: 토큰 사용량 통계
  @GetMapping("/admin/tokens")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> getTokenStatistics() {
    try {
      Map<String, Object> stats = new HashMap<>();

      // 전체 토큰 통계
      Long totalInputTokens = analysisHistoryRepository.getTotalInputTokensSystem();
      Long totalOutputTokens = analysisHistoryRepository.getTotalOutputTokensSystem();
      Long totalTokens = analysisHistoryRepository.getTotalTokensSystem();
      Double totalCost = analysisHistoryRepository.getTotalCostSystem();

      stats.put("total_input_tokens", totalInputTokens != null ? totalInputTokens : 0);
      stats.put("total_output_tokens", totalOutputTokens != null ? totalOutputTokens : 0);
      stats.put("total_tokens", totalTokens != null ? totalTokens : 0);
      stats.put("total_cost", totalCost != null ? totalCost : 0.0);

      // 모델별 통계
      Map<String, Long> tokensByModel = new HashMap<>();
      Map<String, Double> costByModel = new HashMap<>();

      List<Object[]> tokenResults = analysisHistoryRepository.getTokensByModel();
      for (Object[] row : tokenResults) {
        if (row[0] != null && row[1] != null) {
          tokensByModel.put((String) row[0], ((Number) row[1]).longValue());
        }
      }

      List<Object[]> costResults = analysisHistoryRepository.getCostByModel();
      for (Object[] row : costResults) {
        if (row[0] != null && row[1] != null) {
          costByModel.put((String) row[0], ((Number) row[1]).doubleValue());
        }
      }

      stats.put("tokens_by_model", tokensByModel);
      stats.put("cost_by_model", costByModel);

      // 평균 토큰 수
      List<AnalysisHistory> allAnalysis = analysisHistoryRepository.findAll();
      if (!allAnalysis.isEmpty()) {
        double avgInputTokens = allAnalysis.stream()
            .mapToLong(AnalysisHistory::getInputTokens)
            .average()
            .orElse(0);
        double avgOutputTokens = allAnalysis.stream()
            .mapToLong(AnalysisHistory::getOutputTokens)
            .average()
            .orElse(0);

        stats.put("avg_input_tokens", Math.round(avgInputTokens));
        stats.put("avg_output_tokens", Math.round(avgOutputTokens));
      }

      return ResponseEntity.ok(stats);
    } catch (Exception e) {
      log.error("[토큰 통계 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Collections.singletonMap("message",
              "토큰 통계 조회 실패: " + e.getMessage()));
    }
  }

  // 사용자: 자신의 토큰 사용량
  @GetMapping("/my-tokens")
  @ResponseBody
  public ResponseEntity<?> getMyTokenStatistics(
      @RequestHeader(value = "Authorization", required = false) String authHeader) {
    try {
      Long userId = extractUserIdFromAuth(authHeader);
      if (userId == null) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Collections.singletonMap("message", "인증되지 않은 사용자"));
      }

      Map<String, Object> stats = new HashMap<>();

      // 사용자 토큰 통계
      Long inputTokens = analysisHistoryRepository.getTotalInputTokensByUser(userId);
      Long outputTokens = analysisHistoryRepository.getTotalOutputTokensByUser(userId);
      Long totalTokens = analysisHistoryRepository.getTotalTokensByUser(userId);
      Double cost = analysisHistoryRepository.getTotalCostByUser(userId);

      stats.put("input_tokens", inputTokens != null ? inputTokens : 0);
      stats.put("output_tokens", outputTokens != null ? outputTokens : 0);
      stats.put("total_tokens", totalTokens != null ? totalTokens : 0);
      stats.put("total_cost", cost != null ? cost : 0.0);

      return ResponseEntity.ok(stats);
    } catch (Exception e) {
      log.error("[사용자 토큰 통계 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Collections.singletonMap("message",
              "토큰 통계 조회 실패: " + e.getMessage()));
    }
  }

  // 헬퍼: 인증 헤더에서 userId 추출
  private Long extractUserIdFromAuth(String authHeader) {
    // 이 메서드는 SecurityContextHolder나 다른 방식으로 userId를 가져와야 함
    // 현재는 간단한 구현, 실제로는 Spring Security 컨텍스트 사용 권장
    return null;
  }
}
