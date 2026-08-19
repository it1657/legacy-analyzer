package com.legacy.admin;

import com.legacy.analysis.AnalysisHistory;
import com.legacy.auth.Role;
import com.legacy.auth.User;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * com.legacy.admin 패키지 단위 테스트에서 공용으로 사용하는 픽스처 생성 헬퍼.
 * 리플렉션 없이 프로덕션 코드의 기존 생성자/getter/setter만 사용한다
 * (02-design-v1 3절 근거, 공용 유틸로 승격하지 않고 이 패키지 내부에 독립적으로 유지).
 */
class AdminTestFixtures {

  private AdminTestFixtures() {
  }

  /** 임의 description을 가진 Role 인스턴스를 생성한다. */
  static Role newRole(String name) {
    return new Role(name, name + " 역할");
  }

  /** seq/active/roles를 setter로 채운 User 인스턴스를 생성한다. */
  static User newUser(Long seq, String userId, String email, boolean active, Set<Role> roles) {
    User user = new User(userId, email, "hash");
    user.setSeq(seq);
    user.setActive(active);
    user.setRoles(roles);
    return user;
  }

  /** 전 필드를 setter로 채운 AnalysisHistory 인스턴스를 생성한다. sessionId는 필요 시 호출부에서 별도 세팅한다. */
  static AnalysisHistory newAnalysisHistory(Long id, Long userId, String sourcePath, String outputPath,
      Integer totalFiles, Integer successCount, Integer skipCount, Integer failureCount,
      Long processingTimeMs, String status, LocalDateTime createdAt, LocalDateTime completedAt,
      String notes) {
    AnalysisHistory history = new AnalysisHistory();
    history.setId(id);
    history.setUserId(userId);
    history.setSourcePath(sourcePath);
    history.setOutputPath(outputPath);
    history.setTotalFiles(totalFiles);
    history.setSuccessCount(successCount);
    history.setSkipCount(skipCount);
    history.setFailureCount(failureCount);
    history.setProcessingTimeMs(processingTimeMs);
    history.setStatus(status);
    history.setCreatedAt(createdAt);
    history.setCompletedAt(completedAt);
    history.setNotes(notes);
    return history;
  }
}
