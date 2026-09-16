package com.legacy.analysis;

import com.legacy.auth.User;
import com.legacy.core.PresentationGeneratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 일반 사용자가 자신의 분석 이력과 API 사용량을 조회하는 컨트롤러.
 * 자신의 데이터만 접근 가능하며 타 사용자 데이터는 차단된다.
 */
@Controller
public class UserActivityController {

  private static final Logger log = LoggerFactory.getLogger(UserActivityController.class);

  private final AnalysisHistoryRepository analysisHistoryRepository;
  private final PresentationGeneratorService presentationGeneratorService;
  // (REQ-002) 목록의 PAUSED 행에 일시정지 확정 여부(SessionState.pauseSettled)를 실어 보내기 위한 세션 저장소.
  private final SessionRepository sessionRepository;

  @Autowired
  public UserActivityController(AnalysisHistoryRepository analysisHistoryRepository,
      PresentationGeneratorService presentationGeneratorService,
      SessionRepository sessionRepository) {
    this.analysisHistoryRepository = analysisHistoryRepository;
    this.presentationGeneratorService = presentationGeneratorService;
    this.sessionRepository = sessionRepository;
  }

  // 내 활동 페이지 렌더링
  @GetMapping("/my-activity")
  public String myActivityPage() {
    return "my-activity";
  }

  // 내 분석 이력 조회 (본인 데이터만)
  @GetMapping("/api/my/analysis-history")
  @ResponseBody
  public ResponseEntity<?> getMyAnalysisHistory(Authentication authentication) {
    try {
      User user = (User) authentication.getPrincipal();
      List<AnalysisHistory> histories = analysisHistoryRepository
          .findByUserIdOrderByCreatedAtDesc(user.getSeq());

      // (REQ-002) PAUSED 행의 세션만 모아 findAllById 1회 배치 조회로 pauseSettled를 채운다 —
      // 행마다 개별 조회하면 목록 조회 비용이 이력 수에 비례해 늘어나므로 금지(설계 §4.2).
      Set<String> unsettledSessionIds = collectUnsettledPausedSessionIds(histories);

      List<Map<String, Object>> response = histories.stream()
          .map(h -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", h.getId());
            map.put("sessionId", h.getSessionId());
            map.put("sourcePath", h.getSourcePath());
            map.put("outputPath", h.getOutputPath());
            map.put("totalFiles", h.getTotalFiles());
            map.put("successCount", h.getSuccessCount());
            map.put("skipCount", h.getSkipCount());
            map.put("failureCount", h.getFailureCount());
            map.put("processingTimeMs", h.getProcessingTimeMs());
            map.put("avgTimePerFile", h.getAvgTimePerFile());
            map.put("status", h.getStatus());
            map.put("modelName", h.getModelName());
            map.put("inputTokens", h.getInputTokens());
            map.put("outputTokens", h.getOutputTokens());
            map.put("estimatedCost", h.getEstimatedCost());
            map.put("createdAt", h.getCreatedAt());
            map.put("completedAt", h.getCompletedAt());
            map.put("readmePath", h.getReadmePath());
            map.put("hasClaudeMd", h.getClaudeMdContent() != null && !h.getClaudeMdContent().isBlank());
            // (REQ-002) false = 일시정지 요청은 됐지만 pendingFilePaths가 아직 확정되지 않은 구간("멈추는 중").
            // 그 외(확정 후·PAUSED가 아닌 행·세션 행이 없거나 NULL인 기존 세션)는 전부 true.
            map.put("pauseSettled", !unsettledSessionIds.contains(h.getSessionId()));
            return map;
          })
          .toList();

      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("[내 분석이력 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Collections.singletonMap("message", "분석이력 조회 실패: " + e.getMessage()));
    }
  }

  /**
   * (REQ-002) 이력 목록 중 {@code status == "PAUSED"}인 행의 sessionId만 모아 {@code findAllById(...)}
   * <b>1회</b>로 세션을 읽고, 그중 {@code pauseSettled == false}(아직 멈추는 중)인 sessionId 집합을 돌려준다.
   * PAUSED 행이 없으면 DB를 조회하지 않는다(0회). NULL/true인 세션·세션 행이 없는 이력은 확정으로 취급돼
   * 집합에 들어가지 않는다({@link SessionState#hasSettledPause()}).
   *
   * <p>(TASK-002C, v5 §0.22.3 (e)) {@code pauseSettled}는 목록의 <b>부가 필드</b>이므로 그 조회 실패가 목록 전체를
   * 죽이지 않는다 — 세션 조회 구간만 국소 try/catch로 감싸고, 실패하면 빈 집합(= 전 행 {@code pauseSettled=true}
   * = 기존 동작 = 재개 버튼 노출)으로 강등한 뒤 ERROR 로그를 남긴다. 강등 방향은 프런트의 {@code !== false} 폴백,
   * 엔티티의 NULL=확정 해석과 같은 "기존 동작" 쪽이다. 2026-09-15 실배포에서 옛 세션 행의 NULL 컬럼 하나가
   * {@code findAllById} 하이드레이션에서 예외를 내 이력 94건 전체가 500이 됐던 회귀의 재발 방지 장치다.
   */
  private Set<String> collectUnsettledPausedSessionIds(List<AnalysisHistory> histories) {
    List<String> pausedSessionIds = histories.stream()
        .filter(h -> "PAUSED".equals(h.getStatus()))
        .map(AnalysisHistory::getSessionId)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
    if (pausedSessionIds.isEmpty()) return Collections.emptySet();

    try {
      Set<String> unsettled = new HashSet<>();
      for (SessionState session : sessionRepository.findAllById(pausedSessionIds)) {
        if (!session.hasSettledPause()) unsettled.add(session.getSessionId());
      }
      return unsettled;
    } catch (Exception e) {
      // 부가 필드 조회 실패 → 목록은 살리고 전 행 확정(true)으로 강등. 원인·대상은 로그로만 남긴다.
      log.error("[내 분석이력 pauseSettled 조회 실패] 목록은 기존 동작(전 행 확정)으로 강등 — 대상 sessionIds={}",
          pausedSessionIds, e);
      return Collections.emptySet();
    }
  }

  // 해당 분석에 실제 사용된 CLAUDE.md 내용 조회 (본인 이력만)
  @GetMapping("/api/my/claude-md/{historyId}")
  @ResponseBody
  public ResponseEntity<?> getMyClaudeMdContent(
      @PathVariable Long historyId, Authentication authentication) {
    try {
      User user = (User) authentication.getPrincipal();
      AnalysisHistory history = analysisHistoryRepository.findById(historyId).orElse(null);
      if (history == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
      if (!history.getUserId().equals(user.getSeq())) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

      String content = history.getClaudeMdContent();
      if (content == null || content.isBlank()) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Collections.singletonMap("message", "이 분석에 저장된 CLAUDE.md 내용이 없습니다."));
      }
      return ResponseEntity.ok(Collections.singletonMap("content", content));
    } catch (Exception e) {
      log.error("[CLAUDE.md 조회 실패] historyId={}", historyId, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Collections.singletonMap("message", "CLAUDE.md 조회 실패: " + e.getMessage()));
    }
  }

  // 분석 직후 완료 화면용 PPT (내부 통계: 파일 수, 성공률, 토큰/비용)
  @GetMapping("/api/my/download/presentation/{historyId}")
  @ResponseBody
  public ResponseEntity<byte[]> downloadMyPresentation(
      @PathVariable Long historyId, Authentication authentication) {
    try {
      User user = (User) authentication.getPrincipal();
      AnalysisHistory history = analysisHistoryRepository.findById(historyId).orElse(null);
      if (history == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
      if (!history.getUserId().equals(user.getSeq())) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

      byte[] pptxContent = presentationGeneratorService.generateAnalysisResultPresentation(history);
      return buildPptResponse(pptxContent, "summary", history.getSourcePath(), historyId);
    } catch (Exception e) {
      log.error("[분석요약 PPT 다운로드 실패] historyId={}", historyId, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  // 내 분석이력 고객 납품용 PPT (프로젝트 구조, 패키지 구조, 비즈니스 로직)
  @GetMapping("/api/my/download/project-report/{historyId}")
  @ResponseBody
  public ResponseEntity<byte[]> downloadProjectReport(
      @PathVariable Long historyId, Authentication authentication) {
    try {
      User user = (User) authentication.getPrincipal();
      AnalysisHistory history = analysisHistoryRepository.findById(historyId).orElse(null);
      if (history == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
      if (!history.getUserId().equals(user.getSeq())) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

      byte[] pptxContent = presentationGeneratorService.generateProjectReportPresentation(history);
      return buildPptResponse(pptxContent, "report", history.getSourcePath(), historyId);
    } catch (Exception e) {
      log.error("[프로젝트보고서 PPT 다운로드 실패] historyId={}", historyId, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  private ResponseEntity<byte[]> buildPptResponse(byte[] content, String type, String sourcePath, Long historyId) {
    String projectName = sourcePath != null ? sourcePath.replaceAll(".*[/\\\\]", "") : "analysis";
    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(new MediaType("application",
        "vnd.openxmlformats-officedocument.presentationml.presentation"));
    headers.setContentLength(content.length);
    String filename = String.format("%s_%s_%s.pptx", type, projectName, timestamp);
    // 폼 필드용 form-data가 아니라 파일 첨부용 attachment 타입으로 지정한다.
    headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
    return new ResponseEntity<>(content, headers, HttpStatus.OK);
  }
}
