/* [AI 한글 주석 보완 완료] */
// 확장자(.java) 맞춤형 자동 생성 목업 주석 예시 1
package com.legacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// 분석 대상 파일명: FileIoErrorHandler.java
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 파일 I/O 오류 처리 및 복구 담당 컴포넌트
 */
@Component
public class FileIoErrorHandler {

  private static final Logger log = LoggerFactory.getLogger(FileIoErrorHandler.class);

  public enum FileIoErrorType {
    FILE_NOT_FOUND,       // 파일을 찾을 수 없음
    PERMISSION_DENIED,    // 파일 접근 권한 없음
    ENCODING_ERROR,       // 파일 인코딩 오류
    DISK_FULL,            // 디스크 용량 부족
    PATH_INVALID,         // 잘못된 경로
    WRITE_FAILED,         // 파일 쓰기 실패
    READ_FAILED,          // 파일 읽기 실패
    UNKNOWN              // 미분류 오류
  }

  /**
   * 파일 읽기 시도 (다중 인코딩 지원)
   */
  public String readFileWithFallback(Path filePath) throws IOException {
    // 1차: UTF-8
    try {
      return Files.readString(filePath, java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e1) {
      log.debug("[파일 읽기] UTF-8 실패, MS949 시도: {}", filePath);

      // 2차: MS949 (한글 Windows)
      try {
        return Files.readString(filePath, Charset.forName("MS949"));
      } catch (Exception e2) {
        log.debug("[파일 읽기] MS949 실패, ISO-8859-1 시도: {}", filePath);

        // 3차: ISO-8859-1 (Latin-1)
        try {
          return Files.readString(filePath, Charset.forName("ISO-8859-1"));
        } catch (Exception e3) {
          log.error("[파일 읽기] 모든 인코딩 시도 실패: {}", filePath, e3);
          throw new IOException("파일 인코딩을 결정할 수 없습니다: " + filePath, e3);
        }
      }
    }
  }

  /**
   * 파일 읽기 오류 분류
   */
  public FileIoErrorType classifyReadError(Path filePath, Exception exception) {
    if (exception == null) {
      return FileIoErrorType.UNKNOWN;
    }

    String errorMsg = exception.getMessage().toLowerCase();

    if (errorMsg.contains("no such file") || errorMsg.contains("cannot find")) {
      return FileIoErrorType.FILE_NOT_FOUND;
    }
    if (errorMsg.contains("permission denied") || errorMsg.contains("access denied")) {
      return FileIoErrorType.PERMISSION_DENIED;
    }
    if (errorMsg.contains("encoding") || errorMsg.contains("charset")) {
      return FileIoErrorType.ENCODING_ERROR;
    }
    if (errorMsg.contains("invalid") || errorMsg.contains("illegal")) {
      return FileIoErrorType.PATH_INVALID;
    }

    // 파일 존재 여부 직접 확인
    if (!Files.exists(filePath)) {
      return FileIoErrorType.FILE_NOT_FOUND;
    }

    return FileIoErrorType.READ_FAILED;
  }

  /**
   * 파일 쓰기 오류 분류
   */
  public FileIoErrorType classifyWriteError(Path filePath, Exception exception) {
    if (exception == null) {
      return FileIoErrorType.UNKNOWN;
    }

    String errorMsg = exception.getMessage().toLowerCase();

    if (errorMsg.contains("permission denied") || errorMsg.contains("access denied")) {
      return FileIoErrorType.PERMISSION_DENIED;
    }
    if (errorMsg.contains("disk") || errorMsg.contains("space")) {
      return FileIoErrorType.DISK_FULL;
    }
    if (errorMsg.contains("invalid") || errorMsg.contains("illegal")) {
      return FileIoErrorType.PATH_INVALID;
    }

    return FileIoErrorType.WRITE_FAILED;
  }

  /**
   * 파일 쓰기 전 사전 검사
   */
  public boolean canWriteFile(Path filePath) {
    try {
      // 부모 디렉토리 존재 확인
      Path parentDir = filePath.getParent();
      if (parentDir != null && !Files.exists(parentDir)) {
        Files.createDirectories(parentDir);
      }

      // 쓰기 권한 확인
      File file = filePath.toFile();
      if (file.exists()) {
        if (!file.canWrite()) {
          log.warn("[파일 쓰기 권한 없음] {}", filePath);
          return false;
        }
      }

      // 디스크 공간 확인
      if (file.getFreeSpace() < 1024 * 1024) { // 1MB 이상 필요
        log.warn("[디스크 공간 부족] {}", filePath);
        return false;
      }

      return true;
    } catch (Exception e) {
      log.error("[파일 쓰기 사전 검사 실패] {}", filePath, e);
      return false;
    }
  }

  /**
   * 사용자 친화적 에러 메시지 생성
   */
  public String getUserFriendlyMessage(FileIoErrorType errorType, String filePath) {
    return switch (errorType) {
      case FILE_NOT_FOUND ->
        String.format("[파일 없음] %s를 찾을 수 없습니다.", filePath);
      case PERMISSION_DENIED ->
        String.format("[권한 없음] %s에 접근할 권한이 없습니다.", filePath);
      case ENCODING_ERROR ->
        String.format("[인코딩 오류] %s의 문자 인코딩을 인식할 수 없습니다.", filePath);
      case DISK_FULL ->
        "[디스크 가득 참] 저장 공간이 부족합니다. 디스크를 정리하세요.";
      case PATH_INVALID ->
        String.format("[경로 오류] %s는 올바르지 않은 경로입니다.", filePath);
      case WRITE_FAILED ->
        String.format("[쓰기 실패] %s에 결과를 저장할 수 없습니다.", filePath);
      case READ_FAILED ->
        String.format("[읽기 실패] %s를 읽는 중 오류가 발생했습니다.", filePath);
      case UNKNOWN ->
        String.format("[알 수 없는 오류] %s 처리 중 오류가 발생했습니다.", filePath);
    };
  }

  /**
   * 상세 로그 메시지 생성
   */
  public String getDetailedErrorLog(FileIoErrorType errorType, String filePath,
    Exception exception) {
    StringBuilder sb = new StringBuilder();
    sb.append("[").append(errorType.name()).append("] ");
    sb.append("파일: ").append(filePath);

    if (exception != null) {
      sb.append(", 원인: ").append(exception.getMessage());
    }

    return sb.toString();
  }

  /**
   * 로깅
   */
  public void logError(FileIoErrorType errorType, String filePath,
    Exception exception, boolean isWarning) {
    String detailedLog = getDetailedErrorLog(errorType, filePath, exception);

    if (isWarning) {
      log.warn(detailedLog);
    } else {
      log.error(detailedLog, exception);
    }
  }

  /**
   * 파일 쓰기 가능 여부 최종 확인
   */
  public boolean validateBeforeWrite(Path filePath) {
    if (!canWriteFile(filePath)) {
      log.error("[파일 쓰기 불가능] {}", filePath);
      return false;
    }
    return true;
  }
}
