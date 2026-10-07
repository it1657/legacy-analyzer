package com.legacy.analysis;

import com.legacy.analysis.llm.AnthropicErrorFixtures;
import com.legacy.core.ApiErrorHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-005 (work-order 2026-10-counter-integrity-and-failure-reason v1, REQ-003-a / D6, 보정 C15) —
 * 같은 예외를 {@link RetryHandler}와 {@link ApiErrorHandler}가 <b>서로 다르게 분류하지 않는지</b>
 * 확인한다. 분류기가 두 벌이면 "안쪽에서는 재시도 불가인데 바깥에서는 재시도"처럼 동작이 갈린다.
 *
 * <p>측정 방법(C15): {@code RetryHandler.classifyError(Exception)}는 {@code private}이고
 * {@link AnalysisException}이 <b>아닌</b> 예외에만 쓰인다. 그래서 리플렉션 대신
 * {@link RetryHandler#executeWithRetry} 안에서 <b>상태 코드 있는 원시 예외</b>(픽스처에서 얻은 실제
 * {@link WebClientResponseException})를 던지는 작업을 돌려, 던져진 {@code AnalysisException}의
 * {@code getErrorType()}을 {@code apiErrorHandler.classifyError(e, httpStatusOf(e))}와 비교한다.
 *
 * <p>상태 코드가 없는 로컬 작업 예외(파일 없음, 읽기 타임아웃)는 <b>수정 전과 같은 분류</b>여야 한다 —
 * 이 변경은 "응답을 받은 오류"에만 손을 대기 때문이다. 같은 테스트 안의 양성 대조군이기도 하다(G-04 ②).
 *
 * <p>상태 코드 추출은 사전 RED를 낼 수 있게 <b>테스트 전용 지역 도우미</b>로 둔다
 * ({@link #localHttpStatusOf(Throwable)}). 프로덕션 {@code ApiErrorHandler.httpStatusOf}와의 값 대조는
 * {@code ApiErrorClassificationHttpFixtureTest}가 맡는다.
 */
class RetryHandlerClassificationAgreementTest {

  private static final int MAX_RETRIES = 3;

  private final ApiErrorHandler apiErrorHandler = new ApiErrorHandler();

  // ===================================================================
  // 상태 코드가 있는 예외 — 두 분류기가 일치해야 한다
  // ===================================================================

  @Test
  void 상태코드있는_401에서_두_분류기가_일치한다() {
    assertAgreement(AnthropicErrorFixtures.Case.AUTH_401_1);
  }

  @Test
  void 상태코드있는_403에서_두_분류기가_일치한다() {
    assertAgreement(AnthropicErrorFixtures.Case.FORBIDDEN_403);
  }

  /** 양성 대조군 — 수정 전에도 일치했다. */
  @Test
  void 상태코드있는_429에서_두_분류기가_일치한다() {
    assertAgreement(AnthropicErrorFixtures.Case.RATE_LIMIT_429);
  }

  /** 양성 대조군 — 수정 전에도 일치했다. */
  @Test
  void 상태코드있는_500에서_두_분류기가_일치한다() {
    assertAgreement(AnthropicErrorFixtures.Case.SERVER_500);
  }

  @Test
  void 상태코드있는_크레딧소진_두벌에서_두_분류기가_일치한다() {
    assertAgreement(AnthropicErrorFixtures.Case.CREDIT_400);
    assertAgreement(AnthropicErrorFixtures.Case.CREDIT_400_REQUEST_ID_IO);
  }

  @Test
  void 상태코드있는_픽스처_아홉벌_전부에서_두_분류기가_일치한다() {
    List<String> disagreements = new ArrayList<>();
    List<String> table = new ArrayList<>();

    for (AnthropicErrorFixtures.Case fixture : AnthropicErrorFixtures.Case.values()) {
      WebClientResponseException thrown = AnthropicErrorFixtures.thrownBy(fixture);
      ApiErrorHandler.ErrorType viaRetryHandler = errorTypeFromRetryHandler(thrown);
      ApiErrorHandler.ErrorType viaApiErrorHandler =
          apiErrorHandler.classifyError(thrown, localHttpStatusOf(thrown));

      table.add(fixture + ": RetryHandler=" + viaRetryHandler + " / ApiErrorHandler=" + viaApiErrorHandler);
      if (viaRetryHandler != viaApiErrorHandler) {
        disagreements.add(fixture + " → RetryHandler=" + viaRetryHandler
            + ", ApiErrorHandler=" + viaApiErrorHandler);
      }
    }

    System.out.println("[005 분류 일치] " + table);
    assertTrue(disagreements.isEmpty(),
        "같은 예외를 두 분류기가 다르게 분류한다:\n  " + String.join("\n  ", disagreements));
  }

  // ===================================================================
  // 상태 코드가 없는 로컬 작업 예외 — 수정 전과 같은 분류여야 한다(양성 대조군)
  // ===================================================================

  @Test
  void 상태코드_없는_읽기타임아웃은_수정_전과_같은_NETWORK_TIMEOUT이다() {
    Exception local = new RuntimeException("Read timed out");
    assertEquals(0, localHttpStatusOf(local), "이 예외에는 상태 코드가 없어야 한다");

    ApiErrorHandler.ErrorType viaRetryHandler = errorTypeFromRetryHandler(local);
    System.out.println("[005 상태코드 0] Read timed out → RetryHandler=" + viaRetryHandler);
    assertEquals(ApiErrorHandler.ErrorType.NETWORK_TIMEOUT, viaRetryHandler);
    assertEquals(ApiErrorHandler.ErrorType.NETWORK_TIMEOUT,
        apiErrorHandler.classifyError(local, 0));
  }

  @Test
  void 상태코드_없는_파일없음_예외는_수정_전과_같은_UNKNOWN_ERROR다() {
    // 메시지가 파일 경로뿐이라 두 분류기 모두 어느 규칙에도 걸리지 않는다(수정 전 실측과 같은 값).
    Exception local = new NoSuchFileException("missing-source.java");
    assertEquals(0, localHttpStatusOf(local), "이 예외에는 상태 코드가 없어야 한다");

    ApiErrorHandler.ErrorType viaRetryHandler = errorTypeFromRetryHandler(local);
    System.out.println("[005 상태코드 0] NoSuchFileException → RetryHandler=" + viaRetryHandler
        + " / ApiErrorHandler=" + apiErrorHandler.classifyError(local, 0));
    assertEquals(ApiErrorHandler.ErrorType.UNKNOWN_ERROR, viaRetryHandler);
    assertEquals(ApiErrorHandler.ErrorType.UNKNOWN_ERROR, apiErrorHandler.classifyError(local, 0));
  }

  // ===================================================================
  // 도우미
  // ===================================================================

  private void assertAgreement(AnthropicErrorFixtures.Case fixture) {
    WebClientResponseException thrown = AnthropicErrorFixtures.thrownBy(fixture);
    ApiErrorHandler.ErrorType viaRetryHandler = errorTypeFromRetryHandler(thrown);
    ApiErrorHandler.ErrorType viaApiErrorHandler =
        apiErrorHandler.classifyError(thrown, localHttpStatusOf(thrown));

    System.out.println("[005 분류 일치] " + fixture + " → RetryHandler=" + viaRetryHandler
        + " / ApiErrorHandler=" + viaApiErrorHandler);
    assertEquals(viaApiErrorHandler, viaRetryHandler,
        fixture + ": RetryHandler와 ApiErrorHandler의 분류가 다르다(메시지="
            + AnthropicErrorFixtures.maskRequestIds(thrown.getMessage()) + ")");
  }

  /**
   * {@code RetryHandler}가 그 예외를 어떻게 분류하는지 — 원시 예외를 던지는 작업을
   * {@code executeWithRetry}에 넣어 돌려받은 {@code AnalysisException}의 errorType으로 읽는다.
   * 재시도 가능한 분류면 루프를 몇 번 돌 뿐 최종 errorType은 같다(대기는 1~10ms).
   */
  private ApiErrorHandler.ErrorType errorTypeFromRetryHandler(Exception raw) {
    SessionConfig sessionConfig = new SessionConfig();
    sessionConfig.setMaxRetries(MAX_RETRIES);
    sessionConfig.setInitialRetryDelayMs(1);
    sessionConfig.setMaxRetryDelayMs(10);

    RetryHandler retryHandler = new RetryHandler(apiErrorHandler, sessionConfig);
    AnalysisException thrown = assertThrows(AnalysisException.class,
        () -> retryHandler.executeWithRetry("session-agreement", "Sample.java", () -> {
          throw raw;
        }));
    return thrown.getErrorType();
  }

  /** 테스트 전용 상태 코드 추출 — 사전 RED를 낼 수 있게 프로덕션과 독립으로 둔다. */
  private static int localHttpStatusOf(Throwable t) {
    Throwable cursor = t;
    for (int depth = 0; cursor != null && depth < 10; depth++) {
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
}
