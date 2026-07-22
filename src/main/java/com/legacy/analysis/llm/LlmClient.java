package com.legacy.analysis.llm;

/**
 * LLM 호출을 provider(Anthropic Claude API / OpenAI 호환 로컬·사내 LLM)와 무관하게
 * 동일한 형태로 추상화한 인터페이스.
 *
 * 재시도(retry)는 이 인터페이스의 책임이 아니다 — 호출부(예: ClaudeServiceImpl)가
 * HTTP 상태 코드/예외를 보고 재시도 여부를 결정하므로, 구현체는 실패 시 예외를 그대로
 * 던지는 단발성(single-shot) 호출만 수행한다.
 */
public interface LlmClient {

    /**
     * @param systemPrompt 시스템 프롬프트
     * @param userContent  사용자 메시지 본문
     * @param model        호출할 모델 식별자
     * @param maxTokens    최대 출력 토큰 수
     * @return 생성된 텍스트와 토큰 사용량을 담은 결과
     */
    LlmResult call(String systemPrompt, String userContent, String model, int maxTokens);
}
