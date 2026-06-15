package com.legacy;

import java.util.*;

// 전체 시스템 통계
public class SystemStatisticsDto {
  private long totalUsers;
  private long activeUsers;
  private long totalAnalysis;
  private long successAnalysis;
  private long failureAnalysis;
  private long skipAnalysis;
  private double overallSuccessRate;
  private long totalFilesAnalyzed;
  private long totalProcessingTimeMs;
  private double avgProcessingTimeMs;
  private long totalApiRequests;
  private long totalDataProcessedBytes;
  private double avgDataPerRequest;
  private Map<String, Long> analysisStatusDistribution;
  private List<Map<String, Object>> topUsers;
  private List<Map<String, Object>> recentActivity;

  // 생성자
  public SystemStatisticsDto() {
    this.analysisStatusDistribution = new HashMap<>();
    this.topUsers = new ArrayList<>();
    this.recentActivity = new ArrayList<>();
  }

  // 계산 메서드
  public void calculateMetrics() {
    long total = successAnalysis + failureAnalysis + skipAnalysis;
    if (total > 0) {
      this.overallSuccessRate = (double) successAnalysis / total * 100;
    }

    if (totalAnalysis > 0) {
      this.avgProcessingTimeMs = (double) totalProcessingTimeMs / totalAnalysis;
    }

    if (totalApiRequests > 0) {
      this.avgDataPerRequest = (double) totalDataProcessedBytes / totalApiRequests / 1024;
    }
  }

  // Getter/Setter
  public long getTotalUsers() {
    return totalUsers;
  }

  public void setTotalUsers(long totalUsers) {
    this.totalUsers = totalUsers;
  }

  public long getActiveUsers() {
    return activeUsers;
  }

  public void setActiveUsers(long activeUsers) {
    this.activeUsers = activeUsers;
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

  public double getOverallSuccessRate() {
    return overallSuccessRate;
  }

  public void setOverallSuccessRate(double overallSuccessRate) {
    this.overallSuccessRate = overallSuccessRate;
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

  public double getAvgDataPerRequest() {
    return avgDataPerRequest;
  }

  public void setAvgDataPerRequest(double avgDataPerRequest) {
    this.avgDataPerRequest = avgDataPerRequest;
  }

  public Map<String, Long> getAnalysisStatusDistribution() {
    return analysisStatusDistribution;
  }

  public void setAnalysisStatusDistribution(Map<String, Long> analysisStatusDistribution) {
    this.analysisStatusDistribution = analysisStatusDistribution;
  }

  public List<Map<String, Object>> getTopUsers() {
    return topUsers;
  }

  public void setTopUsers(List<Map<String, Object>> topUsers) {
    this.topUsers = topUsers;
  }

  public List<Map<String, Object>> getRecentActivity() {
    return recentActivity;
  }

  public void setRecentActivity(List<Map<String, Object>> recentActivity) {
    this.recentActivity = recentActivity;
  }
}
