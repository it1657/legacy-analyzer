package com.legacy.auth;

import com.legacy.audit.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

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
 * AuthController.loginPage()/login()의 로그인 뷰 반환과 로그인 성공/실패 분기를 검증한다.
 * UserRepository/RoleRepository/PasswordEncoder는 login() 로직에서 실제로 사용되지 않으므로
 * (프로덕션 코드 확인 완료) mock만 생성하고 상호작용 검증은 하지 않는다.
 */
class AuthControllerTest {

  private AuthenticationManager authenticationManager;
  private UserRepository userRepository;
  private RoleRepository roleRepository;
  private PasswordEncoder passwordEncoder;
  private JwtTokenProvider jwtTokenProvider;
  private AuditLogService auditLogService;
  private HttpServletRequest request;
  private AuthController authController;

  @BeforeEach
  void setUp() {
    SecurityContextHolder.clearContext();
    authenticationManager = mock(AuthenticationManager.class);
    userRepository = mock(UserRepository.class); // AuthController.login()에서 미사용
    roleRepository = mock(RoleRepository.class); // AuthController.login()에서 미사용
    passwordEncoder = mock(PasswordEncoder.class); // AuthController.login()에서 미사용
    jwtTokenProvider = mock(JwtTokenProvider.class);
    auditLogService = mock(AuditLogService.class);
    request = mock(HttpServletRequest.class);
    authController = new AuthController(
        authenticationManager,
        userRepository,
        roleRepository,
        passwordEncoder,
        jwtTokenProvider,
        auditLogService);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void loginPage는_auth_login_뷰_이름을_반환한다() {
    assertEquals("auth/login", authController.loginPage());
  }

  @Test
  void login_성공하면_200과_토큰_역할목록을_반환하고_로그인_성공_감사로그를_남긴다() {
    when(request.getRemoteAddr()).thenReturn("127.0.0.1");

    Role adminRole = AuthTestFixtures.newRole("ADMIN");
    Role userRole = AuthTestFixtures.newRole("USER");
    User user = AuthTestFixtures.newUser(1L, "tester", "tester@example.com", true, Set.of(adminRole, userRole));
    Authentication authentication = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());

    when(authenticationManager.authenticate(any())).thenReturn(authentication);
    when(jwtTokenProvider.generateToken(authentication)).thenReturn("issued-token");

    AuthRequest authRequest = new AuthRequest("tester", null, "password");

    ResponseEntity<?> response = authController.login(authRequest, request);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    AuthResponse body = (AuthResponse) response.getBody();
    assertEquals("issued-token", body.getToken());
    assertEquals(1L, body.getSeq());
    assertEquals("tester", body.getUserId());
    assertThat(body.getRoles()).containsExactlyInAnyOrder("ADMIN", "USER");

    verify(auditLogService, times(1)).logLogin("tester", "127.0.0.1");
    verify(auditLogService, never()).logLoginFailure(any(), any());
  }

  @Test
  void login_실패하면_401과_실패_메시지를_반환하고_로그인_실패_감사로그를_남긴다() {
    when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    when(authenticationManager.authenticate(any()))
        .thenThrow(new BadCredentialsException("자격 증명이 올바르지 않습니다"));

    AuthRequest authRequest = new AuthRequest("tester", null, "wrong-password");

    ResponseEntity<?> response = authController.login(authRequest, request);

    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    AuthResponse body = (AuthResponse) response.getBody();
    assertEquals("로그인 실패: 자격 증명이 올바르지 않습니다", body.getMessage());

    verify(auditLogService, times(1)).logLoginFailure("tester", "127.0.0.1");
    verify(auditLogService, never()).logLogin(any(), any());
  }
}
