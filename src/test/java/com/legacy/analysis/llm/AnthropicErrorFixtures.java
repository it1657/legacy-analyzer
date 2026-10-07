package com.legacy.analysis.llm;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TASK-002 (work-order 2026-10-counter-integrity-and-failure-reason v1) —
 * Anthropic 오류 응답 본문 픽스처와, 그 본문을 돌려주는 {@link MockWebServer}를 상대로
 * <b>실제 {@link AnthropicLlmClient}가 던진 예외 객체</b>를 돌려주는 테스트 지원 클래스.
 * 이 클래스 자체는 테스트가 아니다(@Test 없음 — 스위트 클래스 수에 잡히지 않는다).
 *
 * <p>왜 예외를 손으로 만들지 않는가: 분류기(`ApiErrorHandler.classifyError`)가 보는 것은
 * {@code exception.getMessage()}인데, 그 문자열은 ① {@code AnthropicLlmClient}가 만드는
 * {@code "Claude API %d 오류: {본문 최대 300자}"} 와 ② Spring이 {@code WebClientResponseException}에
 * 붙이는 앞머리({@code "401 "} 등)가 겹친 결과다. 손으로 쓴 문자열로 분류를 재면 실제와 다른 것을
 * 재게 되므로, 반드시 실제 클라이언트가 던진 객체를 쓴다.
 *
 * <p>본문은 Anthropic 공개 오류 형식({@code {"type":"error","error":{...},"request_id":"req_…"}})을 따르고
 * 전부 300자 미만이라 {@code request_id}가 절단에 살아남는다(사유 집계 정규화 검증에 필요 — TASK-006).
 */
public final class AnthropicErrorFixtures {

  /** 픽스처 본문이 놓인 클래스패스 폴더. */
  private static final String DIR = "/counterintegrity/http/";

  /**
   * 픽스처·검증용 무효 키. 자격증명이 아니다(G-10) — 어떤 실제 키도 읽지 않는다.
   */
  public static final String INVALID_KEY = "invalid-key-for-counter-verify";

  /** {@code req_} + 영숫자 토큰. 기록·출력 시 앞 8자만 남기고 가리는 데 쓴다. */
  private static final Pattern REQUEST_ID_TOKEN = Pattern.compile("req_[A-Za-z0-9]+");

  /** 픽스처 한 벌 = HTTP 상태 코드 + 본문 리소스. */
  public enum Case {
    /** 401 인증 실패 — 아래 4벌은 {@code request_id}만 다르다(사유 집계가 1종으로 합쳐져야 한다). */
    AUTH_401_1(401, "401-authentication-1.json"),
    AUTH_401_2(401, "401-authentication-2.json"),
    AUTH_401_3(401, "401-authentication-3.json"),
    AUTH_401_4(401, "401-authentication-4.json"),
    /** 403 권한 없음. */
    FORBIDDEN_403(403, "403-permission.json"),
    /** 429 요청 한도 초과. */
    RATE_LIMIT_429(429, "429-rate-limit.json"),
    /** 400 크레딧 소진 — 일반 벌. */
    CREDIT_400(400, "400-credit.json"),
    /** 400 크레딧 소진 — {@code request_id}에 소문자 {@code io}가 든 벌(현행 {@code "io"} 검사와 겹치는 경우). */
    CREDIT_400_REQUEST_ID_IO(400, "400-credit-request-id-io.json"),
    /** 500 서버 오류. */
    SERVER_500(500, "500-api-error.json");

    private final int status;
    private final String resource;

    Case(int status, String resource) {
      this.status = status;
      this.resource = resource;
    }

    public int status() {
      return status;
    }

    public String resource() {
      return DIR + resource;
    }
  }

  private AnthropicErrorFixtures() {
  }

  /** 픽스처 본문 원문(뒤쪽 줄바꿈 제거 — 서버가 실제로 돌려주는 바이트와 같게 둔다). */
  public static String body(Case fixture) {
    try (InputStream in = AnthropicErrorFixtures.class.getResourceAsStream(fixture.resource())) {
      if (in == null) {
        throw new IllegalStateException("픽스처 본문이 클래스패스에 없다: " + fixture.resource());
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      throw new UncheckedIOException("픽스처 본문을 읽지 못했다: " + fixture.resource(), e);
    }
  }

  /** 픽스처의 HTTP 상태 코드. */
  public static int status(Case fixture) {
    return fixture.status();
  }

  /** 픽스처 본문을 그대로 돌려주는 {@link MockResponse}(다른 테스트가 직접 enqueue할 때 쓴다). */
  public static MockResponse mockResponse(Case fixture) {
    return new MockResponse()
        .setResponseCode(fixture.status())
        .addHeader("Content-Type", "application/json")
        .setBody(body(fixture));
  }

  /**
   * 픽스처 본문을 돌려주는 {@link MockWebServer}를 띄우고, 실제 {@link AnthropicLlmClient}가
   * 그 응답을 받아 던진 {@link WebClientResponseException} 객체를 돌려준다.
   * 호출은 1회이며 서버는 돌려주기 전에 닫는다.
   */
  public static WebClientResponseException thrownBy(Case fixture) {
    MockWebServer server = new MockWebServer();
    try {
      server.start();
      server.enqueue(mockResponse(fixture));
      AnthropicLlmClient client =
          new AnthropicLlmClient(INVALID_KEY, "http://localhost:" + server.getPort());
      try {
        client.call("system prompt", "user content", "claude-sonnet-5", 1024);
      } catch (WebClientResponseException expected) {
        return expected;
      }
      throw new IllegalStateException(
          "픽스처 " + fixture + "에서 WebClientResponseException이 던져지지 않았다 — "
              + "픽스처가 오류 응답을 돌려주지 못한 것이므로 이 도우미를 쓰는 판정은 성립하지 않는다.");
    } catch (IOException e) {
      throw new UncheckedIOException("MockWebServer 기동/종료 실패: " + fixture, e);
    } finally {
      try {
        server.shutdown();
      } catch (IOException ignored) {
        // 종료 실패는 판정에 영향이 없다(테스트 JVM 종료 시 정리된다).
      }
    }
  }

  /**
   * 기록·출력용 가림 처리 — {@code req_…} 토큰을 <b>앞 8자만</b> 남긴다.
   * 식별자를 문서·로그에 그대로 남기지 않기 위한 것이다.
   */
  public static String maskRequestIds(String text) {
    if (text == null) {
      return null;
    }
    Matcher m = REQUEST_ID_TOKEN.matcher(text);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String token = m.group();
      String head = token.length() <= 8 ? token : token.substring(0, 8);
      m.appendReplacement(sb, Matcher.quoteReplacement(head + "…(가림)"));
    }
    m.appendTail(sb);
    return sb.toString();
  }
}
