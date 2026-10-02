package com.legacy.analysis;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-007 (work-order 2026-10-resume-consistency-and-local-guard v1, REQ-001 ①) —
 * <b>엔티티에 새 primitive 컬럼이 추가되는 것을 막는 재발 방지 테스트.</b>
 *
 * <h2>왜 primitive가 위험한가</h2>
 * <p>{@code ddl-auto=update}로 기존 테이블에 컬럼을 추가하면 <b>이미 있던 행의 값은 NULL</b>이다.
 * 필드가 primitive면 Hibernate가 그 NULL을 대입할 수 없어
 * {@code Null value was assigned to a property [...] of primitive type} 예외를 내고, 그 예외가
 * 하이드레이션 단계에서 터지므로 <b>해당 테이블 조회 전체가 실패</b>한다. 2026-09-15 실배포에서
 * {@code SessionState.generateReadme}가 primitive였던 탓에 이력 94건 조회가 전부 500이 됐다
 * (그 수정은 wrapper + NULL 해석 규칙으로 마무리됐다 — {@code SessionState.isGenerateReadme()}).
 *
 * <h2>이 테스트가 하는 일</h2>
 * <p>클래스패스를 스캔해 {@code com.legacy} 아래 모든 {@code @Entity}를 찾고, DB에 매핑되는
 * primitive 필드를 수집해 <b>아래 "검토 완료 목록"과 정확히 일치</b>하는지 본다. 새 primitive 필드가
 * 생기면 RED가 되고, 실패 메시지가 wrapper로 바꾸는 방법을 알려 준다.
 *
 * <p>목록에 있는 12개는 모두 <b>그 테이블이 처음 만들어진 커밋에서 함께 도입</b>됐다 — 즉 "컬럼은
 * 나중에 추가됐는데 옛 행에 값이 없는" 조합이 아니다(클래스별 근거는 목록 주석에 적는다).
 */
class EntityPrimitiveColumnGuardTest {

  private static final String BASE_PACKAGE = "com.legacy";

  /**
   * <b>검토 완료 목록</b> — DB에 매핑되는 primitive 필드 12개. 전부 "테이블 최초 생성 커밋에서 함께
   * 도입"임을 git 이력으로 확인했으므로 옛 행에 NULL이 있을 수 없다.
   *
   * <ul>
   *   <li>{@code LlmModelOption} 3개 — 엔티티·{@code @Table(name="llm_model_options")}와 같은 커밋
   *       {@code 0ed4cbb}(2026-08-21 LLM 모델 목록 DB화)에서 함께 도입.</li>
   *   <li>{@code SessionState} 3개 — 엔티티·{@code @Table(name="analysis_sessions")}와 같은 커밋
   *       {@code e8d129c}(2026-06-15 세션 관리 + H2 DB 전환)에서 함께 도입.</li>
   *   <li>{@code ApiUsage} 4개 — 엔티티·{@code @Table(name="api_usage")}와 같은 커밋
   *       {@code 163a680}(2026-06-15 API 사용량 추적)에서 함께 도입.</li>
   *   <li>{@code User} 1개 — 엔티티·{@code @Table(name="users")}와 같은 커밋
   *       {@code b97f193}(2026-06-15 JWT 멀티유저 인증)에서 함께 도입.</li>
   *   <li>{@code Notification} 1개 — 엔티티·{@code @Table(name="notifications")}와 같은 커밋
   *       {@code 6887c3b}(2026-06-15 알림 시스템)에서 함께 도입.</li>
   * </ul>
   *
   * <p><b>12개 중 7개는 실제 컬럼이 nullable이다(잠재 조합)</b> — {@code @Column(name=…)}에
   * {@code nullable}을 적지 않아 JPA 기본값 {@code true}가 primitive의 NOT NULL 추론을 덮었다.
   * 어느 7개인지와 그 의미는 {@code EntityPrimitiveColumnNullabilityJpaTest}의 두 목록을 참조한다.
   */
  private static final Set<String> REVIEWED_PRIMITIVE_FIELDS = new TreeSet<>(List.of(
      "ApiUsage.executionTimeMs",
      "ApiUsage.requestSize",
      "ApiUsage.responseSize",
      "ApiUsage.statusCode",
      "LlmModelOption.active",
      "LlmModelOption.displayOrder",
      "LlmModelOption.failoverTarget",
      "Notification.isRead",
      "SessionState.isCancelled",
      "SessionState.processedFiles",
      "SessionState.totalFiles",
      "User.isActive"));

  // ===================================================================
  // 본 검사
  // ===================================================================

  @Test
  void 엔티티의_DB매핑_primitive_필드는_검토_완료_목록과_정확히_일치한다() {
    List<Class<?>> entities = scanEntities();
    assertTrue(entities.size() >= 8,
        "엔티티를 8개 이상 찾아야 한다(스캐너가 동작하지 않으면 '위반 0건'이 거짓이 된다). 실제: " + entities);

    Set<String> collected = new TreeSet<>();
    for (Class<?> entity : entities) {
      for (Field f : collectDbMappedPrimitiveFields(entity)) {
        collected.add(entity.getSimpleName() + "." + f.getName());
      }
    }

    System.out.println("[007] 스캔한 @Entity " + entities.size() + "개: "
        + entities.stream().map(Class::getSimpleName).sorted().toList());
    System.out.println("[007] 수집한 DB매핑 primitive 필드 " + collected.size() + "개: " + collected);

    Set<String> added = new LinkedHashSet<>(collected);
    added.removeAll(REVIEWED_PRIMITIVE_FIELDS);
    Set<String> removed = new LinkedHashSet<>(REVIEWED_PRIMITIVE_FIELDS);
    removed.removeAll(collected);

    if (!added.isEmpty()) {
      fail(unreviewedFieldMessage(added));
    }
    assertTrue(removed.isEmpty(),
        "검토 완료 목록에 있던 필드가 사라졌다(wrapper로 바꿨거나 삭제됐다면 목록도 함께 줄여라): " + removed);
    assertEquals(REVIEWED_PRIMITIVE_FIELDS, collected);
  }

  /**
   * 검토되지 않은 primitive 필드가 생겼을 때의 안내. <b>두 갈래로 나눠 적는다</b> —
   * 어느 쪽인지에 따라 해야 할 일이 반대이기 때문이다(2026-10 PL 판정).
   *
   * <p>ⓐ <b>기존 테이블에 컬럼을 추가</b>하는 경우는 {@code nullable = false}로 막을 수 없다
   * ({@code ddl-auto=update}는 이미 있는 행의 값을 채우지 않는다). ⓑ <b>새 테이블과 함께 도입</b>하는
   * 경우는 {@code @Column}을 쓴다면 {@code nullable = false}를 반드시 함께 적어야 한다 —
   * {@code @Column(name = …)}만 쓰면 JPA 기본값 {@code nullable = true}가 primitive의 NOT NULL
   * 추론을 덮는다(이 저장소에서 12개 중 7개가 그 상태다).
   */
  static String unreviewedFieldMessage(Set<String> addedFields) {
    return "DB에 매핑되는 primitive 필드가 '검토 완료 목록'에 없다: " + addedFields
        + "\n\nⓐ 기존 테이블에 컬럼을 추가하는 경우 — 옛 행은 그 컬럼이 NULL이라 엔티티 로드가 실패한다"
        + " (Null value was assigned to a property of primitive type). `nullable = false`로는 막을 수 없다"
        + "(ddl-auto=update는 기존 행 값을 채우지 않는다)."
        + " `SessionState.generateReadme`처럼 wrapper + NULL 해석 규칙(접근자는 primitive 유지)을 쓰라."
        + "\n\nⓑ 새 테이블과 함께 도입하는 경우 — `@Column`을 쓴다면 반드시 `nullable = false`를 함께 적어라."
        + " `@Column(name = ...)`만 쓰면 JPA 기본값 nullable = true가 primitive의 NOT NULL 추론을 덮는다."
        + "\n\n검토가 끝나면 이 목록과 EntityPrimitiveColumnNullabilityJpaTest의 두 목록"
        + "(NOT_NULL_PRIMITIVE_COLUMNS / KNOWN_NULLABLE_PRIMITIVE_COLUMNS)에 함께 추가하라.";
  }

  /** (m) 안내 문구가 두 갈래를 모두 담고 있는지 — 메시지 생성 함수 출력으로 보인다. */
  @Test
  void 안내_문구는_기존테이블_추가와_새테이블_도입_두_갈래를_모두_담는다() {
    String message = unreviewedFieldMessage(new TreeSet<>(List.of("SomeEntity.newPrimitiveField")));
    System.out.println("[007 안내 문구 원문]\n" + message);

    assertTrue(message.contains("SomeEntity.newPrimitiveField"), "문제 필드 이름이 없다");
    // ⓐ 기존 테이블에 컬럼 추가 → wrapper
    assertTrue(message.contains("기존 테이블에 컬럼을 추가하는 경우"), "ⓐ 갈래가 없다");
    assertTrue(message.contains("wrapper"), "ⓐ 갈래에 wrapper 안내가 없다");
    assertTrue(message.contains("ddl-auto=update는 기존 행 값을 채우지 않는다"),
        "ⓐ에서 nullable=false로는 막을 수 없는 이유가 없다");
    // ⓑ 새 테이블과 함께 도입 → nullable = false
    assertTrue(message.contains("새 테이블과 함께 도입하는 경우"), "ⓑ 갈래가 없다");
    assertTrue(message.contains("nullable = false"), "ⓑ 갈래에 nullable = false 안내가 없다");
    assertTrue(message.contains("JPA 기본값 nullable = true"), "ⓑ의 원인 설명이 없다");
    // 두 목록에 함께 추가하라는 후속 안내
    assertTrue(message.contains("NOT_NULL_PRIMITIVE_COLUMNS")
            && message.contains("KNOWN_NULLABLE_PRIMITIVE_COLUMNS"),
        "검토 후 갱신할 두 목록 이름이 없다");
  }

  // ===================================================================
  // 양성 대조군 — 수집 함수가 실제로 구분하는가
  // ===================================================================

  /**
   * 대조군용 합성 클래스 — primitive 1 + {@code @Transient} primitive 1 + static primitive 1 +
   * java {@code transient} primitive 1 + wrapper 1.
   *
   * <p><b>일부러 {@code @Entity}를 붙이지 않았다.</b> 테스트 클래스도 테스트 실행 시 클래스패스에
   * 있으므로, 여기에 {@code @Entity}를 붙이면 애플리케이션의 {@code @EntityScan("com.legacy")}와
   * 이 테스트의 스캐너가 이 합성 클래스를 실제 엔티티로 집어 들 수 있다(스키마 생성·컨텍스트 로드에
   * 영향). 수집 함수는 {@code @Entity} 여부를 보지 않으므로 대조군으로는 애노테이션이 필요 없다.
   */
  static class SyntheticEntity {
    @Id
    private Long id;
    /** 수집돼야 하는 유일한 필드. */
    private int countedPrimitive;
    @Transient
    private int transientPrimitive;
    private static int staticPrimitive;
    private Integer wrapperField;
    private transient int javaTransientPrimitive;
  }

  @Test
  void 대조군_합성_클래스에서_수집되는_것은_정확히_1개다() {
    List<Field> collected = collectDbMappedPrimitiveFields(SyntheticEntity.class);
    List<String> names = new ArrayList<>();
    for (Field f : collected) names.add(f.getName());

    System.out.println("[007 대조군] 합성 클래스 수집 결과 = " + names);
    assertEquals(List.of("countedPrimitive"), names,
        "수집 함수가 @Transient / static / java transient / wrapper 를 구분하지 못한다"
            + " — 그러면 본 검사의 '위반 0건'도 증거가 되지 않는다");

    // 각 제외 이유가 실제로 구분됐음을 하나씩 확인한다(한 가지 이유로 전부 걸러진 게 아님).
    assertEquals(4, SyntheticEntity.class.getDeclaredFields().length - 2,
        "합성 클래스 구성이 바뀌었다(id + 수집대상 1 + 제외대상 4를 기대)");
  }

  @Test
  void 대조군_Id_애노테이션_필드가_primitive여도_수집하지_않는다() {
    List<Field> collected = collectDbMappedPrimitiveFields(SyntheticIdPrimitiveEntity.class);
    System.out.println("[007 대조군] @Id primitive 수집 결과 = "
        + collected.stream().map(Field::getName).toList());
    assertTrue(collected.isEmpty(),
        "@Id는 항상 값이 있으므로 제외한다 — 수집되면 목록 관리가 불필요하게 늘어난다");
  }

  /** 같은 이유로 {@code @Entity}를 붙이지 않는다(위 SyntheticEntity 주석 참고). */
  static class SyntheticIdPrimitiveEntity {
    @Id
    private long id;
  }

  // ===================================================================
  // 도우미
  // ===================================================================

  /** {@code com.legacy} 아래의 {@code @Entity} 클래스를 클래스패스 스캔으로 찾는다. */
  private static List<Class<?>> scanEntities() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false) {
          @Override
          protected boolean isCandidateComponent(
              org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
            // 기본 구현은 추상/비독립 클래스를 거르지만, 엔티티는 독립 클래스만 보면 되므로 그대로 둔다.
            return beanDefinition.getMetadata().isIndependent();
          }
        };
    scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

    List<Class<?>> entities = new ArrayList<>();
    for (BeanDefinition bd : scanner.findCandidateComponents(BASE_PACKAGE)) {
      String className = bd.getBeanClassName();
      if (className == null) continue;
      try {
        Class<?> clazz = Class.forName(className);
        // 방어: 테스트 전용 중첩 클래스는 본 검사 대상이 아니다. 지금은 합성 클래스에 @Entity를 붙이지
        // 않아 애초에 걸리지 않지만, 누가 붙이더라도 본 검사가 오염되지 않게 둔다.
        if (clazz.getEnclosingClass() == EntityPrimitiveColumnGuardTest.class) continue;
        entities.add(clazz);
      } catch (ClassNotFoundException e) {
        fail("스캔된 엔티티 클래스를 로드하지 못했다: " + className);
      }
    }
    entities.sort(java.util.Comparator.comparing(Class::getSimpleName));
    return entities;
  }

  /**
   * DB에 매핑되는 primitive 필드만 모은다 — 제외: {@code static}, java {@code transient} 수식자,
   * {@code @Transient}, {@code @Id}.
   */
  private static List<Field> collectDbMappedPrimitiveFields(Class<?> entity) {
    List<Field> out = new ArrayList<>();
    for (Field f : entity.getDeclaredFields()) {
      if (!f.getType().isPrimitive()) continue;
      if (Modifier.isStatic(f.getModifiers())) continue;
      if (Modifier.isTransient(f.getModifiers())) continue;
      if (f.isAnnotationPresent(Transient.class)) continue;
      if (f.isAnnotationPresent(Id.class)) continue;
      out.add(f);
    }
    out.sort(java.util.Comparator.comparing(Field::getName));
    return out;
  }
}
