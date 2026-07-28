package com.legacy.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legacy.analysis.AnalysisHistory;
import com.legacy.auth.Role;
import com.legacy.auth.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * AuditLogService의 편의 메서드 7종(logUserCreation, logUserModification, logUserDeletion,
 * logAnalysisCompletion, logLogin, logLogout, logLoginFailure)이 내부적으로 logAudit에
 * 올바른 인자를 위임하는지 검증한다. SecurityContext는 비워둔 채(=SYSTEM 고정) 진행한다.
 */
class AuditLogServiceConvenienceMethodsTest {

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

  /** changes JSON 문자열을 Map으로 역직렬화한다. */
  private static Map<String, Object> parseChanges(String changesJson) throws Exception {
    return new ObjectMapper().readValue(changesJson, new TypeReference<Map<String, Object>>() {
    });
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
  }

  @Test
  void logUserCreation_호출하면_CREATE_action과_userId_email_roles가_포함된_changes로_저장된다()
      throws Exception {
    Set<Role> roles = new HashSet<>();
    roles.add(AuditLogTestFixtures.newRole("ADMIN"));
    roles.add(AuditLogTestFixtures.newRole("USER"));
    User user = AuditLogTestFixtures.newUser(5L, "hong", "hong@test.com", true, roles);

    auditLogService.logUserCreation(user, "127.0.0.1");

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    AuditLog saved = captor.getValue();

    assertEquals("CREATE", saved.getAction());
    assertEquals("USER", saved.getTarget());
    assertEquals(user.getSeq(), saved.getTargetId());
    assertEquals(user.getUserId(), saved.getTargetName());
    assertEquals("SUCCESS", saved.getStatus());
    assertEquals("새 사용자 생성됨", saved.getDetails());

    Map<String, Object> changes = parseChanges(saved.getChanges());
    assertEquals(user.getUserId(), changes.get("userId"));
    assertEquals(user.getEmail(), changes.get("email"));
    @SuppressWarnings("unchecked")
    java.util.List<String> rolesInChanges = (java.util.List<String>) changes.get("roles");
    assertEquals(Set.of("ADMIN", "USER"), new HashSet<>(rolesInChanges));
  }

  @Test
  void logUserModification_모든_필드가_동일하면_save가_호출되지_않는다() {
    Set<Role> roles = new HashSet<>();
    User oldUser = AuditLogTestFixtures.newUser(1L, "hong", "hong@test.com", true, roles);
    User newUser = AuditLogTestFixtures.newUser(1L, "hong", "hong@test.com", true, roles);

    auditLogService.logUserModification(oldUser, newUser, "127.0.0.1");

    verify(auditLogRepository, never()).save(any());
  }

  @Test
  void logUserModification_userId만_변경되면_changes에_userId_키만_존재한다() throws Exception {
    User oldUser = AuditLogTestFixtures.newUser(1L, "hong1", "hong@test.com", true, new HashSet<>());
    User newUser = AuditLogTestFixtures.newUser(1L, "hong2", "hong@test.com", true, new HashSet<>());

    auditLogService.logUserModification(oldUser, newUser, "127.0.0.1");

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    Map<String, Object> changes = parseChanges(captor.getValue().getChanges());

    assertTrue(changes.containsKey("userId"));
    assertFalse(changes.containsKey("email"));
    assertFalse(changes.containsKey("isActive"));

    Map<String, Object> userIdChange = asMap(changes.get("userId"));
    assertEquals("hong1", userIdChange.get("old"));
    assertEquals("hong2", userIdChange.get("new"));
  }

  @Test
  void logUserModification_email만_변경되면_changes에_email_키만_존재한다() throws Exception {
    User oldUser = AuditLogTestFixtures.newUser(1L, "hong", "old@test.com", true, new HashSet<>());
    User newUser = AuditLogTestFixtures.newUser(1L, "hong", "new@test.com", true, new HashSet<>());

    auditLogService.logUserModification(oldUser, newUser, "127.0.0.1");

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    Map<String, Object> changes = parseChanges(captor.getValue().getChanges());

    assertFalse(changes.containsKey("userId"));
    assertTrue(changes.containsKey("email"));
    assertFalse(changes.containsKey("isActive"));

    Map<String, Object> emailChange = asMap(changes.get("email"));
    assertEquals("old@test.com", emailChange.get("old"));
    assertEquals("new@test.com", emailChange.get("new"));
  }

  @Test
  void logUserModification_isActive만_변경되면_changes에_isActive_키만_존재한다() throws Exception {
    User oldUser = AuditLogTestFixtures.newUser(1L, "hong", "hong@test.com", true, new HashSet<>());
    User newUser = AuditLogTestFixtures.newUser(1L, "hong", "hong@test.com", false, new HashSet<>());

    auditLogService.logUserModification(oldUser, newUser, "127.0.0.1");

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    Map<String, Object> changes = parseChanges(captor.getValue().getChanges());

    assertFalse(changes.containsKey("userId"));
    assertFalse(changes.containsKey("email"));
    assertTrue(changes.containsKey("isActive"));

    Map<String, Object> isActiveChange = asMap(changes.get("isActive"));
    assertEquals(true, isActiveChange.get("old"));
    assertEquals(false, isActiveChange.get("new"));
  }

  @Test
  void logUserModification_전체_필드가_변경되면_changes에_3개_키_모두_존재한다() throws Exception {
    User oldUser = AuditLogTestFixtures.newUser(1L, "hong1", "old@test.com", true, new HashSet<>());
    User newUser = AuditLogTestFixtures.newUser(1L, "hong2", "new@test.com", false, new HashSet<>());

    auditLogService.logUserModification(oldUser, newUser, "127.0.0.1");

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    Map<String, Object> changes = parseChanges(captor.getValue().getChanges());

    assertTrue(changes.containsKey("userId"));
    assertTrue(changes.containsKey("email"));
    assertTrue(changes.containsKey("isActive"));
  }

  @Test
  void logUserDeletion_호출하면_DELETE_action과_userId_email이_포함된_changes로_저장된다() throws Exception {
    User user = AuditLogTestFixtures.newUser(3L, "hong", "hong@test.com", true, new HashSet<>());

    auditLogService.logUserDeletion(user, "127.0.0.1");

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    AuditLog saved = captor.getValue();

    assertEquals("DELETE", saved.getAction());
    assertEquals("사용자 삭제됨", saved.getDetails());

    Map<String, Object> changes = parseChanges(saved.getChanges());
    assertEquals(user.getUserId(), changes.get("userId"));
    assertEquals(user.getEmail(), changes.get("email"));
  }

  @Test
  void logAnalysisCompletion_호출하면_COMPLETED_action과_분석_통계가_포함된_changes로_저장된다() throws Exception {
    AnalysisHistory analysis = AuditLogTestFixtures.newAnalysisHistory(7L, "/src/main", 10, 8, 2,
        1234L);

    auditLogService.logAnalysisCompletion(analysis, "127.0.0.1");

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    AuditLog saved = captor.getValue();

    assertEquals("COMPLETED", saved.getAction());
    assertEquals("ANALYSIS", saved.getTarget());
    assertEquals(analysis.getId(), saved.getTargetId());
    assertEquals(analysis.getSourcePath(), saved.getTargetName());

    Map<String, Object> changes = parseChanges(saved.getChanges());
    assertEquals(10, ((Number) changes.get("totalFiles")).intValue());
    assertEquals(8, ((Number) changes.get("successCount")).intValue());
    assertEquals(2, ((Number) changes.get("failureCount")).intValue());
    assertEquals(1234L, ((Number) changes.get("processingTimeMs")).longValue());
  }

  @Test
  void logLogin_호출하면_LOGIN_action과_SUCCESS_status로_저장되고_changes는_null이다() {
    auditLogService.logLogin("hong", "127.0.0.1");

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    AuditLog saved = captor.getValue();

    assertEquals("LOGIN", saved.getAction());
    assertEquals("USER", saved.getTarget());
    assertNull(saved.getTargetId());
    assertEquals("hong", saved.getTargetName());
    assertEquals("SUCCESS", saved.getStatus());
    assertNull(saved.getChanges());
    assertEquals("사용자 로그인", saved.getDetails());
  }

  @Test
  void logLogout_호출하면_LOGOUT_action으로_저장되고_details는_사용자_로그아웃이다() {
    auditLogService.logLogout("hong", "127.0.0.1");

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    AuditLog saved = captor.getValue();

    assertEquals("LOGOUT", saved.getAction());
    assertEquals("USER", saved.getTarget());
    assertNull(saved.getTargetId());
    assertEquals("hong", saved.getTargetName());
    assertEquals("SUCCESS", saved.getStatus());
    assertNull(saved.getChanges());
    assertEquals("사용자 로그아웃", saved.getDetails());
  }

  @Test
  void logLoginFailure_호출하면_LOGIN_action과_FAILURE_status로_저장된다() {
    auditLogService.logLoginFailure("hong", "127.0.0.1");

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(captor.capture());
    AuditLog saved = captor.getValue();

    assertEquals("LOGIN", saved.getAction());
    assertEquals("FAILURE", saved.getStatus());
    assertEquals("로그인 실패", saved.getDetails());
  }
}
