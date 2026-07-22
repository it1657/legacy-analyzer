package com.legacy.analysis;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * handOff.md "다음 단계" 3번(local provider일 때 비용 0 처리)·4번(GET /api/config/llm-provider)
 * 항목이 이미 코드에 구현돼 있었으나 테스트가 없어 이를 검증한다.
 *
 * MainApiController는 생성자 의존성이 11개라 계정/인증/알림 등 실제 빈을 전부 준비하기보다는,
 * 이 두 기능이 실제로 쓰는 claudeService 외 나머지는 null로 넘기고(둘 다 다른 의존성을 건드리지
 * 않음) private 메서드/필드는 리플렉션으로 접근한다 — 이 저장소의 기존 테스트
 * (PresentationGeneratorScreenFlowTest)와 동일한 리플렉션 접근 패턴을 따른다.
 */
class MainApiControllerLlmProviderTest {

  private static class FakeClaudeService implements ClaudeService {
    private String model;

    FakeClaudeService(String model) {
      this.model = model;
    }

    @Override
    public String analyzeCodeWithClaude(String sourceCode, String fileName, String sourceFolderPath) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TokenUsage getTotalTokenUsage() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void resetTokenUsage() {
    }

    @Override
    public String getCurrentModel() {
      return model;
    }

    @Override
    public void setModel(String model) {
      this.model = model;
    }

    @Override
    public String generateSessionClaudeMd(String customRequirements) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setSessionSystemPrompt(String sourceFolderPath, String claudeMdContent) {
    }

    @Override
    public void clearSessionSystemPrompt(String sourceFolderPath) {
    }
  }

  private MainApiController newController(ClaudeService claudeService, String llmProvider) throws Exception {
    MainApiController controller = new MainApiController(
        claudeService, null, null, null, null, null, null, null, null, null, null);
    Field field = MainApiController.class.getDeclaredField("llmProvider");
    field.setAccessible(true);
    field.set(controller, llmProvider);
    return controller;
  }

  private double calculateEstimatedCost(MainApiController controller, long inputTokens, long outputTokens,
      String modelName) throws Exception {
    Method m = MainApiController.class.getDeclaredMethod(
        "calculateEstimatedCost", long.class, long.class, String.class);
    m.setAccessible(true);
    return (double) m.invoke(controller, inputTokens, outputTokens, modelName);
  }

  @SuppressWarnings("unchecked")
  private java.util.Map<String, Object> getLlmProviderConfig(MainApiController controller) throws Exception {
    Method m = MainApiController.class.getDeclaredMethod("getLlmProviderConfig");
    m.setAccessible(true);
    return (java.util.Map<String, Object>) m.invoke(controller);
  }

  @Test
  void local_provider일때_비용은_토큰수와_무관하게_0이다() throws Exception {
    MainApiController controller = newController(new FakeClaudeService("qwen3-32b"), "local");

    double cost = calculateEstimatedCost(controller, 1_000_000, 1_000_000, "qwen3-32b");

    assertEquals(0.0, cost, "자체 호스팅 LLM은 토큰당 과금이 없으므로 항상 0이어야 함");
  }

  @Test
  void anthropic_provider일때는_기존과_동일하게_모델별_단가로_계산된다() throws Exception {
    MainApiController controller = newController(new FakeClaudeService("claude-sonnet-5"), "anthropic");

    double cost = calculateEstimatedCost(controller, 1_000_000, 1_000_000, "claude-sonnet-5");

    assertEquals(3.00 + 15.00, cost, 0.0001, "sonnet 단가(입력 $3/출력 $15, 1M 토큰 기준)로 계산돼야 함");
  }

  @Test
  void llmProvider_미설정시_기본값_anthropic으로_비용이_계산된다() throws Exception {
    MainApiController controller = newController(new FakeClaudeService("claude-haiku-4-5-20251001"), null);

    double cost = calculateEstimatedCost(controller, 1_000_000, 1_000_000, "claude-haiku-4-5-20251001");

    assertEquals(0.80 + 4.00, cost, 0.0001, "llm.provider 미설정 시 matchIfMissing과 동일하게 anthropic 기본값이어야 함");
  }

  @Test
  void llm_provider_조회_엔드포인트는_local_모드에서_provider와_현재_모델을_반환한다() throws Exception {
    MainApiController controller = newController(new FakeClaudeService("qwen3-32b"), "local");

    java.util.Map<String, Object> result = getLlmProviderConfig(controller);

    assertEquals("local", result.get("provider"));
    assertEquals("qwen3-32b", result.get("model"));
  }

  @Test
  void llm_provider_조회_엔드포인트는_anthropic_모드에서_provider와_현재_모델을_반환한다() throws Exception {
    MainApiController controller = newController(new FakeClaudeService("claude-sonnet-5"), "anthropic");

    java.util.Map<String, Object> result = getLlmProviderConfig(controller);

    assertEquals("anthropic", result.get("provider"));
    assertEquals("claude-sonnet-5", result.get("model"));
  }
}
