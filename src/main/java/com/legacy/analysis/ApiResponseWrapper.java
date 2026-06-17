package com.legacy.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 모든 API 응답을 표준화하는 제네릭 래퍼
 */
public class ApiResponseWrapper<T> {

  @JsonProperty("success")
  private boolean success;

  @JsonProperty("data")
  private T data;

  @JsonProperty("error")
  private ErrorInfo error;

  @JsonProperty("timestamp")
  private LocalDateTime timestamp;

  @JsonProperty("requestId")
  private String requestId;

  public ApiResponseWrapper() {
    this.timestamp = LocalDateTime.now();
    this.requestId = UUID.randomUUID().toString();
  }

  /**
   * 성공 응답 생성
   */
  public static <T> ApiResponseWrapper<T> success(T data) {
    ApiResponseWrapper<T> response = new ApiResponseWrapper<>();
    response.success = true;
    response.data = data;
    response.error = null;
    return response;
  }

  /**
   * 에러 응답 생성
   */
  public static <T> ApiResponseWrapper<T> error(String message, ErrorInfo errorInfo) {
    ApiResponseWrapper<T> response = new ApiResponseWrapper<>();
    response.success = false;
    response.data = null;
    response.error = errorInfo != null ? errorInfo : new ErrorInfo(null, message, null);
    return response;
  }

  // Getters and Setters
  public boolean isSuccess() {
    return success;
  }

  public void setSuccess(boolean success) {
    this.success = success;
  }

  public T getData() {
    return data;
  }

  public void setData(T data) {
    this.data = data;
  }

  public ErrorInfo getError() {
    return error;
  }

  public void setError(ErrorInfo error) {
    this.error = error;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }

  public String getRequestId() {
    return requestId;
  }

  public void setRequestId(String requestId) {
    this.requestId = requestId;
  }

  /**
   * API 에러 정보 내부 클래스
   */
  public static class ErrorInfo {
    @JsonProperty("code")
    private String code;

    @JsonProperty("message")
    private String message;

    @JsonProperty("details")
    private Map<String, Object> details;

    public ErrorInfo() {
    }

    public ErrorInfo(String code, String message, Map<String, Object> details) {
      this.code = code;
      this.message = message;
      this.details = details != null ? details : new HashMap<>();
    }

    public String getCode() {
      return code;
    }

    public void setCode(String code) {
      this.code = code;
    }

    public String getMessage() {
      return message;
    }

    public void setMessage(String message) {
      this.message = message;
    }

    public Map<String, Object> getDetails() {
      return details;
    }

    public void setDetails(Map<String, Object> details) {
      this.details = details;
    }
  }
}
