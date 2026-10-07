package com.legacy.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * API 에러 분류, 재시도 전략 결정 및 사용자 친화적 메시지 생성
 */
@Component
public class ApiErrorHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

  public enum ErrorType {
    NETWORK_TIMEOUT,        // 네트워크 타임아웃
    CONNECT_FAILURE,        // 연결 실패
    API_RATE_LIMIT,         // API 할당량 초과 (429)
    API_AUTHENTICATION,     // 인증 실패 (401)
    API_FORBIDDEN,          // 권한 없음 (403)
    INVALID_REQUEST,        // 잘못된 요청 (400)
    INSUFFICIENT_CREDITS,   // 크레딧 부족 (400 + credit balance 메시지)
    SERVER_ERROR,           // 서버 오류 (5xx)
    FILE_READ_ERROR,        // 파일 읽기 오류
    FILE_WRITE_ERROR,       // 파일 쓰기 오류
    UNKNOWN_ERROR           // 미분류 오류
  }

  /**
   * 예외를 분석하여 에러 타입 결정
   */
  public ErrorType classifyError(Exception exception, int httpStatus) {
    if (exception == null) {
      return classifyErrorByStatus(httpStatus);
    }

    String exceptionMessage = exception.getMessage().toLowerCase();

    // 네트워크 관련 오류
    if (exceptionMessage.contains("timeout") || exceptionMessage.contains("timed out")) {
      return ErrorType.NETWORK_TIMEOUT;
    }
    if (exceptionMessage.contains("connection") || exceptionMessage.contains("refused")) {
      return ErrorType.CONNECT_FAILURE;
    }
    if (exceptionMessage.contains("io")) {
      return ErrorType.CONNECT_FAILURE;
    }

    // 파일 관련 오류
    if (exceptionMessage.contains("no such file") || exceptionMessage.contains("cannot find")) {
      return ErrorType.FILE_READ_ERROR;
    }
    if (exceptionMessage.contains("permission denied") || exceptionMessage.contains("access denied")) {
      return ErrorType.FILE_READ_ERROR;
    }

    // 크레딧 부족 감지 (400 + "credit balance" 메시지)
    if (exceptionMessage.contains("credit balance") || exceptionMessage.contains("too low to access")) {
      return ErrorType.INSUFFICIENT_CREDITS;
    }

    return classifyErrorByStatus(httpStatus);
  }

  /**
   * HTTP 상태 코드로 에러 타입 결정
   */
  private ErrorType classifyErrorByStatus(int httpStatus) {
    return switch (httpStatus) {
      case 400 -> ErrorType.INVALID_REQUEST;
      case 401 -> ErrorType.API_AUTHENTICATION;
      case 403 -> ErrorType.API_FORBIDDEN;
      case 429 -> ErrorType.API_RATE_LIMIT;
      case 500, 502, 503, 504 -> ErrorType.SERVER_ERROR;
      default -> ErrorType.UNKNOWN_ERROR;
    };
  }

  /**
   * 에러 타입별 재시도 가능 여부 판단
   */
  public boolean isRetryable(ErrorType errorType) {
    return switch (errorType) {
      case NETWORK_TIMEOUT,        // 재시도 가능
           CONNECT_FAILURE,        // 재시도 가능
           API_RATE_LIMIT,         // 지수적 백오프로 재시도
           SERVER_ERROR -> true;   // 재시도 가능
      case API_AUTHENTICATION,     // 재시도 불가능 (인증 키 문제)
           API_FORBIDDEN,          // 재시도 불가능 (권한 문제)
           INVALID_REQUEST,        // 재시도 불가능 (요청 자체 문제)
           INSUFFICIENT_CREDITS,   // 재시도 불가능 (크레딧 충전 필요)
           FILE_READ_ERROR,        // 재시도 불가능 (파일 접근 불가)
           FILE_WRITE_ERROR,       // 재시도 불가능 (파일 쓰기 권한)
           UNKNOWN_ERROR -> false;
    };
  }

  /**
   * 에러 타입별 재시도 지연 시간 계산 (지수적 백오프)
   */
  public long calculateRetryDelay(ErrorType errorType, int retryCount,
    long initialDelayMs, long maxDelayMs) {

    if (!isRetryable(errorType)) {
      return 0;
    }

    // 기본: 2^retryCount * initialDelayMs
    long delayMs = initialDelayMs * (long) Math.pow(2, Math.min(retryCount, 5));

    // Jitter 추가 (충돌 방지)
    long jitterMs = new Random().nextLong(Math.max(1, delayMs / 4));
    delayMs += jitterMs;

    // Rate limit 오류는 더 긴 대기
    if (errorType == ErrorType.API_RATE_LIMIT) {
      delayMs = Math.min(delayMs * 2, maxDelayMs);
    }

    return Math.min(delayMs, maxDelayMs);
  }

  /**
   * 사용자 친화적 에러 메시지 생성
   */
  public String getUserFriendlyMessage(ErrorType errorType, String fileName) {
    return switch (errorType) {
      case NETWORK_TIMEOUT -> String.format(
        "[네트워크 타임아웃] %s 분석 중 응답 시간 초과. 자동 재시도 중...", fileName);
      case CONNECT_FAILURE -> String.format(
        "[연결 실패] %s 분석 중 서버 연결 실패. 자동 재시도 중...", fileName);
      case API_RATE_LIMIT -> String.format(
        "[API 할당량 초과] %s 분석이 대기 중. 곧 진행됩니다.", fileName);
      case API_AUTHENTICATION -> String.format(
        "[인증 실패] Claude API 키 검증 실패. 설정을 확인하세요.") ;
      case API_FORBIDDEN -> String.format(
        "[권한 없음] Claude API 접근 권한이 없습니다.");
      case INVALID_REQUEST -> String.format(
        "[요청 오류] %s 분석 요청이 유효하지 않습니다.", fileName);
      case INSUFFICIENT_CREDITS ->
        "💳 [크레딧 부족] Claude API 크레딧이 소진되었습니다. Plans & Billing에서 충전 후 분석을 재시도하세요.";
      case SERVER_ERROR -> String.format(
        "[서버 오류] Claude 서버 일시적 오류. 자동 재시도 중...");
      case FILE_READ_ERROR -> String.format(
        "[파일 읽기 오류] %s 파일을 읽을 수 없습니다. 파일 접근 권한을 확인하세요.", fileName);
      case FILE_WRITE_ERROR -> String.format(
        "[파일 쓰기 오류] 결과를 저장할 수 없습니다. 디스크 공간과 권한을 확인하세요.");
      case UNKNOWN_ERROR -> String.format(
        "[알 수 없는 오류] %s 분석 중 오류가 발생했습니다.", fileName);
    };
  }

  /**
   * 상세 로그 메시지 생성
   */
  public String getDetailedErrorLog(ErrorType errorType, Exception exception,
    String fileName, int retryCount) {
    StringBuilder sb = new StringBuilder();
    sb.append("[").append(errorType.name()).append("] ");
    sb.append("파일: ").append(fileName).append(", ");
    sb.append("재시도: ").append(retryCount).append("회");

    if (exception != null) {
      sb.append(", 원인: ").append(exception.getMessage());
    }

    return sb.toString();
  }

  /**
   * 로깅
   */
  public void logError(ErrorType errorType, String fileName, Exception exception,
    int retryCount, boolean isRetrying) {
    String detailedLog = getDetailedErrorLog(errorType, exception, fileName, retryCount);

    if (isRetrying) {
      log.warn(detailedLog);
    } else {
      log.error(detailedLog, exception);
    }
  }
}
