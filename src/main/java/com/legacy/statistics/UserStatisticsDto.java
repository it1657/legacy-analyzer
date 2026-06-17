package com.legacy.statistics;

// 사용자별 분석 통계
public class UserStatisticsDto {
  private Long userId;
  private String username;
  private String email;
  private long totalAnalysis;
  private long successAnalysis;
  private long failureAnalysis;
  private long skipAnalysis;
  private double successRate;
  private long totalFilesAnalyzed;
  private long totalProcessingTimeMs;
  private double avgProcessingTimeMs;
  private long totalApiRequests;
  private long totalDataProcessedBytes;

  // 생성자
  public UserStatisticsDto() {
  }

  public UserStatisticsDto(Long userId, String username, String email) {
    this.userId = userId;
    this.username = username;
    this.email = email;
  }

  // 계산 메서드
  public void calculateMetrics() {
    long total = successAnalysis + failureAnalysis + skipAnalysis;
    if (total > 0) {
      this.successRate = (double) successAnalysis / total * 100;
    }

    if (totalAnalysis > 0) {
      this.avgProcessingTimeMs = (double) totalProcessingTimeMs / totalAnalysis;
    }
  }

  // Getter/Setter
  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public long getTotalAnalysis() {
    return totalAnalysis;
  }

  public void setTotalAnalysis(long totalAnalysis) {
    this.totalAnalysis = totalAnalysis;
  }

  public long getSuccessAnalysis() {
    return successAnalysis;
  }

  public void setSuccessAnalysis(long successAnalysis) {
    this.successAnalysis = successAnalysis;
  }

  public long getFailureAnalysis() {
    return failureAnalysis;
  }

  public void setFailureAnalysis(long failureAnalysis) {
    this.failureAnalysis = failureAnalysis;
  }

  public long getSkipAnalysis() {
    return skipAnalysis;
  }

  public void setSkipAnalysis(long skipAnalysis) {
    this.skipAnalysis = skipAnalysis;
  }

  public double getSuccessRate() {
    return successRate;
  }

  public void setSuccessRate(double successRate) {
    this.successRate = successRate;
  }

  public long getTotalFilesAnalyzed() {
    return totalFilesAnalyzed;
  }

  public void setTotalFilesAnalyzed(long totalFilesAnalyzed) {
    this.totalFilesAnalyzed = totalFilesAnalyzed;
  }

  public long getTotalProcessingTimeMs() {
    return totalProcessingTimeMs;
  }

  public void setTotalProcessingTimeMs(long totalProcessingTimeMs) {
    this.totalProcessingTimeMs = totalProcessingTimeMs;
  }

  public double getAvgProcessingTimeMs() {
    return avgProcessingTimeMs;
  }

  public void setAvgProcessingTimeMs(double avgProcessingTimeMs) {
    this.avgProcessingTimeMs = avgProcessingTimeMs;
  }

  public long getTotalApiRequests() {
    return totalApiRequests;
  }

  public void setTotalApiRequests(long totalApiRequests) {
    this.totalApiRequests = totalApiRequests;
  }

  public long getTotalDataProcessedBytes() {
    return totalDataProcessedBytes;
  }

  public void setTotalDataProcessedBytes(long totalDataProcessedBytes) {
    this.totalDataProcessedBytes = totalDataProcessedBytes;
  }
}
