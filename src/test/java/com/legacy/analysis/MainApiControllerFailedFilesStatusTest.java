package com.legacy.analysis;

import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.auth.Role;
import com.legacy.auth.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GET /api/analysis/status/{sessionId} 의 신규 failedFiles 필드 검증 (REQ-002 / TASK-003, work-order v3).
 *
 * 이 사이클에서 TASK-003이 두 번 블로킹된 이유를 그대로 회귀 케이스로 고정한다.
 * - 1건차: 단위 테스트가 실제 분석 루프와 다른 방식으로 데이터를 채워 GREEN이 나와도 증명이 안 되던 문제
 *   → 테스트는 getFailedFilePaths()를 직접 채우지 않고 반드시 addFailedFilePath()(분석 루프가 쓰는
 *     바로 그 진입점)를 통해서만 채운다.
 * - 2건차: copy 모드의 실제 저장 경로에는 계정별 분리 세그먼트({username})가 하나 더 있는데
 *   getSessionFileList()의 기존 공식({out}/{srcName})을 재사용해 상대경로가 한 세그먼트 어긋나던 문제
 *   → copy 모드 케이스는 반드시 setUsername()을 설정하고 {out}/{username}/{srcName}/... 형태의
 *     절대경로로 실패 파일을 등록해 runAnalysis()의 실제 저장 경로 형태를 그대로 재현한다.
 *     v2 공식으로 계산되면 "../jhjung/myproj/com/x/A.java"가 나오므로 그 값이 나오면 실패해야 한다
 *     (양성 대조군 — 아래 각 copy 모드 테스트에 명시적 assertNotEquals/assertFalse로 박아둔다).
 */
class MainApiControllerFailedFilesStatusTest {

  /** v2(잘못된) 공식으로 계산했을 때 나오는 값 — 이 값이 나오면 테스트는 실패해야 한다(양성 대조군). */
  private static final String V2_WRONG_RELATIVE_PATH = "../jhjung/myproj/com/x/A.java";

  private MainApiController newController(AnalysisSessionManager sessionManager) {
    return new MainApiController(
        null, null, sessionManager, null, null, null,
        null, null, null, null, null, null, null, mock(LlmModelOptionService.class), null);
  }

  /** getSessionFileList()는 세션 소유자 검사를 하므로 소유자 본인으로 호출하기 위한 인증 객체. */
  private Authentication authAs(String loginId) {
    User user = new User(loginId, loginId + "@example.com", "hash");
    user.setSeq(1L);
    user.setRoles(Set.of(new Role("USER", "일반 사용자")));
    return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
  }

  private MainApiController controllerFor(SessionState session) {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    when(sessionManager.getSession(session.getSessionId())).thenReturn(session);
    return newController(sessionManager);
  }

  @Test
  void copy모드_실패파일은_username세그먼트까지_제거한_소스폴더_기준_상대경로로_내려간다() {
    // runAnalysis()의 실제 저장 경로: {outputPath}/{safeUsername}/{sourceFolderName}/...
    SessionState session = new SessionState("sid", "/tmp/src/myproj", "/tmp/out");
    session.setUsername("jhjung");
    session.setCurrentPhase("COMPLETED");
    session.addFailedFilePath("/tmp/out/jhjung/myproj/com/x/A.java");

    AnalysisStatusDto dto = controllerFor(session).getAnalysisStatus("sid", 80, null);

    List<String> failedFiles = dto.getFailedFiles();
    assertEquals(List.of("com/x/A.java"), failedFiles,
        "copy 모드 실패 파일은 소스 폴더 기준 상대경로여야 프런트 globalFilesCache.fileName과 매칭된다");
    // 양성 대조군: v2 공식({out}/{srcName} 기준)으로 계산되면 아래 값이 나온다.
    assertFalse(failedFiles.contains(V2_WRONG_RELATIVE_PATH),
        "v2 공식(username 세그먼트 누락)으로 계산되면 안 된다 — 이 값이 나오면 copy 모드에서 배지 매칭이 전부 실패한다");
    assertFalse(failedFiles.get(0).startsWith(".."),
        "상대경로가 상위 디렉터리(..)로 올라가면 analysisRoot 공식이 실제 저장 경로와 어긋난 것이다");
  }

  @Test
  void 비copy모드_실패파일은_sourcePath_기준_상대경로로_내려간다() {
    // outputPath == sourcePath (업로드 분석/출력 경로 미지정) → username 세그먼트 없음
    SessionState session = new SessionState("sid", "/tmp/src/myproj", "/tmp/src/myproj");
    session.setUsername("jhjung");
    session.setCurrentPhase("COMPLETED");
    session.addFailedFilePath("/tmp/src/myproj/com/y/B.java");

    AnalysisStatusDto dto = controllerFor(session).getAnalysisStatus("sid", 80, null);

    assertEquals(List.of("com/y/B.java"), dto.getFailedFiles(),
        "비-copy 모드는 sourcePath 자체가 기준이므로 username 세그먼트가 끼어들면 안 된다");
  }

  @Test
  void copy모드_SUCCESS2_FAILED1_SKIPPED1_혼합에서_FAILED_1건만_내려간다() {
    SessionState session = new SessionState("sid", "/tmp/src/myproj", "/tmp/out");
    session.setUsername("jhjung");
    session.setCurrentPhase("COMPLETED");
    // 분석 루프는 SUCCESS/SKIPPED 파일을 failedFilePaths에 넣지 않는다 — 그 상태를 그대로 재현한다.
    session.getPatchedFilePaths().add("/tmp/out/jhjung/myproj/com/x/Ok1.java");   // SUCCESS
    session.getPatchedFilePaths().add("/tmp/out/jhjung/myproj/com/x/Ok2.java");   // SUCCESS
    session.getPatchedFilePaths().add("/tmp/out/jhjung/myproj/com/x/Already.java"); // SKIPPED(ALREADY_PATCHED)
    session.addFailedFilePath("/tmp/out/jhjung/myproj/com/x/A.java");             // FAILED
    session.getStatistics().setSuccessCount(2);
    session.getStatistics().setSkipCount(1);
    session.getStatistics().setFailureCount(1);

    AnalysisStatusDto dto = controllerFor(session).getAnalysisStatus("sid", 80, null);

    assertEquals(List.of("com/x/A.java"), dto.getFailedFiles(),
        "SUCCESS/SKIPPED 파일이 failedFiles에 섞이면 프런트가 정상 파일까지 '처리실패'로 표시한다");
    assertFalse(dto.getFailedFiles().contains(V2_WRONG_RELATIVE_PATH),
        "양성 대조군 — v2 공식으로 계산되면 안 된다");
    // 기존 집계 필드는 이번 변경과 무관하게 그대로여야 한다(회귀 확인).
    assertEquals(2, dto.getSuccessCount());
    assertEquals(1, dto.getAlreadyCount());
    assertEquals(1, dto.getFailedCount());
  }

  @Test
  void copy모드_username에_경로에_못쓰는_문자가_있으면_runAnalysis와_동일한_sanitize_규칙을_적용한다() {
    // runAnalysis()의 safeUsername 규칙: [^a-zA-Z0-9_\-] → '_'
    SessionState session = new SessionState("sid", "/tmp/src/myproj", "/tmp/out");
    session.setUsername("jh.jung@corp");
    session.setCurrentPhase("COMPLETED");
    session.addFailedFilePath("/tmp/out/jh_jung_corp/myproj/com/x/A.java");

    AnalysisStatusDto dto = controllerFor(session).getAnalysisStatus("sid", 80, null);

    assertEquals(List.of("com/x/A.java"), dto.getFailedFiles(),
        "sanitize 규칙이 runAnalysis()와 다르면 실제 저장 경로와 어긋나 매칭이 전부 깨진다");
  }

  @Test
  void copy모드_username이_없으면_unknown_세그먼트를_사용한다() {
    // runAnalysis()는 username이 null/blank면 "unknown" 폴더를 쓴다.
    SessionState session = new SessionState("sid", "/tmp/src/myproj", "/tmp/out");
    session.setCurrentPhase("COMPLETED");
    session.addFailedFilePath("/tmp/out/unknown/myproj/com/x/A.java");

    AnalysisStatusDto dto = controllerFor(session).getAnalysisStatus("sid", 80, null);

    assertEquals(List.of("com/x/A.java"), dto.getFailedFiles());
  }

  @Test
  void phase가_FAILED인_세션도_failedFiles를_내려준다() {
    SessionState session = new SessionState("sid", "/tmp/src/myproj", "/tmp/out");
    session.setUsername("jhjung");
    session.setCurrentPhase("FAILED");
    session.addFailedFilePath("/tmp/out/jhjung/myproj/com/x/A.java");

    AnalysisStatusDto dto = controllerFor(session).getAnalysisStatus("sid", 80, null);

    assertEquals(List.of("com/x/A.java"), dto.getFailedFiles(),
        "세션 전체 치명적 실패(phase=FAILED)도 프런트가 같은 완료 처리 함수를 타므로 함께 내려줘야 한다");
  }

  @Test
  void 분석_진행중_폴링_응답에는_failedFiles가_비어있다() {
    SessionState session = new SessionState("sid", "/tmp/src/myproj", "/tmp/out");
    session.setUsername("jhjung");
    session.setCurrentPhase("ANALYZING");
    session.addFailedFilePath("/tmp/out/jhjung/myproj/com/x/A.java");

    AnalysisStatusDto dto = controllerFor(session).getAnalysisStatus("sid", 80, null);

    assertTrue(dto.getFailedFiles().isEmpty(),
        "진행 중 폴링은 완료 처리 대상이 아니므로 failedFiles를 내려주지 않는다(기본값 빈 배열 유지)");
  }

  @Test
  void getSessionFileList의_파일명은_실제_저장루트_기준_상대경로다() {
    // 회귀 확인: getSessionFileList()는 resolveAnalysisRoot()를 통해
    // 실제 저장 루트({out}/{username}/{srcName})를 기준으로 잡으므로,
    // 재개 그리드 복원용 파일 목록의 fileName은 소스 폴더 기준 상대경로로 내려와야 한다.
    // (픽스처 경로는 runAnalysis()가 copy 모드에서 실제로 파일을 놓는 위치와 동일하게 둔다.)
    SessionState session = new SessionState("sid", "/tmp/src/myproj", "/tmp/out");
    session.setUsername("jhjung");
    session.getPatchedFilePaths().add("/tmp/out/jhjung/myproj/com/z/C.java");

    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    when(sessionManager.getSession("sid")).thenReturn(session);
    MainApiController controller = newController(sessionManager);

    @SuppressWarnings("unchecked")
    List<java.util.Map<String, Object>> files =
        (List<java.util.Map<String, Object>>) controller.getSessionFileList("sid", authAs("jhjung")).get("files");

    assertNotNull(files, "세션 소유자 본인 호출은 기존 동작 그대로 성공해야 한다");
    assertEquals(1, files.size());
    assertEquals("com/z/C.java", files.get(0).get("fileName"),
        "getSessionFileList()는 resolveAnalysisRoot()({out}/{username}/{srcName})를 기준으로"
            + " 소스 폴더 상대경로를 내려줘야 한다");
  }
}
