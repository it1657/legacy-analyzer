package com.legacy.analysis.llm;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TASK-002 (work-order 2026-10-counter-integrity-and-failure-reason v1) —
 * <b>실제</b> Anthropic 엔드포인트가 401에 돌려주는 오류 본문 형식을 1회 확인해,
 * {@link AnthropicErrorFixtures}의 픽스처 본문이 실물과 같은 형식인지 대조한다(예측 P-07).
 *
 * <p><b>{@code @Tag("manual")} — {@code ./gradlew test}·{@code qaTest}에서는 실행되지 않는다.</b>
 * 기본 스위트에서는 컴파일 통과만 확인되고, 실행은 {@code ./gradlew localSmokeTest
 * --tests '*AnthropicRealErrorBodyManualTest'}로 따로 한다(수동 실행 결과 또는 미실행 사유는
 * {@code 05-dev-progress.md}에 기록한다).
 *
 * <p>안전 조건:
 * <ul>
 *   <li>키는 <b>이 파일에 적힌 리터럴 무효 문자열</b>이다 — 환경변수·설정 파일의 키를 읽지 않는다(G-10).
 *       무효 키라 인증 전에 거절되므로 과금이 발생하지 않고, 자격증명도 필요하지 않다.</li>
 *   <li>호출은 <b>1회</b>다(재시도 없음 — 이 테스트는 {@code RetryHandler}를 거치지 않는다).</li>
 *   <li>출력에는 {@code request_id}를 앞 8자만 남긴다({@link AnthropicErrorFixtures#maskRequestIds}).</li>
 *   <li>네트워크가 막혀 있으면 실패가 아니라 <b>건너뜀</b>으로 끝내고 사유를 출력한다 —
 *       형식 확인은 TASK-008 S3(실기동)로 이월한다.</li>
 * </ul>
 */
@Tag("manual")
class AnthropicRealErrorBodyManualTest {

  /** 실제 Anthropic 엔드포인트. */
  private static final String REAL_URL = "https://api.anthropic.com";

  /** 리터럴 무효 키 — 자격증명이 아니다. 설정·환경변수에서 읽지 않는다. */
  private static final String LITERAL_INVALID_KEY = "invalid-key-for-counter-verify";

  @Test
  void 실제_401_본문이_픽스처와_같은_형식인지_1회_확인한다() {
    AnthropicLlmClient client = new AnthropicLlmClient(LITERAL_INVALID_KEY, REAL_URL);

    WebClientResponseException thrown = null;
    String skipReason = null;
    try {
      client.call("system prompt", "user content", "claude-sonnet-5", 16);
      skipReason = "오류 없이 응답이 돌아왔다 — 무효 키인데 2xx가 나온 것이므로 형식 확인을 하지 않고"
          + " PL 판정을 요청한다.";
    } catch (WebClientResponseException e) {
      thrown = e;
    } catch (Exception e) {
      // 연결 자체가 안 된 경우(네트워크 차단·DNS 실패 등) — 차단 아님, TASK-008 S3로 이월.
      skipReason = "실제 엔드포인트에 연결하지 못해 형식 확인을 미실행한다(TASK-008 S3로 이월). 사유="
          + e.getClass().getName() + ": " + e.getMessage();
    }

    if (skipReason != null) {
      System.out.println("[002 실제 401 확인 미실행] " + skipReason);
      assumeTrue(false, skipReason);
    }

    String masked = AnthropicErrorFixtures.maskRequestIds(thrown.getMessage());
    System.out.println("[002 실제 401 원문(request_id 가림)] " + masked);
    System.out.println("[002 픽스처 401 본문] "
        + AnthropicErrorFixtures.body(AnthropicErrorFixtures.Case.AUTH_401_1));

    assertEquals(401, thrown.getStatusCode().value(),
        "무효 키 호출의 상태 코드가 401이 아니다 — 픽스처 전제가 다르므로 PL 판정을 요청한다. 원문=" + masked);

    // 예측 P-07 — 실제 401 본문에 `authentication_error`가 들어 있다(그래서 현행 "io" 검사에 걸린다).
    assertTrue(masked.contains("authentication_error"),
        "실제 401 본문에 authentication_error가 없다 — 픽스처 형식과 다르므로 PL 판정을 요청한다. 원문="
            + masked);
    assertTrue(masked.contains("req_"),
        "실제 401 본문에 request_id가 없다 — 사유 정규화 전제가 다르므로 PL 판정을 요청한다. 원문=" + masked);
  }
}
