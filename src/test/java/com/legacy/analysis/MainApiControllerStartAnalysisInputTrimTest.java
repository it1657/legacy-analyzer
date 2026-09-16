package com.legacy.analysis;

import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.auth.Role;
import com.legacy.auth.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-009(2026-09-remaining-ux-fixes TASK-001) — {@code startAnalysis()} 입력값 진입점 정규화의 전용 근거.
 *
 * <p><b>(A) 동작 케이스</b>: 후행 공백이 붙은 {@code sourcePath}/{@code outputPath}로 {@code startAnalysis()}를
 * 호출하면 세션 생성({@code createSession})에 전달되는 값이 trim된 값이어야 한다.
 * 수정 전 소스에서는 이 단언이 깨진다(두 시점 실행 결과는 {@code 05-dev-progress.md}에 기록) —
 * Windows에서는 {@code new File("...  ").exists()}가 후행 공백을 무시해 통과하므로 공백이 그대로
 * {@code createSession}에 실려 가고, Linux에서는 존재 검사에서 끊겨 {@code createSession}이 호출조차 되지
 * 않는다. 어느 OS든 "trim된 값으로 createSession이 호출됐다"는 단언은 수정 전에는 성립하지 않는다.
 *
 * <p><b>P2 관측(work-order v2 §0.19.3 정정본)</b>: {@code isCopyModeOutput(sourcePath, outputPath)}의 판정이
 * sourcePath trim으로 바뀌는 조합은 <b>sourcePath에 후행 공백이 있는 2개 조합</b>이며(출력 후행공백 유무와 무관),
 * 두 조합 모두 copy → 비-copy(정정 방향)다. sourcePath에 후행 공백이 없는 2개 조합은 trim 전후가 동일하다.
 * 이유: 기존 읽기 측이 {@code outputPath.trim()}과 raw {@code sourcePath}를 비교하는 비대칭 구조라, 출력 쪽
 * 공백은 어차피 흡수되고 소스 쪽 공백만 판정에 영향을 준다.
 * 4개 조합을 전수 실행해 표로 남긴다(사설 static 메서드를 리플렉션으로 직접 호출 — 산식 복제 아님).
 *
 * <p>생성자는 기존 {@code MainApiController*Test}들과 같은 "필요한 의존성만 mock, 나머지는 null" 패턴이다.
 */
class MainApiControllerStartAnalysisInputTrimTest {

  /** createSession에 도달했음을 표시하고 실제 분석 스레드 기동을 막는 표식 예외. */
  private static final String SENTINEL = "SENTINEL: createSession 도달";

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

    controller = new MainApiController(
        claudeService, null, sessionManager, null, null, null, null, null, null, null, null, null, null,
        llmModelOptionService, null);
    // local 모드 — Anthropic 권한 가드에 걸리지 않게 한다(이 테스트의 관심사가 아니다).
    setField("llmProvider", "local");
    setField("uploadStoragePath", tempDir.toString());

    when(sessionManager.createSession(anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException(SENTINEL));
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

  /** 실제 존재하는 소스 디렉터리(존재 검사를 통과해야 createSession까지 간다). */
  private Path realSourceDir() throws Exception {
    Path src = tempDir.resolve("src-proj");
    Files.createDirectories(src);
    return src;
  }

  private static String slash(Path p) {
    return p.toString().replace("\\", "/");
  }

  // ===================================================================
  // (A) 동작 케이스 — 후행 공백 입력 → createSession에는 trim된 값이 전달된다
  // ===================================================================

  @Test
  void 후행공백이_붙은_sourcePath와_outputPath는_읽기_직후_trim되어_createSession에_전달된다() throws Exception {
    Path src = realSourceDir();
    String cleanSource = slash(src);
    String cleanOutput = slash(tempDir.resolve("out-root"));

    Map<String, String> request = new HashMap<>();
    request.put("sourcePath", cleanSource + "   ");
    request.put("outputPath", cleanOutput + " \t");
    request.put("sessionId", "sess-trim-1");
    // model은 비운다 — setModel(Path.of(sourcePath)) 분기(REQ-006 축)에 들어가지 않게 해서
    // 이 케이스가 오직 "createSession에 전달되는 값"만 판정하도록 고립한다.
    request.put("model", "");

    Map<String, Object> result;
    try {
      result = controller.startAnalysis(request, adminAuth());
    } catch (RuntimeException e) {
      // 표식 예외 = createSession까지 도달했다는 뜻. 도달 자체는 정상.
      assertEquals(SENTINEL, e.getMessage());
      result = null;
    }
    if (result != null) {
      // createSession에 도달하지 못하고 조기 반환했다면(예: 존재 검사 실패) 그 사유를 그대로 드러낸다.
      assertEquals(null, result.get("error"),
          "createSession에 도달하지 못했다 — 후행 공백이 존재 검사에서 끊긴 것이면 trim이 적용되지 않은 것이다");
    }

    // 핵심 단언: sourcePath/outputPath 양쪽 모두 trim된 값으로 createSession이 호출됐다.
    verify(sessionManager).createSession(eq("sess-trim-1"), eq(cleanSource), eq(cleanOutput));
  }

  @Test
  void outputPath가_공백뿐이면_trim_후_blank로_취급되어_sourcePath가_출력경로로_쓰인다() throws Exception {
    Path src = realSourceDir();
    String cleanSource = slash(src);

    Map<String, String> request = new HashMap<>();
    request.put("sourcePath", cleanSource + " ");
    request.put("outputPath", "   ");
    request.put("sessionId", "sess-trim-2");
    request.put("model", "");

    try {
      controller.startAnalysis(request, adminAuth());
    } catch (RuntimeException e) {
      assertEquals(SENTINEL, e.getMessage());
    }

    // outputPath.isBlank() → null → createSession의 3번째 인자는 finalSourcePath.
    verify(sessionManager).createSession(eq("sess-trim-2"), eq(cleanSource), eq(cleanSource));
  }

  // ===================================================================
  // P2 관측(v2 §0.19.3 정정본) — isCopyModeOutput 판정이 sourcePath trim으로 바뀌는 조합은
  // "소스 후행공백 있음" 2개(출력 공백 유무 무관)이며, 둘 다 copy → 비-copy
  // ===================================================================

  @Test
  void P2_sourcePath_trim으로_isCopyModeOutput_판정이_바뀌는_조합은_소스후행공백_2개이고_둘다_copy에서_비copy로_바뀐다()
      throws Exception {
    Method m = MainApiController.class.getDeclaredMethod("isCopyModeOutput", String.class, String.class);
    m.setAccessible(true);

    String base = "C:/proj/src";
    // (소스 후행공백?, 출력 후행공백?) 4조합 전수. 출력은 소스와 같은 경로(= 비-copy가 정답인 입력)로 둔다.
    String[][] combos = {
        // label,               rawSource,   rawOutput
        {"소스X 출력X", base,        base},
        {"소스X 출력O", base,        base + " "},
        {"소스O 출력X", base + " ",  base},
        {"소스O 출력O", base + " ",  base + " "},
    };

    List<String> changedLabels = new ArrayList<>();
    List<String> unchangedLabels = new ArrayList<>();
    for (String[] c : combos) {
      String rawSource = c[1];
      String rawOutput = c[2];
      boolean before = (boolean) m.invoke(null, rawSource, rawOutput);          // 진입점 trim 없이(수정 전 입력)
      boolean after = (boolean) m.invoke(null, rawSource.trim(), rawOutput);    // sourcePath만 trim(이 변경의 효과)
      System.out.println("[P2] " + c[0] + " : before(copy?)=" + before + " after(copy?)=" + after
          + (before != after ? "  <-- 판정 변경" : ""));
      if (before != after) {
        changedLabels.add(c[0]);
        // (c) 바뀌는 방향은 copy(true) → 비-copy(false) = 정정 방향이어야 한다.
        assertTrue(before, "바뀌는 조합은 수정 전 copy 판정이어야 한다: " + c[0]);
        assertFalse(after, "바뀐 뒤에는 비-copy 판정이어야 한다: " + c[0]);
      } else {
        unchangedLabels.add(c[0]);
      }
    }

    // (a) 판정이 바뀌는 조합은 정확히 2개다.
    assertEquals(2, changedLabels.size(), "판정이 바뀌는 조합은 정확히 2개여야 한다: " + changedLabels);
    // (b) 그 2개는 sourcePath에 후행 공백이 있는 조합이다(출력 후행공백 유무와 무관).
    assertEquals(List.of("소스O 출력X", "소스O 출력O"), changedLabels);
    // 대조군: sourcePath에 후행 공백이 없는 2개 조합은 trim 전후 판정이 동일하다
    // (출력 후행공백은 기존 읽기 측 outputPath.trim()이 이미 흡수하므로 진입점 trim과 무관).
    assertEquals(List.of("소스X 출력X", "소스X 출력O"), unchangedLabels);
  }
}
