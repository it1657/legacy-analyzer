package com.legacy;

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
  private int totalFiles;

  @Column(name = "success_count")
  private int successCount;

  @Column(name = "skip_count")
  private int skipCount;

  @Column(name = "failure_count")
  private int failureCount;

  @Column(name = "processing_time_ms")
  private long processingTimeMs;

  @Column(name = "status")
  private String status; // COMPLETED, FAILED, IN_PROGRESS

  @Column(name = "created_at")
  private LocalDateTime createdAt;

  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  @Column(name = "notes")
  private String notes;

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

  public int getTotalFiles() {
    return totalFiles;
  }

  public void setTotalFiles(int totalFiles) {
    this.totalFiles = totalFiles;
  }

  public int getSuccessCount() {
    return successCount;
  }

  public void setSuccessCount(int successCount) {
    this.successCount = successCount;
  }

  public int getSkipCount() {
    return skipCount;
  }

  public void setSkipCount(int skipCount) {
    this.skipCount = skipCount;
  }

  public int getFailureCount() {
    return failureCount;
  }

  public void setFailureCount(int failureCount) {
    this.failureCount = failureCount;
  }

  public long getProcessingTimeMs() {
    return processingTimeMs;
  }

  public void setProcessingTimeMs(long processingTimeMs) {
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
}
