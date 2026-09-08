package com.legacy.auth;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Role 엔티티의 생성자/기본생성자+setter에 대한 스모크 테스트.
 *
 * <p>2026-09-anthropic-access-control(TASK-006): equals/hashCode 계약 검증을 아래 섹션에 추가했다.
 * Role은 name이 유일 키(@Column(unique = true))인데 equals/hashCode가 없으면 Set&lt;Role&gt;의
 * add/remove가 인스턴스 동일성에 의존해, 영속성 컨텍스트가 다르면 권한 해제가 조용히 실패하거나
 * 같은 역할이 중복 추가된다 — bug-suspects.md 2026-09-07 항목 대응.
 */
class RoleTest {

  @Test
  void 생성자로_생성하면_name_description이_설정된다() {
    Role role = new Role("ADMIN", "ADMIN 역할");

    assertEquals("ADMIN", role.getName());
    assertEquals("ADMIN 역할", role.getDescription());
  }

  @Test
  void 기본생성자_생성_직후에는_모든_필드가_null이다() {
    Role role = new Role();

    assertNull(role.getId());
    assertNull(role.getName());
    assertNull(role.getDescription());
  }

  @Test
  void 기본생성자_이후_setter로_설정한_값이_getter로_그대로_반환된다() {
    Role role = new Role();

    role.setId(5L);
    role.setName("USER");
    role.setDescription("USER 역할");

    assertEquals(5L, role.getId());
    assertEquals("USER", role.getName());
    assertEquals("USER 역할", role.getDescription());
  }

  // ---------- TASK-006(2026-09): equals/hashCode 계약 ----------

  @Test
  void 이름이_같으면_다른_인스턴스여도_equals가_참이다() {
    Role a = new Role("ANTHROPIC_USER", "Anthropic(Claude API) 사용 권한");
    Role b = new Role("ANTHROPIC_USER", "Anthropic(Claude API) 사용 권한");

    assertFalse(a == b, "이 테스트는 서로 다른 인스턴스를 전제로 한다");
    assertEquals(a, b);
    assertEquals(b, a, "대칭성");
  }

  @Test
  void 이름이_같으면_description이_달라도_equals가_참이다() {
    // 동등성 기준은 name 하나뿐이다 — description은 판정에 관여하지 않는다.
    Role a = new Role("ANTHROPIC_USER", "Anthropic(Claude API) 사용 권한");
    Role b = new Role("ANTHROPIC_USER", "설명이 다른 같은 역할");

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void 이름이_다르면_equals가_거짓이다() {
    Role user = new Role("USER", "일반 사용자 역할");
    Role anthropic = new Role("ANTHROPIC_USER", "Anthropic(Claude API) 사용 권한");

    assertNotEquals(user, anthropic);
    assertNotEquals(anthropic, user);
  }

  @Test
  void 자기자신과는_같고_null이나_다른_타입과는_다르다() {
    Role role = new Role("USER", "일반 사용자 역할");

    assertEquals(role, role);
    assertNotEquals(null, role);
    assertNotEquals("USER", role, "타입이 다르면 거짓이어야 한다");
  }

  @Test
  void hashCode는_같은_이름에_대해_일관된다() {
    Role a = new Role("ADMIN", "관리자 역할");
    Role b = new Role("ADMIN", "관리자 역할");

    assertEquals(a.hashCode(), b.hashCode(), "equals가 참이면 hashCode도 같아야 한다");
    assertEquals(a.hashCode(), a.hashCode(), "반복 호출해도 값이 변하지 않아야 한다");
  }

  @Test
  void HashSet에_이름이_같은_서로_다른_인스턴스를_넣으면_하나로_합쳐진다() {
    // 이 프로젝트에서 실제로 문제가 됐던 지점 — User.roles가 Set<Role>이다.
    Set<Role> roles = new HashSet<>();
    roles.add(new Role("ANTHROPIC_USER", "Anthropic(Claude API) 사용 권한"));
    roles.add(new Role("ANTHROPIC_USER", "설명만 다른 동일 역할"));

    assertEquals(1, roles.size(), "이름이 같으면 중복으로 쌓이면 안 된다");
  }

  @Test
  void HashSet에서_다른_인스턴스로도_remove와_contains가_동작한다() {
    // 권한 해제(updateAnthropicAccess의 roles.remove) 경로가 의존하는 동작이다.
    Set<Role> roles = new HashSet<>();
    roles.add(new Role("USER", "일반 사용자 역할"));
    roles.add(new Role("ANTHROPIC_USER", "Anthropic(Claude API) 사용 권한"));

    assertTrue(roles.contains(new Role("ANTHROPIC_USER", "다른 인스턴스")));
    assertTrue(roles.remove(new Role("ANTHROPIC_USER", "다른 인스턴스")));
    assertEquals(1, roles.size());
    assertFalse(roles.contains(new Role("ANTHROPIC_USER", "또 다른 인스턴스")));
  }
}
