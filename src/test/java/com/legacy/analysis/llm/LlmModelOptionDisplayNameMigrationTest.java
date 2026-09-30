package com.legacy.analysis.llm;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TASK-003 (work-order 2026-09-pricing-source-unification v1, REQ-001) —
 * {@link LlmModelOptionService#migrateLegacySeedDisplayNames()}의 <b>D5 매칭 규칙 검증 본체</b>.
 *
 * <h2>이 정리가 왜 필요한가</h2>
 * <p>{@link LlmModelOptionService#seedDefaultsIfEmpty()}는 테이블이 <b>완전히 비었을 때만</b> 돈다
 * ({@code count() > 0} 가드). 그래서 이미 운영 중인 DB에는 시드 표시명을 고쳐도 전달되지 않고, 단가가
 * 박힌 옛 표시명이 계속 사용자 화면에 보인다. 이 사이클이 고치는 구조적 결함이 바로 이것이다.
 *
 * <h2>지키는 경계 — "고친다"보다 "관리자 편집을 덮어쓰지 않는다"가 우선</h2>
 * <p>단가 패턴으로 일괄 치환하면 관리자가 의도적으로 넣은 문구까지 말없이 사라진다. 그래서
 * <b>{@code (modelKey, 옛 원문)}이 글자 하나까지 같은 행만</b> 교체하고, 나머지는 WARN만 남긴다.
 * 아래 경계 4종이 그 규칙의 본체다.
 * <ol>
 *   <li><b>완전 일치</b> → 교체</li>
 *   <li><b>관리자가 고친 행</b>(단가는 남았지만 원문과 다름) → 불변 + WARN</li>
 *   <li><b>끝 공백 1개 차이(near-miss)</b> → 교체하지 않음 + WARN. 완화를 한 칸이라도 허용하면
 *       "어디까지 같으면 같은 것인가"가 흐려진다.</li>
 *   <li><b>modelKey 불일치</b>(opus 행에 sonnet 원문) → 교체하지 않음 + WARN</li>
 * </ol>
 *
 * <h2>WARN "0건" 단언의 양성 대조군</h2>
 * <p>LOCAL 행·단가 없는 행에서 "WARN 0건"을 단언하는 케이스들과 <b>같은 캡처 방식</b>을 쓰는
 * "관리자 수정 행 → WARN 1건" 케이스가 곧 대조군이다 — 캡처가 죽어 있으면 그 케이스가 먼저 깨진다.
 */
class LlmModelOptionDisplayNameMigrationTest {

  // 착수 커밋의 옛 시드 원문 3개(고정본과 같은 문자열).
  private static final String LEGACY_SONNET = "Claude Sonnet (권장 · $3/$15 per 1M)";
  private static final String LEGACY_OPUS = "Claude Opus (고품질 · $15/$75 per 1M)";
  private static final String LEGACY_HAIKU = "Claude Haiku (빠름/저비용 · $0.80/$4 per 1M)";

  private static final String NEW_SONNET = "Claude Sonnet (권장)";
  private static final String NEW_OPUS = "Claude Opus (고품질)";
  private static final String NEW_HAIKU = "Claude Haiku (빠름/저비용)";

  private static final String KEY_SONNET = "claude-sonnet-4-6";
  private static final String KEY_OPUS = "claude-opus-4-8";
  private static final String KEY_HAIKU = "claude-haiku-4-5-20251001";

  private LlmModelOptionRepository repository;

  // ── 경계 ① 완전 일치 → 교체 ────────────────────────────────────────────────

  @Test
  void 옛_원문_그대로인_3행은_모두_새_표시명으로_교체된다() {
    LlmModelOptionService service = serviceWith(
        row(1L, KEY_SONNET, LEGACY_SONNET, LlmProvider.ANTHROPIC, true),
        row(2L, KEY_OPUS, LEGACY_OPUS, LlmProvider.ANTHROPIC, true),
        row(3L, KEY_HAIKU, LEGACY_HAIKU, LlmProvider.ANTHROPIC, true));

    int replaced = service.migrateLegacySeedDisplayNames();

    assertEquals(3, replaced, "반환값은 교체 건수다");
    ArgumentCaptor<LlmModelOption> captor = ArgumentCaptor.forClass(LlmModelOption.class);
    verify(repository, times(3)).save(captor.capture());
    List<String> saved = captor.getAllValues().stream().map(LlmModelOption::getDisplayName).toList();
    assertTrue(saved.containsAll(List.of(NEW_SONNET, NEW_OPUS, NEW_HAIKU)),
        "저장된 표시명이 새 표시명 3개여야 한다. saved=" + saved);
    assertTrue(saved.stream().noneMatch(name -> name.contains("$")),
        "새 표시명에는 단가가 없어야 한다. saved=" + saved);
  }

  // ── 경계 ② 관리자가 고친 행 → 불변 + WARN (WARN 캡처 양성 대조군) ───────────

  @Test
  void 단가는_남았지만_원문과_다른_관리자_수정행은_건드리지_않고_WARN만_남긴다() {
    String adminEdited = "Claude Opus (최고품질 · $15/$75 per 1M)";
    LlmModelOptionService service = serviceWith(
        row(2L, KEY_OPUS, adminEdited, LlmProvider.ANTHROPIC, true));

    List<String> warnings = new ArrayList<>();
    int replaced = captureWarnings(warnings, service::migrateLegacySeedDisplayNames);

    assertEquals(0, replaced);
    verify(repository, never()).save(any());
    assertEquals(1, warnings.size(), "단가가 남은 행 1건에 WARN 1건. warnings=" + warnings);
    assertTrue(warnings.get(0).contains(KEY_OPUS), "WARN에 modelKey가 있어야 한다. warn=" + warnings.get(0));
    assertTrue(warnings.get(0).contains(adminEdited),
        "WARN에 현재 표시명이 있어야 한다. warn=" + warnings.get(0));
  }

  // ── 경계 ③ 끝 공백 1개 차이(near-miss) → 교체 0 + WARN 1 ───────────────────

  @Test
  void 옛_원문에_끝_공백_1개만_달라도_교체하지_않고_WARN만_남긴다() {
    LlmModelOptionService service = serviceWith(
        row(1L, KEY_SONNET, LEGACY_SONNET + " ", LlmProvider.ANTHROPIC, true));

    List<String> warnings = new ArrayList<>();
    int replaced = captureWarnings(warnings, service::migrateLegacySeedDisplayNames);

    assertEquals(0, replaced, "trim 등 어떤 완화도 하지 않는다");
    verify(repository, never()).save(any());
    assertEquals(1, warnings.size(), "warnings=" + warnings);
  }

  // ── 경계 ④ modelKey 불일치 → 교체 0 + WARN 1 ──────────────────────────────

  @Test
  void opus_행에_sonnet_옛원문이_들어있으면_교체하지_않고_WARN만_남긴다() {
    LlmModelOptionService service = serviceWith(
        row(2L, KEY_OPUS, LEGACY_SONNET, LlmProvider.ANTHROPIC, true));

    List<String> warnings = new ArrayList<>();
    int replaced = captureWarnings(warnings, service::migrateLegacySeedDisplayNames);

    assertEquals(0, replaced, "매칭 단위는 (modelKey, 옛 원문) 쌍이다");
    verify(repository, never()).save(any());
    assertEquals(1, warnings.size(), "warnings=" + warnings);
    assertTrue(warnings.get(0).contains(KEY_OPUS), "warn=" + warnings.get(0));
  }

  // ── active는 판정 기준이 아니다 ────────────────────────────────────────────

  @Test
  void 비활성_행도_옛_원문이면_교체된다() {
    LlmModelOptionService service = serviceWith(
        row(1L, KEY_SONNET, LEGACY_SONNET, LlmProvider.ANTHROPIC, false));

    int replaced = service.migrateLegacySeedDisplayNames();

    assertEquals(1, replaced, "비활성 행도 관리자가 다시 켜면 사용자 화면에 보인다");
    ArgumentCaptor<LlmModelOption> captor = ArgumentCaptor.forClass(LlmModelOption.class);
    verify(repository, times(1)).save(captor.capture());
    assertEquals(NEW_SONNET, captor.getValue().getDisplayName());
    assertFalse(captor.getValue().isActive(), "active 값 자체는 건드리지 않는다");
  }

  // ── 손대지 않는 행들 (WARN 0건 — 위 ②가 이 캡처의 양성 대조군) ─────────────

  @Test
  void LOCAL_행은_손대지_않고_WARN도_0건이다() {
    LlmModelOptionService service = serviceWith(
        row(9L, "qwen2.5-coder:7b", "로컬 모델: qwen2.5-coder:7b (무료 · 자체 호스팅)", LlmProvider.LOCAL, true));

    List<String> warnings = new ArrayList<>();
    int replaced = captureWarnings(warnings, service::migrateLegacySeedDisplayNames);

    assertEquals(0, replaced);
    verify(repository, never()).save(any());
    assertEquals(List.of(), warnings, "단가가 없으니 경고할 것도 없다");
  }

  @Test
  void 단가없는_ANTHROPIC_신모델_행은_손대지_않고_WARN도_0건이다() {
    LlmModelOptionService service = serviceWith(
        row(8L, "claude-sonnet-5", "Claude Sonnet 5", LlmProvider.ANTHROPIC, true));

    List<String> warnings = new ArrayList<>();
    int replaced = captureWarnings(warnings, service::migrateLegacySeedDisplayNames);

    assertEquals(0, replaced);
    verify(repository, never()).save(any());
    assertEquals(List.of(), warnings, "warnings=" + warnings);
  }

  // ── 멱등 ──────────────────────────────────────────────────────────────────

  @Test
  void 이미_새_표시명인_상태로_2회째_호출하면_교체_0건이다() {
    LlmModelOptionService service = serviceWith(
        row(1L, KEY_SONNET, NEW_SONNET, LlmProvider.ANTHROPIC, true),
        row(2L, KEY_OPUS, NEW_OPUS, LlmProvider.ANTHROPIC, true),
        row(3L, KEY_HAIKU, NEW_HAIKU, LlmProvider.ANTHROPIC, true));

    List<String> warnings = new ArrayList<>();
    int replaced = captureWarnings(warnings, service::migrateLegacySeedDisplayNames);

    assertEquals(0, replaced, "교체된 행은 더 이상 옛 원문과 같지 않다");
    verify(repository, never()).save(any());
    assertEquals(List.of(), warnings, "새 표시명에는 단가가 없어 경고도 없다");
  }

  // ── 교체 건수 로그는 0건이어도 반드시 찍힌다 (TASK-008 grep 기준) ───────────

  @Test
  void 교체_건수_로그는_0건이어도_남는다() {
    LlmModelOptionService service = serviceWith();

    List<String> infos = new ArrayList<>();
    int replaced = captureInfos(infos, service::migrateLegacySeedDisplayNames);

    assertEquals(0, replaced);
    assertTrue(infos.stream().anyMatch(msg -> msg.contains("옛 시드 원문 일치 0건 교체")),
        "TASK-008이 이 로그 줄로 실기동을 관측하므로 0건이어도 찍혀야 한다. infos=" + infos);
  }

  @Test
  void 교체_건수_로그에_실제_건수가_숫자로_들어간다() {
    LlmModelOptionService service = serviceWith(
        row(1L, KEY_SONNET, LEGACY_SONNET, LlmProvider.ANTHROPIC, true),
        row(3L, KEY_HAIKU, LEGACY_HAIKU, LlmProvider.ANTHROPIC, true));

    List<String> infos = new ArrayList<>();
    int replaced = captureInfos(infos, service::migrateLegacySeedDisplayNames);

    assertEquals(2, replaced);
    assertTrue(infos.stream().anyMatch(msg -> msg.contains("옛 시드 원문 일치 2건 교체")),
        "infos=" + infos);
  }

  // ── 시드 자체가 새 표시명을 넣는다 ─────────────────────────────────────────

  @Test
  void seedDefaultsIfEmpty가_저장하는_표시명_3개는_단가가_없는_새_표시명이다() {
    repository = mock(LlmModelOptionRepository.class);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(repository.count()).thenReturn(0L);
    when(repository.existsByModelKey(anyString())).thenReturn(false);
    LlmModelOptionService service = new LlmModelOptionService(repository);

    service.seedDefaultsIfEmpty();

    ArgumentCaptor<LlmModelOption> captor = ArgumentCaptor.forClass(LlmModelOption.class);
    verify(repository, times(3)).save(captor.capture());
    List<LlmModelOption> saved = captor.getAllValues();
    assertEquals(List.of(NEW_SONNET, NEW_OPUS, NEW_HAIKU),
        saved.stream().map(LlmModelOption::getDisplayName).toList());
    assertTrue(saved.stream().noneMatch(o -> o.getDisplayName().contains("$")),
        "표시명에 $가 없어야 한다");
    // modelKey·provider·노출순서는 불변이어야 한다.
    assertEquals(List.of(KEY_SONNET, KEY_OPUS, KEY_HAIKU),
        saved.stream().map(LlmModelOption::getModelKey).toList());
    assertTrue(saved.stream().allMatch(o -> o.getProvider() == LlmProvider.ANTHROPIC));
    assertEquals(List.of(0, 1, 2), saved.stream().map(LlmModelOption::getDisplayOrder).toList());
  }

  // ────────────────────────────────────────────────────────────────────────────

  /** 주어진 행들만 들어 있는 목 저장소로 서비스를 만든다. */
  private LlmModelOptionService serviceWith(LlmModelOption... rows) {
    repository = mock(LlmModelOptionRepository.class);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    List<LlmModelOption> all = Arrays.asList(rows);
    when(repository.findAllByOrderByDisplayOrderAsc()).thenReturn(all);
    when(repository.findByModelKey(anyString())).thenAnswer(inv -> {
      String key = inv.getArgument(0);
      return all.stream().filter(o -> o.getModelKey().equals(key)).findFirst();
    });
    return new LlmModelOptionService(repository);
  }

  private static LlmModelOption row(Long id, String modelKey, String displayName, LlmProvider provider,
      boolean active) {
    LlmModelOption option = new LlmModelOption(modelKey, displayName, provider, 0);
    option.setId(id);
    option.setActive(active);
    return option;
  }

  private int captureWarnings(List<String> sink, java.util.function.Supplier<Integer> action) {
    return capture(sink, ch.qos.logback.classic.Level.WARN, action);
  }

  private int captureInfos(List<String> sink, java.util.function.Supplier<Integer> action) {
    return capture(sink, ch.qos.logback.classic.Level.INFO, action);
  }

  /**
   * {@link LlmModelOptionService}가 남긴 특정 레벨 로그만 캡처한다
   * (기존 {@code AnthropicModelPricingTest}/{@code DataInitializerTest}의 ListAppender 관례).
   */
  private int capture(List<String> sink, ch.qos.logback.classic.Level level,
      java.util.function.Supplier<Integer> action) {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LlmModelOptionService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    int result;
    try {
      result = action.get();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
    appender.list.stream()
        .filter(event -> event.getLevel() == level)
        .map(ILoggingEvent::getFormattedMessage)
        .forEach(sink::add);
    return result;
  }
}
