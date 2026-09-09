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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TASK-001 (work-order 2026-09-resume-copymode-path-fix v1, 설계 02-design-v1 §5.1) —
 * <b>copy 모드에서 {@code runAnalysis()}가 실제로 파일을 놓는 위치를 실행 결과로 못박는 계약 고정 테스트</b>.
 *
 * <p><b>이 테스트가 무엇을 고정하는가</b>: 이 사이클의 본질은 "읽기 측 3개 메서드
 * ({@code runAnalysisResume()} / {@code resolveAnalysisRoot()} / {@code resolveFailedFilesRoot()})를
 * 쓰기 측 1곳({@code runAnalysis()})에 맞추는 것"이다. 그 <b>정답</b>이 무엇인지를 코드 인용이 아니라
 * 실제 실행 결과로 고정해 두는 것이 이 테스트의 목적이다 — 즉 위 3개 메서드가 맞혀야 하는 답안지다.
 * 나중에 {@code runAnalysis()}를 손대거나 경로 산식을 전면 통합하다가 이 위치가 달라지면
 * 그 순간 여기서 RED가 나야 한다.
 *
 * <p><b>양성 대조군 역할</b>(STRUCTURE.md 20절): 같은 사이클의 다른 테스트들이 "{@code {out}/.ai-analysis-done.txt}가
 * 생기지 않는다", "{@code {out}/myproj}가 생기지 않는다" 같은 <b>음성 결과</b>를 근거로 삼는다.
 * 이 테스트는 그 앞단에서 "정상 경로에서는 이 위치에 실제로 파일과 추적 파일이 생긴다"를 먼저 보여,
 * 음성 결과가 "하네스가 아무 일도 안 해서 아무것도 안 생긴 것"이 아님을 보증한다.
 *
 * <p><b>관찰지표는 전부 OS 무관하다</b>(work-order §0.4): 파일이 생성된 <b>위치</b>, 갱신된 <b>내용</b>,
 * username 세그먼트 없는 경로의 <b>비생성</b>만 본다. "{@code ..}가 낀 경로로의 쓰기가 실패한다"는
 * Windows/Linux가 갈리는 지표라 여기서 쓰지 않는다(그 확인은 TASK-005 실컨테이너 담당).
 *
 * <p><b>하네스</b>: {@link MainApiControllerResumeFailedFilesCleanupTest} 패턴 재사용 —
 * 실제 임시 디렉터리 + LLM만 대역 + {@code @Value} 필드 리플렉션 주입 + 나머지는 mock.
 * {@code userId=null}로 호출해 {@code AnalysisHistory} 저장 경로를 통째로 건너뛰므로 DB mock 부담이 없다.
 */
class MainApiControllerCopyModeOutputRootContractTest {

  private static final String SID = "sid-copymode-contract";
  private static final String USERNAME = "jhjung";
  private static final String STUB_COMMENT_PREFIX = "// [AI 주석] 테스트 대역이 생성한 주석";

  @TempDir
  Path tempDir;

  private ClaudeService claudeService;
  private AnalysisSessionManager sessionManager;
  private MainApiController controller;

  private Path srcRoot;
  private Path outRoot;
  private SessionState session;

  @BeforeEach
  void setUp() throws Exception {
    srcRoot = tempDir.resolve("myproj");
    // 출력 루트 폴더명을 work-order의 예시("out")가 아니라 "outroot"로 둔 이유:
    // isSupportedFile()이 경로에 "/out/"이 포함된 파일을 빌드 산출물로 보고 전부 제외한다.
    // 폴더명을 "out"으로 두면 복사는 되지만 collectFileList()가 0개를 반환해 분석 자체가
    // "분석 대상 파일 없음"으로 FAILED가 된다(실측 확인). 경로 계약과는 무관한 하네스 제약이다.
    outRoot = tempDir.resolve("outroot");
    Files.createDirectories(srcRoot);
    Files.createDirectories(outRoot);

    claudeService = mock(ClaudeService.class);
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
    // runAnalysis()가 "업로드 세션인가" 판정에 쓰는 @Value 필드 — 스프링 없이는 null이라 NPE가 난다.
    setField("uploadStoragePath", tempDir.resolve(".uploads").toString());

    when(analysisHistoryRepository.findBySessionId(SID)).thenReturn(null);
    // LLM 대역: 원본 코드 앞에 주석 한 줄을 붙여 돌려준다(write-back이 실제로 일어났는지 내용으로 확인 가능).
    when(claudeService.analyzeCodeWithClaude(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> STUB_COMMENT_PREFIX + "\n" + invocation.getArgument(0));

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

  private Path createSourceFile(String relativeName) throws Exception {
    Path file = srcRoot.resolve(relativeName);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "public class X { void m() {} }\n", StandardCharsets.UTF_8);
    return file;
  }

  /**
   * '분석 시작' 이후의 실제 분석 본체({@code runAnalysis()})를 리플렉션으로 직접 호출한다.
   * 프로덕션에서는 별도 스레드로 기동되지만 메서드 자체는 동기적으로 끝나므로 폴링 없이 반환을 기다리면 된다.
   */
  private void runAnalysisAndAwait() throws Exception {
    Method m = MainApiController.class.getDeclaredMethod("runAnalysis",
        String.class, String.class, String.class, boolean.class, Long.class, String.class,
        java.util.Set.class, boolean.class);
    m.setAccessible(true);
    m.invoke(controller, SID, srcRoot.toString(), outRoot.toString(), false, null, USERNAME, null, false);
  }

  // ────────────────────────────────────────────────────────────────────────────

  /**
   * copy 모드 최초 분석의 저장 위치 계약 — {@code {out}/{safeUsername}/{sourceFolderName}/...}.
   * work-order §0.5 (a) 전제를 실행 결과로 확정하는 케이스다.
   */
  @Test
  void copy모드_분석결과는_출력루트아래_username_소스폴더명_경로에_기록된다() throws Exception {
    createSourceFile("com/x/A.java");
    createSourceFile("com/x/B.java");

    runAnalysisAndAwait();

    assertEquals("COMPLETED", session.getCurrentPhase(),
        "분석이 완료되지 않으면 이 테스트는 아무 경로도 고정하지 못한다. errorLog=" + session.getErrorLog());

    // 집계 카운터(session.getStatistics())는 비원자적 setter라 병렬 분석에서 값이 하나 덜 반영될 수
    // 있는 기존 flaky가 있다(work-order 회귀 주의사항, 지난 사이클 51회 실측으로 확정). 이 계약
    // 테스트의 관찰지표는 카운터가 아니라 "디스크의 어느 위치에 무엇이 쓰였는가"이므로 파일로 확인한다.
    Path writtenA = outRoot.resolve(USERNAME).resolve("myproj").resolve("com").resolve("x").resolve("A.java");
    Path writtenB = outRoot.resolve(USERNAME).resolve("myproj").resolve("com").resolve("x").resolve("B.java");
    System.out.println("[TASK-001 계약] 실제 기록 위치 = " + writtenA);
    assertTrue(Files.exists(writtenA),
        "runAnalysis()가 파일을 놓는 위치가 {out}/{username}/{srcName}/... 이 아니라면 읽기 측 산식의 정답이 바뀐 것이다");
    assertTrue(Files.readString(writtenA, StandardCharsets.UTF_8).startsWith(STUB_COMMENT_PREFIX),
        "그 위치의 파일이 실제로 분석 결과로 갱신돼 있어야 한다(빈 복사본이면 계약을 고정한 것이 아니다)");
    assertTrue(Files.exists(writtenB) && Files.readString(writtenB, StandardCharsets.UTF_8)
            .startsWith(STUB_COMMENT_PREFIX),
        "두 번째 파일도 같은 루트에서 갱신돼야 한다(한 건만 우연히 맞은 것이 아님을 확인)");
  }

  /** username 세그먼트가 빠진 경로({@code {out}/{srcName}})는 최초 분석에서 아예 만들어지지 않는다. */
  @Test
  void copy모드에서_username_세그먼트_없는_경로는_생성되지_않는다() throws Exception {
    createSourceFile("com/x/A.java");

    runAnalysisAndAwait();

    Path withoutUsername = outRoot.resolve("myproj");
    System.out.println("[TASK-001 계약] username 없는 경로 존재 여부 = " + Files.exists(withoutUsername)
        + " : " + withoutUsername);
    assertFalse(Files.exists(withoutUsername),
        "읽기 측이 계산하는 {out}/{srcName}은 쓰기 측이 만들지 않는 경로다 — 이것이 이 사이클 버그의 핵심 어긋남이다");
  }

  /**
   * 추적 파일({@code .ai-analysis-done.txt})의 위치 계약 — 프로젝트 루트가 아니라 <b>계정 루트</b>
   * {@code {out}/{safeUsername}}에 놓인다. 재개 경로가 이 위치를 못 맞히면 재시작 후 같은 파일을
   * 다시 LLM에 태우는 조용한 중복 비용이 생긴다(게이트1 확정 3번).
   */
  @Test
  void 추적파일은_출력루트가_아니라_계정루트에_생성되고_기록된_파일의_절대경로를_담는다() throws Exception {
    createSourceFile("com/x/A.java");

    runAnalysisAndAwait();

    Path userTracker = outRoot.resolve(USERNAME).resolve(".ai-analysis-done.txt");
    assertTrue(Files.exists(userTracker), "추적 파일은 계정 루트({out}/{username})에 있어야 한다: " + userTracker);

    String trackerBody = Files.readString(userTracker, StandardCharsets.UTF_8);
    String writtenAbs = outRoot.resolve(USERNAME).resolve("myproj").resolve("com").resolve("x")
        .resolve("A.java").toAbsolutePath().normalize().toString();
    System.out.println("[TASK-001 계약] 추적 파일 내용 = " + trackerBody.trim());
    assertTrue(trackerBody.contains(writtenAbs),
        "추적 파일에는 실제로 기록된 파일의 절대경로가 들어가야 한다. 기대=" + writtenAbs + " 실제=" + trackerBody);
  }

  /** 출력 루트 바로 아래({@code {out}/.ai-analysis-done.txt})에는 추적 파일이 생기지 않는다. */
  @Test
  void 출력루트_바로아래에는_추적파일이_생성되지_않는다() throws Exception {
    createSourceFile("com/x/A.java");

    runAnalysisAndAwait();

    Path outRootTracker = outRoot.resolve(".ai-analysis-done.txt");
    System.out.println("[TASK-001 계약] {out}/.ai-analysis-done.txt 존재 여부 = " + Files.exists(outRootTracker));
    assertFalse(Files.exists(outRootTracker),
        "여기에 추적 파일이 생긴다면 계정 루트 계약이 깨진 것이다(재개 경로가 지금 이 자리에 쓰고 있는 것이 REQ-001 버그다)");
  }
}
