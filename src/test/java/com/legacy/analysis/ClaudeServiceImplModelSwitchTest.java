package com.legacy.analysis;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * handOff.md "구현 진행 상황 (2차)"에서 완료로 기록된 getCurrentModel()의 local 모드 분기
 * (llm.provider=local이면 Anthropic 모델명 대신 llm.local.model을 반환)를 검증한다.
 * 이 분기는 llmClient.call()에 넘어갈 모델명을 결정하므로, local 모드에서 실수로
 * "claude-sonnet-4-6" 같은 이름이 자체 LLM 서버로 전송되는 회귀를 막는 것이 목적이다.
 *
 * ClaudeServiceImpl 생성자 의존성은 이 메서드가 전혀 쓰지 않으므로 전부 null로 넘기고,
 * @Value로 주입되는 llmProvider/llmLocalModel/apiModel/modelOverride 필드만 리플렉션으로
 * 직접 설정한다.
 */
class ClaudeServiceImplModelSwitchTest {

  private ClaudeServiceImpl newService(String llmProvider, String llmLocalModel, String apiModel) throws Exception {
    ClaudeServiceImpl service = new ClaudeServiceImpl(null, null, null, null, null);
    setField(service, "llmProvider", llmProvider);
    setField(service, "llmLocalModel", llmLocalModel);
    setField(service, "apiModel", apiModel);
    return service;
  }

  private void setField(Object target, String name, Object value) throws Exception {
    Field field = ClaudeServiceImpl.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  @Test
  void local_모드에서는_apiModel_대신_llmLocalModel을_반환한다() throws Exception {
    ClaudeServiceImpl service = newService("local", "qwen3-32b", "claude-sonnet-5");

    assertEquals("qwen3-32b", service.getCurrentModel());
  }

  @Test
  void anthropic_모드에서는_기존과_동일하게_apiModel을_반환한다() throws Exception {
    ClaudeServiceImpl service = newService("anthropic", "qwen3-32b", "claude-sonnet-5");

    assertEquals("claude-sonnet-5", service.getCurrentModel());
  }

  @Test
  void llmProvider_미설정시_기본값_anthropic으로_apiModel을_반환한다() throws Exception {
    ClaudeServiceImpl service = newService(null, "qwen3-32b", "claude-sonnet-5");

    assertEquals("claude-sonnet-5", service.getCurrentModel());
  }

  @Test
  void anthropic_모드에서_setModel로_override하면_local_설정과_무관하게_override값을_반환한다() throws Exception {
    ClaudeServiceImpl service = newService("anthropic", "qwen3-32b", "claude-sonnet-5");

    service.setModel("claude-opus-4-8");

    assertEquals("claude-opus-4-8", service.getCurrentModel());
  }
}
