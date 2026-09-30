package com.legacy.analysis.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-003 (work-order 2026-09-pricing-source-unification v1, REQ-002 실DB 검증) —
 * <b>실제 H2 + 실제 저장소</b>로 기동 경로({@link LlmModelOptionSeedInitializer#run(String...)})를
 * 돌려 DB의 {@code display_name}이 실제로 바뀌는지 확인한다.
 *
 * <h2>목 저장소 테스트로 충분하지 않은 이유</h2>
 * <p>{@code LlmModelOptionDisplayNameMigrationTest}는 D5 매칭 규칙을 검증하지만 "저장 호출이
 * 일어났다"까지만 본다. 이 사이클이 고치는 결함은 <b>이미 행이 있는 DB</b>에서 생기므로,
 * ⓐ 테이블이 비어 있지 않아 {@code seedDefaultsIfEmpty()}가 아무것도 넣지 않고
 * ⓑ 그런데도 옛 표시명이 실제로 바뀌는 것을 <b>DB 값으로</b> 봐야 한다. 그래서 SQL 픽스처가
 * Hibernate보다 먼저 기존 5행을 깔고({@code defer-datasource-initialization=false}),
 * 단언은 {@link JdbcTemplate}로 DB를 직접 읽어서 한다(영속성 컨텍스트 캐시를 보지 않는다).
 *
 * <h2>왜 한 메서드에 1·2회차를 다 넣었는가</h2>
 * <p>인메모리 DB와 SQL 픽스처는 <b>컨텍스트당 1회</b>만 초기화되고 이 테스트는
 * {@code @Transactional(NOT_SUPPORTED)}로 각 호출을 실제 커밋한다 — 즉 테스트 메서드가 여럿이면
 * <b>먼저 실행된 메서드가 DB를 바꿔 놓아</b> 뒤 메서드의 기대값이 실행 순서에 의존하게 된다.
 * "2회차 변화 0"은 "1회차에 실제로 2건 바뀐 것"과 같은 흐름 안에서만 의미가 있으므로
 * (전자의 양성 대조군이 후자다) 한 메서드에서 순서대로 관측한다.
 *
 * <p>선례 {@code UserActivityControllerPauseSettledJpaTest}의 설정 방식을 그대로 따른다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("h2")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:llmModelDisplayNameMigrationJpaTest;DB_CLOSE_DELAY=-1;MODE=MySQL",
    "spring.datasource.driverClassName=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=update",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:pricingsinglesource/legacy-llm-model-options.sql",
    // 픽스처에 한글·가운뎃점(U+00B7)이 있다. 이 설정이 없으면 스크립트를 플랫폼 기본 문자셋
    // (개발 PC는 CP949)으로 읽어 표시명이 깨진 채 INSERT되고, 옛 원문 매칭이 실패한다.
    "spring.sql.init.encoding=UTF-8",
    "spring.jpa.show-sql=true"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LlmModelOptionDisplayNameMigrationJpaTest {

  /**
   * 애플리케이션 클래스({@code com.legacy.core})가 이 테스트 패키지의 상위에 없어 기본 탐색이
   * 실패하므로 빈 로컬 설정을 둔다 — 선례와 동일.
   */
  @SpringBootConfiguration
  @AutoConfigurationPackage
  static class TestConfig {
  }

  private static final String LEGACY_SONNET = "Claude Sonnet (권장 · $3/$15 per 1M)";
  private static final String LEGACY_HAIKU = "Claude Haiku (빠름/저비용 · $0.80/$4 per 1M)";
  private static final String ADMIN_EDITED_OPUS = "Claude Opus (최고품질 · $15/$75 per 1M)";
  private static final String LOCAL_ROW = "로컬 모델: qwen2.5-coder:7b (무료 · 자체 호스팅)";
  private static final String NEW_MODEL_ROW = "Claude Sonnet 5";

  private static final String NEW_SONNET = "Claude Sonnet (권장)";
  private static final String NEW_HAIKU = "Claude Haiku (빠름/저비용)";

  @Autowired
  private LlmModelOptionRepository repository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void 실DB_기동_1회차에_옛_원문_2행만_교체되고_2회차는_변화가_없다() {
    LlmModelOptionService service = new LlmModelOptionService(repository);
    LlmModelOptionSeedInitializer initializer = new LlmModelOptionSeedInitializer(service);

    // ── 1회차 전 ──────────────────────────────────────────────────────────
    Map<String, String> before = readDisplayNames();
    System.out.println("[JPA 정리 관측] 1회차 전 = " + before);
    assertEquals(5, before.size(), "픽스처 5행이 깔려 있어야 한다. before=" + before);
    assertEquals(LEGACY_SONNET, before.get("claude-sonnet-4-6"));
    assertEquals(ADMIN_EDITED_OPUS, before.get("claude-opus-4-8"));
    assertEquals(LEGACY_HAIKU, before.get("claude-haiku-4-5-20251001"));
    assertEquals(LOCAL_ROW, before.get("qwen2.5-coder:7b"));
    assertEquals(NEW_MODEL_ROW, before.get("claude-sonnet-5"));

    // ── 1회차 기동 ────────────────────────────────────────────────────────
    Captured first = captureRun(initializer);
    Map<String, String> afterFirst = readDisplayNames();
    System.out.println("[JPA 정리 관측] 1회차 후 = " + afterFirst);
    System.out.println("[JPA 정리 관측] 1회차 WARN = " + first.warnings());
    System.out.println("[JPA 정리 관측] 1회차 교체 로그 = " + first.migrationInfos());

    // 옛 원문 2행은 실제로 DB에서 바뀌었다.
    assertEquals(NEW_SONNET, afterFirst.get("claude-sonnet-4-6"), "sonnet은 교체돼야 한다");
    assertEquals(NEW_HAIKU, afterFirst.get("claude-haiku-4-5-20251001"), "haiku는 교체돼야 한다");
    // 관리자가 고친 행·LOCAL·신모델은 그대로다.
    assertEquals(ADMIN_EDITED_OPUS, afterFirst.get("claude-opus-4-8"),
        "관리자가 고친 행은 단가가 남아 있어도 덮어쓰지 않는다");
    assertEquals(LOCAL_ROW, afterFirst.get("qwen2.5-coder:7b"));
    assertEquals(NEW_MODEL_ROW, afterFirst.get("claude-sonnet-5"));
    // 테이블이 비어 있지 않았으므로 시드는 새 행을 넣지 않았다.
    assertEquals(5, afterFirst.size(), "행 수가 5 그대로여야 한다(시드 미동작). afterFirst=" + afterFirst);

    // 교체 건수 로그는 2건이다.
    assertEquals(List.of("[LLM 모델 표시명 정리] 옛 시드 원문 일치 2건 교체"), first.migrationInfos(),
        "기동 로그의 교체 건수가 2여야 한다");

    // WARN은 관리자가 고친 opus 1건뿐 — LOCAL·신모델·교체된 2행은 단가가 없다.
    assertEquals(1, first.warnings().size(), "WARN 1건(opus)이어야 한다. warnings=" + first.warnings());
    assertTrue(first.warnings().get(0).contains("claude-opus-4-8"), "warn=" + first.warnings().get(0));
    assertTrue(first.warnings().get(0).contains(ADMIN_EDITED_OPUS), "warn=" + first.warnings().get(0));

    // ── 2회차 기동 (멱등) ─────────────────────────────────────────────────
    Captured second = captureRun(initializer);
    Map<String, String> afterSecond = readDisplayNames();
    System.out.println("[JPA 정리 관측] 2회차 후 = " + afterSecond);
    System.out.println("[JPA 정리 관측] 2회차 WARN = " + second.warnings());
    System.out.println("[JPA 정리 관측] 2회차 교체 로그 = " + second.migrationInfos());

    assertEquals(afterFirst, afterSecond, "2회차는 DB 값을 바꾸지 않는다(멱등)");
    assertEquals(List.of("[LLM 모델 표시명 정리] 옛 시드 원문 일치 0건 교체"), second.migrationInfos(),
        "2회차 교체 건수는 0이어야 한다");
    assertEquals(1, second.warnings().size(),
        "opus는 여전히 단가가 남아 있어 계속 알린다(D6 — 저장을 거부하지 않고 기동마다 알림)."
            + " warnings=" + second.warnings());
  }

  // ────────────────────────────────────────────────────────────────────────────

  private record Captured(List<String> warnings, List<String> migrationInfos) {
  }

  /** DB를 직접 읽어 (model_key -> display_name) 맵을 만든다 — 영속성 컨텍스트를 거치지 않는다. */
  private Map<String, String> readDisplayNames() {
    Map<String, String> result = new LinkedHashMap<>();
    jdbcTemplate.query(
        "SELECT model_key, display_name FROM llm_model_options ORDER BY display_order ASC",
        (java.sql.ResultSet rs) -> {
          result.put(rs.getString("model_key"), rs.getString("display_name"));
        });
    return result;
  }

  /** 기동 1회를 돌리며 {@link LlmModelOptionService}의 WARN과 교체 건수 INFO를 함께 캡처한다. */
  private Captured captureRun(LlmModelOptionSeedInitializer initializer) {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LlmModelOptionService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      initializer.run();
    } catch (Exception e) {
      throw new RuntimeException("기동 경로가 예외를 던졌다", e);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
    List<String> warnings = appender.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
    List<String> migrationInfos = appender.list.stream()
        .filter(event -> event.getLevel() == Level.INFO)
        .map(ILoggingEvent::getFormattedMessage)
        .filter(msg -> msg.contains("옛 시드 원문 일치"))
        .toList();
    return new Captured(warnings, migrationInfos);
  }
}
