package com.legacy.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 세션의 완전한 상세 정보를 담는 DTO
 */
public class SessionDetailDto {

  @JsonProperty("summary")
  private SessionSummaryDto summary;

  @JsonProperty("fileResults")
  private List<FileAnalysisState> fileResults = new ArrayList<>();

  @JsonProperty("errorLogs")
  private List<String> errorLogs = new ArrayList<>();

  @JsonProperty("statistics")
  private AnalysisStatistics statistics;

  @JsonProperty("logEntries")
  private List<AnalysisLogEntry> logEntries = new ArrayList<>();

  @JsonProperty("metadata")
  private Map<String, Object> metadata = new HashMap<>();

  public SessionDetailDto() {
  }

  /**
   * SessionState에서 SessionDetailDto로 변환
   */
  public static SessionDetailDto fromSessionState(SessionState session) {
    SessionDetailDto detail = new SessionDetailDto();

    // 요약 정보
    detail.summary = session.generateSummary();

    // 파일 결과
    detail.fileResults = new ArrayList<>(session.getProcessedFilesList().values());

    // 에러 로그
    detail.errorLogs = new ArrayList<>(session.getErrorLog());

    // 통계
    detail.statistics = session.getStatistics();

    // 로그 엔트리
    detail.logEntries = new ArrayList<>(session.getProcessedFilesList().isEmpty() ?
        new ArrayList<>() : new ArrayList<>());

    // 메타데이터
    if (session instanceof SessionState) {
      SessionState s = (SessionState) session;
      // 추가 메타데이터가 필요하면 여기서 처리
    }

    return detail;
  }

  // Getters and Setters
  public SessionSummaryDto getSummary() {
    return summary;
  }

  public void setSummary(SessionSummaryDto summary) {
    this.summary = summary;
  }

  public List<FileAnalysisState> getFileResults() {
    return fileResults;
  }

  public void setFileResults(List<FileAnalysisState> fileResults) {
    this.fileResults = fileResults;
  }

  public List<String> getErrorLogs() {
    return errorLogs;
  }

  public void setErrorLogs(List<String> errorLogs) {
    this.errorLogs = errorLogs;
  }

  public AnalysisStatistics getStatistics() {
    return statistics;
  }

  public void setStatistics(AnalysisStatistics statistics) {
    this.statistics = statistics;
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
}
