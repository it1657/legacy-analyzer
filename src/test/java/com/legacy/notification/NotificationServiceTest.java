package com.legacy.notification;

import com.legacy.analysis.AnalysisHistory;
import com.legacy.auth.User;
import com.legacy.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * NotificationService의 알림 생성/도메인 알림 위임/읽음 처리/삭제/정리 로직을 검증한다.
 * 02-design-v1 3.2절 근거. 모든 public 메서드가 try-catch로 예외를 흡수하는 특성을 함께 고정한다.
 */
class NotificationServiceTest {

  private NotificationRepository notificationRepository;
  private UserRepository userRepository;
  private NotificationService notificationService;

  @BeforeEach
  void setUp() {
    notificationRepository = mock(NotificationRepository.class);
    userRepository = mock(UserRepository.class);
    notificationService = new NotificationService(notificationRepository, userRepository);
  }

  private Notification capturedSavedNotification() {
    ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
    verify(notificationRepository).save(captor.capture());
    return captor.getValue();
  }

  // ---------- createNotification(4-arg) ----------

  @Test
  void createNotification_4arg는_targetId_targetType_actionUrl을_null로_채워_7arg에_위임한다() {
    Notification result = notificationService.createNotification(7L, "SYSTEM", "제목", "내용");

    Notification saved = capturedSavedNotification();
    assertEquals(7L, saved.getUserId());
    assertEquals("SYSTEM", saved.getType());
    assertEquals("제목", saved.getTitle());
    assertEquals("내용", saved.getMessage());
    assertNull(saved.getTargetId());
    assertNull(saved.getTargetType());
    assertNull(saved.getActionUrl());
    assertSame(saved, result);
  }

  // ---------- createNotification(7-arg) ----------

  @Test
  void createNotification_7arg는_save의_반환값이_아니라_내부에서_생성한_객체를_그대로_반환한다() {
    // 특성화 포인트: save()가 전혀 다른 객체를 반환하도록 stub해도 결과는 내부 생성 객체다.
    Notification other = NotificationTestFixtures.newNotification(999L, 999L, "OTHER", "다른", "다른",
        null, null, false, LocalDateTime.now(), null, null);
    when(notificationRepository.save(any(Notification.class))).thenReturn(other);

    Notification result = notificationService.createNotification(1L, "ANALYSIS_COMPLETED", "제목",
        "내용", 55L, "ANALYSIS", "/history");

    Notification saved = capturedSavedNotification();
    assertSame(saved, result);
    assertThat(result).isNotSameAs(other);
    assertEquals(55L, result.getTargetId());
    assertEquals("ANALYSIS", result.getTargetType());
    assertEquals("/history", result.getActionUrl());
  }

  @Test
  void createNotification_7arg는_save가_null을_반환해도_내부_생성_객체를_반환한다() {
    when(notificationRepository.save(any(Notification.class))).thenReturn(null);

    Notification result = notificationService.createNotification(1L, "SYSTEM", "제목", "내용", null,
        null, null);

    assertSame(capturedSavedNotification(), result);
  }

  @Test
  void createNotification_7arg는_save가_예외를_던지면_예외를_흡수하고_null을_반환한다() {
    when(notificationRepository.save(any(Notification.class)))
        .thenThrow(new RuntimeException("DB 오류"));

    Notification result = assertDoesNotThrow(
        () -> notificationService.createNotification(1L, "SYSTEM", "제목", "내용", null, null, null));

    assertNull(result);
  }

  // ---------- notifyAnalysisCompletion ----------

  @Test
  void notifyAnalysisCompletion은_ANALYSIS_COMPLETED_알림을_생성한다() {
    AnalysisHistory analysis =
        NotificationTestFixtures.newAnalysisHistory(30L, 3L, "C:/src/proj", 12);

    notificationService.notifyAnalysisCompletion(analysis);

    Notification saved = capturedSavedNotification();
    assertEquals(3L, saved.getUserId());
    assertEquals("ANALYSIS_COMPLETED", saved.getType());
    assertEquals("분석이 완료되었습니다", saved.getTitle());
    assertEquals("경로 'C:/src/proj'에 대한 분석이 완료되었습니다. (성공: 12개)", saved.getMessage());
    assertEquals(30L, saved.getTargetId());
    assertEquals("ANALYSIS", saved.getTargetType());
    assertEquals("/history", saved.getActionUrl());
  }

  @Test
  void notifyAnalysisCompletion은_save가_예외를_던져도_예외를_전파하지_않는다() {
    // createNotification 내부 catch가 흡수하므로 호출부는 실패 사실 자체를 알 수 없다(반환값 미확인 특성).
    when(notificationRepository.save(any(Notification.class)))
        .thenThrow(new RuntimeException("DB 오류"));
    AnalysisHistory analysis = NotificationTestFixtures.newAnalysisHistory(30L, 3L, "C:/src", 1);

    assertDoesNotThrow(() -> notificationService.notifyAnalysisCompletion(analysis));
  }

  // ---------- notifyAnalysisFailure ----------

  @Test
  void notifyAnalysisFailure는_ANALYSIS_FAILED_알림을_생성한다() {
    AnalysisHistory analysis =
        NotificationTestFixtures.newAnalysisHistory(31L, 4L, "C:/src/fail", 0);

    notificationService.notifyAnalysisFailure(analysis);

    Notification saved = capturedSavedNotification();
    assertEquals(4L, saved.getUserId());
    assertEquals("ANALYSIS_FAILED", saved.getType());
    assertEquals("분석에 실패했습니다", saved.getTitle());
    assertEquals("경로 'C:/src/fail'에 대한 분석 중에 오류가 발생했습니다.", saved.getMessage());
    assertEquals(31L, saved.getTargetId());
    assertEquals("ANALYSIS", saved.getTargetType());
    assertEquals("/history", saved.getActionUrl());
  }

  @Test
  void notifyAnalysisFailure는_save가_예외를_던져도_예외를_전파하지_않는다() {
    when(notificationRepository.save(any(Notification.class)))
        .thenThrow(new RuntimeException("DB 오류"));
    AnalysisHistory analysis = NotificationTestFixtures.newAnalysisHistory(31L, 4L, "C:/src", 0);

    assertDoesNotThrow(() -> notificationService.notifyAnalysisFailure(analysis));
  }

  // ---------- notifyUserCreation ----------

  @Test
  void notifyUserCreation은_userId와_targetId를_모두_신규사용자_seq로_설정한다() {
    User newUser = NotificationTestFixtures.newUser(42L, "newbie", "newbie@example.com");

    notificationService.notifyUserCreation(newUser);

    Notification saved = capturedSavedNotification();
    // 수신자(userId)와 대상(targetId)이 둘 다 신규 사용자 본인이라는 특성을 고정한다.
    assertEquals(42L, saved.getUserId());
    assertEquals(42L, saved.getTargetId());
    assertEquals("USER_CREATED", saved.getType());
    assertEquals("계정이 생성되었습니다", saved.getTitle());
    assertEquals("관리자에 의해 새로운 계정이 생성되었습니다. 사용자 ID: newbie", saved.getMessage());
    assertEquals("USER", saved.getTargetType());
    assertNull(saved.getActionUrl());
  }

  @Test
  void notifyUserCreation은_save가_예외를_던져도_예외를_전파하지_않는다() {
    when(notificationRepository.save(any(Notification.class)))
        .thenThrow(new RuntimeException("DB 오류"));
    User newUser = NotificationTestFixtures.newUser(42L, "newbie", "newbie@example.com");

    assertDoesNotThrow(() -> notificationService.notifyUserCreation(newUser));
  }

  // ---------- notifyAllUsers ----------

  @Test
  void notifyAllUsers는_사용자_수만큼_save를_호출하고_각_알림의_userId가_1대1로_대응한다() {
    List<User> users = Arrays.asList(
        NotificationTestFixtures.newUser(1L, "u1", "u1@example.com"),
        NotificationTestFixtures.newUser(2L, "u2", "u2@example.com"),
        NotificationTestFixtures.newUser(3L, "u3", "u3@example.com"));
    when(userRepository.findAll()).thenReturn(users);

    notificationService.notifyAllUsers("NOTICE", "공지", "내용");

    ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
    verify(notificationRepository, times(3)).save(captor.capture());
    assertThat(captor.getAllValues()).extracting(Notification::getUserId)
        .containsExactly(1L, 2L, 3L);
    assertThat(captor.getAllValues()).allSatisfy(n -> {
      assertEquals("NOTICE", n.getType());
      assertEquals("공지", n.getTitle());
      assertEquals("내용", n.getMessage());
      assertNull(n.getTargetId());
    });
  }

  @Test
  void notifyAllUsers는_findAll이_예외를_던지면_save를_호출하지_않고_예외도_전파하지_않는다() {
    when(userRepository.findAll()).thenThrow(new RuntimeException("조회 실패"));

    assertDoesNotThrow(() -> notificationService.notifyAllUsers("NOTICE", "공지", "내용"));

    verifyNoInteractions(notificationRepository);
  }

  // ---------- markAsRead ----------

  @Test
  void markAsRead는_알림이_존재하면_읽음_처리_후_저장한다() {
    Notification notification = NotificationTestFixtures.newNotification(5L, 1L, "SYSTEM", "제목",
        "내용", null, null, false, LocalDateTime.now().minusHours(1), null, null);
    when(notificationRepository.findById(5L)).thenReturn(Optional.of(notification));
    LocalDateTime before = LocalDateTime.now();

    notificationService.markAsRead(5L);

    Notification saved = capturedSavedNotification();
    assertSame(notification, saved);
    assertTrue(saved.isRead());
    assertThat(saved.getReadAt()).isNotNull();
    assertThat(saved.getReadAt()).isAfterOrEqualTo(before);
  }

  @Test
  void markAsRead는_알림이_없으면_save를_호출하지_않는다() {
    when(notificationRepository.findById(5L)).thenReturn(Optional.empty());

    assertDoesNotThrow(() -> notificationService.markAsRead(5L));

    verify(notificationRepository, never()).save(any(Notification.class));
  }

  @Test
  void markAsRead는_findById가_예외를_던져도_예외를_전파하지_않는다() {
    when(notificationRepository.findById(5L)).thenThrow(new RuntimeException("조회 실패"));

    assertDoesNotThrow(() -> notificationService.markAsRead(5L));

    verify(notificationRepository, never()).save(any(Notification.class));
  }

  // ---------- markAllAsRead ----------

  @Test
  void markAllAsRead는_미읽음_알림_전부를_읽음_처리하고_각각_저장한다() {
    Notification n1 = NotificationTestFixtures.newNotification(1L, 9L, "SYSTEM", "t1", "m1", null,
        null, false, LocalDateTime.now(), null, null);
    Notification n2 = NotificationTestFixtures.newNotification(2L, 9L, "SYSTEM", "t2", "m2", null,
        null, false, LocalDateTime.now(), null, null);
    when(notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(9L))
        .thenReturn(Arrays.asList(n1, n2));

    notificationService.markAllAsRead(9L);

    verify(notificationRepository, times(2)).save(any(Notification.class));
    assertTrue(n1.isRead());
    assertTrue(n2.isRead());
    assertThat(n1.getReadAt()).isNotNull();
    assertThat(n2.getReadAt()).isNotNull();
  }

  @Test
  void markAllAsRead는_미읽음_알림이_없으면_save를_호출하지_않는다() {
    when(notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(9L))
        .thenReturn(Collections.emptyList());

    notificationService.markAllAsRead(9L);

    verify(notificationRepository, never()).save(any(Notification.class));
  }

  @Test
  void markAllAsRead는_조회가_예외를_던져도_예외를_전파하지_않는다() {
    when(notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(9L))
        .thenThrow(new RuntimeException("조회 실패"));

    assertDoesNotThrow(() -> notificationService.markAllAsRead(9L));

    verify(notificationRepository, never()).save(any(Notification.class));
  }

  // ---------- deleteNotification ----------

  @Test
  void deleteNotification은_deleteById를_1회_호출한다() {
    notificationService.deleteNotification(11L);

    verify(notificationRepository, times(1)).deleteById(11L);
  }

  @Test
  void deleteNotification은_deleteById가_예외를_던져도_예외를_전파하지_않는다() {
    org.mockito.Mockito.doThrow(new RuntimeException("삭제 실패"))
        .when(notificationRepository).deleteById(11L);

    assertDoesNotThrow(() -> notificationService.deleteNotification(11L));
  }

  // ---------- cleanupOldNotifications ----------

  @Test
  void cleanupOldNotifications는_오래되고_읽은_알림만_삭제한다() {
    int daysOld = 30;
    LocalDateTime old = LocalDateTime.now().minusDays(daysOld + 1);
    LocalDateTime recent = LocalDateTime.now().minusDays(daysOld - 1);
    Notification oldRead = NotificationTestFixtures.newNotification(1L, 1L, "SYSTEM", "t", "m", null,
        null, true, old, old, null);
    Notification oldUnread = NotificationTestFixtures.newNotification(2L, 1L, "SYSTEM", "t", "m",
        null, null, false, old, null, null);
    Notification recentRead = NotificationTestFixtures.newNotification(3L, 1L, "SYSTEM", "t", "m",
        null, null, true, recent, recent, null);
    Notification recentUnread = NotificationTestFixtures.newNotification(4L, 1L, "SYSTEM", "t", "m",
        null, null, false, recent, null, null);
    when(notificationRepository.findAll())
        .thenReturn(Arrays.asList(oldRead, oldUnread, recentRead, recentUnread));

    notificationService.cleanupOldNotifications(daysOld);

    verify(notificationRepository, times(1)).delete(oldRead);
    verify(notificationRepository, never()).delete(oldUnread);
    verify(notificationRepository, never()).delete(recentRead);
    verify(notificationRepository, never()).delete(recentUnread);
  }

  @Test
  void cleanupOldNotifications는_findAll이_예외를_던져도_예외를_전파하지_않는다() {
    when(notificationRepository.findAll()).thenThrow(new RuntimeException("조회 실패"));

    assertDoesNotThrow(() -> notificationService.cleanupOldNotifications(30));

    verify(notificationRepository, never()).delete(any(Notification.class));
  }

  @Test
  void cleanupOldNotifications는_createdAt이_null인_알림만_건너뛰고_나머지_대상은_정상_삭제한다() {
    // REQ-007 수정 후 확정 동작(02-design-v1 2.4절 근거).
    // createdAt이 null인 항목은 정리 대상 판단이 불가능하므로 경고 로그만 남기고 필터에서 제외되며(삭제 안 함),
    // 스트림이 중단되지 않으므로 뒤따르는 "오래됨+읽음" 항목(oldRead)은 예정대로 삭제된다.
    // 목록 순서상 null 항목이 먼저 평가되는 배치를 그대로 유지해, 이전의 "전량 스킵" 회귀를 잡아낼 수 있게 한다.
    int daysOld = 30;
    LocalDateTime old = LocalDateTime.now().minusDays(daysOld + 1);
    Notification nullCreatedAt = NotificationTestFixtures.newNotification(1L, 1L, "SYSTEM", "t", "m",
        null, null, true, null, null, null);
    Notification oldRead = NotificationTestFixtures.newNotification(2L, 1L, "SYSTEM", "t", "m", null,
        null, true, old, old, null);
    when(notificationRepository.findAll()).thenReturn(Arrays.asList(nullCreatedAt, oldRead));

    assertDoesNotThrow(() -> notificationService.cleanupOldNotifications(daysOld));

    verify(notificationRepository, never()).delete(nullCreatedAt);
    verify(notificationRepository, times(1)).delete(oldRead);
  }
}
