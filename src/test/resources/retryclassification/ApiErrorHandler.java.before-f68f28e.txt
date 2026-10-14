package com.legacy.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.util.*;

/**
 * API 에러 분류, 재시도 전략 결정 및 사용자 친화적 메시지 생성
 */
@Component
public class ApiErrorHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

  /** 원인 사슬을 따라 내려갈 최대 깊이 — 사슬이 서로를 가리키는 경우에도 멈추게 하는 안전장치. */
  private static final int MAX_CAUSE_DEPTH = 10;

  /**
   * 실패 사유 요약에 붙이는 유형 이름표. 사용자가 읽는 안내에는 예외 원문만 나가는데, 원문은
   * 영문·JSON이라 "무슨 종류의 문제인지"가 읽히지 않는다. 여기 없는 유형은 이름표를 붙이지 않는다
   * (UNKNOWN_ERROR·INSUFFICIENT_CREDITS·SOURCE_MISSING 등 — 전용 안내가 따로 있거나 이름표를 붙여도
   * 정보가 늘지 않는 경우다).
   */
  private static final Map<String, String> FAILURE_REASON_LABELS = Map.of(
    "API_AUTHENTICATION", "인증 실패(API 키 확인 필요)",
    "API_FORBIDDEN", "권한 없음(API 접근 권한 확인 필요)",
    "API_RATE_LIMIT", "요청 한도 초과",
    "INVALID_REQUEST", "요청 오류",
    "SERVER_ERROR", "Claude 서버 오류",
    "NETWORK_TIMEOUT", "응답 시간 초과",
    "CONNECT_FAILURE", "연결 실패",
    "FILE_READ_ERROR", "파일 읽기 오류",
    "FILE_WRITE_ERROR", "파일 쓰기 오류");

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
   *
   * 분류 순서가 곧 규칙이다. 응답을 받은 오류(httpStatus > 0)는 상태 코드가 분류를 결정한다 —
   * 예외 메시지에는 응답 본문이 섞여 들어오기 때문에 본문 글자로 먼저 판단하면 오분류가 난다
   * (실측: 401 본문의 "authentication_error" 안에 "io"가 있어 아래 "io" 규칙에 걸려
   * CONNECT_FAILURE로 분류됐고, 재시도 불가인 인증 실패가 재시도 가능으로 뒤집혔다).
   *
   * 크레딧 소진만 상태 코드보다 앞에서 본다. 크레딧 소진도 400으로 오는데 상태 코드만으로는
   * 일반 INVALID_REQUEST와 구별할 수 없고, 구별해야 failover 안내로 갈 수 있다.
   *
   * 응답을 받지 못한 오류(httpStatus == 0)는 상태 코드가 없으니 종전처럼 메시지 규칙을 쓴다.
   */
  public ErrorType classifyError(Exception exception, int httpStatus) {
    if (exception == null) {
      return classifyErrorByStatus(httpStatus);
    }

    String exceptionMessage =
      exception.getMessage() == null ? "" : exception.getMessage().toLowerCase();

    // 크레딧 부족 감지 (400 + "credit balance" 메시지) — 상태 코드보다 메시지가 정확한 유일한 경우
    if (exceptionMessage.contains("credit balance") || exceptionMessage.contains("too low to access")) {
      return ErrorType.INSUFFICIENT_CREDITS;
    }

    // 응답을 받은 오류는 상태 코드가 결정한다
    if (httpStatus > 0) {
      return classifyErrorByStatus(httpStatus);
    }

    // 여기부터는 응답 자체를 받지 못한 오류 — 메시지로만 판단할 수 있다
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

    return classifyErrorByStatus(httpStatus);
  }

  /**
   * 예외(와 그 원인 사슬)에서 HTTP 상태 코드를 꺼낸다. 응답을 받지 못한 오류는 0이다.
   *
   * 재시도 계층이 예외를 여러 겹 감싸므로 자기 자신만 보면 상태 코드를 놓친다.
   * 사슬이 자기 자신을 가리키거나 서로를 가리키는 경우를 대비해 깊이 상한을 둔다.
   */
  public static int httpStatusOf(Throwable t) {
    Throwable cursor = t;
    for (int depth = 0; cursor != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (cursor instanceof WebClientResponseException responseException) {
        return responseException.getStatusCode().value();
      }
      Throwable cause = cursor.getCause();
      if (cause == cursor) {
        break;
      }
      cursor = cause;
    }
    return 0;
  }

  /**
   * 실패 사유 요약에 붙일 유형 이름표. 이름표가 없는 유형이면 {@code null}.
   *
   * @param errorTypeName {@link ErrorType}의 이름({@code name()}). null·공백이면 {@code null}
   */
  public static String failureReasonLabel(String errorTypeName) {
    if (errorTypeName == null) {
      return null;
    }
    return FAILURE_REASON_LABELS.get(errorTypeName.trim());
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
