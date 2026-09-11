package com.legacy.analysis;

import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.auth.Role;
import com.legacy.auth.User;
import com.legacy.core.ApiErrorHandler;
import com.legacy.rag.CodeContentRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TASK-008 (work-order 2026-09-quick-fixes-batch v4, 설계 02-design-v1 §5.1) —
 * <b>{@code POST /api/dashboard-status}의 {@code folderPathStr}(원본 경로) 미처리 예외를
 * "실행"으로 재현하는 하네스</b>. REQ-004 1단계이며 <b>프로덕션 코드 변경은 0줄</b>이다.
 *
 * <p><b>기존 {@link MainApiControllerDashboardStatusPathContractTest}와 무엇이 다른가</b>:
 * 그쪽(직전 사이클)은 <b>{@code outputPath}</b>에 후행 공백/구분자 혼합이 섞였을 때
 * "예외 없이 완료를 0건으로 세는" 산식 어긋남을 고정했다. 이 클래스는 반대쪽 입력인
 * <b>{@code folderPath}</b>에 후행 공백이 섞였을 때 <b>응답 자체가 만들어지지 않는(예외가 컨트롤러 밖으로
 * 튀어나가는)</b> 경로를 본다. 하네스 골격(실제 {@code @TempDir} + LLM만 대역 +
 * {@code @Value} 필드 리플렉션 주입 + {@code userId=null}로 {@code AnalysisHistory} 경로 회피)은
 * 그 클래스를 그대로 재사용한다.
 *
 * <p><b>왜 예외가 되는가</b>(수정 전 코드 흐름, 커밋 {@code 074fec0} 기준 행번호):
 * <ol>
 *   <li>973~977행 가드 {@code new File(folderPathStr).exists() && isDirectory()} —
 *       Windows 파일 API가 경로 컴포넌트의 후행 공백을 스스로 잘라내므로 <b>통과한다</b>(전제 (a), R0에서 단언).</li>
 *   <li>979~981행: {@code outputPath}가 비어 있으면 {@code folderPathStr}을 그대로 대입한다
 *       (→ 후행 공백이 출력 경로에도 전파된다).</li>
 *   <li>983~987행: 구분자만 {@code \}→{@code /}로 정규화한다. <b>trim은 하지 않는다.</b></li>
 *   <li>996행 {@code resolveProjectOutputRoot(...)} → 815행 {@code Path.of(sourcePath)} —
 *       Windows {@code Path} 파서는 후행 공백을 <b>불법</b>으로 보고
 *       {@code InvalidPathException: Trailing char < > at index N}을 던진다(전제 (b)).</li>
 *   <li>이 지점은 1000행 {@code try}보다 <b>앞</b>이고 이 프로젝트에는 전역 예외 처리기
 *       ({@code @ControllerAdvice})가 없다 → 응답이 생성되지 않는다(실서비스에서는 HTTP 500).</li>
 * </ol>
 *
 * <p><b>단언의 방향(중요)</b>: R2/R3의 단언은 <b>TASK-009 수정 후의 기대값</b>으로 적혀 있다.
 * 즉 <b>TASK-008 시점에는 의도적으로 RED</b>이며(= 결함 재현 성공), TASK-009가 진입 가드에서
 * 파싱 불가 경로를 흡수하면 GREEN으로 전이된다(TASK-008 DoD 7 / TASK-009 DoD 1).
 * 실패 메시지에 <b>예외 클래스명 + 메시지 + 발생 행번호 포함 스택트레이스 원문</b>이 그대로 실리므로,
 * RED 출력 자체가 재현 근거 기록이 된다(TASK-008 DoD 2).
 *
 * <p><b>R1은 양성 대조군이다</b>(STRUCTURE.md 20절 / work-order §0.4·§0.8).
 * R2/R3가 "예외가 났다"는 결과든 "나지 않았다"는 음성 결과든, 그 근거가 성립하려면
 * <b>이 하네스가 실제 요청 경로를 모델링하고 있음</b>이 먼저 확인돼야 한다.
 * R1(정상 입력 → {@code completeCount == FILE_COUNT})이 그 확인이며,
 * <b>R1이 RED면 R2/R3의 결과는 근거로 쓸 수 없다</b>(work-order §0.8, TASK-008 DoD 1).
 *
 * <p><b>하네스 함정 3종 회피</b>(work-order TASK-008 작업내용 3 — 셋 다 "RED가 안 나서 결함이 은폐되는" 방향):
 * <ol>
 *   <li>스텁 주석은 {@link #STUB_COMMENT_PREFIX}를 쓴다 — {@code [AI 한글 주석 보완 완료]} 류 하위호환 마커를
 *       <b>포함하지 않는다</b>. 섞이면 완료 판정이 마커 검사 폴백으로 통과해 경로가 틀려도 완료로 센다.</li>
 *   <li>출력 루트 폴더명을 {@code out}으로 두지 않는다({@code outroot}) —
 *       {@code isSupportedFile()}이 경로에 {@code /out/}이 든 파일을 빌드 산출물로 전부 제외한다.</li>
 *   <li>관측지표는 파일 존재 여부가 아니라 응답의 {@code completeCount} / {@code files[].isCompleted}다.</li>
 * </ol>
 *
 * <p><b>플랫폼 의존성</b>: 후행 공백을 불법 문자로 보는 것은 Windows {@code Path} 파서의 동작이다.
 * Linux에서는 후행 공백이 유효한 파일명 문자라 {@code Path.of()}가 예외를 던지지 않는다 —
 * 그쪽에서는 예외 대신 "존재하지 않는 경로를 스캔해 완료 0건"이 될 것으로 <b>추정</b>되나 실측하지 않았다.
 * 게이트1 ⑨에 따라 이번 사이클의 종결 근거는 <b>Windows 로컬 실측 + 플랫폼/JDK 명시</b>다.
 * 실측 환경은 {@code 05-dev-progress.md}의 TASK-008 절에 기록했다
 * ({@code os.name=Windows 10}, {@code os.version=10.0}, {@code java.version=17.0.19} Temurin 17.0.19+10).
 */
class MainApiControllerDashboardStatusSourcePathContractTest {

  private static final String SID = "sid-dashboard-status-sourcepath";
  /** {@code runAnalysis()}에 넘기는 username과 {@code Authentication}의 로그인ID가 같은 값이어야 한다. */
  private static final String USERNAME = "jhjung";
  /** 하위호환 폴백 마커를 포함하지 않는 스텁 주석(기존 계약 테스트와 동일 값). */
  private static final String STUB_COMMENT_PREFIX = "// [AI 주석] 테스트 대역이 생성한 주석";
  /** 각 케이스가 만드는 분석 대상 파일 수(= 정상 동작 시 기대 completeCount). */
  private static final int FILE_COUNT = 2;
  /** 973~977행 가드가 반환하는 기존 사용자 문구 — TASK-009 수정 후 R2/R3가 받아야 할 값이다. */
  private static final String EXPECTED_SOURCE_PATH_ERROR = "올바르지 않은 원본 디렉터리 경로입니다.";
  /**
   * TASK-009 ⑧(b)가 신설하는 <b>출력 경로 전용</b> 사용자 문구(work-order v5 §0.13 / DoD 9).
   * 원본 경로 문구({@link #EXPECTED_SOURCE_PATH_ERROR})와 <b>다른 값</b>이어야 한다 —
   * 같으면 "어느 쪽 경로가 잘못됐는지" 구분이 사라지고, R5가 ⑧(b) 고유 근거가 되지 못한다.
   */
  private static final String EXPECTED_OUTPUT_PATH_ERROR = "올바르지 않은 출력 디렉터리 경로입니다.";

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
    // 함정 2 — 폴더명을 "out"으로 두면 isSupportedFile()이 전부 제외해 분석 자체가 FAILED가 된다.
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
    setField("uploadStoragePath", tempDir.resolve(".uploads").toString());

    when(analysisHistoryRepository.findBySessionId(SID)).thenReturn(null);
    // 함정 1 — 하위호환 마커 없는 주석만 붙인다.
    when(claudeService.analyzeCodeWithClaude(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> STUB_COMMENT_PREFIX + "\n" + invocation.getArgument(0));
  }

  private void setField(String name, Object value) throws Exception {
    Field field = MainApiController.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(controller, value);
  }

  private void createTwoSourceFiles() throws Exception {
    createSourceFile("com/x/A.java");
    createSourceFile("com/x/B.java");
  }

  private void createSourceFile(String relativeName) throws Exception {
    Path file = srcRoot.resolve(relativeName);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "public class X { void m() {} }\n", StandardCharsets.UTF_8);
  }

  /**
   * 분석 본체({@code runAnalysis()}, private)를 리플렉션으로 직접 호출해 실제 파일과 추적 파일을 만든다.
   * R1~R3 모두 <b>정상(공백 없는) 경로로 분석을 먼저 끝낸 뒤</b> 스캔 API만 다른 입력으로 호출한다 —
   * 실제 사용자 시나리오(분석은 정상 기동, 이후 폴링 요청 파라미터에 후행 공백이 섞임)를 그대로 모델링한 것이며,
   * 이렇게 해야 "예외가 나지 않았다면 몇 건을 셌을 것인가"가 관측 가능한 값으로 남는다.
   */
  private void runAnalysisAndAwait(String sourcePath, String outputPath) throws Exception {
    session = new SessionState(SID, sourcePath, outputPath);
    session.setUsername(USERNAME);
    session.setGenerateReadme(false);
    when(sessionManager.getSession(SID)).thenReturn(session);

    Method m = MainApiController.class.getDeclaredMethod("runAnalysis",
        String.class, String.class, String.class, boolean.class, Long.class, String.class,
        java.util.Set.class, boolean.class);
    m.setAccessible(true);
    m.invoke(controller, SID, sourcePath, outputPath, false, null, USERNAME, null, false);

    assertEquals("COMPLETED", session.getCurrentPhase(),
        "분석이 완료되지 않으면 이 케이스는 아무것도 관측하지 못한다. errorLog=" + session.getErrorLog());
  }

  /** {@code getDashboardStatus()}는 public이라 리플렉션 없이 직접 호출한다. */
  private Map<String, Object> dashboardStatus(String folderPath, String outputPath) {
    Map<String, String> request = new HashMap<>();
    request.put("folderPath", folderPath);
    request.put("outputPath", outputPath);
    return controller.getDashboardStatus(request, adminAuth());
  }

  /** {@code getDashboardStatus()}의 isAdmin() 가드를 통과하는 관리자 인증 픽스처. */
  private Authentication adminAuth() {
    User user = new User(USERNAME, USERNAME + "@example.com", "hash");
    user.setSeq(99L);
    user.setRoles(Set.of(new Role("ADMIN", "관리자 역할")));
    return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
  }

  private int intOf(Map<String, Object> result, String key) {
    Object value = result.get(key);
    return value instanceof Integer i ? i : -1;
  }

  /**
   * 스캔 API 호출 결과를 <b>예외까지 포함해</b> 붙여넣을 수 있는 형태로 담는다(TASK-008 DoD 2).
   * 예외가 나면 {@link #thrown}에 담고 스택트레이스 원문을 {@link #text}에 넣는다.
   * 예외가 나지 않으면 실제 응답값({@code error} 또는 {@code completeCount}/{@code files[]})을 넣는다.
   */
  private static final class Observation {
    private final Throwable thrown;
    private final Map<String, Object> result;
    private final String text;

    private Observation(Throwable thrown, Map<String, Object> result, String text) {
      this.thrown = thrown;
      this.result = result;
      this.text = text;
    }
  }

  @SuppressWarnings("unchecked")
  private Observation observe(String caseName, String folderPath, String outputPath) {
    String header = "[" + caseName + "] folderPath=[" + folderPath + "] outputPath=["
        + outputPath + "]\n";
    try {
      Map<String, Object> result = dashboardStatus(folderPath, outputPath);
      StringBuilder sb = new StringBuilder(header).append("  -> 예외 미발생. 응답 원문: ");
      if (result.get("error") != null) {
        sb.append("error=").append(result.get("error"));
      } else {
        sb.append("totalCount=").append(result.get("totalCount"))
            .append(", completeCount=").append(result.get("completeCount"))
            .append(", waitCount=").append(result.get("waitCount"))
            .append(", outputPath=").append(result.get("outputPath"))
            .append(", files=");
        List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");
        if (files != null) {
          for (Map<String, Object> file : files) {
            sb.append("{").append(file.get("fileName")).append(" isCompleted=")
                .append(file.get("isCompleted")).append("}");
          }
        }
      }
      return new Observation(null, result, sb.toString());
    } catch (Throwable t) {
      StringWriter sw = new StringWriter();
      t.printStackTrace(new PrintWriter(sw));
      String text = header + "  -> 예외 발생: " + t.getClass().getName() + ": " + t.getMessage()
          + "\n--- 스택트레이스 원문 ---\n" + sw;
      return new Observation(t, null, text);
    }
  }

  private static boolean isWindows() {
    return File.separatorChar == '\\';
  }

  private static void printEnvironment(String caseName) {
    System.out.println("[" + caseName + "] 실행 환경: os.name=" + System.getProperty("os.name")
        + ", os.version=" + System.getProperty("os.version")
        + ", os.arch=" + System.getProperty("os.arch")
        + ", java.version=" + System.getProperty("java.version")
        + ", java.vendor=" + System.getProperty("java.vendor"));
  }

  // ────────────────────────────────────────────────────────────────────────────

  /**
   * <b>R0 — 착수 전 전제 (a) 실측</b>(work-order TASK-008 작업내용 1 / STRUCTURE.md 21절).
   *
   * <p>설계 전제: {@code new File("{src} ").exists() && isDirectory()}가 <b>true</b>다.
   * true여야 973~977행 가드를 통과해 뒤쪽 {@code Path.of()}까지 도달하므로, 이 값이 false면
   * "가드가 이미 막고 있다 → 재현 실패"로 결론나고 R2/R3는 의미가 없어진다.
   * 그래서 이 전제를 문서가 아니라 <b>저장소 안의 실행 단언</b>으로 남긴다.
   */
  @Test
  void R0_전제a_후행공백이_붙은_원본경로도_File_존재가드를_통과한다() throws Exception {
    printEnvironment("R0");
    createTwoSourceFiles();
    String withSpace = srcRoot.toString().replace("\\", "/") + " ";

    File folder = new File(withSpace);
    System.out.println("[R0] new File([" + withSpace + "]) exists=" + folder.exists()
        + " isDirectory=" + folder.isDirectory()
        + " getAbsolutePath=[" + folder.getAbsolutePath() + "]");

    if (!isWindows()) {
      System.out.println("[R0] 이 플랫폼(non-Windows)에서는 후행 공백이 유효한 파일명 문자라 전제가 다르게 성립한다.");
      return;
    }
    assertTrue(folder.exists() && folder.isDirectory(),
        "전제 (a)가 false면 973~977행 가드가 이미 막고 있는 것이므로 REQ-004는 '재현 실패'로 종결된다.");
  }

  /**
   * <b>R1 — 양성 대조군(필수)</b>. 정상 경로(후행 공백 없음, copy 모드) → {@code completeCount == FILE_COUNT}.
   *
   * <p>이 케이스가 RED면 하네스가 실제 요청 경로를 모델링하지 못한 것이므로
   * <b>R2/R3의 결과(예외든 음성이든)는 근거로 쓸 수 없다</b>(work-order §0.8, TASK-008 DoD 1).
   * copy 모드라 추적 파일({@code .ai-analysis-done.txt})이 출력 루트 아래에 놓여 소스 루트 스캔에 잡히지 않으므로
   * {@code totalCount}도 {@link #FILE_COUNT} 그대로다.
   */
  @Test
  void R1_양성대조군_정상경로면_완료파일을_전부_인식한다() throws Exception {
    printEnvironment("R1");
    createTwoSourceFiles();
    String src = srcRoot.toString();
    String out = outRoot.toString();

    runAnalysisAndAwait(src, out);
    Observation obs = observe("R1 positive-control", src, out);
    System.out.println(obs.text);

    assertNull(obs.thrown, "R1(양성 대조군)에서 예외가 나면 하네스 자체가 잘못된 것이다.\n" + obs.text);
    assertNull(obs.result.get("error"), "R1은 오류 없이 응답해야 한다: " + obs.result.get("error"));
    assertEquals(FILE_COUNT, intOf(obs.result, "totalCount"),
        "스캔 대상 파일 수 자체가 다르면 하네스가 잘못된 것이다.\n" + obs.text);
    assertEquals(FILE_COUNT, intOf(obs.result, "completeCount"),
        "양성 대조군이 실패하면 이 하네스는 아무것도 증명하지 못한다(TASK-008 DoD 1 — 즉시 중단·보고 대상).\n"
            + obs.text);
  }

  /**
   * <b>R2 — 결함 재현</b>. {@code folderPath} 후행 공백 + {@code outputPath} <b>미지정</b>.
   *
   * <p>979~981행에서 {@code outputPath}에 {@code folderPathStr}이 그대로 대입되므로 후행 공백이 양쪽에 전파된다.
   * 이때 {@code isCopyModeOutput()}은 {@code outputPath.trim()}과 {@code sourcePath}를 비교하므로
   * (공백 유무 차이로) <b>copy 모드로 판정</b>되고, 996행 → 815행 {@code Path.of(sourcePath)}에서
   * 후행 공백이 그대로 파서에 들어간다.
   *
   * <p><b>단언은 TASK-009 수정 후 기대값이다</b> — 진입 가드가 파싱 불가 경로를 흡수해
   * {@code error="올바르지 않은 원본 디렉터리 경로입니다."}로 응답해야 한다.
   * TASK-008 시점에는 예외가 컨트롤러 밖으로 나가므로 <b>의도적으로 RED</b>이며, 그 RED 메시지에
   * 스택트레이스 원문이 실린다.
   */
  @Test
  void R2_원본경로_후행공백_출력경로_미지정이면_예외없이_오류응답을_돌려준다() throws Exception {
    printEnvironment("R2");
    createTwoSourceFiles();
    // 분석 자체는 정상 경로로 끝낸 상태에서, 폴링 요청 파라미터에만 후행 공백이 섞인 상황을 모델링한다.
    runAnalysisAndAwait(srcRoot.toString(), outRoot.toString());

    String folderWithSpace = srcRoot.toString().replace("\\", "/") + " ";
    Observation obs = observe("R2 sourcePath-trailing-space/output-unset", folderWithSpace, null);
    System.out.println(obs.text);

    assertNull(obs.thrown,
        "R2: 요청 파라미터의 후행 공백이 컨트롤러 밖으로 예외를 내보내면 안 된다"
            + "(전역 예외 처리기가 없어 응답 자체가 생성되지 않는다 = HTTP 500).\n" + obs.text);
    assertEquals(EXPECTED_SOURCE_PATH_ERROR, obs.result.get("error"),
        "R2: 파싱 불가 원본 경로는 진입 가드에서 기존 사용자 문구로 흡수돼야 한다.\n" + obs.text);
  }

  /**
   * <b>R3 — 결함 재현</b>. {@code folderPath} 후행 공백 + {@code outputPath} <b>정상 지정</b>.
   *
   * <p>출력 경로가 멀쩡해도 결과는 같다 — 815행이 보는 것은 {@code sourcePath}이기 때문이다.
   * R2와 나란히 두는 이유는 "출력 경로 미지정 때문에 생긴 전파" 가설과
   * "원본 경로 자체가 원인" 가설을 <b>분리해서</b> 보이기 위해서다.
   *
   * <p>단언 방향은 R2와 같다(TASK-009 수정 후 기대값, TASK-008 시점 의도적 RED).
   */
  @Test
  void R3_원본경로_후행공백_출력경로_정상지정이어도_예외없이_오류응답을_돌려준다() throws Exception {
    printEnvironment("R3");
    createTwoSourceFiles();
    runAnalysisAndAwait(srcRoot.toString(), outRoot.toString());

    String folderWithSpace = srcRoot.toString().replace("\\", "/") + " ";
    String out = outRoot.toString();
    Observation obs = observe("R3 sourcePath-trailing-space/output-set", folderWithSpace, out);
    System.out.println(obs.text);

    assertNull(obs.thrown,
        "R3: 출력 경로가 정상이어도 원본 경로가 파싱 불가면 같은 지점(815행 Path.of(sourcePath))에서 터진다.\n"
            + obs.text);
    assertEquals(EXPECTED_SOURCE_PATH_ERROR, obs.result.get("error"),
        "R3: 파싱 불가 원본 경로는 진입 가드에서 기존 사용자 문구로 흡수돼야 한다.\n" + obs.text);
  }

  /**
   * <b>R4 — 음성 대조(권고)</b>. {@code folderPath}에 Windows 금지문자({@code |})가 든 경우.
   *
   * <p>이 입력은 {@code new File(...).exists()}가 false라 <b>973~977행 가드에서 이미 걸러진다</b> —
   * 즉 "경로 문자열이 이상하면 무조건 500이 난다"가 아니라 <b>가드를 통과하는 형태(후행 공백)만</b>
   * 뒤쪽 {@code Path.of()}까지 도달한다는 것을 보여, R2/R3가 관측한 예외의 <b>범위를 한정</b>한다.
   *
   * <p>TASK-009 수정 후에도 이 케이스는 <b>무변경으로 계속 PASS</b>해야 한다
   * (가드가 반환하는 문구가 같으므로 사용자 관점 동작이 바뀌지 않는다).
   */
  @Test
  void R4_음성대조_금지문자_원본경로는_기존_가드에서_오류응답으로_걸러진다() throws Exception {
    printEnvironment("R4");
    createTwoSourceFiles();
    String forbidden = srcRoot.toString().replace("\\", "/") + "|x";

    Observation obs = observe("R4 negative-control/forbidden-char", forbidden, outRoot.toString());
    System.out.println(obs.text);

    assertNull(obs.thrown, "R4: 금지문자 경로는 기존 가드에서 걸러져 예외가 나지 않아야 한다.\n" + obs.text);
    assertEquals(EXPECTED_SOURCE_PATH_ERROR, obs.result.get("error"),
        "R4: 973~977행 가드가 반환하는 기존 사용자 문구여야 한다.\n" + obs.text);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // TASK-009 (work-order v5 §0.11 / 작업 7·8) — 심층 방어 항목의 "전용 근거" 케이스
  //
  // R2/R3를 GREEN으로 만드는 최소 조건은 folderPathStr 파싱 검사 하나뿐이다. 즉 게이트1 ⑧(a)(try 범위
  // 확장)와 ⑧(b)(outputPath 검사)를 구현하지 않아도 R2/R3는 GREEN이 되므로, R2/R3만으로 ⑧(a)(b)의
  // 반영을 주장하면 "공허한 통과"다. 아래 두 케이스는 각각 그 변경 <b>하나만</b>이 GREEN으로 만들 수 있는
  // 입력이며, "이 변경을 빼면 이 단언이 깨진다"를 커밋 3분할(C0/C1/C2) 실행으로 보이기 위한 것이다.
  //   - R5 → ⑧(b) 전용 (C0 RED / C1 GREEN / C2 GREEN)
  //   - R6 → ⑧(a) 전용 (C0 RED / C1 RED  / C2 GREEN)
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * <b>R5 — 게이트1 ⑧(b) 전용 근거</b>. {@code folderPath}는 <b>정상</b>이고 {@code outputPath}에만
   * Windows 금지문자({@code |})가 든 경우.
   *
   * <p><b>수정 전 경로</b>: 973~977행 가드는 {@code folderPathStr}만 보므로 통과 → 979~981행은
   * {@code outputPath}가 지정돼 있어 미해당 → 986/987행 구분자 정규화 → 995행
   * {@code resolveUserOutputRoot(...)} → copy 모드이므로 <b>805행 {@code Path.of(outputPath.trim())}</b>에서
   * {@code InvalidPathException}. 이 지점 역시 1000행 {@code try}보다 앞이라 응답이 만들어지지 않는다.
   *
   * <p><b>단언의 핵심은 문구 일치</b>다. ⑧(a)(try 범위 확장)만 적용된 상태라면 예외는 나지 않지만
   * 1058행 {@code catch}가 {@code e.getMessage()}(예외 원문)를 {@code error}에 넣으므로 이 단언은
   * <b>여전히 RED</b>다. 따라서 이 케이스는 ⑧(b)를 구현해야만 GREEN이 되는 고유 근거가 된다.
   *
   * <p><b>플랫폼 의존성</b>: {@code |}를 경로 불법 문자로 보는 것은 Windows {@code Path} 파서의 동작이다
   * (R4와 같은 전제). Linux에서는 유효한 파일명 문자라 이 입력이 파싱 거부되지 않는다 —
   * 이 클래스 전체가 게이트1 ⑨에 따라 <b>Windows 로컬 실측</b>을 종결 근거로 삼는다.
   */
  @Test
  void R5_출력경로에_금지문자가_있으면_예외없이_출력경로_오류응답을_돌려준다() throws Exception {
    printEnvironment("R5");
    createTwoSourceFiles();
    String src = srcRoot.toString();
    String badOut = outRoot.toString().replace("\\", "/") + "|x";

    // 전제 실측 기록(STRUCTURE.md 21절) — 수정 전에는 이 값이 파싱 불가라 805행에서 터진다.
    System.out.println("[R5] 전제: folderPath는 정상(File 가드 통과), outputPath만 파싱 불가."
        + " new File(src).isDirectory()=" + new File(src).isDirectory()
        + " / outputPath=[" + badOut + "]");

    Observation obs = observe("R5 outputPath-forbidden-char", src, badOut);
    System.out.println(obs.text);

    assertNull(obs.thrown,
        "R5: 출력 경로가 파싱 불가여도 컨트롤러 밖으로 예외가 나가면 안 된다"
            + "(805행 Path.of(outputPath.trim())는 1000행 try보다 앞이다).\n" + obs.text);
    assertEquals(EXPECTED_OUTPUT_PATH_ERROR, obs.result.get("error"),
        "R5: 파싱 불가 출력 경로는 진입 가드에서 '출력' 전용 문구로 흡수돼야 한다"
            + "(예외 원문 e.getMessage()가 실리면 ⑧(b)가 반영되지 않은 것이다).\n" + obs.text);
  }

  /**
   * <b>R6 — 게이트1 ⑧(a) 전용 근거(동작 케이스)</b>. {@code folderPath}가 <b>파일시스템 루트</b>인 경우.
   *
   * <p>루트 경로는 {@code new File("C:/").exists() && isDirectory()}가 true이고 {@code Path.of("C:/")}도
   * 정상 파싱되므로 <b>973행 가드도, ⑧(b)가 신설하는 파싱 검사도 모두 통과한다.</b> 그러나 copy 모드에서
   * 818행 {@code sourceRootPath.getFileName().toString()}은 루트의 {@code getFileName()}이 {@code null}이라
   * {@code NullPointerException}을 던진다. 이 지점은 995~998 구간이라 수정 전에는 {@code try} 밖이다 —
   * 즉 <b>⑧(a)(try 범위 확장)만이 흡수할 수 있는 잔여 예외</b>다.
   *
   * <p><b>안전장치(work-order v5 §0.14, 필수)</b>: 818행 NPE가 나지 않으면 실행이 1000행
   * {@code Files.walk(루트)}까지 도달해 <b>드라이브 전체를 순회</b>한다. 그래서 컨트롤러를 호출하기 <b>전에</b>
   * (1) 루트 경로임({@code getFileName() == null}) (2) copy 모드 성립 — 두 전제를 단언한다.
   * 두 전제가 성립하면 818행 NPE는 확정적이므로 {@code Files.walk}는 실행되지 않는다.
   *
   * <p><b>문구는 단언하지 않는다</b> — 1058행이 {@code e.getMessage()}를 그대로 넣는데 NPE의 메시지는
   * JVM 구현에 따라 {@code null}일 수 있다. 이번 사이클의 요구는 <b>"500이 되지 않게"</b>까지이며
   * (work-order §11), 루트 경로를 친절한 문구로 거부하는 것은 범위 밖이다.
   */
  @Test
  void R6_원본경로가_파일시스템_루트여도_예외없이_오류응답을_돌려준다() throws Exception {
    printEnvironment("R6");
    createTwoSourceFiles();
    // 컨트롤러가 986행에서 적용하는 구분자 정규화를 그대로 반영한 값으로 전제를 단언한다.
    String rootStr = tempDir.getRoot().toString().replace("\\", "/");
    String out = outRoot.toString().replace("\\", "/");

    // ── §0.14 안전장치 (컨트롤러 호출 전에 반드시 확인) ──────────────────────────
    assertNull(Path.of(rootStr).getFileName(),
        "전제 1 미성립: [" + rootStr + "]가 루트 경로가 아니면 818행 NPE가 확정적이지 않고, "
            + "실행이 Files.walk까지 도달해 드라이브 전체를 순회한다. 케이스를 실행하지 않는다.");
    assertTrue(!out.isBlank() && !out.trim().equals(rootStr),
        "전제 2 미성립: copy 모드가 아니면 818행(getFileName())에 도달하지 않는다. "
            + "outputPath=[" + out + "] folderPath=[" + rootStr + "]");
    System.out.println("[R6] 전제 확인: getFileName()=null, copy 모드 성립 → 818행 NPE 확정,"
        + " Files.walk(루트)는 실행되지 않는다. folderPath=[" + rootStr + "] outputPath=[" + out + "]");

    Observation obs = observe("R6 sourcePath-filesystem-root", rootStr, out);
    System.out.println(obs.text);

    assertNull(obs.thrown,
        "R6: 818행 NPE가 컨트롤러 밖으로 나가면 안 된다 — 995~998 구간이 기존 catch(Exception e)의 "
            + "보호 범위에 들어와야 한다(게이트1 ⑧(a)).\n" + obs.text);
    assertTrue(obs.result.containsKey("error"),
        "R6: 잔여 예외는 기존 catch가 흡수해 error 키를 담은 응답으로 돌아와야 한다.\n" + obs.text);
    assertNull(obs.result.get("files"),
        "R6: 오류 응답이므로 정상 응답 키(files)가 있으면 안 된다.\n" + obs.text);
    assertNull(obs.result.get("completeCount"),
        "R6: 오류 응답이므로 정상 응답 키(completeCount)가 있으면 안 된다.\n" + obs.text);
  }
}
