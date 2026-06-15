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
      response.put("id", user.getId());
      response.put("username", user.getUsername());
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
            Map<String, Object> map = new HashMap<>();
            map.put("id", user.getId());
            map.put("username", user.getUsername());
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
  @PutMapping("/{userId}/activate")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> toggleUserStatus(@PathVariable Long userId, @RequestBody Map<String, Boolean> request) {
    try {
      User user = userRepository.findById(userId)
          .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

      user.setActive(request.get("isActive"));
      user.setUpdatedAt(java.time.LocalDateTime.now());
      userRepository.save(user);

      log.info("[사용자 활성화 변경] userId={}, isActive={}", userId, request.get("isActive"));
      return ResponseEntity.ok(Collections.singletonMap("message", "사용자 상태가 변경되었습니다."));
    } catch (Exception e) {
      log.error("[사용자 활성화 변경 실패] userId={}", userId, e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "사용자 상태 변경 실패: " + e.getMessage()));
    }
  }

  // 사용자 삭제 (관리자만)
  @DeleteMapping("/{userId}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> deleteUser(@PathVariable Long userId) {
    try {
      userRepository.deleteById(userId);
      log.info("[사용자 삭제] userId={}", userId);
      return ResponseEntity.ok(Collections.singletonMap("message", "사용자가 삭제되었습니다."));
    } catch (Exception e) {
      log.error("[사용자 삭제 실패] userId={}", userId, e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "사용자 삭제 실패: " + e.getMessage()));
    }
  }
}
