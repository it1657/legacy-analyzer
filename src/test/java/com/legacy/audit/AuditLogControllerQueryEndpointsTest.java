package com.legacy.audit;

import com.legacy.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditLogController의 나머지 4개 조회 엔드포인트
 * (getAuditLogsByPeriod / getUserActivityLogs / getAuditLogStatistics / getRecentActivity)의
 * 정상/예외 동작을 검증한다.
 */
class AuditLogControllerQueryEndpointsTest {

  private AuditLogRepository auditLogRepository;
  // 대상 메서드들에서 미사용, 생성자 주입용 mock
  private UserRepository userRepository;
  private AuditLogController controller;

  @BeforeEach
  void setUp() {
    auditLogRepository = mock(AuditLogRepository.class);
    userRepository = mock(UserRepository.class);
    controller = new AuditLogController(auditLogRepository, userRepository);
  }

  @Test
  void 기간별_감사로그_조회가_정상이면_200과_필드매핑된_응답을_반환한다() {
    LocalDateTime startTime = LocalDateTime.of(2026, 7, 1, 0, 0, 0);
    LocalDateTime endTime = LocalDateTime.of(2026, 7, 31, 23, 59, 59);
    LocalDateTime timestamp = LocalDateTime.of(2026, 7, 15, 10, 0, 0);
    AuditLog log = AuditLogTestFixtures.newAuditLog(1L, 2L, "hong", "LOGIN", "USER", null, "hong",
        "SUCCESS", null, "상세", timestamp, "127.0.0.1");
    when(auditLogRepository.findByTimestampBetween(startTime, endTime))
        .thenReturn(List.of(log));

    ResponseEntity<?> response = controller.getAuditLogsByPeriod(startTime, endTime);

    verify(auditLogRepository).findByTimestampBetween(startTime, endTime);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    assertEquals(1, body.size());
    Map<String, Object> map = body.get(0);
    assertEquals(log.getId(), map.get("id"));
    assertEquals(log.getUserId(), map.get("userId"));
    assertEquals(log.getUsername(), map.get("username"));
    assertEquals(log.getAction(), map.get("action"));
    assertEquals(log.getTarget(), map.get("target"));
    assertEquals(log.getTargetId(), map.get("targetId"));
    assertEquals(log.getTargetName(), map.get("targetName"));
    assertEquals(log.getStatus(), map.get("status"));
    assertEquals(log.getChanges(), map.get("changes"));
    assertEquals(log.getDetails(), map.get("details"));
    assertEquals(log.getTimestamp(), map.get("timestamp"));
    assertEquals(log.getIpAddress(), map.get("ipAddress"));
  }

  @Test
  void 기간별_감사로그_조회중_예외가_발생하면_400과_기간별_조회실패_메시지를_응답한다() {
    LocalDateTime startTime = LocalDateTime.of(2026, 7, 1, 0, 0, 0);
    LocalDateTime endTime = LocalDateTime.of(2026, 7, 31, 23, 59, 59);
    when(auditLogRepository.findByTimestampBetween(startTime, endTime))
        .thenThrow(new RuntimeException("기간 오류"));

    ResponseEntity<?> response = controller.getAuditLogsByPeriod(startTime, endTime);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertEquals("기간별 감사 로그 조회 실패: 기간 오류", body.get("message"));
  }

  @Test
  void 사용자_활동로그_조회가_정상이면_findUserActivityLogs가_호출되고_필드매핑된_응답을_반환한다() {
    Long userId = 5L;
    int limit = 30;
    LocalDateTime timestamp = LocalDateTime.of(2026, 7, 10, 9, 0, 0);
    AuditLog log = AuditLogTestFixtures.newAuditLog(1L, userId, "kim", "UPDATE", "USER", userId,
        "kim", "SUCCESS", null, "상세", timestamp, "10.0.0.1");
    when(auditLogRepository.findUserActivityLogs(userId, limit)).thenReturn(List.of(log));

    ResponseEntity<?> response = controller.getUserActivityLogs(userId, limit);

    verify(auditLogRepository).findUserActivityLogs(userId, limit);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    assertEquals(1, body.size());
    Map<String, Object> map = body.get(0);
    assertEquals(log.getId(), map.get("id"));
    assertEquals(log.getUserId(), map.get("userId"));
    assertEquals(log.getUsername(), map.get("username"));
    assertEquals(log.getAction(), map.get("action"));
    assertEquals(log.getTarget(), map.get("target"));
    assertEquals(log.getTargetId(), map.get("targetId"));
    assertEquals(log.getTargetName(), map.get("targetName"));
    assertEquals(log.getStatus(), map.get("status"));
    assertEquals(log.getChanges(), map.get("changes"));
    assertEquals(log.getDetails(), map.get("details"));
    assertEquals(log.getTimestamp(), map.get("timestamp"));
    assertEquals(log.getIpAddress(), map.get("ipAddress"));
  }

  @Test
  void 사용자_활동로그_조회중_예외가_발생하면_400과_사용자_활동로그_조회실패_메시지를_응답한다() {
    Long userId = 5L;
    int limit = 30;
    when(auditLogRepository.findUserActivityLogs(userId, limit))
        .thenThrow(new RuntimeException("사용자 조회 오류"));

    ResponseEntity<?> response = controller.getUserActivityLogs(userId, limit);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertTrue(((String) body.get("message")).startsWith("사용자 활동 로그 조회 실패: "));
  }

  @Test
  void 액션통계_조회가_정상이면_days만큼의_기간으로_조회하고_action_count_형태로_변환한다() {
    int days = 7;
    when(auditLogRepository.getActionStatistics(any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(Arrays.<Object[]>asList(
            new Object[] { "LOGIN", 5L },
            new Object[] { "CREATE", 2L }));

    ResponseEntity<?> response = controller.getAuditLogStatistics(days);

    ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
    ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(auditLogRepository).getActionStatistics(startCaptor.capture(), endCaptor.capture());

    long actualDays = Duration.between(startCaptor.getValue(), endCaptor.getValue()).toDays();
    assertEquals(days, actualDays);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    assertEquals(2, body.size());
    assertEquals("LOGIN", body.get(0).get("action"));
    assertEquals(5L, body.get(0).get("count"));
    assertEquals("CREATE", body.get(1).get("action"));
    assertEquals(2L, body.get(1).get("count"));
  }

  @Test
  void 액션통계_조회중_예외가_발생하면_500과_액션_통계_조회실패_메시지를_응답한다() {
    when(auditLogRepository.getActionStatistics(any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenThrow(new RuntimeException("통계 오류"));

    ResponseEntity<?> response = controller.getAuditLogStatistics(30);

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertTrue(((String) body.get("message")).startsWith("액션 통계 조회 실패: "));
  }

  @Test
  void 최근활동_조회가_정상이면_findRecentLogs가_호출되고_필드매핑된_응답을_반환한다() {
    int limit = 20;
    LocalDateTime timestamp = LocalDateTime.of(2026, 7, 20, 15, 0, 0);
    AuditLog log = AuditLogTestFixtures.newAuditLog(1L, 3L, "lee", "DELETE", "ANALYSIS", 9L,
        "lee", "SUCCESS", null, "상세", timestamp, "192.168.0.1");
    when(auditLogRepository.findRecentLogs(limit)).thenReturn(List.of(log));

    ResponseEntity<?> response = controller.getRecentActivity(limit);

    verify(auditLogRepository).findRecentLogs(eq(limit));
    assertEquals(HttpStatus.OK, response.getStatusCode());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    assertEquals(1, body.size());
    Map<String, Object> map = body.get(0);
    assertEquals(log.getId(), map.get("id"));
    assertEquals(log.getUserId(), map.get("userId"));
    assertEquals(log.getUsername(), map.get("username"));
    assertEquals(log.getAction(), map.get("action"));
    assertEquals(log.getTarget(), map.get("target"));
    assertEquals(log.getTargetId(), map.get("targetId"));
    assertEquals(log.getTargetName(), map.get("targetName"));
    assertEquals(log.getStatus(), map.get("status"));
    assertEquals(log.getChanges(), map.get("changes"));
    assertEquals(log.getDetails(), map.get("details"));
    assertEquals(log.getTimestamp(), map.get("timestamp"));
    assertEquals(log.getIpAddress(), map.get("ipAddress"));
  }

  @Test
  void 최근활동_조회중_예외가_발생하면_500과_최근_활동_조회실패_메시지를_응답한다() {
    int limit = 20;
    when(auditLogRepository.findRecentLogs(limit)).thenThrow(new RuntimeException("최근 활동 오류"));

    ResponseEntity<?> response = controller.getRecentActivity(limit);

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertTrue(((String) body.get("message")).startsWith("최근 활동 조회 실패: "));
  }
}
