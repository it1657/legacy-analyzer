package com.legacy.admin;

import com.legacy.audit.AuditLogService;
import com.legacy.auth.Role;
import com.legacy.auth.RoleRepository;
import com.legacy.auth.User;
import com.legacy.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserController.getCurrentUser()/updateMyProfile()의 본인 정보 조회·수정을 검증한다.
 * 02-design-v1 5.1절 근거.
 */
class UserControllerSelfServiceTest {

  private UserRepository userRepository;
  private RoleRepository roleRepository;
  private PasswordEncoder passwordEncoder;
  private AuditLogService auditLogService;
  private Authentication authentication;
  private UserController userController;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    roleRepository = mock(RoleRepository.class); // 이 2개 메서드에서 미사용
    passwordEncoder = mock(PasswordEncoder.class);
    auditLogService = mock(AuditLogService.class); // 이 2개 메서드에서 미사용
    authentication = mock(Authentication.class);

    userController = new UserController(userRepository, roleRepository, passwordEncoder, auditLogService);
  }

  // ── getCurrentUser ────────────────────────────────────────────────

  @Test
  void getCurrentUser_정상이면_200과_6개_필드가_전수_일치한다() {
    Role adminRole = AdminTestFixtures.newRole("ADMIN");
    Role userRole = AdminTestFixtures.newRole("USER");
    User user = AdminTestFixtures.newUser(1L, "tester", "tester@example.com", true,
        Set.of(adminRole, userRole));
    when(authentication.getPrincipal()).thenReturn(user);

    ResponseEntity<?> response = userController.getCurrentUser(authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<?, ?> body = (Map<?, ?>) response.getBody();
    assertEquals(1L, body.get("seq"));
    assertEquals("tester", body.get("userId"));
    assertEquals("tester", body.get("displayName")); // displayName 미설정 시 userId fallback (User.getDisplayName())
    assertEquals("tester@example.com", body.get("email"));
    @SuppressWarnings("unchecked")
    java.util.List<String> roles = (java.util.List<String>) body.get("roles");
    assertThat(roles).containsExactlyInAnyOrder("ADMIN", "USER");
    assertEquals(true, body.get("isActive"));
  }

  @Test
  void getCurrentUser_principal이_User가_아니면_401과_고정_메시지를_반환한다() {
    when(authentication.getPrincipal()).thenReturn("not-a-user"); // ClassCastException 유발

    ResponseEntity<?> response = userController.getCurrentUser(authentication);

    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    Map<?, ?> body = (Map<?, ?>) response.getBody();
    // 예외 메시지가 아니라 고정 문자열임을 명시적으로 확인 (ClassCastException 메시지는 포함되지 않는다).
    assertEquals("사용자 정보 조회 실패", body.get("message"));
  }

  // ── updateMyProfile ──────────────────────────────────────────────

  private User newManagedUser() {
    return AdminTestFixtures.newUser(1L, "tester", "tester@example.com", true, Set.of());
  }

  @Test
  void updateMyProfile_대상_사용자가_없으면_400을_반환한다() {
    User principal = newManagedUser();
    when(authentication.getPrincipal()).thenReturn(principal);
    when(userRepository.findById(1L)).thenReturn(Optional.empty());

    ResponseEntity<?> response = userController.updateMyProfile(new HashMap<>(), authentication);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
  }

  @Test
  void updateMyProfile_displayName을_갱신한다() {
    User principal = newManagedUser();
    User managed = newManagedUser();
    when(authentication.getPrincipal()).thenReturn(principal);
    when(userRepository.findById(1L)).thenReturn(Optional.of(managed));

    Map<String, String> request = new HashMap<>();
    request.put("displayName", "새이름");

    ResponseEntity<?> response = userController.updateMyProfile(request, authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("새이름", managed.getDisplayName());
    verify(userRepository, times(1)).save(managed);
  }

  @Test
  void updateMyProfile_displayName이_빈문자열_또는_공백이면_무시된다() {
    for (String displayName : new String[] {"", "   "}) {
      User principal = newManagedUser();
      User managed = newManagedUser();
      when(authentication.getPrincipal()).thenReturn(principal);
      when(userRepository.findById(1L)).thenReturn(Optional.of(managed));

      Map<String, String> request = new HashMap<>();
      request.put("displayName", displayName);

      ResponseEntity<?> response = userController.updateMyProfile(request, authentication);

      assertEquals(HttpStatus.OK, response.getStatusCode());
      assertEquals("tester", managed.getDisplayName()); // 변경되지 않고 userId fallback 그대로
    }
  }

  @Test
  void updateMyProfile_email이_기존과_동일하면_중복_체크를_건너뛰고_그대로_설정한다() {
    User principal = newManagedUser();
    User managed = newManagedUser(); // email == "tester@example.com"
    when(authentication.getPrincipal()).thenReturn(principal);
    when(userRepository.findById(1L)).thenReturn(Optional.of(managed));

    Map<String, String> request = new HashMap<>();
    request.put("email", "tester@example.com");

    ResponseEntity<?> response = userController.updateMyProfile(request, authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("tester@example.com", managed.getEmail());
    verify(userRepository, never()).existsByEmail(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void updateMyProfile_email이_다르고_중복이_없으면_정상_갱신된다() {
    User principal = newManagedUser();
    User managed = newManagedUser();
    when(authentication.getPrincipal()).thenReturn(principal);
    when(userRepository.findById(1L)).thenReturn(Optional.of(managed));
    when(userRepository.existsByEmail("new@example.com")).thenReturn(false);

    Map<String, String> request = new HashMap<>();
    request.put("email", "new@example.com");

    ResponseEntity<?> response = userController.updateMyProfile(request, authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("new@example.com", managed.getEmail());
    verify(userRepository, times(1)).save(managed);
  }

  @Test
  void updateMyProfile_email이_다르고_중복이_있으면_400을_반환하고_save가_호출되지_않는다() {
    User principal = newManagedUser();
    User managed = newManagedUser();
    when(authentication.getPrincipal()).thenReturn(principal);
    when(userRepository.findById(1L)).thenReturn(Optional.of(managed));
    when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

    Map<String, String> request = new HashMap<>();
    request.put("email", "dup@example.com");

    ResponseEntity<?> response = userController.updateMyProfile(request, authentication);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("이미 사용 중인 이메일입니다.", ((Map<?, ?>) response.getBody()).get("message"));
    verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void updateMyProfile_newPassword_제공시_currentPassword가_없으면_400을_반환한다() {
    User principal = newManagedUser();
    User managed = newManagedUser();
    when(authentication.getPrincipal()).thenReturn(principal);
    when(userRepository.findById(1L)).thenReturn(Optional.of(managed));

    Map<String, String> request = new HashMap<>();
    request.put("newPassword", "newpw123");
    // currentPassword 미제공

    ResponseEntity<?> response = userController.updateMyProfile(request, authentication);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("현재 비밀번호가 올바르지 않습니다.", ((Map<?, ?>) response.getBody()).get("message"));
    verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void updateMyProfile_currentPassword가_일치하지_않으면_400을_반환한다() {
    User principal = newManagedUser();
    User managed = newManagedUser();
    when(authentication.getPrincipal()).thenReturn(principal);
    when(userRepository.findById(1L)).thenReturn(Optional.of(managed));
    when(passwordEncoder.matches("wrong", managed.getPasswordHash())).thenReturn(false);

    Map<String, String> request = new HashMap<>();
    request.put("newPassword", "newpw123");
    request.put("currentPassword", "wrong");

    ResponseEntity<?> response = userController.updateMyProfile(request, authentication);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("현재 비밀번호가 올바르지 않습니다.", ((Map<?, ?>) response.getBody()).get("message"));
    verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void updateMyProfile_currentPassword가_일치하면_비밀번호가_갱신된다() {
    User principal = newManagedUser();
    User managed = newManagedUser();
    when(authentication.getPrincipal()).thenReturn(principal);
    when(userRepository.findById(1L)).thenReturn(Optional.of(managed));
    when(passwordEncoder.matches("current", managed.getPasswordHash())).thenReturn(true);
    when(passwordEncoder.encode("newpw123")).thenReturn("encoded-new-pw");

    Map<String, String> request = new HashMap<>();
    request.put("newPassword", "newpw123");
    request.put("currentPassword", "current");

    ResponseEntity<?> response = userController.updateMyProfile(request, authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("encoded-new-pw", managed.getPasswordHash());
    verify(userRepository, times(1)).save(managed);
  }

  @Test
  void updateMyProfile_성공하면_save가_1회_호출되고_응답에_displayName_email이_담긴다() {
    User principal = newManagedUser();
    User managed = newManagedUser();
    when(authentication.getPrincipal()).thenReturn(principal);
    when(userRepository.findById(1L)).thenReturn(Optional.of(managed));
    when(userRepository.existsByEmail("changed@example.com")).thenReturn(false);

    Map<String, String> request = new HashMap<>();
    request.put("displayName", "변경된이름");
    request.put("email", "changed@example.com");

    ResponseEntity<?> response = userController.updateMyProfile(request, authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(userRepository, times(1)).save(managed);
    Map<?, ?> body = (Map<?, ?>) response.getBody();
    assertEquals("변경된이름", body.get("displayName"));
    assertEquals("changed@example.com", body.get("email"));
  }

  @Test
  void updateMyProfile_save가_예외를_던지면_400과_실패_메시지를_반환한다() {
    User principal = newManagedUser();
    User managed = newManagedUser();
    when(authentication.getPrincipal()).thenReturn(principal);
    when(userRepository.findById(1L)).thenReturn(Optional.of(managed));
    when(userRepository.save(managed)).thenThrow(new RuntimeException("DB 오류"));

    Map<String, String> request = new HashMap<>();
    request.put("displayName", "새이름");

    ResponseEntity<?> response = userController.updateMyProfile(request, authentication);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("프로필 수정 실패: DB 오류", ((Map<?, ?>) response.getBody()).get("message"));
  }
}
