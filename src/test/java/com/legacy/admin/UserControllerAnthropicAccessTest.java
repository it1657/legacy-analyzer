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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserController.updateAnthropicAccess()(PUT /api/users/{userSeq}/anthropic-access)를 검증한다.
 * REQ-002(2026-09-anthropic-access-control), 02-design-v1 2절 근거.
 *
 * <p>기존 updateUser()의 role 처리("기존 역할 전체를 단일 역할로 교체")와 달리 이 엔드포인트는
 * ANTHROPIC_USER 역할만 additive로 더하고 빼는 것이 계약이므로, 모든 케이스에서 기존 역할(USER 등)이
 * 그대로 남아 있는지를 함께 확인한다.
 *
 * <p>mock 구성은 {@code UserControllerAdminManagementTest}와 동일한 패턴을 그대로 재사용한다.
 */
class UserControllerAnthropicAccessTest {

  private UserRepository userRepository;
  private RoleRepository roleRepository;
  private PasswordEncoder passwordEncoder;
  private AuditLogService auditLogService;
  private HttpServletRequest httpRequest;
  private UserController userController;

  private Role userRole;
  private Role anthropicRole;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    roleRepository = mock(RoleRepository.class);
    passwordEncoder = mock(PasswordEncoder.class);
    auditLogService = mock(AuditLogService.class);
    httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");

    userController = new UserController(userRepository, roleRepository, passwordEncoder, auditLogService);

    userRole = AdminTestFixtures.newRole("USER");
    anthropicRole = AdminTestFixtures.newRole("ANTHROPIC_USER");
  }

  /** 기존 역할로 USER만 가진 대상 사용자를 만든다. */
  private User newTargetUser(Set<Role> roles) {
    return AdminTestFixtures.newUser(1L, "u1", "u1@example.com", true, roles);
  }

  private Map<String, Boolean> body(boolean grant) {
    Map<String, Boolean> request = new HashMap<>();
    request.put("grant", grant);
    return request;
  }

  @Test
  void grant가_true이면_ANTHROPIC_USER가_추가되고_기존_역할은_유지된다() {
    User target = newTargetUser(new HashSet<>(Set.of(userRole)));
    when(userRepository.findById(1L)).thenReturn(Optional.of(target));
    when(roleRepository.findByName("ANTHROPIC_USER")).thenReturn(Optional.of(anthropicRole));

    ResponseEntity<?> response = userController.updateAnthropicAccess(1L, body(true), httpRequest);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("Anthropic 권한이 부여되었습니다.", ((Map<?, ?>) response.getBody()).get("message"));
    // 교체가 아니라 추가 — 기존 USER 역할이 그대로 남아 있어야 한다.
    assertThat(target.getRoles()).containsExactlyInAnyOrder(userRole, anthropicRole);
    verify(userRepository).save(target);
  }

  @Test
  void grant가_false이면_ANTHROPIC_USER만_제거되고_기존_역할은_유지된다() {
    User target = newTargetUser(new LinkedHashSet<>(Set.of(userRole, anthropicRole)));
    when(userRepository.findById(1L)).thenReturn(Optional.of(target));
    when(roleRepository.findByName("ANTHROPIC_USER")).thenReturn(Optional.of(anthropicRole));

    ResponseEntity<?> response = userController.updateAnthropicAccess(1L, body(false), httpRequest);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("Anthropic 권한이 해제되었습니다.", ((Map<?, ?>) response.getBody()).get("message"));
    // 해제해도 기존 USER 역할까지 날아가면 안 된다(계정이 권한 없는 상태가 되어버림).
    assertThat(target.getRoles()).containsExactly(userRole);
    verify(userRepository).save(target);
  }

  @Test
  void 대상_사용자가_없으면_400을_반환하고_저장하지_않는다() {
    when(userRepository.findById(99L)).thenReturn(Optional.empty());

    ResponseEntity<?> response = userController.updateAnthropicAccess(99L, body(true), httpRequest);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertThat(((Map<?, ?>) response.getBody()).get("message").toString())
        .contains("사용자를 찾을 수 없습니다.");
    verify(userRepository, never()).save(any());
    verify(auditLogService, never()).logAudit(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void ANTHROPIC_USER_역할이_DB에_없으면_400을_반환하고_저장하지_않는다() {
    User target = newTargetUser(new HashSet<>(Set.of(userRole)));
    when(userRepository.findById(1L)).thenReturn(Optional.of(target));
    when(roleRepository.findByName("ANTHROPIC_USER")).thenReturn(Optional.empty());

    ResponseEntity<?> response = userController.updateAnthropicAccess(1L, body(true), httpRequest);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertThat(((Map<?, ?>) response.getBody()).get("message").toString())
        .contains("ANTHROPIC_USER 역할이 없습니다.");
    // 역할 조회 실패 시 사용자 역할이 변형되면 안 된다.
    assertThat(target.getRoles()).containsExactly(userRole);
    verify(userRepository, never()).save(any());
  }

  @Test
  void 이미_부여된_상태에서_grant를_true로_재호출해도_중복되지_않는다() {
    User target = newTargetUser(new LinkedHashSet<>(Set.of(userRole, anthropicRole)));
    when(userRepository.findById(1L)).thenReturn(Optional.of(target));
    when(roleRepository.findByName("ANTHROPIC_USER")).thenReturn(Optional.of(anthropicRole));

    ResponseEntity<?> response = userController.updateAnthropicAccess(1L, body(true), httpRequest);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    // 멱등 — Set 크기가 2 그대로여야 한다.
    assertEquals(2, target.getRoles().size());
    assertThat(target.getRoles()).containsExactlyInAnyOrder(userRole, anthropicRole);
  }

  @Test
  void 감사로그가_anthropicAccess_변경_내역과_함께_기록된다() {
    User target = newTargetUser(new HashSet<>(Set.of(userRole)));
    when(userRepository.findById(1L)).thenReturn(Optional.of(target));
    when(roleRepository.findByName("ANTHROPIC_USER")).thenReturn(Optional.of(anthropicRole));

    userController.updateAnthropicAccess(1L, body(true), httpRequest);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> changesCaptor = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<String> detailsCaptor = ArgumentCaptor.forClass(String.class);
    verify(auditLogService).logAudit(org.mockito.ArgumentMatchers.eq("UPDATE"),
        org.mockito.ArgumentMatchers.eq("USER"), org.mockito.ArgumentMatchers.eq(1L),
        org.mockito.ArgumentMatchers.eq("u1"), org.mockito.ArgumentMatchers.eq("SUCCESS"),
        changesCaptor.capture(), detailsCaptor.capture(), org.mockito.ArgumentMatchers.eq("127.0.0.1"));

    assertThat(changesCaptor.getValue()).containsEntry("anthropicAccess", true);
    assertEquals("Anthropic 권한 부여", detailsCaptor.getValue());
  }

  @Test
  void 감사로그_details는_해제일_때_해제_문구로_기록된다() {
    User target = newTargetUser(new LinkedHashSet<>(Set.of(userRole, anthropicRole)));
    when(userRepository.findById(1L)).thenReturn(Optional.of(target));
    when(roleRepository.findByName("ANTHROPIC_USER")).thenReturn(Optional.of(anthropicRole));

    userController.updateAnthropicAccess(1L, body(false), httpRequest);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> changesCaptor = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<String> detailsCaptor = ArgumentCaptor.forClass(String.class);
    verify(auditLogService).logAudit(org.mockito.ArgumentMatchers.eq("UPDATE"),
        org.mockito.ArgumentMatchers.eq("USER"), org.mockito.ArgumentMatchers.eq(1L),
        org.mockito.ArgumentMatchers.eq("u1"), org.mockito.ArgumentMatchers.eq("SUCCESS"),
        changesCaptor.capture(), detailsCaptor.capture(), org.mockito.ArgumentMatchers.eq("127.0.0.1"));

    assertThat(changesCaptor.getValue()).containsEntry("anthropicAccess", false);
    assertEquals("Anthropic 권한 해제", detailsCaptor.getValue());
  }

  // ---------- TASK-006(2026-09): Role 인스턴스가 서로 달라도 동작해야 한다 ----------
  // 프로덕션에서는 OSIV 덕에 같은 인스턴스가 오지만, 그 전제가 깨지면 조용히 실패하던 경로다.
  // 아래 2건은 사용자 Set 안의 ANTHROPIC_USER와 repository가 돌려주는 ANTHROPIC_USER를
  // 의도적으로 서로 다른 인스턴스로 구성한다(Role.equals가 없으면 실패하는 회귀 재현 테스트).

  @Test
  void grant가_false일때_Role_인스턴스가_달라도_ANTHROPIC_USER가_제거된다() {
    Role anthropicInUserSet = AdminTestFixtures.newRole("ANTHROPIC_USER");
    Role anthropicFromRepository = AdminTestFixtures.newRole("ANTHROPIC_USER");
    assertNotSame(anthropicInUserSet, anthropicFromRepository, "서로 다른 인스턴스를 전제로 한다");

    User target = newTargetUser(new LinkedHashSet<>(Set.of(userRole, anthropicInUserSet)));
    when(userRepository.findById(1L)).thenReturn(Optional.of(target));
    when(roleRepository.findByName("ANTHROPIC_USER")).thenReturn(Optional.of(anthropicFromRepository));

    ResponseEntity<?> response = userController.updateAnthropicAccess(1L, body(false), httpRequest);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertThat(target.getRoles()).containsExactly(userRole);
  }

  @Test
  void 이미_보유한_상태에서_Role_인스턴스가_달라도_grant_true는_중복_추가하지_않는다() {
    Role anthropicInUserSet = AdminTestFixtures.newRole("ANTHROPIC_USER");
    Role anthropicFromRepository = AdminTestFixtures.newRole("ANTHROPIC_USER");
    assertNotSame(anthropicInUserSet, anthropicFromRepository, "서로 다른 인스턴스를 전제로 한다");

    User target = newTargetUser(new LinkedHashSet<>(Set.of(userRole, anthropicInUserSet)));
    when(userRepository.findById(1L)).thenReturn(Optional.of(target));
    when(roleRepository.findByName("ANTHROPIC_USER")).thenReturn(Optional.of(anthropicFromRepository));

    ResponseEntity<?> response = userController.updateAnthropicAccess(1L, body(true), httpRequest);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(2, target.getRoles().size(), "인스턴스가 달라도 같은 역할이 두 번 쌓이면 안 된다");
  }
}
