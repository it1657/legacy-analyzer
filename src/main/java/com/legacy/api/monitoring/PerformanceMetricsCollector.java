package com.legacy.api.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 분석 작업의 성능 메트릭을 수집하는 컴포넌트
 */
@Component
public class PerformanceMetricsCollector {

  private static final Logger log = LoggerFactory.getLogger(PerformanceMetricsCollector.class);
  private final Map<String, Map<String, Object>> sessionMetrics = new ConcurrentHashMap<>();
  private final Map<String, Long> fileStartTimes = new ConcurrentHashMap<>();
  private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

  // 파일 분석 시작
  public void startFileAnalysis(String sessionId, String filePath) {
    fileStartTimes.put(filePath, System.currentTimeMillis());

    Map<String, Object> metrics = sessionMetrics.computeIfAbsent(sessionId, k -> new HashMap<>());
    List<String> filesInProgress = (List<String>) metrics.computeIfAbsent("filesInProgress",
        k -> new ArrayList<>());
    filesInProgress.add(filePath);
  }

  // 파일 분석 종료 및 처리 시간 반환
  public long endFileAnalysis(String sessionId, String filePath) {
    Long startTime = fileStartTimes.remove(filePath);
    if (startTime == null) return 0;

    long processingTime = System.currentTimeMillis() - startTime;

    Map<String, Object> metrics = sessionMetrics.get(sessionId);
    if (metrics != null) {
      List<String> filesInProgress = (List<String>) metrics.get("filesInProgress");
      if (filesInProgress != null) {
        filesInProgress.remove(filePath);
      }

      // 처리 시간 누적
      long totalTime = (long) metrics.getOrDefault("totalProcessingTime", 0L);
      metrics.put("totalProcessingTime", totalTime + processingTime);

      // 파일 개수 증가
      int processedFiles = (int) metrics.getOrDefault("processedFiles", 0);
      metrics.put("processedFiles", processedFiles + 1);
    }

    return processingTime;
  }

  // 메모리 사용량 기록
  public void recordMemoryUsage(String sessionId) {
    long usedMemory = memoryBean.getHeapMemoryUsage().getUsed();
    long maxMemory = memoryBean.getHeapMemoryUsage().getMax();
    double memoryPercent = ((double) usedMemory / maxMemory) * 100;

    Map<String, Object> metrics = sessionMetrics.computeIfAbsent(sessionId, k -> new HashMap<>());
    metrics.put("usedMemoryMB", usedMemory / (1024 * 1024));
    metrics.put("maxMemoryMB", maxMemory / (1024 * 1024));
    metrics.put("memoryUsagePercent", memoryPercent);
    metrics.put("lastMemoryCheckTime", LocalDateTime.now());
  }

  // 세션별 메트릭 조회
  public Map<String, Object> getSessionMetrics(String sessionId) {
    return new HashMap<>(sessionMetrics.getOrDefault(sessionId, new HashMap<>()));
  }

  // 시스템 메트릭 조회
  public Map<String, Object> getSystemMetrics() {
    Map<String, Object> systemMetrics = new HashMap<>();
    systemMetrics.put("activeSessions", sessionMetrics.size());
    systemMetrics.put("totalFilesProcessed",
        sessionMetrics.values().stream()
            .mapToInt(m -> (int) m.getOrDefault("processedFiles", 0))
            .sum());
    systemMetrics.put("timestamp", LocalDateTime.now());
    return systemMetrics;
  }

  // 성능 통계 계산
  public Map<String, Double> calculatePerformanceStats(String sessionId) {
    Map<String, Object> metrics = sessionMetrics.get(sessionId);
    Map<String, Double> stats = new HashMap<>();

    if (metrics != null) {
      long totalTime = (long) metrics.getOrDefault("totalProcessingTime", 0L);
      int processedFiles = (int) metrics.getOrDefault("processedFiles", 0);

      if (processedFiles > 0) {
        stats.put("averageTimePerFile", (double) totalTime / processedFiles);
        stats.put("filesPerSecond", (double) processedFiles / Math.max(1, totalTime / 1000.0));
      }

      stats.put("memoryUsagePercent",
          (double) metrics.getOrDefault("memoryUsagePercent", 0.0));
    }

    return stats;
  }
}
