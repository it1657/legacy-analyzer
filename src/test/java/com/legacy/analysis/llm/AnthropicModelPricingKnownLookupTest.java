package com.legacy.analysis.llm;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-002 (work-order 2026-09-pricing-source-unification v1, REQ-001) —
 * <b>표시용 조회 {@link AnthropicModelPricing#findKnown(String)}와 표시 문자열
 * {@code Pricing.label()}</b>을 고정한다.
 *
 * <h2>왜 계산용과 표시용을 나눴는가</h2>
 * <p>{@code of()}는 "모른다"를 반환할 수 없고(모르면 최고 단가로 추정) 모르는 키에 WARN을 남긴다.
 * 목록 조회 API는 사용자가 화면에 들어올 때마다 모델 전체에 대해 이 조회를 하므로, 거기서 {@code of()}를
 * 부르면 두 가지가 망가진다.
 * <ol>
 *   <li>LOCAL 모델·단가를 모르는 Claude 모델마다 WARN이 찍혀 로그가 묻힌다.</li>
 *   <li>더 나쁘게는, 모델키당 1회 억제 집합이 <b>표시 경로에서 먼저 채워져</b> 정작 비용 계산이
 *       그 모델을 처음 만났을 때 경고가 남지 않는다.</li>
 * </ol>
 * 그래서 {@code findKnown()}은 WARN을 남기지 않는다 — <b>이 테스트의 중심 단언이다.</b>
 *
 * <h2>"WARN 0건"을 어떻게 믿게 하는가 (양성 대조군)</h2>
 * <p>로그 캡처가 애초에 동작하지 않으면 "WARN 0건"은 공짜로 통과한다. 그래서 같은 캡처를 켠 채
 * 이어서 {@code of()}를 <b>이 테스트 전용 고유 미지 키</b>로 부르고 WARN이 정확히 1건 잡힘을 확인한다.
 * 키를 고유하게 두는 이유는 억제 집합이 {@code static}이라 같은 JVM의 다른 테스트와 키가 겹치면
 * 먼저 실행된 쪽이 경고를 소진해 대조군이 조용히 0건이 되기 때문이다.
 *
 * <h2>표시 연속성</h2>
 * <p>{@code label()}의 결과는 착수 커밋 고정본 시드 표시명에 박혀 있던 단가 문자열과 <b>글자 단위로
 * 같아야</b> 한다 — 사용자가 보던 문구가 이 사이클 때문에 달라지면 안 된다. 기대값을 손으로 적는 대신
 * TASK-001 고정본에서 실제로 뽑아내 비교한다.
 */
class AnthropicModelPricingKnownLookupTest {

  private static final String SERVICE_FIXTURE =
      "/pricingsinglesource/LlmModelOptionService.java.before-318e086.txt";

  // ── findKnown: 무엇을 아는가 ────────────────────────────────────────────────

  @Test
  void findKnown은_시드_3종_정확키를_안다() {
    assertEquals(new AnthropicModelPricing.Pricing(3.00, 15.00),
        findKnownOrFail("claude-sonnet-4-6"));
    assertEquals(new AnthropicModelPricing.Pricing(15.00, 75.00),
        findKnownOrFail("claude-opus-4-8"));
    assertEquals(new AnthropicModelPricing.Pricing(0.80, 4.00),
        findKnownOrFail("claude-haiku-4-5-20251001"));
  }

  @Test
  void findKnown은_시드에_없는_세대도_패밀리_매칭으로_안다() {
    assertEquals(new AnthropicModelPricing.Pricing(3.00, 15.00), findKnownOrFail("claude-sonnet-5"));
  }

  @Test
  void findKnown은_대소문자_혼용과_앞뒤_공백을_정규화한다() {
    assertEquals(new AnthropicModelPricing.Pricing(3.00, 15.00),
        findKnownOrFail("  Claude-Sonnet-4-6  "));
    assertEquals(new AnthropicModelPricing.Pricing(15.00, 75.00), findKnownOrFail("CLAUDE-OPUS-4-8"));
  }

  /** 미지 키는 {@code of()}처럼 최고 단가로 떨어지지 않고 "모른다"가 된다 — D3의 근거. */
  @Test
  void findKnown은_모르는_모델에_empty를_준다() {
    assertTrue(AnthropicModelPricing.findKnown("gpt-4o").isEmpty());
    assertTrue(AnthropicModelPricing.findKnown("qwen2.5-coder:7b").isEmpty());
  }

  @Test
  void findKnown은_null과_공백에도_예외없이_empty를_준다() {
    assertTrue(AnthropicModelPricing.findKnown(null).isEmpty());
    assertTrue(AnthropicModelPricing.findKnown("").isEmpty());
    assertTrue(AnthropicModelPricing.findKnown("   ").isEmpty());
  }

  // ── findKnown은 WARN을 남기지 않는다 (+ 양성 대조군) ────────────────────────

  /**
   * <b>본 단언 + 양성 대조군이 한 테스트 안에 있다.</b> 같은 캡처를 켠 채 ① 아는 키·모르는 키·LOCAL 키·
   * null을 {@code findKnown()}으로 조회(WARN 0건) ② 이어서 고유 미지 키로 {@code of()}를 조회
   * (WARN 정확히 1건). ②가 없으면 ①은 "캡처가 죽어 있었다"와 구별되지 않는다.
   */
  @Test
  void findKnown은_WARN을_남기지_않는다_같은_캡처로_of는_1건을_남긴다() {
    String probeKey = "claude-unknown-knownlookup-warnfree-probe";

    List<String> warnings = captureWarnings(() -> {
      // ① 표시용 조회 — 아는 키·모르는 키·LOCAL 키·null 어디에도 경고를 남기지 않아야 한다.
      AnthropicModelPricing.findKnown("claude-sonnet-4-6");
      AnthropicModelPricing.findKnown("gpt-4o");
      AnthropicModelPricing.findKnown("qwen2.5-coder:7b");
      AnthropicModelPricing.findKnown(probeKey);
      AnthropicModelPricing.findKnown(null);

      // ② 양성 대조군 — 같은 캡처로 계산용 조회를 하면 경고가 잡힌다.
      AnthropicModelPricing.of(probeKey);
    });

    assertEquals(1, warnings.size(),
        "findKnown 5회 + of 1회에서 WARN은 of의 1건뿐이어야 한다(findKnown 0건). warnings=" + warnings);
    assertTrue(warnings.get(0).contains(probeKey),
        "그 1건은 of(고유 미지 키)가 남긴 것이어야 한다 — 캡처가 실제로 동작한다는 증거. warnings=" + warnings);
  }

  /**
   * 억제 집합 오염 방지의 실질을 확인한다 — {@code findKnown()}을 여러 번 불러도 그 뒤의
   * {@code of()}가 <b>여전히</b> 경고를 남긴다. {@code findKnown()}이 억제 집합에 키를 넣어버리면
   * 이 단언이 깨진다(F2가 경고한 오염 경로).
   */
  @Test
  void findKnown은_억제집합을_오염시키지_않아_뒤이은_of가_여전히_경고한다() {
    String probeKey = "claude-unknown-knownlookup-suppression-probe";

    List<String> warnings = captureWarnings(() -> {
      for (int i = 0; i < 5; i++) {
        AnthropicModelPricing.findKnown(probeKey);
      }
      AnthropicModelPricing.of(probeKey);
    });

    assertEquals(1, warnings.size(),
        "findKnown 5회가 억제 집합을 채웠다면 뒤이은 of가 조용해져 0건이 된다. warnings=" + warnings);
  }

  // ── findKnown ↔ of 일관성 (RG-1) ───────────────────────────────────────────

  /**
   * <b>RG-1</b> — 매칭 규칙이 한 곳에만 있음을 결과로 확인한다. {@code findKnown()}이 값을 주면
   * {@code of()}와 같고, 주지 않으면 {@code of()}가 미지 폴백(OPUS 15/75)과 같다.
   * 두 메서드가 각자 조회 순서를 갖게 되면 어느 키에서든 이 단언이 깨진다.
   */
  @Test
  void findKnown과_of는_모든_샘플키에서_일관된다() {
    AnthropicModelPricing.Pricing fallback = new AnthropicModelPricing.Pricing(15.00, 75.00);
    List<String> sampleKeys = List.of(
        "claude-sonnet-4-6", "claude-opus-4-8", "claude-haiku-4-5-20251001",
        "claude-sonnet-5", "claude-opus-9-9", "claude-haiku-9",
        "CLAUDE-SONNET-4-6", "  claude-opus-4-8  ",
        "claude-unknown-knownlookup-consistency-a", "gpt-4o-knownlookup-consistency",
        "qwen2.5-coder:7b");

    for (String key : sampleKeys) {
      Optional<AnthropicModelPricing.Pricing> known = AnthropicModelPricing.findKnown(key);
      AnthropicModelPricing.Pricing computed = AnthropicModelPricing.of(key);
      if (known.isPresent()) {
        assertEquals(known.get(), computed,
            "findKnown이 아는 키는 of와 같은 단가여야 한다. key=" + key);
      } else {
        assertEquals(fallback, computed,
            "findKnown이 모르는 키는 of가 미지 폴백(OPUS)을 줘야 한다. key=" + key);
      }
    }
  }

  // ── label(): 표시 문자열 ───────────────────────────────────────────────────

  @Test
  void label은_정수면_소수점없이_아니면_소수_둘째자리로_쓴다() {
    assertEquals("$3/$15 per 1M", new AnthropicModelPricing.Pricing(3.00, 15.00).label());
    assertEquals("$15/$75 per 1M", new AnthropicModelPricing.Pricing(15.00, 75.00).label());
    assertEquals("$0.80/$4 per 1M", new AnthropicModelPricing.Pricing(0.80, 4.00).label());
    assertEquals("$1.25/$6.50 per 1M", new AnthropicModelPricing.Pricing(1.25, 6.5).label());
  }

  /**
   * <b>표시 연속성</b> — 기대값을 손으로 적지 않고 <b>착수 커밋 고정본의 시드 표시명에서 단가 부분을
   * 실제로 뽑아내</b> {@code label()} 결과와 글자 단위로 비교한다. 손으로 적은 기대값은 옛 문구를
   * 잘못 옮겨도 통과할 수 있다.
   */
  @Test
  void label은_착수커밋_시드표시명의_단가부분과_글자단위로_같다() throws Exception {
    String fixture = readFixture();

    // 고정본 시드 3행에서 "$…/… per 1M" 부분만 추출한다(모델키와 짝지어).
    Pattern seedLine = Pattern.compile(
        "create\\(\"(claude-[^\"]+)\", \"[^\"$]*·\\s*(\\$[^\"]*per 1M)\\)\"");
    Matcher m = seedLine.matcher(fixture);

    List<String> pairs = new ArrayList<>();
    while (m.find()) {
      String modelKey = m.group(1);
      String pricingPart = m.group(2);
      String fromCode = findKnownOrFail(modelKey).label();
      assertEquals(pricingPart, fromCode,
          "고정본 시드 표시명의 단가 부분과 label()이 글자 단위로 같아야 한다. modelKey=" + modelKey);
      pairs.add(modelKey + " -> " + pricingPart);
    }

    System.out.println("[label 표시연속성] " + pairs);
    assertEquals(3, pairs.size(),
        "고정본에서 시드 3행의 단가를 추출해야 한다 — 0건이면 추출 정규식이 죽은 것이므로 위 비교는"
            + " 아무것도 증명하지 못한다. pairs=" + pairs);
  }

  /** 추출 정규식이 실제로 동작함을 보이는 음성 확인 — 단가 없는 새 표시명 형태에서는 0건이어야 한다. */
  @Test
  void 양성대조군_추출정규식은_단가없는_표시명에서는_0건이다() {
    Pattern seedLine = Pattern.compile(
        "create\\(\"(claude-[^\"]+)\", \"[^\"$]*·\\s*(\\$[^\"]*per 1M)\\)\"");
    String withoutPricing = "create(\"claude-sonnet-4-6\", \"Claude Sonnet (권장)\", LlmProvider.ANTHROPIC, 0);";
    assertFalse(seedLine.matcher(withoutPricing).find(),
        "단가가 없는 새 표시명에서는 추출되지 않아야 한다");

    String withPricing =
        "create(\"claude-sonnet-4-6\", \"Claude Sonnet (권장 · $3/$15 per 1M)\", LlmProvider.ANTHROPIC, 0);";
    assertTrue(seedLine.matcher(withPricing).find(), "옛 표시명에서는 추출돼야 한다");
  }

  // ────────────────────────────────────────────────────────────────────────────

  private static AnthropicModelPricing.Pricing findKnownOrFail(String modelKey) {
    return AnthropicModelPricing.findKnown(modelKey)
        .orElseGet(() -> fail("findKnown이 알아야 하는 키인데 empty였다: " + modelKey));
  }

  /**
   * {@link AnthropicModelPricing}가 남긴 WARN만 캡처한다 —
   * 기존 {@code AnthropicModelPricingTest}의 관례(부착 → 실행 → finally 분리)를 그대로 따른다.
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

  private static String readFixture() throws IOException {
    try (InputStream in = AnthropicModelPricingKnownLookupTest.class.getResourceAsStream(SERVICE_FIXTURE)) {
      if (in == null) {
        return fail("TASK-001 고정본이 클래스패스에 없다: " + SERVICE_FIXTURE);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
