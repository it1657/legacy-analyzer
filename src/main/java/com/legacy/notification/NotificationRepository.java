package com.legacy.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

  List<Notification> findByUserId(Long userId);

  List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);

  List<Notification> findByUserIdAndIsReadFalseOrderByCreatedAtDesc(Long userId);

  List<Notification> findByUserIdAndType(Long userId, String type);

  long countByUserIdAndIsReadFalse(Long userId);

  // 미읽음 알림 개수
  @Query("SELECT COUNT(n) FROM Notification n WHERE n.userId = :userId AND n.isRead = false")
  long countUnreadNotifications(@Param("userId") Long userId);

  // 모든 사용자에게 알림 생성 (관리자 공지용)
  List<Notification> findByTypeOrderByCreatedAtDesc(String type);
}
