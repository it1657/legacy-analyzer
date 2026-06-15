package com.legacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class AuditLogService {

  private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

  private final AuditLogRepository auditLogRepository;
  private final ObjectMapper objectMapper;

  @Autowired
  public AuditLogService(AuditLogRepository auditLogRepository) {
    this.auditLogRepository = auditLogRepository;
    this.objectMapper = new ObjectMapper();
  }

  // 감사 로그 기록
  public void logAudit(String action, String target, Long targetId, String targetName,
      String status, Map<String, Object> changes, String details, String ipAddress) {
    try {
      Long userId = null;
      String username = "SYSTEM";

      // 현재 인증된 사용자 정보 추출
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication != null && authentication.getPrincipal() instanceof User) {
        User user = (User) authentication.getPrincipal();
        userId = user.getId();
        username = user.getUsername();
      }

      // 변경사항을 JSON으로 변환
      String changesJson = changes != null ? objectMapper.writeValueAsString(changes) : null;

      AuditLog auditLog = new AuditLog(userId, username, action, target, targetId, targetName,
          status, ipAddress);
      auditLog.setChanges(changesJson);
      auditLog.setDetails(details);

      auditLogRepository.save(auditLog);

      log.debug(
          "[감사 로그] action={}, target={}, targetId={}, user={}, status={}, ip={}",
          action, target, targetId, username, status, ipAddress);
    } catch (Exception e) {
      log.error("[감사 로그 기록 실패]", e);
    }
  }

  // 간단한 버전 (changes 없이)
  public void logAudit(String action, String target, Long targetId, String targetName,
      String status, String ipAddress) {
    logAudit(action, target, targetId, targetName, status, null, null, ipAddress);
  }

  // 사용자 생성 로그
  public void logUserCreation(User user, String ipAddress) {
    Map<String, Object> changes = new HashMap<>();
    changes.put("username", user.getUsername());
    changes.put("email", user.getEmail());
    changes.put("roles", user.getRoles().stream().map(Role::getName).toList());

    logAudit("CREATE", "USER", user.getId(), user.getUsername(), "SUCCESS", changes,
        "새 사용자 생성됨", ipAddress);
  }

  // 사용자 수정 로그
  public void logUserModification(User oldUser, User newUser, String ipAddress) {
    Map<String, Object> changes = new HashMap<>();

    if (!oldUser.getUsername().equals(newUser.getUsername())) {
      changes.put("username", Map.of("old", oldUser.getUsername(), "new", newUser.getUsername()));
    }

    if (!oldUser.getEmail().equals(newUser.getEmail())) {
      changes.put("email", Map.of("old", oldUser.getEmail(), "new", newUser.getEmail()));
    }

    if (oldUser.isActive() != newUser.isActive()) {
      changes.put("isActive", Map.of("old", oldUser.isActive(), "new", newUser.isActive()));
    }

    if (!changes.isEmpty()) {
      logAudit("UPDATE", "USER", newUser.getId(), newUser.getUsername(), "SUCCESS", changes,
          "사용자 정보 수정됨", ipAddress);
    }
  }

  // 사용자 삭제 로그
  public void logUserDeletion(User user, String ipAddress) {
    Map<String, Object> changes = new HashMap<>();
    changes.put("username", user.getUsername());
    changes.put("email", user.getEmail());

    logAudit("DELETE", "USER", user.getId(), user.getUsername(), "SUCCESS", changes,
        "사용자 삭제됨", ipAddress);
  }

  // 분석 완료 로그
  public void logAnalysisCompletion(AnalysisHistory analysis, String ipAddress) {
    Map<String, Object> changes = new HashMap<>();
    changes.put("totalFiles", analysis.getTotalFiles());
    changes.put("successCount", analysis.getSuccessCount());
    changes.put("failureCount", analysis.getFailureCount());
    changes.put("processingTimeMs", analysis.getProcessingTimeMs());

    logAudit("COMPLETED", "ANALYSIS", analysis.getId(), analysis.getSourcePath(), "SUCCESS",
        changes, "코드 분석 완료", ipAddress);
  }

  // 로그인 로그
  public void logLogin(String username, String ipAddress) {
    logAudit("LOGIN", "USER", null, username, "SUCCESS", null, "사용자 로그인", ipAddress);
  }

  // 로그아웃 로그
  public void logLogout(String username, String ipAddress) {
    logAudit("LOGOUT", "USER", null, username, "SUCCESS", null, "사용자 로그아웃", ipAddress);
  }

  // 로그인 실패 로그
  public void logLoginFailure(String username, String ipAddress) {
    logAudit("LOGIN", "USER", null, username, "FAILURE", null, "로그인 실패", ipAddress);
  }
}
