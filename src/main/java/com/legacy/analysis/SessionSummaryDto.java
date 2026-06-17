package com.legacy.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/**
 * 분석 세션의 전체 요약 정보
 */
public class SessionSummaryDto {

  @JsonProperty("sessionId")
  private String sessionId;

  @JsonProperty("startTime")
  private LocalDateTime startTime;

  @JsonProperty("endTime")
  private LocalDateTime endTime;

  @JsonProperty("totalFiles")
  private int totalFiles;

  @JsonProperty("successCount")
  private int successCount;

  @JsonProperty("failureCount")
  private int failureCount;

  @JsonProperty("skipCount")
  private int skipCount;

  @JsonProperty("totalProcessingTimeMs")
  private long totalProcessingTimeMs;

  @JsonProperty("averageProcessingTimeMs")
  private double averageProcessingTimeMs;

  @JsonProperty("successRate")
  private double successRate;

  @JsonProperty("status")
  private String status;

  public SessionSummaryDto() {
  }

  public void calculateSuccessRate() {
    int totalProcessed = successCount + failureCount + skipCount;
    if (totalProcessed > 0) {
      this.successRate = ((double) successCount / totalProcessed) * 100;
    }
  }

  public void calculateAverageTime() {
    if (successCount > 0) {
      this.averageProcessingTimeMs = (double) totalProcessingTimeMs / successCount;
    }
  }

  // Getters and Setters
  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
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

  public long getTotalProcessingTimeMs() {
    return totalProcessingTimeMs;
  }

  public void setTotalProcessingTimeMs(long totalProcessingTimeMs) {
    this.totalProcessingTimeMs = totalProcessingTimeMs;
  }

  public double getAverageProcessingTimeMs() {
    return averageProcessingTimeMs;
  }

  public void setAverageProcessingTimeMs(double averageProcessingTimeMs) {
    this.averageProcessingTimeMs = averageProcessingTimeMs;
  }

  public double getSuccessRate() {
    return successRate;
  }

  public void setSuccessRate(double successRate) {
    this.successRate = successRate;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }
}
