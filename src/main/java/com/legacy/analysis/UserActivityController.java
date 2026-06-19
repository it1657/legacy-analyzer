package com.legacy.analysis;

import com.legacy.auth.User;
import com.legacy.core.PresentationGeneratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

  @Autowired
  public UserActivityController(AnalysisHistoryRepository analysisHistoryRepository,
      PresentationGeneratorService presentationGeneratorService) {
    this.analysisHistoryRepository = analysisHistoryRepository;
    this.presentationGeneratorService = presentationGeneratorService;
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

      List<Map<String, Object>> response = histories.stream()
          .map(h -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", h.getId());
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
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(new MediaType("application",
        "vnd.openxmlformats-officedocument.presentationml.presentation"));
    headers.setContentLength(content.length);
    headers.setContentDispositionFormData("attachment",
        String.format("%s_%s_%d.pptx", type, projectName, historyId));
    return new ResponseEntity<>(content, headers, HttpStatus.OK);
  }
}
