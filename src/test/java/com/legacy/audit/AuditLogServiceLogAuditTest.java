package com.legacy.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legacy.auth.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * AuditLogService.logAudit(8-arg)의 핵심 분기(인증 사용자 추출, changes JSON 직렬화,
 * SYSTEM 폴백, 직렬화 실패 시 예외 삼킴, 6-arg 오버로드 위임)를 검증한다.
 */
class AuditLogServiceLogAuditTest {

  private AuditLogRepository auditLogRepository;
  private AuditLogService auditLogService;

  @BeforeEach
  void setUp() {
    SecurityContextHolder.clearContext();
    auditLogRepository = mock(AuditLogRepository.class);
    auditLogService = new AuditLogService(auditLogRepository);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void 인증된_사용자와_정상_changes로_호출하면_사용자정보와_JSON으로_직렬화된_changes가_저장된다() throws Exception {
    User user = AuditLogTestFixtures.newUser(10L, "hong", "hong@test.com", true, new HashSet<>());
    AuditLogTestFixtures.setAuthenticatedUser(user);

    Map<String, Object> changes = new HashMap<>();
    changes.put("field", "value");
    changes.put("count", 3);

    auditLogService.logAudit("UPDATE", "USER", 100L, "hong", "SUCCESS", changes, "상세설명",
        "127.0.0.1");

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    AuditLog saved = captor.getValue();

    assertEquals(user.getSeq(), saved.getUserId());
    assertEquals(user.getUserId(), saved.getUsername());
    assertEquals("UPDATE", saved.getAction());
    assertEquals("USER", saved.getTarget());
    assertEquals(100L, saved.getTargetId());
    assertEquals("hong", saved.getTargetName());
    assertEquals("SUCCESS", saved.getStatus());
    assertEquals("상세설명", saved.getDetails());
    assertEquals("127.0.0.1", saved.getIpAddress());

    @SuppressWarnings("unchecked")
    Map<String, Object> parsedChanges =
        new ObjectMapper().readValue(saved.getChanges(), Map.class);
    assertEquals(changes, parsedChanges);
  }

  @Test
  void 인증정보가_없으면_userId는_null이고_username은_SYSTEM으로_저장된다() {
    // SecurityContext가 비어있는 상태(getAuthentication()==null)에서 호출
    auditLogService.logAudit("CREATE", "USER", 1L, "target", "SUCCESS", null, null,
        "127.0.0.1");

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    AuditLog saved = captor.getValue();

    assertNull(saved.getUserId());
    assertEquals("SYSTEM", saved.getUsername());
  }

  @Test
  void principal이_User가_아니면_SYSTEM으로_폴백된다() {
    AuditLogTestFixtures.setNonUserAuthentication();

    auditLogService.logAudit("CREATE", "USER", 1L, "target", "SUCCESS", null, null,
        "127.0.0.1");

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    AuditLog saved = captor.getValue();

    assertNull(saved.getUserId());
    assertEquals("SYSTEM", saved.getUsername());
  }

  @Test
  void changes가_null이면_저장되는_AuditLog의_changes도_null이다() {
    auditLogService.logAudit("CREATE", "USER", 1L, "target", "SUCCESS", null, "상세",
        "127.0.0.1");

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    AuditLog saved = captor.getValue();

    assertNull(saved.getChanges());
  }

  /** getter가 전혀 없어 Jackson이 직렬화할 수 없는 클래스 (JSON 직렬화 실패를 유도). */
  private static class Unserializable {
  }

  @Test
  void JSON_직렬화에_실패하면_save가_호출되지_않고_예외도_던지지_않는다() {
    Map<String, Object> changes = new HashMap<>();
    changes.put("bad", new Unserializable());

    assertDoesNotThrow(() -> auditLogService.logAudit("CREATE", "USER", 1L, "target",
        "SUCCESS", changes, "상세", "127.0.0.1"));

    verify(auditLogRepository, never()).save(any());
  }

  @Test
  void 인자가_적은_오버로드는_changes와_details를_null로_전체_인자_버전에_위임한다() {
    auditLogService.logAudit("LOGIN", "USER", null, "hong", "SUCCESS", "127.0.0.1");

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    AuditLog saved = captor.getValue();

    assertNull(saved.getChanges());
    assertNull(saved.getDetails());
  }
}
