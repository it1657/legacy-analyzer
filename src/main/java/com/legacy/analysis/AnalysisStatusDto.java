package com.legacy.analysis;

import java.util.List;

/**
 * 분석 진행 상태 폴링 응답 DTO
 */
public class AnalysisStatusDto {

  private String sessionId;
  private String phase;         // STARTING, COPYING, ANALYZING, FINALIZING, COMPLETED, FAILED, CANCELLED
  private int totalFiles;
  private int processedFiles;
  private int successCount;
  private int failedCount;
  private int alreadyCount;
  private List<String> recentLogs;
  private boolean completed;
  private String errorMessage;

  // 완료 시 추가 정보
  private String avgTimePerFile;
  private String finalSummary;
  private String loginId;
  private String readmeContent;
  private String readmePath;
  private Long historyId;

  public String getSessionId() { return sessionId; }
  public void setSessionId(String sessionId) { this.sessionId = sessionId; }

  public String getPhase() { return phase; }
  public void setPhase(String phase) { this.phase = phase; }

  public int getTotalFiles() { return totalFiles; }
  public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }

  public int getProcessedFiles() { return processedFiles; }
  public void setProcessedFiles(int processedFiles) { this.processedFiles = processedFiles; }

  public int getSuccessCount() { return successCount; }
  public void setSuccessCount(int successCount) { this.successCount = successCount; }

  public int getFailedCount() { return failedCount; }
  public void setFailedCount(int failedCount) { this.failedCount = failedCount; }

  public int getAlreadyCount() { return alreadyCount; }
  public void setAlreadyCount(int alreadyCount) { this.alreadyCount = alreadyCount; }

  public List<String> getRecentLogs() { return recentLogs; }
  public void setRecentLogs(List<String> recentLogs) { this.recentLogs = recentLogs; }

  public boolean isCompleted() { return completed; }
  public void setCompleted(boolean completed) { this.completed = completed; }

  public String getErrorMessage() { return errorMessage; }
  public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

  public String getAvgTimePerFile() { return avgTimePerFile; }
  public void setAvgTimePerFile(String avgTimePerFile) { this.avgTimePerFile = avgTimePerFile; }

  public String getFinalSummary() { return finalSummary; }
  public void setFinalSummary(String finalSummary) { this.finalSummary = finalSummary; }

  public String getLoginId() { return loginId; }
  public void setLoginId(String loginId) { this.loginId = loginId; }

  public String getReadmeContent() { return readmeContent; }
  public void setReadmeContent(String readmeContent) { this.readmeContent = readmeContent; }

  public String getReadmePath() { return readmePath; }
  public void setReadmePath(String readmePath) { this.readmePath = readmePath; }

  public Long getHistoryId() { return historyId; }
  public void setHistoryId(Long historyId) { this.historyId = historyId; }
}
