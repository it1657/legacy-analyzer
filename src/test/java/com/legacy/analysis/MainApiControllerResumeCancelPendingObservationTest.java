package com.legacy.analysis;

import com.legacy.rag.CodeContentRagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TASK-001 (work-order 2026-10-resume-entry-guard v3, REQ-001 / O44) —
 * <b>재개 도중 취소로 끝나는 경로가 남은 파일 목록을 빈 목록으로 저장한다</b>는 현재 동작을 그대로
 * 적어 두는 <b>관찰 시험</b>이다. 게이트1 ②에서 "보존하지 않는다 = 의도된 동작"으로 확정됐으므로
 * 이 시험은 <b>고치기 전·후 모두 GREEN</b>이며, 수정 대상이 아니라 기록이다.
 *
 * <p>왜 이 모양인가(설계 v3 §3.4): 재개 입구({@code resumePendingFilesInThread})가 스레드를 띄우기
 * 직전에 메모리 세션의 대기 목록을 비우고({@code setPendingFilePaths(new ArrayList&lt;&gt;())}),
 * 재개 취소 분기는 대기 목록을 다시 쓰지 않은 채 세션을 저장한다. 그래서 저장되는 목록은 빈 목록이다.
 * 첫 실행 도중 취소({@code runAnalysis}의 취소 분기)도 목록을 쓴 적이 없어 같은 모양이므로,
 * "취소로 끝난 분석은 남은 목록이 없다"가 두 경로 공통 모양이다.
 *
 * <p>호출 방법은 기존 선례와 같은 리플렉션 직접 호출이다
 * ({@code MainApiControllerResumeSourceMissingTest}의 케이스ⓔ). 세션 공급 mock은 <b>모든 스레드에
 * 같은 세션</b>을 돌려준다 — 재개 루프의 일하는 스레드가 {@code null}을 받으면 취소 감지를 건너뛰고
 * 파일 분석으로 가기 때문이다.
 */
class MainApiControllerResumeCancelPendingObservationTest {

  @TempDir
  Path dir;

  private void runAnalysisResume(MainApiController controller, String sessionId, List<Path> fileList)
      throws Exception {
    Method m = MainApiController.class.getDeclaredMethod("runAnalysisResume", String.class, List.class);
    m.setAccessible(true);
    m.invoke(controller, sessionId, fileList);
  }

  @Test
  void E1_재개_도중_취소로_끝나면_남은_목록이_빈_목록으로_저장되고_상태는_CANCELLED가_된다() throws Exception {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    AnalysisHistoryRepository historyRepository = mock(AnalysisHistoryRepository.class);
    ClaudeService claudeService = mock(ClaudeService.class);
    CodeContentRagService codeContentRagService = mock(CodeContentRagService.class);
    MainApiController controller = new MainApiController(
        claudeService, null, sessionManager, null, null, null,
        historyRepository, null, null, null, null, null, codeContentRagService, null, null);

    // 입구(resumePendingFilesInThread)가 대기 목록을 비우고 스레드를 띄운 직후 상태를 그대로 세운다.
    SessionState session = new SessionState("sid", dir.toString(), dir.resolve("out").toString());
    session.setUsername("owner");
    session.setTotalFiles(1);
    session.setStatus("IN_PROGRESS");
    session.setCurrentPhase("ANALYZING");
    session.setPendingFilePaths(new ArrayList<>());
    session.cancel();

    // 요청 스레드와 재개 루프의 일하는 스레드 모두 같은 세션을 받아야 취소 감지에 들어간다.
    when(sessionManager.getSession("sid")).thenReturn(session);
    when(historyRepository.findBySessionId("sid")).thenReturn(null);

    // "저장된 남은 목록"은 저장이 일어난 그 순간의 값으로 관측한다.
    List<String> savedPendingJson = Collections.synchronizedList(new ArrayList<>());
    List<String> savedStatus = Collections.synchronizedList(new ArrayList<>());
    doAnswer(invocation -> {
      SessionState saved = invocation.getArgument(0);
      savedPendingJson.add(saved.getPendingFilePathsJson());
      savedStatus.add(saved.getStatus());
      return null;
    }).when(sessionManager).saveSessionState(any());

    Path target = Files.createFile(dir.resolve("A.java"));

    runAnalysisResume(controller, "sid", List.of(target));

    System.out.println("[E1 저장 시점 대기목록 JSON] " + savedPendingJson);
    System.out.println("[E1 저장 시점 status] " + savedStatus);
    System.out.println("[E1 종료 후] status=" + session.getStatus()
        + ", phase=" + session.getCurrentPhase()
        + ", pendingJson=" + session.getPendingFilePathsJson());

    // 취소 분기로 끝났다 — 완료·실패 처리로 가지 않았다.
    verify(sessionManager, never()).completeSession(anyString());
    verify(sessionManager, never()).failSession(anyString(), anyString());
    verify(sessionManager, times(1)).saveSessionState(any());
    assertEquals("CANCELLED", session.getStatus());
    assertEquals("CANCELLED", session.getCurrentPhase());
    // O44 — 저장된 "남은 파일 목록"은 빈 목록이다(보존하지 않는다 = 의도된 동작).
    assertEquals(List.of("[]"), savedPendingJson,
        "취소 분기가 저장한 대기 목록이 빈 목록이 아니다: " + savedPendingJson);
    assertEquals(List.of("CANCELLED"), savedStatus,
        "저장 직전 status가 CANCELLED로 맞춰지지 않았다: " + savedStatus);
    assertEquals("[]", session.getPendingFilePathsJson());
  }
}
