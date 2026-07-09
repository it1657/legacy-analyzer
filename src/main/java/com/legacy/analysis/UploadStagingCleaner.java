package com.legacy.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 업로드 분석 스테이징 폴더({app.analysis.upload-storage-path}/{sessionId}/...)의
 * 잔여물을 정리한다.
 *
 * write-back 완료 후 브라우저가 호출하는 명시적 cleanup(/api/upload-session/{id}/cleanup)이
 * 탭 종료·네트워크 오류 등으로 누락되면, 세션 폴더가 프로젝트 디렉토리 안에 그대로 남는다.
 * 이 잔여물이 남아있는 상태에서 같은 프로젝트를 다시 업로드하면, 브라우저 폴더 스캐너가
 * 잔여 세션 폴더 안의 파일까지 새 소스로 다시 집계해 분석 대상 파일 수가 부풀려지는
 * 문제가 있었다(예: 82개 → 165개). 서버 시작 시 1회 + 주기적으로 오래된 세션 폴더를 정리해
 * 이런 잔여물이 누적되지 않도록 한다.
 */
@Component
public class UploadStagingCleaner implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(UploadStagingCleaner.class);

  @Value("${app.analysis.upload-storage-path:.uploads}")
  private String uploadStoragePath;

  // 세션 하나가 정상적으로 살아있을 수 있는 최대 시간(분)과 동일한 기준을 재사용한다.
  // 이보다 오래된 세션 폴더는 진행 중인 분석일 수 없으므로 정리 대상으로 간주해도 안전하다.
  @Value("${app.analysis.session-timeout-minutes:240}")
  private long staleThresholdMinutes;

  @Override
  public void run(ApplicationArguments args) {
    cleanupStaleSessions();
  }

  @Scheduled(fixedRateString = "${app.analysis.upload-cleanup-interval-ms:3600000}")
  public void scheduledCleanup() {
    cleanupStaleSessions();
  }

  private void cleanupStaleSessions() {
    Path root = Path.of(uploadStoragePath).toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) return;

    Instant threshold = Instant.now().minusSeconds(staleThresholdMinutes * 60);
    try (Stream<Path> sessions = Files.list(root)) {
      sessions.filter(Files::isDirectory).forEach(sessionDir -> {
        try {
          Instant lastModified = Files.getLastModifiedTime(sessionDir).toInstant();
          if (lastModified.isBefore(threshold)) {
            deleteRecursively(sessionDir);
            log.info("[업로드 스테이징 정리] 잔여 세션 폴더 삭제: {}", sessionDir);
          }
        } catch (IOException e) {
          log.warn("[업로드 스테이징 정리] 세션 폴더 확인 실패: {}", sessionDir, e);
        }
      });
    } catch (IOException e) {
      log.warn("[업로드 스테이징 정리] 스캔 실패: {}", root, e);
    }
  }

  private void deleteRecursively(Path dir) throws IOException {
    try (Stream<Path> stream = Files.walk(dir)) {
      stream.sorted(Comparator.reverseOrder()).forEach(p -> {
        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
      });
    }
  }
}
