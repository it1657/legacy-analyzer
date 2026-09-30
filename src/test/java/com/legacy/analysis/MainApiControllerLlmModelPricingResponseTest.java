package com.legacy.analysis;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legacy.analysis.llm.AnthropicModelPricing;
import com.legacy.analysis.llm.LlmModelOption;
import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.analysis.llm.LlmProvider;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TASK-002 (work-order 2026-09-pricing-source-unification v1, REQ-002 파생 계약) —
 * {@code GET /api/config/llm-models} 응답의 <b>{@code pricing} 필드가 코드 정본에서 파생됨</b>을 고정한다.
 *
 * <h2>무엇을 고정하는가</h2>
 * <ol>
 *   <li><b>파생 계약</b> — {@code pricing}의 두 숫자와 {@code label}이
 *       {@link AnthropicModelPricing}의 값과 <b>같다</b>. 기대값을 손으로 적지 않고 정본에서 가져와
 *       비교하므로, 응답이 정본과 어긋나면 단가 값을 바꿨는지와 무관하게 깨진다.</li>
 *   <li><b>단가를 모르면 {@code null}</b>(게이트1 D3) — LOCAL(비과금)과 단가를 모르는 Claude 모델
 *       모두. 추정치를 확정 단가처럼 보여주지 않고, "단가 미등록" 같은 문구도 넣지 않는다.</li>
 *   <li><b>기존 4개 키 불변</b>(RG-3) — {@code pricing} 추가가 기존 계약을 건드리지 않는다.</li>
 *   <li><b>목록 조회는 WARN을 남기지 않는다</b>(RG-2) — 이 엔드포인트가 {@code of()}를 부르면
 *       LOCAL·미지 키마다 경고가 찍히고 비용 계산 경로의 억제 집합이 오염된다.</li>
 * </ol>
 *
 * <p>컨트롤러 생성은 기존 {@code MainApiControllerLlmProviderTest}의 관례(목
 * {@link LlmModelOptionService}, 나머지 협력자 {@code null}, 4-인자
 * {@link LlmModelOption} 생성자)를 <b>참고만</b> 했고 그 파일은 수정하지 않았다.
 */
class MainApiControllerLlmModelPricingResponseTest {

  // ── 파생 계약: pricing 값이 정본과 같다 ────────────────────────────────────

  @Test
  void ANTHROPIC_시드_3종의_pricing은_코드정본_값과_같다() throws Exception {
    List<String> seedKeys =
        List.of("claude-sonnet-4-6", "claude-opus-4-8", "claude-haiku-4-5-20251001");
    List<Map<String, Object>> result = callWith(
        option(seedKeys.get(0), "Claude Sonnet (권장)", LlmProvider.ANTHROPIC, 0),
        option(seedKeys.get(1), "Claude Opus (고품질)", LlmProvider.ANTHROPIC, 1),
        option(seedKeys.get(2), "Claude Haiku (빠름/저비용)", LlmProvider.ANTHROPIC, 2));

    assertEquals(3, result.size());
    for (int i = 0; i < seedKeys.size(); i++) {
      String key = seedKeys.get(i);
      AnthropicModelPricing.Pricing canonical = AnthropicModelPricing.of(key);
      Map<String, Object> pricing = pricingOf(result.get(i));

      assertNotNull(pricing, "시드 3종은 단가를 알아야 한다. key=" + key);
      assertEquals(canonical.inputPerMillionTokens(), pricing.get("inputPerMillionTokens"),
          "입력 단가가 정본과 달라졌다. key=" + key);
      assertEquals(canonical.outputPerMillionTokens(), pricing.get("outputPerMillionTokens"),
          "출력 단가가 정본과 달라졌다. key=" + key);
      assertEquals(canonical.label(), pricing.get("label"),
          "label이 정본 label()과 달라졌다. key=" + key);
    }
  }

  /** 시드에 없는 세대도 패밀리 매칭으로 단가를 받는다 — 관리자가 신모델을 등록해도 표시가 따라온다. */
  @Test
  void 시드에_없는_claude_sonnet_5도_sonnet_단가를_받는다() throws Exception {
    List<Map<String, Object>> result =
        callWith(option("claude-sonnet-5", "Claude Sonnet 5", LlmProvider.ANTHROPIC, 0));

    Map<String, Object> pricing = pricingOf(result.get(0));
    assertNotNull(pricing);
    assertEquals(AnthropicModelPricing.of("claude-sonnet-5").label(), pricing.get("label"));
    assertEquals(3.00, pricing.get("inputPerMillionTokens"));
    assertEquals(15.00, pricing.get("outputPerMillionTokens"));
  }

  // ── 단가를 모르면 null (D3) ────────────────────────────────────────────────

  /** LOCAL은 자체 호스팅이라 토큰당 과금이 없다 — 비용 계산의 0.0과 같은 취지(F3). */
  @Test
  void LOCAL_모델의_pricing은_키는_있고_값은_null이다() throws Exception {
    List<Map<String, Object>> result =
        callWith(option("qwen2.5-coder:7b", "로컬 모델 qwen2.5-coder:7b", LlmProvider.LOCAL, 0));

    Map<String, Object> row = result.get(0);
    assertTrue(row.containsKey("pricing"), "pricing 키 자체는 있어야 한다(프런트가 존재를 가정)");
    assertNull(row.get("pricing"), "LOCAL은 비과금이라 단가를 붙이지 않는다");
  }

  /**
   * 단가를 모르는 ANTHROPIC 모델은 <b>추정치를 붙이지 않는다</b>(D3). {@code of()}를 부르면 최고 단가가
   * 확정 단가처럼 표시되므로, 이 단언이 곧 "{@code of()}를 부르지 않았다"는 증거다.
   */
  @Test
  void 단가를_모르는_ANTHROPIC_모델의_pricing은_null이다() throws Exception {
    List<Map<String, Object>> result =
        callWith(option("claude-future-99", "Claude Future", LlmProvider.ANTHROPIC, 0));

    assertNull(result.get(0).get("pricing"),
        "단가를 모르면 추정치(최고 단가)를 붙이지 않고 null이어야 한다");
  }

  // ── RG-3: 기존 4개 키 불변 ────────────────────────────────────────────────

  @Test
  void 기존_4개_키는_입력_엔티티_값_그대로다() throws Exception {
    List<Map<String, Object>> result =
        callWith(option("claude-sonnet-4-6", "Claude Sonnet (권장)", LlmProvider.ANTHROPIC, 7));

    Map<String, Object> row = result.get(0);
    assertEquals("claude-sonnet-4-6", row.get("modelKey"));
    assertEquals("Claude Sonnet (권장)", row.get("displayName"));
    assertEquals("ANTHROPIC", row.get("provider"));
    assertEquals(7, row.get("displayOrder"));
    assertEquals(5, row.size(), "기존 4개 + pricing = 5개여야 한다(키가 더 늘면 계약 변경). row=" + row);
  }

  /** {@code displayName}은 이스케이프 없이 원문 그대로 — 프런트가 {@code textContent}로 넣는다(RG-4). */
  @Test
  void displayName은_이스케이프_없이_원문_그대로_반환된다() throws Exception {
    String raw = "<img src=x onerror=alert(1)>";
    List<Map<String, Object>> result =
        callWith(option("claude-sonnet-4-6", raw, LlmProvider.ANTHROPIC, 0));

    assertEquals(raw, result.get(0).get("displayName"));
  }

  // ── RG-2: 목록 조회는 WARN 0건 (+ 양성 대조군) ─────────────────────────────

  /**
   * 목록에 LOCAL·미지 ANTHROPIC 키가 섞여 있어도 경고가 0건이다. <b>같은 캡처</b>로 이어서
   * {@code of(고유 미지 키)}를 부르면 1건이 잡혀 캡처가 살아 있음을 증명한다 — 이 대조군이 없으면
   * "0건"은 "캡처가 죽어 있었다"와 구별되지 않는다.
   */
  @Test
  void 목록_조회는_단가_WARN을_남기지_않는다_같은_캡처로_of는_1건을_남긴다() throws Exception {
    String probeKey = "claude-unknown-pricingresponse-probe";

    List<String> warnings = captureAnthropicPricingWarnings(() -> {
      try {
        callWith(
            option("claude-sonnet-4-6", "Claude Sonnet (권장)", LlmProvider.ANTHROPIC, 0),
            option("claude-future-99", "Claude Future", LlmProvider.ANTHROPIC, 1),
            option("qwen2.5-coder:7b", "로컬 모델 qwen2.5-coder:7b", LlmProvider.LOCAL, 2));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      // 양성 대조군 — 같은 캡처로 계산용 조회를 하면 경고가 잡힌다.
      AnthropicModelPricing.of(probeKey);
    });

    assertEquals(1, warnings.size(),
        "목록 조회는 WARN 0건이고, 뒤이은 of(고유 미지 키) 1건만 잡혀야 한다. warnings=" + warnings);
    assertTrue(warnings.get(0).contains(probeKey),
        "그 1건은 대조군 of가 남긴 것이어야 한다. warnings=" + warnings);
  }

  // ── JSON 직렬화 실측 (G-05, §0.2 C6) ──────────────────────────────────────

  /**
   * <b>JSON 모양을 코드 인용이 아니라 실행 결과로 확인한다</b>(G-05). {@code pricing}이 {@code null}인
   * 행에서 키가 <b>빠지지 않고 값이 null로</b> 실려 나가는지가 프런트 계약의 관심사다(§0.2 C6).
   * 직렬화한 JSON 전문은 {@code 05-dev-progress.md}에 붙여넣었고, TASK-004 하네스의 입력 모양이 된다.
   */
  @Test
  void JSON_직렬화에서_pricing이_null이면_키는_남고_값만_null이다() throws Exception {
    List<Map<String, Object>> result = callWith(
        option("claude-sonnet-4-6", "Claude Sonnet (권장)", LlmProvider.ANTHROPIC, 0),
        option("claude-future-99", "Claude Future", LlmProvider.ANTHROPIC, 1),
        option("qwen2.5-coder:7b", "로컬 모델 qwen2.5-coder:7b", LlmProvider.LOCAL, 2));

    String json = new ObjectMapper().writeValueAsString(result);
    System.out.println("[pricing JSON 실측] " + json);

    assertTrue(json.contains("\"pricing\":null"),
        "단가를 모르는 행/LOCAL 행은 \"pricing\":null 로 직렬화돼야 한다. json=" + json);
    assertTrue(json.contains("\"label\":\"$3/$15 per 1M\""),
        "시드 sonnet 행은 label을 실어 보내야 한다. json=" + json);
    assertEquals(2, countOccurrences(json, "\"pricing\":null"),
        "null인 행은 미지 ANTHROPIC 1건 + LOCAL 1건 = 2건이다. json=" + json);
    assertFalse(json.contains("\"displayName\":\"Claude Sonnet (권장 ·"),
        "displayName에는 단가가 없어야 한다. json=" + json);
  }

  // ────────────────────────────────────────────────────────────────────────────

  private static LlmModelOption option(String modelKey, String displayName, LlmProvider provider,
      int displayOrder) {
    return new LlmModelOption(modelKey, displayName, provider, displayOrder);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> pricingOf(Map<String, Object> row) {
    return (Map<String, Object>) row.get("pricing");
  }

  /** 목 서비스가 주어진 활성 목록을 주도록 세운 컨트롤러로 목록 API를 호출한다. */
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> callWith(LlmModelOption... options) throws Exception {
    LlmModelOptionService service = mock(LlmModelOptionService.class);
    when(service.listActive()).thenReturn(List.of(options));

    MainApiController controller = new MainApiController(
        null, null, null, null, null, null, null, null, null, null, null, null, null,
        service, null);

    Method m = MainApiController.class.getDeclaredMethod("getLlmModelOptions");
    m.setAccessible(true);
    return (List<Map<String, Object>>) m.invoke(controller);
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int idx = haystack.indexOf(needle);
    while (idx >= 0) {
      count++;
      idx = haystack.indexOf(needle, idx + needle.length());
    }
    return count;
  }

  /** {@link AnthropicModelPricing}가 남긴 WARN만 캡처한다(기존 테스트들의 ListAppender 관례). */
  private static List<String> captureAnthropicPricingWarnings(Runnable action) {
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
