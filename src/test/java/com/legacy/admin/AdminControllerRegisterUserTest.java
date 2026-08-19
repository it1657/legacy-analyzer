package com.legacy.admin;

import com.legacy.analysis.AnalysisHistoryRepository;
import com.legacy.audit.AuditLogService;
import com.legacy.auth.Role;
import com.legacy.auth.RoleRepository;
import com.legacy.auth.User;
import com.legacy.auth.UserRepository;
import com.legacy.core.PresentationGeneratorService;
import com.legacy.notification.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentCaptor.forClass;

/**
 * AdminController.registerUser()의 필드 검증 순서/중복 체크/역할 기본값/성공·실패 분기를 검증한다.
 * 02-design-v1 4.1절 근거.
 */
class AdminControllerRegisterUserTest {

  private UserRepository userRepository;
  private RoleRepository roleRepository;
  private PasswordEncoder passwordEncoder;
  private AnalysisHistoryRepository analysisHistoryRepository;
  private PresentationGeneratorService presentationGeneratorService;
  private AuditLogService auditLogService;
  private NotificationService notificationService;
  private HttpServletRequest httpRequest;
  private AdminController adminController;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    roleRepository = mock(RoleRepository.class);
    passwordEncoder = mock(PasswordEncoder.class);
    analysisHistoryRepository = mock(AnalysisHistoryRepository.class); // registerUser에서 미사용
    presentationGeneratorService = mock(PresentationGeneratorService.class); // registerUser에서 미사용
    auditLogService = mock(AuditLogService.class);
    notificationService = mock(NotificationService.class);
    httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");

    adminController = new AdminController(
        userRepository,
        roleRepository,
        passwordEncoder,
        analysisHistoryRepository,
        presentationGeneratorService,
        auditLogService,
        notificationService);
  }

  private Map<String, String> validRequest() {
    Map<String, String> request = new HashMap<>();
    request.put("userId", "newuser");
    request.put("email", "newuser@example.com");
    request.put("password", "pw1234");
    return request;
  }

  @Test
  void userId가_null_또는_공백이면_400과_사용자ID_필요_메시지를_반환한다() {
    for (String userId : new String[] {null, "   "}) {
      Map<String, String> request = validRequest();
      request.put("userId", userId);

      ResponseEntity<?> response = adminController.registerUser(request, httpRequest);

      assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
      assertEquals("사용자 ID가 필요합니다.", ((Map<?, ?>) response.getBody()).get("message"));
    }
  }

  @Test
  void userId가_없으면_email도_없어도_사용자ID_메시지가_우선_반환된다() {
    Map<String, String> request = new HashMap<>();
    // userId/email/password 전부 미제공 — 우선순위상 userId 메시지가 가장 먼저 반환되어야 한다.

    ResponseEntity<?> response = adminController.registerUser(request, httpRequest);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("사용자 ID가 필요합니다.", ((Map<?, ?>) response.getBody()).get("message"));
  }

  @Test
  void email이_null_또는_공백이면_400과_이메일_필요_메시지를_반환한다() {
    for (String email : new String[] {null, "   "}) {
      Map<String, String> request = validRequest();
      request.put("email", email);

      ResponseEntity<?> response = adminController.registerUser(request, httpRequest);

      assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
      assertEquals("이메일이 필요합니다.", ((Map<?, ?>) response.getBody()).get("message"));
    }
  }

  @Test
  void password가_null_또는_공백이면_400과_비밀번호_필요_메시지를_반환한다() {
    for (String password : new String[] {null, "   "}) {
      Map<String, String> request = validRequest();
      request.put("password", password);

      ResponseEntity<?> response = adminController.registerUser(request, httpRequest);

      assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
      assertEquals("비밀번호가 필요합니다.", ((Map<?, ?>) response.getBody()).get("message"));
    }
  }

  @Test
  void existsByUserId가_true이면_400과_사용자ID_중복_메시지를_반환한다() {
    Map<String, String> request = validRequest();
    when(userRepository.existsByUserId("newuser")).thenReturn(true);

    ResponseEntity<?> response = adminController.registerUser(request, httpRequest);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("이미 존재하는 사용자 ID입니다.", ((Map<?, ?>) response.getBody()).get("message"));
  }

  @Test
  void existsByEmail이_true이면_400과_이메일_중복_메시지를_반환한다() {
    Map<String, String> request = validRequest();
    when(userRepository.existsByUserId("newuser")).thenReturn(false);
    when(userRepository.existsByEmail("newuser@example.com")).thenReturn(true);

    ResponseEntity<?> response = adminController.registerUser(request, httpRequest);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("이미 존재하는 이메일입니다.", ((Map<?, ?>) response.getBody()).get("message"));
  }

  @Test
  void 역할이_유효하지_않으면_400과_역할_오류_메시지를_반환한다() {
    Map<String, String> request = validRequest();
    request.put("role", "UNKNOWN");
    when(roleRepository.findByName("UNKNOWN")).thenReturn(Optional.empty());

    ResponseEntity<?> response = adminController.registerUser(request, httpRequest);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("유효하지 않은 역할입니다.", ((Map<?, ?>) response.getBody()).get("message"));
  }

  @Test
  void role_키가_없으면_기본값_USER로_findByName을_호출한다() {
    Map<String, String> request = validRequest();
    // "role" 키 자체를 넣지 않음
    when(roleRepository.findByName("USER")).thenReturn(Optional.of(AdminTestFixtures.newRole("USER")));
    when(passwordEncoder.encode("pw1234")).thenReturn("encoded-pw");

    ResponseEntity<?> response = adminController.registerUser(request, httpRequest);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(roleRepository).findByName("USER");
  }

  @Test
  void 성공하면_displayName_미제공시_userId를_fallback으로_사용한다() {
    Map<String, String> request = validRequest();
    Role userRole = AdminTestFixtures.newRole("USER");
    when(roleRepository.findByName("USER")).thenReturn(Optional.of(userRole));
    when(passwordEncoder.encode("pw1234")).thenReturn("encoded-pw");
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
      User saved = invocation.getArgument(0);
      saved.setSeq(100L);
      return saved;
    });

    ResponseEntity<?> response = adminController.registerUser(request, httpRequest);

    assertEquals(HttpStatus.OK, response.getStatusCode());

    var captor = forClass(User.class);
    verify(userRepository).save(captor.capture());
    User captured = captor.getValue();
    assertEquals("encoded-pw", captured.getPasswordHash());
    assertThat(captured.getRoles()).containsExactly(userRole);
    assertThat(captured.isActive()).isTrue();
    assertEquals("newuser", captured.getDisplayName());

    verify(auditLogService, times(1)).logUserCreation(captured, "127.0.0.1");
    verify(notificationService, times(1)).notifyUserCreation(captured);

    Map<?, ?> body = (Map<?, ?>) response.getBody();
    assertEquals(100L, body.get("seq"));
    assertEquals("newuser", body.get("userId"));
  }

  @Test
  void 성공하면_displayName_제공시_trim된_값을_사용한다() {
    Map<String, String> request = validRequest();
    request.put("displayName", "  홍길동  ");
    Role userRole = AdminTestFixtures.newRole("USER");
    when(roleRepository.findByName("USER")).thenReturn(Optional.of(userRole));
    when(passwordEncoder.encode("pw1234")).thenReturn("encoded-pw");
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
      User saved = invocation.getArgument(0);
      saved.setSeq(101L);
      return saved;
    });

    ResponseEntity<?> response = adminController.registerUser(request, httpRequest);

    assertEquals(HttpStatus.OK, response.getStatusCode());

    var captor = forClass(User.class);
    verify(userRepository).save(captor.capture());
    User captured = captor.getValue();
    assertEquals("encoded-pw", captured.getPasswordHash());
    assertThat(captured.getRoles()).containsExactly(userRole);
    assertThat(captured.isActive()).isTrue();
    assertEquals("홍길동", captured.getDisplayName());

    verify(auditLogService, times(1)).logUserCreation(captured, "127.0.0.1");
    verify(notificationService, times(1)).notifyUserCreation(captured);

    Map<?, ?> body = (Map<?, ?>) response.getBody();
    assertEquals(101L, body.get("seq"));
    assertEquals("newuser", body.get("userId"));
  }

  @Test
  void save가_예외를_던지면_500과_실패_메시지를_반환한다() {
    Map<String, String> request = validRequest();
    when(roleRepository.findByName("USER")).thenReturn(Optional.of(AdminTestFixtures.newRole("USER")));
    when(passwordEncoder.encode("pw1234")).thenReturn("encoded-pw");
    when(userRepository.save(any(User.class))).thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = adminController.registerUser(request, httpRequest);

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertEquals("사용자 등록 실패: DB 오류", ((Map<?, ?>) response.getBody()).get("message"));
    verify(auditLogService, never()).logUserCreation(any(), any());
    verify(notificationService, never()).notifyUserCreation(any());
  }
}
