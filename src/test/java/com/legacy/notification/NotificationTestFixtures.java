package com.legacy.notification;

import com.legacy.analysis.AnalysisHistory;
import com.legacy.auth.User;

import java.time.LocalDateTime;

/**
 * com.legacy.notification 패키지 단위 테스트에서 공용으로 사용하는 픽스처 생성 헬퍼.
 * 리플렉션 없이 프로덕션 코드의 기존 생성자/getter/setter만 사용한다
 * (02-design-v1 3.1절 근거, 공용 유틸로 승격하지 않고 이 패키지 내부에 독립적으로 유지).
 */
class NotificationTestFixtures {

  private NotificationTestFixtures() {
  }

  /** seq를 setter로 채운 User 인스턴스를 생성한다. */
  static User newUser(Long seq, String userId, String email) {
    User user = new User(userId, email, "hash");
    user.setSeq(seq);
    return user;
  }

  /**
   * 6-arg 생성자로 만든 뒤 나머지 필드를 setter로 전부 채운 Notification 인스턴스를 생성한다.
   * 생성자가 createdAt을 now로 자동 세팅하므로, 테스트에서 시각을 고정하려면 setter로 덮어쓴다.
   */
  static Notification newNotification(Long id, Long userId, String type, String title,
      String message, Long targetId, String targetType, boolean read, LocalDateTime createdAt,
      LocalDateTime readAt, String actionUrl) {
    Notification notification = new Notification(userId, type, title, message, targetId, targetType);
    notification.setId(id);
    notification.setRead(read);
    notification.setCreatedAt(createdAt);
    notification.setReadAt(readAt);
    notification.setActionUrl(actionUrl);
    return notification;
  }

  /** NotificationService가 실제로 사용하는 4개 필드만 채운 AnalysisHistory 인스턴스를 생성한다. */
  static AnalysisHistory newAnalysisHistory(Long id, Long userId, String sourcePath,
      Integer successCount) {
    AnalysisHistory history = new AnalysisHistory();
    history.setId(id);
    history.setUserId(userId);
    history.setSourcePath(sourcePath);
    history.setSuccessCount(successCount);
    return history;
  }
}
