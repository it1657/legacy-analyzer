package com.legacy.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Role 엔티티의 생성자/기본생성자+setter에 대한 스모크 테스트.
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
}
