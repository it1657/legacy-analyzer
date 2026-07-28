package com.legacy.audit;

import com.legacy.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditLogController.getAllAuditLogs의 5개 분기(userId/action+target/action/target/기본),
 * convertToMap 필드 매핑, 조회 실패 시 예외 처리를 검증한다.
 */
class AuditLogControllerGetAllAuditLogsTest {

  private AuditLogRepository auditLogRepository;
  // 대상 메서드(getAllAuditLogs)에서 미사용, 생성자 주입용 mock
  private UserRepository userRepository;
  private AuditLogController controller;

  @BeforeEach
  void setUp() {
    auditLogRepository = mock(AuditLogRepository.class);
    userRepository = mock(UserRepository.class);
    controller = new AuditLogController(auditLogRepository, userRepository);
  }

  @Test
  void userId_파라미터가_있으면_findUserActivityLogs만_호출되고_다른_조회메서드는_호출되지_않는다() {
    Long userId = 10L;
    int limit = 20;
    when(auditLogRepository.findUserActivityLogs(userId, limit)).thenReturn(
        List.of(AuditLogTestFixtures.newAuditLog(1L, userId, "hong", "LOGIN", "USER", null,
            "hong", "SUCCESS", null, null, LocalDateTime.now(), "127.0.0.1")));

    // action/target도 함께 세팅되지만 userId가 우선한다
    controller.getAllAuditLogs(limit, "CREATE", "USER", userId);

    verify(auditLogRepository).findUserActivityLogs(userId, limit);
    verify(auditLogRepository, never()).findByAction(any());
    verify(auditLogRepository, never()).findByTarget(any());
    verify(auditLogRepository, never()).findRecentLogs(anyInt());
  }

  @Test
  void action과_target이_모두_있으면_findByAction_결과중_target이_일치하는_항목만_응답에_포함되고_limit로_잘린다() {
    String action = "UPDATE";
    String matchingTarget = "USER";
    LocalDateTime now = LocalDateTime.now();

    AuditLog match1 = AuditLogTestFixtures.newAuditLog(1L, 1L, "u1", action, matchingTarget, 1L,
        "u1", "SUCCESS", null, null, now, "127.0.0.1");
    AuditLog mismatch1 = AuditLogTestFixtures.newAuditLog(2L, 2L, "u2", action, "ANALYSIS", 2L,
        "u2", "SUCCESS", null, null, now, "127.0.0.1");
    AuditLog match2 = AuditLogTestFixtures.newAuditLog(3L, 3L, "u3", action, matchingTarget, 3L,
        "u3", "SUCCESS", null, null, now, "127.0.0.1");
    AuditLog match3 = AuditLogTestFixtures.newAuditLog(4L, 4L, "u4", action, matchingTarget, 4L,
        "u4", "SUCCESS", null, null, now, "127.0.0.1");
    AuditLog mismatch2 = AuditLogTestFixtures.newAuditLog(5L, 5L, "u5", action, "ANALYSIS", 5L,
        "u5", "SUCCESS", null, null, now, "127.0.0.1");

    when(auditLogRepository.findByAction(action))
        .thenReturn(Arrays.asList(match1, mismatch1, match2, match3, mismatch2));

    int limit = 2; // target 일치 항목이 3건이지만 limit=2로 잘려야 함
    ResponseEntity<?> response = controller.getAllAuditLogs(limit, action, matchingTarget, null);

    verify(auditLogRepository).findByAction(action);
    verify(auditLogRepository, never()).findUserActivityLogs(anyLong(), anyInt());
    verify(auditLogRepository, never()).findByTarget(any());
    verify(auditLogRepository, never()).findRecentLogs(anyInt());

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    assertEquals(2, body.size());
    assertEquals(1L, body.get(0).get("id"));
    assertEquals(3L, body.get(1).get("id"));
    for (Map<String, Object> item : body) {
      assertEquals(matchingTarget, item.get("target"));
    }
  }

  @Test
  void action만_있으면_findByAction_결과가_limit개로_잘려서_응답된다() {
    String action = "DELETE";
    LocalDateTime now = LocalDateTime.now();
    List<AuditLog> logs = Arrays.asList(
        AuditLogTestFixtures.newAuditLog(1L, 1L, "u1", action, "USER", 1L, "u1", "SUCCESS", null,
            null, now, "127.0.0.1"),
        AuditLogTestFixtures.newAuditLog(2L, 2L, "u2", action, "USER", 2L, "u2", "SUCCESS", null,
            null, now, "127.0.0.1"),
        AuditLogTestFixtures.newAuditLog(3L, 3L, "u3", action, "USER", 3L, "u3", "SUCCESS", null,
            null, now, "127.0.0.1"),
        AuditLogTestFixtures.newAuditLog(4L, 4L, "u4", action, "USER", 4L, "u4", "SUCCESS", null,
            null, now, "127.0.0.1"));
    when(auditLogRepository.findByAction(action)).thenReturn(logs);

    int limit = 2;
    ResponseEntity<?> response = controller.getAllAuditLogs(limit, action, null, null);

    verify(auditLogRepository).findByAction(action);
    verify(auditLogRepository, never()).findByTarget(any());
    verify(auditLogRepository, never()).findRecentLogs(anyInt());
    verify(auditLogRepository, never()).findUserActivityLogs(anyLong(), anyInt());

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    assertEquals(limit, body.size());
  }

  @Test
  void target만_있으면_findByTarget_결과가_limit개로_잘려서_응답된다() {
    String target = "ANALYSIS";
    LocalDateTime now = LocalDateTime.now();
    List<AuditLog> logs = Arrays.asList(
        AuditLogTestFixtures.newAuditLog(1L, 1L, "u1", "COMPLETED", target, 1L, "u1", "SUCCESS",
            null, null, now, "127.0.0.1"),
        AuditLogTestFixtures.newAuditLog(2L, 2L, "u2", "COMPLETED", target, 2L, "u2", "SUCCESS",
            null, null, now, "127.0.0.1"),
        AuditLogTestFixtures.newAuditLog(3L, 3L, "u3", "COMPLETED", target, 3L, "u3", "SUCCESS",
            null, null, now, "127.0.0.1"));
    when(auditLogRepository.findByTarget(target)).thenReturn(logs);

    int limit = 2;
    ResponseEntity<?> response = controller.getAllAuditLogs(limit, null, target, null);

    verify(auditLogRepository).findByTarget(target);
    verify(auditLogRepository, never()).findByAction(any());
    verify(auditLogRepository, never()).findRecentLogs(anyInt());
    verify(auditLogRepository, never()).findUserActivityLogs(anyLong(), anyInt());

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    assertEquals(limit, body.size());
  }

  @Test
  void 파라미터가_모두_없으면_기본_limit_100으로_findRecentLogs가_호출된다() {
    when(auditLogRepository.findRecentLogs(100)).thenReturn(List.of(
        AuditLogTestFixtures.newAuditLog(1L, 1L, "u1", "LOGIN", "USER", null, "u1", "SUCCESS",
            null, null, LocalDateTime.now(), "127.0.0.1")));

    // 컨트롤러 메서드를 직접 호출하므로 @RequestParam 기본값(100)을 그대로 전달한다
    ResponseEntity<?> response = controller.getAllAuditLogs(100, null, null, null);

    verify(auditLogRepository).findRecentLogs(eq(100));
    verify(auditLogRepository, never()).findByAction(any());
    verify(auditLogRepository, never()).findByTarget(any());
    verify(auditLogRepository, never()).findUserActivityLogs(anyLong(), anyInt());

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    assertEquals(1, body.size());
  }

  @Test
  void 응답의_각_원소는_AuditLog의_12개_필드를_모두_정확히_매핑한다() {
    LocalDateTime timestamp = LocalDateTime.of(2026, 7, 1, 10, 30, 0);
    AuditLog log = AuditLogTestFixtures.newAuditLog(11L, 22L, "hong", "UPDATE", "USER", 33L,
        "targetName", "SUCCESS", "{\"a\":1}", "상세설명", timestamp, "10.0.0.1");
    when(auditLogRepository.findRecentLogs(100)).thenReturn(List.of(log));

    ResponseEntity<?> response = controller.getAllAuditLogs(100, null, null, null);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    assertEquals(1, body.size());
    Map<String, Object> map = body.get(0);

    assertEquals(12, map.size());
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
  void 조회중_예외가_발생하면_500과_감사로그_조회실패_메시지를_응답한다() {
    when(auditLogRepository.findRecentLogs(100)).thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = controller.getAllAuditLogs(100, null, null, null);

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertEquals("감사 로그 조회 실패: DB 오류", body.get("message"));
  }
}
