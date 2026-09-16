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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-001(2026-09-remaining-ux-fixes TASK-008) — 관리자 "분석 이력"의 상세 보고서 PPT 다운로드
 * {@code AdminController.downloadProjectReport()} 검증. 기존
 * {@code AdminControllerAnalysisHistoryMutationAndDownloadTest}의 {@code downloadPresentation} 케이스와
 * 같은 패턴(필요 의존성만 mock)으로, 요약 PPT와 상세 보고서 PPT의 차이점(서비스 메서드·접두사)을 고정한다.
 *
 * <p>권한 가드(ADMIN 전용, 비-ADMIN 403)는 Spring Security 필터/프록시가 적용하는 것이라 단위 테스트에서는
 * 관측할 수 없다 — 여기서는 "클래스 레벨 {@code @PreAuthorize("hasRole('ADMIN')")}가 존재하고 메서드 레벨
 * 애노테이션이 없다(= 상속)"는 정적 구조만 단언하고, 실제 403 관측은 실컨테이너에서 한다(DoD 2).
 */
class AdminControllerProjectReportDownloadTest {

  private AnalysisHistoryRepository analysisHistoryRepository;
  private PresentationGeneratorService presentationGeneratorService;
  private AdminController adminController;

  @BeforeEach
  void setUp() {
    analysisHistoryRepository = mock(AnalysisHistoryRepository.class);
    presentationGeneratorService = mock(PresentationGeneratorService.class);

    adminController = new AdminController(
        mock(UserRepository.class),
        mock(RoleRepository.class),
        mock(PasswordEncoder.class),
        analysisHistoryRepository,
        presentationGeneratorService,
        mock(AuditLogService.class),
        mock(NotificationService.class));
  }

  @Test
  void downloadProjectReport_대상이_없으면_404를_반환하고_서비스를_호출하지_않는다() throws IOException {
    when(analysisHistoryRepository.findById(1L)).thenReturn(Optional.empty());

    ResponseEntity<byte[]> response = adminController.downloadProjectReport(1L);

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    assertThat(response.getBody() == null || response.getBody().length == 0).isTrue();
    verify(presentationGeneratorService, never()).generateProjectReportPresentation(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void downloadProjectReport_정상이면_200과_상세보고서_바이트를_반환하고_헤더와_파일명이_올바르다() throws IOException {
    // 관리자 본인(seq 99)이 아닌 다른 사용자(userId 10)의 이력 — 소유자 검증 없이 내려와야 한다.
    AnalysisHistory history = AdminTestFixtures.newAnalysisHistory(1L, 10L, "/a/b/myproject", "/out",
        1, 1, 0, 0, 100L, "COMPLETED", null, null, null);
    when(analysisHistoryRepository.findById(1L)).thenReturn(Optional.of(history));
    byte[] pptxContent = {7, 8, 9};
    when(presentationGeneratorService.generateProjectReportPresentation(history)).thenReturn(pptxContent);

    ResponseEntity<byte[]> response = adminController.downloadProjectReport(1L);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertThat(response.getBody()).isEqualTo(pptxContent);
    assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation",
        response.getHeaders().getContentType().toString());
    assertEquals(pptxContent.length, response.getHeaders().getContentLength());
    assertThat(response.getHeaders().getContentDisposition().isAttachment()).isTrue();

    String contentDisposition = response.getHeaders().getContentDisposition().toString();
    Pattern pattern = Pattern.compile("filename=\"report_myproject_\\d{8}_\\d{6}\\.pptx\"");
    assertThat(pattern.matcher(contentDisposition).find())
        .as("파일명은 report_{projectName}_{yyyyMMdd_HHmmss}.pptx 형태: " + contentDisposition).isTrue();

    // 요약 PPT 서비스가 아니라 상세 보고서 서비스를 호출했다(두 엔드포인트의 차이점).
    verify(presentationGeneratorService, never()).generateAnalysisResultPresentation(history);
  }

  @Test
  void downloadProjectReport_sourcePath가_백슬래시_경로면_마지막_세그먼트만_파일명에_남는다() throws IOException {
    AnalysisHistory history = AdminTestFixtures.newAnalysisHistory(1L, 10L, "C:\\work\\proj", "/out",
        1, 1, 0, 0, 100L, "COMPLETED", null, null, null);
    when(analysisHistoryRepository.findById(1L)).thenReturn(Optional.of(history));
    when(presentationGeneratorService.generateProjectReportPresentation(history)).thenReturn(new byte[] {1});

    ResponseEntity<byte[]> response = adminController.downloadProjectReport(1L);

    String contentDisposition = response.getHeaders().getContentDisposition().toString();
    assertThat(contentDisposition).contains("report_proj_");
  }

  @Test
  void downloadProjectReport_sourcePath가_null이면_파일명이_untitled로_대체된다() throws IOException {
    AnalysisHistory history = AdminTestFixtures.newAnalysisHistory(1L, 10L, null, "/out",
        1, 1, 0, 0, 100L, "COMPLETED", null, null, null);
    when(analysisHistoryRepository.findById(1L)).thenReturn(Optional.of(history));
    when(presentationGeneratorService.generateProjectReportPresentation(history)).thenReturn(new byte[] {9});

    ResponseEntity<byte[]> response = adminController.downloadProjectReport(1L);

    String contentDisposition = response.getHeaders().getContentDisposition().toString();
    Pattern pattern = Pattern.compile("filename=\"report_untitled_\\d{8}_\\d{6}\\.pptx\"");
    assertThat(pattern.matcher(contentDisposition).find()).isTrue();
  }

  @Test
  void downloadProjectReport_PPT_생성중_IOException이면_500과_빈_body를_반환한다() throws IOException {
    AnalysisHistory history = AdminTestFixtures.newAnalysisHistory(1L, 10L, "/a/b/project", "/out",
        1, 1, 0, 0, 100L, "COMPLETED", null, null, null);
    when(analysisHistoryRepository.findById(1L)).thenReturn(Optional.of(history));
    when(presentationGeneratorService.generateProjectReportPresentation(history))
        .thenThrow(new IOException("생성 실패"));

    ResponseEntity<byte[]> response = adminController.downloadProjectReport(1L);

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertThat(response.getBody() == null || response.getBody().length == 0).isTrue();
  }

  @Test
  void downloadProjectReport는_클래스_레벨_ADMIN_가드를_상속하고_메서드_레벨_애노테이션이_없다() throws Exception {
    PreAuthorize classLevel = AdminController.class.getAnnotation(PreAuthorize.class);
    assertNotNull(classLevel, "클래스 레벨 @PreAuthorize가 있어야 한다");
    assertEquals("hasRole('ADMIN')", classLevel.value());

    Method m = AdminController.class.getMethod("downloadProjectReport", Long.class);
    assertNull(m.getAnnotation(PreAuthorize.class), "메서드 레벨 @PreAuthorize는 두지 않는다(클래스 레벨 상속)");
  }
}
