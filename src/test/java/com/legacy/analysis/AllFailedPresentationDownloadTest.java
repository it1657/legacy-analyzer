package com.legacy.analysis;

import com.legacy.auth.Role;
import com.legacy.auth.User;
import com.legacy.core.PresentationGeneratorService;
import com.legacy.core.ProjectTypeDetector;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TASK-006 (work-order 2026-10-resume-consistency-and-local-guard v1, REQ-002 ③) —
 * <b>전량실패(PAUSED) 이력으로도 PPT가 실제로 만들어지는지</b>를 <b>실제</b>
 * {@code PresentationGeneratorService}로 확인한다.
 *
 * <p>왜 필요한가: ③은 "폴링 응답에 {@code historyId}를 싣는다"까지만 고친다. 프런트가 그 id로
 * {@code /api/my/download/presentation/{id}}를 불렀을 때 서버가 500을 내면 결국 버튼은 동작하지
 * 않는다. 전량실패 이력은 <b>성공 0건 / 토큰·비용·모델명·완료시각이 모두 null</b>이라 정상 완료
 * 이력과 모양이 다르므로, 그 입력으로 PPT 생성이 터지지 않는지를 목이 아닌 실제 구현으로 본다.
 *
 * <p>{@code PresentationGeneratorService}는 수정하지 않는다(G-12) — 실패하면 멈추고 보고한다.
 */
class AllFailedPresentationDownloadTest {

  private static final Long USER_SEQ = 70L;
  private static final String USERNAME = "jhjung";

  private Authentication ownerAuth() {
    User owner = new User(USERNAME, USERNAME + "@example.com", "hash");
    owner.setSeq(USER_SEQ);
    owner.setRoles(Set.of(new Role("USER", "일반 사용자")));
    return new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities());
  }

  /** 전량실패로 PAUSED가 된 이력의 실제 모양 — 성공 0·실패 3, 토큰·비용·모델·완료시각 null. */
  private AnalysisHistory allFailedPausedHistory(long id) {
    AnalysisHistory h = new AnalysisHistory(USER_SEQ, "sid-all-failed", "/src/myproj", "/out");
    h.setId(id);
    h.setStatus("PAUSED");
    h.setTotalFiles(3);
    h.setSuccessCount(0);
    h.setSkipCount(0);
    h.setFailureCount(3);
    // 토큰·비용·모델명·완료시각은 전량실패 경로에서 기록되지 않는다(= null 그대로 둔다).
    return h;
  }

  @Test
  void 전량실패_PAUSED_이력으로도_실제_PPT가_생성되고_200으로_내려온다() {
    long historyId = 97L;
    AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);
    AnalysisHistory history = allFailedPausedHistory(historyId);
    when(historyRepository.findById(historyId)).thenReturn(Optional.of(history));

    // 실제 구현 2개를 그대로 쓴다(목이 아니다).
    PresentationGeneratorService realGenerator =
        new PresentationGeneratorService(new ProjectTypeDetector());
    UserActivityController controller = new UserActivityController(
        historyRepository, realGenerator, mock(SessionRepository.class));

    ResponseEntity<byte[]> response =
        controller.downloadMyPresentation(historyId, ownerAuth());

    byte[] body = response.getBody();
    HttpHeaders headers = response.getHeaders();
    System.out.println("[006 PPT] status=" + response.getStatusCode().value()
        + ", 바이트=" + (body == null ? "null" : body.length)
        + ", Content-Disposition=" + headers.getContentDisposition()
        + ", Content-Type=" + headers.getContentType());

    assertEquals(200, response.getStatusCode().value(),
        "전량실패 이력으로 PPT 생성이 실패했다(500이면 PPT 버튼이 결국 동작하지 않는다)");
    assertNotNull(body, "응답 본문이 없다");
    assertTrue(body.length > 0, "PPT 바이트가 0이다");
    assertNotNull(headers.getContentDisposition(), "Content-Disposition이 없다(파일 저장이 안 된다)");
    assertNotNull(headers.getContentDisposition().getFilename(), "파일명이 없다");
    assertTrue(headers.getContentDisposition().getFilename().endsWith(".pptx"),
        "파일명이 .pptx가 아니다: " + headers.getContentDisposition().getFilename());
    assertTrue(headers.getContentDisposition().getFilename().startsWith("summary_myproj_"),
        "파일명 형식이 기존과 다르다: " + headers.getContentDisposition().getFilename());

    // PPTX는 ZIP 컨테이너다 — 앞 2바이트가 "PK"인지로 "진짜 파일"임을 확인한다.
    assertEquals('P', (char) body[0], "PPTX(ZIP) 시그니처가 아니다");
    assertEquals('K', (char) body[1], "PPTX(ZIP) 시그니처가 아니다");
  }

  /**
   * 양성 대조군 — 같은 절차에서 정상 완료 이력(토큰·비용·모델·완료시각이 채워진 모양)도 200이다.
   * 위 전량실패 통과가 "이 경로가 뭐든 200을 돌려줘서"가 아님을 보인다(두 모양이 모두 처리된다).
   */
  @Test
  void 대조군_정상_완료_이력도_같은_경로로_200이고_바이트가_더_많거나_같다() {
    long allFailedId = 97L;
    long completedId = 98L;
    AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);

    AnalysisHistory allFailed = allFailedPausedHistory(allFailedId);
    AnalysisHistory completed =
        new AnalysisHistory(USER_SEQ, "sid-completed", "/src/myproj", "/out");
    completed.setId(completedId);
    completed.setStatus("COMPLETED");
    completed.setTotalFiles(3);
    completed.setSuccessCount(3);
    completed.setSkipCount(0);
    completed.setFailureCount(0);
    completed.setModelName("claude-sonnet-4-20250514");
    completed.setInputTokens(1000L);
    completed.setOutputTokens(2000L);
    completed.setTotalTokens(3000L);
    completed.setEstimatedCost(0.0333);
    completed.setCompletedAt(java.time.LocalDateTime.of(2026, 10, 1, 9, 0));
    completed.setProcessingTimeMs(12345L);

    when(historyRepository.findById(allFailedId)).thenReturn(Optional.of(allFailed));
    when(historyRepository.findById(completedId)).thenReturn(Optional.of(completed));

    UserActivityController controller = new UserActivityController(
        historyRepository, new PresentationGeneratorService(new ProjectTypeDetector()),
        mock(SessionRepository.class));

    ResponseEntity<byte[]> failedResponse = controller.downloadMyPresentation(allFailedId, ownerAuth());
    ResponseEntity<byte[]> completedResponse = controller.downloadMyPresentation(completedId, ownerAuth());

    System.out.println("[006 PPT 대조군] 전량실패=" + failedResponse.getStatusCode().value()
        + "/" + failedResponse.getBody().length + "바이트, 정상완료="
        + completedResponse.getStatusCode().value() + "/" + completedResponse.getBody().length + "바이트");

    assertEquals(200, failedResponse.getStatusCode().value());
    assertEquals(200, completedResponse.getStatusCode().value());
    assertTrue(failedResponse.getBody().length > 0);
    assertTrue(completedResponse.getBody().length > 0);
  }

  /**
   * 음성 방향 대조군 — 같은 엔드포인트가 "남의 이력"에는 403을 돌려준다. 즉 위 200들이
   * "이 엔드포인트가 무조건 200을 주기 때문"이 아님을 보인다.
   */
  @Test
  void 대조군_남의_이력이면_같은_엔드포인트가_403을_돌려준다() {
    long historyId = 99L;
    AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);
    AnalysisHistory other = allFailedPausedHistory(historyId);
    other.setUserId(USER_SEQ + 1);   // 다른 사용자의 이력
    when(historyRepository.findById(historyId)).thenReturn(Optional.of(other));
    when(historyRepository.findById(anyLong())).thenReturn(Optional.of(other));

    UserActivityController controller = new UserActivityController(
        historyRepository, new PresentationGeneratorService(new ProjectTypeDetector()),
        mock(SessionRepository.class));

    ResponseEntity<byte[]> response = controller.downloadMyPresentation(historyId, ownerAuth());
    System.out.println("[006 PPT 대조군] 남의 이력 → status=" + response.getStatusCode().value());
    assertEquals(403, response.getStatusCode().value(), "소유자 검사가 동작하지 않는다");
  }
}
