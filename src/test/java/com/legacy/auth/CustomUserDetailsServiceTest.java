package com.legacy.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.HashSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CustomUserDetailsService.loadUserByUsername의 정상/예외 분기를 검증한다.
 */
class CustomUserDetailsServiceTest {

  private UserRepository userRepository;
  private CustomUserDetailsService customUserDetailsService;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    customUserDetailsService = new CustomUserDetailsService(userRepository);
  }

  @Test
  void findByUserId가_사용자를_반환하면_동일한_인스턴스를_그대로_반환한다() {
    User user = AuthTestFixtures.newUser(1L, "hong", "hong@test.com", true, new HashSet<>());
    when(userRepository.findByUserId("hong")).thenReturn(Optional.of(user));

    UserDetails result = customUserDetailsService.loadUserByUsername("hong");

    assertSame(user, result);
  }

  @Test
  void findByUserId가_비어있으면_UsernameNotFoundException이_발생하고_메시지에_userId가_포함된다() {
    when(userRepository.findByUserId("unknown")).thenReturn(Optional.empty());

    UsernameNotFoundException exception = assertThrows(UsernameNotFoundException.class,
        () -> customUserDetailsService.loadUserByUsername("unknown"));

    assertEquals("사용자를 찾을 수 없습니다: unknown", exception.getMessage());
  }
}
