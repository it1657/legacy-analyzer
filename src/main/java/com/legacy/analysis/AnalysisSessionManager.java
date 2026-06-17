package com.legacy.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 분석 세션 생성/로드/저장/관리 담당 컴포넌트
 */
@Component
public class AnalysisSessionManager {

  private static final Logger log = LoggerFactory.getLogger(AnalysisSessionManager.class);
  private static final ObjectMapper objectMapper = new ObjectMapper()
    .findAndRegisterModules()
    .enable(SerializationFeature.INDENT_OUTPUT);

  private final SessionConfig sessionConfig;
  private final SessionRepository sessionRepository;
  private final Map<String, SessionState> activeSessions = new ConcurrentHashMap<>();

  @Autowired
  public AnalysisSessionManager(SessionConfig sessionConfig, SessionRepository sessionRepository) {
    this.sessionConfig = sessionConfig;
    this.sessionRepository = sessionRepository;
  }

  /**
   * 새로운 분석 세션 생성 (클라이언트의 sessionId 사용)
   */
  public SessionState createSession(String sessionId, String sourcePath, String outputPath) {
    SessionState session = new SessionState(sessionId, sourcePath, outputPath);

    activeSessions.put(sessionId, session);

    // 파일 기반 저장 활성화 시 즉시 저장
    if (sessionConfig.isEnableSessionPersistence()) {
      saveSessionState(session);
    }

    log.info("[세션 생성] sessionId={}, source={}, output={}", sessionId, sourcePath, outputPath);
    return session;
  }

  /**
   * 새로운 분석 세션 생성 (자동 UUID 생성)
   */
  public SessionState createSession(String sourcePath, String outputPath) {
    String sessionId = UUID.randomUUID().toString();
    return createSession(sessionId, sourcePath, outputPath);
  }

  /**
   * 기존 세션 조회 (메모리 먼저, 없으면 DB에서)
   */
  public SessionState getSession(String sessionId) {
    SessionState session = activeSessions.get(sessionId);
    if (session == null) {
      session = loadSessionFromDatabase(sessionId);
      if (session != null) {
        activeSessions.put(sessionId, session);
      }
    }
    return session;
  }

  /**
   * 기존 세션 로드 (DB에서)
   */
  public SessionState loadSessionFromDatabase(String sessionId) {
    try {
      return sessionRepository.findById(sessionId).orElse(null);
    } catch (Exception e) {
      log.error("[세션 DB 로드 실패] sessionId={}", sessionId, e);
      return null;
    }
  }

  /**
   * 기존 세션 로드 (호환성 유지)
   */
  public SessionState loadSessionFromFile(String sessionId) {
    return loadSessionFromDatabase(sessionId);
  }

  /**
   * 세션 상태를 DB로 저장
   */
  public synchronized void saveSessionState(SessionState session) {
    if (!sessionConfig.isEnableSessionPersistence()) {
      return;
    }

    try {
      // H2 데이터베이스에 저장
      sessionRepository.save(session);
      log.debug("[세션 DB 저장] sessionId={}, processedFiles={}",
        session.getSessionId(), session.getProcessedFiles());
    } catch (Exception e) {
      log.error("[세션 저장 실패] sessionId={}", session.getSessionId(), e);
    }
  }

  /**
   * 세션 일시 중지
   */
  public void pauseSession(String sessionId) {
    SessionState session = activeSessions.get(sessionId);
    if (session != null) {
      session.setStatus("PAUSED");
      session.setLastUpdateTime(LocalDateTime.now());
      saveSessionState(session);
      log.info("[세션 일시 중지] sessionId={}", sessionId);
    }
  }

  /**
   * 세션 재개
   */
  public SessionState resumeSession(String sessionId) {
    SessionState session = activeSessions.get(sessionId);
    if (session == null) {
      session = loadSessionFromFile(sessionId);
    }

    if (session != null) {
      session.setStatus("IN_PROGRESS");
      session.setLastUpdateTime(LocalDateTime.now());
      saveSessionState(session);
      log.info("[세션 재개] sessionId={}, processedFiles={}",
        sessionId, session.getProcessedFiles());
    }
    return session;
  }

  /**
   * 세션 완료
   */
  public void completeSession(String sessionId) {
    SessionState session = activeSessions.get(sessionId);
    if (session != null) {
      session.setStatus("COMPLETED");
      session.setLastUpdateTime(LocalDateTime.now());
      session.getStatistics().setEndTime(LocalDateTime.now());
      saveSessionState(session);
      log.info("[세션 완료] sessionId={}", sessionId);
    }
  }

  /**
   * 세션 실패 처리
   */
  public void failSession(String sessionId, String errorMessage) {
    SessionState session = activeSessions.get(sessionId);
    if (session != null) {
      session.setStatus("FAILED");
      session.setLastUpdateTime(LocalDateTime.now());
      session.addErrorLog("세션 실패: " + errorMessage);
      saveSessionState(session);
      log.error("[세션 실패] sessionId={}, error={}", sessionId, errorMessage);
    }
  }

  /**
   * 세션 취소
   */
  public void cancelSession(String sessionId) {
    SessionState session = activeSessions.get(sessionId);
    if (session != null) {
      session.cancel();
      session.setStatus("CANCELLED");
      session.setLastUpdateTime(LocalDateTime.now());
      saveSessionState(session);
      log.info("[세션 취소] sessionId={}", sessionId);
    }
  }

  /**
   * 파일 분석 완료 기록
   */
  public void recordFileAnalysis(String sessionId, String filePath,
      FileAnalysisState fileState) {
    SessionState session = activeSessions.get(sessionId);
    if (session != null) {
      session.addProcessedFile(filePath, fileState);

      // 처리 시간 누적
      long totalTimeMs = session.getStatistics().getTotalProcessingTimeMs() +
          fileState.getProcessingTimeMs();
      session.getStatistics().setTotalProcessingTimeMs(totalTimeMs);

      // 주기적 저장 (대량 파일 시에도 성능을 위해 메모리에만 유지)
      // 매 100파일마다 또는 완료 시에만 저장하도록 최적화
      if (session.getProcessedFiles() % 100 == 0) {
        saveSessionState(session);
      }
    }
  }

  /**
   * 분석 통계 업데이트
   */
  public void updateStatistics(String sessionId, int successCount,
      int skipCount, int failureCount, int oversizeCount) {
    SessionState session = activeSessions.get(sessionId);
    if (session != null) {
      AnalysisStatistics stats = session.getStatistics();
      stats.setSuccessCount(successCount);
      stats.setSkipCount(skipCount);
      stats.setFailureCount(failureCount);
      stats.setOversizeCount(oversizeCount);
      session.setLastUpdateTime(LocalDateTime.now());
    }
  }

  /**
   * 분석 대상 파일 목록 초기화
   */
  public void initializeFileList(String sessionId, int totalFiles) {
    SessionState session = activeSessions.get(sessionId);
    if (session != null) {
      session.setTotalFiles(totalFiles);
      session.getStatistics().setTotalFiles(totalFiles);
      session.setLastUpdateTime(LocalDateTime.now());
    }
  }

  /**
   * 세션 자동 저장 (스케줄러용)
   */
  public void autoSaveSession(String sessionId) {
    SessionState session = activeSessions.get(sessionId);
    if (session != null) {
      saveSessionState(session);
    }
  }

  /**
   * 세션 삭제
   */
  public void deleteSession(String sessionId) {
    try {
      // DB에서 삭제
      sessionRepository.deleteById(sessionId);
      activeSessions.remove(sessionId);
      log.info("[세션 삭제] sessionId={}", sessionId);
    } catch (Exception e) {
      log.error("[세션 삭제 실패] sessionId={}", sessionId, e);
    }
  }

  /**
   * 모든 활성 세션 목록 조회
   */
  public List<SessionState> getAllActiveSessions() {
    return new ArrayList<>(activeSessions.values());
  }

  /**
   * 세션 타임아웃 체크 및 정리
   */
  public void cleanupExpiredSessions() {
    LocalDateTime expirationTime = LocalDateTime.now()
      .minusMinutes(sessionConfig.getSessionTimeoutMinutes());

    activeSessions.values().removeIf(session -> {
      if (session.getLastUpdateTime().isBefore(expirationTime)) {
        log.info("[세션 타임아웃] sessionId={}", session.getSessionId());
        return true;
      }
      return false;
    });
  }

  /**
   * 손상된 세션 복구 (서버 재시작 시)
   */
  public void recoverSessions() {
    try {
      Path sessionStoragePath = Paths.get(sessionConfig.getSessionStoragePath());
      if (!Files.exists(sessionStoragePath)) {
        return;
      }

      Files.list(sessionStoragePath)
        .filter(Files::isDirectory)
        .forEach(sessionDir -> {
          try {
            String sessionId = sessionDir.getFileName().toString();
            SessionState session = loadSessionFromFile(sessionId);
            if (session != null && "IN_PROGRESS".equals(session.getStatus())) {
              session.setStatus("PAUSED");
              saveSessionState(session);
              log.info("[세션 복구] sessionId={} (상태 변경: IN_PROGRESS -> PAUSED)", sessionId);
            }
          } catch (Exception e) {
            log.warn("[세션 복구 실패]", e);
          }
        });
    } catch (Exception e) {
      log.error("[세션 복구 중 오류 발생]", e);
    }
  }

  // Private 헬퍼 메서드

  private Path getSessionFilePath(String sessionId) {
    return Paths.get(sessionConfig.getSessionStoragePath(), sessionId, "state.json");
  }
}
