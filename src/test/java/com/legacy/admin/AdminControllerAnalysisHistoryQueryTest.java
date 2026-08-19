package com.legacy.admin;

import com.legacy.analysis.AnalysisHistory;
import com.legacy.analysis.AnalysisHistoryRepository;
import com.legacy.audit.AuditLogService;
import com.legacy.auth.RoleRepository;
import com.legacy.auth.User;
import com.legacy.auth.UserRepository;
import com.legacy.core.PresentationGeneratorService;
import com.legacy.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AdminController의 분석 이력 조회 3종(getUserAnalysisHistory/getAllAnalysisHistory/filterAnalysisHistory)을
 * 검증한다. 02-design-v1 4.2절 근거.
 */
class AdminControllerAnalysisHistoryQueryTest {

  private UserRepository userRepository;
  private RoleRepository roleRepository;
  private PasswordEncoder passwordEncoder;
  private AnalysisHistoryRepository analysisHistoryRepository;
  private PresentationGeneratorService presentationGeneratorService;
  private AuditLogService auditLogService;
  private NotificationService notificationService;
  private AdminController adminController;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    roleRepository = mock(RoleRepository.class); // 이 3개 메서드에서 미사용
    passwordEncoder = mock(PasswordEncoder.class); // 이 3개 메서드에서 미사용
    analysisHistoryRepository = mock(AnalysisHistoryRepository.class);
    presentationGeneratorService = mock(PresentationGeneratorService.class); // 이 3개 메서드에서 미사용
    auditLogService = mock(AuditLogService.class); // 이 3개 메서드에서 미사용
    notificationService = mock(NotificationService.class); // 이 3개 메서드에서 미사용

    adminController = new AdminController(
        userRepository,
        roleRepository,
        passwordEncoder,
        analysisHistoryRepository,
        presentationGeneratorService,
        auditLogService,
        notificationService);
  }

  // ── getUserAnalysisHistory ──────────────────────────────────────────

  @Test
  void getUserAnalysisHistory_사용자가_없으면_400과_사용자를_찾을_수_없다는_메시지를_반환한다() {
    when(userRepository.findById(99L)).thenReturn(Optional.empty());

    ResponseEntity<?> response = adminController.getUserAnalysisHistory(99L);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("분석 히스토리 조회 실패: 사용자를 찾을 수 없습니다.",
        ((Map<?, ?>) response.getBody()).get("message"));
  }

  @Test
  void getUserAnalysisHistory_정상이면_히스토리_필드가_전수_일치한다() {
    User user = AdminTestFixtures.newUser(10L, "u10", "u10@example.com", true, null);
    when(userRepository.findById(10L)).thenReturn(Optional.of(user));

    AnalysisHistory h1 = AdminTestFixtures.newAnalysisHistory(1L, 10L, "/a/b/proj1", "/out/proj1",
        5, 4, 1, 0, 1234L, "COMPLETED",
        LocalDateTime.of(2026, 1, 1, 10, 0), LocalDateTime.of(2026, 1, 1, 10, 5), "note1");
    h1.setSessionId("session-1");
    AnalysisHistory h2 = AdminTestFixtures.newAnalysisHistory(2L, 10L, "/a/b/proj2", "/out/proj2",
        8, 6, 2, 0, 5678L, "FAILED",
        LocalDateTime.of(2026, 1, 2, 11, 0), null, "note2");
    h2.setSessionId("session-2");
    when(analysisHistoryRepository.findByUserIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(h1, h2));

    ResponseEntity<?> response = adminController.getUserAnalysisHistory(10L);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    assertThat(body).hasSize(2);
    assertHistoryFieldsMatch(body.get(0), h1);
    assertHistoryFieldsMatch(body.get(1), h2);
  }

  private void assertHistoryFieldsMatch(Map<String, Object> map, AnalysisHistory h) {
    assertEquals(h.getSessionId(), map.get("sessionId"));
    assertEquals(h.getSourcePath(), map.get("sourcePath"));
    assertEquals(h.getOutputPath(), map.get("outputPath"));
    assertEquals(h.getTotalFiles(), map.get("totalFiles"));
    assertEquals(h.getSuccessCount(), map.get("successCount"));
    assertEquals(h.getSkipCount(), map.get("skipCount"));
    assertEquals(h.getFailureCount(), map.get("failureCount"));
    assertEquals(h.getProcessingTimeMs(), map.get("processingTimeMs"));
    assertEquals(h.getStatus(), map.get("status"));
    assertEquals(h.getCreatedAt(), map.get("createdAt"));
    assertEquals(h.getCompletedAt(), map.get("completedAt"));
  }

  @Test
  void getUserAnalysisHistory_리포지토리가_예외를_던지면_400을_반환한다() {
    User user = AdminTestFixtures.newUser(10L, "u10", "u10@example.com", true, null);
    when(userRepository.findById(10L)).thenReturn(Optional.of(user));
    when(analysisHistoryRepository.findByUserIdOrderByCreatedAtDesc(10L))
        .thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = adminController.getUserAnalysisHistory(10L);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("분석 히스토리 조회 실패: DB 오류", ((Map<?, ?>) response.getBody()).get("message"));
  }

  // ── getAllAnalysisHistory ────────────────────────────────────────────

  @Test
  void getAllAnalysisHistory_사용자_존재유무에_따라_userId와_displayName이_다르게_채워지고_17개_필드가_전수_매핑된다() {
    User existingUser = AdminTestFixtures.newUser(10L, "u10", "u10@example.com", true, null);
    when(userRepository.findById(10L)).thenReturn(Optional.of(existingUser));
    when(userRepository.findById(99L)).thenReturn(Optional.empty());

    AnalysisHistory h1 = AdminTestFixtures.newAnalysisHistory(1L, 10L, "/a/b/proj1", "/out/proj1",
        5, 4, 1, 0, 1234L, "COMPLETED",
        LocalDateTime.of(2026, 1, 1, 10, 0), LocalDateTime.of(2026, 1, 1, 10, 5), "note1");
    h1.setSessionId("session-1");
    h1.setModelName("claude-3");
    h1.setInputTokens(1000L);
    h1.setOutputTokens(500L);
    h1.setEstimatedCost(1.23);

    AnalysisHistory h2 = AdminTestFixtures.newAnalysisHistory(2L, 99L, "/a/b/proj2", "/out/proj2",
        8, 6, 2, 0, 5678L, "FAILED",
        LocalDateTime.of(2026, 1, 2, 11, 0), null, "note2");
    h2.setSessionId("session-2");
    h2.setModelName("claude-3");
    h2.setInputTokens(2000L);
    h2.setOutputTokens(700L);
    h2.setEstimatedCost(4.56);

    when(analysisHistoryRepository.findAll()).thenReturn(List.of(h1, h2));

    ResponseEntity<?> response = adminController.getAllAnalysisHistory();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    assertThat(body).hasSize(2);

    Map<String, Object> map1 = body.get(0);
    assertEquals(h1.getId(), map1.get("id"));
    assertEquals(h1.getUserId(), map1.get("userSeq"));
    assertEquals("u10", map1.get("userId"));
    assertEquals(existingUser.getDisplayName(), map1.get("displayName"));
    assertEquals(h1.getSessionId(), map1.get("sessionId"));
    assertEquals(h1.getSourcePath(), map1.get("sourcePath"));
    assertEquals(h1.getOutputPath(), map1.get("outputPath"));
    assertEquals(h1.getTotalFiles(), map1.get("totalFiles"));
    assertEquals(h1.getSuccessCount(), map1.get("successCount"));
    assertEquals(h1.getSkipCount(), map1.get("skipCount"));
    assertEquals(h1.getFailureCount(), map1.get("failureCount"));
    assertEquals(h1.getProcessingTimeMs(), map1.get("processingTimeMs"));
    assertEquals(h1.getStatus(), map1.get("status"));
    assertEquals(h1.getNotes(), map1.get("notes"));
    assertEquals(h1.getModelName(), map1.get("modelName"));
    assertEquals(h1.getInputTokens(), map1.get("inputTokens"));
    assertEquals(h1.getOutputTokens(), map1.get("outputTokens"));
    assertEquals(h1.getEstimatedCost(), map1.get("estimatedCost"));
    assertEquals(h1.getCreatedAt(), map1.get("createdAt"));
    assertEquals(h1.getCompletedAt(), map1.get("completedAt"));

    Map<String, Object> map2 = body.get(1);
    assertEquals("user_99", map2.get("userId"));
    assertNull(map2.get("displayName"));
  }

  @Test
  void getAllAnalysisHistory_리포지토리가_예외를_던지면_500을_반환한다() {
    when(analysisHistoryRepository.findAll()).thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = adminController.getAllAnalysisHistory();

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    // getUserAnalysisHistory와 메시지 prefix("분석 히스토리 조회 실패: ")는 같지만 상태코드가 다르다(400 vs 500).
    assertEquals("분석 히스토리 조회 실패: DB 오류", ((Map<?, ?>) response.getBody()).get("message"));
  }

  // ── filterAnalysisHistory ────────────────────────────────────────────

  @Test
  void filterAnalysisHistory_userSeq만_지정하면_해당_사용자만_필터된다() {
    AnalysisHistory h1 = AdminTestFixtures.newAnalysisHistory(1L, 10L, "/p1", "/out1",
        1, 1, 0, 0, 100L, "COMPLETED", LocalDateTime.of(2026, 1, 1, 0, 0), null, null);
    AnalysisHistory h2 = AdminTestFixtures.newAnalysisHistory(2L, 20L, "/p2", "/out2",
        1, 1, 0, 0, 100L, "COMPLETED", LocalDateTime.of(2026, 1, 2, 0, 0), null, null);
    when(analysisHistoryRepository.findAll()).thenReturn(List.of(h1, h2));
    when(userRepository.findById(10L)).thenReturn(Optional.empty());

    ResponseEntity<?> response = adminController.filterAnalysisHistory(10L, null, null, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    assertThat(body).hasSize(1);
    assertEquals(10L, body.get(0).get("userSeq"));
  }

  @Test
  void filterAnalysisHistory_status만_지정하면_해당_상태만_필터된다() {
    AnalysisHistory h1 = AdminTestFixtures.newAnalysisHistory(1L, 10L, "/p1", "/out1",
        1, 1, 0, 0, 100L, "COMPLETED", LocalDateTime.of(2026, 1, 1, 0, 0), null, null);
    AnalysisHistory h2 = AdminTestFixtures.newAnalysisHistory(2L, 10L, "/p2", "/out2",
        1, 0, 0, 1, 100L, "FAILED", LocalDateTime.of(2026, 1, 2, 0, 0), null, null);
    when(analysisHistoryRepository.findAll()).thenReturn(List.of(h1, h2));
    when(userRepository.findById(10L)).thenReturn(Optional.empty());

    ResponseEntity<?> response = adminController.filterAnalysisHistory(null, "FAILED", null, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    assertThat(body).hasSize(1);
    assertEquals("FAILED", body.get(0).get("status"));
  }

  @Test
  void filterAnalysisHistory_startDate_endDate_경계값이_포함_제외_규칙대로_적용된다() {
    AnalysisHistory beforeStart = AdminTestFixtures.newAnalysisHistory(1L, 10L, "/p1", "/out1",
        1, 1, 0, 0, 100L, "COMPLETED", LocalDateTime.of(2026, 3, 9, 23, 59, 59), null, null);
    AnalysisHistory atStart = AdminTestFixtures.newAnalysisHistory(2L, 10L, "/p2", "/out2",
        1, 1, 0, 0, 100L, "COMPLETED", LocalDateTime.of(2026, 3, 10, 0, 0, 0), null, null);
    AnalysisHistory atEndBoundary = AdminTestFixtures.newAnalysisHistory(3L, 10L, "/p3", "/out3",
        1, 1, 0, 0, 100L, "COMPLETED", LocalDateTime.of(2026, 3, 10, 23, 59, 59), null, null);
    AnalysisHistory nextDay = AdminTestFixtures.newAnalysisHistory(4L, 10L, "/p4", "/out4",
        1, 1, 0, 0, 100L, "COMPLETED", LocalDateTime.of(2026, 3, 11, 0, 0, 0), null, null);
    when(analysisHistoryRepository.findAll())
        .thenReturn(List.of(beforeStart, atStart, atEndBoundary, nextDay));
    when(userRepository.findById(10L)).thenReturn(Optional.empty());

    ResponseEntity<?> response =
        adminController.filterAnalysisHistory(null, null, "2026-03-10", "2026-03-10");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    List<Object> ids = body.stream().map(m -> m.get("id")).toList();
    assertThat(ids).containsExactlyInAnyOrder(2L, 3L);
  }

  @Test
  void filterAnalysisHistory_파라미터_전부_미지정이면_전체가_정렬만_적용되어_반환된다() {
    AnalysisHistory h1 = AdminTestFixtures.newAnalysisHistory(1L, 10L, "/p1", "/out1",
        1, 1, 0, 0, 100L, "COMPLETED", LocalDateTime.of(2026, 1, 1, 0, 0), null, null);
    AnalysisHistory h2 = AdminTestFixtures.newAnalysisHistory(2L, 20L, "/p2", "/out2",
        1, 1, 0, 0, 100L, "FAILED", LocalDateTime.of(2026, 1, 2, 0, 0), null, null);
    when(analysisHistoryRepository.findAll()).thenReturn(List.of(h1, h2));
    when(userRepository.findById(10L)).thenReturn(Optional.empty());
    when(userRepository.findById(20L)).thenReturn(Optional.empty());

    ResponseEntity<?> response = adminController.filterAnalysisHistory(null, null, null, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    assertThat(body).hasSize(2);
    // 정렬: createdAt 내림차순이므로 h2(1/2)가 h1(1/1)보다 먼저 온다.
    assertEquals(2L, body.get(0).get("id"));
    assertEquals(1L, body.get(1).get("id"));
  }

  @Test
  void filterAnalysisHistory_createdAt_내림차순_정렬되고_null은_마지막에_온다() {
    AnalysisHistory oldest = AdminTestFixtures.newAnalysisHistory(1L, 10L, "/p1", "/out1",
        1, 1, 0, 0, 100L, "COMPLETED", LocalDateTime.of(2026, 1, 1, 0, 0), null, null);
    AnalysisHistory newest = AdminTestFixtures.newAnalysisHistory(2L, 10L, "/p2", "/out2",
        1, 1, 0, 0, 100L, "COMPLETED", LocalDateTime.of(2026, 1, 3, 0, 0), null, null);
    AnalysisHistory noCreatedAt = AdminTestFixtures.newAnalysisHistory(3L, 10L, "/p3", "/out3",
        1, 1, 0, 0, 100L, "COMPLETED", null, null, null);
    when(analysisHistoryRepository.findAll()).thenReturn(List.of(oldest, newest, noCreatedAt));
    when(userRepository.findById(10L)).thenReturn(Optional.empty());

    ResponseEntity<?> response = adminController.filterAnalysisHistory(null, null, null, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    assertThat(body).hasSize(3);
    assertEquals(2L, body.get(0).get("id")); // newest
    assertEquals(1L, body.get(1).get("id")); // oldest
    assertEquals(3L, body.get(2).get("id")); // null createdAt -> 마지막
  }

  @Test
  void filterAnalysisHistory_사용자_존재유무에_따라_userId와_displayName이_다르게_채워진다() {
    User existingUser = AdminTestFixtures.newUser(10L, "u10", "u10@example.com", true, null);
    when(userRepository.findById(10L)).thenReturn(Optional.of(existingUser));
    when(userRepository.findById(99L)).thenReturn(Optional.empty());

    AnalysisHistory h1 = AdminTestFixtures.newAnalysisHistory(1L, 10L, "/p1", "/out1",
        1, 1, 0, 0, 100L, "COMPLETED", LocalDateTime.of(2026, 1, 1, 0, 0), null, null);
    AnalysisHistory h2 = AdminTestFixtures.newAnalysisHistory(2L, 99L, "/p2", "/out2",
        1, 1, 0, 0, 100L, "COMPLETED", LocalDateTime.of(2026, 1, 2, 0, 0), null, null);
    when(analysisHistoryRepository.findAll()).thenReturn(List.of(h1, h2));

    ResponseEntity<?> response = adminController.filterAnalysisHistory(null, null, null, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    Map<String, Object> mapForH1 = body.stream().filter(m -> m.get("id").equals(1L)).findFirst().orElseThrow();
    Map<String, Object> mapForH2 = body.stream().filter(m -> m.get("id").equals(2L)).findFirst().orElseThrow();
    assertEquals("u10", mapForH1.get("userId"));
    assertEquals(existingUser.getDisplayName(), mapForH1.get("displayName"));
    assertEquals("user_99", mapForH2.get("userId"));
    assertNull(mapForH2.get("displayName"));
  }

  @Test
  void filterAnalysisHistory_10개_필드가_전수_매핑된다() {
    User existingUser = AdminTestFixtures.newUser(10L, "u10", "u10@example.com", true, null);
    when(userRepository.findById(10L)).thenReturn(Optional.of(existingUser));

    AnalysisHistory h = AdminTestFixtures.newAnalysisHistory(1L, 10L, "/a/b/proj", "/out/proj",
        5, 4, 1, 0, 1234L, "COMPLETED", LocalDateTime.of(2026, 1, 1, 10, 0), null, "note-x");
    when(analysisHistoryRepository.findAll()).thenReturn(List.of(h));

    ResponseEntity<?> response = adminController.filterAnalysisHistory(null, null, null, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    Map<String, Object> map = body.get(0);
    // outputPath/skipCount는 filterAnalysisHistory 응답에 포함되지 않는다(getAllAnalysisHistory와 차이점).
    assertThat(map).doesNotContainKey("outputPath");
    assertThat(map).doesNotContainKey("skipCount");
    assertEquals(h.getId(), map.get("id"));
    assertEquals(h.getUserId(), map.get("userSeq"));
    assertEquals("u10", map.get("userId"));
    assertEquals(existingUser.getDisplayName(), map.get("displayName"));
    assertEquals(h.getSourcePath(), map.get("sourcePath"));
    assertEquals(h.getTotalFiles(), map.get("totalFiles"));
    assertEquals(h.getSuccessCount(), map.get("successCount"));
    assertEquals(h.getFailureCount(), map.get("failureCount"));
    assertEquals(h.getProcessingTimeMs(), map.get("processingTimeMs"));
    assertEquals(h.getStatus(), map.get("status"));
    assertEquals(h.getNotes(), map.get("notes"));
    assertEquals(h.getCreatedAt(), map.get("createdAt"));
  }

  @Test
  void filterAnalysisHistory_리포지토리가_예외를_던지면_500과_조회_실패_메시지를_반환한다() {
    when(analysisHistoryRepository.findAll()).thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = adminController.filterAnalysisHistory(null, null, null, null);

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    // getUserAnalysisHistory/getAllAnalysisHistory와 메시지 prefix("분석 히스토리 조회 실패: ")가 다르다("조회 실패: ").
    assertEquals("조회 실패: DB 오류", ((Map<?, ?>) response.getBody()).get("message"));
  }
}
