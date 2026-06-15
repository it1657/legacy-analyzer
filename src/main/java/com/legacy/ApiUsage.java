package com.legacy;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "api_usage")
public class ApiUsage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id")
  private Long userId;

  @Column(name = "endpoint", length = 255)
  private String endpoint;

  @Column(name = "method", length = 10)
  private String method;

  @Column(name = "request_size")
  private long requestSize;

  @Column(name = "response_size")
  private long responseSize;

  @Column(name = "status_code")
  private int statusCode;

  @Column(name = "execution_time_ms")
  private long executionTimeMs;

  @Column(name = "timestamp")
  private LocalDateTime timestamp;

  @Column(name = "ip_address", length = 50)
  private String ipAddress;

  // 생성자
  public ApiUsage() {
  }

  public ApiUsage(Long userId, String endpoint, String method,
      long requestSize, long responseSize, int statusCode, long executionTimeMs,
      String ipAddress) {
    this.userId = userId;
    this.endpoint = endpoint;
    this.method = method;
    this.requestSize = requestSize;
    this.responseSize = responseSize;
    this.statusCode = statusCode;
    this.executionTimeMs = executionTimeMs;
    this.ipAddress = ipAddress;
    this.timestamp = LocalDateTime.now();
  }

  // Getter/Setter
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }

  public String getMethod() {
    return method;
  }

  public void setMethod(String method) {
    this.method = method;
  }

  public long getRequestSize() {
    return requestSize;
  }

  public void setRequestSize(long requestSize) {
    this.requestSize = requestSize;
  }

  public long getResponseSize() {
    return responseSize;
  }

  public void setResponseSize(long responseSize) {
    this.responseSize = responseSize;
  }

  public int getStatusCode() {
    return statusCode;
  }

  public void setStatusCode(int statusCode) {
    this.statusCode = statusCode;
  }

  public long getExecutionTimeMs() {
    return executionTimeMs;
  }

  public void setExecutionTimeMs(long executionTimeMs) {
    this.executionTimeMs = executionTimeMs;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public void setIpAddress(String ipAddress) {
    this.ipAddress = ipAddress;
  }
}
