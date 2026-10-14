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
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.AccessDeniedException;
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
 * TASK-006 (work-order 2026-10-retry-and-classification v5, REQ-002 — 분기 A) —
 * {@link ApiErrorHandler#classifyError(Exception, int)}의 <b>상태 코드 0 경로</b>에서
 * {@code "io"} 두 글자만 보고 "연결 실패"로 단정하던 규칙을 없앤 뒤의 분류를 고정한다.
 *
 * <p>왜 문제인가: {@code permission}·{@code option}·{@code location}·{@code exception}·
 * {@code session}·{@code transaction} 같은 흔한 영어 낱말 안에 {@code io}가 들어 있다. 그래서
 * 연결과 아무 관계가 없는 오류가 {@code CONNECT_FAILURE}(재시도 대상)로 분류돼 같은 요청을
 * 네 번 보냈고, 바로 뒤에 있는 파일 규칙({@code permission denied} → {@code FILE_READ_ERROR})에는
 * 도달할 수 없었다.
 *
 * <p>두 묶음을 <b>같은 파일에</b> 둔다(REQ-002 제약 3).
 * <ul>
 *   <li><b>Z1~Z8</b> — 이 변경으로 분류가 바뀌는 경우. 변경 전에는 전부 {@code CONNECT_FAILURE}였다.
 *       Z8은 끝단(실제 배선)에서 <b>요청을 몇 번 보내는지</b>로 같은 사실을 본다.</li>
 *   <li><b>P1~P8</b> — 바뀌면 안 되는 양성 대조군. 진짜 연결 오류·시간 초과·파일 오류·상태 코드가
 *       있는 오류·크레딧 소진은 {@code io} 규칙에 기대지 않고 분류되므로 <b>변경 전후 모두</b>
 *       같은 값이어야 한다(G-04). P8은 끝단 요청 수 4회 대조다.</li>
 * </ul>
 *
 * <p>전제 단언(G-21): 메시지 규칙을 재는 케이스는 그 메시지에 실제로 걸리는 규칙 단어 목록을
 * {@link #matchedRuleWords(String)}로 먼저 확인한다. "다른 규칙 단어가 섞여 들어와 우연히 맞는"
 * 통과를 막기 위한 것이다. 요청 수를 재는 케이스(Z8 = 1회·P8 = 4회)는 단언 앞에 "요청 ≥ 1"을
 * 먼저 보고, 1회 단언 옆에 같은 클래스의 4회 대조를 둔다.
 *
 * <p>외부 호출은 없다 — 대상 주소는 {@code localhost}({@link MockWebServer})뿐이고 키는 무효
 * 문자열({@link AnthropicErrorFixtures#INVALID_KEY})이다.
 */
class StatusZeroMessageClassificationTest {

  /**
   * 상태 코드 0 경로가 메시지에서 부분 문자열로 찾는 단어 전부(크레딧 규칙 2개 포함).
   * 케이스별로 "걸리는 단어가 정확히 무엇인지"를 이 목록으로 확인한다.
   */
  private static final List<String> STATUS_ZERO_RULE_WORDS = List.of(
      "credit balance", "too low to access", "timeout", "timed out",
      "connection", "refused", "io", "no such file", "cannot find",
      "permission denied", "access denied");

  /** 운영과 같은 재시도 정책(대기만 짧게) — 끝단 케이스 Z8·P8이 쓴다. */
  private static final int MAX_RETRIES = 3;

  /** 재시도 대상의 시도 수 = 첫 시도 + 재시도 3회. */
  private static final int RETRYABLE_ATTEMPTS = 4;

  /** 재시도 불가 오류의 시도 수. */
  private static final int NON_RETRYABLE_ATTEMPTS = 1;

  private static final String MODEL = "claude-sonnet-5";
  private static final String SESSION_ROOT = "/source/status-zero-message-session";
  private static final String SESSION_ID = "session-status-zero-message";
  private static final String SOURCE_CODE = "public class Sample {}";

  @TempDir
  Path tempDir;

  private final ApiErrorHandler apiErrorHandler = new ApiErrorHandler();

  // ===================================================================
  // Z1~Z8 — 이 변경으로 분류가 바뀌는 경우
  // ===================================================================

  /** {@code permission}의 {@code io}에 먼저 걸려 파일 규칙에 닿지 못했던 경우(O36 해소 근거). */
  @Test
  void Z1_permission_denied_메시지는_FILE_READ_ERROR로_분류된다() {
    Exception probe = new RuntimeException("permission denied");

    assertEquals(0, ApiErrorHandler.httpStatusOf(probe), "Z1 전제: 이 예외에는 상태 코드가 없어야 한다");
    assertEquals(List.of("io", "permission denied"), matchedRuleWords(probe.getMessage()),
        "Z1 전제: 걸리는 규칙 단어가 io와 permission denied 둘뿐이어야 한다");

    ApiErrorHandler.ErrorType actual = apiErrorHandler.classifyError(probe, 0);
    System.out.println("[RC 상태0 분류] Z1 permission denied | 메시지 원문=" + probe.getMessage()
        + " | 규칙 단어=" + matchedRuleWords(probe.getMessage())
        + " | 분류=" + actual
        + " | isRetryable=" + apiErrorHandler.isRetryable(actual));

    assertEquals(ApiErrorHandler.ErrorType.FILE_READ_ERROR, actual,
        "Z1: permission denied가 파일 읽기 오류로 분류되지 않는다");
    assertFalse(apiErrorHandler.isRetryable(actual), "Z1: 파일 읽기 오류는 재시도 대상이 아니어야 한다");
  }

  @Test
  void Z2_option이_든_메시지는_UNKNOWN_ERROR로_분류된다() {
    assertUnknown("Z2 option", new RuntimeException("Unsupported option: --strict"));
  }

  @Test
  void Z3_location이_든_메시지는_UNKNOWN_ERROR로_분류된다() {
    assertUnknown("Z3 location", new RuntimeException("Invalid location header value"));
  }

  @Test
  void Z4_exception이_든_메시지는_UNKNOWN_ERROR로_분류된다() {
    assertUnknown("Z4 exception", new RuntimeException("Handler dispatch failed; nested exception"));
  }

  @Test
  void Z5_session이_든_메시지는_UNKNOWN_ERROR로_분류된다() {
    assertUnknown("Z5 session", new RuntimeException("Session is already closed"));
  }

  /**
   * 경로에 {@code Option}이 든 <b>실제</b> {@link AccessDeniedException} — 디렉터리 경로에 쓰기를
   * 시도해 만든다. 이 예외의 메시지는 경로 문자열뿐이라 사유 문구({@code access denied})가 없다.
   */
  @Test
  void Z6_경로에_Option이_든_실제_AccessDeniedException은_UNKNOWN_ERROR로_분류된다() throws Exception {
    Path deniedDirectory = Files.createDirectories(tempDir.resolve("Option-denied"));

    AccessDeniedException thrown = assertThrows(AccessDeniedException.class,
        () -> Files.writeString(deniedDirectory, "본문"),
        "Z6 전제: 디렉터리 경로에 쓰면 접근 거부 예외가 나야 한다");

    String message = thrown.getMessage();
    assertNotNull(message, "Z6 전제: 예외 메시지가 null이면 경로 글자를 볼 수 없다");
    assertEquals(0, ApiErrorHandler.httpStatusOf(thrown), "Z6 전제: 이 예외에는 상태 코드가 없어야 한다");
    assertTrue(message.toLowerCase().contains("option"),
        "Z6 전제: 경로에 Option이 들어 있지 않다 — io 글자가 경로에서 오지 않는다. 원문=" + message);
    assertEquals(List.of("io"), matchedRuleWords(message),
        "Z6 전제: 걸리는 규칙 단어가 io 하나여야 한다(임시 경로에 다른 규칙 단어가 섞였다). 원문=" + message);

    ApiErrorHandler.ErrorType actual = apiErrorHandler.classifyError(thrown, 0);
    System.out.println("[RC 상태0 분류] Z6 실제 AccessDeniedException"
        + " | 예외=" + thrown.getClass().getName()
        + " | 메시지 원문=" + message
        + " | 규칙 단어=" + matchedRuleWords(message)
        + " | 분류=" + actual);

    assertEquals(ApiErrorHandler.ErrorType.UNKNOWN_ERROR, actual,
        "Z6: 경로에 든 io 글자로 연결 실패로 분류된다");
  }

  /** 실제 스프링 트랜잭션 예외 — {@code transaction}의 {@code io}에 걸렸던 경우. */
  @Test
  void Z7_실제_스프링_트랜잭션_예외는_UNKNOWN_ERROR로_분류된다() {
    CannotCreateTransactionException probe =
        new CannotCreateTransactionException("Could not open JPA EntityManager for transaction");

    assertEquals(0, ApiErrorHandler.httpStatusOf(probe), "Z7 전제: 이 예외에는 상태 코드가 없어야 한다");
    assertEquals(List.of("io"), matchedRuleWords(probe.getMessage()),
        "Z7 전제: 걸리는 규칙 단어가 io 하나여야 한다");

    ApiErrorHandler.ErrorType actual = apiErrorHandler.classifyError(probe, 0);
    System.out.println("[RC 상태0 분류] Z7 실제 트랜잭션 예외"
        + " | 예외=" + probe.getClass().getName()
        + " | 메시지 원문=" + probe.getMessage()
        + " | 규칙 단어=" + matchedRuleWords(probe.getMessage())
        + " | 분류=" + actual);

    assertEquals(ApiErrorHandler.ErrorType.UNKNOWN_ERROR, actual,
        "Z7: 트랜잭션 예외가 연결 실패로 분류된다");
  }

  /**
   * 끝단 — 대역이 {@code io} 글자만 든 예외를 던질 때 LLM을 몇 번 부르는지. 재시도 대상이 아니게
   * 되므로 1회여야 한다. 같은 클래스의 P8(연결 거부 = 4회)이 "항상 1"이 아님을 보인다.
   */
  @Test
  void Z8_끝단에서_io_글자만_든_예외는_1회만_요청한다() throws Exception {
    Exception probe = GenericIoWordClient.failure();
    System.out.println("[RC 전제] Z8 대역 예외=" + probe.getClass().getName()
        + " | 메시지 원문=" + probe.getMessage()
        + " | 규칙 단어=" + matchedRuleWords(probe.getMessage())
        + " | classifyError(e, 0)=" + apiErrorHandler.classifyError(probe, 0));
    assertEquals(List.of("io"), matchedRuleWords(probe.getMessage()),
        "Z8 전제: 대역 예외에 걸리는 규칙 단어가 io 하나여야 한다");

    Harness harness = newHarness(new GenericIoWordClient(), operationalConfig());
    AnalysisException thrown = assertThrows(AnalysisException.class, harness::analyzeOneFile);

    System.out.println("[RC 시도 수] Z8 io 글자만 든 예외 위임=" + harness.counter.callCount
        + " 서버=(해당없음 — 서버를 쓰지 않는 대역)"
        + " errorType=" + thrown.getErrorType());

    assertTrue(harness.counter.callCount >= 1,
        "Z8: 대역이 한 번도 불리지 않았다 — 키 가드 등에 막힌 것이므로 시도 수 단언이 무의미하다");
    assertEquals(NON_RETRYABLE_ATTEMPTS, harness.counter.callCount, "Z8: 시도 수가 기대와 다르다");
    assertEquals(ApiErrorHandler.ErrorType.UNKNOWN_ERROR, thrown.getErrorType(),
        "Z8: 최종 errorType이 UNKNOWN_ERROR가 아니다");
  }

  // ===================================================================
  // P1~P8 — 바뀌면 안 되는 양성 대조군 (변경 전후 같은 값)
  // ===================================================================

  /** 실제 연결 거부 예외 — {@code connection}·{@code refused} 규칙으로 잡히므로 값이 그대로다. */
  @Test
  void P1_실제_연결거부_예외는_CONNECT_FAILURE로_분류된다() throws Exception {
    Exception thrown = anthropicFailure("http://localhost:" + deadPort());

    String message = thrown.getMessage();
    List<String> matched = matchedRuleWords(message);
    assertEquals(0, ApiErrorHandler.httpStatusOf(thrown), "P1 전제: 연결 거부 예외에는 상태 코드가 없어야 한다");
    assertTrue(matched.contains("connection") || matched.contains("refused"),
        "P1 전제: 연결 거부 메시지가 connection·refused 어느 규칙에도 걸리지 않는다"
            + " — 이 케이스가 io 규칙에 기대고 있다는 뜻이다. 원문=" + message);

    ApiErrorHandler.ErrorType actual = apiErrorHandler.classifyError(thrown, 0);
    System.out.println("[RC 상태0 분류] P1 실제 연결 거부"
        + " | 예외=" + thrown.getClass().getName()
        + " | 메시지 첫 줄=" + firstLine(message)
        + " | 규칙 단어=" + matched
        + " | 분류=" + actual
        + " | isRetryable=" + apiErrorHandler.isRetryable(actual));

    assertEquals(ApiErrorHandler.ErrorType.CONNECT_FAILURE, actual, "P1: 연결 거부 분류가 바뀌었다");
    assertTrue(apiErrorHandler.isRetryable(actual), "P1: 연결 거부가 재시도 대상이 아니게 됐다");
  }

  /**
   * 실제 JDK {@link SocketException}({@code Connection reset})을 원인으로 담은 비검사 예외.
   * 메시지에 {@code connection}과 {@code io}가 함께 있는데, 상태 0 경로는 {@code connection}
   * 규칙을 {@code io} 규칙보다 먼저 보므로 {@code io} 규칙이 없어져도 값이 그대로다.
   */
  @Test
  void P2_실제_Connection_reset_예외는_CONNECT_FAILURE로_분류된다() {
    UncheckedIOException probe = new UncheckedIOException(new SocketException("Connection reset"));

    String message = probe.getMessage();
    List<String> matched = matchedRuleWords(message);
    ApiErrorHandler.ErrorType actual = apiErrorHandler.classifyError(probe, 0);

    System.out.println("[RC 전제] P2 예외=" + probe.getClass().getName()
        + " | 메시지 원문=" + message
        + " | 원인=" + probe.getCause()
        + " | 규칙 단어=" + matched
        + " | connection 위치=" + message.toLowerCase().indexOf("connection")
        + " | io 위치=" + message.toLowerCase().indexOf("io")
        + " | 분류=" + actual
        + " | isRetryable=" + apiErrorHandler.isRetryable(actual)
        + " | 상태 0 경로는 connection 규칙을 io 규칙보다 먼저 본다");

    assertEquals(0, ApiErrorHandler.httpStatusOf(probe), "P2 전제: 이 예외에는 상태 코드가 없어야 한다");
    assertTrue(matched.contains("connection"),
        "P2 전제: 메시지에 connection이 없다 — io 규칙에 기대지 않는 대조군이 되지 못한다. 원문=" + message);
    assertTrue(matched.contains("io"),
        "P2 전제: 메시지에 io가 없다 — 두 규칙의 순서를 보이는 케이스가 되지 못한다. 원문=" + message);

    assertEquals(ApiErrorHandler.ErrorType.CONNECT_FAILURE, actual, "P2: 연결 끊김 분류가 바뀌었다");
    assertTrue(apiErrorHandler.isRetryable(actual), "P2: 연결 끊김이 재시도 대상이 아니게 됐다");
  }

  @Test
  void P3_읽기시간초과_예외는_NETWORK_TIMEOUT으로_분류된다() {
    UncheckedIOException probe =
        new UncheckedIOException(new SocketTimeoutException("Read timed out"));

    String message = probe.getMessage();
    List<String> matched = matchedRuleWords(message);
    ApiErrorHandler.ErrorType actual = apiErrorHandler.classifyError(probe, 0);

    System.out.println("[RC 상태0 분류] P3 읽기 시간 초과"
        + " | 메시지 원문=" + message
        + " | 규칙 단어=" + matched
        + " | 분류=" + actual
        + " | isRetryable=" + apiErrorHandler.isRetryable(actual));

    assertEquals(0, ApiErrorHandler.httpStatusOf(probe), "P3 전제: 이 예외에는 상태 코드가 없어야 한다");
    assertTrue(matched.contains("timed out"),
        "P3 전제: 메시지에 timed out이 없다. 원문=" + message);
    assertEquals(ApiErrorHandler.ErrorType.NETWORK_TIMEOUT, actual, "P3: 시간 초과 분류가 바뀌었다");
    assertTrue(apiErrorHandler.isRetryable(actual), "P3: 시간 초과가 재시도 대상이 아니게 됐다");
  }

  /** {@code access denied}에는 {@code io} 글자가 없어 변경 전에도 파일 규칙에 도달했다. */
  @Test
  void P4_access_denied_메시지는_FILE_READ_ERROR로_분류된다() {
    assertFileReadError("P4 access denied", new RuntimeException("Access denied"), "access denied");
  }

  @Test
  void P5_no_such_file_메시지는_FILE_READ_ERROR로_분류된다() {
    assertFileReadError("P5 no such file",
        new RuntimeException("No such file or directory"), "no such file");
  }

  /**
   * 401 픽스처 — 응답 본문의 {@code authentication_error} 안에 {@code io}가 있지만 상태 코드가
   * 있으므로 상태 코드 표가 분류를 결정한다(직전 사이클이 고친 순서). 상태 0 경로를 건드려도 그대로다.
   */
  @Test
  void P6_픽스처_401은_API_AUTHENTICATION으로_분류된다() {
    WebClientResponseException thrown =
        AnthropicErrorFixtures.thrownBy(AnthropicErrorFixtures.Case.AUTH_401_1);
    int status = ApiErrorHandler.httpStatusOf(thrown);
    assertEquals(401, status, "P6 전제: 픽스처에서 401이 추출되지 않았다");

    ApiErrorHandler.ErrorType actual = apiErrorHandler.classifyError(thrown, status);
    System.out.println("[RC 상태0 분류] P6 픽스처 401 (status=" + status + ")"
        + " | 메시지=" + AnthropicErrorFixtures.maskRequestIds(thrown.getMessage())
        + " | 본문에 걸리는 규칙 단어=" + matchedRuleWords(thrown.getMessage())
        + " | 분류=" + actual
        + " | isRetryable=" + apiErrorHandler.isRetryable(actual));

    assertEquals(ApiErrorHandler.ErrorType.API_AUTHENTICATION, actual, "P6: 401 분류가 바뀌었다");
    assertFalse(apiErrorHandler.isRetryable(actual), "P6: 인증 실패가 재시도 대상이 됐다");
  }

  /** 크레딧 규칙은 상태 코드보다도 앞에서 보므로 {@code io}가 든 벌도 그대로다. */
  @Test
  void P7_픽스처_크레딧소진_requestid에_io가_든_벌은_INSUFFICIENT_CREDITS로_분류된다() {
    WebClientResponseException thrown =
        AnthropicErrorFixtures.thrownBy(AnthropicErrorFixtures.Case.CREDIT_400_REQUEST_ID_IO);
    int status = ApiErrorHandler.httpStatusOf(thrown);
    assertEquals(400, status, "P7 전제: 픽스처에서 400이 추출되지 않았다");

    ApiErrorHandler.ErrorType actual = apiErrorHandler.classifyError(thrown, status);
    System.out.println("[RC 상태0 분류] P7 크레딧 소진 io 벌 (status=" + status + ")"
        + " | 메시지=" + AnthropicErrorFixtures.maskRequestIds(thrown.getMessage())
        + " | 본문에 걸리는 규칙 단어=" + matchedRuleWords(thrown.getMessage())
        + " | 분류=" + actual);

    assertEquals(ApiErrorHandler.ErrorType.INSUFFICIENT_CREDITS, actual,
        "P7: 크레딧 소진 분류가 바뀌었다");
  }

  /** 끝단 대조 — 진짜 연결 오류는 그대로 재시도 대상이므로 4회다(Z8의 1회가 공허하지 않음을 보인다). */
  @Test
  void P8_끝단_연결거부는_4회_요청한다() throws Exception {
    Harness harness = newHarness(new AnthropicLlmClient(
        AnthropicErrorFixtures.INVALID_KEY, "http://localhost:" + deadPort()), operationalConfig());

    AnalysisException thrown = assertThrows(AnalysisException.class, harness::analyzeOneFile);

    System.out.println("[RC 시도 수] P8 연결거부 위임=" + harness.counter.callCount
        + " 서버=(해당없음 — 요청이 서버에 닿지 않음)"
        + " errorType=" + thrown.getErrorType());

    assertTrue(harness.counter.callCount >= 1,
        "P8: LLM이 한 번도 불리지 않았다 — 키 가드 등에 막힌 것이므로 시도 수 단언이 무의미하다");
    assertEquals(RETRYABLE_ATTEMPTS, harness.counter.callCount, "P8: 시도 수가 기대와 다르다");
    assertEquals(ApiErrorHandler.ErrorType.CONNECT_FAILURE, thrown.getErrorType(),
        "P8: 최종 errorType이 CONNECT_FAILURE가 아니다");
  }

  // ===================================================================
  // 도우미 — 메시지 규칙 확인
  // ===================================================================

  /** 메시지(소문자)에 실제로 걸리는 상태 코드 0 경로 규칙 단어를 규칙 순서대로 모은다. */
  private static List<String> matchedRuleWords(String message) {
    String lowered = message == null ? "" : message.toLowerCase();
    List<String> matched = new ArrayList<>();
    for (String word : STATUS_ZERO_RULE_WORDS) {
      if (lowered.contains(word)) {
        matched.add(word);
      }
    }
    return matched;
  }

  private void assertUnknown(String id, Exception probe) {
    assertEquals(0, ApiErrorHandler.httpStatusOf(probe), id + " 전제: 이 예외에는 상태 코드가 없어야 한다");
    assertEquals(List.of("io"), matchedRuleWords(probe.getMessage()),
        id + " 전제: 걸리는 규칙 단어가 io 하나여야 한다");

    ApiErrorHandler.ErrorType actual = apiErrorHandler.classifyError(probe, 0);
    System.out.println("[RC 상태0 분류] " + id
        + " | 메시지 원문=" + probe.getMessage()
        + " | 규칙 단어=" + matchedRuleWords(probe.getMessage())
        + " | 분류=" + actual
        + " | isRetryable=" + apiErrorHandler.isRetryable(actual));

    assertEquals(ApiErrorHandler.ErrorType.UNKNOWN_ERROR, actual,
        id + ": 연결과 무관한 메시지가 연결 실패로 분류된다");
  }

  private void assertFileReadError(String id, Exception probe, String expectedWord) {
    List<String> matched = matchedRuleWords(probe.getMessage());
    assertEquals(0, ApiErrorHandler.httpStatusOf(probe), id + " 전제: 이 예외에는 상태 코드가 없어야 한다");
    assertEquals(List.of(expectedWord), matched,
        id + " 전제: 걸리는 규칙 단어가 " + expectedWord + " 하나여야 한다(io가 없어야 대조군이 된다)");

    ApiErrorHandler.ErrorType actual = apiErrorHandler.classifyError(probe, 0);
    System.out.println("[RC 상태0 분류] " + id
        + " | 메시지 원문=" + probe.getMessage()
        + " | 규칙 단어=" + matched
        + " | 분류=" + actual
        + " | isRetryable=" + apiErrorHandler.isRetryable(actual));

    assertEquals(ApiErrorHandler.ErrorType.FILE_READ_ERROR, actual, id + ": 파일 오류 분류가 바뀌었다");
  }

  private static String firstLine(String text) {
    if (text == null) {
      return "(null)";
    }
    int at = text.indexOf('\n');
    return at < 0 ? text : text.substring(0, at);
  }

  // ===================================================================
  // 도우미 — 실제 예외 만들기
  // ===================================================================

  /** 띄웠다 내린 포트 번호 — 연결 거부를 실제로 만들기 위한 것이다. */
  private static int deadPort() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    int port = server.getPort();
    server.shutdown();
    return port;
  }

  private static Exception anthropicFailure(String apiUrl) {
    AnthropicLlmClient client =
        new AnthropicLlmClient(AnthropicErrorFixtures.INVALID_KEY, apiUrl);
    return assertThrows(Exception.class,
        () -> client.call("system prompt", "user content", MODEL, 16),
        "내려간 포트로 호출하면 예외가 나야 한다");
  }

  // ===================================================================
  // 도우미 — 끝단 하네스 (Z8·P8)
  // ===================================================================

  /** 실제 클라이언트에 위임하면서 호출 횟수만 세는 껍데기. 분류·재시도에는 전혀 개입하지 않는다. */
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

  /** Z8 전용 대역 — 규칙 단어 중 {@code io}만 걸리는 메시지의 예외를 던진다. */
  private static final class GenericIoWordClient implements LlmClient {
    private static final String MESSAGE = "delegate stub raised a generic exception";

    private static Exception failure() {
      return new RuntimeException(MESSAGE);
    }

    @Override
    public LlmResult call(String systemPrompt, String userContent, String model, int maxTokens) {
      throw new RuntimeException(MESSAGE);
    }
  }

  /** 운영과 같은 재시도 정책(대기만 짧게). */
  private static SessionConfig operationalConfig() {
    SessionConfig config = new SessionConfig();
    config.setMaxRetries(MAX_RETRIES);
    config.setInitialRetryDelayMs(1);
    config.setMaxRetryDelayMs(10);
    return config;
  }

  /** 운영과 같은 모양으로 배선한 1회 분석 하네스. */
  private static final class Harness {
    private final RetryHandler retryHandler;
    private final ClaudeService claudeService;
    private final CountingDelegate counter;

    private Harness(RetryHandler retryHandler, ClaudeService claudeService,
        CountingDelegate counter) {
      this.retryHandler = retryHandler;
      this.claudeService = claudeService;
      this.counter = counter;
    }

    /** {@code MainApiController.analyzeFile}과 같은 형태 — 4인자 호출을 바깥 재시도로 감싼다. */
    private String analyzeOneFile() {
      return retryHandler.executeWithRetry(SESSION_ID, SESSION_ROOT + "/Sample.java",
          () -> claudeService.analyzeCodeWithClaude(SOURCE_CODE, "Sample.java",
              SESSION_ROOT, SESSION_ROOT + "/Sample.java"));
    }
  }

  private Harness newHarness(LlmClient client, SessionConfig sessionConfig) {
    CountingDelegate counter = new CountingDelegate(client);
    ClaudeServiceImpl service = newClaudeService(counter, sessionConfig);
    return new Harness(new RetryHandler(apiErrorHandler, sessionConfig), service, counter);
  }

  /** 안쪽(ClaudeServiceImpl)과 바깥(RetryHandler)이 같은 설정·같은 분류기를 쓰게 한다. */
  private ClaudeServiceImpl newClaudeService(CountingDelegate counter, SessionConfig sessionConfig) {
    LlmClientResolver resolver = new LlmClientResolver(counter, counter);
    ClaudeServiceImpl service = new ClaudeServiceImpl(
        apiErrorHandler, null, sessionConfig, null, resolver, null, null);
    setField(service, "llmProvider", "anthropic");
    setField(service, "apiModel", MODEL);
    setField(service, "llmLocalModel", MODEL);
    // 키 가드를 통과하는 무효 문자열 — 자격증명이 아니다(G-10).
    setField(service, "apiKey", AnthropicErrorFixtures.INVALID_KEY);
    setField(service, "systemPromptFilename", "prompts/prompt-base.md");
    return service;
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
}
