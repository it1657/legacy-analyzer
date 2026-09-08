package com.legacy.admin;

import com.legacy.audit.AuditLogService;
import com.legacy.auth.Role;
import com.legacy.auth.RoleRepository;
import com.legacy.auth.User;
import com.legacy.auth.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserController.getAllUsers()/toggleUserStatus()/updateUser()의 관리자 전용 사용자 관리 기능을 검증한다.
 * 02-design-v1 5.2절 근거.
 */
class UserControllerAdminManagementTest {

  private UserRepository userRepository;
  private RoleRepository roleRepository;
  private PasswordEncoder passwordEncoder;
  private AuditLogService auditLogService;
  private HttpServletRequest httpRequest;
  private UserController userController;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    roleRepository = mock(RoleRepository.class);
    passwordEncoder = mock(PasswordEncoder.class);
    auditLogService = mock(AuditLogService.class);
    httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");

    userController = new UserController(userRepository, roleRepository, passwordEncoder, auditLogService);
  }

  // ── getAllUsers ──────────────────────────────────────────────────

  @Test
  void getAllUsers_정상이면_200과_7개_필드가_전수_매핑된다() {
    Role adminRole = AdminTestFixtures.newRole("ADMIN");
    Role userRole = AdminTestFixtures.newRole("USER");
    User user = AdminTestFixtures.newUser(1L, "u1", "u1@example.com", true, Set.of(adminRole, userRole));
    when(userRepository.findAll()).thenReturn(List.of(user));

    ResponseEntity<?> response = userController.getAllUsers();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    assertThat(body).hasSize(1);
    Map<String, Object> map = body.get(0);
    assertEquals(1L, map.get("seq"));
    assertEquals("u1", map.get("userId"));
    assertEquals(user.getDisplayName(), map.get("displayName"));
    assertEquals("u1@example.com", map.get("email"));
    @SuppressWarnings("unchecked")
    List<String> roles = (List<String>) map.get("roles");
    assertThat(roles).containsExactlyInAnyOrder("ADMIN", "USER");
    assertEquals(true, map.get("isActive"));
    assertEquals(user.getCreatedAt(), map.get("createdAt"));
  }

  @Test
  void getAllUsers_예외가_발생하면_500과_고정_메시지를_반환한다() {
    when(userRepository.findAll()).thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = userController.getAllUsers();

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    // 예외 메시지가 아니라 고정 문자열임을 명시적으로 확인.
    assertEquals("사용자 목록 조회 실패", ((Map<?, ?>) response.getBody()).get("message"));
  }

  // ── toggleUserStatus ─────────────────────────────────────────────

  @Test
  void toggleUserStatus_대상이_없으면_400을_반환한다() {
    when(userRepository.findById(1L)).thenReturn(Optional.empty());

    ResponseEntity<?> response = userController.toggleUserStatus(1L, new HashMap<>(), httpRequest);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
  }

  @Test
  void toggleUserStatus_isActive_true이면_활성화되고_계정_활성화_감사로그가_기록된다() {
    User user = AdminTestFixtures.newUser(1L, "u1", "u1@example.com", false, Set.of());
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));

    Map<String, Boolean> request = new HashMap<>();
    request.put("isActive", true);

    ResponseEntity<?> response = userController.toggleUserStatus(1L, request, httpRequest);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertThat(user.isActive()).isTrue();

    ArgumentCaptor<String> actionCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> targetCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Long> targetIdCaptor = ArgumentCaptor.forClass(Long.class);
    ArgumentCaptor<String> targetNameCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> changesCaptor = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<String> detailsCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);

    verify(auditLogService).logAudit(actionCaptor.capture(), targetCaptor.capture(),
        targetIdCaptor.capture(), targetNameCaptor.capture(), statusCaptor.capture(),
        changesCaptor.capture(), detailsCaptor.capture(), ipCaptor.capture());

    assertEquals("UPDATE", actionCaptor.getValue());
    assertEquals("USER", targetCaptor.getValue());
    assertEquals(1L, targetIdCaptor.getValue());
    assertEquals("u1", targetNameCaptor.getValue());
    assertEquals("SUCCESS", statusCaptor.getValue());
    assertEquals(Map.of("isActive", true), changesCaptor.getValue());
    assertEquals("계정 활성화", detailsCaptor.getValue());
    assertEquals("127.0.0.1", ipCaptor.getValue());
  }

  @Test
  void toggleUserStatus_isActive_false이면_비활성화되고_계정_비활성화_감사로그가_기록된다() {
    User user = AdminTestFixtures.newUser(1L, "u1", "u1@example.com", true, Set.of());
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));

    Map<String, Boolean> request = new HashMap<>();
    request.put("isActive", false);

    ResponseEntity<?> response = userController.toggleUserStatus(1L, request, httpRequest);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertThat(user.isActive()).isFalse();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> changesCaptor = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<String> detailsCaptor = ArgumentCaptor.forClass(String.class);
    verify(auditLogService).logAudit(any(), any(), any(), any(), any(),
        changesCaptor.capture(), detailsCaptor.capture(), any());

    assertEquals(Map.of("isActive", false), changesCaptor.getValue());
    assertEquals("계정 비활성화", detailsCaptor.getValue());
  }

  @Test
  void toggleUserStatus_isActive_키가_없으면_null로_취급되어_비활성화로_처리된다() {
    User user = AdminTestFixtures.newUser(1L, "u1", "u1@example.com", true, Set.of());
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));

    Map<String, Boolean> request = new HashMap<>(); // "isActive" 키 자체 없음

    ResponseEntity<?> response = userController.toggleUserStatus(1L, request, httpRequest);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    // Boolean.TRUE.equals(null) == false 이므로 비활성화로 귀결된다.
    assertThat(user.isActive()).isFalse();
  }

  @Test
  void toggleUserStatus_예외가_발생하면_400을_반환한다() {
    when(userRepository.findById(1L)).thenReturn(Optional.empty());

    ResponseEntity<?> response = userController.toggleUserStatus(1L, new HashMap<>(), httpRequest);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("사용자 상태 변경 실패: 사용자를 찾을 수 없습니다.",
        ((Map<?, ?>) response.getBody()).get("message"));
  }

  // ── updateUser ───────────────────────────────────────────────────

  private User newManagedUser() {
    Role roleA = AdminTestFixtures.newRole("USER");
    Role roleB = AdminTestFixtures.newRole("VIEWER");
    return AdminTestFixtures.newUser(1L, "u1", "u1@example.com", true, new HashSet<>(Set.of(roleA, roleB)));
  }

  @Test
  void updateUser_대상이_없으면_400을_반환한다() {
    when(userRepository.findById(1L)).thenReturn(Optional.empty());

    ResponseEntity<?> response = userController.updateUser(1L, new HashMap<>(), httpRequest);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
  }

  @Test
  void updateUser_displayName과_password를_갱신한다() {
    User managed = newManagedUser();
    when(userRepository.findById(1L)).thenReturn(Optional.of(managed));
    when(passwordEncoder.encode("newpw")).thenReturn("encoded-newpw");

    Map<String, String> request = new HashMap<>();
    request.put("displayName", "새이름");
    request.put("password", "newpw");

    ResponseEntity<?> response = userController.updateUser(1L, request, httpRequest);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("새이름", managed.getDisplayName());
    assertEquals("encoded-newpw", managed.getPasswordHash());
    verify(userRepository, times(1)).save(managed);
  }

  @Test
  void updateUser_email이_중복이면_400을_반환하고_save가_호출되지_않는다() {
    User managed = newManagedUser();
    when(userRepository.findById(1L)).thenReturn(Optional.of(managed));
    when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

    Map<String, String> request = new HashMap<>();
    request.put("email", "dup@example.com");

    ResponseEntity<?> response = userController.updateUser(1L, request, httpRequest);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("이미 존재하는 이메일입니다.", ((Map<?, ?>) response.getBody()).get("message"));
    verify(userRepository, never()).save(any());
  }

  @Test
  void updateUser_role이_존재하지_않으면_400을_반환하고_save가_호출되지_않는다() {
    User managed = newManagedUser();
    when(userRepository.findById(1L)).thenReturn(Optional.of(managed));
    when(roleRepository.findByName("UNKNOWN")).thenReturn(Optional.empty());

    Map<String, String> request = new HashMap<>();
    request.put("role", "UNKNOWN");

    ResponseEntity<?> response = userController.updateUser(1L, request, httpRequest);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("유효하지 않은 역할입니다.", ((Map<?, ?>) response.getBody()).get("message"));
    verify(userRepository, never()).save(any());
  }

  @Test
  void updateUser_role이_존재하면_기존_역할들이_새_단일_역할로_교체된다() {
    User managed = newManagedUser(); // 기존 역할 2개(USER, VIEWER)
    Role adminRole = AdminTestFixtures.newRole("ADMIN");
    when(userRepository.findById(1L)).thenReturn(Optional.of(managed));
    when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(adminRole));

    Map<String, String> request = new HashMap<>();
    request.put("role", "ADMIN");

    ResponseEntity<?> response = userController.updateUser(1L, request, httpRequest);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertThat(managed.getRoles()).containsExactly(adminRole); // 추가가 아니라 교체
  }

  @Test
  void updateUser_성공하면_감사로그가_changes_null_사용자_정보_수정으로_기록된다() {
    User managed = newManagedUser();
    when(userRepository.findById(1L)).thenReturn(Optional.of(managed));

    Map<String, String> request = new HashMap<>();
    request.put("displayName", "새이름");

    ResponseEntity<?> response = userController.updateUser(1L, request, httpRequest);

    assertEquals(HttpStatus.OK, response.getStatusCode());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> changesCaptor = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<String> detailsCaptor = ArgumentCaptor.forClass(String.class);
    verify(auditLogService).logAudit(org.mockito.ArgumentMatchers.eq("UPDATE"),
        org.mockito.ArgumentMatchers.eq("USER"), org.mockito.ArgumentMatchers.eq(1L),
        org.mockito.ArgumentMatchers.eq("u1"), org.mockito.ArgumentMatchers.eq("SUCCESS"),
        changesCaptor.capture(), detailsCaptor.capture(), org.mockito.ArgumentMatchers.eq("127.0.0.1"));

    assertThat(changesCaptor.getValue()).isNull();
    assertEquals("사용자 정보 수정", detailsCaptor.getValue());
  }

  @Test
  void updateUser_예외가_발생하면_400을_반환한다() {
    when(userRepository.findById(1L)).thenReturn(Optional.empty());

    ResponseEntity<?> response = userController.updateUser(1L, new HashMap<>(), httpRequest);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("사용자 수정 실패: 사용자를 찾을 수 없습니다.",
        ((Map<?, ?>) response.getBody()).get("message"));
  }

  // ---------- TASK-006(2026-09): updateUser의 역할 교체가 ANTHROPIC_USER를 보존해야 한다 ----------
  // 기존 계약("role이 오면 기존 역할 전체를 단일 역할로 교체")은 그대로 두되, Anthropic 사용 권한은
  // 별도 축이므로 보유 중이었다면 살려둔다. 화면(TASK-005)뿐 아니라 API 직접 호출 경로도 닫는 조치다.

  /** {USER, ANTHROPIC_USER}를 보유한 사용자 — 권한 보존 검증용. */
  private User newUserWithAnthropicAccess() {
    Role userRole = AdminTestFixtures.newRole("USER");
    Role anthropicRole = AdminTestFixtures.newRole("ANTHROPIC_USER");
    return AdminTestFixtures.newUser(1L, "u1", "u1@example.com", true,
        new HashSet<>(Set.of(userRole, anthropicRole)));
  }

  @Test
  void updateUser_이름만_수정해도_ANTHROPIC_USER_권한이_유지된다() {
    // 사람이 실제로 겪던 흐름 — 편집 모달에서 role은 현재값(USER) 그대로 두고 이름만 바꿔 저장.
    User managed = newUserWithAnthropicAccess();
    when(userRepository.findById(1L)).thenReturn(Optional.of(managed));
    when(roleRepository.findByName("USER")).thenReturn(Optional.of(AdminTestFixtures.newRole("USER")));
    when(roleRepository.findByName("ANTHROPIC_USER"))
        .thenReturn(Optional.of(AdminTestFixtures.newRole("ANTHROPIC_USER")));

    Map<String, String> request = new HashMap<>();
    request.put("displayName", "새이름");
    request.put("role", "USER");

    ResponseEntity<?> response = userController.updateUser(1L, request, httpRequest);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertThat(managed.getRoles()).extracting(Role::getName)
        .containsExactlyInAnyOrder("USER", "ANTHROPIC_USER");
  }

  @Test
  void updateUser_USER에서_ADMIN으로_승격해도_ANTHROPIC_USER_권한이_유지된다() {
    User managed = newUserWithAnthropicAccess();
    when(userRepository.findById(1L)).thenReturn(Optional.of(managed));
    when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(AdminTestFixtures.newRole("ADMIN")));
    when(roleRepository.findByName("ANTHROPIC_USER"))
        .thenReturn(Optional.of(AdminTestFixtures.newRole("ANTHROPIC_USER")));

    Map<String, String> request = new HashMap<>();
    request.put("role", "ADMIN");

    ResponseEntity<?> response = userController.updateUser(1L, request, httpRequest);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    // 역할 승격(USER 제거 + ADMIN 부여)과 Anthropic 권한 보존이 동시에 성립해야 한다.
    assertThat(managed.getRoles()).extracting(Role::getName)
        .containsExactlyInAnyOrder("ADMIN", "ANTHROPIC_USER");
  }

  @Test
  void updateUser_ANTHROPIC_USER_역할이_DB에_없으면_보존을_건너뛰고_정상_완료된다() {
    // 방어 케이스 — 부가 조치(권한 보존)의 실패가 updateUser 본 기능을 막으면 안 된다.
    User managed = newUserWithAnthropicAccess();
    when(userRepository.findById(1L)).thenReturn(Optional.of(managed));
    when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(AdminTestFixtures.newRole("ADMIN")));
    when(roleRepository.findByName("ANTHROPIC_USER")).thenReturn(Optional.empty());

    Map<String, String> request = new HashMap<>();
    request.put("role", "ADMIN");

    ResponseEntity<?> response = userController.updateUser(1L, request, httpRequest);

    assertEquals(HttpStatus.OK, response.getStatusCode(), "400이 아니라 정상 완료여야 한다");
    assertThat(managed.getRoles()).extracting(Role::getName).containsExactly("ADMIN");
  }
}
