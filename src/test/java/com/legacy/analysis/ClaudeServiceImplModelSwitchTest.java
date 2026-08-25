package com.legacy.analysis;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * handOff.md "구현 진행 상황 (2차)"에서 완료로 기록된 getCurrentModel()의 local 모드 분기
 * (llm.provider=local이면 Anthropic 모델명 대신 llm.local.model을 반환)를 검증한다.
 * 이 분기는 llmClient.call()에 넘어갈 모델명을 결정하므로, local 모드에서 실수로
 * "claude-sonnet-4-6" 같은 이름이 자체 LLM 서버로 전송되는 회귀를 막는 것이 목적이다.
 *
 * ClaudeServiceImpl 생성자 의존성은 이 메서드가 전혀 쓰지 않으므로 전부 null로 넘기고,
 * @Value로 주입되는 llmProvider/llmLocalModel/apiModel 필드만 리플렉션으로 직접 설정한다.
 *
 * 2026-08-20 긴급수정: 과거 싱글턴 필드(modelOverride) 기반 setModel()/getCurrentModel()이
 * sourceFolderPath(세션 식별 키) 단위로 격리되도록 시그니처가 바뀌었다. 아래
 * "서로_다른_세션끼리_모델_오버라이드가_섞이지_않는다" 테스트는 그 레이스 컨디션 버그의
 * 회귀 테스트다 — 이 수정 전이었다면 실패했을 케이스.
 * (참고: analyzer-plan docs/chat/etc/2026-08-20-user-selectable-provider-analysis-and-setmodel-race-bug.md)
 */
class ClaudeServiceImplModelSwitchTest {

  private ClaudeServiceImpl newService(String llmProvider, String llmLocalModel, String apiModel) throws Exception {
    ClaudeServiceImpl service = new ClaudeServiceImpl(null, null, null, null, null, null);
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

    assertEquals("qwen3-32b", service.getCurrentModel("/session/a"));
  }

  @Test
  void anthropic_모드에서는_기존과_동일하게_apiModel을_반환한다() throws Exception {
    ClaudeServiceImpl service = newService("anthropic", "qwen3-32b", "claude-sonnet-5");

    assertEquals("claude-sonnet-5", service.getCurrentModel("/session/a"));
  }

  @Test
  void llmProvider_미설정시_기본값_anthropic으로_apiModel을_반환한다() throws Exception {
    ClaudeServiceImpl service = newService(null, "qwen3-32b", "claude-sonnet-5");

    assertEquals("claude-sonnet-5", service.getCurrentModel("/session/a"));
  }

  @Test
  void anthropic_모드에서_setModel로_override하면_local_설정과_무관하게_override값을_반환한다() throws Exception {
    ClaudeServiceImpl service = newService("anthropic", "qwen3-32b", "claude-sonnet-5");

    service.setModel("/session/a", "claude-opus-4-8");

    assertEquals("claude-opus-4-8", service.getCurrentModel("/session/a"));
  }

  @Test
  void sourceFolderPath가_null이면_오버라이드_여부와_무관하게_기본_apiModel을_반환한다() throws Exception {
    // /api/config/llm-provider처럼 특정 세션에 종속되지 않은 조회를 위한 안전장치.
    // ConcurrentHashMap은 null 키 조회 시 예외를 던지므로, null 가드가 없으면 이 호출 자체가 깨진다.
    ClaudeServiceImpl service = newService("anthropic", "qwen3-32b", "claude-sonnet-5");
    service.setModel("/session/a", "claude-opus-4-8");

    assertEquals("claude-sonnet-5", service.getCurrentModel(null));
  }

  @Test
  void 서로_다른_세션끼리_모델_오버라이드가_섞이지_않는다() throws Exception {
    // 2026-08-20 레이스 컨디션 버그 회귀 테스트: 수정 전에는 modelOverride가 인스턴스 전역
    // 단일 필드여서, 세션 A가 모델을 바꾸면 동시에 진행 중인 세션 B도 그 모델로 바뀌어 버렸다.
    // 같은 ClaudeServiceImpl 인스턴스에서 서로 다른 sourceFolderPath로 setModel을 호출했을 때
    // 각 세션이 자신이 지정한 모델만 유지해야 한다.
    ClaudeServiceImpl service = newService("anthropic", "qwen3-32b", "claude-sonnet-5");
    String sessionA = "/source/project-a";
    String sessionB = "/source/project-b";

    // 세션 A가 먼저 opus로 변경
    service.setModel(sessionA, "claude-opus-4-8");
    assertEquals("claude-opus-4-8", service.getCurrentModel(sessionA));
    // 세션 B는 아직 아무 것도 설정하지 않았으므로 기본값을 유지해야 함
    assertEquals("claude-sonnet-5", service.getCurrentModel(sessionB));

    // 세션 B가 haiku로 변경해도 세션 A의 opus 설정에는 영향이 없어야 함(레이스 컨디션 수정 핵심)
    service.setModel(sessionB, "claude-haiku-4-5-20251001");
    assertEquals("claude-opus-4-8", service.getCurrentModel(sessionA));
    assertEquals("claude-haiku-4-5-20251001", service.getCurrentModel(sessionB));
    assertNotEquals(service.getCurrentModel(sessionA), service.getCurrentModel(sessionB));
  }

  @Test
  void 세션_종료_시_clearSessionSystemPrompt를_호출하면_모델_오버라이드도_함께_정리된다() throws Exception {
    // 메모리 누수 방지 검증: sessionSystemPrompts와 동일한 세션 종료 정리 지점에서
    // sessionModelOverrides도 함께 제거되는지 확인한다.
    ClaudeServiceImpl service = newService("anthropic", "qwen3-32b", "claude-sonnet-5");
    String session = "/source/project-c";
    service.setModel(session, "claude-opus-4-8");
    assertEquals("claude-opus-4-8", service.getCurrentModel(session));

    service.clearSessionSystemPrompt(session);

    assertEquals("claude-sonnet-5", service.getCurrentModel(session), "세션 종료 후에는 오버라이드가 제거되고 기본값으로 돌아가야 함");
  }
}
