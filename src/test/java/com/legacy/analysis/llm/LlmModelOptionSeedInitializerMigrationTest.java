package com.legacy.analysis.llm;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TASK-003 (work-order 2026-09-pricing-source-unification v1, REQ-001) —
 * 기동 경로에 {@link LlmModelOptionService#migrateLegacySeedDisplayNames()}가 <b>올바른 자리에</b>
 * 연결됐고, <b>실패해도 기동을 막지 않는지</b>를 고정한다.
 *
 * <h2>왜 순서가 계약인가</h2>
 * <p>{@code seedDefaultsIfEmpty()} <b>직후</b>여야 한다 — 빈 DB에 방금 새 표시명으로 시드한 행을
 * 정리 대상으로 훑는 것은 무해하지만(0건), 순서가 뒤바뀌어 정리가 먼저 돌면 시드 전 빈 테이블을
 * 훑어 아무 일도 하지 않는다. 그리고 {@code seedLocalFromEnvIfConfigured()} <b>전</b>이어야
 * LOCAL 행이 정리 대상 스캔에 끼어들지 않는다.
 *
 * <h2>왜 예외를 삼키는가 (그리고 왜 여기만인가)</h2>
 * <p>표시명 정리는 <b>표시 개선</b>일 뿐이라 실패해도 애플리케이션이 뜨지 못할 이유가 없다. 반면
 * 기존 두 호출({@code seedDefaultsIfEmpty}/{@code seedLocalFromEnvIfConfigured})의 <b>예외 전파는
 * 종전 그대로</b>다 — 시드가 실패하면 드롭다운이 비어 분석 자체를 시작할 수 없으므로 조용히 넘기면
 * 안 된다. 이 비대칭이 의도한 것임을 테스트로 고정한다.
 */
class LlmModelOptionSeedInitializerMigrationTest {

  @Test
  void run은_시드_정리_로컬시드_순서로_각_1회_호출한다() throws Exception {
    LlmModelOptionService service = mock(LlmModelOptionService.class);
    LlmModelOptionSeedInitializer initializer = new LlmModelOptionSeedInitializer(service);

    initializer.run();

    InOrder order = inOrder(service);
    order.verify(service, times(1)).seedDefaultsIfEmpty();
    order.verify(service, times(1)).migrateLegacySeedDisplayNames();
    order.verify(service, times(1)).seedLocalFromEnvIfConfigured(any());
    order.verifyNoMoreInteractions();
  }

  @Test
  void 정리가_RuntimeException을_던져도_기동은_계속되고_로컬시드는_여전히_호출된다() throws Exception {
    LlmModelOptionService service = mock(LlmModelOptionService.class);
    when(service.migrateLegacySeedDisplayNames())
        .thenThrow(new IllegalStateException("정리 중 실패(테스트)"));
    LlmModelOptionSeedInitializer initializer = new LlmModelOptionSeedInitializer(service);

    List<String> errors = captureErrors(() -> {
      try {
        initializer.run();
      } catch (Exception e) {
        throw new AssertionError("run()이 예외를 전파했다 — 표시명 정리 실패는 기동을 막지 않아야 한다", e);
      }
    });

    verify(service, times(1)).seedLocalFromEnvIfConfigured(any());
    assertEquals(1, errors.size(), "log.error 1건이 남아야 한다(조용히 삼키지 않는다). errors=" + errors);
    assertTrue(errors.get(0).contains("표시명 정리"), "errors=" + errors);
  }

  /** 기존 동작 불변 — 시드 실패는 종전처럼 전파된다(정리와 달리 삼키지 않는다). */
  @Test
  void seedDefaultsIfEmpty가_예외를_던지면_기존처럼_전파된다() {
    LlmModelOptionService service = mock(LlmModelOptionService.class);
    doThrow(new IllegalStateException("시드 실패(테스트)")).when(service).seedDefaultsIfEmpty();
    LlmModelOptionSeedInitializer initializer = new LlmModelOptionSeedInitializer(service);

    assertThrows(IllegalStateException.class, initializer::run);

    verify(service, times(0)).migrateLegacySeedDisplayNames();
    verify(service, times(0)).seedLocalFromEnvIfConfigured(any());
  }

  /** {@link LlmModelOptionSeedInitializer}가 남긴 ERROR 로그만 캡처한다. */
  private List<String> captureErrors(Runnable action) {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LlmModelOptionSeedInitializer.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      action.run();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
    return appender.list.stream()
        .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }
}
