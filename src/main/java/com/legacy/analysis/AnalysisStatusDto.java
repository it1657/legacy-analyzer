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
  // 이번 세션에서 분석 실패로 끝난 파일의 상대경로 목록 (COMPLETED/FAILED 시점에만 채워짐).
  // 프런트가 완료 처리 시 실패 파일을 "패치완료"로 덮어쓰지 않도록 구분하는 데 사용한다.
  private List<String> failedFiles = new java.util.ArrayList<>();
  private boolean completed;
  private String errorMessage;

  // AWAITING_FAILOVER_CONFIRM(Phase 4) 상태일 때만 채워지는 failover 대상 모델 키.
  // Phase 5(프런트 컨펌 모달)가 "자체 LLM({modelKey})으로 진행하시겠습니까?" 문구를 만드는 데 사용한다.
  private String failoverModelKey;

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

  public List<String> getFailedFiles() { return failedFiles; }
  public void setFailedFiles(List<String> failedFiles) { this.failedFiles = failedFiles; }

  public boolean isCompleted() { return completed; }
  public void setCompleted(boolean completed) { this.completed = completed; }

  public String getErrorMessage() { return errorMessage; }
  public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

  public String getFailoverModelKey() { return failoverModelKey; }
  public void setFailoverModelKey(String failoverModelKey) { this.failoverModelKey = failoverModelKey; }

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
