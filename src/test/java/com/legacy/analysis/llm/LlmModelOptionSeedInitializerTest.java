package com.legacy.analysis.llm;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

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

  @Test
  void run은_seedLocalFromEnvIfConfigured도_1회_호출한다() throws Exception {
    // REQ-002(2026-09): .env의 LLM_LOCAL_MODEL을 DB에 자동 시드하는 호출부가 기동 경로에
    // 실제로 연결돼 있는지 검증한다(멱등성 로직 자체는 LlmModelOptionServiceTest가 다룬다).
    // @Value 필드는 순수 단위 테스트에서 주입되지 않으므로 리플렉션으로 직접 설정한다.
    LlmModelOptionService service = mock(LlmModelOptionService.class);
    LlmModelOptionSeedInitializer initializer = new LlmModelOptionSeedInitializer(service);
    setField(initializer, "llmLocalModel", "qwen2.5-coder:7b");

    initializer.run();

    verify(service, times(1)).seedLocalFromEnvIfConfigured("qwen2.5-coder:7b");
  }

  private void setField(Object target, String name, Object value) throws Exception {
    Field field = LlmModelOptionSeedInitializer.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
