package com.legacy.analysis;

import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.auth.Role;
import com.legacy.auth.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TASK-003 (work-order 2026-09-remaining-ux-fixes v1, 설계 02-design-v1 §3 / §13 P6·P7·P8) —
 * <b>{@code startAnalysis()}의 {@code claudeService.setModel(Path.of(sourcePath).toString(), selectedModel)} 지점에서
 * 미처리 {@code InvalidPathException}이 나는지를 "실행 결과"로 재현 시도하는 하네스. 프로덕션 변경 0줄.</b>
 *
 * <p><b>재현 대상</b>: {@code MainApiController.startAnalysis()} 안의 {@code if (!selectedModel.isBlank()) { claudeService.setModel(Path.of(sourcePath)...) }}
 * (work-order가 적은 331행은 TASK-001 반영 전 참고값이고, 착수 시점 HEAD {@code 223a10f}에서는 340행이다 — 메서드명이 정본).
 * 이 호출은 메서드 안 어떤 {@code try}에도 감싸여 있지 않고(메서드 전체에 try 없음), 이 프로젝트에는 {@code @ControllerAdvice}가 없다.
 * REQ-009(TASK-001)로 {@code sourcePath}는 읽기 직후 trim되므로, <b>정규화 이후에도 남는 축</b>만 걸러낸다.
 *
 * <p><b>하네스 형태(§0.8 — 실경로 모델링 범위를 먼저 못박는다)</b>:
 * <ul>
 *   <li>{@code MainApiController}를 "필요한 의존성만 mock, 나머지는 null"로 만들고 <b>public {@code startAnalysis()}를 직접 호출</b>한다.
 *       Spring MVC 디스패치는 <b>모델링하지 않는다</b> — 따라서 "실서비스에서 HTTP 500"은 이 하네스의 직접 관측이 아니라
 *       "예외가 컨트롤러 메서드 밖으로 전파됐다 + 전역 예외 처리기 0건"으로부터의 <b>추론</b>이다(DoD 6).</li>
 *   <li><b>P8 함정 회피</b>: 모델을 비우면 {@code if (!selectedModel.isBlank())} 분기에 <b>들어가지도 못해</b> "예외 없음"이 나온다. 그래서
 *       모든 재현 케이스는 {@code model}을 채우고, {@code llmProvider=local} + {@code findByModelKey → empty}로 Anthropic 권한 가드에
 *       걸리지 않게 한 뒤, <b>R1 양성 대조군</b>에서 {@code setModel()}이 실제로 호출됨(= 그 줄에 도달함)을 먼저 보인다.</li>
 *   <li>{@code createSession}은 표식 예외를 던져 분석 스레드 기동을 막는다(기존 {@code MainApiControllerStartAnalysisInputTrimTest} 패턴).</li>
 * </ul>
 *
 * <p><b>케이스</b>: R1 양성 대조군(정상 경로 + 모델) / R2 P8 대조(모델 비움 + 금지문자 → 분기 미도달) / R3 P6(후행 공백 + 모델 → TASK-001 효과로
 * 미재현) / R4 P7 본 재현(Windows 금지문자 {@code |} + 모델) / R5 outputPath 축 관측(수정 안 함).
 *
 * <p><b>R4는 재현되면 의도된 RED</b>(work-order TASK-003 → TASK-004 DoD 1 "같은 단언 그대로 RED → GREEN"): 단언은 수정 후 기대 동작
 * (예외 없이 기존 문구 {@code "올바르지 않은 원본 소스 경로입니다."}를 error로 돌려준다)에 걸어 두고, 지금 RED가 나는 실패 메시지에
 * 스택트레이스 원문(첫 예외 발생 클래스·메서드·라인)을 실어 그 출력 자체가 재현 근거가 되게 한다.
 *
 * <p><b>플랫폼</b>: 후행 공백·금지문자 축은 Windows 한정 현상이다({@code java.nio.file.Path} 파서). 이 클래스는 실행 OS/JDK를 system-out에 남기고,
 * Windows가 아니면 R3/R4의 예외 단언을 건너뛰지 않고 <b>관측값을 그대로 출력</b>한다(결론은 "이 하네스/플랫폼에서는"까지만).
 */
class MainApiControllerStartAnalysisPathOfReproductionTest {

  private static final String SENTINEL = "SENTINEL: createSession 도달";
  private static final String MODEL = "qwen3:8b";
  private static final String EXISTING_SOURCE_ERROR = "올바르지 않은 원본 소스 경로입니다.";

  @TempDir
  Path tempDir;

  private ClaudeService claudeService;
  private AnalysisSessionManager sessionManager;
  private LlmModelOptionService llmModelOptionService;
  private MainApiController controller;

  @BeforeEach
  void setUp() throws Exception {
    claudeService = mock(ClaudeService.class);
    sessionManager = mock(AnalysisSessionManager.class);
    llmModelOptionService = mock(LlmModelOptionService.class);
    // any(): 모델을 비운 R2에서는 getCurrentModel() mock이 null을 돌려줘 findByModelKey(null)이 호출된다.
    when(llmModelOptionService.findByModelKey(any())).thenReturn(Optional.empty());

    controller = new MainApiController(
        claudeService, null, sessionManager, null, null, null, null, null, null, null, null, null, null,
        llmModelOptionService, null);
    // local 모드 — Anthropic 권한 가드에 걸리지 않게 한다(관심사 아님). 모델을 채워야 setModel 분기에 들어간다(P8).
    setField("llmProvider", "local");
    setField("uploadStoragePath", tempDir.toString());

    when(sessionManager.createSession(anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException(SENTINEL));

    System.out.println("[ENV] os.name=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
        + " / os.arch=" + System.getProperty("os.arch")
        + " / java.version=" + System.getProperty("java.version")
        + " / java.vendor=" + System.getProperty("java.vendor")
        + " / file.separator=" + File.separator);
  }

  private void setField(String name, Object value) throws Exception {
    Field field = MainApiController.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(controller, value);
  }

  private Authentication adminAuth() {
    User user = new User("admin", "admin@example.com", "hash");
    user.setSeq(99L);
    user.setRoles(Set.of(new Role("ADMIN", "관리자 역할")));
    return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
  }

  private Path realSourceDir() throws Exception {
    Path src = tempDir.resolve("src-proj");
    Files.createDirectories(src);
    return src;
  }

  private static String slash(Path p) {
    return p.toString().replace("\\", "/");
  }

  /**
   * 금지문자가 든 경로 문자열은 <b>문자열 결합</b>으로 만든다. {@code tempDir.resolve("src|bad")}처럼 만들면 Windows에서는
   * 하네스 쪽 {@code Path.resolve()}가 먼저 {@code InvalidPathException}을 던져 프로덕션 코드에 도달조차 못 한다
   * (첫 실행에서 실제로 그렇게 깨졌다 — R2/R4/R5 3건, 컨트롤러 프레임 없음). 이 하네스가 관측하려는 것은
   * 프로덕션 {@code Path.of(sourcePath)}이므로, 파싱은 오직 프로덕션 안에서만 일어나야 한다.
   */
  private static String badPathUnder(Path dir, String badName) {
    return slash(dir) + "/" + badName;
  }

  private static boolean isWindows() {
    return File.separatorChar == '\\';
  }

  private static String stackTraceOf(Throwable t) {
    StringWriter sw = new StringWriter();
    t.printStackTrace(new PrintWriter(sw));
    return sw.toString();
  }

  /** 스택트레이스에서 첫 MainApiController 프레임(= 예외가 컨트롤러 안 어느 줄에서 났는지)을 찾는다. */
  private static String firstControllerFrame(Throwable t) {
    for (StackTraceElement e : t.getStackTrace()) {
      if (e.getClassName().equals(MainApiController.class.getName())) return e.toString();
    }
    return "(MainApiController 프레임 없음)";
  }

  private Map<String, String> request(String sourcePath, String outputPath, String model, String sessionId) {
    Map<String, String> request = new HashMap<>();
    request.put("sourcePath", sourcePath);
    request.put("outputPath", outputPath);
    request.put("sessionId", sessionId);
    request.put("model", model);
    return request;
  }

  /** startAnalysis()를 호출하고 (응답 map, 전파된 예외) 중 하나를 돌려준다. SENTINEL은 "createSession 도달"로 정규화. */
  private record Outcome(Map<String, Object> result, Throwable thrown, boolean reachedCreateSession) {
    String summary() {
      if (reachedCreateSession) return "createSession 도달(정상 진행)";
      if (thrown != null) return "예외 전파: " + thrown.getClass().getName() + ": " + thrown.getMessage();
      return "응답 반환: error=" + (result == null ? null : result.get("error"));
    }
  }

  private Outcome call(Map<String, String> request) {
    try {
      Map<String, Object> result = controller.startAnalysis(request, adminAuth());
      return new Outcome(result, null, false);
    } catch (RuntimeException e) {
      if (SENTINEL.equals(e.getMessage())) return new Outcome(null, null, true);
      return new Outcome(null, e, false);
    }
  }

  // ────────────────────────────────────────────────────────────────────────────

  /**
   * <b>R1 — 양성 대조군(하네스 유효성, P8)</b>: 정상 경로 + 모델 지정이면 {@code setModel(Path.of(sourcePath).toString(), model)}에
   * 실제로 도달·호출되고 createSession까지 간다. 이 케이스가 RED면 아래 R3/R4의 "예외 없음/있음"은 아무것도 증명하지 못한다.
   */
  @Test
  void R1_양성대조군_정상경로에_모델을_지정하면_setModel_Path_of_지점에_실제로_도달한다() throws Exception {
    Path src = realSourceDir();
    Outcome o = call(request(slash(src), "", MODEL, "sess-r1"));
    System.out.println("[R1] " + o.summary());

    assertTrue(o.reachedCreateSession(), "정상 입력은 createSession까지 가야 한다: " + o.summary());
    verify(claudeService).setModel(eq(Path.of(slash(src)).toString()), eq(MODEL));
  }

  /**
   * <b>R2 — P8 대조(음성 결과의 함정)</b>: 모델을 비우면 금지문자 경로라도 {@code Path.of()} 분기에 들어가지 않아 예외가 나지 않고,
   * 뒤의 존재 검사가 기존 문구로 조기 반환한다. 즉 모델 없는 하네스의 "예외 없음"은 가드가 막은 것이 아니라 분기 미도달이다.
   */
  @Test
  void R2_P8대조_모델을_비우면_금지문자_경로라도_Path_of_분기에_들어가지_않아_예외가_나지_않는다() {
    String bad = badPathUnder(tempDir, "src|bad");
    Outcome o = call(request(bad, "", "", "sess-r2"));
    System.out.println("[R2] " + o.summary());

    assertNull(o.thrown(), "모델이 비면 Path.of 분기 자체를 타지 않아야 한다: " + o.summary());
    assertNotNull(o.result());
    assertEquals(EXISTING_SOURCE_ERROR, o.result().get("error"), "존재 검사(new File)가 조기 반환해야 한다");
    verify(claudeService, never()).setModel(anyString(), anyString());
  }

  /**
   * <b>R3 — P6(REQ-009 양성 대조군)</b>: 후행 공백 + 모델. TASK-001의 읽기 직후 trim 덕에 {@code Path.of()}에는 공백 없는 값이 들어가
   * 예외가 나지 않아야 한다. 같은 원문을 직접 {@code Path.of()}에 넣으면 Windows에서 {@code InvalidPathException}이 나는 것을
   * 함께 보여, "미재현"이 하네스 무력이 아니라 trim의 효과임을 드러낸다.
   */
  @Test
  void R3_P6_후행공백_sourcePath는_TASK001_trim_덕에_Path_of에서_예외가_나지_않는다() throws Exception {
    Path src = realSourceDir();
    String withTrailingSpace = slash(src) + " ";

    // 대조: trim 없는 원문은 이 플랫폼에서 Path.of()가 어떻게 반응하는가
    String rawParse;
    try {
      Path.of(withTrailingSpace);
      rawParse = "원문 Path.of() 예외 없음(비-Windows 파서로 추정)";
    } catch (InvalidPathException e) {
      rawParse = "원문 Path.of() → " + e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    Outcome o = call(request(withTrailingSpace, "", MODEL, "sess-r3"));
    System.out.println("[R3] windows=" + isWindows() + " / " + rawParse + " / startAnalysis: " + o.summary());

    assertNull(o.thrown(), "TASK-001 trim이 적용됐다면 후행 공백으로는 예외가 나지 않아야 한다: " + o.summary());
    assertTrue(o.reachedCreateSession(), "trim된 경로는 존재 검사를 통과해 createSession까지 가야 한다: " + o.summary());
    verify(claudeService).setModel(eq(Path.of(slash(src)).toString()), eq(MODEL));
    if (isWindows()) {
      assertTrue(rawParse.contains("InvalidPathException"),
          "Windows에서는 trim 없는 원문이 Path.of()에서 예외여야 이 케이스가 trim의 효과를 증명한다: " + rawParse);
    }
  }

  /**
   * <b>R4 — P7 본 재현 후보</b>: Windows 금지문자({@code |}) + 모델. REQ-009 적용 후에도 trim으로는 걸러지지 않는 축이다.
   * <b>단언은 수정 후 기대 동작</b>(TASK-004: 진입부 {@code isParsablePath()} 가드가 기존 문구로 흡수)에 걸려 있다 —
   * 재현되면 지금은 RED이고, 실패 메시지에 예외 원문과 첫 컨트롤러 프레임이 실린다.
   */
  @Test
  void R4_P7_Windows_금지문자_sourcePath에_모델을_지정하면_기존_문구로_거부되어야_한다_현재는_재현시_의도된_RED() {
    String bad = badPathUnder(tempDir, "src|bad");
    Outcome o = call(request(bad, "", MODEL, "sess-r4"));
    String frame = o.thrown() == null ? "-" : firstControllerFrame(o.thrown());
    System.out.println("[R4] windows=" + isWindows() + " / " + o.summary() + " / firstControllerFrame=" + frame);
    if (o.thrown() != null) System.out.println("[R4-stacktrace]\n" + stackTraceOf(o.thrown()));

    if (o.thrown() != null) {
      fail("재현됨 — startAnalysis()가 예외를 밖으로 전파했다(try 없음, @ControllerAdvice 0건 → 실서비스 HTTP 500은 추론). "
          + o.thrown().getClass().getName() + ": " + o.thrown().getMessage()
          + " / 첫 컨트롤러 프레임: " + frame);
    }
    assertNotNull(o.result());
    assertEquals(EXISTING_SOURCE_ERROR, o.result().get("error"),
        "수정 후에는 진입 가드가 기존 문구로 거부해야 한다: " + o.summary());
    verify(claudeService, never()).setModel(anyString(), anyString());
  }

  /**
   * <b>R5 — outputPath 축 관측(수정하지 않는다)</b>: 금지문자가 {@code outputPath}에만 있고 모델을 지정하면, {@code startAnalysis()} 안에는
   * {@code Path.of(outputPath)}가 없어 createSession까지 그대로 간다(outputPath 파싱은 {@code runAnalysis()} 안 try 블록에서 일어난다).
   * 이 관측은 "startAnalysis()에 outputPath 축 노출이 있는가"에만 답한다.
   */
  @Test
  void R5_outputPath_축_관측_금지문자가_outputPath에만_있으면_startAnalysis는_예외_없이_createSession까지_간다() throws Exception {
    Path src = realSourceDir();
    String badOut = badPathUnder(tempDir, "out|bad");
    Outcome o = call(request(slash(src), badOut, MODEL, "sess-r5"));
    System.out.println("[R5] " + o.summary());

    assertNull(o.thrown(), "startAnalysis() 안에는 Path.of(outputPath)가 없어 예외가 나지 않아야 한다: " + o.summary());
    assertTrue(o.reachedCreateSession(), o.summary());
    verify(sessionManager).createSession(eq("sess-r5"), eq(slash(src)), eq(badOut));
  }

  /**
   * <b>R6 — TASK-004 정적 구조 단언(DoD 5)</b>: 진입 가드 {@code isParsablePath(sourcePath)}가 {@code startAnalysis()} 안에서
   * {@code claudeService.setModel(Path.of(sourcePath)...)} 줄보다 <b>앞</b>에 있고, 가드가 값을 변형하지 않는다(가드 블록에 {@code trim()}
   * /{@code replace()}가 없다). 대조군 = TASK-003 커밋({@code 5380314}) 시점의 고정본에는 {@code startAnalysis()} 안에 {@code isParsablePath(}
   * 호출이 0건이었다 — 이 단언은 그 고정본에서 RED다.
   */
  @Test
  void R6_TASK004_정적단언_isParsablePath_가드가_startAnalysis_안에서_setModel_Path_of_줄보다_앞에_있다() throws Exception {
    Path source = Path.of("src/main/java/com/legacy/analysis/MainApiController.java");
    String text = Files.readString(source);
    int methodStart = text.indexOf("public Map<String, Object> startAnalysis(");
    assertTrue(methodStart >= 0, "startAnalysis() 선언을 찾지 못했다");
    int setModelAt = text.indexOf("claudeService.setModel(Path.of(sourcePath).toString(), selectedModel);", methodStart);
    assertTrue(setModelAt > methodStart, "startAnalysis() 안의 setModel(Path.of(sourcePath)) 줄을 찾지 못했다");
    int guardAt = text.indexOf("if (!isParsablePath(sourcePath)) {", methodStart);
    assertTrue(guardAt > methodStart, "startAnalysis() 안에 isParsablePath(sourcePath) 진입 가드가 없다(TASK-004 미적용 상태)");
    assertTrue(guardAt < setModelAt,
        "진입 가드는 setModel(Path.of(sourcePath)) 줄보다 앞이어야 한다: guardAt=" + guardAt + ", setModelAt=" + setModelAt);

    // 가드는 검사만 한다 — 가드 블록(if ~ 첫 return) 안에 값 변형(trim/replace)이 없다.
    int guardEnd = text.indexOf("return result;", guardAt);
    String guardBlock = text.substring(guardAt, guardEnd);
    assertTrue(!guardBlock.contains(".trim(") && !guardBlock.contains(".replace("),
        "가드 블록은 값을 변형하지 않아야 한다: " + guardBlock);
    // 가드가 흡수하는 문구는 기존 존재-검사 문구와 동일하다(새 문구 0건).
    assertTrue(guardBlock.contains("\"" + EXISTING_SOURCE_ERROR + "\""), "가드는 기존 문구를 재사용해야 한다: " + guardBlock);

    int guardLine = (int) text.substring(0, guardAt).chars().filter(ch -> ch == '\n').count() + 1;
    int setModelLine = (int) text.substring(0, setModelAt).chars().filter(ch -> ch == '\n').count() + 1;
    System.out.println("[R6] guard line=" + guardLine + " / setModel(Path.of(sourcePath)) line=" + setModelLine);
  }
}
