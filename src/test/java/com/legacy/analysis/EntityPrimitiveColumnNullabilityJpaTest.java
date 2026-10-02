package com.legacy.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-007 (work-order 2026-10-resume-consistency-and-local-guard v1 + v2 보강, REQ-001 ① / C11) —
 * <b>엔티티 primitive 필드 12개의 실제 컬럼이 NOT NULL인지</b>를 Hibernate가 만든 H2 스키마로 관측한다.
 *
 * <p>primitive 필드는 Hibernate가 {@code nullable = false}로 DDL을 만든다. 그래서 <b>테이블이 처음
 * 만들어질 때부터 있던</b> primitive 컬럼은 NOT NULL이고 옛 행에 NULL이 있을 수 없다 — 이 테스트는
 * 그 전제를 실제 스키마로 확인한다. 하나라도 {@code YES}(nullable)면 "컬럼이 나중에 추가됐을 가능성"이
 * 있다는 신호이므로 단언을 쓰기 전에 멈추고 PL에 보고해야 한다(work-order TASK-007 작업 3).
 *
 * <p><b>대조</b>: 같은 쿼리로 {@code analysis_sessions.generate_readme}(wrapper {@code Boolean},
 * {@code nullable} 미지정)가 {@code YES}임을 함께 관측한다 — 쿼리가 무엇이든 {@code NO}를 돌려주는
 * 것이 아님을 보인다.
 *
 * <p><b>C11 겹 1 (v2 보강)</b>: TASK-011에서 운영 DB에 <b>1회만</b> 돌릴 D9 COUNT 12문을
 * {@code 05-dev-progress.md}에 적는 원문과 <b>문자 그대로 같은 문자열로</b> 이 H2 스키마에 미리 실행해
 * SQL 오류가 0임을 확인한다. PostgreSQL은 읽기 전용 트랜잭션 안에서 문장 하나가 오류 나면 그 뒤
 * 문장을 전부 거부하므로, 컬럼명 오타 1건이 12개 결과 전체를 날릴 수 있다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("h2")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:entityPrimitiveNullabilityJpaTest;DB_CLOSE_DELAY=-1;MODE=MySQL",
    "spring.datasource.driverClassName=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create",
    "spring.jpa.show-sql=true"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EntityPrimitiveColumnNullabilityJpaTest {

  /**
   * 애플리케이션 클래스({@code LegacyAnalyzerApplication})를 쓰지 않고 빈 설정을 두되,
   * 엔티티는 {@code com.legacy} 전체를 스캔한다 — 조사 대상 엔티티가 여러 패키지에 흩어져 있다.
   */
  @SpringBootConfiguration
  @AutoConfigurationPackage
  @EntityScan("com.legacy")
  static class TestConfig {
  }

  /**
   * <b>NOT NULL로 관측된 primitive 컬럼 5개</b> — {@code @Column(..., nullable = false)}를 명시했거나
   * ({@code llm_model_options} 3개) {@code @Column}을 아예 쓰지 않아 Hibernate가 primitive에서
   * NOT NULL을 추론한 경우({@code total_files}·{@code processed_files})다. 옛 행에 NULL이 있을 수 없다.
   */
  private static final List<String[]> NOT_NULL_PRIMITIVE_COLUMNS = List.of(
      new String[] {"llm_model_options", "display_order", "LlmModelOption.displayOrder"},
      new String[] {"llm_model_options", "active", "LlmModelOption.active"},
      new String[] {"llm_model_options", "is_failover_target", "LlmModelOption.failoverTarget"},
      new String[] {"analysis_sessions", "total_files", "SessionState.totalFiles"},
      new String[] {"analysis_sessions", "processed_files", "SessionState.processedFiles"});

  /**
   * <b>알려진 잠재 조합 7개</b> — primitive 필드지만 {@code @Column(name=…)}에 {@code nullable}이 없어
   * 컬럼이 NULL을 허용한다(JPA 기본값 {@code nullable = true}가 primitive의 NOT NULL 추론을 덮는다).
   *
   * <p>12건 모두 <b>테이블 생성 커밋에서 도입</b>(나중 추가 0), <b>컬럼명 변경 0</b>,
   * <b>native 쓰기 0</b>, JPA는 primitive라 NULL을 쓸 수 없음 → <b>앱 경로상 NULL 불가</b>다.
   * 남은 위험은 저장소 밖 쓰기(수동 SQL·외부 도구)뿐이며, 운영 실측은
   * {@code 2026-10-resume-consistency-and-local-guard} TASK-011 **D9** 조회가 담당한다.
   *
   * <p><b>이 목록에 새 항목을 늘리지 마라</b> — 새 primitive 필드는 {@code nullable = false}를 명시하거나
   * wrapper + NULL 해석 규칙을 쓴다(가드 테스트 실패 메시지의 ⓐ·ⓑ 두 갈래 참조).
   */
  private static final List<String[]> KNOWN_NULLABLE_PRIMITIVE_COLUMNS = List.of(
      new String[] {"analysis_sessions", "is_cancelled", "SessionState.isCancelled"},
      new String[] {"api_usage", "request_size", "ApiUsage.requestSize"},
      new String[] {"api_usage", "response_size", "ApiUsage.responseSize"},
      new String[] {"api_usage", "status_code", "ApiUsage.statusCode"},
      new String[] {"api_usage", "execution_time_ms", "ApiUsage.executionTimeMs"},
      new String[] {"users", "is_active", "User.isActive"},
      new String[] {"notifications", "is_read", "Notification.isRead"});

  /**
   * 조사 대상 12개 {@code {테이블}.{컬럼}} — 위 두 목록에서 파생한다(문장이 두 벌로 갈라지지 않게).
   * 순서는 D9 스크립트와 같다: NOT NULL 5개 → 잠재 조합 7개.
   */
  private static final List<String[]> PRIMITIVE_COLUMNS = concat(
      NOT_NULL_PRIMITIVE_COLUMNS, KNOWN_NULLABLE_PRIMITIVE_COLUMNS);

  private static List<String[]> concat(List<String[]> a, List<String[]> b) {
    List<String[]> out = new ArrayList<>(a);
    out.addAll(b);
    return List.copyOf(out);
  }

  private static String key(String[] c) {
    return c[0] + "." + c[1];
  }

  /**
   * <b>D9 COUNT 12문 — 이 문자열이 정본이다.</b> {@code 05-dev-progress.md}의 D9 스크립트와
   * {@code TASK-011}에서 운영 DB에 실행할 문장이 이 목록과 문자 그대로 같아야 한다.
   * 테이블명·컬럼명은 따옴표 없이 소문자(PostgreSQL 기본 정규화와 {@code information_schema} 값의
   * 대소문자를 일치시키기 위함).
   *
   * <p>순서는 <b>앞 5문 = NOT NULL 컬럼(대조, 0 예측) / 뒤 7문 = 잠재 조합(nullable — 실측 대상)</b>이다.
   * 두 목록에서 파생하므로 그 구분이 자동으로 유지된다.
   */
  static final List<String> D9_COUNT_STATEMENTS = buildD9CountStatements();

  /** D9 사전 확인 SELECT — 12쌍의 존재와 {@code is_nullable}을 함께 확인한다(2026-10 v5 보강). */
  static final String D9_PRECHECK_SELECT = buildD9PrecheckSelect();

  private static List<String> buildD9CountStatements() {
    List<String> out = new ArrayList<>();
    for (String[] c : PRIMITIVE_COLUMNS) {
      out.add("SELECT COUNT(*) FROM " + c[0] + " WHERE " + c[1] + " IS NULL;");
    }
    return List.copyOf(out);
  }

  /**
   * 사전 확인 SELECT를 같은 12쌍에서 만든다 — 스크립트와 테스트가 같은 출처를 쓰게 한다.
   * {@code is_nullable} 열을 함께 돌려주므로 운영 스키마가 H2 관측(5 NO / 7 YES)과 같은지 대조할 수 있다.
   */
  private static String buildD9PrecheckSelect() {
    StringBuilder sb = new StringBuilder();
    sb.append("SELECT table_name, column_name, is_nullable\n")
        .append("  FROM information_schema.columns\n")
        .append(" WHERE table_schema = current_schema()\n")
        .append("   AND (table_name, column_name) IN (\n");
    for (int i = 0; i < PRIMITIVE_COLUMNS.size(); i++) {
      String[] c = PRIMITIVE_COLUMNS.get(i);
      sb.append("         ('").append(c[0]).append("','").append(c[1]).append("')")
          .append(i == PRIMITIVE_COLUMNS.size() - 1 ? ")" : ",").append('\n');
    }
    sb.append(" ORDER BY table_name, column_name;");
    return sb.toString();
  }

  @Autowired JdbcTemplate jdbcTemplate;

  private String isNullable(String table, String column) {
    List<String> rows = jdbcTemplate.queryForList(
        "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS"
            + " WHERE UPPER(TABLE_NAME) = UPPER(?) AND UPPER(COLUMN_NAME) = UPPER(?)",
        String.class, table, column);
    return rows.isEmpty() ? null : rows.get(0);
  }

  // ────────────────────────────────────────────────────────────────────────────

  /**
   * 12개 컬럼의 {@code IS_NULLABLE}을 관측·기록하고, <b>겹치지 않는 두 목록으로 값을 고정</b>한다
   * (2026-10 PL 판정 3). NOT NULL 5개는 {@code NO}, 알려진 잠재 조합 7개는 {@code YES}다.
   *
   * <p><b>실패 메시지를 방향별로 다르게</b> 쓴다 — 어느 쪽이 깨졌는지가 해야 할 일을 정하기 때문이다.
   * {@code NO} → {@code YES}는 회귀(막아야 함)이고, {@code YES} → {@code NO}는 바람직한 변화
   * (목록을 옮기면 됨)다.
   */
  @Test
  void primitive_12개_컬럼의_IS_NULLABLE이_두_목록과_정확히_일치한다() {
    Map<String, String> observed = new LinkedHashMap<>();
    List<String> missing = new ArrayList<>();

    for (String[] c : PRIMITIVE_COLUMNS) {
      String nullable = isNullable(c[0], c[1]);
      observed.put(key(c) + " (" + c[2] + ")", String.valueOf(nullable));
      if (nullable == null) missing.add(key(c));
    }
    // 대조 1건 — wrapper 필드(generate_readme)는 nullable이어야 한다.
    String wrapperNullable = isNullable("analysis_sessions", "generate_readme");
    observed.put("analysis_sessions.generate_readme (wrapper 대조)", String.valueOf(wrapperNullable));

    System.out.println("[007 IS_NULLABLE 관측 13행]");
    observed.forEach((k, v) -> System.out.println("  " + k + " = " + v));
    System.out.println("[007 IS_NULLABLE] 두 목록 고정: NOT NULL " + NOT_NULL_PRIMITIVE_COLUMNS.size()
        + "개 = NO / 알려진 잠재 조합 " + KNOWN_NULLABLE_PRIMITIVE_COLUMNS.size() + "개 = YES"
        + " (합집합 " + PRIMITIVE_COLUMNS.size() + ")");

    // D9 조회의 전제: 테이블·컬럼명이 실제로 존재해야 한다(이름 추정 오류를 먼저 가른다).
    assertTrue(missing.isEmpty(),
        "INFORMATION_SCHEMA에서 찾지 못한 컬럼이 있다(테이블·컬럼명 추정이 틀렸다): " + missing);

    // ⓐ NOT NULL 목록 5개 — NO여야 한다. 깨지면 "NOT NULL이 빠졌다"(회귀).
    for (String[] c : NOT_NULL_PRIMITIVE_COLUMNS) {
      assertEquals("NO", isNullable(c[0], c[1]),
          key(c) + " (" + c[2] + "): primitive 컬럼의 NOT NULL이 빠졌다 —"
              + " `@Column`에 `nullable = false`를 되돌리거나 wrapper로 바꿔라."
              + " (이 컬럼을 잠재 조합으로 받아들이려면 KNOWN_NULLABLE_PRIMITIVE_COLUMNS로 옮기되,"
              + " 그건 보호가 약해지는 변경이므로 PL 판단을 받아라.)");
    }

    // ⓑ 잠재 조합 목록 7개 — YES여야 한다. 깨지면 "NOT NULL이 됐다"(바람직한 변화).
    for (String[] c : KNOWN_NULLABLE_PRIMITIVE_COLUMNS) {
      assertEquals("YES", isNullable(c[0], c[1]),
          key(c) + " (" + c[2] + "): 이 컬럼이 NOT NULL이 됐다 —"
              + " 잠재 조합 목록(KNOWN_NULLABLE_PRIMITIVE_COLUMNS)에서"
              + " NOT_NULL_PRIMITIVE_COLUMNS로 옮겨라(바람직한 변화다).");
    }

    // ⓒ 두 목록의 합집합 = 12, 교집합 = 0.
    List<String> notNullKeys = NOT_NULL_PRIMITIVE_COLUMNS.stream().map(
        EntityPrimitiveColumnNullabilityJpaTest::key).toList();
    List<String> nullableKeys = KNOWN_NULLABLE_PRIMITIVE_COLUMNS.stream().map(
        EntityPrimitiveColumnNullabilityJpaTest::key).toList();
    java.util.Set<String> union = new java.util.LinkedHashSet<>(notNullKeys);
    union.addAll(nullableKeys);
    java.util.Set<String> intersection = new java.util.LinkedHashSet<>(notNullKeys);
    intersection.retainAll(nullableKeys);
    System.out.println("[007 IS_NULLABLE] 합집합=" + union.size() + ", 교집합=" + intersection);
    assertEquals(12, union.size(),
        "두 목록의 합집합이 12개여야 한다(조사 대상 primitive 필드 수와 같아야 한다). 합집합=" + union);
    assertTrue(intersection.isEmpty(),
        "두 목록이 겹치면 같은 컬럼에 NO와 YES를 동시에 단언하게 된다: " + intersection);
    assertEquals(12, PRIMITIVE_COLUMNS.size(), "파생 목록 크기가 12가 아니다");

    // ⓓ 기존 대조군 2건 — 이 쿼리가 무엇이든 같은 값을 돌려주는 것이 아님을 보인다.
    assertNotNull(wrapperNullable, "대조 컬럼 generate_readme를 찾지 못했다(스키마가 바뀌었다)");
    assertEquals("YES", wrapperNullable,
        "대조군 실패 — wrapper 컬럼이 NOT NULL이면 이 쿼리의 결과를 믿을 수 없다");
    assertEquals("NO", isNullable("llm_model_options", "display_order"),
        "대조군 실패 — nullable=false를 명시한 컬럼이 NO가 아니면 이 쿼리의 결과를 믿을 수 없다");
  }

  /**
   * C11 겹 1 — D9 COUNT 12문을 <b>기록과 같은 원문</b>으로 H2에 실행해 SQL 오류 0을 확인한다.
   * 값 자체(빈 스키마라 전부 0)가 아니라 "오류 없이 실행됨"이 목적이다.
   */
  @Test
  void D9_COUNT_12문을_원문_그대로_H2에_실행해_오류가_0건이다() {
    assertEquals(12, D9_COUNT_STATEMENTS.size(), "D9 COUNT 문장이 12개여야 한다");

    System.out.println("[007 D9 사전 확인 SELECT — 05-dev-progress.md에 적는 원문과 동일]");
    System.out.println(D9_PRECHECK_SELECT);
    System.out.println("[007 D9 COUNT 12문 — 05-dev-progress.md에 적는 원문과 동일]");
    System.out.println("  -- 앞 5문 = NOT NULL 컬럼(대조, 0 예측) / 뒤 7문 = 잠재 조합(nullable — 실측 대상)");
    List<String> results = new ArrayList<>();
    for (String sql : D9_COUNT_STATEMENTS) {
      System.out.println("  " + sql);
      Integer count = assertDoesNotThrow(
          () -> jdbcTemplate.queryForObject(sql, Integer.class),
          "D9 COUNT 문장이 SQL 오류를 냈다(운영 DB 1회 조회에서 이 오류가 나면 뒤 문장이 전부 무효가 된다): "
              + sql);
      assertNotNull(count, "COUNT가 null을 돌려줬다: " + sql);
      results.add(sql + "  →  " + count);
    }

    System.out.println("[007 D9 COUNT 12문 H2 실행 결과 12행]");
    results.forEach(r -> System.out.println("  " + r));

    assertEquals(12, results.size(), "12문 모두 숫자를 돌려줘야 한다");
    for (String r : results) {
      assertTrue(r.endsWith("0"),
          "빈 스키마이므로 전부 0이 예측된다(값이 아니라 오류 0이 목적이지만, 0이 아니면 픽스처가"
              + " 끼어든 것이므로 확인이 필요하다): " + r);
    }
  }

  /**
   * 대조군 — 같은 실행 방식으로 <b>일부러 틀린 컬럼명</b>을 쓰면 실제로 SQL 오류가 난다.
   * 위 "오류 0건"이 "이 실행 경로가 오류를 삼켜서" 나온 결과가 아님을 보인다.
   */
  @Test
  void 대조군_틀린_컬럼명을_쓰면_같은_실행_방식이_SQL_오류를_낸다() {
    String wrong = "SELECT COUNT(*) FROM analysis_sessions WHERE current_phase IS NULL;";
    System.out.println("[007 D9 대조군] 일부러 틀린 문장: " + wrong);

    Exception thrown = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
        () -> jdbcTemplate.queryForObject(wrong, Integer.class),
        "틀린 컬럼명인데 오류가 나지 않았다 — 그러면 '오류 0건'이 아무것도 보증하지 않는다");
    System.out.println("[007 D9 대조군] 실제 예외: " + thrown.getClass().getSimpleName()
        + " / " + String.valueOf(thrown.getMessage()).split("\n")[0]);

    // current_phase를 고른 이유: SessionState.currentPhase가 @Transient라 이 테이블에 컬럼이 없다(C11).
    assertTrue(String.valueOf(thrown.getMessage()).toUpperCase().contains("CURRENT_PHASE")
            || thrown.getClass().getSimpleName().contains("BadSqlGrammar"),
        "예상한 '컬럼 없음' 오류가 아니다: " + thrown);
  }

  /** C11 — {@code @Transient} 필드명이 D9 대상 목록에 섞여 있지 않은지 문자열로 확인한다. */
  @Test
  void D9_대상_목록에_Transient_필드명이_없다() {
    List<String> transientFieldColumns = List.of(
        "current_phase", "error_log", "statistics", "processed_files_list", "recovery_queue",
        "session_summary", "log_entries", "metadata", "patched_file_paths", "failed_file_paths",
        "preview_cache", "recent_logs", "file_failure_reason_counts");

    List<String> leaked = new ArrayList<>();
    for (String sql : D9_COUNT_STATEMENTS) {
      for (String t : transientFieldColumns) {
        if (sql.contains(" " + t + " ")) leaked.add(t + " in [" + sql + "]");
      }
    }
    System.out.println("[007 C11] D9 문장에 섞인 @Transient 컬럼명 = " + leaked);
    assertTrue(leaked.isEmpty(), "@Transient 필드명이 D9 대상에 섞였다(그 컬럼은 DB에 없다): " + leaked);

    // 양성 대조군 — 같은 검사 함수에 일부러 섞은 문장을 넣으면 검출된다.
    List<String> poisoned = new ArrayList<>(D9_COUNT_STATEMENTS);
    poisoned.add("SELECT COUNT(*) FROM analysis_sessions WHERE current_phase IS NULL;");
    List<String> detected = new ArrayList<>();
    for (String sql : poisoned) {
      for (String t : transientFieldColumns) {
        if (sql.contains(" " + t + " ")) detected.add(t);
      }
    }
    assertEquals(List.of("current_phase"), detected,
        "검사 함수가 섞인 @Transient 컬럼명을 검출하지 못한다");
  }
}
