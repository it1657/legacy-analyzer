package com.legacy.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DataInitializer.run()의 역할/기본 계정 초기화 분기를 검증한다.
 * 프로덕션 로직은 initializeRoles() → initializeAdminUser() → initializeTestUser() 순서로
 * roleRepository.findByName(...)을 무조건 1회(역할 존재 확인) 호출한 뒤,
 * existsByUserId(...)가 false일 때만 각 계정 생성 로직에서 findByName(...)을 추가로 호출한다.
 * 이 순서 특성 때문에 시나리오 3/4에서는 두 역할(ADMIN/USER) 모두 "이미 존재"로 stub해
 * 서로 다른 계정 경로가 orElseThrow로 실패하지 않도록 격리한다.
 */
class DataInitializerTest {

  private RoleRepository roleRepository;
  private UserRepository userRepository;
  private PasswordEncoder passwordEncoder;
  private DataInitializer dataInitializer;

  @BeforeEach
  void setUp() {
    roleRepository = mock(RoleRepository.class);
    userRepository = mock(UserRepository.class);
    passwordEncoder = mock(PasswordEncoder.class);
    dataInitializer = new DataInitializer(roleRepository, userRepository, passwordEncoder);
  }

  @Test
  void 최초_실행이면_역할과_기본_계정을_모두_생성한다() throws Exception {
    // initializeRoles()의 첫 조회는 비어있어 역할을 새로 생성하고,
    // initializeAdminUser/initializeTestUser의 재조회에서는 그 역할이 존재하는 것으로 stub한다.
    Role adminRoleForUser = AuthTestFixtures.newRole("ADMIN");
    Role userRoleForUser = AuthTestFixtures.newRole("USER");
    when(roleRepository.findByName("ADMIN")).thenReturn(Optional.empty(), Optional.of(adminRoleForUser));
    when(roleRepository.findByName("USER")).thenReturn(Optional.empty(), Optional.of(userRoleForUser));
    when(userRepository.existsByUserId("admin")).thenReturn(false);
    when(userRepository.existsByUserId("test")).thenReturn(false);
    when(passwordEncoder.encode("admin")).thenReturn("encoded-admin");
    when(passwordEncoder.encode("1")).thenReturn("encoded-test");

    dataInitializer.run();

    ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
    verify(roleRepository, times(2)).save(roleCaptor.capture());
    Role savedAdminRole = roleCaptor.getAllValues().stream()
        .filter(r -> "ADMIN".equals(r.getName())).findFirst().orElseThrow();
    assertEquals("관리자 역할", savedAdminRole.getDescription());
    Role savedUserRole = roleCaptor.getAllValues().stream()
        .filter(r -> "USER".equals(r.getName())).findFirst().orElseThrow();
    assertEquals("일반 사용자 역할", savedUserRole.getDescription());

    ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
    verify(userRepository, times(2)).save(userCaptor.capture());
    User savedAdmin = userCaptor.getAllValues().stream()
        .filter(u -> "admin".equals(u.getUserId())).findFirst().orElseThrow();
    assertEquals("admin@example.com", savedAdmin.getEmail());
    assertEquals("관리자", savedAdmin.getDisplayName());
    assertEquals(Collections.singleton(adminRoleForUser), savedAdmin.getRoles());
    assertTrue(savedAdmin.isActive());
    assertEquals("encoded-admin", savedAdmin.getPasswordHash());

    User savedTest = userCaptor.getAllValues().stream()
        .filter(u -> "test".equals(u.getUserId())).findFirst().orElseThrow();
    assertEquals("test@example.com", savedTest.getEmail());
    assertEquals("테스트사용자", savedTest.getDisplayName());
    assertEquals(Collections.singleton(userRoleForUser), savedTest.getRoles());
    assertTrue(savedTest.isActive());
    assertEquals("encoded-test", savedTest.getPasswordHash());

    verify(passwordEncoder, times(1)).encode("admin");
    verify(passwordEncoder, times(1)).encode("1");
  }

  @Test
  void ADMIN_역할이_이미_존재하면_ADMIN_역할_저장은_건너뛰고_USER_역할은_계속_생성한다() throws Exception {
    Role existingAdminRole = AuthTestFixtures.newRole("ADMIN");
    Role userRoleForUser = AuthTestFixtures.newRole("USER");
    when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(existingAdminRole));
    when(roleRepository.findByName("USER")).thenReturn(Optional.empty(), Optional.of(userRoleForUser));
    when(userRepository.existsByUserId("admin")).thenReturn(false);
    when(userRepository.existsByUserId("test")).thenReturn(false);

    dataInitializer.run();

    verify(roleRepository, never()).save(argThat(r -> "ADMIN".equals(r.getName())));
    ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
    verify(roleRepository, times(1)).save(roleCaptor.capture());
    assertEquals("USER", roleCaptor.getValue().getName());
    assertEquals("일반 사용자 역할", roleCaptor.getValue().getDescription());
  }

  @Test
  void existsByUserId_admin이_true이면_admin_계정_생성_로직을_건너뛴다() throws Exception {
    // ADMIN/USER 역할 모두 "이미 존재"로 stub해 initializeRoles()의 무조건 조회와
    // (existsByUserId("test")=false 기본값으로 계속 진행되는) initializeTestUser()의
    // 정상 흐름이 예외 없이 끝나도록 격리한다.
    when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(AuthTestFixtures.newRole("ADMIN")));
    when(roleRepository.findByName("USER")).thenReturn(Optional.of(AuthTestFixtures.newRole("USER")));
    when(userRepository.existsByUserId("admin")).thenReturn(true);

    dataInitializer.run();

    // existsByUserId("admin") 체크가 findByName("ADMIN") 조회보다 선행하므로,
    // initializeAdminUser() 내부에서 findByName("ADMIN")을 추가로 호출하지 않는다.
    // (initializeRoles()에서 무조건 발생하는 1회 호출 외에 추가 호출이 없음을 확인.)
    verify(roleRepository, times(1)).findByName("ADMIN");
    verify(userRepository, never()).save(argThat(u -> "admin".equals(u.getUserId())));
  }

  @Test
  void existsByUserId_test가_true이면_test_계정_생성_로직을_건너뛴다() throws Exception {
    // 3번 시나리오와 대칭: ADMIN/USER 역할 모두 "이미 존재"로 stub해
    // (existsByUserId("admin")=false 기본값으로 계속 진행되는) initializeAdminUser()가
    // 예외 없이 정상적으로 끝나도록 격리한다.
    when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(AuthTestFixtures.newRole("ADMIN")));
    when(roleRepository.findByName("USER")).thenReturn(Optional.of(AuthTestFixtures.newRole("USER")));
    when(userRepository.existsByUserId("test")).thenReturn(true);

    dataInitializer.run();

    // existsByUserId("test") 체크가 findByName("USER") 조회보다 선행하므로,
    // initializeTestUser() 내부에서 findByName("USER")를 추가로 호출하지 않는다.
    verify(roleRepository, times(1)).findByName("USER");
    verify(userRepository, never()).save(argThat(u -> "test".equals(u.getUserId())));
  }

  @Test
  void USER_역할이_계속_비어있으면_RuntimeException이_발생한다() {
    // ADMIN 경로는 existsByUserId("admin")=true로 건너뛰어, USER 역할 부재로 인한
    // 예외만 격리해서 확인한다(그렇지 않으면 initializeAdminUser의 findByName("ADMIN")
    // orElseThrow가 먼저 던져질 수 있다).
    when(roleRepository.findByName("USER")).thenReturn(Optional.empty());
    when(userRepository.existsByUserId("admin")).thenReturn(true);

    RuntimeException exception = assertThrows(RuntimeException.class, () -> dataInitializer.run());

    assertEquals("USER 역할이 없습니다.", exception.getMessage());
  }
}
