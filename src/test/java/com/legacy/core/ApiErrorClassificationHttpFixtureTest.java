package com.legacy.core;

import com.legacy.analysis.llm.AnthropicErrorFixtures;
import com.legacy.analysis.llm.AnthropicLlmClient;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-005 (work-order 2026-10-counter-integrity-and-failure-reason v1, REQ-003-a / 게이트1 D6) —
 * {@link ApiErrorHandler#classifyError(Exception, int)}가 <b>응답을 받은 오류는 상태 코드로</b>
 * 분류하는지, 손으로 만든 문자열이 아니라 <b>실제로 던져진 예외 객체</b>로 확인한다.
 *
 * <p>왜 실제 예외여야 하는가: 분류기가 보는 것은 {@code exception.getMessage()}이고, 그 문자열은
 * {@code AnthropicLlmClient}가 만드는 {@code "Claude API %d 오류: {본문 최대 300자}"} 와 Spring이
 * {@code WebClientResponseException}에 붙이는 앞머리({@code "401 "})가 겹친 결과다. 수정 전 코드가
 * 401을 {@code CONNECT_FAILURE}로 분류한 원인이 바로 <b>응답 본문에 섞여 들어온 글자</b>
 * ({@code "authentication_error"} 안의 {@code io})이므로, 본문이 실물과 같아야 이 테스트가 의미를 갖는다.
 * 픽스처는 TASK-002의 {@link AnthropicErrorFixtures}(MockWebServer + 실제 클라이언트)를 쓴다.
 *
 * <p>같은 테스트 안에 <b>바뀌면 안 되는 경로</b>(429·500·일반 크레딧·상태 코드 0 두 경로)를 함께 둬서,
 * 단언이 "항상 실패/항상 통과"가 아님을 보인다(G-04 ②, RG-12).
 *
 * <p>상태 코드 추출은 사전 RED를 낼 수 있어야 하므로 <b>테스트 전용 지역 도우미</b>
 * {@link #localHttpStatusOf(Throwable)}로 둔다. 프로덕션 {@code ApiErrorHandler.httpStatusOf}와
 * 값이 같은지는 {@link #두_상태코드_추출기가_모든_픽스처에서_같은_값을_낸다()}가 대조한다
 * (이 한 건은 {@code httpStatusOf}가 없으면 컴파일되지 않으므로 수정 후에 추가했다 — 사전 RED 대상 아님).
 */
class ApiErrorClassificationHttpFixtureTest {

  private final ApiErrorHandler apiErrorHandler = new ApiErrorHandler();

  // ===================================================================
  // 상태 코드가 있는 오류 — 상태 코드가 분류를 결정한다
  // ===================================================================

  @Test
  void 픽스처_401은_API_AUTHENTICATION으로_분류된다() {
    assertClassified(AnthropicErrorFixtures.Case.AUTH_401_1,
        ApiErrorHandler.ErrorType.API_AUTHENTICATION);
  }

  @Test
  void 픽스처_401_네벌이_모두_API_AUTHENTICATION으로_분류된다() {
    List<String> results = new ArrayList<>();
    for (AnthropicErrorFixtures.Case fixture : List.of(
        AnthropicErrorFixtures.Case.AUTH_401_1, AnthropicErrorFixtures.Case.AUTH_401_2,
        AnthropicErrorFixtures.Case.AUTH_401_3, AnthropicErrorFixtures.Case.AUTH_401_4)) {
      WebClientResponseException thrown = AnthropicErrorFixtures.thrownBy(fixture);
      ApiErrorHandler.ErrorType actual =
          apiErrorHandler.classifyError(thrown, localHttpStatusOf(thrown));
      results.add(fixture + "=" + actual);
    }
    System.out.println("[005 분류] 401 4벌 → " + results);
    for (String r : results) {
      assertTrue(r.endsWith("=API_AUTHENTICATION"),
          "401 4벌은 request_id만 달라도 모두 API_AUTHENTICATION이어야 한다: " + results);
    }
  }

  @Test
  void 픽스처_403은_API_FORBIDDEN으로_분류된다() {
    assertClassified(AnthropicErrorFixtures.Case.FORBIDDEN_403,
        ApiErrorHandler.ErrorType.API_FORBIDDEN);
  }

  /** 양성 대조군 — 수정 전후 모두 {@code API_RATE_LIMIT}이어야 한다(RG-12). */
  @Test
  void 픽스처_429는_API_RATE_LIMIT으로_분류된다() {
    assertClassified(AnthropicErrorFixtures.Case.RATE_LIMIT_429,
        ApiErrorHandler.ErrorType.API_RATE_LIMIT);
  }

  /** 양성 대조군 — 수정 전후 모두 {@code SERVER_ERROR}여야 한다(RG-12). */
  @Test
  void 픽스처_500은_SERVER_ERROR로_분류된다() {
    assertClassified(AnthropicErrorFixtures.Case.SERVER_500,
        ApiErrorHandler.ErrorType.SERVER_ERROR);
  }

  // ===================================================================
  // 크레딧 소진 — 상태 코드(400)보다 메시지가 정확한 유일한 예외다
  // ===================================================================

  /** 양성 대조군 — 수정 전에도 {@code INSUFFICIENT_CREDITS}였다. */
  @Test
  void 픽스처_크레딧소진_일반벌은_INSUFFICIENT_CREDITS로_분류된다() {
    assertClassified(AnthropicErrorFixtures.Case.CREDIT_400,
        ApiErrorHandler.ErrorType.INSUFFICIENT_CREDITS);
  }

  /** {@code request_id}에 소문자 {@code io}가 들어간 벌 — 현행 {@code "io"} 검사와 겹치는 경우. */
  @Test
  void 픽스처_크레딧소진_requestid에_io가_든_벌도_INSUFFICIENT_CREDITS로_분류된다() {
    assertClassified(AnthropicErrorFixtures.Case.CREDIT_400_REQUEST_ID_IO,
        ApiErrorHandler.ErrorType.INSUFFICIENT_CREDITS);
  }

  // ===================================================================
  // 상태 코드 0(응답을 못 받은 오류) — 기존 메시지 규칙 그대로여야 한다
  // ===================================================================

  /** 양성 대조군 — 실제 연결 거부 예외. 상태 코드가 없으므로 수정 전후 같은 분류여야 한다. */
  @Test
  void 상태코드_0인_실제_연결거부_예외는_CONNECT_FAILURE로_분류된다() {
    Exception refused = connectRefusedException();
    int status = localHttpStatusOf(refused);
    assertEquals(0, status, "연결 거부 예외에는 HTTP 상태 코드가 없어야 한다. 원문=" + refused);

    ApiErrorHandler.ErrorType actual = apiErrorHandler.classifyError(refused, status);
    System.out.println("[005 분류] 연결 거부 → " + actual + " | 예외=" + refused.getClass().getName()
        + " | 메시지=" + firstLine(refused.getMessage()));
    assertEquals(ApiErrorHandler.ErrorType.CONNECT_FAILURE, actual);
  }

  /** 양성 대조군 — 기존 하네스들이 LLM 대역에서 던지는 형태(C4). */
  @Test
  void 상태코드_0인_읽기타임아웃_예외는_NETWORK_TIMEOUT으로_분류된다() {
    Exception timeout = new RuntimeException("Read timed out");
    assertEquals(0, localHttpStatusOf(timeout));
    assertEquals(ApiErrorHandler.ErrorType.NETWORK_TIMEOUT,
        apiErrorHandler.classifyError(timeout, 0));
  }

  @Test
  void 메시지가_null인_예외도_NPE_없이_분류된다() {
    Exception noMessage = new RuntimeException();
    assertNotNull(noMessage);

    ApiErrorHandler.ErrorType actual = apiErrorHandler.classifyError(noMessage, 0);
    System.out.println("[005 분류] 메시지 null → " + actual);
    assertEquals(ApiErrorHandler.ErrorType.UNKNOWN_ERROR, actual);
  }

  @Test
  void 예외가_null이면_상태코드만으로_분류한다() {
    assertEquals(ApiErrorHandler.ErrorType.API_AUTHENTICATION, apiErrorHandler.classifyError(null, 401));
    assertEquals(ApiErrorHandler.ErrorType.UNKNOWN_ERROR, apiErrorHandler.classifyError(null, 0));
  }

  /**
   * 상태 코드 표({@code classifyErrorByStatus})에 408·529가 들어갔다(2026-10-retry-and-classification
   * TASK-005, 의도된 동작 변화 ②) — 408은 {@code NETWORK_TIMEOUT}, 529는 {@code SERVER_ERROR}이고
   * 둘 다 재시도 대상이다. 표에 없는 코드(409)는 종전처럼 {@code UNKNOWN_ERROR}로 떨어진다.
   */
  @Test
  void 상태코드_408은_NETWORK_TIMEOUT_529는_SERVER_ERROR로_분류된다() {
    assertEquals(ApiErrorHandler.ErrorType.NETWORK_TIMEOUT, apiErrorHandler.classifyError(null, 408));
    assertEquals(ApiErrorHandler.ErrorType.SERVER_ERROR, apiErrorHandler.classifyError(null, 529));

    // 표에 없는 코드는 그대로 UNKNOWN_ERROR다(표가 통째로 바뀐 것이 아님을 보이는 대조).
    assertEquals(ApiErrorHandler.ErrorType.UNKNOWN_ERROR, apiErrorHandler.classifyError(null, 409));

    // 같은 표의 기존 항목은 그대로다.
    assertEquals(ApiErrorHandler.ErrorType.SERVER_ERROR, apiErrorHandler.classifyError(null, 503));
    assertEquals(ApiErrorHandler.ErrorType.INVALID_REQUEST, apiErrorHandler.classifyError(null, 400));

    System.out.println("[005 상태코드 표] 408=" + apiErrorHandler.classifyError(null, 408)
        + ", 529=" + apiErrorHandler.classifyError(null, 529)
        + ", 409=" + apiErrorHandler.classifyError(null, 409)
        + ", 503=" + apiErrorHandler.classifyError(null, 503)
        + ", 400=" + apiErrorHandler.classifyError(null, 400)
        + " | isRetryable(NETWORK_TIMEOUT)=" + apiErrorHandler.isRetryable(ApiErrorHandler.ErrorType.NETWORK_TIMEOUT)
        + ", isRetryable(UNKNOWN_ERROR)=" + apiErrorHandler.isRetryable(ApiErrorHandler.ErrorType.UNKNOWN_ERROR)
        + ", isRetryable(CONNECT_FAILURE)=" + apiErrorHandler.isRetryable(ApiErrorHandler.ErrorType.CONNECT_FAILURE));
  }

  // ===================================================================
  // 상태 코드 추출기 대조
  // (이 두 건은 ApiErrorHandler.httpStatusOf가 없으면 컴파일되지 않으므로 프로덕션 수정 후에
  //  추가했다 — 사전 RED 집합에는 들어가지 않는다. 방식은 work-order TASK-005 「사전 RED」가
  //  허용한 "테스트 지역 도우미로 사전 RED를 낸 뒤 두 값이 같음을 단언" 쪽이다.)
  // ===================================================================

  @Test
  void 두_상태코드_추출기가_모든_픽스처에서_같은_값을_낸다() {
    List<String> table = new ArrayList<>();
    for (AnthropicErrorFixtures.Case fixture : AnthropicErrorFixtures.Case.values()) {
      WebClientResponseException thrown = AnthropicErrorFixtures.thrownBy(fixture);
      int production = ApiErrorHandler.httpStatusOf(thrown);
      int local = localHttpStatusOf(thrown);
      table.add(fixture + "=" + production);
      assertEquals(local, production, fixture + ": 테스트 지역 도우미와 프로덕션 추출기가 다르다");
      assertEquals(fixture.status(), production, fixture + ": 픽스처 상태 코드와 다르다");
    }

    // 상태 코드가 없는 예외는 둘 다 0이어야 한다.
    Exception refused = connectRefusedException();
    assertEquals(0, ApiErrorHandler.httpStatusOf(refused));
    assertEquals(localHttpStatusOf(refused), ApiErrorHandler.httpStatusOf(refused));
    assertEquals(0, ApiErrorHandler.httpStatusOf(new RuntimeException("Read timed out")));
    assertEquals(0, ApiErrorHandler.httpStatusOf(null));

    System.out.println("[005 상태코드 추출] " + table + " / 연결거부=0 / 타임아웃=0 / null=0");
  }

  @Test
  void 상태코드_추출은_원인_사슬을_따라가고_자기참조_사슬에서_멈춘다() {
    WebClientResponseException inner =
        AnthropicErrorFixtures.thrownBy(AnthropicErrorFixtures.Case.FORBIDDEN_403);

    // 원인 사슬 안쪽에 있어도 찾는다(재시도 계층이 예외를 여러 겹 감싸는 형태).
    Exception wrapped = new RuntimeException("감싼 예외", new IllegalStateException("한 겹 더", inner));
    assertEquals(403, ApiErrorHandler.httpStatusOf(wrapped));

    // 자기 자신을 원인으로 갖는 사슬에서도 무한 루프에 빠지지 않는다.
    SelfCausedException loop = new SelfCausedException();
    assertSame(loop, loop.getCause());
    assertEquals(0, ApiErrorHandler.httpStatusOf(loop));
  }

  /** 자기 자신을 원인으로 돌려주는 예외 — 사슬 순환 방지 확인용. */
  private static class SelfCausedException extends RuntimeException {
    SelfCausedException() {
      super("자기 자신을 원인으로 갖는 예외");
    }

    @Override
    public synchronized Throwable getCause() {
      return this;
    }
  }

  // ===================================================================
  // 도우미
  // ===================================================================

  private void assertClassified(AnthropicErrorFixtures.Case fixture,
      ApiErrorHandler.ErrorType expected) {
    WebClientResponseException thrown = AnthropicErrorFixtures.thrownBy(fixture);
    int status = localHttpStatusOf(thrown);
    assertEquals(fixture.status(), status, fixture + ": 상태 코드 추출이 틀렸다");

    ApiErrorHandler.ErrorType actual = apiErrorHandler.classifyError(thrown, status);
    System.out.println("[005 분류] " + fixture + " (status=" + status + ") → " + actual
        + " | 메시지=" + AnthropicErrorFixtures.maskRequestIds(thrown.getMessage()));
    assertEquals(expected, actual, fixture + ": 분류가 기대와 다르다(메시지="
        + AnthropicErrorFixtures.maskRequestIds(thrown.getMessage()) + ")");
  }

  /**
   * 실제 연결 거부 예외 — MockWebServer를 띄워 포트를 얻고 <b>내린 뒤</b> 같은 포트로 호출한다.
   * 손으로 만든 메시지가 아니라 WebClient가 실제로 던진 객체여야 분류 경로가 실물과 같다.
   */
  private static Exception connectRefusedException() {
    int deadPort;
    MockWebServer server = new MockWebServer();
    try {
      server.start();
      deadPort = server.getPort();
      server.shutdown();
    } catch (IOException e) {
      return fail("연결 거부용 포트를 준비하지 못했다: " + e);
    }

    AnthropicLlmClient client = new AnthropicLlmClient(
        AnthropicErrorFixtures.INVALID_KEY, "http://localhost:" + deadPort);
    return assertThrows(Exception.class,
        () -> client.call("system prompt", "user content", "claude-sonnet-5", 16),
        "내려간 포트로 호출하면 예외가 나야 한다");
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

  private static String firstLine(String text) {
    if (text == null) {
      return "(null)";
    }
    int at = text.indexOf('\n');
    return at < 0 ? text : text.substring(0, at);
  }
}
