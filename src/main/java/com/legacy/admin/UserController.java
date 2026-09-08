package com.legacy.admin;
import com.legacy.audit.AuditLogService;
import com.legacy.auth.User;
import com.legacy.auth.Role;
import com.legacy.auth.UserRepository;
import com.legacy.auth.RoleRepository;
import jakarta.servlet.http.HttpServletRequest;

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
  private final AuditLogService auditLogService;

  @Autowired
  public UserController(
      UserRepository userRepository,
      RoleRepository roleRepository,
      PasswordEncoder passwordEncoder,
      AuditLogService auditLogService) {
    this.userRepository = userRepository;
    this.roleRepository = roleRepository;
    this.passwordEncoder = passwordEncoder;
    this.auditLogService = auditLogService;
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

  // 본인 프로필 수정 (표시명, 이메일, 비밀번호)
  @PutMapping("/me")
  @ResponseBody
  public ResponseEntity<?> updateMyProfile(
      @RequestBody Map<String, String> request,
      Authentication authentication) {
    try {
      User principal = (User) authentication.getPrincipal();
      User user = userRepository.findById(principal.getSeq())
          .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

      String displayName  = request.get("displayName");
      String email        = request.get("email");
      String newPassword  = request.get("newPassword");
      String currentPassword = request.get("currentPassword");

      if (displayName != null && !displayName.trim().isEmpty()) {
        user.setDisplayName(displayName.trim());
      }
      if (email != null && !email.trim().isEmpty()) {
        String trimmed = email.trim();
        if (!user.getEmail().equals(trimmed) && userRepository.existsByEmail(trimmed)) {
          return ResponseEntity.badRequest()
              .body(Collections.singletonMap("message", "이미 사용 중인 이메일입니다."));
        }
        user.setEmail(trimmed);
      }
      if (newPassword != null && !newPassword.trim().isEmpty()) {
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
          return ResponseEntity.badRequest()
              .body(Collections.singletonMap("message", "현재 비밀번호가 올바르지 않습니다."));
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
      }

      user.setUpdatedAt(LocalDateTime.now());
      userRepository.save(user);

      Map<String, Object> response = new HashMap<>();
      response.put("message", "프로필이 수정되었습니다.");
      response.put("displayName", user.getDisplayName());
      response.put("email", user.getEmail());

      log.info("[프로필 수정] userId={}", user.getUserId());
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("[프로필 수정 실패]", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "프로필 수정 실패: " + e.getMessage()));
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
      @RequestBody Map<String, Boolean> request, HttpServletRequest httpRequest) {
    try {
      User user = userRepository.findById(userSeq)
          .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
      boolean newActive = Boolean.TRUE.equals(request.get("isActive"));
      user.setActive(newActive);
      user.setUpdatedAt(java.time.LocalDateTime.now());
      userRepository.save(user);
      auditLogService.logAudit("UPDATE", "USER", user.getSeq(), user.getUserId(), "SUCCESS",
          java.util.Map.of("isActive", newActive), newActive ? "계정 활성화" : "계정 비활성화",
          httpRequest.getRemoteAddr());
      log.info("[사용자 활성화 변경] userSeq={}, isActive={}", userSeq, newActive);
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
      @RequestBody Map<String, String> request, HttpServletRequest httpRequest) {
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
        // 역할은 기존과 동일하게 "단일 역할로 교체"하되, Anthropic 사용 권한은 별도 축이므로
        // 보유 중이었다면 보존한다 — 이름만 수정해도 권한이 조용히 사라지던 문제 대응 (TASK-006, 2026-09).
        boolean hadAnthropicAccess = user.getRoles().stream()
            .anyMatch(r -> "ANTHROPIC_USER".equals(r.getName()));
        Set<Role> newRoles = new HashSet<>();
        newRoles.add(role);
        if (hadAnthropicAccess) {
          // 조회 실패 시 조용히 스킵 — 이 부가 조치의 실패가 updateUser 본 기능을 막지 않는다.
          roleRepository.findByName("ANTHROPIC_USER").ifPresent(newRoles::add);
        }
        user.setRoles(newRoles);
      }

      user.setUpdatedAt(LocalDateTime.now());
      userRepository.save(user);
      auditLogService.logAudit("UPDATE", "USER", user.getSeq(), user.getUserId(), "SUCCESS",
          null, "사용자 정보 수정", httpRequest.getRemoteAddr());

      log.info("[사용자 수정] userSeq={}, userId={}", userSeq, user.getUserId());
      return ResponseEntity.ok(Collections.singletonMap("message", "사용자 정보가 수정되었습니다."));
    } catch (Exception e) {
      log.error("[사용자 수정 실패] userSeq={}", userSeq, e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "사용자 수정 실패: " + e.getMessage()));
    }
  }

  // Anthropic(Claude API) 사용 권한 부여/해제 (관리자만) - REQ-002, 2026-09
  // updateUser(PUT /api/users/{userSeq})의 role 처리는 "기존 역할 전체를 단일 역할로 교체"하는 계약이라
  // 여기에 얹으면 기존 USER↔ADMIN 승격/강등 흐름이 깨진다. 그래서 additive 부여/해제는 별도 엔드포인트로 분리한다.
  @PutMapping("/{userSeq}/anthropic-access")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> updateAnthropicAccess(@PathVariable Long userSeq,
      @RequestBody Map<String, Boolean> request, HttpServletRequest httpRequest) {
    try {
      User user = userRepository.findById(userSeq)
          .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
      Role anthropicRole = roleRepository.findByName("ANTHROPIC_USER")
          .orElseThrow(() -> new RuntimeException("ANTHROPIC_USER 역할이 없습니다."));
      boolean grant = Boolean.TRUE.equals(request.get("grant"));

      // 기존 역할 방어적 복사 — 원본 Set을 직접 변형하지 않는다.
      Set<Role> roles = new HashSet<>(user.getRoles());
      if (grant) {
        roles.add(anthropicRole);
      } else {
        roles.remove(anthropicRole);
      }
      user.setRoles(roles);
      user.setUpdatedAt(LocalDateTime.now());
      userRepository.save(user);

      auditLogService.logAudit("UPDATE", "USER", user.getSeq(), user.getUserId(), "SUCCESS",
          Map.of("anthropicAccess", grant), grant ? "Anthropic 권한 부여" : "Anthropic 권한 해제",
          httpRequest.getRemoteAddr());

      log.info("[Anthropic 권한 변경] userSeq={}, grant={}", userSeq, grant);
      return ResponseEntity.ok(Collections.singletonMap("message",
          grant ? "Anthropic 권한이 부여되었습니다." : "Anthropic 권한이 해제되었습니다."));
    } catch (Exception e) {
      log.error("[Anthropic 권한 변경 실패] userSeq={}", userSeq, e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "Anthropic 권한 변경 실패: " + e.getMessage()));
    }
  }

  // 사용자 삭제 (관리자만)
  @DeleteMapping("/{userSeq}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> deleteUser(@PathVariable Long userSeq, HttpServletRequest httpRequest) {
    try {
      User user = userRepository.findById(userSeq)
          .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
      auditLogService.logUserDeletion(user, httpRequest.getRemoteAddr());
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
