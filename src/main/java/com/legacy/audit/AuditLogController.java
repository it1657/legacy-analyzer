package com.legacy.audit;
import com.legacy.auth.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

  private static final Logger log = LoggerFactory.getLogger(AuditLogController.class);

  private final AuditLogRepository auditLogRepository;
  private final UserRepository userRepository;

  @Autowired
  public AuditLogController(AuditLogRepository auditLogRepository,
      UserRepository userRepository) {
    this.auditLogRepository = auditLogRepository;
    this.userRepository = userRepository;
  }

  // 관리자: 전체 감사 로그 조회
  @GetMapping("/admin/all")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> getAllAuditLogs(
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String target,
      @RequestParam(required = false) Long userId) {
    try {
      List<AuditLog> logs;

      if (userId != null) {
        logs = auditLogRepository.findUserActivityLogs(userId, limit);
      } else if (action != null && target != null) {
        logs = auditLogRepository.findByAction(action);
        logs = logs.stream()
            .filter(log -> target.equals(log.getTarget()))
            .limit(limit)
            .toList();
      } else if (action != null) {
        logs = auditLogRepository.findByAction(action);
        logs = logs.stream().limit(limit).toList();
      } else if (target != null) {
        logs = auditLogRepository.findByTarget(target);
        logs = logs.stream().limit(limit).toList();
      } else {
        logs = auditLogRepository.findRecentLogs(limit);
      }

      List<Map<String, Object>> response = logs.stream()
          .map(this::convertToMap)
          .toList();

      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("[감사 로그 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Collections.singletonMap("message", "감사 로그 조회 실패: " + e.getMessage()));
    }
  }

  // 관리자: 기간별 감사 로그 조회
  @GetMapping("/admin/period")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> getAuditLogsByPeriod(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
    try {
      List<AuditLog> logs = auditLogRepository
          .findByTimestampBetween(startTime, endTime);

      List<Map<String, Object>> response = logs.stream()
          .map(this::convertToMap)
          .toList();

      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("[기간별 감사 로그 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message",
              "기간별 감사 로그 조회 실패: " + e.getMessage()));
    }
  }

  // 관리자: 사용자 활동 로그
  @GetMapping("/admin/users/{userId}/activity")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> getUserActivityLogs(
      @PathVariable Long userId,
      @RequestParam(defaultValue = "50") int limit) {
    try {
      List<AuditLog> logs = auditLogRepository.findUserActivityLogs(userId, limit);

      List<Map<String, Object>> response = logs.stream()
          .map(this::convertToMap)
          .toList();

      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("[사용자 활동 로그 조회 실패] userId={}", userId, e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message",
              "사용자 활동 로그 조회 실패: " + e.getMessage()));
    }
  }

  // 관리자: 액션별 통계
  @GetMapping("/admin/statistics")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> getAuditLogStatistics(
      @RequestParam(defaultValue = "30") int days) {
    try {
      LocalDateTime endTime = LocalDateTime.now();
      LocalDateTime startTime = endTime.minusDays(days);

      List<Object[]> stats = auditLogRepository
          .getActionStatistics(startTime, endTime);

      List<Map<String, Object>> response = new ArrayList<>();
      for (Object[] stat : stats) {
        Map<String, Object> map = new HashMap<>();
        map.put("action", stat[0]);
        map.put("count", stat[1]);
        response.add(map);
      }

      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("[액션 통계 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Collections.singletonMap("message",
              "액션 통계 조회 실패: " + e.getMessage()));
    }
  }

  // 관리자: 최근 활동 요약
  @GetMapping("/admin/recent-activity")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseBody
  public ResponseEntity<?> getRecentActivity(
      @RequestParam(defaultValue = "20") int limit) {
    try {
      List<AuditLog> logs = auditLogRepository.findRecentLogs(limit);

      List<Map<String, Object>> response = logs.stream()
          .map(this::convertToMap)
          .toList();

      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("[최근 활동 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Collections.singletonMap("message",
              "최근 활동 조회 실패: " + e.getMessage()));
    }
  }

  // AuditLog를 맵으로 변환
  private Map<String, Object> convertToMap(AuditLog log) {
    Map<String, Object> map = new HashMap<>();
    map.put("id", log.getId());
    map.put("userId", log.getUserId());
    map.put("username", log.getUsername());
    map.put("action", log.getAction());
    map.put("target", log.getTarget());
    map.put("targetId", log.getTargetId());
    map.put("targetName", log.getTargetName());
    map.put("status", log.getStatus());
    map.put("changes", log.getChanges());
    map.put("details", log.getDetails());
    map.put("timestamp", log.getTimestamp());
    map.put("ipAddress", log.getIpAddress());
    return map;
  }
}
