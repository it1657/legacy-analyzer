package com.legacy.analysis;

import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.core.ApiErrorHandler;
import com.legacy.rag.CodeContentRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TASK-003 / REQ-001 (work-order 2026-09-resume-copymode-path-fix v1, 설계 02-design-v1 §5.2(a)(b)) —
 * <b>copy 모드 세션을 '이어서 분석'으로 재개할 때 {@code runAnalysisResume()}가 계산하는 경로가
 * {@code runAnalysis()}(쓰기 측 정답)와 어긋나 있던 결함</b>의 재현→해소 테스트.
 *
 * <p><b>수정 전 어긋남 3가지</b>(02-design-v1 §1.2): {@code isCopyMode} 판정에 null/blank/trim 가드가 없고,
 * {@code finalProjectOutputPath}에 {@code {safeUsername}} 세그먼트가 빠졌으며,
 * {@code finalOutPath}가 raw {@code outputPath}라 추적 파일({@code .ai-analysis-done.txt})이
 * 계정 루트가 아니라 출력 루트 바로 아래에 쓰였다.
 *
 * <p><b>RED/GREEN 판정 지표는 전부 OS 무관하다</b>(work-order §0.4 — 필독 지시):
 * 이 결함의 최종 증상인 "{@code {out}/{src}/../{user}/{src}/...} 경로로의 쓰기 실패"는 Linux 컨테이너에서만
 * 재현될 가능성이 높다. 실제로 이 저장소의 테스트 호스트(Windows)에서 즉석 스니펫으로 실측한 결과,
 * 존재하지 않는 중간 디렉터리를 지나는 {@code ..} 경로에도 <b>쓰기가 성공</b>했다(05-dev-progress.md 기록).
 * 그래서 여기서는 <b>추적 파일이 생성된 위치</b>, <b>갱신된 파일의 위치</b>, <b>username 없는 경로의 비생성</b>만
 * 지표로 쓴다. "쓰기가 실패한다"를 assert 하는 케이스는 하나도 없다 — 그 확인은 TASK-005(실컨테이너) 담당이다.
 *
 * <p><b>하네스</b>: {@link MainApiControllerResumeFailedFilesCleanupTest} 패턴 그대로 —
 * {@code resumePendingFilesInThread()} <b>실제 진입점</b>을 리플렉션으로 호출하고, 테스트는
 * 경로 계산도 {@code addFailedFilePath}/{@code removeFailedFilePath}도 직접 호출하지 않는다.
 * 전부 프로덕션 경로가 하게 만든다.
 */
class MainApiControllerResumeCopyModePathTest {

  private static final String SID = "sid-resume-copymode";
  private static final String USERNAME = "jhjung";
  private static final String STUB_COMMENT_PREFIX = "// [AI 주석] 테스트 대역이 생성한 주석";

  @TempDir
  Path tempDir;

  private AnalysisSessionManager sessionManager;
  private MainApiController controller;

  private Path srcRoot;
  /** 출력 루트. 폴더명이 "out"이면 isSupportedFile()의 "/out/" 제외 규칙에 걸리므로 "outroot"를 쓴다. */
  private Path outRoot;
  /** runAnalysis()가 copy 모드에서 실제로 파일을 놓는 프로젝트 루트 = {out}/{username}/{srcName}. */
  private Path realProjectRoot;
  private SessionState session;

  @BeforeEach
  void setUp() throws Exception {
    srcRoot = tempDir.resolve("myproj");
    outRoot = tempDir.resolve("outroot");
    realProjectRoot = outRoot.resolve(USERNAME).resolve("myproj");
    Files.createDirectories(srcRoot);
    Files.createDirectories(realProjectRoot);

    ClaudeService claudeService = mock(ClaudeService.class);
    sessionManager = mock(AnalysisSessionManager.class);
    AnalysisHistoryRepository analysisHistoryRepository = mock(AnalysisHistoryRepository.class);
    CodeContentRagService codeContentRagService = mock(CodeContentRagService.class);
    LlmModelOptionService llmModelOptionService = mock(LlmModelOptionService.class);

    SessionConfig sessionConfig = new SessionConfig();
    sessionConfig.setMaxRetries(0);
    RetryHandler retryHandler = new RetryHandler(new ApiErrorHandler(), sessionConfig);

    controller = new MainApiController(
        claudeService, null, sessionManager, null, null, retryHandler,
        analysisHistoryRepository, null, null, null, null, null,
        codeContentRagService, llmModelOptionService, null);
    setField("chunkingThresholdBytes", 153600L);
    setField("chunkSizeLines", 1000);
    setField("chunkOverlapLines", 100);
    setField("threadPoolSize", 2);
    setField("uploadStoragePath", tempDir.resolve(".uploads").toString());

    when(analysisHistoryRepository.findBySessionId(SID)).thenReturn(null);
    when(llmModelOptionService.getActiveFailoverTarget()).thenReturn(Optional.empty());
    when(claudeService.analyzeCodeWithClaude(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> STUB_COMMENT_PREFIX + "\n" + invocation.getArgument(0));

    // copy 모드 세션: sourcePath != outputPath
    session = new SessionState(SID, srcRoot.toString(), outRoot.toString());
    session.setUsername(USERNAME);
    session.setGenerateReadme(false);
    when(sessionManager.getSession(SID)).thenReturn(session);
  }

  private void setField(String name, Object value) throws Exception {
    Field field = MainApiController.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(controller, value);
  }

  /**
   * pending 파일을 <b>TASK-001이 실행으로 고정한 실제 저장 위치</b>({@code {out}/{username}/{srcName}/...})에
   * 실제로 만들어 둔다 — 최초 분석이 일시정지된 직후의 디스크 상태를 그대로 재현하는 것이다.
   */
  private Path createPendingFileAtRealLocation(String relativeName) throws Exception {
    Path file = realProjectRoot.resolve(relativeName);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "public class X { void m() {} }\n", StandardCharsets.UTF_8);
    return file;
  }

  private void givenPausedSessionWithPendingFiles(List<Path> files) {
    session.setTotalFiles(files.size());
    session.setStatus("PAUSED");
    session.setCurrentPhase("PAUSED");
    List<String> pending = new ArrayList<>();
    for (Path f : files) pending.add(f.toString());
    session.setPendingFilePaths(pending);
  }

  /** '이어서 분석' 실제 진입점을 호출하고 재개 스레드가 종료 상태에 도달할 때까지 기다린다. */
  @SuppressWarnings("unchecked")
  private void resumeAndAwait() throws Exception {
    Method m = MainApiController.class.getDeclaredMethod(
        "resumePendingFilesInThread", SessionState.class, String.class);
    m.setAccessible(true);
    Map<String, Object> response = (Map<String, Object>) m.invoke(controller, session, SID);
    assertEquals(Boolean.TRUE, response.get("success"),
        "재개 진입점이 실패하면 이 테스트는 아무것도 검증하지 못한다: " + response.get("message"));

    long deadline = System.currentTimeMillis() + 60_000L;
    while (System.currentTimeMillis() < deadline) {
      String phase = session.getCurrentPhase();
      if ("COMPLETED".equals(phase) || "PAUSED".equals(phase) || "FAILED".equals(phase)
          || "CANCELLED".equals(phase)
          || SessionState.STATUS_AWAITING_FAILOVER_CONFIRM.equals(phase)) {
        return;
      }
      Thread.sleep(20L);
    }
    fail("재개 스레드가 60초 안에 종료 상태에 도달하지 못했다. phase=" + session.getCurrentPhase());
  }

  // ────────────────────────────────────────────────────────────────────────────
  // (a) 재개 경로 end-to-end — REQ-001 재현→해소
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * <b>이 사이클의 핵심 케이스.</b> 수정 전에는 추적 파일이 {@code {out}/.ai-analysis-done.txt}에 생기고
   * 계정 루트 {@code {out}/{username}/.ai-analysis-done.txt}에는 생기지 않았다(게이트1 확정 3번의 어긋남).
   * 그 결과 앱 재시작 후 같은 소스를 다시 분석하면 재개로 이미 처리했던 파일을 다시 LLM에 태운다.
   */
  @Test
  void copy모드_재개는_추적파일을_출력루트가_아니라_계정루트에_기록한다() throws Exception {
    Path fileA = createPendingFileAtRealLocation("com/x/A.java");
    Path fileB = createPendingFileAtRealLocation("com/x/B.java");
    givenPausedSessionWithPendingFiles(List.of(fileA, fileB));

    resumeAndAwait();

    Path userTracker = outRoot.resolve(USERNAME).resolve(".ai-analysis-done.txt");
    Path outRootTracker = outRoot.resolve(".ai-analysis-done.txt");
    System.out.println("[TASK-003] 계정 루트 추적파일 존재 = " + Files.exists(userTracker) + " : " + userTracker);
    System.out.println("[TASK-003] 출력 루트 추적파일 존재 = " + Files.exists(outRootTracker) + " : " + outRootTracker);

    assertTrue(Files.exists(userTracker),
        "재개분 추적 기록은 runAnalysis()와 같은 계정 루트({out}/{username})에 남아야 한다");
    String body = Files.readString(userTracker, StandardCharsets.UTF_8);
    System.out.println("[TASK-003] 계정 루트 추적파일 내용 = " + body.trim().replace("\n", " | "));
    assertTrue(body.contains(fileA.toAbsolutePath().normalize().toString()),
        "처리한 파일의 절대경로가 추적 파일에 있어야 한다. 실제=" + body);
    assertTrue(body.contains(fileB.toAbsolutePath().normalize().toString()),
        "처리한 파일의 절대경로가 추적 파일에 있어야 한다. 실제=" + body);
    assertFalse(Files.exists(outRootTracker),
        "출력 루트 바로 아래에 추적 파일이 생기면 최초 분석이 읽는 파일과 다른 파일에 기록한 것이다");
  }

  /** 재개된 파일은 <b>원래 자리</b>({@code {out}/{username}/{srcName}/...})에서 내용이 갱신돼야 한다. */
  @Test
  void copy모드_재개는_파일을_원래자리에서_갱신하고_정상_완료된다() throws Exception {
    Path fileA = createPendingFileAtRealLocation("com/x/A.java");
    String before = Files.readString(fileA, StandardCharsets.UTF_8);
    givenPausedSessionWithPendingFiles(List.of(fileA));

    resumeAndAwait();

    assertEquals("COMPLETED", session.getCurrentPhase(),
        "재개가 정상 완료돼야 한다. errorLog=" + session.getErrorLog());
    assertEquals(0, session.getStatistics().getFailureCount(),
        "copy 모드 재개에서 실패가 나면 경로 계산이 어긋난 것이다");
    String after = Files.readString(fileA, StandardCharsets.UTF_8);
    System.out.println("[TASK-003] 원래 자리 파일 갱신 여부 = " + !before.equals(after) + " : " + fileA);
    assertFalse(before.equals(after), "재개 경로가 원래 자리의 파일을 실제로 다시 써야 한다");
    assertTrue(after.startsWith(STUB_COMMENT_PREFIX), "write-back 내용이 실제 분석 결과여야 한다");
  }

  /**
   * username 세그먼트가 빠진 경로({@code {out}/{srcName}})는 재개 과정에서도 만들어지면 안 된다.
   * (Windows에서는 {@code ..}가 어휘적으로 접히므로 수정 전에도 이 디렉터리가 안 생길 수 있다 —
   * 그래서 이 지표 하나만으로 판정하지 않고 위 두 케이스와 함께 본다. Linux에서는 여기서 갈린다.)
   */
  @Test
  void copy모드_재개는_username_없는_경로를_만들지_않는다() throws Exception {
    Path fileA = createPendingFileAtRealLocation("com/x/A.java");
    givenPausedSessionWithPendingFiles(List.of(fileA));

    resumeAndAwait();

    Path withoutUsername = outRoot.resolve("myproj");
    System.out.println("[TASK-003] username 없는 경로 존재 = " + Files.exists(withoutUsername)
        + " : " + withoutUsername);
    assertFalse(Files.exists(withoutUsername),
        "쓰기 측이 만들지 않는 경로를 재개 측이 만들면 두 경로 산식이 다시 어긋난 것이다");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // (b) 헬퍼 산식 단위 케이스 7종 (DoD 4번) — MainApiControllerFailedFilesRootTrimTest 패턴
  // ────────────────────────────────────────────────────────────────────────────

  private Path projectRoot(String sourcePath, String outputPath, String username) throws Exception {
    Method m = MainApiController.class.getDeclaredMethod(
        "resolveProjectOutputRoot", String.class, String.class, String.class);
    m.setAccessible(true);
    return (Path) m.invoke(null, sourcePath, outputPath, username);
  }

  private String userRoot(String sourcePath, String outputPath, String username) throws Exception {
    Method m = MainApiController.class.getDeclaredMethod(
        "resolveUserOutputRoot", String.class, String.class, String.class);
    m.setAccessible(true);
    return (String) m.invoke(null, sourcePath, outputPath, username);
  }

  private boolean copyMode(String sourcePath, String outputPath) throws Exception {
    Method m = MainApiController.class.getDeclaredMethod(
        "isCopyModeOutput", String.class, String.class);
    m.setAccessible(true);
    return (boolean) m.invoke(null, sourcePath, outputPath);
  }

  @Test
  void 헬퍼_정상_copy모드는_출력루트아래_username_소스폴더명을_돌려준다() throws Exception {
    assertTrue(copyMode("/tmp/src/myproj", "/tmp/out"));
    assertEquals(Path.of("/tmp/out").resolve("jhjung").resolve("myproj"),
        projectRoot("/tmp/src/myproj", "/tmp/out", "jhjung"));
    assertEquals(Path.of("/tmp/out").resolve("jhjung").toString().replace("\\", "/"),
        userRoot("/tmp/src/myproj", "/tmp/out", "jhjung"));
  }

  @Test
  void 헬퍼_후행공백_copy모드는_trim된_출력경로로_계산한다() throws Exception {
    assertTrue(copyMode("/tmp/src/myproj", "/tmp/out "));
    assertEquals(Path.of("/tmp/out").resolve("jhjung").resolve("myproj"),
        projectRoot("/tmp/src/myproj", "/tmp/out ", "jhjung"),
        "trim이 빠지면 runAnalysis()의 실제 저장 경로와 어긋난다");
  }

  @Test
  void 헬퍼_후행공백만_다른_출력경로는_비copy모드로_판정한다() throws Exception {
    assertFalse(copyMode("/tmp/src/myproj", "/tmp/src/myproj "),
        "runAnalysis()는 trim 비교로 '같다'고 보고 원본을 직접 수정한다(비-copy 모드)");
    assertEquals(Path.of("/tmp/src/myproj"),
        projectRoot("/tmp/src/myproj", "/tmp/src/myproj ", "jhjung"));
    assertEquals("/tmp/src/myproj", userRoot("/tmp/src/myproj", "/tmp/src/myproj ", "jhjung"));
  }

  @Test
  void 헬퍼_outputPath가_null이면_비copy모드로_폴백한다() throws Exception {
    assertFalse(copyMode("/tmp/src/myproj", null));
    assertEquals(Path.of("/tmp/src/myproj"), projectRoot("/tmp/src/myproj", null, "jhjung"),
        "null 가드가 없으면 copy 모드로 오판한 뒤 Path.of(null)에서 NPE가 난다");
    assertEquals("/tmp/src/myproj", userRoot("/tmp/src/myproj", null, "jhjung"));
  }

  @Test
  void 헬퍼_outputPath가_빈문자열이면_비copy모드로_폴백한다() throws Exception {
    assertFalse(copyMode("/tmp/src/myproj", ""));
    assertEquals(Path.of("/tmp/src/myproj"), projectRoot("/tmp/src/myproj", "", "jhjung"),
        "blank 가드가 없으면 Path.of(\"\")가 루트로 해석돼 엉뚱한 경로가 나온다");
    assertEquals("/tmp/src/myproj", userRoot("/tmp/src/myproj", "", "jhjung"));
  }

  @Test
  void 헬퍼_username이_null이면_unknown_세그먼트를_쓴다() throws Exception {
    assertEquals(Path.of("/tmp/out").resolve("unknown").resolve("myproj"),
        projectRoot("/tmp/src/myproj", "/tmp/out", null));
    assertEquals(Path.of("/tmp/out").resolve("unknown").toString().replace("\\", "/"),
        userRoot("/tmp/src/myproj", "/tmp/out", null));
  }

  @Test
  void 헬퍼_username의_특수문자는_언더스코어로_치환된다() throws Exception {
    assertEquals(Path.of("/tmp/out").resolve("jh_jung_corp_com").resolve("myproj"),
        projectRoot("/tmp/src/myproj", "/tmp/out", "jh jung@corp.com"),
        "runAnalysis()의 [^a-zA-Z0-9_\\-] → _ 규칙을 그대로 복제해야 실제 저장 경로와 일치한다");
  }
}
