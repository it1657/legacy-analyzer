/* [AI 한글 주석 보완 완료] */
// 분석 전용 구조화된 로거
package com.legacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 코드 분석 작업을 위한 구조화된 로깅
 */
@Component
public class AnalysisLogger {

  private static final Logger mainLogger = LoggerFactory.getLogger(AnalysisLogger.class);
  private static final Logger sessionLogger = LoggerFactory.getLogger("com.legacy.session");

  private final Map<String, List<AnalysisLogEntry>> sessionLogs = new ConcurrentHashMap<>();

  // 파일 분석 시작
  public void logFileAnalysisStart(String sessionId, String filePath) {
    AnalysisLogEntry entry = new AnalysisLogEntry();
    entry.setSessionId(sessionId);
    entry.setFilePath(filePath);
    entry.setEventType(AnalysisLogEntry.EventType.FILE_START);
    entry.setLogLevel(AnalysisLogEntry.LogLevel.INFO);
    entry.setMessage("파일 분석 시작: " + filePath);
    entry.setTimestamp(LocalDateTime.now());

    addLogEntry(entry);
    mainLogger.info("[분석 시작] 세션: {}, 파일: {}", sessionId, filePath);
  }

  // 파일 분석 완료
  public void logFileAnalysisComplete(String sessionId, String filePath,
      String status, long processingTimeMs) {
    AnalysisLogEntry entry = new AnalysisLogEntry();
    entry.setSessionId(sessionId);
    entry.setFilePath(filePath);
    entry.setEventType(AnalysisLogEntry.EventType.FILE_COMPLETE);
    entry.setLogLevel(AnalysisLogEntry.LogLevel.INFO);
    entry.setMessage(String.format("파일 분석 완료: %s (상태: %s)", filePath, status));
    entry.setTimestamp(LocalDateTime.now());
    entry.setProcessingTimeMs(processingTimeMs);
    entry.getMetadata().put("status", status);

    addLogEntry(entry);
    mainLogger.info("[분석 완료] 세션: {}, 파일: {}, 상태: {}, 소요시간: {}ms",
        sessionId, filePath, status, processingTimeMs);
  }

  // 에러 로깅
  public void logError(String sessionId, String filePath, String errorType, String message) {
    AnalysisLogEntry entry = new AnalysisLogEntry();
    entry.setSessionId(sessionId);
    entry.setFilePath(filePath);
    entry.setEventType(AnalysisLogEntry.EventType.ERROR_OCCURRED);
    entry.setLogLevel(AnalysisLogEntry.LogLevel.ERROR);
    entry.setErrorType(errorType);
    entry.setMessage(message);
    entry.setTimestamp(LocalDateTime.now());

    addLogEntry(entry);
    mainLogger.error("[에러 발생] 세션: {}, 파일: {}, 타입: {}, 메시지: {}",
        sessionId, filePath, errorType, message);
  }

  // 재시도 로깅
  public void logRetry(String sessionId, String filePath, int retryCount, long delayMs) {
    AnalysisLogEntry entry = new AnalysisLogEntry();
    entry.setSessionId(sessionId);
    entry.setFilePath(filePath);
    entry.setEventType(AnalysisLogEntry.EventType.RETRY_ATTEMPTED);
    entry.setLogLevel(AnalysisLogEntry.LogLevel.WARN);
    entry.setMessage(String.format("재시도 예정: %s (시도: %d, 대기: %dms)",
        filePath, retryCount, delayMs));
    entry.setTimestamp(LocalDateTime.now());
    entry.getMetadata().put("retryCount", retryCount);
    entry.getMetadata().put("delayMs", delayMs);

    addLogEntry(entry);
    mainLogger.warn("[재시도] 세션: {}, 파일: {}, 시도: {}, 대기: {}ms",
        sessionId, filePath, retryCount, delayMs);
  }

  // 내부 메서드
  private void addLogEntry(AnalysisLogEntry entry) {
    String sessionId = entry.getSessionId();
    sessionLogs.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(entry);
  }

  public List<AnalysisLogEntry> getSessionLogs(String sessionId) {
    return new ArrayList<>(sessionLogs.getOrDefault(sessionId, new ArrayList<>()));
  }

  public void flushLogs() {
    sessionLogs.clear();
    mainLogger.info("[로그 플러시] 모든 세션 로그가 초기화되었습니다.");
  }
}
