package com.legacy.auth;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * User 엔티티(UserDetails 구현 포함)의 생성자/getter/setter 및 파생 로직에 대한 스모크 테스트.
 */
class UserTest {

  @Test
  void 생성자로_생성하면_userId_email_passwordHash가_설정되고_createdAt_updatedAt이_null이_아니다() {
    User user = new User("hong", "hong@test.com", "hashed-pw");

    assertEquals("hong", user.getUserId());
    assertEquals("hong@test.com", user.getEmail());
    assertEquals("hashed-pw", user.getPasswordHash());
    assertNotNull(user.getCreatedAt());
    assertNotNull(user.getUpdatedAt());
  }

  @Test
  void getDisplayName_displayName_미설정시_userId를_반환한다() {
    User user = new User("hong", "hong@test.com", "hashed-pw");

    assertEquals("hong", user.getDisplayName());
  }

  @Test
  void getDisplayName_displayName_설정시_설정된_값을_반환한다() {
    User user = new User("hong", "hong@test.com", "hashed-pw");
    user.setDisplayName("홍길동");

    assertEquals("홍길동", user.getDisplayName());
  }

  @Test
  void getAuthorities_roles가_있으면_ROLE_접두사가_붙은_권한으로_매핑된다() {
    User user = AuthTestFixtures.newUser(1L, "hong", "hong@test.com", true,
        Set.of(AuthTestFixtures.newRole("ADMIN"), AuthTestFixtures.newRole("USER")));

    List<String> authorities = user.getAuthorities().stream()
        .map(Object::toString)
        .toList();

    assertEquals(2, authorities.size());
    assertTrue(authorities.containsAll(List.of("ROLE_ADMIN", "ROLE_USER")));
  }

  @Test
  void getAuthorities_roles가_빈_Set이면_빈_리스트를_반환한다() {
    User user = AuthTestFixtures.newUser(1L, "hong", "hong@test.com", true, new HashSet<>());

    assertTrue(user.getAuthorities().isEmpty());
  }

  @Test
  void UserDetails_스모크_getUsername_getPassword가_userId_passwordHash와_동일하다() {
    User user = new User("hong", "hong@test.com", "hashed-pw");

    assertEquals("hong", user.getUsername());
    assertEquals("hashed-pw", user.getPassword());
  }

  @Test
  void isEnabled_active가_true이면_true를_반환한다() {
    User user = AuthTestFixtures.newUser(1L, "hong", "hong@test.com", true, new HashSet<>());

    assertTrue(user.isEnabled());
  }

  @Test
  void isEnabled_active가_false이면_false를_반환한다() {
    User user = AuthTestFixtures.newUser(1L, "hong", "hong@test.com", false, new HashSet<>());

    assertFalse(user.isEnabled());
  }

  @Test
  void isAccountNonExpired_isAccountNonLocked_isCredentialsNonExpired는_항상_true를_반환한다() {
    User user = new User("hong", "hong@test.com", "hashed-pw");

    assertTrue(user.isAccountNonExpired());
    assertTrue(user.isAccountNonLocked());
    assertTrue(user.isCredentialsNonExpired());
  }

  @Test
  void 나머지_setter들이_getter로_일괄_확인된다() {
    User user = new User();

    Set<Role> roles = Set.of(AuthTestFixtures.newRole("ADMIN"));
    LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 0, 0);
    LocalDateTime updatedAt = LocalDateTime.of(2026, 1, 2, 0, 0);

    user.setSeq(10L);
    user.setEmail("new@test.com");
    user.setPasswordHash("new-hash");
    user.setRoles(roles);
    user.setCreatedAt(createdAt);
    user.setUpdatedAt(updatedAt);

    assertEquals(10L, user.getSeq());
    assertEquals("new@test.com", user.getEmail());
    assertEquals("new-hash", user.getPasswordHash());
    assertEquals(roles, user.getRoles());
    assertEquals(createdAt, user.getCreatedAt());
    assertEquals(updatedAt, user.getUpdatedAt());
  }
}
