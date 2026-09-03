package com.legacy.analysis;

import com.legacy.auth.User;
import com.legacy.core.PresentationGeneratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.io.IOException;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * UserActivityController의 PPT 다운로드 두 경로(downloadMyPresentation/downloadProjectReport)를 검증한다.
 * 04-work-order-v2 TASK-013/014 근거 — Content-Disposition disposition-type이 attachment인지 확인한다.
 */
class UserActivityControllerDownloadTest {

  private AnalysisHistoryRepository analysisHistoryRepository;
  private PresentationGeneratorService presentationGeneratorService;
  private Authentication authentication;
  private UserActivityController userActivityController;

  @BeforeEach
  void setUp() {
    analysisHistoryRepository = mock(AnalysisHistoryRepository.class);
    presentationGeneratorService = mock(PresentationGeneratorService.class);

    userActivityController = new UserActivityController(
        analysisHistoryRepository,
        presentationGeneratorService);

    // 로그인 주체는 seq=10L 사용자로 고정한다.
    User principal = new User("tester", "tester@example.com", "hash");
    principal.setSeq(10L);
    authentication = mock(Authentication.class);
    when(authentication.getPrincipal()).thenReturn(principal);
  }

  // 이력 픽스처 (AdminTestFixtures는 package-private이라 재사용 불가하여 직접 구성)
  private AnalysisHistory newHistory(Long id, Long userId, String sourcePath) {
    AnalysisHistory history = new AnalysisHistory(userId, "session-1", sourcePath, "/out");
    history.setId(id);
    return history;
  }

  @Test
  void downloadMyPresentation_정상이면_200과_attachment_Content_Disposition을_반환한다() throws IOException {
    AnalysisHistory history = newHistory(1L, 10L, "/a/b/myproject");
    when(analysisHistoryRepository.findById(1L)).thenReturn(Optional.of(history));
    byte[] pptxContent = {1, 2, 3, 4, 5};
    when(presentationGeneratorService.generateAnalysisResultPresentation(history)).thenReturn(pptxContent);

    ResponseEntity<byte[]> response = userActivityController.downloadMyPresentation(1L, authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertThat(response.getBody()).isEqualTo(pptxContent);
    assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation",
        response.getHeaders().getContentType().toString());
    assertEquals(pptxContent.length, response.getHeaders().getContentLength());

    // 폼 필드용 form-data가 아니라 파일 첨부용 attachment 타입이어야 한다.
    assertThat(response.getHeaders().getContentDisposition().isAttachment()).isTrue();

    // 파일명 포맷({type}_{projectName}_{timestamp}.pptx)은 수정 전과 동일하게 유지되어야 한다.
    String contentDisposition = response.getHeaders().getContentDisposition().toString();
    Pattern pattern = Pattern.compile("filename=\"summary_myproject_\\d{8}_\\d{6}\\.pptx\"");
    assertThat(pattern.matcher(contentDisposition).find()).isTrue();
  }

  @Test
  void downloadProjectReport_정상이면_200과_attachment_Content_Disposition을_반환한다() throws IOException {
    AnalysisHistory history = newHistory(1L, 10L, "/a/b/myproject");
    when(analysisHistoryRepository.findById(1L)).thenReturn(Optional.of(history));
    byte[] pptxContent = {9, 8, 7};
    when(presentationGeneratorService.generateProjectReportPresentation(history)).thenReturn(pptxContent);

    ResponseEntity<byte[]> response = userActivityController.downloadProjectReport(1L, authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertThat(response.getBody()).isEqualTo(pptxContent);
    assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation",
        response.getHeaders().getContentType().toString());
    assertEquals(pptxContent.length, response.getHeaders().getContentLength());

    // 폼 필드용 form-data가 아니라 파일 첨부용 attachment 타입이어야 한다.
    assertThat(response.getHeaders().getContentDisposition().isAttachment()).isTrue();

    String contentDisposition = response.getHeaders().getContentDisposition().toString();
    Pattern pattern = Pattern.compile("filename=\"report_myproject_\\d{8}_\\d{6}\\.pptx\"");
    assertThat(pattern.matcher(contentDisposition).find()).isTrue();
  }

  @Test
  void downloadMyPresentation_대상_이력이_없으면_404를_반환한다() {
    when(analysisHistoryRepository.findById(1L)).thenReturn(Optional.empty());

    ResponseEntity<byte[]> response = userActivityController.downloadMyPresentation(1L, authentication);

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    assertThat(response.getBody() == null || response.getBody().length == 0).isTrue();
  }

  @Test
  void downloadMyPresentation_타인의_이력이면_403을_반환한다() {
    // 이력 소유자(userId=99L)가 로그인 주체(seq=10L)와 다르다.
    AnalysisHistory history = newHistory(1L, 99L, "/a/b/myproject");
    when(analysisHistoryRepository.findById(1L)).thenReturn(Optional.of(history));

    ResponseEntity<byte[]> response = userActivityController.downloadMyPresentation(1L, authentication);

    assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
  }
}
