package com.legacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

  private static final Logger log = LoggerFactory.getLogger(AdminController.class);

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final PasswordEncoder passwordEncoder;
  private final AnalysisHistoryRepository analysisHistoryRepository;

  @Autowired
  public AdminController(
      UserRepository userRepository,
      RoleRepository roleRepository,
      PasswordEncoder passwordEncoder,
      AnalysisHistoryRepository analysisHistoryRepository) {
    this.userRepository = userRepository;
    this.roleRepository = roleRepository;
    this.passwordEncoder = passwordEncoder;
    this.analysisHistoryRepository = analysisHistoryRepository;
  }

  // 사용자 등록 (관리자만)
  @PostMapping("/users/register")
  @ResponseBody
  public ResponseEntity<?> registerUser(@RequestBody Map<String, String> request) {
    try {
      String username = request.get("username");
      String email = request.get("email");
      String password = request.get("password");
      String roleStr = request.getOrDefault("role", "USER");

      // 유효성 검증
      if (username == null || username.trim().isEmpty()) {
        return ResponseEntity.badRequest()
            .body(Collections.singletonMap("message", "사용자명이 필요합니다."));
      }

      if (email == null || email.trim().isEmpty()) {
        return ResponseEntity.badRequest()
            .body(Collections.singletonMap("message", "이메일이 필요합니다."));
      }

      if (password == null || password.trim().isEmpty()) {
        return ResponseEntity.badRequest()
            .body(Collections.singletonMap("message", "비밀번호가 필요합니다."));
      }

      // 사용자명/이메일 중복 확인
      if (userRepository.existsByUsername(username)) {
        return ResponseEntity.badRequest()
            .body(Collections.singletonMap("message", "이미 존재하는 사용자명입니다."));
      }

      if (userRepository.existsByEmail(email)) {
        return ResponseEntity.badRequest()
            .body(Collections.singletonMap("message", "이미 존재하는 이메일입니다."));
      }

      // 역할 조회
      Role role = roleRepository.findByName(roleStr)
          .orElse(null);
      if (role == null) {
        return ResponseEntity.badRequest()
            .body(Collections.singletonMap("message", "유효하지 않은 역할입니다."));
      }

      // 사용자 생성
      User newUser = new User(username, email, passwordEncoder.encode(password));
      newUser.setRoles(new HashSet<>(Collections.singleton(role)));
      newUser.setActive(true);
      newUser.setCreatedAt(LocalDateTime.now());
      newUser.setUpdatedAt(LocalDateTime.now());

      userRepository.save(newUser);
      log.info("[사용자 등록] username={}, email={}, role={}", username, email, roleStr);

      Map<String, Object> response = new HashMap<>();
      response.put("message", "사용자가 성공적으로 등록되었습니다.");
      response.put("userId", newUser.getId());
      response.put("username", newUser.getUsername());
      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("[사용자 등록 실패]", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Collections.singletonMap("message", "사용자 등록 실패: " + e.getMessage()));
    }
  }

  // 사용자별 분석 히스토리 조회
  @GetMapping("/users/{userId}/analysis-history")
  @ResponseBody
  public ResponseEntity<?> getUserAnalysisHistory(@PathVariable Long userId) {
    try {
      User user = userRepository.findById(userId)
          .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

      List<AnalysisHistory> histories = analysisHistoryRepository
          .findByUserIdOrderByCreatedAtDesc(userId);

      List<Map<String, Object>> response = histories.stream()
          .map(history -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", history.getId());
            map.put("sessionId", history.getSessionId());
            map.put("sourcePath", history.getSourcePath());
            map.put("outputPath", history.getOutputPath());
            map.put("totalFiles", history.getTotalFiles());
            map.put("successCount", history.getSuccessCount());
            map.put("skipCount", history.getSkipCount());
            map.put("failureCount", history.getFailureCount());
            map.put("processingTimeMs", history.getProcessingTimeMs());
            map.put("status", history.getStatus());
            map.put("createdAt", history.getCreatedAt());
            map.put("completedAt", history.getCompletedAt());
            return map;
          })
          .toList();

      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("[분석 히스토리 조회 실패] userId={}", userId, e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "분석 히스토리 조회 실패: " + e.getMessage()));
    }
  }

  // 전체 분석 히스토리 조회
  @GetMapping("/analysis-history")
  @ResponseBody
  public ResponseEntity<?> getAllAnalysisHistory() {
    try {
      List<AnalysisHistory> histories = analysisHistoryRepository.findAll();

      List<Map<String, Object>> response = histories.stream()
          .map(history -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", history.getId());
            map.put("userId", history.getUserId());
            map.put("sessionId", history.getSessionId());
            map.put("sourcePath", history.getSourcePath());
            map.put("outputPath", history.getOutputPath());
            map.put("totalFiles", history.getTotalFiles());
            map.put("successCount", history.getSuccessCount());
            map.put("skipCount", history.getSkipCount());
            map.put("failureCount", history.getFailureCount());
            map.put("processingTimeMs", history.getProcessingTimeMs());
            map.put("status", history.getStatus());
            map.put("createdAt", history.getCreatedAt());
            map.put("completedAt", history.getCompletedAt());
            return map;
          })
          .toList();

      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("[전체 분석 히스토리 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Collections.singletonMap("message", "분석 히스토리 조회 실패: " + e.getMessage()));
    }
  }
}
