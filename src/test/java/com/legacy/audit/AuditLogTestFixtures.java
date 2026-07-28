package com.legacy.audit;

import com.legacy.analysis.AnalysisHistory;
import com.legacy.auth.Role;
import com.legacy.auth.User;
import org.mockito.Mockito;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.LocalDateTime;
import java.util.Set;

import static org.mockito.Mockito.when;

/**
 * com.legacy.audit 패키지 단위 테스트에서 공용으로 사용하는 픽스처 생성 헬퍼.
 * 프로덕션 코드의 생성자/getter/setter만 사용하며 리플렉션을 사용하지 않는다.
 */
class AuditLogTestFixtures {

  private AuditLogTestFixtures() {
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

  /** 기본 생성자 + setter로 채운 AnalysisHistory 인스턴스를 생성한다. */
  static AnalysisHistory newAnalysisHistory(Long id, String sourcePath, int totalFiles,
      int successCount, int failureCount, long processingTimeMs) {
    AnalysisHistory history = new AnalysisHistory();
    history.setId(id);
    history.setSourcePath(sourcePath);
    history.setTotalFiles(totalFiles);
    history.setSuccessCount(successCount);
    history.setFailureCount(failureCount);
    history.setProcessingTimeMs(processingTimeMs);
    return history;
  }

  /** 12개 필드 전부를 setter로 채운 AuditLog 인스턴스를 생성한다. */
  static AuditLog newAuditLog(Long id, Long userId, String username, String action, String target,
      Long targetId, String targetName, String status, String changes, String details,
      LocalDateTime timestamp, String ipAddress) {
    AuditLog auditLog = new AuditLog();
    auditLog.setId(id);
    auditLog.setUserId(userId);
    auditLog.setUsername(username);
    auditLog.setAction(action);
    auditLog.setTarget(target);
    auditLog.setTargetId(targetId);
    auditLog.setTargetName(targetName);
    auditLog.setStatus(status);
    auditLog.setChanges(changes);
    auditLog.setDetails(details);
    auditLog.setTimestamp(timestamp);
    auditLog.setIpAddress(ipAddress);
    return auditLog;
  }

  /** SecurityContext에 인증된 User principal을 세팅한다. */
  static void setAuthenticatedUser(User user) {
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
  }

  /** principal이 User가 아닌(SYSTEM 폴백 검증용) Authentication을 세팅한다. */
  static void setNonUserAuthentication() {
    Authentication authentication = Mockito.mock(Authentication.class);
    when(authentication.getPrincipal()).thenReturn("anonymousUser");
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
