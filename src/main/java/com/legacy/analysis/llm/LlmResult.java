package com.legacy.analysis.llm;

/**
 * {@link LlmClient#call} 호출 결과.
 *
 * OpenAI 호환 백엔드는 프롬프트 캐싱을 지원하지 않으므로
 * {@code cacheReadTokens}/{@code cacheCreationTokens}는 항상 0이다.
 */
public record LlmResult(String text, long inputTokens, long outputTokens,
                         long cacheReadTokens, long cacheCreationTokens) {}
