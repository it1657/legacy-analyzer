package com.legacy.admin;

import com.legacy.analysis.AnalysisHistory;
import com.legacy.analysis.AnalysisHistoryRepository;
import com.legacy.audit.AuditLogService;
import com.legacy.auth.RoleRepository;
import com.legacy.auth.UserRepository;
import com.legacy.core.PresentationGeneratorService;
import com.legacy.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminController.deleteAnalysisHistory()/downloadPresentation()을 검증한다. 02-design-v1 4.3절 근거.
 */
class AdminControllerAnalysisHistoryMutationAndDownloadTest {

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
    userRepository = mock(UserRepository.class); // 이 2개 메서드에서 미사용
    roleRepository = mock(RoleRepository.class); // 이 2개 메서드에서 미사용
    passwordEncoder = mock(PasswordEncoder.class); // 이 2개 메서드에서 미사용
    analysisHistoryRepository = mock(AnalysisHistoryRepository.class);
    presentationGeneratorService = mock(PresentationGeneratorService.class);
    auditLogService = mock(AuditLogService.class); // 이 2개 메서드에서 미사용
    notificationService = mock(NotificationService.class); // 이 2개 메서드에서 미사용

    adminController = new AdminController(
        userRepository,
        roleRepository,
        passwordEncoder,
        analysisHistoryRepository,
        presentationGeneratorService,
        auditLogService,
        notificationService);
  }

  // ── deleteAnalysisHistory ────────────────────────────────────────────

  @Test
  void deleteAnalysisHistory_대상이_없으면_404와_이력을_찾을_수_없다는_메시지를_반환한다() {
    when(analysisHistoryRepository.existsById(1L)).thenReturn(false);

    ResponseEntity<?> response = adminController.deleteAnalysisHistory(1L);

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    assertEquals("이력을 찾을 수 없습니다.", ((Map<?, ?>) response.getBody()).get("message"));
    verify(analysisHistoryRepository, never()).deleteById(1L);
  }

  @Test
  void deleteAnalysisHistory_성공하면_deleteById가_호출되고_200과_삭제_메시지를_반환한다() {
    when(analysisHistoryRepository.existsById(1L)).thenReturn(true);

    ResponseEntity<?> response = adminController.deleteAnalysisHistory(1L);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("삭제되었습니다.", ((Map<?, ?>) response.getBody()).get("message"));
    verify(analysisHistoryRepository, times(1)).deleteById(1L);
  }

  @Test
  void deleteAnalysisHistory_deleteById가_예외를_던지면_500을_반환한다() {
    when(analysisHistoryRepository.existsById(1L)).thenReturn(true);
    org.mockito.Mockito.doThrow(new RuntimeException("DB 오류"))
        .when(analysisHistoryRepository).deleteById(1L);

    ResponseEntity<?> response = adminController.deleteAnalysisHistory(1L);

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertEquals("삭제 실패: DB 오류", ((Map<?, ?>) response.getBody()).get("message"));
  }

  // ── downloadPresentation ────────────────────────────────────────────

  @Test
  void downloadPresentation_대상이_없으면_404를_반환하고_body가_없다() {
    when(analysisHistoryRepository.findById(1L)).thenReturn(Optional.empty());

    ResponseEntity<byte[]> response = adminController.downloadPresentation(1L);

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    assertThat(response.getBody() == null || response.getBody().length == 0).isTrue();
  }

  @Test
  void downloadPresentation_정상이면_200과_PPT_바이트를_반환하고_헤더가_올바르다() throws IOException {
    AnalysisHistory history = AdminTestFixtures.newAnalysisHistory(1L, 10L, "/a/b/myproject", "/out",
        1, 1, 0, 0, 100L, "COMPLETED", null, null, null);
    when(analysisHistoryRepository.findById(1L)).thenReturn(Optional.of(history));
    byte[] pptxContent = {1, 2, 3, 4, 5};
    when(presentationGeneratorService.generateAnalysisResultPresentation(history)).thenReturn(pptxContent);

    ResponseEntity<byte[]> response = adminController.downloadPresentation(1L);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertThat(response.getBody()).isEqualTo(pptxContent);
    assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation",
        response.getHeaders().getContentType().toString());
    assertEquals(pptxContent.length, response.getHeaders().getContentLength());

    // REQ-002: 폼 필드용 form-data가 아니라 파일 첨부용 attachment 타입이어야 한다.
    assertThat(response.getHeaders().getContentDisposition().isAttachment()).isTrue();

    String contentDisposition = response.getHeaders().getContentDisposition().toString();
    Pattern pattern = Pattern.compile("filename=\"analysis_myproject_\\d{8}_\\d{6}\\.pptx\"");
    assertThat(pattern.matcher(contentDisposition).find()).isTrue();
  }

  @Test
  void downloadPresentation_sourcePath가_슬래시_경로면_마지막_세그먼트만_파일명에_남는다() throws IOException {
    AnalysisHistory history = AdminTestFixtures.newAnalysisHistory(1L, 10L, "/a/b/project", "/out",
        1, 1, 0, 0, 100L, "COMPLETED", null, null, null);
    when(analysisHistoryRepository.findById(1L)).thenReturn(Optional.of(history));
    when(presentationGeneratorService.generateAnalysisResultPresentation(history))
        .thenReturn(new byte[] {9});

    ResponseEntity<byte[]> response = adminController.downloadPresentation(1L);

    String contentDisposition = response.getHeaders().getContentDisposition().toString();
    assertThat(contentDisposition).contains("analysis_project_");
  }

  @Test
  void downloadPresentation_sourcePath가_백슬래시_경로면_마지막_세그먼트만_파일명에_남는다() throws IOException {
    AnalysisHistory history = AdminTestFixtures.newAnalysisHistory(1L, 10L, "C:\\a\\b\\project", "/out",
        1, 1, 0, 0, 100L, "COMPLETED", null, null, null);
    when(analysisHistoryRepository.findById(1L)).thenReturn(Optional.of(history));
    when(presentationGeneratorService.generateAnalysisResultPresentation(history))
        .thenReturn(new byte[] {9});

    ResponseEntity<byte[]> response = adminController.downloadPresentation(1L);

    String contentDisposition = response.getHeaders().getContentDisposition().toString();
    assertThat(contentDisposition).contains("analysis_project_");
  }

  @Test
  void downloadPresentation_sourcePath가_null이면_파일명이_untitled로_대체된다() throws IOException {
    AnalysisHistory history = AdminTestFixtures.newAnalysisHistory(1L, 10L, null, "/out",
        1, 1, 0, 0, 100L, "COMPLETED", null, null, null);
    when(analysisHistoryRepository.findById(1L)).thenReturn(Optional.of(history));
    when(presentationGeneratorService.generateAnalysisResultPresentation(history))
        .thenReturn(new byte[] {9});

    ResponseEntity<byte[]> response = adminController.downloadPresentation(1L);

    // REQ-001 수정 후: projectName 기본값이 "untitled"로 대체되어 최종 파일명은
    // "analysis_untitled_{timestamp}.pptx"가 된다. 접두사 "analysis_"와 중복되지 않으며,
    // 파일명 포맷 규칙("analysis_{projectName}_{timestamp}.pptx")은 다른 sourcePath 케이스와 동일하게 유지된다.
    String contentDisposition = response.getHeaders().getContentDisposition().toString();
    Pattern pattern = Pattern.compile("filename=\"analysis_untitled_\\d{8}_\\d{6}\\.pptx\"");
    assertThat(pattern.matcher(contentDisposition).find()).isTrue();
  }

  @Test
  void downloadPresentation_PPT_생성중_IOException이면_500과_빈_body를_반환한다() throws IOException {
    AnalysisHistory history = AdminTestFixtures.newAnalysisHistory(1L, 10L, "/a/b/project", "/out",
        1, 1, 0, 0, 100L, "COMPLETED", null, null, null);
    when(analysisHistoryRepository.findById(1L)).thenReturn(Optional.of(history));
    when(presentationGeneratorService.generateAnalysisResultPresentation(history))
        .thenThrow(new IOException("생성 실패"));

    ResponseEntity<byte[]> response = adminController.downloadPresentation(1L);

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertThat(response.getBody() == null || response.getBody().length == 0).isTrue();
  }
}
