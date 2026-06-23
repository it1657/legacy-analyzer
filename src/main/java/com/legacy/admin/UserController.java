package com.legacy.admin;
import com.legacy.auth.User;
import com.legacy.auth.Role;
import com.legacy.auth.UserRepository;
import com.legacy.auth.RoleRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

  private static final Logger log = LoggerFactory.getLogger(UserController.class);

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final PasswordEncoder passwordEncoder;

  @Autowired
  public UserController(
      UserRepository userRepository,
      RoleRepository roleRepository,
      PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.roleRepository = roleRepository;
    this.passwordEncoder = passwordEncoder;
  }

  // 현재 사용자 정보 조회
  @GetMapping("/me")
  @ResponseBody
  public ResponseEntity<?> getCurrentUser(Authentication authentication) {
    try {
      User user = (User) authentication.getPrincipal();
      Map<String, Object> response = new HashMap<>();
      response.put("seq", user.getSeq());
      response.put("userId", user.getUserId());
      response.put("displayName", user.getDisplayName());
      response.put("email", user.getEmail());
      response.put("roles", user.getRoles().stream().map(Role::getName).toList());
      response.put("isActive", user.isActive());
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("[사용자 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Collections.singletonMap("message", "사용자 정보 조회 실패"));
    }
  }

  // 모든 사용자 조회 (관리자만)
  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> getAllUsers() {
    try {
      List<Map<String, Object>> users = userRepository.findAll().stream()
          .map(user -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("seq", user.getSeq());
            map.put("userId", user.getUserId());
            map.put("displayName", user.getDisplayName());
            map.put("email", user.getEmail());
            map.put("roles", user.getRoles().stream().map(Role::getName).toList());
            map.put("isActive", user.isActive());
            map.put("createdAt", user.getCreatedAt());
            return map;
          })
          .toList();
      return ResponseEntity.ok(users);
    } catch (Exception e) {
      log.error("[사용자 목록 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Collections.singletonMap("message", "사용자 목록 조회 실패"));
    }
  }

  // 사용자 활성/비활성화 (관리자만)
  @PutMapping("/{userSeq}/activate")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> toggleUserStatus(@PathVariable Long userSeq,
      @RequestBody Map<String, Boolean> request) {
    try {
      User user = userRepository.findById(userSeq)
          .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
      user.setActive(request.get("isActive"));
      user.setUpdatedAt(java.time.LocalDateTime.now());
      userRepository.save(user);
      log.info("[사용자 활성화 변경] userSeq={}, isActive={}", userSeq, request.get("isActive"));
      return ResponseEntity.ok(Collections.singletonMap("message", "사용자 상태가 변경되었습니다."));
    } catch (Exception e) {
      log.error("[사용자 활성화 변경 실패] userSeq={}", userSeq, e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "사용자 상태 변경 실패: " + e.getMessage()));
    }
  }

  // 사용자 정보 수정 (관리자만) - 표시명, 이메일, 비밀번호, 역할 변경 가능
  @PutMapping("/{userSeq}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> updateUser(@PathVariable Long userSeq,
      @RequestBody Map<String, String> request) {
    try {
      User user = userRepository.findById(userSeq)
          .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

      String displayName = request.get("displayName");
      String email = request.get("email");
      String password = request.get("password");
      String roleStr = request.get("role");

      if (displayName != null && !displayName.trim().isEmpty()) {
        user.setDisplayName(displayName.trim());
      }
      if (email != null && !email.trim().isEmpty()) {
        if (!user.getEmail().equals(email.trim()) && userRepository.existsByEmail(email.trim())) {
          return ResponseEntity.badRequest()
              .body(Collections.singletonMap("message", "이미 존재하는 이메일입니다."));
        }
        user.setEmail(email.trim());
      }
      if (password != null && !password.trim().isEmpty()) {
        user.setPasswordHash(passwordEncoder.encode(password));
      }
      if (roleStr != null && !roleStr.trim().isEmpty()) {
        Role role = roleRepository.findByName(roleStr).orElse(null);
        if (role == null) {
          return ResponseEntity.badRequest()
              .body(Collections.singletonMap("message", "유효하지 않은 역할입니다."));
        }
        user.setRoles(new HashSet<>(Collections.singleton(role)));
      }

      user.setUpdatedAt(LocalDateTime.now());
      userRepository.save(user);

      log.info("[사용자 수정] userSeq={}, userId={}", userSeq, user.getUserId());
      return ResponseEntity.ok(Collections.singletonMap("message", "사용자 정보가 수정되었습니다."));
    } catch (Exception e) {
      log.error("[사용자 수정 실패] userSeq={}", userSeq, e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "사용자 수정 실패: " + e.getMessage()));
    }
  }

  // 사용자 삭제 (관리자만)
  @DeleteMapping("/{userSeq}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> deleteUser(@PathVariable Long userSeq) {
    try {
      User user = userRepository.findById(userSeq)
          .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
      user.getRoles().clear();
      userRepository.save(user);
      userRepository.delete(user);
      log.info("[사용자 삭제] userSeq={}", userSeq);
      return ResponseEntity.ok(Collections.singletonMap("message", "사용자가 삭제되었습니다."));
    } catch (Exception e) {
      log.error("[사용자 삭제 실패] userSeq={}", userSeq, e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "사용자 삭제 실패: " + e.getMessage()));
    }
  }
}
