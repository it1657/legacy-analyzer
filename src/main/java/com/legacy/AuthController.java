package com.legacy;

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

  @Autowired
  public AuthController(
      AuthenticationManager authenticationManager,
      UserRepository userRepository,
      RoleRepository roleRepository,
      PasswordEncoder passwordEncoder,
      JwtTokenProvider jwtTokenProvider) {
    this.authenticationManager = authenticationManager;
    this.userRepository = userRepository;
    this.roleRepository = roleRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtTokenProvider = jwtTokenProvider;
  }

  // 로그인 페이지 (GET)
  @GetMapping("/login")
  public String loginPage() {
    return "auth/login";
  }

  // 로그인 (POST)
  @PostMapping("/login")
  @ResponseBody
  public ResponseEntity<?> login(@RequestBody AuthRequest authRequest) {
    try {
      Authentication authentication = authenticationManager.authenticate(
          new UsernamePasswordAuthenticationToken(
              authRequest.getUsername(),
              authRequest.getPassword()));

      SecurityContextHolder.getContext().setAuthentication(authentication);

      User user = (User) authentication.getPrincipal();
      String token = jwtTokenProvider.generateToken(authentication);

      log.info("[로그인 성공] username={}", authRequest.getUsername());
      return ResponseEntity.ok(new AuthResponse(token, user.getId(), user.getUsername()));

    } catch (Exception e) {
      log.error("[로그인 실패] username={}, reason={}", authRequest.getUsername(),
          e.getMessage());
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(new AuthResponse("로그인 실패: " + e.getMessage()));
    }
  }

  // 사용자 등록은 관리자 API(/api/admin/users/register)를 통해서만 가능합니다.
}
