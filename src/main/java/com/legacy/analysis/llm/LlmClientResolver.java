package com.legacy.analysis.llm;

import org.springframework.stereotype.Component;

/**
 * {@link LlmModelOption#getProvider()}(ANTHROPIC/LOCAL) 값을 기준으로 실제 호출에 쓸
 * {@link LlmClient} 구현체를 런타임에 선택한다.
 *
 * scenario_0.md 단계에서는 {@code @ConditionalOnProperty}로 두 구현체 중 하나만 빈으로
 * 등록해 애초에 "선택"의 여지가 없었다(서버 전역 스위치, {@code llm.provider}). 이번 변경
 * (모델 목록 DB화 + 크레딧소진 failover)은 같은 서버 안에서 세션별로 Anthropic/로컬 모델을
 * 동시에 골라 써야 하므로, 두 빈을 항상 등록해두고(AnthropicLlmClient/OpenAiCompatibleLlmClient의
 * {@code @ConditionalOnProperty} 제거) 이 Resolver가 매 호출마다 provider에 맞는 구현체를 고른다.
 *
 * 근거: analyzer-plan docs/chat/etc/2026-08-21-llm-model-db-crud-and-credit-exhaustion-failover-design.md
 */
@Component
public class LlmClientResolver {

  // 필드 타입을 구현체가 아닌 LlmClient 인터페이스로 둔 이유: 테스트에서 가짜(fake) LlmClient를
  // 그대로 주입해 실제 WebClient/HTTP 없이 검증할 수 있게 하기 위함이다. Spring이 두 빈(둘 다
  // LlmClient 구현체) 중 어느 것을 각 생성자 파라미터에 넣을지는 파라미터명↔빈 이름(클래스명 첫
  // 글자 소문자, 기본 규칙) 일치로 자동 판별한다 — @Qualifier 없이도 모호하지 않다.
  private final LlmClient anthropicLlmClient;
  private final LlmClient openAiCompatibleLlmClient;

  public LlmClientResolver(LlmClient anthropicLlmClient, LlmClient openAiCompatibleLlmClient) {
    this.anthropicLlmClient = anthropicLlmClient;
    this.openAiCompatibleLlmClient = openAiCompatibleLlmClient;
  }

  /** provider에 맞는 LlmClient 구현체를 반환한다. null이거나 인식할 수 없는 값이면 안전하게 ANTHROPIC(기본값)으로 처리한다. */
  public LlmClient resolve(LlmProvider provider) {
    if (provider == LlmProvider.LOCAL) {
      return openAiCompatibleLlmClient;
    }
    return anthropicLlmClient;
  }
}
