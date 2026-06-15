package com.legacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

  private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

  private final NotificationRepository notificationRepository;
  private final NotificationService notificationService;

  @Autowired
  public NotificationController(NotificationRepository notificationRepository,
      NotificationService notificationService) {
    this.notificationRepository = notificationRepository;
    this.notificationService = notificationService;
  }

  // 내 알림 목록 조회
  @GetMapping
  @ResponseBody
  public ResponseEntity<?> getMyNotifications(
      @RequestParam(defaultValue = "false") boolean unreadOnly,
      Authentication authentication) {
    try {
      User user = (User) authentication.getPrincipal();
      List<Notification> notifications;

      if (unreadOnly) {
        notifications = notificationRepository
            .findByUserIdAndIsReadFalseOrderByCreatedAtDesc(user.getId());
      } else {
        notifications = notificationRepository
            .findByUserIdOrderByCreatedAtDesc(user.getId());
      }

      List<Map<String, Object>> response = notifications.stream()
          .map(this::convertToMap)
          .toList();

      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("[알림 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "알림 조회 실패: " + e.getMessage()));
    }
  }

  // 미읽음 알림 개수
  @GetMapping("/unread-count")
  @ResponseBody
  public ResponseEntity<?> getUnreadCount(Authentication authentication) {
    try {
      User user = (User) authentication.getPrincipal();
      long unreadCount = notificationRepository.countUnreadNotifications(user.getId());

      Map<String, Object> response = new HashMap<>();
      response.put("unreadCount", unreadCount);

      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("[미읽음 개수 조회 실패]", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "미읽음 개수 조회 실패: " + e.getMessage()));
    }
  }

  // 알림 읽음 처리
  @PostMapping("/{notificationId}/read")
  @ResponseBody
  public ResponseEntity<?> markAsRead(@PathVariable Long notificationId) {
    try {
      notificationService.markAsRead(notificationId);

      return ResponseEntity.ok(Collections.singletonMap("message", "알림이 읽음 처리되었습니다."));
    } catch (Exception e) {
      log.error("[알림 읽음 처리 실패] notificationId={}", notificationId, e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "읽음 처리 실패: " + e.getMessage()));
    }
  }

  // 모든 알림 읽음 처리
  @PostMapping("/read-all")
  @ResponseBody
  public ResponseEntity<?> markAllAsRead(Authentication authentication) {
    try {
      User user = (User) authentication.getPrincipal();
      notificationService.markAllAsRead(user.getId());

      return ResponseEntity.ok(Collections.singletonMap("message", "모든 알림이 읽음 처리되었습니다."));
    } catch (Exception e) {
      log.error("[모든 알림 읽음 처리 실패]", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "읽음 처리 실패: " + e.getMessage()));
    }
  }

  // 알림 삭제
  @DeleteMapping("/{notificationId}")
  @ResponseBody
  public ResponseEntity<?> deleteNotification(@PathVariable Long notificationId) {
    try {
      notificationService.deleteNotification(notificationId);

      return ResponseEntity.ok(Collections.singletonMap("message", "알림이 삭제되었습니다."));
    } catch (Exception e) {
      log.error("[알림 삭제 실패] notificationId={}", notificationId, e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "삭제 실패: " + e.getMessage()));
    }
  }

  // 모든 알림 삭제
  @DeleteMapping
  @ResponseBody
  public ResponseEntity<?> deleteAllNotifications(Authentication authentication) {
    try {
      User user = (User) authentication.getPrincipal();
      notificationRepository.findByUserId(user.getId())
          .forEach(notification -> notificationService.deleteNotification(notification.getId()));

      return ResponseEntity.ok(Collections.singletonMap("message", "모든 알림이 삭제되었습니다."));
    } catch (Exception e) {
      log.error("[모든 알림 삭제 실패]", e);
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Collections.singletonMap("message", "삭제 실패: " + e.getMessage()));
    }
  }

  // Notification을 맵으로 변환
  private Map<String, Object> convertToMap(Notification notification) {
    Map<String, Object> map = new HashMap<>();
    map.put("id", notification.getId());
    map.put("type", notification.getType());
    map.put("title", notification.getTitle());
    map.put("message", notification.getMessage());
    map.put("isRead", notification.isRead());
    map.put("targetId", notification.getTargetId());
    map.put("targetType", notification.getTargetType());
    map.put("actionUrl", notification.getActionUrl());
    map.put("createdAt", notification.getCreatedAt());
    map.put("readAt", notification.getReadAt());
    return map;
  }
}
