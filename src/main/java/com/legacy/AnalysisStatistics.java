/* [AI 한글 주석 보완 완료] */
// 확장자(.java) 맞춤형 자동 생성 목업 주석 예시 1
package com.legacy;

import java.time.LocalDateTime;
import java.util.HashMap;
// 분석 대상 파일명: AnalysisStatistics.java
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 분석 작업의 종합 통계 정보
 */
public class AnalysisStatistics {

  @JsonProperty("totalFiles")
  private int totalFiles = 0;

  @JsonProperty("successCount")
  private int successCount = 0;

  @JsonProperty("failureCount")
  private int failureCount = 0;

  @JsonProperty("skipCount")
  private int skipCount = 0;

  @JsonProperty("oversizeCount")
  private int oversizeCount = 0;

  @JsonProperty("totalProcessingTimeMs")
  private long totalProcessingTimeMs = 0;

  @JsonProperty("startTime")
  private LocalDateTime startTime;

  @JsonProperty("endTime")
  private LocalDateTime endTime;

  @JsonProperty("errorBreakdown")
  private Map<String, Integer> errorBreakdown = new HashMap<>();

  @JsonProperty("averageProcessingTimeMs")
  private double averageProcessingTimeMs = 0.0;

  @JsonProperty("successRate")
  private double successRate = 0.0;

  @JsonProperty("errorCategoryMap")
  private Map<String, Integer> errorCategoryMap = new HashMap<>();

  @JsonProperty("performanceMetrics")
  private Map<String, Double> performanceMetrics = new HashMap<>();

  // 기본 생성자
  public AnalysisStatistics() {
    this.startTime = LocalDateTime.now();
  }

  // Getter/Setter
  public int getTotalFiles() {
    return totalFiles;
  }

  public void setTotalFiles(int totalFiles) {
    this.totalFiles = totalFiles;
  }

  public int getSuccessCount() {
    return successCount;
  }

  public void setSuccessCount(int successCount) {
    this.successCount = successCount;
  }

  public int getFailureCount() {
    return failureCount;
  }

  public void setFailureCount(int failureCount) {
    this.failureCount = failureCount;
  }

  public int getSkipCount() {
    return skipCount;
  }

  public void setSkipCount(int skipCount) {
    this.skipCount = skipCount;
  }

  public int getOversizeCount() {
    return oversizeCount;
  }

  public void setOversizeCount(int oversizeCount) {
    this.oversizeCount = oversizeCount;
  }

  public long getTotalProcessingTimeMs() {
    return totalProcessingTimeMs;
  }

  public void setTotalProcessingTimeMs(long totalProcessingTimeMs) {
    this.totalProcessingTimeMs = totalProcessingTimeMs;
  }

  public LocalDateTime getStartTime() {
    return startTime;
  }

  public void setStartTime(LocalDateTime startTime) {
    this.startTime = startTime;
  }

  public LocalDateTime getEndTime() {
    return endTime;
  }

  public void setEndTime(LocalDateTime endTime) {
    this.endTime = endTime;
  }

  public Map<String, Integer> getErrorBreakdown() {
    return errorBreakdown;
  }

  public void setErrorBreakdown(Map<String, Integer> errorBreakdown) {
    this.errorBreakdown = errorBreakdown;
  }

  // 에러 타입 증가
  public void incrementErrorCount(String errorType) {
    errorBreakdown.put(errorType, errorBreakdown.getOrDefault(errorType, 0) + 1);
  }

  // 통계 계산
  public void calculateStatistics() {
    // 평균 처리 시간
    if (successCount > 0) {
      this.averageProcessingTimeMs = (double) totalProcessingTimeMs / successCount;
    }

    // 성공률
    int totalProcessed = successCount + failureCount + skipCount;
    if (totalProcessed > 0) {
      this.successRate = ((double) successCount / totalProcessed) * 100;
    }
  }

  public double getSuccessRate() {
    return successRate;
  }

  public void setSuccessRate(double successRate) {
    this.successRate = successRate;
  }

  public double getAverageProcessingTimeMs() {
    return averageProcessingTimeMs;
  }

  public void setAverageProcessingTimeMs(double averageProcessingTimeMs) {
    this.averageProcessingTimeMs = averageProcessingTimeMs;
  }

  public Map<String, Integer> getErrorCategoryMap() {
    return errorCategoryMap;
  }

  public void setErrorCategoryMap(Map<String, Integer> errorCategoryMap) {
    this.errorCategoryMap = errorCategoryMap;
  }

  public void incrementErrorCategory(String category) {
    this.errorCategoryMap.put(category,
        this.errorCategoryMap.getOrDefault(category, 0) + 1);
  }

  public Map<String, Double> getPerformanceMetrics() {
    return performanceMetrics;
  }

  public void setPerformanceMetrics(Map<String, Double> performanceMetrics) {
    this.performanceMetrics = performanceMetrics;
  }

  public void recordPerformanceMetric(String metricName, double value) {
    this.performanceMetrics.put(metricName, value);
  }
}
