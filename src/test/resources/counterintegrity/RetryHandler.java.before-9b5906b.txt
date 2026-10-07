package com.legacy.analysis;
import com.legacy.core.ApiErrorHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 재시도 전략을 관리하는 컴포넌트
 * 지수적 백오프를 통한 자동 재시도 제공
 */
@Component
public class RetryHandler {

  private static final Logger log = LoggerFactory.getLogger(RetryHandler.class);

  private final ApiErrorHandler apiErrorHandler;
  private final SessionConfig sessionConfig;

  @Autowired
  public RetryHandler(ApiErrorHandler apiErrorHandler, SessionConfig sessionConfig) {
    this.apiErrorHandler = apiErrorHandler;
    this.sessionConfig = sessionConfig;
  }

  /**
   * 재시도 전략으로 작업 실행
   */
  public <T> T executeWithRetry(String sessionId, String filePath, RetryableTask<T> task) {
    int retryCount = 0;
    long lastDelayMs = 0;

    while (retryCount <= sessionConfig.getMaxRetries()) {
      try {
        return task.execute();
      } catch (Exception e) {
        // 이미 정확히 분류된 예외(예: ClaudeServiceImpl에서 credit balance 등을 보고 분류한 결과)는
        // 그 분류를 그대로 신뢰한다. 여기서 메시지 문자열만으로 다시 분류하면 원래 정확했던
        // 분류(INSUFFICIENT_CREDITS 등)가 뭉개져서 INVALID_REQUEST 같은 값으로 퇴화할 수 있다.
        ApiErrorHandler.ErrorType errorType = (e instanceof AnalysisException ae)
            ? ae.getErrorType() : classifyError(e);

        // 재시도 불가능하거나 최대 재시도 초과
        if (!apiErrorHandler.isRetryable(errorType) ||
            retryCount >= sessionConfig.getMaxRetries()) {
          log.error("[작업 최종 실패] {} - 재시도 횟수: {}, 에러: {}",
              filePath, retryCount, errorType);
          throw new AnalysisException(errorType, e);
        }

        // 재시도 대기 시간 계산
        lastDelayMs = apiErrorHandler.calculateRetryDelay(
            errorType,
            retryCount,
            sessionConfig.getInitialRetryDelayMs(),
            sessionConfig.getMaxRetryDelayMs());

        retryCount++;

        log.warn("[재시도 예정] {} - 시도: {}/{}, 대기: {}ms, 에러: {}",
            filePath, retryCount, sessionConfig.getMaxRetries(), lastDelayMs, errorType);

        // 중단 신호 확인
        if (Thread.currentThread().isInterrupted()) {
          throw new AnalysisException(ApiErrorHandler.ErrorType.UNKNOWN_ERROR,
              new InterruptedException("분석이 중단되었습니다."));
        }

        try {
          Thread.sleep(lastDelayMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new AnalysisException(ApiErrorHandler.ErrorType.UNKNOWN_ERROR, ie);
        }
      }
    }

    throw new AnalysisException(ApiErrorHandler.ErrorType.UNKNOWN_ERROR,
        new RuntimeException("최대 재시도 횟수를 초과했습니다."));
  }

  /**
   * 예외를 에러 타입으로 분류
   */
  private ApiErrorHandler.ErrorType classifyError(Exception e) {
    String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

    if (message.contains("timeout") || message.contains("timed out")) {
      return ApiErrorHandler.ErrorType.NETWORK_TIMEOUT;
    } else if (message.contains("connection") || message.contains("refused")) {
      return ApiErrorHandler.ErrorType.CONNECT_FAILURE;
    } else if (message.contains("rate limit") || message.contains("429")) {
      return ApiErrorHandler.ErrorType.API_RATE_LIMIT;
    } else if (message.contains("authentication") || message.contains("401")) {
      return ApiErrorHandler.ErrorType.API_AUTHENTICATION;
    } else if (message.contains("forbidden") || message.contains("403")) {
      return ApiErrorHandler.ErrorType.API_FORBIDDEN;
    } else if (message.contains("bad request") || message.contains("400")) {
      return ApiErrorHandler.ErrorType.INVALID_REQUEST;
    } else if (message.contains("server error") || message.contains("500")) {
      return ApiErrorHandler.ErrorType.SERVER_ERROR;
    } else if (message.contains("no such file") || message.contains("cannot find") ||
               message.contains("permission denied") || message.contains("access denied")) {
      return ApiErrorHandler.ErrorType.FILE_READ_ERROR;
    } else {
      return ApiErrorHandler.ErrorType.UNKNOWN_ERROR;
    }
  }

  /**
   * 재시도 가능한 작업을 정의하는 함수형 인터페이스
   */
  @FunctionalInterface
  public interface RetryableTask<T> {
    T execute() throws Exception;
  }
}
