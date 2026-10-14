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

  /** 세션 조회 경로 — 선택 주입이라 없을 수 있고, 없으면 항상 "중단 아님"이다. */
  private AnalysisSessionManager sessionManager;

  /** 기본 판정 팩토리 — 처음 판정할 때 1번만 세션을 조회하고, 이후 그 세션의 shouldStop만 본다. */
  private java.util.function.Function<String, java.util.function.BooleanSupplier> stopCheckFactory =
      sessionId -> {
        SessionState[] resolved = new SessionState[1];
        boolean[] looked = new boolean[1];
        return () -> {
          if (!looked[0]) {
            looked[0] = true;
            resolved[0] = sessionManager == null ? null : sessionManager.getSession(sessionId);
          }
          return resolved[0] != null && resolved[0].shouldStop();
        };
      };

  @Autowired
  public RetryHandler(ApiErrorHandler apiErrorHandler, SessionConfig sessionConfig) {
    this.apiErrorHandler = apiErrorHandler;
    this.sessionConfig = sessionConfig;
  }

  /** 기존 2인자 생성자를 쓰는 배선을 그대로 두기 위해 세션 관리자는 선택 주입으로 받는다. */
  @Autowired(required = false)
  public void setSessionManager(AnalysisSessionManager sessionManager) {
    this.sessionManager = sessionManager;
  }

  /** 테스트용 판정 함수 주입. */
  void setStopCheckFactory(
      java.util.function.Function<String, java.util.function.BooleanSupplier> factory) {
    this.stopCheckFactory = factory;
  }

  /**
   * 재시도 전략으로 작업 실행
   */
  public <T> T executeWithRetry(String sessionId, String filePath, RetryableTask<T> task) {
    int retryCount = 0;
    long lastDelayMs = 0;

    while (retryCount <= sessionConfig.getMaxRetries()) {
      try {
        return RetryStopSignal.runWithStopCheck(stopCheckFactory.apply(sessionId), task);
      } catch (Exception e) {
        // 이미 정확히 분류된 예외(예: ClaudeServiceImpl에서 credit balance 등을 보고 분류한 결과)는
        // 그 분류를 그대로 신뢰한다. 여기서 메시지 문자열만으로 다시 분류하면 원래 정확했던
        // 분류(INSUFFICIENT_CREDITS 등)가 뭉개져서 INVALID_REQUEST 같은 값으로 퇴화할 수 있다.
        ApiErrorHandler.ErrorType errorType = (e instanceof AnalysisException ae)
            ? ae.getErrorType() : classifyError(e);

        // 재시도 불가능하거나 최대 재시도 초과
        // 안쪽(ClaudeServiceImpl)이 이미 재시도까지 판정한 AnalysisException을 여기서 다시 돌리면
        // 안쪽 횟수와 곱이 된다 — 그 분류를 그대로 최종 실패로 쓴다.
        if (e instanceof AnalysisException ||
            !apiErrorHandler.isRetryable(errorType) ||
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
    // 응답을 받은 오류(상태 코드가 있는 예외)는 ApiErrorHandler 한 곳에서만 분류한다.
    // 아래 메시지 규칙과 ApiErrorHandler의 규칙이 서로 달라서, 같은 예외가 여기서는 재시도 불가로,
    // 안쪽에서는 재시도 가능으로 갈리는 일이 있었다(실측: 401 → 여기 API_AUTHENTICATION /
    // 안쪽 CONNECT_FAILURE).
    int status = ApiErrorHandler.httpStatusOf(e);
    if (status > 0) {
      return apiErrorHandler.classifyError(e, status);
    }

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
