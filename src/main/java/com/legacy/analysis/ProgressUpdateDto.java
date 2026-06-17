package com.legacy.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/**
 * 실시간 진행 상황 업데이트 정보
 */
public class ProgressUpdateDto {

  @JsonProperty("sessionId")
  private String sessionId;

  @JsonProperty("fileName")
  private String fileName;

  @JsonProperty("currentFileIndex")
  private int currentFileIndex;

  @JsonProperty("totalFiles")
  private int totalFiles;

  @JsonProperty("progressPercentage")
  private double progressPercentage;

  @JsonProperty("processingSpeed")
  private double processingSpeed;

  @JsonProperty("estimatedRemainingTimeMs")
  private long estimatedRemainingTimeMs;

  @JsonProperty("fileStatus")
  private String fileStatus;

  @JsonProperty("timestamp")
  private LocalDateTime timestamp;

  public ProgressUpdateDto() {
  }

  public void calculateProgressPercentage() {
    if (totalFiles > 0) {
      this.progressPercentage = ((double) currentFileIndex / totalFiles) * 100;
    }
  }

  public void calculateEstimatedTime(long elapsedTimeMs, int processedFiles) {
    if (processedFiles == 0 || elapsedTimeMs == 0) {
      this.estimatedRemainingTimeMs = 0;
      return;
    }
    double remainingFiles = totalFiles - processedFiles;
    double timePerFile = (double) elapsedTimeMs / processedFiles;
    this.estimatedRemainingTimeMs = (long) (remainingFiles * timePerFile);
  }

  public void calculateSpeed(long elapsedTimeMs, int processedFiles) {
    if (elapsedTimeMs == 0 || processedFiles == 0) {
      this.processingSpeed = 0.0;
      return;
    }
    this.processingSpeed = (double) processedFiles / (elapsedTimeMs / 1000.0);
  }

  // Getters and Setters
  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getFileName() {
    return fileName;
  }

  public void setFileName(String fileName) {
    this.fileName = fileName;
  }

  public int getCurrentFileIndex() {
    return currentFileIndex;
  }

  public void setCurrentFileIndex(int currentFileIndex) {
    this.currentFileIndex = currentFileIndex;
  }

  public int getTotalFiles() {
    return totalFiles;
  }

  public void setTotalFiles(int totalFiles) {
    this.totalFiles = totalFiles;
  }

  public double getProgressPercentage() {
    return progressPercentage;
  }

  public void setProgressPercentage(double progressPercentage) {
    this.progressPercentage = progressPercentage;
  }

  public double getProcessingSpeed() {
    return processingSpeed;
  }

  public void setProcessingSpeed(double processingSpeed) {
    this.processingSpeed = processingSpeed;
  }

  public long getEstimatedRemainingTimeMs() {
    return estimatedRemainingTimeMs;
  }

  public void setEstimatedRemainingTimeMs(long estimatedRemainingTimeMs) {
    this.estimatedRemainingTimeMs = estimatedRemainingTimeMs;
  }

  public String getFileStatus() {
    return fileStatus;
  }

  public void setFileStatus(String fileStatus) {
    this.fileStatus = fileStatus;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }
}
