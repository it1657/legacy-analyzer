package com.legacy.analysis.llm;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-007 (work-order 2026-09-cost-stats-bugfix, REQ-002/003) — {@link AnthropicModelPricing}의
 * 단가 결정 규칙을 고정한다.
 *
 * <h2>무엇을 고정하는가</h2>
 * <ol>
 *   <li><b>단가 값 3종은 종전과 동일</b>(opus $15/$75, sonnet $3/$15, haiku $0.80/$4). 이번 사이클이
 *       바꾼 것은 "값"이 아니라 "결정 구조"다 — 값이 바뀌면 이 테스트가 먼저 깨져야 한다.</li>
 *   <li><b>정확 키 → 패밀리 → 미지 폴백</b>의 3단 조회 순서. 특히 시드에 없는 세대
 *       ({@code claude-sonnet-5})가 패밀리 매칭으로 sonnet 단가를 받는다 — 정확 키 목록만으로는
 *       기존 비용 테스트가 깨진다.</li>
 *   <li><b>미지 모델은 최저가 아니라 최고 단가로 추정</b>하고 WARN을 남긴다. 종전 구현은 else 분기가
 *       haiku(최저)여서 신규 모델의 과금을 조용히 과소 추정했다.</li>
 *   <li><b>패밀리 매칭이 {@code claude-} 접두로 좁혀져 있다</b> — {@code opus-coder:7b}처럼 로컬
 *       모델풍 이름이 Claude Opus로 오판되지 않는다.</li>
 * </ol>
 *
 * <h2>WARN 억제 집합이 static이라는 점</h2>
 * 억제 집합은 클래스 전역(static)이므로 <b>같은 JVM에서 실행되는 테스트 사이에 상태가 남는다.</b>
 * 따라서 각 테스트는 <b>자기만 쓰는 모델키</b>를 사용한다 — 다른 테스트가 먼저 경고를 남겨
 * 억제해버리면 로그 관측이 무의미해지기 때문이다. 프로덕션에서는 이 누적이 의도된 동작이다
 * (프로세스 생애 동안 같은 경고를 반복하지 않는다).
 */
class AnthropicModelPricingTest {

  // ---------- ① 정확 키 매칭 (DB 시드 3종) ----------

  @Test
  void 시드_키_claude_opus_4_8은_opus_단가다() {
    AnthropicModelPricing.Pricing pricing = AnthropicModelPricing.of("claude-opus-4-8");

    assertEquals(15.00, pricing.inputPerMillionTokens(), 0.0001);
    assertEquals(75.00, pricing.outputPerMillionTokens(), 0.0001);
  }

  @Test
  void 시드_키_claude_sonnet_4_6은_sonnet_단가다() {
    AnthropicModelPricing.Pricing pricing = AnthropicModelPricing.of("claude-sonnet-4-6");

    assertEquals(3.00, pricing.inputPerMillionTokens(), 0.0001);
    assertEquals(15.00, pricing.outputPerMillionTokens(), 0.0001);
  }

  @Test
  void 시드_키_claude_haiku_4_5_20251001은_haiku_단가다() {
    AnthropicModelPricing.Pricing pricing = AnthropicModelPricing.of("claude-haiku-4-5-20251001");

    assertEquals(0.80, pricing.inputPerMillionTokens(), 0.0001);
    assertEquals(4.00, pricing.outputPerMillionTokens(), 0.0001);
  }

  // ---------- ② 패밀리 토큰 매칭 ----------

  @Test
  void 시드에_없는_세대_claude_sonnet_5도_패밀리_매칭으로_sonnet_단가다() {
    // 기존 비용 테스트(MainApiControllerLlmProviderTest)가 이 키에 sonnet 단가를 기대하므로,
    // 정확 키 목록만 갖고 있으면 그 테스트가 깨진다 — 패밀리 매칭이 반드시 커버해야 하는 자리다.
    AnthropicModelPricing.Pricing pricing = AnthropicModelPricing.of("claude-sonnet-5");

    assertEquals(3.00, pricing.inputPerMillionTokens(), 0.0001);
    assertEquals(15.00, pricing.outputPerMillionTokens(), 0.0001);
  }

  @Test
  void 대소문자가_섞여도_정규화되어_같은_단가를_돌려준다() {
    AnthropicModelPricing.Pricing mixedCase = AnthropicModelPricing.of("Claude-Sonnet-4-6");

    assertEquals(3.00, mixedCase.inputPerMillionTokens(), 0.0001);
    assertEquals(15.00, mixedCase.outputPerMillionTokens(), 0.0001);
  }

  @Test
  void 패밀리_매칭은_opus와_haiku에도_적용된다() {
    AnthropicModelPricing.Pricing opus = AnthropicModelPricing.of("claude-opus-9-1");
    AnthropicModelPricing.Pricing haiku = AnthropicModelPricing.of("claude-haiku-9-1");

    assertEquals(15.00, opus.inputPerMillionTokens(), 0.0001);
    assertEquals(75.00, opus.outputPerMillionTokens(), 0.0001);
    assertEquals(0.80, haiku.inputPerMillionTokens(), 0.0001);
    assertEquals(4.00, haiku.outputPerMillionTokens(), 0.0001);
  }

  // ---------- ③ 미지 모델 폴백 (D2) ----------

  @Test
  void 등록되지_않은_claude_모델은_최저가_아니라_최고_단가로_추정한다() {
    // 종전 구현은 else 분기가 haiku($0.80/$4)여서 신규 모델의 과금을 조용히 과소 추정했다.
    AnthropicModelPricing.Pricing pricing = AnthropicModelPricing.of("claude-newmodel-9");

    assertEquals(15.00, pricing.inputPerMillionTokens(), 0.0001, "미지 모델은 opus 단가로 추정해야 함");
    assertEquals(75.00, pricing.outputPerMillionTokens(), 0.0001);
  }

  @Test
  void claude와_무관한_키도_예외없이_최고_단가로_추정한다() {
    AnthropicModelPricing.Pricing pricing = AnthropicModelPricing.of("some-unknown-key");

    assertEquals(15.00, pricing.inputPerMillionTokens(), 0.0001);
    assertEquals(75.00, pricing.outputPerMillionTokens(), 0.0001);
  }

  @Test
  void null과_빈_문자열은_예외없이_폴백으로_흐른다() {
    // 비용 추정이 분석 자체를 실패시키면 안 된다.
    AnthropicModelPricing.Pricing fromNull = AnthropicModelPricing.of(null);
    AnthropicModelPricing.Pricing fromBlank = AnthropicModelPricing.of("   ");

    assertEquals(15.00, fromNull.inputPerMillionTokens(), 0.0001);
    assertEquals(75.00, fromNull.outputPerMillionTokens(), 0.0001);
    assertEquals(15.00, fromBlank.inputPerMillionTokens(), 0.0001);
    assertEquals(75.00, fromBlank.outputPerMillionTokens(), 0.0001);
  }

  // ---------- 패밀리 매칭 좁히기 회귀 (DoD d) ----------

  @Test
  void 로컬_모델풍_이름_opus_coder는_opus로_오판되지_않고_미지_경로로_흐른다() {
    // 단가는 결과적으로 opus와 같지만 경로가 다르다: "opus 패밀리로 인식"이 아니라
    // "모르는 모델이라 최고 단가로 추정"이어야 하고, 그 증거로 WARN이 남아야 한다.
    // contains("opus")였던 종전 구현에서는 WARN 없이 조용히 Opus 단가가 적용됐다.
    String localStyleKey = "opus-coder:7b";

    List<String> warnings = captureWarnings(() -> AnthropicModelPricing.of(localStyleKey));

    assertEquals(1, warnings.size(), "미지 모델 경로를 탔다면 WARN이 정확히 1건 남아야 함");
    assertTrue(warnings.get(0).contains(localStyleKey),
        "WARN 문구에 문제가 된 모델키가 들어 있어야 운영자가 조치할 수 있다 — 실제 문구: "
            + warnings.get(0));
    assertTrue(warnings.get(0).contains("최고 단가"),
        "WARN 문구에 '최고 단가로 추정했다'는 사실이 들어 있어야 한다 — 실제 문구: " + warnings.get(0));
  }

  // ---------- WARN 중복 억제 실측 (DoD b) ----------

  @Test
  void 같은_미지_모델키를_5회_조회해도_WARN은_1회만_남는다() {
    // 파일 수백 개를 분석하는 세션에서는 이 조회가 파일마다 일어난다 — 억제가 없으면 같은 경고가
    // 수백 줄 찍혀 아무도 읽지 않는다. 억제 집합의 상태를 들여다보는 대신 실제 로그를 캡처해
    // 건수를 관측한다(DataInitializerTest의 ListAppender 관례).
    String probeKey = "unknown-suppression-probe-model";

    List<String> warnings = captureWarnings(() -> {
      for (int i = 0; i < 5; i++) {
        AnthropicModelPricing.of(probeKey);
      }
    });

    assertEquals(1, warnings.size(),
        "같은 모델키 5회 조회에 WARN은 1건이어야 함 — 실제: " + warnings);
    assertTrue(warnings.get(0).contains(probeKey));
  }

  @Test
  void 서로_다른_미지_모델키는_각각_한_번씩_경고한다() {
    // 억제가 "모델키당 1회"이지 "전체 1회"가 아님을 고정한다. 억제 단위를 잘못 잡으면
    // 두 번째 미지 모델이 영원히 보고되지 않는다.
    List<String> warnings = captureWarnings(() -> {
      AnthropicModelPricing.of("unknown-probe-alpha");
      AnthropicModelPricing.of("unknown-probe-alpha");
      AnthropicModelPricing.of("unknown-probe-beta");
      AnthropicModelPricing.of("unknown-probe-beta");
    });

    assertEquals(2, warnings.size(), "서로 다른 키 2종이므로 WARN 2건이어야 함 — 실제: " + warnings);
    assertTrue(warnings.stream().anyMatch(m -> m.contains("unknown-probe-alpha")));
    assertTrue(warnings.stream().anyMatch(m -> m.contains("unknown-probe-beta")));
  }

  @Test
  void 알려진_모델은_경고를_남기지_않는다() {
    // 양성 대조군의 음성 짝 — 위 억제 테스트들이 "WARN이 1건"을 단언하므로, 정상 경로에서는
    // 애초에 0건이어야 함을 함께 고정해야 "1건"이 의미를 갖는다.
    List<String> warnings = captureWarnings(() -> {
      AnthropicModelPricing.of("claude-sonnet-4-6");
      AnthropicModelPricing.of("claude-opus-4-8");
      AnthropicModelPricing.of("claude-haiku-4-5-20251001");
      AnthropicModelPricing.of("claude-sonnet-5");
    });

    assertEquals(List.of(), warnings, "단가를 아는 모델에는 경고가 남지 않아야 함");
  }

  /**
   * {@link AnthropicModelPricing}가 남긴 WARN 메시지만 캡처한다.
   * {@code DataInitializerTest}의 기존 관례(ListAppender 부착 → 실행 → finally에서 분리)를 따른다.
   */
  private List<String> captureWarnings(Runnable action) {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AnthropicModelPricing.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      action.run();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
    return appender.list.stream()
        .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }
}
