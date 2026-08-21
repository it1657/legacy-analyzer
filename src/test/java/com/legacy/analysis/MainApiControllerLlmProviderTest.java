package com.legacy.analysis;

import com.legacy.analysis.llm.LlmModelOption;
import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.analysis.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * handOff.md "다음 단계" 3번(local provider일 때 비용 0 처리)·4번(GET /api/config/llm-provider)
 * 항목이 이미 코드에 구현돼 있었으나 테스트가 없어 이를 검증한다. Phase 3(2026-08-21)부터는
 * GET /api/config/llm-models(사용자 드롭다운용 활성 모델 조회) 검증도 이 클래스에서 함께 다룬다.
 *
 * MainApiController는 생성자 의존성이 12개라 계정/인증/알림 등 실제 빈을 전부 준비하기보다는,
 * 이 두 기능이 실제로 쓰는 claudeService(+ llmModelOptionService) 외 나머지는 null로 넘기고(다른
 * 의존성을 건드리지 않음) private 메서드/필드는 리플렉션으로 접근한다 — 이 저장소의 기존 테스트
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
    public String getCurrentModel(String sourceFolderPath) {
      return model;
    }

    @Override
    public void setModel(String sourceFolderPath, String model) {
      this.model = model;
    }

    @Override
    public String generateSessionClaudeMd(String customRequirements, java.util.Set<String> extensions, String sourceFolderPath) {
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
    return newController(claudeService, llmProvider, null);
  }

  private MainApiController newController(ClaudeService claudeService, String llmProvider,
      LlmModelOptionService llmModelOptionService) throws Exception {
    MainApiController controller = new MainApiController(
        claudeService, null, null, null, null, null, null, null, null, null, null, null,
        llmModelOptionService);
    Field field = MainApiController.class.getDeclaredField("llmProvider");
    field.setAccessible(true);
    field.set(controller, llmProvider);
    return controller;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> getLlmModelOptions(MainApiController controller) throws Exception {
    Method m = MainApiController.class.getDeclaredMethod("getLlmModelOptions");
    m.setAccessible(true);
    return (List<Map<String, Object>>) m.invoke(controller);
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

  @Test
  void llm_provider_조회_엔드포인트는_containerized_여부를_함께_반환한다() throws Exception {
    // 관리자 전용 "서버 경로 직접 지정" UI를 컨테이너 환경에서 숨기기 위한 플래그.
    // 테스트가 도는 이 환경(Docker 컨테이너 아님)에서는 /.dockerenv가 없으므로 false여야 한다.
    MainApiController controller = newController(new FakeClaudeService("qwen3-32b"), "local");

    java.util.Map<String, Object> result = getLlmProviderConfig(controller);

    assertEquals(false, result.get("containerized"));
  }

  @Test
  void isRunningInContainer는_dockerenv_파일이_없으면_false를_반환한다() throws Exception {
    MainApiController controller = newController(new FakeClaudeService("qwen3-32b"), "local");
    Method m = MainApiController.class.getDeclaredMethod("isRunningInContainer");
    m.setAccessible(true);

    boolean result = (boolean) m.invoke(controller);

    assertEquals(false, result, "/.dockerenv가 없는 일반 환경(로컬/CI)에서는 false여야 함");
  }

  @Test
  void llm_모델_조회_엔드포인트는_활성_모델만_노출순서대로_반환한다() throws Exception {
    LlmModelOptionService llmModelOptionService = mock(LlmModelOptionService.class);
    LlmModelOption sonnet = new LlmModelOption("claude-sonnet-4-6", "Claude Sonnet", LlmProvider.ANTHROPIC, 0);
    LlmModelOption haiku = new LlmModelOption("claude-haiku-4-5-20251001", "Claude Haiku", LlmProvider.ANTHROPIC, 1);
    // listActive()는 Repository가 이미 displayOrder 오름차순으로 정렬해 반환하는 계약이므로
    // Mock에서도 그 순서 그대로 리스트를 준다(정렬 로직 자체는 이 컨트롤러 책임이 아님).
    when(llmModelOptionService.listActive()).thenReturn(List.of(sonnet, haiku));
    MainApiController controller = newController(new FakeClaudeService("claude-sonnet-4-6"), "anthropic",
        llmModelOptionService);

    List<Map<String, Object>> result = getLlmModelOptions(controller);

    assertEquals(2, result.size());
    assertEquals("claude-sonnet-4-6", result.get(0).get("modelKey"));
    assertEquals("Claude Sonnet", result.get(0).get("displayName"));
    assertEquals("ANTHROPIC", result.get(0).get("provider"));
    assertEquals("claude-haiku-4-5-20251001", result.get(1).get("modelKey"));
  }

  @Test
  void llm_모델_조회_엔드포인트는_비활성_모델이_섞여있어도_서비스가_거른_활성_목록만_그대로_전달한다() throws Exception {
    // listActive() 자체가 활성 모델만 걸러 반환하는 계약이므로(LlmModelOptionService 책임),
    // 여기서는 컨트롤러가 서비스 반환값을 그대로 변환만 하는지 확인한다.
    LlmModelOptionService llmModelOptionService = mock(LlmModelOptionService.class);
    when(llmModelOptionService.listActive()).thenReturn(List.of());
    MainApiController controller = newController(new FakeClaudeService("claude-sonnet-4-6"), "anthropic",
        llmModelOptionService);

    List<Map<String, Object>> result = getLlmModelOptions(controller);

    assertEquals(0, result.size());
  }
}
