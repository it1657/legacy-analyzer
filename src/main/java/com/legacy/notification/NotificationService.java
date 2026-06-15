package com.legacy.notification;
import com.legacy.auth.User;
import com.legacy.analysis.AnalysisHistory;
import com.legacy.auth.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@Service
public class NotificationService {

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

  private final NotificationRepository notificationRepository;
  private final UserRepository userRepository;

  @Autowired
  public NotificationService(NotificationRepository notificationRepository,
      UserRepository userRepository) {
    this.notificationRepository = notificationRepository;
    this.userRepository = userRepository;
  }

  // 알림 생성
  public Notification createNotification(Long userId, String type, String title,
      String message) {
    return createNotification(userId, type, title, message, null, null, null);
  }

  // 알림 생성 (대상 정보 포함)
  public Notification createNotification(Long userId, String type, String title, String message,
      Long targetId, String targetType, String actionUrl) {
    try {
      Notification notification = new Notification(userId, type, title, message, targetId,
          targetType);
      notification.setActionUrl(actionUrl);

      notificationRepository.save(notification);

      log.info("[알림 생성] userId={}, type={}, title={}", userId, type, title);
      return notification;
    } catch (Exception e) {
      log.error("[알림 생성 실패]", e);
      return null;
    }
  }

  // 분석 완료 알림
  public void notifyAnalysisCompletion(AnalysisHistory analysis) {
    try {
      String title = "분석이 완료되었습니다";
      String message = String.format("경로 '%s'에 대한 분석이 완료되었습니다. (성공: %d개)",
          analysis.getSourcePath(), analysis.getSuccessCount());

      createNotification(analysis.getUserId(), "ANALYSIS_COMPLETED", title, message,
          analysis.getId(), "ANALYSIS", "/history");

      log.info("[분석 완료 알림] userId={}, analysisId={}", analysis.getUserId(),
          analysis.getId());
    } catch (Exception e) {
      log.error("[분석 완료 알림 실패]", e);
    }
  }

  // 분석 실패 알림
  public void notifyAnalysisFailure(AnalysisHistory analysis) {
    try {
      String title = "분석에 실패했습니다";
      String message = String.format("경로 '%s'에 대한 분석 중에 오류가 발생했습니다.",
          analysis.getSourcePath());

      createNotification(analysis.getUserId(), "ANALYSIS_FAILED", title, message,
          analysis.getId(), "ANALYSIS", "/history");

      log.info("[분석 실패 알림] userId={}, analysisId={}", analysis.getUserId(),
          analysis.getId());
    } catch (Exception e) {
      log.error("[분석 실패 알림 실패]", e);
    }
  }

  // 사용자 생성 알림 (새 사용자에게)
  public void notifyUserCreation(User newUser) {
    try {
      String title = "계정이 생성되었습니다";
      String message = String.format("관리자에 의해 새로운 계정이 생성되었습니다. 사용자명: %s",
          newUser.getUsername());

      createNotification(newUser.getId(), "USER_CREATED", title, message, newUser.getId(),
          "USER", null);

      log.info("[사용자 생성 알림] userId={}", newUser.getId());
    } catch (Exception e) {
      log.error("[사용자 생성 알림 실패]", e);
    }
  }

  // 공지 알림 (모든 사용자에게)
  public void notifyAllUsers(String type, String title, String message) {
    try {
      userRepository.findAll().forEach(user -> {
        createNotification(user.getId(), type, title, message);
      });

      log.info("[공지 알림 전송] type={}, title={}, 대상={}", type, title,
          userRepository.count());
    } catch (Exception e) {
      log.error("[공지 알림 전송 실패]", e);
    }
  }

  // 알림 읽음 처리
  public void markAsRead(Long notificationId) {
    try {
      Notification notification = notificationRepository.findById(notificationId)
          .orElse(null);

      if (notification != null) {
        notification.setRead(true);
        notification.setReadAt(LocalDateTime.now());
        notificationRepository.save(notification);

        log.debug("[알림 읽음] notificationId={}", notificationId);
      }
    } catch (Exception e) {
      log.error("[알림 읽음 처리 실패]", e);
    }
  }

  // 모든 알림 읽음 처리
  public void markAllAsRead(Long userId) {
    try {
      notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(userId)
          .forEach(notification -> {
            notification.setRead(true);
            notification.setReadAt(LocalDateTime.now());
            notificationRepository.save(notification);
          });

      log.debug("[모든 알림 읽음] userId={}", userId);
    } catch (Exception e) {
      log.error("[모든 알림 읽음 처리 실패]", e);
    }
  }

  // 알림 삭제
  public void deleteNotification(Long notificationId) {
    try {
      notificationRepository.deleteById(notificationId);
      log.debug("[알림 삭제] notificationId={}", notificationId);
    } catch (Exception e) {
      log.error("[알림 삭제 실패]", e);
    }
  }

  // 오래된 알림 정리 (30일 이상)
  public void cleanupOldNotifications(int daysOld) {
    try {
      LocalDateTime cutoffDate = LocalDateTime.now().minusDays(daysOld);
      notificationRepository.findAll()
          .stream()
          .filter(n -> n.getCreatedAt().isBefore(cutoffDate) && n.isRead())
          .forEach(n -> notificationRepository.delete(n));

      log.info("[오래된 알림 정리] {}일 이상 이전의 읽은 알림 제거", daysOld);
    } catch (Exception e) {
      log.error("[오래된 알림 정리 실패]", e);
    }
  }
}
