package com.legacy.admin;
import com.legacy.auth.User;
import com.legacy.auth.Role;
import com.legacy.auth.UserRepository;
import com.legacy.auth.RoleRepository;
import com.legacy.analysis.AnalysisHistory;
import com.legacy.analysis.AnalysisHistoryRepository;
import com.legacy.core.PresentationGeneratorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
  private final PresentationGeneratorService presentationGeneratorService;

  @Autowired
  public AdminController(
      UserRepository userRepository,
      RoleRepository roleRepository,
      PasswordEncoder passwordEncoder,
      AnalysisHistoryRepository analysisHistoryRepository,
      PresentationGeneratorService presentationGeneratorService) {
    this.userRepository = userRepository;
    this.roleRepository = roleRepository;
    this.passwordEncoder = passwordEncoder;
    this.analysisHistoryRepository = analysisHistoryRepository;
    this.presentationGeneratorService = presentationGeneratorService;
  }

  // 사용자 등록 (관리자만)
  @PostMapping("/users/register")
  @ResponseBody
  public ResponseEntity<?> registerUser(@RequestBody Map<String, String> request) {
    try {
      String userId = request.get("userId");
      String displayName = request.get("displayName");
      String email = request.get("email");
      String password = request.get("password");
      String roleStr = request.getOrDefault("role", "USER");

      if (userId == null || userId.trim().isEmpty()) {
        return ResponseEntity.badRequest()
            .body(Collections.singletonMap("message", "사용자 ID가 필요합니다."));
      }
      if (email == null || email.trim().isEmpty()) {
        return ResponseEntity.badRequest()
            .body(Collections.singletonMap("message", "이메일이 필요합니다."));
      }
      if (password == null || password.trim().isEmpty()) {
        return ResponseEntity.badRequest()
            .body(Collections.singletonMap("message", "비밀번호가 필요합니다."));
      }
      if (userRepository.existsByUserId(userId)) {
        return ResponseEntity.badRequest()
            .body(Collections.singletonMap("message", "이미 존재하는 사용자 ID입니다."));
      }
      if (userRepository.existsByEmail(email)) {
        return ResponseEntity.badRequest()
            .body(Collections.singletonMap("message", "이미 존재하는 이메일입니다."));
      }

      Role role = roleRepository.findByName(roleStr).orElse(null);
      if (role == null) {
        return ResponseEntity.badRequest()
            .body(Collections.singletonMap("message", "유효하지 않은 역할입니다."));
      }

      User newUser = new User(userId, email, passwordEncoder.encode(password));
      newUser.setDisplayName(displayName != null && !displayName.trim().isEmpty()
          ? displayName.trim() : userId);
      newUser.setRoles(new HashSet<>(Collections.singleton(role)));
      newUser.setActive(true);
      newUser.setCreatedAt(LocalDateTime.now());
      newUser.setUpdatedAt(LocalDateTime.now());
      userRepository.save(newUser);

      log.info("[사용자 등록] userId={}, email={}, role={}", userId, email, roleStr);

      Map<String, Object> response = new HashMap<>();
      response.put("message", "사용자가 성공적으로 등록되었습니다.");
      response.put("seq", newUser.getSeq());
      response.put("userId", newUser.getUserId());
      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("[사용자 등록 실패]", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Collections.singletonMap("message", "사용자 등록 실패: " + e.getMessage()));
    }
  }

  // 사용자별 분석 히스토리 조회
  @GetMapping("/users/{userSeq}/analysis-history")
  @ResponseBody
  public ResponseEntity<?> getUserAnalysisHistory(@PathVariable Long userSeq) {
    try {
      User user = userRepository.findById(userSeq)
          .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

      List<AnalysisHistory> histories = analysisHistoryRepository
          .findByUserIdOrderByCreatedAtDesc(userSeq);

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
      log.error("[분석 히스토리 조회 실패] userSeq={}", userSeq, e);
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
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", history.getId());
            map.put("userSeq", history.getUserId());
            // userSeq로 사용자 조회 — 없으면 기본값 설정
            userRepository.findById(history.getUserId()).ifPresentOrElse(
                u -> {
                  map.put("userId", u.getUserId());
                  map.put("displayName", u.getDisplayName());
                },
                () -> {
                  map.put("userId", "user_" + history.getUserId());
                  map.put("displayName", null);
                });
            map.put("sessionId", history.getSessionId());
            map.put("sourcePath", history.getSourcePath());
            map.put("outputPath", history.getOutputPath());
            map.put("totalFiles", history.getTotalFiles());
            map.put("successCount", history.getSuccessCount());
            map.put("skipCount", history.getSkipCount());
            map.put("failureCount", history.getFailureCount());
            map.put("processingTimeMs", history.getProcessingTimeMs());
            map.put("status", history.getStatus());
            map.put("modelName", history.getModelName());
            map.put("inputTokens", history.getInputTokens());
            map.put("outputTokens", history.getOutputTokens());
            map.put("estimatedCost", history.getEstimatedCost());
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

  // 분석 이력 필터 조회 (사용자, 상태, 날짜 범위)
  @GetMapping("/analysis-history/filter")
  @ResponseBody
  public ResponseEntity<?> filterAnalysisHistory(
      @RequestParam(required = false) Long userSeq,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String startDate,
      @RequestParam(required = false) String endDate) {
    try {
      List<AnalysisHistory> histories = analysisHistoryRepository.findAll();

      if (userSeq != null) {
        histories = histories.stream()
            .filter(h -> userSeq.equals(h.getUserId()))
            .toList();
      }
      if (status != null && !status.isBlank()) {
        histories = histories.stream()
            .filter(h -> status.equals(h.getStatus()))
            .toList();
      }
      if (startDate != null && !startDate.isBlank()) {
        LocalDateTime start = LocalDate.parse(startDate).atStartOfDay();
        histories = histories.stream()
            .filter(h -> h.getCreatedAt() != null && !h.getCreatedAt().isBefore(start))
            .toList();
      }
      if (endDate != null && !endDate.isBlank()) {
        LocalDateTime end = LocalDate.parse(endDate).plusDays(1).atStartOfDay();
        histories = histories.stream()
            .filter(h -> h.getCreatedAt() != null && h.getCreatedAt().isBefore(end))
            .toList();
      }

      List<Map<String, Object>> response = histories.stream()
          .sorted(Comparator.comparing(AnalysisHistory::getCreatedAt,
              Comparator.nullsLast(Comparator.reverseOrder())))
          .map(history -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", history.getId());
            map.put("userSeq", history.getUserId());
            userRepository.findById(history.getUserId()).ifPresentOrElse(
                u -> { map.put("userId", u.getUserId()); map.put("displayName", u.getDisplayName()); },
                () -> { map.put("userId", "user_" + history.getUserId()); map.put("displayName", null); });
            map.put("sourcePath", history.getSourcePath());
            map.put("totalFiles", history.getTotalFiles());
            map.put("successCount", history.getSuccessCount());
            map.put("failureCount", history.getFailureCount());
            map.put("processingTimeMs", history.getProcessingTimeMs());
            map.put("status", history.getStatus());
            map.put("createdAt", history.getCreatedAt());
            return map;
          })
          .toList();

      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("[분석 이력 필터 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Collections.singletonMap("message", "조회 실패: " + e.getMessage()));
    }
  }

  // 분석 이력 단건 삭제
  @DeleteMapping("/analysis-history/{historyId}")
  @ResponseBody
  public ResponseEntity<?> deleteAnalysisHistory(@PathVariable Long historyId) {
    try {
      if (!analysisHistoryRepository.existsById(historyId)) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Collections.singletonMap("message", "이력을 찾을 수 없습니다."));
      }
      analysisHistoryRepository.deleteById(historyId);
      log.info("[분석 이력 삭제] historyId={}", historyId);
      return ResponseEntity.ok(Collections.singletonMap("message", "삭제되었습니다."));
    } catch (Exception e) {
      log.error("[분석 이력 삭제 실패] historyId={}", historyId, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Collections.singletonMap("message", "삭제 실패: " + e.getMessage()));
    }
  }

  // 분석 이력 ID 기반 PPT 다운로드
  @GetMapping("/download/presentation/{historyId}")
  public ResponseEntity<byte[]> downloadPresentation(@PathVariable Long historyId) {
    try {
      AnalysisHistory history = analysisHistoryRepository.findById(historyId).orElse(null);
      if (history == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

      byte[] pptxContent = presentationGeneratorService.generateAnalysisResultPresentation(history);

      String projectName = history.getSourcePath() != null
          ? history.getSourcePath().replaceAll(".*[/\\\\]", "") : "analysis";

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(new MediaType("application",
          "vnd.openxmlformats-officedocument.presentationml.presentation"));
      headers.setContentLength(pptxContent.length);
      String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
      headers.setContentDispositionFormData("attachment",
          String.format("analysis_%s_%s.pptx", projectName, timestamp));

      return new ResponseEntity<>(pptxContent, headers, HttpStatus.OK);
    } catch (Exception e) {
      log.error("[PPT 다운로드 실패] historyId={}", historyId, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }
}
