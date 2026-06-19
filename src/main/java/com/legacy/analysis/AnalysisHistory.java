package com.legacy.analysis;

import jakarta.persistence.*;
import java.time.LocalDateTime;

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
}
