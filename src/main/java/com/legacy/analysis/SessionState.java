package com.legacy.analysis;

import java.time.LocalDateTime;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;

/**
 * 분석 세션의 진행 상황 및 상태 정보
 */
@Entity
@Table(name = "analysis_sessions")
public class SessionState {

  @Id
  @Column(length = 36)
  private String sessionId;

  @Column(name = "user_id")
  @JsonProperty("userId")
  private Long userId;

  @JsonProperty("sourcePath")
  private String sourcePath;

  @JsonProperty("outputPath")
  private String outputPath;

  @JsonProperty("status")
  private String status; // IN_PROGRESS, PAUSED, COMPLETED, FAILED

  @JsonProperty("totalFiles")
  private int totalFiles = 0;

  @JsonProperty("processedFiles")
  private int processedFiles = 0;

  @JsonProperty("startTime")
  private LocalDateTime startTime;

  @JsonProperty("lastUpdateTime")
  private LocalDateTime lastUpdateTime;

  @Transient
  @JsonProperty("processedFilesList")
  private Map<String, FileAnalysisState> processedFilesList = new HashMap<>();

  @Transient
  @JsonProperty("recoveryQueue")
  private List<FileAnalysisState> recoveryQueue = new ArrayList<>();

  @Transient
  @JsonProperty("errorLog")
  private List<String> errorLog = new ArrayList<>();

  @Transient
  @JsonProperty("statistics")
  private AnalysisStatistics statistics = new AnalysisStatistics();

  @Column(name = "is_cancelled")
  @JsonProperty("isCancelled")
  private boolean isCancelled = false;

  @Column(name = "is_analysis_completed")
  @JsonProperty("isAnalysisCompleted")
  private Boolean isAnalysisCompleted = false;

  @Column(name = "paused_at")
  @JsonProperty("pausedAt")
  private LocalDateTime pausedAt;

  @Column(name = "resumed_at")
  @JsonProperty("resumedAt")
  private LocalDateTime resumedAt;

  // 일시정지 후 처리되지 않은 파일 경로 목록 (JSON 배열, 재개용)
  @Column(name = "pending_file_paths_json", columnDefinition = "TEXT")
  private String pendingFilePathsJson;

  // 재개 시 분석 스레드 재시작에 필요한 사용자명
  @Column(name = "username", length = 100)
  private String username;

  // 분석 시작 시 사용자가 입력한 추가 요구사항 (prompt.md와 결합해 이 세션 전용 CLAUDE.md를 AI로 생성하는 데 사용)
  @Column(name = "requirements", columnDefinition = "TEXT")
  private String requirements;

  // 강제 재분석 여부
  @Column(name = "force_active")
  private boolean forceActive = false;

  @Transient
  @JsonProperty("sessionSummary")
  private SessionSummaryDto sessionSummary;

  @Transient
  @JsonProperty("logEntries")
  private List<AnalysisLogEntry> logEntries = new ArrayList<>();

  @Transient
  @JsonProperty("metadata")
  private Map<String, Object> metadata = new HashMap<>();

  // 이미 처리된 파일 절대경로 집합 (메모리 전용, 파일 마커 대신 사용)
  @Transient
  private java.util.Set<String> patchedFilePaths = java.util.concurrent.ConcurrentHashMap.newKeySet();

  // 폴링용 필드 (메모리 전용, DB 저장 안함)
  @Transient
  private String currentPhase = "STARTING";

  @Transient
  private final java.util.ArrayDeque<String> recentLogs = new java.util.ArrayDeque<>();

  private static final int MAX_RECENT_LOGS = 300;

  // 기본 생성자
  public SessionState() {
  }

  // 생성자
  public SessionState(String sessionId, String sourcePath, String outputPath) {
    this.sessionId = sessionId;
    this.sourcePath = sourcePath;
    this.outputPath = outputPath;
    this.status = "IN_PROGRESS";
    this.startTime = LocalDateTime.now();
    this.lastUpdateTime = LocalDateTime.now();
  }

  // Getter/Setter
  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
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

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public int getTotalFiles() {
    return totalFiles;
  }

  public void setTotalFiles(int totalFiles) {
    this.totalFiles = totalFiles;
  }

  public int getProcessedFiles() {
    return processedFiles;
  }

  public void setProcessedFiles(int processedFiles) {
    this.processedFiles = processedFiles;
  }

  public LocalDateTime getStartTime() {
    return startTime;
  }

  public void setStartTime(LocalDateTime startTime) {
    this.startTime = startTime;
  }

  public LocalDateTime getLastUpdateTime() {
    return lastUpdateTime;
  }

  public void setLastUpdateTime(LocalDateTime lastUpdateTime) {
    this.lastUpdateTime = lastUpdateTime;
  }

  public Map<String, FileAnalysisState> getProcessedFilesList() {
    return processedFilesList;
  }

  public void setProcessedFilesList(Map<String, FileAnalysisState> processedFilesList) {
    this.processedFilesList = processedFilesList;
  }

  public List<FileAnalysisState> getRecoveryQueue() {
    return recoveryQueue;
  }

  public void setRecoveryQueue(List<FileAnalysisState> recoveryQueue) {
    this.recoveryQueue = recoveryQueue;
  }

  public List<String> getErrorLog() {
    return errorLog;
  }

  public void setErrorLog(List<String> errorLog) {
    this.errorLog = errorLog;
  }

  public AnalysisStatistics getStatistics() {
    return statistics;
  }

  public void setStatistics(AnalysisStatistics statistics) {
    this.statistics = statistics;
  }

  // 편의 메서드
  public void addProcessedFile(String filePath, FileAnalysisState state) {
    processedFilesList.put(filePath, state);
    processedFiles++;
    lastUpdateTime = LocalDateTime.now();
  }

  public void addToRecoveryQueue(FileAnalysisState state) {
    recoveryQueue.add(state);
  }

  public void addErrorLog(String errorMessage) {
    errorLog.add("[" + LocalDateTime.now() + "] " + errorMessage);
  }

  public boolean isCancelled() {
    return isCancelled;
  }

  public void cancel() {
    this.isCancelled = true;
    this.lastUpdateTime = LocalDateTime.now();
  }

  public LocalDateTime getPausedAt() {
    return pausedAt;
  }

  public void setPausedAt(LocalDateTime pausedAt) {
    this.pausedAt = pausedAt;
  }

  public LocalDateTime getResumedAt() {
    return resumedAt;
  }

  public void setResumedAt(LocalDateTime resumedAt) {
    this.resumedAt = resumedAt;
  }

  public String getPendingFilePathsJson() { return pendingFilePathsJson; }
  public void setPendingFilePathsJson(String json) { this.pendingFilePathsJson = json; }

  public List<String> getPendingFilePaths() {
    if (pendingFilePathsJson == null || pendingFilePathsJson.isBlank()) return new ArrayList<>();
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      return mapper.readValue(pendingFilePathsJson,
          new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
    } catch (Exception e) {
      return new ArrayList<>();
    }
  }

  public void setPendingFilePaths(List<String> paths) {
    if (paths == null || paths.isEmpty()) {
      this.pendingFilePathsJson = "[]";
      return;
    }
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      this.pendingFilePathsJson = mapper.writeValueAsString(paths);
    } catch (Exception e) {
      this.pendingFilePathsJson = "[]";
    }
  }

  public java.util.Set<String> getPatchedFilePaths() { return patchedFilePaths; }
  public void setPatchedFilePaths(java.util.Set<String> set) { this.patchedFilePaths = set; }

  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }
  public String getRequirements() { return requirements; }
  public void setRequirements(String requirements) { this.requirements = requirements; }
  public boolean isForceActive() { return forceActive; }
  public void setForceActive(boolean forceActive) { this.forceActive = forceActive; }

  // 분석을 중단해야 하는지 판단
  public boolean shouldStop() {
    return isCancelled || "PAUSED".equals(status) || "PAUSED".equals(currentPhase);
  }

  // 분석 완료 상태 확인
  public boolean isAnalysisCompleted() {
    return Boolean.TRUE.equals(isAnalysisCompleted);
  }

  // 분석 완료 상태 설정
  public void setAnalysisCompleted(boolean completed) {
    this.isAnalysisCompleted = completed;
  }

  // 세션 요약 생성
  public SessionSummaryDto generateSummary() {
    SessionSummaryDto summary = new SessionSummaryDto();
    summary.setSessionId(this.sessionId);
    summary.setStartTime(this.startTime);
    summary.setEndTime(this.statistics.getEndTime());
    summary.setTotalFiles(this.totalFiles);
    summary.setSuccessCount(this.statistics.getSuccessCount());
    summary.setFailureCount(this.statistics.getFailureCount());
    summary.setSkipCount(this.statistics.getSkipCount());
    summary.setTotalProcessingTimeMs(this.statistics.getTotalProcessingTimeMs());
    summary.calculateAverageTime();
    summary.calculateSuccessRate();
    summary.setStatus(this.status);
    return summary;
  }

  // 로그 엔트리 추가
  public void addLogEntry(AnalysisLogEntry entry) {
    this.logEntries.add(entry);
    // 최대 1000개까지만 유지 (메모리 절약)
    if (this.logEntries.size() > 1000) {
      this.logEntries.remove(0);
    }
  }

  // 최근 로그 조회
  public List<AnalysisLogEntry> getRecentLogs(int count) {
    int startIdx = Math.max(0, this.logEntries.size() - count);
    return this.logEntries.subList(startIdx, this.logEntries.size());
  }

  // 메타데이터 업데이트
  public void updateMetadata(String key, Object value) {
    this.metadata.put(key, value);
  }

  public SessionSummaryDto getSessionSummary() {
    return sessionSummary;
  }

  public void setSessionSummary(SessionSummaryDto sessionSummary) {
    this.sessionSummary = sessionSummary;
  }

  public List<AnalysisLogEntry> getLogEntries() {
    return logEntries;
  }

  public void setLogEntries(List<AnalysisLogEntry> logEntries) {
    this.logEntries = logEntries;
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }

  public void setMetadata(Map<String, Object> metadata) {
    this.metadata = metadata;
  }

  // 폴링용 메서드

  public String getCurrentPhase() {
    return currentPhase;
  }

  public void setCurrentPhase(String phase) {
    this.currentPhase = phase;
    this.lastUpdateTime = LocalDateTime.now();
  }

  public synchronized void addRecentLog(String log) {
    if (log == null || log.isBlank()) return;
    recentLogs.addLast(log.stripTrailing());
    while (recentLogs.size() > MAX_RECENT_LOGS) {
      recentLogs.pollFirst();
    }
    lastUpdateTime = LocalDateTime.now();
  }

  public synchronized List<String> getRecentLogLines(int count) {
    List<String> all = new ArrayList<>(recentLogs);
    if (all.size() <= count) return all;
    return all.subList(all.size() - count, all.size());
  }
}
