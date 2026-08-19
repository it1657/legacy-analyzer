package com.legacy.admin;

import com.legacy.audit.AuditLogService;
import com.legacy.auth.Role;
import com.legacy.auth.RoleRepository;
import com.legacy.auth.User;
import com.legacy.auth.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserController.deleteUser()의 삭제 흐름(사용자 없음/성공 시 호출 순서/예외)을 검증한다.
 * 02-design-v1 5.3절 근거. 순서 검증의 정밀도를 위해 다른 시나리오와 섞지 않고 별도 파일로 분리.
 *
 * 시나리오 2("삭제 직전 역할을 비우고 저장하는 것이 의도적 방어 로직인지 불필요한 코드인지")는
 * 정상 동작 자체(순서)를 특성화 테스트로 고정하되, 그 설계 의도에 대한 정상/버그 판단은 이 테스트가
 * 내리지 않는다 — QA가 06-qa-results.md/bug-suspects.md에 "버그 의심"으로 별도 기록한다.
 */
class UserControllerDeleteUserTest {

  private UserRepository userRepository;
  private RoleRepository roleRepository;
  private PasswordEncoder passwordEncoder;
  private AuditLogService auditLogService;
  private HttpServletRequest httpRequest;
  private UserController userController;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    roleRepository = mock(RoleRepository.class); // deleteUser에서 미사용
    passwordEncoder = mock(PasswordEncoder.class); // deleteUser에서 미사용
    auditLogService = mock(AuditLogService.class);
    httpRequest = mock(HttpServletRequest.class);
    when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");

    userController = new UserController(userRepository, roleRepository, passwordEncoder, auditLogService);
  }

  @Test
  void deleteUser_대상이_없으면_400을_반환하고_save_delete_logUserDeletion이_전부_호출되지_않는다() {
    when(userRepository.findById(1L)).thenReturn(Optional.empty());

    ResponseEntity<?> response = userController.deleteUser(1L, httpRequest);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    verify(userRepository, never()).save(any());
    verify(userRepository, never()).delete(any());
    verify(auditLogService, never()).logUserDeletion(any(), any());
  }

  @Test
  void deleteUser_성공하면_logUserDeletion_save_delete_순서로_호출되고_역할은_로그기록_시점엔_2개_저장시점엔_0개다() {
    Role roleA = AdminTestFixtures.newRole("USER");
    Role roleB = AdminTestFixtures.newRole("VIEWER");
    User user = AdminTestFixtures.newUser(1L, "u1", "u1@example.com", true, new HashSet<>(Set.of(roleA, roleB)));
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));

    // 인자 캡처는 참조 캡처이므로, logUserDeletion 호출 "시점"에 roles를 스냅샷 떠서
    // 이후 roles.clear()가 실행돼도 그 시점엔 역할이 2개였음을 확인할 수 있게 한다.
    List<Set<Role>> logDeletionRolesSnapshot = new ArrayList<>();
    doAnswer(invocation -> {
      logDeletionRolesSnapshot.add(new HashSet<>(user.getRoles()));
      return null;
    }).when(auditLogService).logUserDeletion(user, "127.0.0.1");

    // save(user) 호출 "시점"에는 roles.clear()가 이미 실행된 뒤이므로 비어있는 상태여야 한다.
    List<Set<Role>> saveRolesSnapshot = new ArrayList<>();
    doAnswer(invocation -> {
      saveRolesSnapshot.add(new HashSet<>(user.getRoles()));
      return user;
    }).when(userRepository).save(user);

    ResponseEntity<?> response = userController.deleteUser(1L, httpRequest);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("사용자가 삭제되었습니다.", ((Map<?, ?>) response.getBody()).get("message"));

    assertThat(logDeletionRolesSnapshot).hasSize(1);
    assertThat(logDeletionRolesSnapshot.get(0)).hasSize(2); // 로그 기록 시점엔 역할이 2개
    assertThat(saveRolesSnapshot).hasSize(1);
    assertThat(saveRolesSnapshot.get(0)).isEmpty(); // save 시점엔 이미 비어있음

    InOrder inOrder = inOrder(auditLogService, userRepository);
    inOrder.verify(auditLogService).logUserDeletion(user, "127.0.0.1");
    inOrder.verify(userRepository).save(user);
    inOrder.verify(userRepository).delete(user);
  }

  @Test
  void deleteUser_findById_이후_예외가_발생하면_400을_반환한다() {
    User user = AdminTestFixtures.newUser(1L, "u1", "u1@example.com", true, new HashSet<>());
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    doThrow(new RuntimeException("DB 오류")).when(auditLogService).logUserDeletion(user, "127.0.0.1");

    ResponseEntity<?> response = userController.deleteUser(1L, httpRequest);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("사용자 삭제 실패: DB 오류", ((Map<?, ?>) response.getBody()).get("message"));
    verify(userRepository, never()).save(any());
    verify(userRepository, never()).delete(any());
  }
}
