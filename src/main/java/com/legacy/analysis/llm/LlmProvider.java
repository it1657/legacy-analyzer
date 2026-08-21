package com.legacy.analysis.llm;

/**
 * DB로 관리되는 모델 옵션({@link LlmModelOption})이 실제로 어떤 인프라를 통해 호출되는지 나타내는 값.
 * {@link LlmClientResolver}가 이 값을 기준으로 실제 호출에 쓸 {@link LlmClient} 구현체
 * (AnthropicLlmClient / OpenAiCompatibleLlmClient)를 선택한다.
 *
 * 근거: analyzer-plan docs/chat/etc/2026-08-21-llm-model-db-crud-and-credit-exhaustion-failover-design.md
 */
public enum LlmProvider {
  ANTHROPIC,
  LOCAL
}
