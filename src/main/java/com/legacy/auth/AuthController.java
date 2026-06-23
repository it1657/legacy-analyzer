package com.legacy.auth;

import com.legacy.audit.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/auth")
public class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);

  private final AuthenticationManager authenticationManager;
  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenProvider jwtTokenProvider;
  private final AuditLogService auditLogService;

  @Autowired
  public AuthController(
      AuthenticationManager authenticationManager,
      UserRepository userRepository,
      RoleRepository roleRepository,
      PasswordEncoder passwordEncoder,
      JwtTokenProvider jwtTokenProvider,
      AuditLogService auditLogService) {
    this.authenticationManager = authenticationManager;
    this.userRepository = userRepository;
    this.roleRepository = roleRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtTokenProvider = jwtTokenProvider;
    this.auditLogService = auditLogService;
  }

  @GetMapping("/login")
  public String loginPage() {
    return "auth/login";
  }

  @PostMapping("/login")
  @ResponseBody
  public ResponseEntity<?> login(@RequestBody AuthRequest authRequest, HttpServletRequest request) {
    String ip = request.getRemoteAddr();
    try {
      Authentication authentication = authenticationManager.authenticate(
          new UsernamePasswordAuthenticationToken(
              authRequest.getUserId(),
              authRequest.getPassword()));

      SecurityContextHolder.getContext().setAuthentication(authentication);

      User user = (User) authentication.getPrincipal();
      String token = jwtTokenProvider.generateToken(authentication);

      java.util.List<String> roles = user.getRoles().stream()
          .map(role -> role.getName())
          .toList();

      auditLogService.logLogin(authRequest.getUserId(), ip);
      log.info("[로그인 성공] userId={}, roles={}", authRequest.getUserId(), roles);
      return ResponseEntity.ok(new AuthResponse(token, user.getSeq(), user.getUserId(), roles));

    } catch (Exception e) {
      auditLogService.logLoginFailure(authRequest.getUserId(), ip);
      log.error("[로그인 실패] userId={}, reason={}", authRequest.getUserId(), e.getMessage());
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(new AuthResponse("로그인 실패: " + e.getMessage()));
    }
  }
}
