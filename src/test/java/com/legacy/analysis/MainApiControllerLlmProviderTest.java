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
        claudeService, null, null, null, null, null, null, null, null, null, null, null, null,
        llmModelOptionService);
    Field field = MainApiController.class.getDeclaredField("llmProvider");
    field.setAccessible(true);
    field.set(controller, llmProvider);
    return controller;
  }

  /** availableProviders 계산에만 쓰이는 @Value 필드를 리플렉션으로 세팅한다(REQ-001, 2026-09). */
  private void setAnthropicApiKey(MainApiController controller, String apiKey) throws Exception {
    Field field = MainApiController.class.getDeclaredField("anthropicApiKey");
    field.setAccessible(true);
    field.set(controller, apiKey);
  }

  @SuppressWarnings("unchecked")
  private List<String> getAvailableProviders(MainApiController controller) throws Exception {
    return (List<String>) getLlmProviderConfig(controller).get("availableProviders");
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
  void anthropic_모드에서_DB상_LOCAL_모델을_선택했으면_비용이_0이다() throws Exception {
    // REQ-002(2026-09): 레이어 A 바이패스 제거로 anthropic 모드에서도 DB에 등록된 LOCAL 모델을
    // 세션이 실제로 쓸 수 있게 됐다. 이 분기가 없으면 로컬 모델명이 "opus"/"sonnet" 어디에도
    // 걸리지 않아 haiku 단가($0.80/$4)로 잘못 과금된다.
    LlmModelOptionService llmModelOptionService = mock(LlmModelOptionService.class);
    LlmModelOption localModel = new LlmModelOption("qwen3-32b", "Qwen3 32B", LlmProvider.LOCAL, 0);
    when(llmModelOptionService.findByModelKey("qwen3-32b")).thenReturn(java.util.Optional.of(localModel));
    MainApiController controller = newController(new FakeClaudeService("qwen3-32b"), "anthropic",
        llmModelOptionService);

    double cost = calculateEstimatedCost(controller, 1_000_000, 1_000_000, "qwen3-32b");

    assertEquals(0.0, cost, "DB상 LOCAL provider 모델은 anthropic 모드에서도 과금 대상이 아니어야 함");
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
  void availableProviders는_anthropic_키만_설정돼있으면_anthropic_하나뿐이다() throws Exception {
    LlmModelOptionService llmModelOptionService = mock(LlmModelOptionService.class);
    when(llmModelOptionService.hasActiveLocalModel()).thenReturn(false);
    MainApiController controller = newController(new FakeClaudeService("claude-sonnet-5"), "anthropic",
        llmModelOptionService);
    setAnthropicApiKey(controller, "sk-ant-real-key");

    assertEquals(List.of("anthropic"), getAvailableProviders(controller));
  }

  @Test
  void availableProviders는_anthropic_키가_없고_활성_LOCAL_모델이_있으면_local_하나뿐이다() throws Exception {
    // scenario_1(경량 배포판) 회귀 가드 — .env.lite.example은 CLAUDE_API_KEY가 빈 값이고
    // LLM_LOCAL_MODEL이 TASK-002의 env 자동 시드로 DB에 LOCAL 1건으로 들어간다.
    // 이때 availableProviders가 정확히 ["local"] 하나여야 프런트가 토글을 숨기고
    // 기존 "선택 여지 없음" UX를 그대로 재현한다(02-design-v2 §2.3).
    LlmModelOptionService llmModelOptionService = mock(LlmModelOptionService.class);
    when(llmModelOptionService.hasActiveLocalModel()).thenReturn(true);
    MainApiController controller = newController(new FakeClaudeService("qwen2.5-coder:7b"), "local",
        llmModelOptionService);
    setAnthropicApiKey(controller, "");

    assertEquals(List.of("local"), getAvailableProviders(controller));
  }

  @Test
  void availableProviders는_둘_다_설정돼있으면_anthropic_local_순서로_둘_다_포함한다() throws Exception {
    LlmModelOptionService llmModelOptionService = mock(LlmModelOptionService.class);
    when(llmModelOptionService.hasActiveLocalModel()).thenReturn(true);
    MainApiController controller = newController(new FakeClaudeService("claude-sonnet-5"), "anthropic",
        llmModelOptionService);
    setAnthropicApiKey(controller, "sk-ant-real-key");

    assertEquals(List.of("anthropic", "local"), getAvailableProviders(controller),
        "프런트가 이 순서 그대로 토글 버튼을 렌더링하므로 순서가 고정돼야 함");
  }

  @Test
  void availableProviders는_MOCK_키를_설정되지_않은_것으로_취급한다() throws Exception {
    // ClaudeServiceImpl의 기존 API 키 가드와 동일 기준 — MOCK 접두사는 실제 호출이 불가능한
    // 개발용 더미 값이므로 anthropic을 선택지로 제공하면 안 된다.
    LlmModelOptionService llmModelOptionService = mock(LlmModelOptionService.class);
    when(llmModelOptionService.hasActiveLocalModel()).thenReturn(true);
    MainApiController controller = newController(new FakeClaudeService("qwen3-32b"), "local",
        llmModelOptionService);
    setAnthropicApiKey(controller, "MOCK_API_KEY");

    assertEquals(List.of("local"), getAvailableProviders(controller));
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

  @Test
  void llm_모델_조회_엔드포인트는_displayName을_이스케이프_없이_원문_그대로_반환한다() throws Exception {
    // 저장형 XSS 버그 수정(2026-08-21, bug-suspects.md)의 회귀 방지 테스트.
    // 서버(API)는 displayName을 가공/이스케이프하지 않고 원문 그대로 전달하는 것이 이 프로젝트의 기존
    // 관례(UserController.updateProfile 등의 displayName 필드도 동일하게 trim만 하고 별도 sanitize 없음)와
    // 일치한다 — 실제 XSS 방어는 dashboard.js의 populateModelSelectOptions()가 innerHTML 대신
    // textContent로 DOM에 삽입하는 프런트엔드 계층에서 담당한다(브라우저 자동 이스케이프).
    // 즉 이 테스트는 "서버가 악의적 문자열을 왜곡 없이 그대로 반환하는지"만 확인하고, DOM 삽입
    // 안전성 자체는 코드 리뷰로 갈음한다(이 프로젝트에 JS 단위테스트 프레임워크가 없음).
    LlmModelOptionService llmModelOptionService = mock(LlmModelOptionService.class);
    String maliciousDisplayName = "<script>alert('xss')</script>";
    LlmModelOption malicious = new LlmModelOption("claude-sonnet-4-6", maliciousDisplayName, LlmProvider.ANTHROPIC, 0);
    when(llmModelOptionService.listActive()).thenReturn(List.of(malicious));
    MainApiController controller = newController(new FakeClaudeService("claude-sonnet-4-6"), "anthropic",
        llmModelOptionService);

    List<Map<String, Object>> result = getLlmModelOptions(controller);

    assertEquals(maliciousDisplayName, result.get(0).get("displayName"),
        "서버는 sanitize 없이 원문을 그대로 반환해야 하며, XSS 방어 책임은 프런트엔드(textContent)에 있다");
  }
}
