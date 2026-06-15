/* [AI 한글 주석 보완 완료] */
// 분석 과정 중 발생하는 커스텀 예외
package com.legacy.analysis;
import com.legacy.core.ApiErrorHandler;

/**
 * 코드 분석 중 발생하는 커스텀 예외
 * API 에러, 파일 I/O 에러 등을 래핑
 */
public class AnalysisException extends RuntimeException {

  private final ApiErrorHandler.ErrorType errorType;

  public AnalysisException(ApiErrorHandler.ErrorType errorType, Exception cause) {
    super(cause.getMessage(), cause);
    this.errorType = errorType;
  }

  public ApiErrorHandler.ErrorType getErrorType() {
    return errorType;
  }
}
