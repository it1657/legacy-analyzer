/* [AI 한글 주석 보완 완료] */
// 구조화된 분석 로그 엔트리
package com.legacy;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 개별 분석 로그 항목 (구조화된 로깅용)
 */
public class AnalysisLogEntry {

  public enum LogLevel {
    DEBUG, INFO, WARN, ERROR
  }

  public enum EventType {
    FILE_START, FILE_COMPLETE, ERROR_OCCURRED, RETRY_ATTEMPTED, SESSION_START, SESSION_END
  }

  @JsonProperty("timestamp")
  private LocalDateTime timestamp;

  @JsonProperty("sessionId")
  private String sessionId;

  @JsonProperty("filePath")
  private String filePath;

  @JsonProperty("logLevel")
  private LogLevel logLevel;

  @JsonProperty("eventType")
  private EventType eventType;

  @JsonProperty("message")
  private String message;

  @JsonProperty("errorType")
  private String errorType;

  @JsonProperty("processingTimeMs")
  private long processingTimeMs;

  @JsonProperty("metadata")
  private Map<String, Object> metadata = new HashMap<>();

  public AnalysisLogEntry() {
    this.timestamp = LocalDateTime.now();
  }

  public String toJsonString() {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append("\"timestamp\":\"").append(timestamp).append("\",");
    sb.append("\"sessionId\":\"").append(sessionId).append("\",");
    sb.append("\"filePath\":\"").append(filePath).append("\",");
    sb.append("\"logLevel\":\"").append(logLevel).append("\",");
    sb.append("\"eventType\":\"").append(eventType).append("\",");
    sb.append("\"message\":\"").append(message).append("\",");
    sb.append("\"errorType\":").append(errorType != null ? "\"" + errorType + "\"" : "null").append(",");
    sb.append("\"processingTimeMs\":").append(processingTimeMs);
    sb.append("}");
    return sb.toString();
  }

  // Getters and Setters
  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getFilePath() {
    return filePath;
  }

  public void setFilePath(String filePath) {
    this.filePath = filePath;
  }

  public LogLevel getLogLevel() {
    return logLevel;
  }

  public void setLogLevel(LogLevel logLevel) {
    this.logLevel = logLevel;
  }

  public EventType getEventType() {
    return eventType;
  }

  public void setEventType(EventType eventType) {
    this.eventType = eventType;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getErrorType() {
    return errorType;
  }

  public void setErrorType(String errorType) {
    this.errorType = errorType;
  }

  public long getProcessingTimeMs() {
    return processingTimeMs;
  }

  public void setProcessingTimeMs(long processingTimeMs) {
    this.processingTimeMs = processingTimeMs;
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }

  public void setMetadata(Map<String, Object> metadata) {
    this.metadata = metadata;
  }
}
