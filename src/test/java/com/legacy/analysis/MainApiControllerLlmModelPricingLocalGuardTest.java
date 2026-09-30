package com.legacy.analysis;

import com.legacy.analysis.llm.AnthropicModelPricing;
import com.legacy.analysis.llm.LlmModelOption;
import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.analysis.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TASK-006 작업내용 6 (work-order 2026-09-pricing-source-unification v4, W1) —
 * {@code toPricingResponse()}의 <b>provider 가드</b>를 테스트로 고정한다.
 *
 * <h2>왜 이 파일이 따로 필요한가 (테스트 공백 M4)</h2>
 * <p>TASK-002가 만든 {@code MainApiControllerLlmModelPricingResponseTest}의 LOCAL 케이스는
 * {@code qwen2.5-coder:7b} 하나뿐이다. 그 키는 {@link AnthropicModelPricing#findKnown} 에서 어차피
 * empty이므로, 컨트롤러 헬퍼의 첫 문장
 * {@code if (option.getProvider() != LlmProvider.ANTHROPIC) { return null; }} 을 <b>지워도 결과가 같다</b>
 * — QA의 변이 검증에서 이 가드 제거(M4)가 <b>생존</b>했다. 즉 "provider == LOCAL이면 {@code null}"은
 * 구현돼 있으나 <b>어떤 테스트도 고정하지 못하는</b> 상태였다.
 *
 * <p>그래서 <b>같은 modelKey {@code claude-sonnet-4-6}을 provider만 바꾼 쌍</b>으로 본다. 같은 키에서
 * 결과가 갈리는 유일한 이유가 provider 가드이므로, 가드를 지우면 LOCAL 행에도 단가가 붙어 RED가 된다.
 *
 * <h2>단언 3개</h2>
 * <ol>
 *   <li><b>전제</b> — {@code findKnown("claude-sonnet-4-6")}이 값을 준다. 이 전제가 깨지면(예: 나중에
 *       키가 바뀌어 미지 키가 되면) 이 테스트는 "가드가 없어도 통과"로 조용히 무력화되므로 먼저 단언한다.</li>
 *   <li><b>쌍 대조군</b> — ANTHROPIC 행은 {@code pricing}이 null이 아니고 {@code label}이 정본
 *       {@code of(...).label()}과 같다. 음성 결과(LOCAL은 null)에 붙는 양성 대조군이다.</li>
 *   <li><b>본 단언</b> — LOCAL 행은 {@code pricing} <b>키가 있고 값이 {@code null}</b>(게이트1 D3·F3:
 *       자체 호스팅은 토큰당 과금이 없다. 키 자체를 빼지 않는 것은 프런트가 키 조회로 판정하기 때문).</li>
 * </ol>
 *
 * <p>컨트롤러·엔티티 생성은 {@code MainApiControllerLlmModelPricingResponseTest}(TASK-002 산출)의
 * 관례를 <b>참고만</b> 했다 — 그 파일과 {@code AnthropicModelPricingKnownLookupTest}는 QA Pass 판정의
 * 대상 산출물이므로 <b>수정하지 않는다</b>(G-07 v3 보충). 필요한 헬퍼는 이 파일 안에 뒀다.
 * 프로덕션은 {@code MainApiController.java}를 포함해 <b>0행</b> 변경이다.
 */
class MainApiControllerLlmModelPricingLocalGuardTest {

  /** 가드가 없으면 단가가 붙는 키 — ANTHROPIC/LOCAL 양쪽에 같은 값으로 쓴다. */
  private static final String PAIRED_MODEL_KEY = "claude-sonnet-4-6";

  @Test
  void 같은_modelKey라도_provider가_LOCAL이면_pricing이_null이고_ANTHROPIC이면_값이_붙는다() throws Exception {
    // ⓐ 전제 — 이 키는 "가드가 없으면 단가가 붙는" 키다. 깨지면 이 테스트가 의미를 잃으므로 먼저 본다.
    assertTrue(AnthropicModelPricing.findKnown(PAIRED_MODEL_KEY).isPresent(),
        "전제 붕괴: " + PAIRED_MODEL_KEY + " 가 미지 키가 됐다. 그러면 provider 가드를 지워도 양쪽 다 null이"
            + " 되어 이 테스트가 조용히 무력화된다 — 가드가 없어도 단가가 붙는 다른 키로 바꿔야 한다.");

    // 같은 키, provider만 다른 두 행을 한 번의 목록 조회로 받는다(목 서비스라 modelKey 유일 제약과 무관).
    List<Map<String, Object>> rows = callWith(
        option(PAIRED_MODEL_KEY, "Claude Sonnet (권장)", LlmProvider.ANTHROPIC, 0),
        option(PAIRED_MODEL_KEY, "Claude Sonnet (로컬 사본)", LlmProvider.LOCAL, 1));

    assertEquals(2, rows.size(), "노출순서대로 2행이 와야 한다. rows=" + rows);
    Map<String, Object> anthropicRow = rows.get(0);
    Map<String, Object> localRow = rows.get(1);
    assertEquals("ANTHROPIC", String.valueOf(anthropicRow.get("provider")), "rows=" + rows);
    assertEquals("LOCAL", String.valueOf(localRow.get("provider")), "rows=" + rows);

    // ⓑ 쌍 대조군 — 같은 키인데 ANTHROPIC 쪽은 단가가 붙는다(결과가 갈리는 이유가 provider뿐임을 보인다).
    Map<String, Object> anthropicPricing = pricingOf(anthropicRow);
    assertNotNull(anthropicPricing,
        "ANTHROPIC 행에는 pricing이 있어야 한다 — 이게 null이면 쌍 대조군이 성립하지 않아 LOCAL의 null이"
            + " '가드 때문'인지 '키를 몰라서'인지 구별할 수 없다. row=" + anthropicRow);
    assertEquals(AnthropicModelPricing.of(PAIRED_MODEL_KEY).label(), anthropicPricing.get("label"),
        "ANTHROPIC 행의 label은 코드 정본에서 파생돼야 한다. row=" + anthropicRow);

    // ⓒ 본 단언 — LOCAL 행은 키가 있고 값이 null이다(가드를 지우면 여기서 단가가 붙어 RED).
    assertTrue(localRow.containsKey("pricing"),
        "LOCAL 행에도 pricing 키 자체는 있어야 한다 — 프런트가 키 조회로 판정한다. row=" + localRow);
    assertNull(localRow.get("pricing"),
        "LOCAL은 자체 호스팅이라 토큰당 과금이 없으므로 pricing은 null이어야 한다(D3·F3)."
            + " 같은 키의 ANTHROPIC 행에는 단가가 붙었으므로, 여기에 값이 들어왔다면"
            + " toPricingResponse()의 provider 가드가 사라진 것이다. row=" + localRow);
  }

  // ── 헬퍼 (TASK-002 산출 테스트의 관례를 참고만 했고, 그 파일은 수정하지 않았다) ──

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

    Method method = MainApiController.class.getDeclaredMethod("getLlmModelOptions");
    method.setAccessible(true);
    return (List<Map<String, Object>>) method.invoke(controller);
  }
}
