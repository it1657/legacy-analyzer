package com.legacy.core;

import com.legacy.analysis.AnalysisException;
import com.legacy.analysis.ClaudeService;
import com.legacy.analysis.ClaudeServiceImpl;
import com.legacy.analysis.RetryHandler;
import com.legacy.analysis.SessionConfig;
import com.legacy.analysis.llm.AnthropicErrorFixtures;
import com.legacy.analysis.llm.AnthropicLlmClient;
import com.legacy.analysis.llm.LlmClient;
import com.legacy.analysis.llm.LlmClientResolver;
import com.legacy.analysis.llm.LlmResult;
import com.legacy.analysis.llm.OpenAiCompatibleLlmClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-002 (work-order 2026-10-retry-and-classification v1, REQ-002 조사) —
 * 분류기가 <b>실제로 어떤 예외를 받는지</b>를 가짜 서버로 만들어 목록으로 고정한다.
 * 이 TASK는 동작을 바꾸지 않는다(프로덕션 0행) — <b>현재 동작의 기록</b>이다.
 *
 * <p>세 묶음을 본다.
 * <ul>
 *   <li><b>E1~E6</b> — {@code ApiErrorHandler.classifyError}의 상태 코드 0 경로에 실제로 오는 예외.
 *       손으로 만든 메시지는 쓰지 않는다. 분류기가 보는 것은 {@code getMessage()} 문자열인데 그 문자열은
 *       클라이언트·Netty·Spring이 겹쳐 만든 결과라, 손으로 쓴 문자열로는 실제와 다른 것을 재게 된다.</li>
 *   <li><b>N1~N3</b> — AI 응답을 해석하지 못하는 세 경로에서 <b>요청을 몇 번 보내는지</b>.
 *       "해석 실패가 유료 호출 반복으로 이어지는가"가 REQ-002의 우선순위 판단 근거다.</li>
 *   <li><b>W1~W3</b> — 두 번째 분류기({@code RetryHandler.classifyError})의 현재 동작. 이 분류기는
 *       숫자·단어를 부분 문자열로 찾으므로 <b>파일 경로에 든 글자</b>가 분류를 바꾼다. 이번 사이클은
 *       이 분류기를 고치지 않기로 했고(게이트1 D3), 대신 현재 동작을 실행으로 남긴다.</li>
 * </ul>
 *
 * <p><b>복사본 두 벌</b>: E 묶음의 판정은 "진짜 연결 오류가 {@code "io"} 글자 규칙에만 기대고 있는가"다.
 * 그래서 테스트 안에 착수 커밋 {@code f68f28e}의 상태 0 경로 규칙을 두 벌 둔다 — 지금 그대로({@code io}
 * 있음)와 {@code io} 규칙만 뺀 것. 각 예외에 대해 ① <b>프로덕션 = 복사본(있음)</b>(복사가 정확함)과
 * ② <b>복사본(있음) = 복사본(없음)</b>({@code io} 글자에만 기대지 않음)을 단언한다. ②가 깨지는 행이
 * 있으면 그 예외는 {@code io}에만 기대고 있다는 뜻이다.
 *
 * <p>복사본이 "항상 같은 값"을 내서 ②가 공허하게 참이 되는 것을 막기 위해, E1에서 두 복사본이 실제로
 * {@code CONNECT_FAILURE}를 내는 것을 함께 단언한다. E4·E5는 환경(읽기 제한·DNS)에 따라 값이 달라질
 * 수 있어 <b>분류 값 자체는 단언하지 않고 기록만</b> 한다(①②는 모든 행에서 단언).
 *
 * <p>외부 호출은 없다 — 대상 주소는 {@code localhost}(MockWebServer)와, 주소 찾기 실패 전용으로
 * 예약된 {@code .invalid} 뿐이고 키는 무효 문자열({@link AnthropicErrorFixtures#INVALID_KEY})이다.
 */
class ConnectionErrorInventoryTest {

  /** 운영과 같은 재시도 정책(대기만 짧게) — {@code LlmRetryAttemptCountTest}와 같은 배선. */
  private static final int MAX_RETRIES = 3;

  /** 재시도가 살아 있는 경로의 시도 수 = 첫 시도 + 재시도 3회(바깥은 {@code AnalysisException}을 다시 재시도하지 않음). */
  private static final int RETRYABLE_ATTEMPTS = 4;

  /** 두 번째 분류기만 거치는 경로(파일 쓰기)의 재시도 수 = 바깥 4회. */
  private static final int OUTER_ATTEMPTS = 4;

  private static final String MODEL = "claude-sonnet-5";
  private static final String SESSION_ROOT = "/source/connection-error-inventory-session";
  private static final String SOURCE_CODE = "public class Sample {}";

  /**
   * {@code RetryHandler.classifyError}가 메시지에서 부분 문자열로 찾는 단어 전부(C-7).
   * W 묶음의 경로 전제를 이 목록으로 검사한다.
   */
  private static final List<String> SECOND_CLASSIFIER_WORDS = List.of(
      "timeout", "timed out", "connection", "refused", "rate limit", "429",
      "authentication", "401", "forbidden", "403", "bad request", "400",
      "server error", "500", "no such file", "cannot find",
      "permission denied", "access denied");

  private final ApiErrorHandler apiErrorHandler = new ApiErrorHandler();

  // ===================================================================
  // E1~E6 — 상태 0 경로에 실제로 오는 예외 목록
  // ===================================================================

  @Test
  void E1_연결거부_예외를_목록에_올린다() throws Exception {
    int port = deadPort();
    Exception thrown = thrownByAnthropic("http://localhost:" + port);

    Row row = recordRow("E1 연결 거부", thrown);
    assertCopyAgreement("E1", row);

    // 양성 확인 — 복사본이 "항상 UNKNOWN"을 내는 결함이 아님을 보인다(G-21).
    assertEquals(ApiErrorHandler.ErrorType.CONNECT_FAILURE, row.withIo(),
        "E1: 복사본(io 있음)이 연결 거부를 CONNECT_FAILURE로 내지 않는다 — 복사본이 제 구실을 못 한다");
    assertEquals(ApiErrorHandler.ErrorType.CONNECT_FAILURE, row.withoutIo(),
        "E1: 복사본(io 없음)이 연결 거부를 CONNECT_FAILURE로 내지 않는다 — 복사본이 제 구실을 못 한다");
  }

  @Test
  void E2_응답_전_끊김_예외를_목록에_올린다() throws Exception {
    MockWebServer server = startServer(
        new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
    try {
      Exception thrown = thrownByAnthropic("http://localhost:" + server.getPort());
      Row row = recordRow("E2 응답 전 끊김", thrown);
      assertCopyAgreement("E2", row);
    } finally {
      server.shutdown();
    }
  }

  @Test
  void E3_본문_도중_끊김_예외를_목록에_올린다() throws Exception {
    MockWebServer server = startServer(new MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody("{\"content\":[{\"type\":\"text\",\"text\":\"abcdefghijklmnopqrstuvwxyz\"}]}")
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
    try {
      Exception thrown = thrownByAnthropic("http://localhost:" + server.getPort());
      Row row = recordRow("E3 본문 도중 끊김", thrown);
      assertCopyAgreement("E3", row);
    } finally {
      server.shutdown();
    }
  }

  /** 읽기 제한이 있는 클라이언트는 로컬 쪽뿐이다 — Anthropic 클라이언트에는 응답 시간 제한이 없다. */
  @Test
  void E4_로컬_읽기시간초과_예외를_목록에_올린다() throws Exception {
    MockWebServer server = startServer(
        new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
    try {
      OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
          "http://localhost:" + server.getPort(), "", 1L, 0.2, 1);
      Exception thrown = assertThrows(Exception.class,
          () -> client.call("system prompt", "user content", MODEL, 1024));

      Row row = recordRow("E4 로컬 읽기 시간 초과", thrown);
      assertCopyAgreement("E4", row);
      // 분류 값 자체는 단언하지 않는다(읽기 제한·환경 의존) — 위 출력 줄에 기록만 남긴다.
    } finally {
      server.shutdown();
    }
  }

  @Test
  void E5_주소찾기_실패_예외를_목록에_올린다() {
    // .invalid는 주소 찾기 실패 전용으로 예약된 이름이라 외부로 나가는 요청이 생기지 않는다.
    Exception thrown = thrownByAnthropic("http://nonexistent-host.invalid");

    Row row = recordRow("E5 주소 찾기 실패", thrown);
    assertCopyAgreement("E5", row);
    // 분류 값 자체는 단언하지 않는다(환경 의존) — 기록만.
  }

  @Test
  void E6_TLS_불일치_예외를_목록에_올린다() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      // 평문 서버에 https로 말을 건다 — 핸드셰이크가 성립하지 않는다.
      Exception thrown = thrownByAnthropic("https://localhost:" + server.getPort());
      Row row = recordRow("E6 TLS 불일치", thrown);
      assertCopyAgreement("E6", row);
    } finally {
      server.shutdown();
    }
  }

  // ===================================================================
  // N1~N3 — 응답을 해석하지 못하는 경로의 요청 수
  // ===================================================================

  /** 200인데 본문이 JSON이 아니다 — 해석 단계에서 깨진다. 헤더에 따라 다른 예외가 나므로 헤더를 붙인다(C-9). */
  @Test
  void N1_상태200_비JSON_본문의_요청_수를_센다() throws Exception {
    MockResponse response = new MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody("plain-text-not-a-json");

    NonJsonOutcome outcome = runOneFile("N1 200 + 비JSON 본문", response);

    assertTrue(outcome.failed(), "N1: 성공으로 끝났다 — 해석 실패 경로가 아니다");
    assertEquals(1, outcome.delegateCalls(), "N1: 요청 수가 1이 아니다 — 유료 호출이 반복됐다");
  }

  /** 200 + 빈 객체 — 응답 봉투에 {@code content}가 없다. */
  @Test
  void N2_상태200_빈_객체의_요청_수를_센다() throws Exception {
    MockResponse response = new MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody("{}");

    NonJsonOutcome outcome = runOneFile("N2 200 + 빈 객체", response);

    assertTrue(outcome.failed(), "N2: 성공으로 끝났다 — 해석 실패 경로가 아니다");
    assertEquals(1, outcome.delegateCalls(), "N2: 요청 수가 1이 아니다 — 유료 호출이 반복됐다");
  }

  /** 봉투는 정상이고 AI가 준 본문만 JSON이 아니다 — 주석 결합 단계가 통째로 감싸져 있다. */
  @Test
  void N3_정상_봉투_비JSON_텍스트의_요청_수를_센다() throws Exception {
    MockResponse response = new MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody("{\"content\":[{\"type\":\"text\",\"text\":\"주석 지도가 아니라 그냥 설명문입니다\"}],"
            + "\"usage\":{\"input_tokens\":11,\"output_tokens\":22}}");

    NonJsonOutcome outcome = runOneFile("N3 정상 봉투 + 비JSON 텍스트", response);

    assertFalse(outcome.failed(), "N3: 실패로 끝났다 — 주석 결합 단계가 감싸고 있지 않다");
    assertEquals(1, outcome.delegateCalls(), "N3: 요청 수가 1이 아니다 — 유료 호출이 반복됐다");
    // 원본이 사라지지 않는 것만 단언한다. "원본 그대로인지"는 출력 줄에 기록으로 남긴다.
    assertNotNull(outcome.returned(), "N3: 반환값이 없다");
    assertTrue(outcome.returned().contains(SOURCE_CODE),
        "N3: 반환값에 원본 소스가 남아 있지 않다. 반환값=" + outcome.returned());
  }

  // ===================================================================
  // W1~W3 — 두 번째 분류기의 현재 동작 (게이트1 D3: 기록만)
  // ===================================================================

  /** 경로에 규칙 단어가 하나도 없는 경우 — 분류가 미분류로 떨어져 재시도하지 않는다. */
  @Test
  void W1_규칙_단어_없는_경로의_쓰기_실패를_기록한다() throws Exception {
    WriteOutcome outcome = writeToDirectory("W1 규칙 단어 없음", "rc-w1-plain");

    // C-7 전제 — 이 경로의 실제 예외 메시지에 규칙 단어가 하나도 없어야 판정이 성립한다.
    List<String> found = wordsIn(outcome.causeMessage());
    assertTrue(found.isEmpty(),
        "W1: 예외 메시지에 두 번째 분류기 규칙 단어가 들어 있다(기준 폴더를 바꿔야 한다) — 발견="
            + found + " / 메시지=" + outcome.causeMessage());

    assertTrue(outcome.runs() >= 1, "W1: 작업이 한 번도 실행되지 않았다 — 실행 횟수 단언이 무의미하다");
    assertEquals(1, outcome.runs(), "W1: 규칙 단어가 없으면 재시도하지 않아야 한다");
  }

  /** 폴더 이름에 {@code timeout}이 든 경우 — 파일 쓰기 실패가 "응답 시간 초과"로 분류돼 재시도된다. */
  @Test
  void W2_폴더_이름에_timeout이_든_경로의_쓰기_실패를_기록한다() throws Exception {
    WriteOutcome outcome = writeToDirectory("W2 폴더 이름 timeout", "rc-w2-timeout");

    assertIntendedWordOnly("W2", outcome.causeMessage(), "timeout");

    assertTrue(outcome.runs() >= 1, "W2: 작업이 한 번도 실행되지 않았다 — 실행 횟수 단언이 무의미하다");
    assertEquals(OUTER_ATTEMPTS, outcome.runs(), "W2: 바깥 재시도 4회가 아니다");
  }

  /** 폴더 이름에 {@code 500}이 든 경우 — 파일 쓰기 실패가 "Claude 서버 오류"로 분류돼 재시도된다. */
  @Test
  void W3_폴더_이름에_500이_든_경로의_쓰기_실패를_기록한다() throws Exception {
    WriteOutcome outcome = writeToDirectory("W3 폴더 이름 500", "rc-w3-500");

    assertIntendedWordOnly("W3", outcome.causeMessage(), "500");

    assertTrue(outcome.runs() >= 1, "W3: 작업이 한 번도 실행되지 않았다 — 실행 횟수 단언이 무의미하다");
    assertEquals(OUTER_ATTEMPTS, outcome.runs(), "W3: 바깥 재시도 4회가 아니다");
  }

  /** 양성 대조군 — 같은 바깥 재시도에서 값이 1과 4로 갈린다는 것을 한 테스트 안에서 보인다(G-04). */
  @Test
  void W_대조_같은_바깥_재시도가_분류에_따라_1회와_4회로_갈린다() throws Exception {
    WriteOutcome plain = writeToDirectory("W 대조(규칙 단어 없음)", "rc-wc-plain");
    WriteOutcome timeout = writeToDirectory("W 대조(timeout)", "rc-wc-timeout");

    assertEquals(1, plain.runs(), "대조: 규칙 단어가 없는 경로가 1회가 아니다");
    assertEquals(OUTER_ATTEMPTS, timeout.runs(), "대조: timeout이 든 경로가 4회가 아니다");
    assertTrue(plain.runs() < timeout.runs(),
        "대조: 두 경로의 실행 횟수가 갈리지 않았다 — 분류가 실행 횟수에 영향을 주지 않는다는 뜻이다");
  }

  // ===================================================================
  // 착수 커밋 상태 0 경로 규칙의 복사본 두 벌
  // ===================================================================

  /**
   * 복사본 — 지금 그대로({@code io} 규칙 있음). 원문은 착수 커밋 {@code f68f28e}
   * {@code ApiErrorHandler.classifyError}의 상태 0 경로다. 메시지를 소문자로 내리는 입력 준비
   * 두 줄도 함께 옮겼다. 예외가 {@code null}인 경우의 앞쪽 가드는 복사 범위 밖이며,
   * 이 테스트는 복사본을 실제 예외 객체로만 부른다.
   */
  private ApiErrorHandler.ErrorType copyWithIo(Exception exception, int httpStatus) {
    String exceptionMessage =
      exception.getMessage() == null ? "" : exception.getMessage().toLowerCase();

    if (exceptionMessage.contains("credit balance") || exceptionMessage.contains("too low to access")) {
      return ApiErrorHandler.ErrorType.INSUFFICIENT_CREDITS;
    }

    if (httpStatus > 0) {
      return copyByStatus(httpStatus);
    }

    if (exceptionMessage.contains("timeout") || exceptionMessage.contains("timed out")) {
      return ApiErrorHandler.ErrorType.NETWORK_TIMEOUT;
    }
    if (exceptionMessage.contains("connection") || exceptionMessage.contains("refused")) {
      return ApiErrorHandler.ErrorType.CONNECT_FAILURE;
    }
    if (exceptionMessage.contains("io")) {
      return ApiErrorHandler.ErrorType.CONNECT_FAILURE;
    }

    if (exceptionMessage.contains("no such file") || exceptionMessage.contains("cannot find")) {
      return ApiErrorHandler.ErrorType.FILE_READ_ERROR;
    }
    if (exceptionMessage.contains("permission denied") || exceptionMessage.contains("access denied")) {
      return ApiErrorHandler.ErrorType.FILE_READ_ERROR;
    }

    return copyByStatus(httpStatus);
  }

  /** 복사본 — {@code io} 규칙 두 줄만 뺀 것. 그 밖은 위와 한 글자도 다르지 않다. */
  private ApiErrorHandler.ErrorType copyWithoutIo(Exception exception, int httpStatus) {
    String exceptionMessage =
      exception.getMessage() == null ? "" : exception.getMessage().toLowerCase();

    if (exceptionMessage.contains("credit balance") || exceptionMessage.contains("too low to access")) {
      return ApiErrorHandler.ErrorType.INSUFFICIENT_CREDITS;
    }

    if (httpStatus > 0) {
      return copyByStatus(httpStatus);
    }

    if (exceptionMessage.contains("timeout") || exceptionMessage.contains("timed out")) {
      return ApiErrorHandler.ErrorType.NETWORK_TIMEOUT;
    }
    if (exceptionMessage.contains("connection") || exceptionMessage.contains("refused")) {
      return ApiErrorHandler.ErrorType.CONNECT_FAILURE;
    }

    if (exceptionMessage.contains("no such file") || exceptionMessage.contains("cannot find")) {
      return ApiErrorHandler.ErrorType.FILE_READ_ERROR;
    }
    if (exceptionMessage.contains("permission denied") || exceptionMessage.contains("access denied")) {
      return ApiErrorHandler.ErrorType.FILE_READ_ERROR;
    }

    return copyByStatus(httpStatus);
  }

  /**
   * 복사본이 기대는 상태 코드 표. 프로덕션의 같은 메서드는 {@code private}이라 호출할 수 없어
   * 함께 옮겼다 — 상태 0이면 미분류로 떨어진다.
   */
  private ApiErrorHandler.ErrorType copyByStatus(int httpStatus) {
    return switch (httpStatus) {
      case 400 -> ApiErrorHandler.ErrorType.INVALID_REQUEST;
      case 401 -> ApiErrorHandler.ErrorType.API_AUTHENTICATION;
      case 403 -> ApiErrorHandler.ErrorType.API_FORBIDDEN;
      case 429 -> ApiErrorHandler.ErrorType.API_RATE_LIMIT;
      case 500, 502, 503, 504 -> ApiErrorHandler.ErrorType.SERVER_ERROR;
      default -> ApiErrorHandler.ErrorType.UNKNOWN_ERROR;
    };
  }

  // ===================================================================
  // 도우미 — 목록 기록
  // ===================================================================

  private record Row(ApiErrorHandler.ErrorType production, ApiErrorHandler.ErrorType withIo,
                     ApiErrorHandler.ErrorType withoutIo) {
  }

  private Row recordRow(String id, Exception thrown) {
    int httpStatus = ApiErrorHandler.httpStatusOf(thrown);
    ApiErrorHandler.ErrorType production = apiErrorHandler.classifyError(thrown, httpStatus);
    ApiErrorHandler.ErrorType withIo = copyWithIo(thrown, httpStatus);
    ApiErrorHandler.ErrorType withoutIo = copyWithoutIo(thrown, httpStatus);

    System.out.println("[RC 목록] " + id
        + " | 사슬=" + chain(thrown)
        + " | 메시지 첫 줄=" + firstLine(thrown.getMessage())
        + " | httpStatus=" + httpStatus
        + " | 프로덕션=" + production
        + " | 복사본(io 있음)=" + withIo
        + " | 복사본(io 없음)=" + withoutIo
        + " | isRetryable=" + apiErrorHandler.isRetryable(production));

    return new Row(production, withIo, withoutIo);
  }

  private void assertCopyAgreement(String id, Row row) {
    assertEquals(row.production(), row.withIo(),
        id + ": 복사본(io 있음)이 프로덕션 분류와 다르다 — 복사가 정확하지 않으므로 이 행의 판정은 성립하지 않는다");
    assertEquals(row.withIo(), row.withoutIo(),
        id + ": io 규칙을 빼면 분류가 달라진다 — 이 오류는 io 글자에만 기대고 있다(분기 B 사유)");
  }

  private static String chain(Throwable thrown) {
    StringBuilder sb = new StringBuilder();
    Throwable cursor = thrown;
    for (int depth = 0; cursor != null && depth < 5; depth++) {
      if (depth > 0) {
        sb.append(" <- ");
      }
      sb.append(cursor.getClass().getName());
      Throwable cause = cursor.getCause();
      if (cause == cursor) {
        break;
      }
      cursor = cause;
    }
    return sb.toString();
  }

  /**
   * 사슬의 각 단계를 "클래스: 메시지 전문"으로 적는다(C-9). 분류기는 자기가 받은 단계의 메시지만
   * 보므로, 어느 단계에 어떤 글자가 들었는지를 단계별로 남겨야 판단할 수 있다.
   */
  private static String chainWithMessages(Throwable thrown) {
    StringBuilder sb = new StringBuilder();
    Throwable cursor = thrown;
    for (int depth = 0; cursor != null && depth < 5; depth++) {
      if (depth > 0) {
        sb.append(" <- ");
      }
      sb.append(cursor.getClass().getName()).append(": ")
          .append(String.valueOf(cursor.getMessage()).replace("\n", "\\n"));
      Throwable cause = cursor.getCause();
      if (cause == cursor) {
        break;
      }
      cursor = cause;
    }
    return sb.toString();
  }

  /** 사슬에서 메시지에 {@code io} 글자가 든 단계의 클래스 이름(C-9 — 재시도로 뒤집히는지 판단용). */
  private static List<String> ioLevels(Throwable thrown) {
    List<String> levels = new ArrayList<>();
    Throwable cursor = thrown;
    for (int depth = 0; cursor != null && depth < 5; depth++) {
      String message = cursor.getMessage();
      if (message != null && message.toLowerCase().contains("io")) {
        levels.add(cursor.getClass().getSimpleName());
      }
      Throwable cause = cursor.getCause();
      if (cause == cursor) {
        break;
      }
      cursor = cause;
    }
    return levels;
  }

  /** 가짜 서버가 실제로 돌려준 응답의 상태 줄과 헤더 원문(C-9). */
  private static String responseSummary(MockResponse response) {
    return "[" + response.getStatus() + "] 헤더=" + response.getHeaders().toString().replace("\n", " / ")
        + " 본문=" + (response.getBody() == null ? "(없음)" : response.getBody().readUtf8());
  }

  private static String firstLine(String message) {
    if (message == null) {
      return "(null)";
    }
    int at = message.indexOf('\n');
    return at < 0 ? message : message.substring(0, at);
  }

  // ===================================================================
  // 도우미 — E 묶음
  // ===================================================================

  private Exception thrownByAnthropic(String apiUrl) {
    AnthropicLlmClient client = new AnthropicLlmClient(AnthropicErrorFixtures.INVALID_KEY, apiUrl);
    return assertThrows(Exception.class,
        () -> client.call("system prompt", "user content", MODEL, 1024),
        "실제 클라이언트가 예외를 던지지 않았다 — 목록에 올릴 예외가 없다: " + apiUrl);
  }

  private static MockWebServer startServer(MockResponse response) throws IOException {
    MockWebServer server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        return response;
      }
    });
    server.start();
    return server;
  }

  /** 띄웠다 내린 포트 — 연결 거부를 만드는 가장 확실한 방법이다. */
  private static int deadPort() throws IOException {
    MockWebServer server = new MockWebServer();
    server.start();
    int port = server.getPort();
    server.shutdown();
    return port;
  }

  // ===================================================================
  // 도우미 — N 묶음 (운영과 같은 두 겹 재시도 배선)
  // ===================================================================

  private record NonJsonOutcome(boolean failed, int delegateCalls, String returned) {
  }

  /** 실제 클라이언트에 위임하면서 호출 횟수만 센다. 분류·재시도에는 개입하지 않는다. */
  private static final class CountingDelegate implements LlmClient {
    private final LlmClient delegate;
    private int callCount;

    private CountingDelegate(LlmClient delegate) {
      this.delegate = delegate;
    }

    @Override
    public LlmResult call(String systemPrompt, String userContent, String model, int maxTokens) {
      callCount++;
      return delegate.call(systemPrompt, userContent, model, maxTokens);
    }
  }

  private NonJsonOutcome runOneFile(String id, MockResponse response) throws Exception {
    MockWebServer server = startServer(response);
    try {
      SessionConfig sessionConfig = new SessionConfig();
      sessionConfig.setMaxRetries(MAX_RETRIES);
      sessionConfig.setInitialRetryDelayMs(1);
      sessionConfig.setMaxRetryDelayMs(10);

      ApiErrorHandler handler = new ApiErrorHandler();
      CountingDelegate counter = new CountingDelegate(new AnthropicLlmClient(
          AnthropicErrorFixtures.INVALID_KEY, "http://localhost:" + server.getPort()));
      LlmClientResolver resolver = new LlmClientResolver(counter, counter);

      ClaudeServiceImpl service = new ClaudeServiceImpl(
          handler, null, sessionConfig, null, resolver, null, null);
      setField(service, "llmProvider", "anthropic");
      setField(service, "apiModel", MODEL);
      setField(service, "llmLocalModel", MODEL);
      // 키 가드를 통과하는 무효 문자열 — 자격증명이 아니다. 가드에 막히면 LLM이 아예 불리지 않아
      // 요청 수를 잴 수 없다.
      setField(service, "apiKey", AnthropicErrorFixtures.INVALID_KEY);
      setField(service, "systemPromptFilename", "prompts/prompt-base.md");

      RetryHandler retryHandler = new RetryHandler(handler, sessionConfig);
      ClaudeService claudeService = service;

      String returned = null;
      String errorType = "(없음)";
      String chain = "(없음)";
      String ioLevels = "[]";
      boolean failed = false;
      try {
        returned = retryHandler.executeWithRetry("session-rc-inventory",
            SESSION_ROOT + "/Sample.java",
            () -> claudeService.analyzeCodeWithClaude(SOURCE_CODE, "Sample.java",
                SESSION_ROOT, SESSION_ROOT + "/Sample.java"));
      } catch (AnalysisException e) {
        failed = true;
        errorType = String.valueOf(e.getErrorType());
        // C-9 — 메시지에 io가 섞여 재시도되는지 판단할 수 있게 단계별 전문을 남긴다.
        chain = chainWithMessages(e);
        ioLevels = ioLevels(e).toString();
      }

      System.out.println("[RC 해석 실패] " + id
          + " | 보낸 응답=" + responseSummary(response)
          + " | 위임 카운터=" + counter.callCount
          + " | getRequestCount()=" + server.getRequestCount()
          + " | 결과=" + (failed ? "실패" : "성공")
          + " | errorType=" + errorType
          + " | 반환값이 원본과 같은가=" + (returned != null && returned.equals(SOURCE_CODE))
          + " | 반환값 길이=" + (returned == null ? -1 : returned.length())
          + " | 원본 길이=" + SOURCE_CODE.length()
          + " | 사슬·메시지 전문=" + chain
          + " | io 글자가 든 단계=" + ioLevels);

      assertTrue(counter.callCount >= 1,
          id + ": LLM이 한 번도 불리지 않았다 — 키 가드 등에 막힌 것이므로 요청 수 단언이 무의미하다");
      // 응답이 가는 경우이므로 두 셈이 같아야 한다(교차 확인).
      assertEquals(counter.callCount, server.getRequestCount(),
          id + ": 위임 카운터와 MockWebServer 요청 수가 다르다");

      return new NonJsonOutcome(failed, counter.callCount, returned);
    } finally {
      server.shutdown();
    }
  }

  /** 양성 대조군 — 같은 배선에서 재시도가 살아 있는 경로는 4회가 된다(N 묶음의 "1회"가 항상 1이 아님). */
  @Test
  void N_대조_같은_배선에서_서버오류_500은_4회_요청한다() throws Exception {
    MockResponse response = AnthropicErrorFixtures.mockResponse(
        AnthropicErrorFixtures.Case.SERVER_500);

    NonJsonOutcome outcome = runOneFile("N 대조(500 서버 오류)", response);

    assertTrue(outcome.failed(), "대조: 500이 성공으로 끝났다");
    assertEquals(RETRYABLE_ATTEMPTS, outcome.delegateCalls(),
        "대조: 재시도가 살아 있는 경로가 4회가 아니다 — N 묶음의 '1회'가 항상 1이라는 뜻이 된다");
  }

  private static void setField(Object target, String name, Object value) {
    try {
      Field field = ClaudeServiceImpl.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      fail("ClaudeServiceImpl." + name + " 주입 실패 — 필드 이름이 바뀌었는지 확인하라: " + e);
    }
  }

  // ===================================================================
  // 도우미 — W 묶음 (실제 RetryHandler로 감싼 파일 쓰기)
  // ===================================================================

  private record WriteOutcome(int runs, String causeMessage, String errorType, String path) {
  }

  /**
   * 디렉터리 경로에 파일을 쓰려고 해서 실제 {@code IOException}을 만들고, 그 작업을 실제
   * {@link RetryHandler}로 감싼다. 경로 문자열이 분류를 바꾸므로 폴더 이름을 <b>고정</b>해서
   * 쓴다 — 무작위 이름을 쓰면 그 안의 숫자가 규칙 단어({@code 500} 등)와 겹쳐 결과가 흔들린다.
   */
  private WriteOutcome writeToDirectory(String id, String folderName) throws Exception {
    Path dir = Path.of(System.getProperty("java.io.tmpdir"), folderName, "target");
    Files.createDirectories(dir);
    try {
      SessionConfig sessionConfig = new SessionConfig();
      sessionConfig.setMaxRetries(MAX_RETRIES);
      sessionConfig.setInitialRetryDelayMs(1);
      sessionConfig.setMaxRetryDelayMs(10);

      ApiErrorHandler handler = new ApiErrorHandler();
      RetryHandler retryHandler = new RetryHandler(handler, sessionConfig);

      int[] runs = {0};
      String[] causeMessage = {null};

      AnalysisException thrown = assertThrows(AnalysisException.class,
          () -> retryHandler.executeWithRetry("session-rc-write", dir.toString(), () -> {
            runs[0]++;
            try {
              Files.writeString(dir, "이 경로는 디렉터리라 파일로 쓸 수 없다");
            } catch (IOException e) {
              causeMessage[0] = e.getMessage();
              throw e;
            }
            return "written";
          }),
          id + ": 디렉터리 경로에 쓰기가 성공했다 — 실패를 만들지 못했으므로 판정이 성립하지 않는다");

      System.out.println("[RC 두번째 분류기] " + id
          + " | 경로=" + dir
          + " | 실행 횟수=" + runs[0]
          + " | errorType=" + thrown.getErrorType()
          + " | 원인 사슬=" + chain(thrown)
          + " | 원인 메시지 첫 줄=" + firstLine(causeMessage[0])
          + " | 규칙 단어=" + wordsIn(causeMessage[0]));

      assertNotNull(causeMessage[0], id + ": 쓰기 실패 예외 메시지를 잡지 못했다");

      return new WriteOutcome(runs[0], causeMessage[0],
          String.valueOf(thrown.getErrorType()), dir.toString());
    } finally {
      Files.deleteIfExists(dir);
      Files.deleteIfExists(dir.getParent());
    }
  }

  /** 메시지(소문자) 안에 들어 있는 두 번째 분류기 규칙 단어 전부. */
  private static List<String> wordsIn(String message) {
    if (message == null) {
      return List.of();
    }
    String lower = message.toLowerCase();
    List<String> found = new ArrayList<>();
    for (String word : SECOND_CLASSIFIER_WORDS) {
      if (lower.contains(word)) {
        found.add(word);
      }
    }
    return found;
  }

  private static void assertIntendedWordOnly(String id, String message, String intended) {
    List<String> found = wordsIn(message);
    assertTrue(found.contains(intended),
        id + ": 의도한 토큰 '" + intended + "'이 예외 메시지에 없다. 메시지=" + message);
    List<String> unexpected = new ArrayList<>(found);
    unexpected.remove(intended);
    assertTrue(unexpected.isEmpty(),
        id + ": 의도한 토큰 밖의 규칙 단어가 섞였다(기준 폴더를 바꿔야 한다) — 발견=" + unexpected
            + " / 메시지=" + message);
  }
}
