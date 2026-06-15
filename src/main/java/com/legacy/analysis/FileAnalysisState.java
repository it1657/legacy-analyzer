/* [AI 한글 주석 보완 완료] */
// 확장자(.java) 맞춤형 자동 생성 목업 주석 예시 1
package com.legacy.analysis;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonProperty;
// 분석 대상 파일명: FileAnalysisState.java

/**
 * 개별 파일 분석 상태 추적
 */
public class FileAnalysisState {

  @JsonProperty("filePath")
  private String filePath;

  @JsonProperty("status")
  private String status; // PENDING, IN_PROGRESS, SUCCESS, FAILED, SKIPPED

  @JsonProperty("errorType")
  private String errorType; // null이면 성공

  @JsonProperty("errorMessage")
  private String errorMessage;

  @JsonProperty("retryCount")
  private int retryCount = 0;

  @JsonProperty("processingTimeMs")
  private long processingTimeMs = 0;

  @JsonProperty("timestamp")
  private LocalDateTime timestamp;

  // 기본 생성자
  public FileAnalysisState() {
  }

  // 생성자
  public FileAnalysisState(String filePath) {
    this.filePath = filePath;
    this.status = "PENDING";
    this.timestamp = LocalDateTime.now();
  }

  // Getter/Setter
  public String getFilePath() {
    return filePath;
  }

  public void setFilePath(String filePath) {
    this.filePath = filePath;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getErrorType() {
    return errorType;
  }

  public void setErrorType(String errorType) {
    this.errorType = errorType;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public int getRetryCount() {
    return retryCount;
  }

  public void setRetryCount(int retryCount) {
    this.retryCount = retryCount;
  }

  public long getProcessingTimeMs() {
    return processingTimeMs;
  }

  public void setProcessingTimeMs(long processingTimeMs) {
    this.processingTimeMs = processingTimeMs;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }
}
