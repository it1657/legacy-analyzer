package com.legacy.analysis;

import com.legacy.analysis.llm.LlmModelOptionService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REQ-002 / TASK-007(work-order v5, 설계 02-design-v4 §2.4 버그2·§3.4) —
 * {@code resolveFailedFilesRoot()}의 {@code isCopyMode} 판정을 {@code runAnalysis()}(1262-1263행)와
 * 동일하게 {@code outputPath.trim()} 기준으로 맞춘 것을 고정한다.
 *
 * <p>수정 전에는 "출력 경로 = 소스 경로 + 후행 공백"인 입력에서 {@code runAnalysis()}는 비-copy 모드로
 * (원본 직접 수정) 동작하는데 {@code resolveFailedFilesRoot()}만 copy 모드로 판정해, failedFiles의 상대경로가
 * {@code ../..}류로 깨지고 프런트 {@code globalFilesCache.fileName}과 한 건도 매칭되지 않은 채 조용히
 * "실패 파일 0건"으로 수렴했다(예외도 안 남는다). 그 조용한 실패를 재현하는 테스트다.
 */
class MainApiControllerFailedFilesRootTrimTest {

  private MainApiController controllerFor(SessionState session) {
    AnalysisSessionManager sessionManager = mock(AnalysisSessionManager.class);
    when(sessionManager.getSession(session.getSessionId())).thenReturn(session);
    return new MainApiController(
        null, null, sessionManager, null, null, null,
        null, null, null, null, null, null, null, mock(LlmModelOptionService.class), null);
  }

  private Path resolveFailedFilesRoot(MainApiController controller, SessionState session) throws Exception {
    Method m = MainApiController.class.getDeclaredMethod("resolveFailedFilesRoot", SessionState.class);
    m.setAccessible(true);
    return (Path) m.invoke(controller, session);
  }

  @Test
  void 출력경로에_후행공백만_있으면_비copy모드로_판정해_sourcePath를_그대로_기준으로_쓴다() throws Exception {
    // runAnalysis()는 outputPath.trim()으로 비교해 "같다"고 보고 원본을 직접 수정한다(비-copy 모드).
    SessionState session = new SessionState("sid", "/tmp/src/myproj", "/tmp/src/myproj ");
    session.setUsername("jhjung");
    MainApiController controller = controllerFor(session);

    Path root = resolveFailedFilesRoot(controller, session);

    assertEquals(Path.of("/tmp/src/myproj"), root,
        "isCopyMode가 true로 계산되면 {out}/{username}/{srcName} 경로가 나와 runAnalysis()의 실제 저장 위치와 어긋난다");
  }

  @Test
  void 후행공백_출력경로에서도_failedFiles가_소스폴더_기준_상대경로로_내려간다() {
    SessionState session = new SessionState("sid", "/tmp/src/myproj", "/tmp/src/myproj ");
    session.setUsername("jhjung");
    session.setCurrentPhase("COMPLETED");
    session.addFailedFilePath("/tmp/src/myproj/com/x/A.java");

    List<String> failedFiles = controllerFor(session).getAnalysisStatus("sid", 80, null).getFailedFiles();

    assertEquals(List.of("com/x/A.java"), failedFiles,
        "trim 없이 copy 모드로 판정하면 상대경로가 깨져 프런트 파일명과 한 건도 매칭되지 않는다");
    // 양성 대조군: trim 수정 전에는 '../..'로 시작하는 값이 나왔다.
    assertFalse(failedFiles.get(0).startsWith(".."),
        "상대경로가 상위 디렉터리로 올라가면 analysisRoot 공식이 실제 저장 경로와 어긋난 것이다");
  }

  /**
   * v6 신규 케이스. 위 세 건과 조합이 다르다 — 여기서는 출력 경로가 소스 경로와 **다르면서**(=진짜 copy 모드)
   * 후행 공백이 붙어 있다. 판정식(isCopyMode)만 trim된 1회차 상태에서는 copy 모드로 정상 판정된 뒤
   * 경로 생성부가 trim 없는 outputPath를 그대로 써서, runAnalysis()가 실제로 파일을 쓰는
   * {out.trim()}/{username}/{srcName}과 어긋난 루트가 나온다.
   */
  @Test
  void 출력경로가_소스와_다르고_후행공백이_있으면_copy모드_경로도_trim된_출력경로로_계산된다() throws Exception {
    SessionState session = new SessionState("sid", "/tmp/src/myproj", "/tmp/out ");
    session.setUsername("jhjung");
    MainApiController controller = controllerFor(session);

    Path root = resolveFailedFilesRoot(controller, session);

    assertEquals(Path.of("/tmp/out").resolve("jhjung").resolve("myproj"), root,
        "경로 생성부에 trim이 없으면 runAnalysis()의 실제 저장 경로(Path.of(outputPath.trim())...)와 어긋난다");
  }

  /**
   * v6 신규 케이스의 end-to-end 확인. 헬퍼 반환값이 아니라 실제 응답 DTO의 failedFiles 값으로 확인한다.
   */
  @Test
  void 출력경로가_소스와_다르고_후행공백이_있어도_failedFiles가_소스폴더_기준_상대경로로_내려간다() {
    SessionState session = new SessionState("sid", "/tmp/src/myproj", "/tmp/out ");
    session.setUsername("jhjung");
    session.setCurrentPhase("COMPLETED");
    // runAnalysis()가 실제로 파일을 쓰는 위치: {out.trim()}/{safeUsername}/{srcName}/...
    String written = Path.of("/tmp/out").resolve("jhjung").resolve("myproj")
        .resolve("com").resolve("x").resolve("A.java").toString();
    session.addFailedFilePath(written);

    List<String> failedFiles = controllerFor(session).getAnalysisStatus("sid", 80, null).getFailedFiles();

    assertEquals(List.of("com/x/A.java"), failedFiles,
        "경로 생성부 trim이 빠지면 relativize 기준이 어긋나 프런트 파일명과 매칭되지 않는다");
    assertFalse(failedFiles.get(0).startsWith(".."),
        "상대경로가 상위 디렉터리로 올라가면 analysisRoot 공식이 실제 저장 경로와 어긋난 것이다");
  }

  @Test
  void 공백없는_정상_copy모드_판정은_이번_변경의_영향을_받지_않는다() throws Exception {
    // 회귀 확인: outputPath에 공백이 없으면 trim() 유무가 결과를 바꾸지 않아야 한다.
    SessionState session = new SessionState("sid", "/tmp/src/myproj", "/tmp/out");
    session.setUsername("jhjung");
    MainApiController controller = controllerFor(session);

    Path root = resolveFailedFilesRoot(controller, session);

    assertEquals(Path.of("/tmp/out").resolve("jhjung").resolve("myproj"), root,
        "정상 copy 모드({out}/{username}/{srcName})는 종전 그대로 유지돼야 한다");
  }
}
