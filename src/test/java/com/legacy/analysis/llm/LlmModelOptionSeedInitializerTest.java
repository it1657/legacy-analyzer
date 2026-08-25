package com.legacy.analysis.llm;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link LlmModelOptionSeedInitializer}가 기동 시 {@link LlmModelOptionService#seedDefaultsIfEmpty()}를
 * 정확히 1회 호출하는지만 검증한다("빈 테이블이면 3개 삽입/이미 있으면 스킵"의 실제 멱등성 로직 자체는
 * {@code LlmModelOptionServiceTest}가 이미 전수 검증하므로 여기서는 중복하지 않는다).
 */
class LlmModelOptionSeedInitializerTest {

  @Test
  void run은_seedDefaultsIfEmpty를_정확히_1회_호출한다() throws Exception {
    LlmModelOptionService service = mock(LlmModelOptionService.class);
    LlmModelOptionSeedInitializer initializer = new LlmModelOptionSeedInitializer(service);

    initializer.run();

    verify(service, times(1)).seedDefaultsIfEmpty();
  }
}
