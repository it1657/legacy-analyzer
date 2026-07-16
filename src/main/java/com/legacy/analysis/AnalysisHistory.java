package com.legacy.analysis;

import com.legacy.core.ProjectStructureSnapshot;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "analysis_history")
public class AnalysisHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "session_id", length = 36)
  private String sessionId;

  @Column(name = "source_path")
  private String sourcePath;

  @Column(name = "output_path")
  private String outputPath;

  @Column(name = "total_files")
  private Integer totalFiles;

  @Column(name = "success_count")
  private Integer successCount;

  @Column(name = "skip_count")
  private Integer skipCount;

  @Column(name = "failure_count")
  private Integer failureCount;

  @Column(name = "processing_time_ms")
  private Long processingTimeMs;

  @Column(name = "status")
  private String status; // COMPLETED, FAILED, IN_PROGRESS

  @Column(name = "created_at")
  private LocalDateTime createdAt;

  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  @Column(name = "notes")
  private String notes;

  @Column(name = "model_name", length = 100)
  private String modelName;

  @Column(name = "input_tokens")
  private Long inputTokens;

  @Column(name = "output_tokens")
  private Long outputTokens;

  @Column(name = "total_tokens")
  private Long totalTokens;

  @Column(name = "estimated_cost")
  private Double estimatedCost;

  @Column(name = "readme_path")
  private String readmePath;

  @Column(name = "readme_content", columnDefinition = "TEXT")
  private String readmeContent;

  @Column(name = "claude_md_content", columnDefinition = "TEXT")
  private String claudeMdContent;

  @Column(name = "avg_time_per_file")
  private Double avgTimePerFile;

  // 부분 분석 시 사용자가 선택한 파일들의 정규화(슬래시 통일)된 상대경로 목록 (JSON 배열).
  // null이면 "전체 분석" - 보고서(PPT) 생성 시 이 값의 유무로 구조 슬라이드 범위를 판단한다.
  @Column(name = "selected_paths_json", columnDefinition = "TEXT")
  private String selectedPathsJson;

  // 분석 완료 시점에 한 번 계산해 둔 PPT용 프로젝트 구조 스냅샷(JSON). 다운로드할 때마다 디스크를
  // 재스캔하지 않기 위한 것 - null이면(이 기능 도입 전 완료된 이력) PPT 생성 시 라이브 스캔으로 폴백한다.
  @Column(name = "structure_snapshot_json", columnDefinition = "TEXT")
  private String structureSnapshotJson;

  // 생성자
  public AnalysisHistory() {
  }

  public AnalysisHistory(Long userId, String sessionId, String sourcePath, String outputPath) {
    this.userId = userId;
    this.sessionId = sessionId;
    this.sourcePath = sourcePath;
    this.outputPath = outputPath;
    this.status = "IN_PROGRESS";
    this.createdAt = LocalDateTime.now();
  }

  // Getter/Setter
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getSourcePath() {
    return sourcePath;
  }

  public void setSourcePath(String sourcePath) {
    this.sourcePath = sourcePath;
  }

  public String getOutputPath() {
    return outputPath;
  }

  public void setOutputPath(String outputPath) {
    this.outputPath = outputPath;
  }

  public Integer getTotalFiles() {
    return totalFiles;
  }

  public void setTotalFiles(Integer totalFiles) {
    this.totalFiles = totalFiles;
  }

  public Integer getSuccessCount() {
    return successCount;
  }

  public void setSuccessCount(Integer successCount) {
    this.successCount = successCount;
  }

  public Integer getSkipCount() {
    return skipCount;
  }

  public void setSkipCount(Integer skipCount) {
    this.skipCount = skipCount;
  }

  public Integer getFailureCount() {
    return failureCount;
  }

  public void setFailureCount(Integer failureCount) {
    this.failureCount = failureCount;
  }

  public Long getProcessingTimeMs() {
    return processingTimeMs;
  }

  public void setProcessingTimeMs(Long processingTimeMs) {
    this.processingTimeMs = processingTimeMs;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(LocalDateTime completedAt) {
    this.completedAt = completedAt;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public String getModelName() {
    return modelName;
  }

  public void setModelName(String modelName) {
    this.modelName = modelName;
  }

  public Long getInputTokens() {
    return inputTokens;
  }

  public void setInputTokens(Long inputTokens) {
    this.inputTokens = inputTokens;
  }

  public Long getOutputTokens() {
    return outputTokens;
  }

  public void setOutputTokens(Long outputTokens) {
    this.outputTokens = outputTokens;
  }

  public Long getTotalTokens() {
    return totalTokens;
  }

  public void setTotalTokens(Long totalTokens) {
    this.totalTokens = totalTokens;
  }

  public Double getEstimatedCost() {
    return estimatedCost;
  }

  public void setEstimatedCost(Double estimatedCost) {
    this.estimatedCost = estimatedCost;
  }

  public String getReadmePath() {
    return readmePath;
  }

  public void setReadmePath(String readmePath) {
    this.readmePath = readmePath;
  }

  public String getReadmeContent() {
    return readmeContent;
  }

  public void setReadmeContent(String readmeContent) {
    this.readmeContent = readmeContent;
  }

  public String getClaudeMdContent() {
    return claudeMdContent;
  }

  public void setClaudeMdContent(String claudeMdContent) {
    this.claudeMdContent = claudeMdContent;
  }

  public Double getAvgTimePerFile() {
    return avgTimePerFile;
  }

  public void setAvgTimePerFile(Double avgTimePerFile) {
    this.avgTimePerFile = avgTimePerFile;
  }

  public String getSelectedPathsJson() {
    return selectedPathsJson;
  }

  public void setSelectedPathsJson(String selectedPathsJson) {
    this.selectedPathsJson = selectedPathsJson;
  }

  public Set<String> getSelectedRelativePaths() {
    if (selectedPathsJson == null || selectedPathsJson.isBlank()) return null;
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      return new HashSet<>(mapper.readValue(selectedPathsJson,
          new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {}));
    } catch (Exception e) {
      return null;
    }
  }

  public void setSelectedRelativePaths(Set<String> selectedRelativePaths) {
    if (selectedRelativePaths == null || selectedRelativePaths.isEmpty()) {
      this.selectedPathsJson = null;
      return;
    }
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      this.selectedPathsJson = mapper.writeValueAsString(selectedRelativePaths);
    } catch (Exception e) {
      this.selectedPathsJson = null;
    }
  }

  public String getStructureSnapshotJson() {
    return structureSnapshotJson;
  }

  public void setStructureSnapshotJson(String structureSnapshotJson) {
    this.structureSnapshotJson = structureSnapshotJson;
  }

  public ProjectStructureSnapshot getStructureSnapshot() {
    if (structureSnapshotJson == null || structureSnapshotJson.isBlank()) return null;
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      return mapper.readValue(structureSnapshotJson, ProjectStructureSnapshot.class);
    } catch (Exception e) {
      return null;
    }
  }

  public void setStructureSnapshot(ProjectStructureSnapshot snapshot) {
    if (snapshot == null) {
      this.structureSnapshotJson = null;
      return;
    }
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      this.structureSnapshotJson = mapper.writeValueAsString(snapshot);
    } catch (Exception e) {
      this.structureSnapshotJson = null;
    }
  }
}
