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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TASK-001/TASK-002 (work-order 2026-09-outputpath-normalization v2, 설계 02-design-v2 §5) —
 * <b>{@code POST /api/dashboard-status}(1단계 스캔)의 경로 산식이 실제 쓰기 위치({@code runAnalysis()})와
 * 일치하는지를 "실행 결과"로 못박는 계약 테스트</b>.
 *
 * <p><b>무엇을 재현하는가</b>: {@code getDashboardStatus()}는 계정별 출력 경로를 인라인으로 4번째 복제해
 * 갖고 있었고, 그 사본에는 {@code outputPath.trim()} / blank 가드 / 경로 구분자 정규화가 빠져 있었다.
 * 완료 판정이 <b>절대경로 문자열 일치 비교</b>이므로 산식이 한 글자만 어긋나도 추적 파일과 한 건도
 * 매칭되지 않는다.
 *
 * <p><b>수정 전 실패 양상은 플랫폼 의존적이다</b>(work-order v2 §0.5 전제 (b) — TASK-001 실측으로 정정된 사실).
 * 설계 v1은 "예외도 로그도 없이 전부 대기중으로 보인다"고 서술했으나 <b>Windows에서는 그렇지 않다</b>:
 * 후행 공백이 붙은 경로에서 {@code Path.of()}가 {@code InvalidPathException}을 던지고, 그 지점이
 * {@code getDashboardStatus()}의 {@code try} 블록보다 <b>앞</b>이며 이 프로젝트에 전역 예외 처리기가 없어
 * <b>응답 자체가 생성되지 않는다(HTTP 500)</b>. Linux에서는 후행 공백이 유효 문자라 예외 없이
 * {@code completeCount == 0}이 될 것으로 추정되나 실측하지 않았다.
 * <b>어느 갈래든 "수정 전에는 완료를 한 건도 인지하지 못한다 → 수정 후 completeCount == N"이라는
 * RED→GREEN 전이는 동일하게 성립</b>하므로 이 테스트의 단언은 갈래와 무관하게 유효하다.
 *
 * <p><b>관측지표</b>(work-order §0.4 조건 3): "파일이 존재하는가"가 아니라 응답의
 * {@code completeCount} / {@code files[].isCompleted}를 본다. 완료 판정이 문자열 비교라 이 지표는
 * Windows/Linux 양쪽에서 동일하게 RED가 난다(파일 존재 여부를 지표로 삼으면 Windows는 경로 컴포넌트의
 * 후행 공백을 스스로 잘라내 버려 재현되지 않는다).
 *
 * <p><b>하네스 조건 3가지</b>(work-order §0.4 — 셋 다 "RED가 안 나서 결함이 은폐되는" 방향의 함정이다):
 * <ol>
 *   <li>LLM 대역의 스텁 주석은 {@link #STUB_COMMENT_PREFIX}를 쓴다 —
 *       {@code [AI 한글 주석 보완 완료]} / {@code [AI 한글 주석 가상 시뮬레이션 완료]} 마커를 <b>포함하지 않는다</b>.
 *       이 마커가 섞이면 {@code getDashboardStatus()}의 하위호환 폴백(마커 문자열 검사)이 발동해
 *       경로가 틀렸는데도 완료로 판정된다. 기존 {@code MainApiControllerCopyModeOutputRootContractTest}의
 *       상수를 그대로 재사용한 값이다.</li>
 *   <li>출력 루트 폴더명을 {@code out}으로 두지 않는다({@code outroot}) —
 *       {@code isSupportedFile()}이 경로에 {@code /out/}이 든 파일을 빌드 산출물로 보고 전부 제외한다.</li>
 *   <li>관측지표는 파일 존재 여부가 아니라 {@code completeCount} / {@code files[].isCompleted}다(위 참고).</li>
 * </ol>
 *
 * <p><b>C1은 양성 대조군이다</b>(STRUCTURE.md 20절). C2/C3/C4의 근거는 "{@code completeCount == 0}"이라는
 * 음성 결과인데, 하네스가 애초에 아무 일도 하지 않았어도 0이 나온다. 그래서 "정상 입력이면 수정 전에도
 * 정확히 N을 센다"를 같은 하네스로 먼저 보인다. <b>C1이 RED면 C2/C3의 0은 아무것도 증명하지 못한다.</b>
 *
 * <p><b>하네스 원본</b>: {@code MainApiControllerCopyModeOutputRootContractTest}(실제 {@code @TempDir} +
 * LLM만 대역 + {@code @Value} 필드 리플렉션 주입 + {@code userId=null}로 {@code AnalysisHistory} 경로 회피).
 * 인증 픽스처는 {@code MainApiControllerAnthropicAccessGuardTest}의 {@code authOf(...)} 패턴을 재사용한다.
 */
class MainApiControllerDashboardStatusPathContractTest {

  private static final String SID = "sid-dashboard-status-contract";
  /** {@code runAnalysis()}에 넘기는 username과 {@code Authentication}의 로그인ID가 같은 값이어야 한다. */
  private static final String USERNAME = "jhjung";
  /** work-order §0.4 조건 1 — 하위호환 폴백 마커를 포함하지 않는 스텁 주석. */
  private static final String STUB_COMMENT_PREFIX = "// [AI 주석] 테스트 대역이 생성한 주석";
  /** 각 케이스가 만드는 분석 대상 파일 수(= 정상 동작 시 기대 completeCount). */
  private static final int FILE_COUNT = 2;
  /**
   * <b>비-copy 모드에서만</b> 스캔 목록이 {@link #FILE_COUNT}보다 1건 많아지는 현재 동작을 고정한 값
   * (work-order v2 §0.8 판단 3 — PL이 모드별 정확값으로 지시).
   *
   * <p><b>왜 +1인가</b>: 비-copy(원본 직접 수정) 모드에서는 출력 루트 == 소스 루트이므로
   * {@code runAnalysis()}가 만드는 추적 파일 {@code .ai-analysis-done.txt}가 <b>스캔 대상 소스 루트 안</b>에
   * 놓인다. 그런데 {@code isSupportedFile()}의 지원 확장자 목록에 {@code .txt}가 포함돼 있어
   * 추적 파일 자신이 스캔 목록에 1건 끼어든다. 추적 파일은 자기 자신을 완료 목록에 기록하지 않으므로
   * {@code isCompleted=false}로 고정되고, 그 결과 {@code waitCount}에도 1이 영구히 남는다.
   *
   * <p><b>이번 사이클의 결함이 아니다</b>: 이 수정 전후로 동일하게 나타나는 <b>기존 별건 결함</b>이며,
   * {@code docs/pipeline/bug-suspects.md}에 2026-09-09 별도 항목(상태 {@code 미확인})으로 등록됐다.
   * 여기서는 <b>고치지 않고 현재 동작을 그대로 고정</b>만 한다 — 이 값이 바뀌면(고쳐지든 악화되든)
   * RED가 나서 사람이 알아채도록 하는 것이 목적이다({@code >=} 완화를 쓰지 않는 이유).
   *
   * <p>반대로 <b>copy 모드</b>(C1·C3)에서는 추적 파일이 출력 루트({@code {out}/{safeUsername}}) 아래에 있어
   * 소스 루트 스캔에 잡히지 않으므로 {@link #FILE_COUNT} 그대로다.
   */
  private static final int NON_COPY_SCAN_COUNT = FILE_COUNT + 1;

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
    // work-order §0.4 조건 2 — 폴더명을 "out"으로 두면 isSupportedFile()이 전부 제외해 분석 자체가 FAILED가 된다.
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
    // work-order §0.4 조건 1 — 하위호환 마커 없는 주석만 붙인다.
    when(claudeService.analyzeCodeWithClaude(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> STUB_COMMENT_PREFIX + "\n" + invocation.getArgument(0));
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

  private void createTwoSourceFiles() throws Exception {
    createSourceFile("com/x/A.java");
    createSourceFile("com/x/B.java");
  }

  /**
   * 분석 본체({@code runAnalysis()}, private)를 리플렉션으로 직접 호출해 실제 파일과 추적 파일을 만든다.
   * 프로덕션에서는 별도 스레드로 기동되지만 메서드 자체는 동기적으로 끝난다.
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

  /** 실측값을 그대로 붙여넣을 수 있도록 응답 전체를 한 줄로 요약한다(work-order §0.6). */
  @SuppressWarnings("unchecked")
  private String summarize(String caseName, Map<String, Object> result) {
    if (result.get("error") != null) {
      return "[" + caseName + "] error=" + result.get("error");
    }
    StringBuilder sb = new StringBuilder("[").append(caseName).append("] ")
        .append("totalCount=").append(result.get("totalCount"))
        .append(", completeCount=").append(result.get("completeCount"))
        .append(", waitCount=").append(result.get("waitCount"))
        .append(", outputPath=").append(result.get("outputPath"))
        .append(", files=");
    List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");
    for (Map<String, Object> file : files) {
      sb.append("{").append(file.get("fileName")).append(" isCompleted=")
          .append(file.get("isCompleted")).append("}");
    }
    return sb.toString();
  }

  private static boolean isWindows() {
    return File.separatorChar == '\\';
  }

  // ────────────────────────────────────────────────────────────────────────────

  /**
   * <b>C1 — 양성 대조군</b>. copy 모드 + 후행 공백/구분자 혼합 없음.
   * 수정 전에도 GREEN이어야 한다(수정 전 산식과 정답 산식이 이 입력에서만 우연히 일치하기 때문).
   * 이 케이스가 RED면 하네스가 아무 일도 안 하고 있다는 뜻이므로 C2/C3의 0은 무의미하다.
   */
  @Test
  void C1_양성대조군_copy모드_정상입력이면_완료파일을_전부_인식한다() throws Exception {
    createTwoSourceFiles();
    String src = srcRoot.toString();
    String out = outRoot.toString();

    runAnalysisAndAwait(src, out);
    Map<String, Object> result = dashboardStatus(src, out);
    System.out.println(summarize("C1 positive-control copy/no-space", result));

    assertNull(result.get("error"), "C1은 오류 없이 응답해야 한다: " + result.get("error"));
    // 부가 단언 — copy 모드라 추적 파일이 출력 루트에 있고 소스 루트 스캔에 잡히지 않는다(§0.8 판단 3).
    assertEquals(FILE_COUNT, intOf(result, "totalCount"), "스캔 대상 파일 수 자체가 다르면 하네스가 잘못된 것이다");
    assertEquals(FILE_COUNT, intOf(result, "completeCount"),
        "양성 대조군이 실패하면 이 하네스는 아무것도 증명하지 못한다(work-order TASK-001 DoD 3 — 즉시 중단·보고 대상). "
            + summarize("C1", result));
  }

  /**
   * <b>C2 — 결함 재현</b>. 비-copy 모드인데 출력 경로에 후행 공백이 붙은 입력({@code out = src + " "}).
   * {@code runAnalysis()}는 {@code outputPath.trim()}으로 비교해 <b>비-copy</b>로 판정하고 원본을 직접 수정하지만,
   * 수정 전 {@code getDashboardStatus()}는 trim 없이 비교해 <b>copy 모드로 오판</b>하고
   * 존재하지 않는 {@code {src }/{username}/{srcName}} 아래를 찾는다 → 완료 0건.
   */
  @Test
  void C2_비copy모드_출력경로_후행공백에서도_완료파일을_인식한다() throws Exception {
    createTwoSourceFiles();
    String src = srcRoot.toString();
    String out = src + " ";

    runAnalysisAndAwait(src, out);
    Map<String, Object> result = dashboardStatus(src, out);
    System.out.println(summarize("C2 non-copy/trailing-space", result));

    assertNull(result.get("error"), "C2는 오류 없이 응답해야 한다: " + result.get("error"));
    // 부가 단언 — 비-copy 모드라 추적 파일(.ai-analysis-done.txt)이 소스 루트 스캔에 1건 섞인다.
    // 왜 +1인지는 NON_COPY_SCAN_COUNT 주석 참고(별건 결함, 이번 사이클에서는 고치지 않고 고정만 한다).
    assertEquals(NON_COPY_SCAN_COUNT, intOf(result, "totalCount"));
    assertEquals(FILE_COUNT, intOf(result, "completeCount"),
        "runAnalysis()는 trim 후 비교해 원본을 직접 수정했는데 스캔 API가 copy 모드로 오판하면 완료가 0건이 된다. "
            + summarize("C2", result));
  }

  /**
   * <b>C3 — 결함 재현</b>. copy 모드 + 출력 경로 후행 공백({@code out = outroot + " "}).
   * {@code runAnalysis()}는 {@code {outroot}/{username}/{srcName}}에 쓰지만,
   * 수정 전 {@code getDashboardStatus()}는 trim 없이 {@code {outroot }/{username}/{srcName}}을 본다 → 완료 0건.
   */
  @Test
  void C3_copy모드_출력경로_후행공백에서도_완료파일을_인식한다() throws Exception {
    createTwoSourceFiles();
    String src = srcRoot.toString();
    String out = outRoot.toString() + " ";

    runAnalysisAndAwait(src, out);
    Map<String, Object> result = dashboardStatus(src, out);
    System.out.println(summarize("C3 copy/trailing-space", result));

    assertNull(result.get("error"), "C3은 오류 없이 응답해야 한다: " + result.get("error"));
    // 부가 단언 — copy 모드라 추적 파일이 출력 루트에 있고 소스 루트 스캔에 잡히지 않는다(§0.8 판단 3).
    assertEquals(FILE_COUNT, intOf(result, "totalCount"));
    assertEquals(FILE_COUNT, intOf(result, "completeCount"),
        "추적 파일 경로에 후행 공백이 남으면 절대경로 문자열 비교가 한 건도 매칭되지 않는다. "
            + summarize("C3", result));
  }

  /**
   * <b>C4 — 플랫폼 조건부</b>. 사용자가 두 입력 필드에 구분자를 섞어 넣은 경우
   * (원본은 {@code \} 표기, 출력은 같은 경로의 {@code /} 표기).
   * {@code startAnalysis()}는 두 값 모두 {@code \}→{@code /}로 정규화한 뒤 {@code runAnalysis()}에 넘기므로
   * 실제 분석은 <b>비-copy</b>로 돌지만, 수정 전 {@code getDashboardStatus()}는 raw 문자열을 그대로 비교해
   * <b>copy 모드로 오판</b>한다.
   *
   * <p>Linux에서는 backslash가 경로 구분자가 아니라 파일명 문자라 이 어긋남이 성립하지 않는다.
   * work-order TASK-001 DoD 6에 따라 <b>재현되지 않는 것은 Fail 사유가 아니며 실측 결과만 기록</b>한다.
   */
  @Test
  void C4_구분자_혼합입력에서도_완료파일을_인식한다_윈도우_조건부() throws Exception {
    createTwoSourceFiles();
    // startAnalysis()가 세션/분석 본체에 넘기는 형태: 두 값 모두 "/"로 정규화됨 → 비-copy
    String normalized = srcRoot.toString().replace("\\", "/");
    runAnalysisAndAwait(normalized, normalized);

    // 스캔 API에는 사용자가 입력한 raw 값이 그대로 온다.
    String rawFolder = srcRoot.toString();
    String rawOutput = normalized;
    Map<String, Object> result = dashboardStatus(rawFolder, rawOutput);
    System.out.println(summarize("C4 mixed-separator windows=" + isWindows()
        + " folderPath=" + rawFolder + " outputPath=" + rawOutput, result));

    if (!isWindows()) {
      // 이 플랫폼에서는 두 표기가 애초에 같은 문자열이라 어긋남 자체가 성립하지 않는다(재현 불가).
      System.out.println("[C4] 재현 불가 — 이 플랫폼에서는 '\\' 표기가 존재하지 않아 두 입력이 동일 문자열이다.");
    }
    assertNull(result.get("error"), "C4는 오류 없이 응답해야 한다: " + result.get("error"));
    // 부가 단언 — 실제 분석은 비-copy로 돌았으므로 추적 파일이 소스 루트 스캔에 1건 섞인다.
    // 왜 +1인지는 NON_COPY_SCAN_COUNT 주석 참고. 이 단언이 먼저 터지면 1차 판정 기준인
    // completeCount가 가려지므로, §0.8 판단 3에 따라 실측값(N+1)으로 고정한다.
    assertEquals(NON_COPY_SCAN_COUNT, intOf(result, "totalCount"));
    assertEquals(FILE_COUNT, intOf(result, "completeCount"),
        "요청 파라미터 구분자를 startAnalysis()와 같은 규칙으로 정규화하지 않으면 copy 모드 판정이 서로 반대가 된다. "
            + summarize("C4", result));
  }

  /**
   * <b>C5 — 동치성 회귀망</b>(work-order TASK-003 작업내용 3).
   * 응답의 {@code outputPath} 필드가 공용 헬퍼 {@code resolveUserOutputRoot()}의 반환값과
   * <b>문자열로 정확히 동일</b>한지 못박는다. 이 메서드에 "다섯 번째 인라인 사본"이 다시 생기면
   * 계산 결과가 헬퍼와 어긋나므로 이 단언이 RED로 알려준다.
   *
   * <p><b>TASK-005와 층이 다르다</b>: TASK-005는 소스 텍스트를 읽어 산식 복제를 잡는 <b>정적</b> 회귀망이고,
   * 이 케이스는 실제로 메서드를 호출해 값을 대조하는 <b>런타임</b> 회귀망이다. 텍스트 검사를 우회하는
   * 형태(변수명·정규식 표기를 바꾼 사본)로 복제해도 이쪽에서 잡힌다.
   *
   * <p><b>왜 헬퍼에 정규화된 입력을 넘기는가</b>: 요청 파라미터의 {@code \}→{@code /} 정규화는
   * 이 엔드포인트가 지켜야 할 계약의 <b>일부</b>다(게이트1 확정 ③ / TASK-002 작업내용 1 —
   * 헬퍼가 copy 모드를 <b>문자열 비교</b>로 판정하므로 정규화 없이는 위임 자체가 성립하지 않는다).
   * 따라서 고정해야 할 계약은 "정규화 → 헬퍼 위임" 파이프라인 전체이며,
   * 기대값도 {@code startAnalysis()}와 같은 규칙으로 정규화한 입력을 헬퍼에 넘겨 만든다.
   *
   * <p><b>단언의 탐지력 확인</b>(STRUCTURE.md 20절): 이 케이스의 근거는 "두 문자열이 같다"는 것이라,
   * 양쪽이 모두 {@code null}이거나 우연히 상수로 같아도 통과한다. 그래서 copy 모드 입력에서
   * <b>계정명만 바꾼 헬퍼 호출과는 반드시 달라야 한다</b>는 반대 방향 단언을 함께 둔다 —
   * 비교 대상 문자열이 실제로 산식의 입력에 반응한다는 증거다.
   *
   * <p>수행 시간을 줄이기 위해 {@code runAnalysis()}는 부르지 않는다. 이 케이스가 보는 것은
   * 완료 판정 결과가 아니라 <b>경로 산식의 출처</b>이며, 그 값은 분석 수행 여부와 무관하게 결정된다.
   */
  @Test
  void C5_응답의_outputPath는_공용헬퍼_반환값과_문자열로_동일하다() throws Exception {
    createTwoSourceFiles();
    String src = srcRoot.toString();

    // C1~C3와 같은 입력 형태 3가지: (설명, folderPath, outputPath)
    String[][] inputs = {
        {"C1-copy/no-space", src, outRoot.toString()},
        {"C2-non-copy/trailing-space", src, src + " "},
        {"C3-copy/trailing-space", src, outRoot.toString() + " "},
    };

    for (String[] input : inputs) {
      String label = input[0];
      String folderPath = input[1];
      String outputPath = input[2];

      Map<String, Object> result = dashboardStatus(folderPath, outputPath);
      assertNull(result.get("error"), "C5(" + label + ")는 오류 없이 응답해야 한다: " + result.get("error"));

      String expected = resolveUserOutputRootByReflection(
          normalizeSeparators(folderPath), normalizeSeparators(outputPath), USERNAME);

      System.out.println("[C5 " + label + "] responseOutputPath=" + result.get("outputPath")
          + " / helper=" + expected);
      System.out.println("[C5 " + label + "] consoleLog=" + result.get("consoleLog"));

      assertEquals(expected, result.get("outputPath"),
          "응답 outputPath가 공용 헬퍼 반환값과 다르면 이 메서드가 산식 사본을 다시 갖게 된 것이다("
              + label + "). 새 호출부는 resolveUserOutputRoot()를 부르면 된다.");
    }

    // 탐지력 확인 — copy 모드에서는 계정 세그먼트가 경로에 들어가므로 계정명이 다르면 값도 달라야 한다.
    Map<String, Object> copyResult = dashboardStatus(src, outRoot.toString());
    String otherUserRoot = resolveUserOutputRootByReflection(
        normalizeSeparators(src), normalizeSeparators(outRoot.toString()), "intruder");
    System.out.println("[C5 detection-check] otherUserRoot=" + otherUserRoot);
    assertNotEquals(otherUserRoot, copyResult.get("outputPath"),
        "계정명을 바꿔도 같은 값이 나온다면 이 동치성 단언은 아무것도 검증하지 못한다(공허한 통과).");
  }

  /** {@code resolveUserOutputRoot()}는 private static이라 리플렉션으로 호출한다. */
  private String resolveUserOutputRootByReflection(String sourcePath, String outputPath, String username)
      throws Exception {
    Method m = MainApiController.class.getDeclaredMethod(
        "resolveUserOutputRoot", String.class, String.class, String.class);
    m.setAccessible(true);
    return (String) m.invoke(null, sourcePath, outputPath, username);
  }

  /** {@code startAnalysis()} / {@code getDashboardStatus()}가 요청 파라미터에 적용하는 정규화 규칙과 동일. */
  private static String normalizeSeparators(String path) {
    return path.replace("\\", "/");
  }
}
