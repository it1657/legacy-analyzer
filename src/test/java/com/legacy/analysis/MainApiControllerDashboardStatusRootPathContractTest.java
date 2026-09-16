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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * TASK-005 (work-order 2026-09-remaining-ux-fixes <b>v6 블록이 정본</b>, REQ-008 / 게이트1 ⑩ "조기 거부") —
 * <b>{@code POST /api/dashboard-status}가 파일시스템 루트 경로를 전용 문구로 조기 거부</b>하는 계약.
 *
 * <p><b>출발점</b>: 직전 사이클 R6({@link MainApiControllerDashboardStatusSourcePathContractTest})는
 * 루트 + copy 모드에서 {@code resolveProjectOutputRoot()}의 {@code getFileName()}이 {@code null}이라 나는 NPE를
 * "try 확장으로 500만 면한" 상태까지 고정했다(응답 {@code error}에 JVM NPE 원문이 실린다). 이 클래스는 그 다음
 * 단계 — 루트 가드가 {@code Path.of(folderPathStr).getFileName() == null}로 루트를 인식해 <b>전용 문구</b>로
 * 거부하고, {@code resolve*()} 위임과 {@code Files.walk()} 어느 쪽에도 도달하지 않는다 — 를 단언한다.
 *
 * <p><b>가드의 위치(v6 §0.26, 대안 (c))</b>: 루트 가드는 진입 가드 블록(try 밖)이 아니라 <b>기존
 * {@code catch (Exception e)}가 달린 바깥 {@code try}의 첫 문장</b>이다 — 기존 {@code Path folderPath = Path.of(folderPathStr);}
 * 선언을 그 자리로 올리고 바로 이어서 {@code getFileName() == null} 판정으로 거부한다. 직전 사이클 게이트1 ⑧(a)의
 * 정적 계약({@link MainApiControllerDashboardStatusTryScopeSingleSourceTest} — 요청 파라미터 파싱 토큰의 <b>첫 출현이
 * try 안</b>)과 이번 판정식·순서 요건을 동시에 글자 그대로 만족하는 유일한 배치다. 검사 대상은 구분자 정규화
 * ({@code replace("\\","/")})를 거친 {@code folderPathStr}이며 이는 하류 {@code resolve*()}·{@code Files.walk()}가
 * 실제로 파싱하는 값과 같다(검사 대상 = 파싱 대상). trim은 하지 않는다.
 *
 * <p><b>단언은 신설 문구에만 건다</b>(DoD 2). NPE 메시지 원문은 JVM 구현마다 다를 수 있어 하드코딩하지 않는다.
 * 수정 전 시점의 RED 출력에 실제 {@code error} 값(NPE 원문)이 그대로 찍히므로 그 출력이 P12의 근거가 된다.
 *
 * <p><b>안전장치(DoD 3 — 필수)</b>: 루트 + <b>비-copy</b> 조합은 수정 전 코드에서 진입 가드를 전부 통과해
 * {@code Files.walk(루트)}로 <b>드라이브 전체를 순회</b>한다. 그래서
 * <ul>
 *   <li>copy 모드 케이스(P12)는 컨트롤러 호출 <b>전에</b> (1) 루트임 (2) copy 모드 성립을 단언한다 — 두 전제가 성립하면
 *       수정 전에는 {@code getFileName()} NPE가 {@code Files.walk} 앞에서 확정적으로 나므로 순회하지 않는다.</li>
 *   <li>비-copy 케이스(P11 실행분)는 <b>가드가 실제로 반영돼 있음을 먼저 실행으로 확인한 뒤</b>(copy 모드 루트 요청이
 *       신설 문구를 돌려주는지) 실행한다. 가드가 없으면 {@code fail()}로 끝내고 비-copy 요청은 보내지 않는다 —
 *       이 클래스를 수정 전 코드에서 돌려도 드라이브를 순회하지 않게 하기 위한 장치다.</li>
 * </ul>
 *
 * <p>하네스 골격(실제 {@code @TempDir}, 협력자 mock/null, {@code @Value} 필드 리플렉션 주입, 관리자 인증 픽스처)은
 * 직전 사이클 계약 테스트를 그대로 따른다. 이 클래스는 {@code runAnalysis()}를 돌리지 않는다 — 루트 거부는 분석
 * 결과와 무관하게 진입부에서 끝나야 하기 때문이다.
 */
class MainApiControllerDashboardStatusRootPathContractTest {

  private static final String USERNAME = "jhjung";

  /** work-order TASK-005 작업 2가 지정한 전용 문구. 프로덕션 상수와 <b>문자 단위</b>로 같아야 한다. */
  private static final String EXPECTED_ROOT_PATH_ERROR =
      "최상위 경로(드라이브 루트)는 분석 대상으로 지정할 수 없습니다. 하위 프로젝트 폴더를 지정해 주세요.";
  private static final String EXISTING_SOURCE_PATH_ERROR = "올바르지 않은 원본 디렉터리 경로입니다.";
  private static final String EXISTING_OUTPUT_PATH_ERROR = "올바르지 않은 출력 디렉터리 경로입니다.";

  @TempDir
  Path tempDir;

  private MainApiController controller;
  private Path srcRoot;
  private Path outRoot;

  @BeforeEach
  void setUp() throws Exception {
    srcRoot = tempDir.resolve("myproj");
    outRoot = tempDir.resolve("outroot");
    Files.createDirectories(srcRoot);
    Files.createDirectories(outRoot);
    Files.createDirectories(srcRoot.resolve("com/x"));
    Files.writeString(srcRoot.resolve("com/x/A.java"), "public class A {}\n", StandardCharsets.UTF_8);

    SessionConfig sessionConfig = new SessionConfig();
    sessionConfig.setMaxRetries(0);
    RetryHandler retryHandler = new RetryHandler(new ApiErrorHandler(), sessionConfig);
    controller = new MainApiController(
        mock(ClaudeService.class), null, mock(AnalysisSessionManager.class), null, null, retryHandler,
        mock(AnalysisHistoryRepository.class), null, null, null, null, null,
        mock(CodeContentRagService.class), mock(LlmModelOptionService.class), null);
    setField("uploadStoragePath", tempDir.resolve(".uploads").toString());
  }

  private void setField(String name, Object value) throws Exception {
    Field field = MainApiController.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(controller, value);
  }

  private Authentication adminAuth() {
    User user = new User(USERNAME, USERNAME + "@example.com", "hash");
    user.setSeq(99L);
    user.setRoles(Set.of(new Role("ADMIN", "관리자 역할")));
    return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
  }

  private Map<String, Object> dashboardStatus(String folderPath, String outputPath) {
    Map<String, String> request = new HashMap<>();
    request.put("folderPath", folderPath);
    request.put("outputPath", outputPath);
    return controller.getDashboardStatus(request, adminAuth());
  }

  /** 예외까지 포함해 붙여넣을 수 있는 관측값. */
  private record Observation(Throwable thrown, Map<String, Object> result, String text) {}

  private Observation observe(String caseName, String folderPath, String outputPath) {
    String header = "[" + caseName + "] folderPath=[" + folderPath + "] outputPath=[" + outputPath + "]\n";
    try {
      Map<String, Object> result = dashboardStatus(folderPath, outputPath);
      String text = header + "  -> 예외 미발생. 응답 원문: " + (result.get("error") != null
          ? "error=" + result.get("error")
          : "totalCount=" + result.get("totalCount") + ", completeCount=" + result.get("completeCount")
              + ", waitCount=" + result.get("waitCount") + ", outputPath=" + result.get("outputPath"));
      return new Observation(null, result, text);
    } catch (Throwable t) {
      StringWriter sw = new StringWriter();
      t.printStackTrace(new PrintWriter(sw));
      return new Observation(t, null, header + "  -> 예외 발생: " + t.getClass().getName() + ": " + t.getMessage()
          + "\n--- 스택트레이스 원문 ---\n" + sw);
    }
  }

  /** 이 플랫폼의 파일시스템 루트(Windows: 드라이브 루트 {@code C:/}, 그 외: {@code /}). 구분자는 컨트롤러 정규화와 같게 {@code /}. */
  private String filesystemRoot() {
    return tempDir.toAbsolutePath().getRoot().toString().replace("\\", "/");
  }

  private static String slash(Path p) {
    return p.toString().replace("\\", "/");
  }

  private static void printEnvironment(String caseName) {
    System.out.println("[" + caseName + "] 실행 환경: os.name=" + System.getProperty("os.name")
        + ", os.version=" + System.getProperty("os.version") + ", java.version=" + System.getProperty("java.version")
        + ", java.vendor=" + System.getProperty("java.vendor") + ", file.separator=" + File.separator);
  }

  private static String productionRootMessage() throws Exception {
    Field f = MainApiController.class.getDeclaredField("ROOT_SOURCE_PATH_ERROR");
    f.setAccessible(true);
    return (String) f.get(null);
  }

  // ────────────────────────────────────────────────────────────────────────────

  /**
   * <b>P12 — 루트 + copy 모드(DoD 1)</b>. 수정 전에는 {@code resolveProjectOutputRoot()}의 {@code getFileName()} NPE가
   * 확장된 try에 잡혀 {@code error}에 JVM NPE 원문이 실렸다(직전 사이클 R6가 고정한 현 상태). 수정 후에는 루트 가드가
   * 신설 문구로 거부한다. <b>같은 케이스의 두 시점 실행</b>으로 RED → GREEN을 보인다.
   */
  @Test
  void P12_루트경로_copy모드는_NPE원문이_아니라_전용_문구로_거부된다() {
    printEnvironment("P12");
    String root = filesystemRoot();
    String out = slash(outRoot);

    // ── 안전장치(DoD 3): 컨트롤러 호출 전에 단언 ──────────────────────────────
    assertNull(Path.of(root).getFileName(), "전제 1 미성립: [" + root + "]가 루트가 아니다 — 케이스를 실행하지 않는다");
    assertTrue(!out.isBlank() && !out.trim().equals(root),
        "전제 2 미성립: copy 모드가 아니면 수정 전 코드는 Files.walk(루트)까지 간다 — 케이스를 실행하지 않는다");
    System.out.println("[P12] 전제 확인: getFileName()=null, copy 모드 성립. folderPath=[" + root + "] outputPath=[" + out + "]");

    Observation obs = observe("P12 root+copy", root, out);
    System.out.println(obs.text());

    assertNull(obs.thrown(), "P12: 루트 경로로 컨트롤러 밖에 예외가 나가면 안 된다(직전 사이클 ⑧(a) 회귀).\n" + obs.text());
    assertNotNull(obs.result());
    assertEquals(EXPECTED_ROOT_PATH_ERROR, obs.result().get("error"),
        "P12: 루트 경로는 루트 가드가 전용 문구로 거부해야 한다 — NPE 원문이 실리면 가드가 없는 것이다.\n" + obs.text());
    assertNull(obs.result().get("files"), "P12: 오류 응답이므로 files가 있으면 안 된다.\n" + obs.text());
    assertNull(obs.result().get("outputPath"), "P12: resolve*() 위임에 도달하지 않았으므로 outputPath가 있으면 안 된다.\n" + obs.text());
  }

  /**
   * <b>P11 — 루트 + 비-copy 모드(DoD 4)</b>. 수정 전에는 진입 가드를 전부 통과해 {@code Files.walk(루트)}에 도달한다
   * (코드 경로 추적: File 가드 통과 → 파싱 가드 통과 → outputPath 기본값=folderPath → 비-copy → try 안
   * {@code Files.walk}). 수정 후에는 try 첫 문장의 <b>루트 가드</b>가 {@code resolve*()}·{@code Files.walk()}보다 먼저
   * 끊는다. 그래서 <b>가드 반영이 실행으로 확인된 뒤에만</b> 이 조합을 보낸다.
   */
  @Test
  void P11_루트경로_비copy모드는_가드가_Files_walk보다_먼저_끊는다_가드_확인_후에만_실행() {
    printEnvironment("P11");
    String root = filesystemRoot();
    assertNull(Path.of(root).getFileName(), "전제 미성립: [" + root + "]가 루트가 아니다");

    // ── 안전장치(DoD 3): 가드가 실제로 반영돼 있는지 copy 모드 요청으로 먼저 확인한다 ──
    Observation probe = observe("P11 probe(root+copy)", root, slash(outRoot));
    if (probe.thrown() != null || !EXPECTED_ROOT_PATH_ERROR.equals(probe.result().get("error"))) {
      fail("P11: 루트 가드가 반영되지 않은 코드다 — 비-copy + 루트 조합은 Files.walk(루트)로 드라이브 전체를 순회하므로 "
          + "실행하지 않는다(수정 전 근거는 P12(copy 모드)로만 확보한다).\n" + probe.text());
    }

    long started = System.nanoTime();
    Observation obs = observe("P11 root+non-copy", root, "");
    long elapsedMs = (System.nanoTime() - started) / 1_000_000;
    System.out.println(obs.text() + "\n[P11] 소요 " + elapsedMs + "ms");

    assertNull(obs.thrown(), obs.text());
    assertEquals(EXPECTED_ROOT_PATH_ERROR, obs.result().get("error"), "P11: 비-copy 루트도 같은 전용 문구로 거부돼야 한다.\n" + obs.text());
    assertNull(obs.result().get("files"), "P11: files가 있으면 Files.walk(루트)를 실제로 돌린 것이다.\n" + obs.text());
    assertNull(obs.result().get("totalCount"), "P11: totalCount가 있으면 Files.walk(루트)를 실제로 돌린 것이다.\n" + obs.text());
  }

  /**
   * <b>DoD 5 — 정상 경로 무영향(양성 대조군)</b>: 루트가 아닌 실존 디렉터리는 이 가드에 걸리지 않고 스캔 결과를 돌려준다.
   * 후행 공백·구분자 혼합(C2/C3)은 기존 {@link MainApiControllerDashboardStatusPathContractTest}가 계속 덮는다.
   */
  @Test
  void DoD5_루트가_아닌_정상_디렉터리는_가드에_걸리지_않는다() {
    String src = slash(srcRoot);
    assertNotNull(Path.of(src).getFileName(), "대조군 전제: 정상 경로는 getFileName()이 null이 아니다");

    Observation nonCopy = observe("DoD5 non-copy", src, "");
    Observation copy = observe("DoD5 copy", src, slash(outRoot));
    System.out.println(nonCopy.text() + "\n" + copy.text());

    for (Observation o : new Observation[] {nonCopy, copy}) {
      assertNull(o.thrown(), o.text());
      assertNull(o.result().get("error"), "정상 경로가 거부됐다.\n" + o.text());
      assertEquals(1, o.result().get("totalCount"), "정상 경로는 스캔 결과(totalCount)를 돌려줘야 한다.\n" + o.text());
    }
  }

  /**
   * <b>DoD 10 — 백슬래시 루트 1건 실행 단언(v6 신설)</b>: {@code folderPath = "C:\\"}(Windows 드라이브 루트를 백슬래시로) +
   * 파싱 가능한 별도 출력 경로(copy 모드)가 {@code ROOT_SOURCE_PATH_ERROR}로 거부된다. 목적: (c) 구조에서 가드가
   * 구분자 정규화({@code replace("\\","/")}) <b>뒤</b>에 놓이므로, 정규화가 루트 판정을 바꾸지 않는다는 것을 PL의
   * 추론이 아니라 실행으로 고정한다(§0.26.4). 백슬래시 구분자는 Windows 기본 파일시스템에서만 루트 표기이므로
   * OS 의존 — 비-Windows에서는 {@code assumeTrue}로 건너뛴다(TASK-003·004와 같은 방식, 한계는 dev-progress에 기록).
   */
  @Test
  void DoD10_백슬래시_드라이브루트_copy모드도_정규화_뒤_가드가_전용_문구로_거부한다() {
    printEnvironment("DoD10");
    assumeTrue(System.getProperty("os.name", "").toLowerCase().startsWith("windows"),
        "백슬래시 드라이브 루트(C:\\)는 Windows 전용 표기 — 이 OS에서는 케이스를 실행하지 않는다");
    String rootBackslash = tempDir.toAbsolutePath().getRoot().toString(); // 예: C:\
    assumeTrue(rootBackslash.endsWith("\\") && rootBackslash.length() == 3,
        "드라이브 루트가 'X:\\' 형태가 아니다(예: UNC) — 케이스를 실행하지 않는다: [" + rootBackslash + "]");
    String out = slash(outRoot);

    // ── 안전장치(DoD 3): 컨트롤러 호출 전에 단언 ──────────────────────────────
    assertTrue(rootBackslash.contains("\\"), "전제 0 미성립: 백슬래시 표기가 아니다: [" + rootBackslash + "]");
    assertNull(Path.of(rootBackslash).getFileName(), "전제 1 미성립: [" + rootBackslash + "]가 루트가 아니다");
    assertNull(Path.of(rootBackslash.replace("\\", "/")).getFileName(),
        "전제 1' 미성립: 정규화본 [" + rootBackslash.replace("\\", "/") + "]가 루트가 아니다");
    assertTrue(!out.isBlank() && !out.trim().equals(rootBackslash) && !out.trim().equals(rootBackslash.replace("\\", "/")),
        "전제 2 미성립: copy 모드가 아니면 수정 전 코드는 Files.walk(루트)까지 간다 — 케이스를 실행하지 않는다");
    System.out.println("[DoD10] 전제 확인: 백슬래시 루트 [" + rootBackslash + "] getFileName()=null(원문·정규화본 모두), copy 모드 성립. outputPath=[" + out + "]");

    Observation obs = observe("DoD10 backslash-root+copy", rootBackslash, out);
    System.out.println(obs.text());

    assertNull(obs.thrown(), "DoD10: 백슬래시 루트로 컨트롤러 밖에 예외가 나가면 안 된다.\n" + obs.text());
    assertNotNull(obs.result());
    assertEquals(EXPECTED_ROOT_PATH_ERROR, obs.result().get("error"),
        "DoD10: 백슬래시 루트도 구분자 정규화 뒤 가드가 전용 문구로 거부해야 한다.\n" + obs.text());
    assertNull(obs.result().get("files"), "DoD10: 오류 응답이므로 files가 있으면 안 된다.\n" + obs.text());
    assertNull(obs.result().get("outputPath"), "DoD10: resolve*() 위임에 도달하지 않았으므로 outputPath가 있으면 안 된다.\n" + obs.text());
  }

  /**
   * <b>작업 2 — 전용 문구 상수화 + 문자 단위 대조</b>: 프로덕션 상수 {@code ROOT_SOURCE_PATH_ERROR}가 work-order 문구와
   * 문자 단위로 같고, 기존 두 문구와 겹치지 않으며, {@code getDashboardStatus()} 안에서 정확히 한 번 쓰인다.
   *
   * <p><b>작업 3 / DoD 9 — 정적 순서 단언 4개(v6, (c) 구조)</b>. <b>주석을 제거한 코드 텍스트만</b> 대조한다
   * (주석 속 토큰 오탐은 직전 사이클이 실제로 겪은 형태 — {@code …TryScopeSingleSourceTest.sanitizeForBraceCounting()}과
   * 같은 방식으로 행·블록 주석과 문자열 리터럴을 지운 뒤 행 단위로 찾는다).
   * <ol>
   *   <li>(i) {@code isParsablePath(folderPathStr)} 행 &lt; 루트 가드 행</li>
   *   <li>(ii) <b>바깥 {@code try} 행 &lt; 루트 가드 행</b> — 그리고 try와 {@code Path.of(folderPathStr)} 선언 사이에
   *       코드 행이 없다(= try의 <b>첫 문장</b>)</li>
   *   <li>(iii) 루트 가드 행 &lt; {@code resolveUserOutputRoot(} 행</li>
   *   <li>(iv) 루트 가드 행 &lt; {@code Files.walk(} 행</li>
   * </ol>
   * 아울러 {@code Path.of(folderPathStr)} 토큰의 메서드 안 <b>첫 출현이 try 안</b>임(⑧(a) 계약과 같은 판정),
   * 가드 줄·선언 줄에 {@code trim(}·{@code normalize(}가 없음(작업 4), 사용 지점 1개소를 유지 단언한다.
   */
  @Test
  void 작업2_3_DoD9_전용문구_상수와_루트가드의_정적_순서_파싱검사_뒤_try안_첫문장_resolve_walk_앞() throws Exception {
    String production = productionRootMessage();
    assertEquals(EXPECTED_ROOT_PATH_ERROR, production, "프로덕션 상수가 work-order 문구와 문자 단위로 다르다");
    assertNotEquals(EXISTING_SOURCE_PATH_ERROR, production);
    assertNotEquals(EXISTING_OUTPUT_PATH_ERROR, production);
    assertFalse(production.contains(EXISTING_SOURCE_PATH_ERROR) || EXISTING_SOURCE_PATH_ERROR.contains(production),
        "기존 원본 경로 문구와 문자열이 겹친다");
    assertFalse(production.contains(EXISTING_OUTPUT_PATH_ERROR) || EXISTING_OUTPUT_PATH_ERROR.contains(production),
        "기존 출력 경로 문구와 문자열이 겹친다");

    String source = Files.readString(locate("src/main/java/com/legacy/analysis/MainApiController.java"), StandardCharsets.UTF_8);
    String[] raw = source.split("\n", -1);
    String[] code = stripCommentsAndLiterals(raw);

    // 메서드 범위: 선언 행 ~ 다음 @PostMapping 직전
    int methodStart = firstLine(code, 0, code.length - 1, "getDashboardStatus(");
    assertTrue(methodStart >= 0, "getDashboardStatus() 선언을 찾지 못했다");
    int methodEnd = firstLine(code, methodStart + 1, code.length - 1, "@PostMapping(");
    assertTrue(methodEnd > methodStart, "getDashboardStatus() 다음 메서드 경계를 찾지 못했다");

    int parseGuard = firstLine(code, methodStart, methodEnd, "isParsablePath(folderPathStr)");
    int outerTry = firstLineMatching(code, methodStart, methodEnd, "^\\s*try\\s*\\{\\s*$");
    int pathOfDecl = firstLine(code, methodStart, methodEnd, "Path.of(folderPathStr)");
    int rootGuard = firstLine(code, methodStart, methodEnd, "folderPath.getFileName() == null");
    int rootUse = firstLine(code, methodStart, methodEnd, "ROOT_SOURCE_PATH_ERROR);");
    int resolveCall = firstLine(code, methodStart, methodEnd, "resolveUserOutputRoot(");
    int walk = firstLine(code, methodStart, methodEnd, "Files.walk(");
    String located = "parse=" + (parseGuard + 1) + " try=" + (outerTry + 1) + " Path.of=" + (pathOfDecl + 1)
        + " guard=" + (rootGuard + 1) + " use=" + (rootUse + 1) + " resolve=" + (resolveCall + 1) + " walk=" + (walk + 1)
        + " (1-based 행, 주석 제거본 기준)";
    System.out.println("[DoD9 static-order] " + located);
    assertTrue(parseGuard >= 0 && outerTry >= 0 && pathOfDecl >= 0 && rootGuard >= 0 && rootUse >= 0
            && resolveCall >= 0 && walk >= 0,
        "getDashboardStatus() 안에서 기준 지점을 찾지 못했다: " + located);

    // (i) 파싱 검사 뒤
    assertTrue(parseGuard < rootGuard, "(i) 위반: 루트 가드가 isParsablePath(folderPathStr) 검사보다 앞이다. " + located);
    // (ii) 바깥 try 안, 그리고 try의 첫 문장 (try 행과 선언 행 사이에 코드 행 없음)
    assertTrue(outerTry < pathOfDecl && pathOfDecl < rootGuard,
        "(ii) 위반: Path.of(folderPathStr) 선언·루트 가드가 바깥 try 안이 아니다. " + located);
    for (int i = outerTry + 1; i < pathOfDecl; i++) {
      assertTrue(code[i].isBlank(), "(ii) 위반: try와 Path.of(folderPathStr) 선언 사이에 코드 행이 있다 — 첫 문장이 아니다: "
          + (i + 1) + "행 [" + code[i] + "] " + located);
    }
    // (iii)·(iv) resolve*()·Files.walk() 앞
    assertTrue(rootGuard < rootUse && rootUse < resolveCall, "(iii) 위반: 루트 가드가 resolveUserOutputRoot( 위임보다 뒤다. " + located);
    assertTrue(rootGuard < walk, "(iv) 위반: 루트 가드가 Files.walk( 보다 뒤다. " + located);
    // ⑧(a) 동형 판정: Path.of(folderPathStr) 토큰의 첫 출현이 try 안(코드 텍스트 기준)
    assertTrue(pathOfDecl > outerTry, "⑧(a) 위반: Path.of(folderPathStr) 첫 출현이 try 밖이다. " + located);
    // 작업 4: 선언 줄·가드 줄에 trim/normalize/재대입 없음
    for (int i : new int[] {pathOfDecl, rootGuard}) {
      assertFalse(code[i].contains(".trim("), "루트 가드가 trim된 값을 검사한다(작업 4 위반): " + (i + 1) + "행 " + raw[i]);
      assertFalse(code[i].contains(".normalize("), "루트 가드가 normalize된 값을 검사한다(작업 4 위반): " + (i + 1) + "행 " + raw[i]);
      assertFalse(code[i].contains("folderPathStr ="), "루트 가드 줄에서 folderPathStr을 재대입한다(작업 4 위반): " + (i + 1) + "행 " + raw[i]);
    }
    // 정확히 한 번 쓰인다(코드 텍스트 기준).
    int uses = 0;
    for (int i = 0; i < code.length; i++) {
      if (code[i].contains("ROOT_SOURCE_PATH_ERROR") && code[i].contains("resultData.put(")) uses++;
    }
    assertEquals(1, uses, "ROOT_SOURCE_PATH_ERROR 사용 지점이 1개소가 아니다");
  }

  // ── 정적 대조 보조 (주석·문자열 리터럴 제거 — …TryScopeSingleSourceTest.sanitizeForBraceCounting()과 같은 방식) ──

  private static int firstLine(String[] lines, int from, int to, String token) {
    for (int i = from; i <= to; i++) {
      if (lines[i].contains(token)) return i;
    }
    return -1;
  }

  private static int firstLineMatching(String[] lines, int from, int to, String regex) {
    java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
    for (int i = from; i <= to; i++) {
      if (p.matcher(lines[i]).find()) return i;
    }
    return -1;
  }

  private static String[] stripCommentsAndLiterals(String[] rawLines) {
    String[] out = new String[rawLines.length];
    boolean inBlockComment = false;
    for (int i = 0; i < rawLines.length; i++) {
      StringBuilder sb = new StringBuilder();
      String line = rawLines[i];
      for (int k = 0; k < line.length(); k++) {
        char c = line.charAt(k);
        if (inBlockComment) {
          if (c == '*' && k + 1 < line.length() && line.charAt(k + 1) == '/') {
            inBlockComment = false;
            k++;
          }
          continue;
        }
        if (c == '/' && k + 1 < line.length() && line.charAt(k + 1) == '/') break;
        if (c == '/' && k + 1 < line.length() && line.charAt(k + 1) == '*') {
          inBlockComment = true;
          k++;
          continue;
        }
        if (c == '"' || c == '\'') {
          char quote = c;
          sb.append(quote);
          k++;
          while (k < line.length()) {
            char d = line.charAt(k);
            if (d == '\\') {
              k += 2;
              continue;
            }
            if (d == quote) break;
            k++;
          }
          sb.append(quote);
          continue;
        }
        sb.append(c);
      }
      out[i] = sb.toString();
    }
    return out;
  }

  private static Path locate(String relative) {
    Path cursor = Path.of("").toAbsolutePath();
    while (cursor != null) {
      Path candidate = cursor.resolve(relative);
      if (Files.isRegularFile(candidate)) return candidate;
      cursor = cursor.getParent();
    }
    return fail("소스를 찾지 못했다: " + relative);
  }
}
