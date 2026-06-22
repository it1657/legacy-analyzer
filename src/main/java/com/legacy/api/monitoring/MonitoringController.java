package com.legacy.api.monitoring;
import com.legacy.analysis.AnalysisSessionManager;
import com.legacy.analysis.AnalysisLogger;
import com.legacy.analysis.SessionDetailDto;
import com.legacy.analysis.SessionSummaryDto;
import com.legacy.analysis.SessionState;
import com.legacy.analysis.AnalysisLogEntry;
import com.legacy.analysis.ApiResponseWrapper;
import com.legacy.auth.User;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

/**
 * 분석 세션의 실시간 모니터링 및 상태 조회 API
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitoringController {

  private static final Logger log = LoggerFactory.getLogger(MonitoringController.class);

  private final AnalysisSessionManager sessionManager;
  private final AnalysisLogger analysisLogger;
  private final PerformanceMetricsCollector metricsCollector;

  @Autowired
  public MonitoringController(
      AnalysisSessionManager sessionManager,
      AnalysisLogger analysisLogger,
      PerformanceMetricsCollector metricsCollector) {
    this.sessionManager = sessionManager;
    this.analysisLogger = analysisLogger;
    this.metricsCollector = metricsCollector;
  }

  // 세션 상세 정보 조회
  @GetMapping("/session/{sessionId}")
  @ResponseBody
  public ApiResponseWrapper<SessionDetailDto> getSessionDetails(
      @PathVariable String sessionId, Authentication authentication) {
    try {
      SessionState session = sessionManager.getSession(sessionId);
      if (session == null) {
        return ApiResponseWrapper.error("세션을 찾을 수 없습니다.",
            new ApiResponseWrapper.ErrorInfo("SESSION_NOT_FOUND", "유효하지 않은 세션 ID", null));
      }
      if (!isOwnerOrAdmin(session, authentication)) {
        return ApiResponseWrapper.error("접근 권한이 없습니다.",
            new ApiResponseWrapper.ErrorInfo("ACCESS_DENIED", "본인 세션만 조회할 수 있습니다.", null));
      }
      SessionDetailDto detail = SessionDetailDto.fromSessionState(session);
      return ApiResponseWrapper.success(detail);
    } catch (Exception e) {
      log.error("[세션 조회 실패] sessionId={}", sessionId, e);
      return ApiResponseWrapper.error("세션 조회 실패: " + e.getMessage(),
          new ApiResponseWrapper.ErrorInfo("SESSION_ERROR", e.getMessage(), null));
    }
  }

  // 시스템 메트릭 조회
  @GetMapping("/metrics")
  @ResponseBody
  public ApiResponseWrapper<Map<String, Object>> getSystemMetrics() {
    try {
      Map<String, Object> metrics = metricsCollector.getSystemMetrics();
      return ApiResponseWrapper.success(metrics);
    } catch (Exception e) {
      log.error("[시스템 메트릭 조회 실패]", e);
      return ApiResponseWrapper.error("메트릭 조회 실패: " + e.getMessage(),
          new ApiResponseWrapper.ErrorInfo("METRICS_ERROR", e.getMessage(), null));
    }
  }

  // 세션별 메트릭 조회
  @GetMapping("/metrics/{sessionId}")
  @ResponseBody
  public ApiResponseWrapper<Map<String, Object>> getSessionMetrics(
      @PathVariable String sessionId, Authentication authentication) {
    try {
      SessionState session = sessionManager.getSession(sessionId);
      if (session != null && !isOwnerOrAdmin(session, authentication)) {
        return ApiResponseWrapper.error("접근 권한이 없습니다.",
            new ApiResponseWrapper.ErrorInfo("ACCESS_DENIED", "본인 세션만 조회할 수 있습니다.", null));
      }
      Map<String, Object> metrics = metricsCollector.getSessionMetrics(sessionId);
      return ApiResponseWrapper.success(metrics);
    } catch (Exception e) {
      log.error("[세션 메트릭 조회 실패] sessionId={}", sessionId, e);
      return ApiResponseWrapper.error("세션 메트릭 조회 실패: " + e.getMessage(),
          new ApiResponseWrapper.ErrorInfo("METRICS_ERROR", e.getMessage(), null));
    }
  }

  // 세션 로그 조회
  @GetMapping("/logs/{sessionId}")
  @ResponseBody
  public ApiResponseWrapper<List<AnalysisLogEntry>> getSessionLogs(
      @PathVariable String sessionId,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset,
      Authentication authentication) {
    try {
      SessionState session = sessionManager.getSession(sessionId);
      if (session != null && !isOwnerOrAdmin(session, authentication)) {
        return ApiResponseWrapper.error("접근 권한이 없습니다.",
            new ApiResponseWrapper.ErrorInfo("ACCESS_DENIED", "본인 세션만 조회할 수 있습니다.", null));
      }
      List<AnalysisLogEntry> allLogs = analysisLogger.getSessionLogs(sessionId);
      int startIdx = Math.min(offset, allLogs.size());
      int endIdx = Math.min(offset + limit, allLogs.size());
      List<AnalysisLogEntry> result = allLogs.subList(startIdx, endIdx);
      return ApiResponseWrapper.success(result);
    } catch (Exception e) {
      log.error("[로그 조회 실패] sessionId={}", sessionId, e);
      return ApiResponseWrapper.error("로그 조회 실패: " + e.getMessage(),
          new ApiResponseWrapper.ErrorInfo("LOG_ERROR", e.getMessage(), null));
    }
  }

  // 세션 요약 조회
  @GetMapping("/summary/{sessionId}")
  @ResponseBody
  public ApiResponseWrapper<SessionSummaryDto> getSessionSummary(
      @PathVariable String sessionId, Authentication authentication) {
    try {
      SessionState session = sessionManager.getSession(sessionId);
      if (session == null) {
        return ApiResponseWrapper.error("세션을 찾을 수 없습니다.",
            new ApiResponseWrapper.ErrorInfo("SESSION_NOT_FOUND", "유효하지 않은 세션 ID", null));
      }
      if (!isOwnerOrAdmin(session, authentication)) {
        return ApiResponseWrapper.error("접근 권한이 없습니다.",
            new ApiResponseWrapper.ErrorInfo("ACCESS_DENIED", "본인 세션만 조회할 수 있습니다.", null));
      }
      SessionSummaryDto summary = session.generateSummary();
      return ApiResponseWrapper.success(summary);
    } catch (Exception e) {
      log.error("[세션 요약 조회 실패] sessionId={}", sessionId, e);
      return ApiResponseWrapper.error("세션 요약 조회 실패: " + e.getMessage(),
          new ApiResponseWrapper.ErrorInfo("SESSION_ERROR", e.getMessage(), null));
    }
  }

  // 세션 삭제 (정리)
  @DeleteMapping("/session/{sessionId}")
  @ResponseBody
  public ApiResponseWrapper<Boolean> deleteSession(
      @PathVariable String sessionId, Authentication authentication) {
    try {
      SessionState session = sessionManager.getSession(sessionId);
      if (session != null && !isOwnerOrAdmin(session, authentication)) {
        return ApiResponseWrapper.error("접근 권한이 없습니다.",
            new ApiResponseWrapper.ErrorInfo("ACCESS_DENIED", "본인 세션만 삭제할 수 있습니다.", null));
      }
      sessionManager.deleteSession(sessionId);
      return ApiResponseWrapper.success(true);
    } catch (Exception e) {
      log.error("[세션 삭제 실패] sessionId={}", sessionId, e);
      return ApiResponseWrapper.error("세션 삭제 실패: " + e.getMessage(),
          new ApiResponseWrapper.ErrorInfo("SESSION_ERROR", e.getMessage(), null));
    }
  }

  // 세션 소유자 또는 ADMIN 여부 확인
  private boolean isOwnerOrAdmin(SessionState session, Authentication authentication) {
    if (authentication == null) return false;
    if (authentication.getAuthorities().stream()
        .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) return true;
    if (!(authentication.getPrincipal() instanceof User user)) return false;
    return user.getUserId().equals(session.getUsername());
  }
}
