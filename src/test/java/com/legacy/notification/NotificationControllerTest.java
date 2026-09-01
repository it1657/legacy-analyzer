package com.legacy.notification;

import com.legacy.auth.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotificationController 6개 엔드포인트의 분기/응답 필드/예외 처리(개별 try-catch, 400 반환)를 검증한다.
 * 02-design-v1 3.3절 근거.
 */
class NotificationControllerTest {

  private NotificationRepository notificationRepository;
  private NotificationService notificationService;
  private Authentication authentication;
  private NotificationController notificationController;
  private User currentUser;

  @BeforeEach
  void setUp() {
    notificationRepository = mock(NotificationRepository.class);
    notificationService = mock(NotificationService.class);
    authentication = mock(Authentication.class);
    currentUser = NotificationTestFixtures.newUser(7L, "alice", "alice@example.com");
    when(authentication.getPrincipal()).thenReturn(currentUser);

    notificationController = new NotificationController(notificationRepository, notificationService);
  }

  private static String messageOf(ResponseEntity<?> response) {
    return (String) ((Map<?, ?>) response.getBody()).get("message");
  }

  // ---------- getMyNotifications ----------

  @Test
  void getMyNotifications는_unreadOnly가_false면_전체_목록을_조회하고_10개_필드를_매핑한다() {
    LocalDateTime createdAt = LocalDateTime.of(2026, 9, 1, 10, 0);
    LocalDateTime readAt = LocalDateTime.of(2026, 9, 1, 11, 0);
    Notification notification = NotificationTestFixtures.newNotification(3L, 7L,
        "ANALYSIS_COMPLETED", "제목", "내용", 55L, "ANALYSIS", true, createdAt, readAt, "/history");
    when(notificationRepository.findByUserIdOrderByCreatedAtDesc(7L))
        .thenReturn(Collections.singletonList(notification));

    ResponseEntity<?> response = notificationController.getMyNotifications(false, authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    List<?> body = (List<?>) response.getBody();
    assertThat(body).hasSize(1);
    Map<?, ?> map = (Map<?, ?>) body.get(0);
    assertEquals(3L, map.get("id"));
    assertEquals("ANALYSIS_COMPLETED", map.get("type"));
    assertEquals("제목", map.get("title"));
    assertEquals("내용", map.get("message"));
    assertEquals(true, map.get("isRead"));
    assertEquals(55L, map.get("targetId"));
    assertEquals("ANALYSIS", map.get("targetType"));
    assertEquals("/history", map.get("actionUrl"));
    assertEquals(createdAt, map.get("createdAt"));
    assertEquals(readAt, map.get("readAt"));
    verify(notificationRepository, never())
        .findByUserIdAndIsReadFalseOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void getMyNotifications는_unreadOnly가_true면_미읽음_목록만_조회한다() {
    Notification notification = NotificationTestFixtures.newNotification(4L, 7L, "SYSTEM", "t", "m",
        null, null, false, LocalDateTime.now(), null, null);
    when(notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(7L))
        .thenReturn(Collections.singletonList(notification));

    ResponseEntity<?> response = notificationController.getMyNotifications(true, authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertThat((List<?>) response.getBody()).hasSize(1);
    verify(notificationRepository, times(1)).findByUserIdAndIsReadFalseOrderByCreatedAtDesc(7L);
    verify(notificationRepository, never())
        .findByUserIdOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void getMyNotifications는_조회_실패_시_400과_알림_조회_실패_메시지를_반환한다() {
    when(notificationRepository.findByUserIdOrderByCreatedAtDesc(7L))
        .thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = notificationController.getMyNotifications(false, authentication);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("알림 조회 실패: DB 오류", messageOf(response));
  }

  // ---------- getUnreadCount ----------

  @Test
  void getUnreadCount는_미읽음_개수를_unreadCount_필드로_반환한다() {
    when(notificationRepository.countUnreadNotifications(7L)).thenReturn(5L);

    ResponseEntity<?> response = notificationController.getUnreadCount(authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(5L, ((Map<?, ?>) response.getBody()).get("unreadCount"));
  }

  @Test
  void getUnreadCount는_조회_실패_시_400과_미읽음_개수_조회_실패_메시지를_반환한다() {
    when(notificationRepository.countUnreadNotifications(7L))
        .thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = notificationController.getUnreadCount(authentication);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("미읽음 개수 조회 실패: DB 오류", messageOf(response));
  }

  // ---------- markAsRead ----------

  @Test
  void markAsRead는_서비스에_위임하고_200과_고정_메시지를_반환한다() {
    ResponseEntity<?> response = notificationController.markAsRead(9L);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("알림이 읽음 처리되었습니다.", messageOf(response));
    verify(notificationService, times(1)).markAsRead(9L);
  }

  @Test
  void markAsRead는_서비스가_예외를_던지면_400과_읽음_처리_실패_메시지를_반환한다() {
    doThrow(new RuntimeException("처리 오류")).when(notificationService).markAsRead(9L);

    ResponseEntity<?> response = notificationController.markAsRead(9L);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("읽음 처리 실패: 처리 오류", messageOf(response));
  }

  // ---------- markAllAsRead ----------

  @Test
  void markAllAsRead는_인증사용자_seq로_서비스에_위임하고_200과_고정_메시지를_반환한다() {
    ResponseEntity<?> response = notificationController.markAllAsRead(authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("모든 알림이 읽음 처리되었습니다.", messageOf(response));
    verify(notificationService, times(1)).markAllAsRead(7L);
  }

  @Test
  void markAllAsRead는_예외_시_markAsRead와_동일한_읽음_처리_실패_prefix를_사용한다() {
    // 특성화 포인트: 서로 다른 엔드포인트(markAsRead / markAllAsRead)인데 catch 메시지 prefix가 "읽음 처리 실패: "로 동일하다.
    doThrow(new RuntimeException("처리 오류")).when(notificationService).markAllAsRead(7L);

    ResponseEntity<?> response = notificationController.markAllAsRead(authentication);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("읽음 처리 실패: 처리 오류", messageOf(response));
  }

  // ---------- deleteNotification ----------

  @Test
  void deleteNotification은_서비스에_위임하고_200과_고정_메시지를_반환한다() {
    ResponseEntity<?> response = notificationController.deleteNotification(12L);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("알림이 삭제되었습니다.", messageOf(response));
    verify(notificationService, times(1)).deleteNotification(12L);
  }

  @Test
  void deleteNotification은_서비스가_예외를_던지면_400과_삭제_실패_메시지를_반환한다() {
    doThrow(new RuntimeException("삭제 오류")).when(notificationService).deleteNotification(12L);

    ResponseEntity<?> response = notificationController.deleteNotification(12L);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("삭제 실패: 삭제 오류", messageOf(response));
  }

  // ---------- deleteAllNotifications ----------

  @Test
  void deleteAllNotifications는_내_알림_수만큼_서비스_삭제를_호출한다() {
    Notification n1 = NotificationTestFixtures.newNotification(1L, 7L, "SYSTEM", "t1", "m1", null,
        null, false, LocalDateTime.now(), null, null);
    Notification n2 = NotificationTestFixtures.newNotification(2L, 7L, "SYSTEM", "t2", "m2", null,
        null, true, LocalDateTime.now(), LocalDateTime.now(), null);
    when(notificationRepository.findByUserId(7L)).thenReturn(Arrays.asList(n1, n2));

    ResponseEntity<?> response = notificationController.deleteAllNotifications(authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("모든 알림이 삭제되었습니다.", messageOf(response));
    verify(notificationService, times(1)).deleteNotification(1L);
    verify(notificationService, times(1)).deleteNotification(2L);
    verify(notificationService, times(2))
        .deleteNotification(org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void deleteAllNotifications는_예외_시_deleteNotification과_동일한_삭제_실패_prefix를_사용한다() {
    // 특성화 포인트: deleteNotification / deleteAllNotifications의 catch 메시지 prefix가 "삭제 실패: "로 동일하다.
    when(notificationRepository.findByUserId(7L)).thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = notificationController.deleteAllNotifications(authentication);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("삭제 실패: DB 오류", messageOf(response));
    verify(notificationService, never())
        .deleteNotification(org.mockito.ArgumentMatchers.anyLong());
  }
}
