package com.legacy.auth;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
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
 *
 * <p>2026-09-security-fixes(REQ-001): DataInitializer 생성자에 시딩 토글/기본 비밀번호 3개 파라미터가
 * 추가됐다. 기존 5개 시나리오는 기존 동작과 동일한 기본값(true/"admin"/"1")을 넘겨 assertion을 그대로
 * 유지하고(회귀 확인), 수정 후 동작(시딩 스킵, 로그 비밀번호 미노출, 비밀번호 오버라이드)은
 * 아래 신규 시나리오 3건에서 검증한다.
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
    // 기존 시나리오는 기존 동작과 동일한 기본값(시딩 on, admin/1)으로 생성해 회귀 여부를 확인한다.
    dataInitializer = new DataInitializer(roleRepository, userRepository, passwordEncoder,
        true, "admin", "1");
  }

  /** 세 역할이 모두 존재하고 두 계정이 아직 없는 "정상 시딩" 상태로 stub한다. */
  private void stubRolesExistAndUsersMissing() {
    when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(AuthTestFixtures.newRole("ADMIN")));
    when(roleRepository.findByName("USER")).thenReturn(Optional.of(AuthTestFixtures.newRole("USER")));
    when(roleRepository.findByName("ANTHROPIC_USER"))
        .thenReturn(Optional.of(AuthTestFixtures.newRole("ANTHROPIC_USER")));
    when(userRepository.existsByUserId("admin")).thenReturn(false);
    when(userRepository.existsByUserId("test")).thenReturn(false);
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
    // 2026-09-anthropic-access-control(REQ-001): 생성 대상 역할이 ADMIN/USER 2개 → +ANTHROPIC_USER 3개로 늘었다.
    // 기존 ADMIN/USER 검증은 그대로 유지하고 세 번째 역할 검증만 덧붙인다.
    verify(roleRepository, times(3)).save(roleCaptor.capture());
    Role savedAdminRole = roleCaptor.getAllValues().stream()
        .filter(r -> "ADMIN".equals(r.getName())).findFirst().orElseThrow();
    assertEquals("관리자 역할", savedAdminRole.getDescription());
    Role savedUserRole = roleCaptor.getAllValues().stream()
        .filter(r -> "USER".equals(r.getName())).findFirst().orElseThrow();
    assertEquals("일반 사용자 역할", savedUserRole.getDescription());
    Role savedAnthropicRole = roleCaptor.getAllValues().stream()
        .filter(r -> "ANTHROPIC_USER".equals(r.getName())).findFirst().orElseThrow();
    assertEquals("Anthropic(Claude API) 사용 권한", savedAnthropicRole.getDescription());

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
    // 2026-09-anthropic-access-control(REQ-001): ANTHROPIC_USER 역할이 함께 생성되므로 저장 횟수가 2회다.
    // "ADMIN은 건너뛰고 USER는 생성한다"는 기존 검증 의도는 그대로 유지한다.
    verify(roleRepository, times(2)).save(roleCaptor.capture());
    Role savedUserRole = roleCaptor.getAllValues().stream()
        .filter(r -> "USER".equals(r.getName())).findFirst().orElseThrow();
    assertEquals("USER", savedUserRole.getName());
    assertEquals("일반 사용자 역할", savedUserRole.getDescription());
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

  // ---------- 2026-09-security-fixes(REQ-001) 수정 후 동작 검증 ----------

  @Test
  void seedDefaultAccounts가_false이면_admin과_test_계정을_모두_생성하지_않는다() throws Exception {
    stubRolesExistAndUsersMissing();
    DataInitializer noSeedInitializer = new DataInitializer(roleRepository, userRepository,
        passwordEncoder, false, "admin", "1");

    noSeedInitializer.run();

    verify(userRepository, never()).save(argThat(u -> "admin".equals(u.getUserId())));
    verify(userRepository, never()).save(argThat(u -> "test".equals(u.getUserId())));
    // 계정 존재 여부 조회 자체에 도달하지 않는다(가드가 진입부에 있으므로).
    verify(userRepository, never()).existsByUserId("admin");
    verify(userRepository, never()).existsByUserId("test");
    verify(passwordEncoder, never()).encode(anyString());
  }

  @Test
  void 계정_생성_로그에_평문_비밀번호_값이_남지_않는다() throws Exception {
    stubRolesExistAndUsersMissing();

    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DataInitializer.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      dataInitializer.run();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    List<String> messages = appender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .toList();

    // 계정 생성 로그 자체는 그대로 남는다(userId 문자열 "admin"/"test"는 허용).
    assertTrue(messages.stream().anyMatch(m -> m.contains("[기본 사용자 생성] userId=admin")));
    assertTrue(messages.stream().anyMatch(m -> m.contains("[기본 사용자 생성] userId=test")));

    // "비밀번호" 뒤에 콜론+값이 따라오는 형태(=평문 노출)가 어떤 로그에도 없어야 한다.
    Pattern plainPasswordPattern = Pattern.compile("비밀번호\\s*[::]\\s*\\S");
    for (String message : messages) {
      assertFalse(plainPasswordPattern.matcher(message).find(),
          "평문 비밀번호가 로그에 노출됨: " + message);
      assertFalse(message.contains("비밀번호: admin"), "평문 비밀번호가 로그에 노출됨: " + message);
      assertFalse(message.contains("비밀번호: 1"), "평문 비밀번호가 로그에 노출됨: " + message);
    }
  }

  @Test
  void 기본_비밀번호_설정값을_바꾸면_그_값으로_인코딩된다() throws Exception {
    stubRolesExistAndUsersMissing();
    DataInitializer customInitializer = new DataInitializer(roleRepository, userRepository,
        passwordEncoder, true, "custom-pw", "custom-test-pw");

    customInitializer.run();

    verify(passwordEncoder, times(1)).encode("custom-pw");
    verify(passwordEncoder, times(1)).encode("custom-test-pw");
    verify(passwordEncoder, never()).encode("admin");
    verify(passwordEncoder, never()).encode("1");
  }

  // ---------- 2026-09-anthropic-access-control(REQ-001) ANTHROPIC_USER 역할 신설 ----------

  @Test
  void ANTHROPIC_USER_역할이_없으면_ADMIN_USER와_함께_생성된다() throws Exception {
    // 세 역할 모두 최초 조회는 비어 있고, 계정 생성 단계의 재조회에서는 존재하도록 stub한다.
    when(roleRepository.findByName("ADMIN"))
        .thenReturn(Optional.empty(), Optional.of(AuthTestFixtures.newRole("ADMIN")));
    when(roleRepository.findByName("USER"))
        .thenReturn(Optional.empty(), Optional.of(AuthTestFixtures.newRole("USER")));
    when(roleRepository.findByName("ANTHROPIC_USER")).thenReturn(Optional.empty());
    when(userRepository.existsByUserId("admin")).thenReturn(false);
    when(userRepository.existsByUserId("test")).thenReturn(false);

    dataInitializer.run();

    ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
    verify(roleRepository, times(3)).save(roleCaptor.capture());
    // ADMIN/USER와 병존 생성되는지 확인한다(기존 두 역할을 대체하는 게 아님).
    List<String> savedRoleNames = roleCaptor.getAllValues().stream().map(Role::getName).toList();
    assertTrue(savedRoleNames.contains("ADMIN"));
    assertTrue(savedRoleNames.contains("USER"));
    assertTrue(savedRoleNames.contains("ANTHROPIC_USER"));
    Role savedAnthropicRole = roleCaptor.getAllValues().stream()
        .filter(r -> "ANTHROPIC_USER".equals(r.getName())).findFirst().orElseThrow();
    assertEquals("Anthropic(Claude API) 사용 권한", savedAnthropicRole.getDescription());
  }

  @Test
  void ANTHROPIC_USER_역할이_이미_존재하면_재생성하지_않는다() throws Exception {
    // 멱등성 확인 — 재기동 시 중복 생성되지 않아야 한다.
    stubRolesExistAndUsersMissing();

    dataInitializer.run();

    verify(roleRepository, never()).save(argThat(r -> "ANTHROPIC_USER".equals(r.getName())));
    verify(roleRepository, never()).save(argThat(r -> "ADMIN".equals(r.getName())));
    verify(roleRepository, never()).save(argThat(r -> "USER".equals(r.getName())));
  }

  @Test
  void 기본_계정_admin과_test에는_ANTHROPIC_USER_역할이_부여되지_않는다() throws Exception {
    // "기본값은 권한 없음" 요구사항 — initializeAdminUser/initializeTestUser는 무변경이어야 한다.
    stubRolesExistAndUsersMissing();

    dataInitializer.run();

    ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
    verify(userRepository, times(2)).save(userCaptor.capture());
    for (User savedUser : userCaptor.getAllValues()) {
      assertFalse(savedUser.getRoles().stream().anyMatch(r -> "ANTHROPIC_USER".equals(r.getName())),
          "기본 계정에 ANTHROPIC_USER가 자동 부여됨: " + savedUser.getUserId());
    }
    User savedAdmin = userCaptor.getAllValues().stream()
        .filter(u -> "admin".equals(u.getUserId())).findFirst().orElseThrow();
    assertEquals(1, savedAdmin.getRoles().size());
    User savedTest = userCaptor.getAllValues().stream()
        .filter(u -> "test".equals(u.getUserId())).findFirst().orElseThrow();
    assertEquals(1, savedTest.getRoles().size());
  }
}
