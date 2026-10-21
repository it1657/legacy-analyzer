package com.legacy.analysis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-006 (work-order 2026-10-collect-zero-and-claudemd-button v2) —
 * <b>REQ-001 수정이 "닿지 않아야 하는 곳"을 고정하는 무변경 가드</b>. 회귀 테스트
 * ({@link CollectRootRelativeExclusionTest})와 섞지 않고 따로 둔다(보정 C18).
 *
 * <p>케이스는 work-order §0.5-v2 회귀 가드와 1:1이다.
 * <ul>
 *   <li><b>G1</b>(RG-5) — {@code MainApiController}의 12개 메서드 본문이 착수 커밋 고정본과 문자 단위로 같다.
 *       같은 이름 오버로드는 <b>선언 줄 전체</b>(매개변수 목록 포함)로 찾는다.</li>
 *   <li><b>G2</b>(RG-11) — {@code dashboard.js}의 12개 함수 본문이 고정본과 문자 단위로 같다.</li>
 *   <li><b>G3</b>(RG-3) — {@code isSupportedFile()}의 추적파일 주석 첫 줄부터 메서드 끝까지가 고정본과 같다
 *       (= 파일명 규칙 0행).</li>
 *   <li><b>G4</b>(RG-2) — 네 스캔 지점이 모두 새 판정을 거치고, 옛 호출 형태가 남아 있지 않다.</li>
 *   <li><b>G5</b>(RG-12) — 0건 분기 블록만 자리표시로 가린 {@code runAnalysis()} 본문이 고정본과 같다
 *       (= 이력 행 생성 위치·내용 불변, D11).</li>
 *   <li><b>G6</b> — {@code dashboard.js}의 새 실패 처리 함수가 완료 경로의 부수효과를 쓰지 않고,
 *       폴링이 완료 처리보다 <b>먼저</b> FAILED를 가로챈다.</li>
 * </ul>
 *
 * <p><b>왜 고정본 해시를 먼저 단언하는가</b>: 비교의 기준이 조용히 바뀌면 이 가드가 통째로 무의미해진다.
 * 그래서 고정본을 읽을 때마다 {@code git hash-object} 값(보정 C20)을 먼저 확인한다.
 *
 * <p><b>양성 대조군</b>(G-04 ①②): 케이스마다 같은 비교기·검사기에 <b>1글자 변이</b>를 먹여 "불일치를
 * 실제로 잡는다"는 것을 같은 테스트 안에서 보인다. 변이는 메모리 문자열에만 하고 파일은 건드리지 않는다.
 *
 * <p>행번호는 적지 않는다(G-08) — 찾는 기준은 선언 줄·함수 이름·주석 첫 줄 문자열이다.
 */
class CollectZeroUnchangedMethodsGuardTest {

  private static final String CONTROLLER = "src/main/java/com/legacy/analysis/MainApiController.java";
  private static final String DASHBOARD_JS = "src/main/resources/static/js/dashboard.js";

  private static final String CONTROLLER_FIXTURE =
      "/collectzero/MainApiController.java.before-b4abecb.txt";
  private static final String DASHBOARD_FIXTURE = "/collectzero/dashboard.js.before-b4abecb.txt";

  /** 착수 커밋 고정본의 raw blob SHA-1(보정 C20). */
  private static final String CONTROLLER_FIXTURE_BLOB = "3bf6081ae9c50426577360b248d9d4c74ac49cf0";
  private static final String DASHBOARD_FIXTURE_BLOB = "f54e0f1e8b3739b5cfcf5622800de3deb4286b41";

  /**
   * RG-5 — 이번 수정이 닿지 않아야 하는 {@code MainApiController} 메서드 12개. 선언 줄 전체로 찾으므로
   * {@code resolveProjectOutputRoot} 오버로드 2개가 섞이지 않는다.
   * (RG-1이 이미 고정하는 9개는 중복으로 넣지 않는다.)
   */
  private static final List<String> CONTROLLER_METHOD_DECLARATIONS = List.of(
      "private Path getUploadStorageRoot() {",
      "public Map<String, Object> cleanupUploadSession(@PathVariable String sessionId, Authentication authentication) {",
      "private static String resolveUserOutputRoot(String sourcePath, String outputPath, String username) {",
      "private static Path resolveProjectOutputRoot(String sourcePath, String outputPath, String username) {",
      "private static Path resolveProjectOutputRoot(SessionState session) {",
      "private Path resolveFailedFilesRoot(SessionState session) {",
      "private Map<String, Object> resumePendingFilesInThread(SessionState session, String sessionId) {",
      "private void runAnalysisResume(String sessionId, List<Path> fileList) {",
      "private Set<String> detectExtensions(List<Path> fileList) {",
      "private Path getTrackerFilePath(String outputRoot) {",
      "private StringBuilder buildDetailedProjectStructure(SessionState session, Path outputPath) {",
      "private String detectBuildTool(Path outputPath) {");

  /** RG-11 — 이번 수정이 닿지 않아야 하는 {@code dashboard.js} 함수 12개. */
  private static final List<String> DASHBOARD_FUNCTIONS = List.of(
      "handleAnalysisCompletion", "showCompletionResult", "showAllFailedPausedResult",
      "isAllFailedPause", "setCompletionPanelMode", "fillCompletionResultPanel",
      "handleAnalysisPaused", "handleAnalysisCancelled", "updateUiFromStatus",
      "performWriteBack", "showError", "dismissError");

  /** 네 스캔 지점 — 각 본문에 새 판정 호출이 정확히 1회 있어야 한다(RG-2). */
  private static final List<String> SCAN_POINT_DECLARATIONS = List.of(
      "public Map<String, Object> getDashboardStatus(",
      "private List<Path> collectFileList(Path sourceRootPath) throws Exception {",
      "private void appendJavaStructure(StringBuilder sb, Path outputPath, SessionState session) {",
      "private void appendFrontendStructure(StringBuilder sb, Path root, String projectType, SessionState session) {");

  private static final String ANALYSIS_TARGET_DECLARATION =
      "private boolean isAnalysisTarget(Path root, Path file) {";
  private static final String SUPPORTED_FILE_DECLARATION =
      "private boolean isSupportedFile(Path path) {";
  /** RG-3 — 이 주석 첫 줄부터 메서드 끝까지는 바이트 동일해야 한다. */
  private static final String TRACKER_COMMENT_HEAD =
      "// 분석 완료 추적파일(TRACKER_FILE_NAME)은";

  private static final String RUN_ANALYSIS_DECLARATION =
      "private void runAnalysis(String sessionId, String normalizedSourcePath,";
  private static final String ZERO_BRANCH_HEAD = "if (fileList.isEmpty()) {";
  private static final String ZERO_BRANCH_PLACEHOLDER = "[0건 분기 — RG-12로 따로 본다]";

  /** {@code handleAnalysisFailed()} 본문에 있으면 안 되는 토큰(§0.3-v2). */
  private static final List<String> FAILED_HANDLER_FORBIDDEN_TOKENS = List.of(
      "globalFilesCache", "renderDividedGrid(", "isCompleted", "fillCompletionResultPanel(",
      "showCompletionResult(", "showAllFailedPausedResult(", "performWriteBack(", "innerHTML",
      "cr_", "setInterval(", "setTimeout(");

  /** 세션 정리 3줄 — 기존 종단 처리 함수들과 같은 모양이어야 한다. */
  private static final List<String> SESSION_CLEANUP_TAIL = List.of(
      "clearSessionFromStorage();", "currentSessionId = null;", "updateSessionControlPanel();");

  // ════════════════════════════════════════════════════════════════════════════
  // G1 — RG-5: MainApiController 12개 메서드 본문 불변
  // ════════════════════════════════════════════════════════════════════════════

  @Test
  void G1_범위_밖_12개_메서드_본문이_착수커밋_고정본과_문자단위로_같다() throws Exception {
    String current = normalizeEol(readCurrentSource(CONTROLLER));
    String fixture = normalizeEol(readFixture(CONTROLLER_FIXTURE, CONTROLLER_FIXTURE_BLOB));

    List<String> violations = new ArrayList<>();
    int compared = 0;
    for (String declaration : CONTROLLER_METHOD_DECLARATIONS) {
      String currentBody = javaMethodBody(current, "MainApiController(현재)", declaration);
      String fixtureBody = javaMethodBody(fixture, "MainApiController(고정본)", declaration);
      compared++;

      System.out.println("[G1] " + shortName(declaration)
          + " | 현재 첫줄=" + firstLine(currentBody) + " | 현재 끝줄=" + lastLine(currentBody)
          + " | 길이 현재/고정본=" + currentBody.length() + "/" + fixtureBody.length());

      if (!currentBody.equals(fixtureBody)) {
        violations.add(shortName(declaration) + ": " + firstDifference(fixtureBody, currentBody));
      }
    }

    assertEquals(CONTROLLER_METHOD_DECLARATIONS.size(), compared,
        "감시 목록이 줄어들었다 — 비교한 메서드 수가 기대와 다르다");
    assertTrue(violations.isEmpty(),
        "0행으로 지정된 메서드 본문이 바뀌었다(RG-5):\n  " + String.join("\n  ", violations));

    // 양성 대조군 — 같은 비교기에 1글자 변이를 먹이면 불일치로 판정한다.
    String sample = javaMethodBody(fixture, "MainApiController(고정본)",
        CONTROLLER_METHOD_DECLARATIONS.get(0));
    String mutated = mutateOneChar(sample);
    assertNotEquals(sample, mutated, "변이가 만들어지지 않았다");
    assertFalse(sample.equals(mutated), "1글자 변이를 같다고 판정했다 — 비교기가 결함을 못 잡는다");
    assertTrue(sample.equals(sample), "같은 문자열을 다르다고 판정했다(비교기가 '항상 불일치'가 아님)");
    System.out.println("[G1 양성 대조군] " + shortName(CONTROLLER_METHOD_DECLARATIONS.get(0))
        + " 1글자 변이 → 불일치 검출");
  }

  // ════════════════════════════════════════════════════════════════════════════
  // G2 — RG-11: dashboard.js 12개 함수 본문 불변
  // ════════════════════════════════════════════════════════════════════════════

  @Test
  void G2_dashboard_js의_12개_함수_본문이_착수커밋_고정본과_문자단위로_같다() throws Exception {
    String current = normalizeEol(readCurrentSource(DASHBOARD_JS));
    String fixture = normalizeEol(readFixture(DASHBOARD_FIXTURE, DASHBOARD_FIXTURE_BLOB));

    List<String> violations = new ArrayList<>();
    int compared = 0;
    for (String name : DASHBOARD_FUNCTIONS) {
      String currentBody = jsFunctionBody(current, "dashboard.js(현재)", name);
      String fixtureBody = jsFunctionBody(fixture, "dashboard.js(고정본)", name);
      compared++;

      System.out.println("[G2] " + name
          + " | 현재 첫줄=" + firstLine(currentBody) + " | 현재 끝줄=" + lastLine(currentBody)
          + " | 길이 현재/고정본=" + currentBody.length() + "/" + fixtureBody.length());

      if (!currentBody.equals(fixtureBody)) {
        violations.add(name + ": " + firstDifference(fixtureBody, currentBody));
      }
    }

    assertEquals(DASHBOARD_FUNCTIONS.size(), compared, "감시 함수 목록이 줄어들었다");
    assertTrue(violations.isEmpty(),
        "0행으로 지정된 함수 본문이 바뀌었다(RG-11):\n  " + String.join("\n  ", violations));

    String sample = jsFunctionBody(fixture, "dashboard.js(고정본)", DASHBOARD_FUNCTIONS.get(0));
    String mutated = mutateOneChar(sample);
    assertFalse(sample.equals(mutated), "1글자 변이를 같다고 판정했다 — 비교기가 결함을 못 잡는다");
    System.out.println("[G2 양성 대조군] " + DASHBOARD_FUNCTIONS.get(0) + " 1글자 변이 → 불일치 검출");
  }

  // ════════════════════════════════════════════════════════════════════════════
  // G3 — RG-3: isSupportedFile()의 파일명 규칙 구간 불변
  // ════════════════════════════════════════════════════════════════════════════

  @Test
  void G3_isSupportedFile의_추적파일_주석부터_메서드_끝까지가_고정본과_같다() throws Exception {
    String current = normalizeEol(readCurrentSource(CONTROLLER));
    String fixture = normalizeEol(readFixture(CONTROLLER_FIXTURE, CONTROLLER_FIXTURE_BLOB));

    String currentTail = supportedFileNameRules(current, "MainApiController(현재)");
    String fixtureTail = supportedFileNameRules(fixture, "MainApiController(고정본)");

    System.out.println("[G3] 현재 첫줄=" + firstLine(currentTail) + " | 현재 끝줄=" + lastLine(currentTail));
    System.out.println("[G3] 고정본 첫줄=" + firstLine(fixtureTail) + " | 고정본 끝줄=" + lastLine(fixtureTail)
        + " | 길이 현재/고정본=" + currentTail.length() + "/" + fixtureTail.length());

    assertEquals(fixtureTail, currentTail,
        "파일명 규칙(추적파일·바이너리·화이트리스트) 구간이 바뀌었다(RG-3): "
            + firstDifference(fixtureTail, currentTail));

    String mutated = mutateOneChar(fixtureTail);
    assertFalse(fixtureTail.equals(mutated), "1글자 변이를 같다고 판정했다 — 비교기가 결함을 못 잡는다");
    System.out.println("[G3 양성 대조군] 구간 1글자 변이 → 불일치 검출");
  }

  // ════════════════════════════════════════════════════════════════════════════
  // G4 — RG-2: 네 스캔 지점이 모두 새 판정을 거치고 옛 호출 형태가 없다
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 한 지점만 고쳐 두고 나머지를 옛 판정에 남겨두면 "1단계 개수 ≠ 수집 개수"가 다시 벌어진다.
   * 그래서 네 지점 각각을 <b>이름으로</b> 확인하고(G-24), 옛 호출 형태({@code this::isSupportedFile})가
   * 코드에 0회임을 함께 본다. 주석은 공백으로 지워 세므로 설명 문장은 집계에 들어가지 않는다.
   */
  @Test
  void G4_네_스캔지점이_모두_isAnalysisTarget을_거치고_옛_호출형태가_남아있지_않다() throws Exception {
    // 양성 대조군 — 집계기가 호출 1건을 세고, 괄호 1글자만 깨면 0건으로 떨어진다는 것을 먼저 보인다.
    // (프로덕션 상태와 무관한 합성 입력이라 이 케이스가 RED일 때도 "검사기는 작동한다"가 남는다.)
    String synthetic = "    return stream.filter(p -> isAnalysisTarget(root, p)).toList();";
    assertEquals(1, countOccurrences(synthetic, "isAnalysisTarget("), "집계기가 호출 1건을 못 센다");
    String syntheticBroken = synthetic.replaceFirst("isAnalysisTarget\\(", "isAnalysisTarget ");
    assertNotEquals(synthetic, syntheticBroken, "변이가 만들어지지 않았다");
    assertEquals(0, countOccurrences(syntheticBroken, "isAnalysisTarget("),
        "1글자 변이를 집계기가 못 잡는다 — 이 검사는 결함을 탐지하지 못한다");
    System.out.println("[G4 양성 대조군] 합성 입력 1건 → 괄호 1글자 변이 후 0건 (검출)");

    String blanked = blankComments(normalizeEol(readCurrentSource(CONTROLLER)));

    List<String> violations = new ArrayList<>();
    for (String declaration : SCAN_POINT_DECLARATIONS) {
      String body = javaMethodBody(blanked, "MainApiController(현재·주석제거)", declaration);
      int calls = countOccurrences(body, "isAnalysisTarget(");
      System.out.println("[G4] " + shortName(declaration) + " → isAnalysisTarget( " + calls + "회");
      if (calls != 1) {
        violations.add(shortName(declaration) + ": isAnalysisTarget( 호출이 " + calls + "회(기대 1회)");
      }
    }

    int oldForm = countOccurrences(blanked, "this::isSupportedFile");
    System.out.println("[G4] 코드(주석 제거) 전체 this::isSupportedFile = " + oldForm + "회");
    if (oldForm != 0) {
      violations.add("this::isSupportedFile가 " + oldForm + "회 남아 있다(기대 0회)");
    }

    // 파일명 규칙은 한 곳에서만 쓰인다 — 선언 1개 + isAnalysisTarget 본문 안의 호출 1개 = 전체 2회.
    int totalSupported = countOccurrences(blanked, "isSupportedFile(");
    int declarations = countOccurrences(blanked, ANALYSIS_TARGET_DECLARATION);
    int insideTarget = -1;
    if (declarations == 1) {
      String targetBody = javaMethodBody(blanked, "MainApiController(현재·주석제거)",
          ANALYSIS_TARGET_DECLARATION);
      insideTarget = countOccurrences(targetBody, "isSupportedFile(");
      if (insideTarget != 1) {
        violations.add("isAnalysisTarget 본문의 isSupportedFile( 호출이 " + insideTarget + "회(기대 1회)");
      }
    } else {
      violations.add("isAnalysisTarget(Path, Path) 선언을 정확히 1개 찾지 못했다(찾은 수="
          + declarations + ") — 새 판정 메서드가 없거나 시그니처가 다르다");
    }
    System.out.println("[G4] isSupportedFile( 전체=" + totalSupported
        + "회(선언 1 + 호출 1 기대) / isAnalysisTarget 선언=" + declarations
        + "개 / 그 본문 안 isSupportedFile( =" + (insideTarget < 0 ? "측정불가" : insideTarget) + "회");
    if (totalSupported != 2) {
      violations.add("isSupportedFile( 출현이 " + totalSupported
          + "회다(기대 2회 = 선언 1 + isAnalysisTarget 안 호출 1) — 다른 곳에서도 직접 부르고 있다");
    }

    assertTrue(violations.isEmpty(), "RG-2 위반:\n  " + String.join("\n  ", violations));
  }

  // ════════════════════════════════════════════════════════════════════════════
  // G5 — RG-12: 0건 분기 밖 runAnalysis 본문 불변 (D11 — 이력 행 미생성 고정)
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 0건 분기 안의 두 줄(실패 사유·서버 로그)만 바뀌어야 한다. 그래서 <b>그 블록만</b> 같은 자리표시
   * 문자열로 가린 뒤 나머지를 고정본과 비교한다 — 이력 생성 블록이 0건 검사보다 앞으로 올라가거나
   * 내용이 바뀌면 여기서 걸린다.
   */
  @Test
  void G5_0건_분기만_가린_runAnalysis_본문이_고정본과_문자단위로_같다() throws Exception {
    String current = normalizeEol(readCurrentSource(CONTROLLER));
    String fixture = normalizeEol(readFixture(CONTROLLER_FIXTURE, CONTROLLER_FIXTURE_BLOB));

    String currentMasked = runAnalysisWithZeroBranchMasked(current, "MainApiController(현재)");
    String fixtureMasked = runAnalysisWithZeroBranchMasked(fixture, "MainApiController(고정본)");

    System.out.println("[G5] 현재 첫줄=" + firstLine(currentMasked) + " | 현재 끝줄=" + lastLine(currentMasked));
    System.out.println("[G5] 길이 현재/고정본=" + currentMasked.length() + "/" + fixtureMasked.length()
        + " | 자리표시 포함 현재=" + currentMasked.contains(ZERO_BRANCH_PLACEHOLDER)
        + " 고정본=" + fixtureMasked.contains(ZERO_BRANCH_PLACEHOLDER));

    assertTrue(currentMasked.contains(ZERO_BRANCH_PLACEHOLDER)
        && fixtureMasked.contains(ZERO_BRANCH_PLACEHOLDER), "0건 분기를 가리지 못했다");
    assertEquals(fixtureMasked, currentMasked,
        "0건 분기 밖 runAnalysis() 본문이 바뀌었다(RG-12 — 이력 생성 위치·내용 불변): "
            + firstDifference(fixtureMasked, currentMasked));

    String mutated = mutateOneChar(fixtureMasked);
    assertFalse(fixtureMasked.equals(mutated), "1글자 변이를 같다고 판정했다 — 비교기가 결함을 못 잡는다");
    System.out.println("[G5 양성 대조군] 가린 본문 1글자 변이 → 불일치 검출");
  }

  // ════════════════════════════════════════════════════════════════════════════
  // G6 — β-화면: 실패 처리가 완료 경로의 부수효과를 쓰지 않는다
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 실패 화면을 "완료 패널 재활용"으로 만들면 그리드·카운터·write-back이 함께 따라와 FAILED가 다시
   * 완료처럼 보인다. 그래서 ① 새 함수가 존재하고 ② 완료 경로 토큰을 하나도 쓰지 않으며 ③ 기존 종단
   * 처리와 같은 세션 정리 3줄로 끝나고 ④ 폴링이 완료 처리보다 <b>먼저</b> FAILED를 가로채는지 본다.
   */
  @Test
  void G6_handleAnalysisFailed가_완료경로_토큰을_쓰지_않고_폴링이_완료처리보다_먼저_FAILED를_가로챈다()
      throws Exception {
    String current = normalizeEol(readCurrentSource(DASHBOARD_JS));

    // 양성 대조군 ⓐ(먼저 둔다) — 완료 처리 본문을 같은 검사기에 먹이면 금지 토큰을 실제로 잡아낸다.
    // 이 케이스가 RED일 때도 "검사기는 작동한다"는 사실이 출력에 남는다.
    String completionBody = jsFunctionBody(current, "dashboard.js(현재)", "handleAnalysisCompletion");
    List<String> detectedInCompletion = forbiddenTokensIn(completionBody);
    System.out.println("[G6 양성 대조군 ⓐ] handleAnalysisCompletion 본문 → 금지 토큰 " + detectedInCompletion);
    assertFalse(detectedInCompletion.isEmpty(),
        "완료 처리 본문에서도 금지 토큰을 못 찾는다면 이 검사기는 아무것도 탐지하지 못한다");

    // ① 선언 존재 — 없으면 아래 단언이 전부 공허해지므로 여기서 fail로 보고한다.
    if (!current.contains("\nfunction handleAnalysisFailed(status) {")) {
      fail("dashboard.js에 function handleAnalysisFailed(status) 선언이 없다 — β-화면이 적용되지 않았다.");
    }
    String body = jsFunctionBody(current, "dashboard.js(현재)", "handleAnalysisFailed");
    System.out.println("[G6] handleAnalysisFailed 길이=" + body.length()
        + " | 끝줄=" + lastLine(body));

    // ② 금지 토큰 0
    List<String> found = forbiddenTokensIn(body);
    System.out.println("[G6] 금지 토큰 검사 = " + (found.isEmpty() ? "0건" : found));
    assertTrue(found.isEmpty(),
        "handleAnalysisFailed() 본문에 완료 경로 토큰이 있다(실패가 완료처럼 보이게 된다): " + found);

    // ③ 세션 정리 3줄로 끝난다(기존 handleAnalysisCancelled와 같은 모양)
    List<String> tail = lastMeaningfulLines(body, SESSION_CLEANUP_TAIL.size());
    System.out.println("[G6] 끝 3줄 = " + tail);
    assertEquals(SESSION_CLEANUP_TAIL, tail,
        "handleAnalysisFailed()가 기존 종단 처리와 같은 세션 정리 3줄로 끝나지 않는다");

    // ④ 폴링 분기 순서 — FAILED 가로채기가 handleAnalysisCompletion 호출보다 앞
    String polling = jsFunctionBody(current, "dashboard.js(현재)", "startPolling");
    assertEquals(0, failedBranchOrderViolations(polling).size(),
        "폴링 분기 순서가 어긋났다: " + failedBranchOrderViolations(polling) + "\n" + polling);
    System.out.println("[G6] 폴링 분기 순서 = FAILED 가로채기 → handleAnalysisCompletion (위반 0건)");

    // 양성 대조군 ⓑ — 끝 3줄 검사: 1글자 변이로 깨면 같은 검사기가 불일치로 본다.
    String mutatedTailBody = body.replaceFirst("updateSessionControlPanel\\(\\);",
        "updateSessionControlPanel();;");
    assertNotEquals(body, mutatedTailBody, "변이가 만들어지지 않았다");
    assertNotEquals(SESSION_CLEANUP_TAIL,
        lastMeaningfulLines(mutatedTailBody, SESSION_CLEANUP_TAIL.size()),
        "끝 3줄 검사가 1글자 변이를 못 잡는다");

    // 양성 대조군 ⓒ — 폴링 순서 검사: phase 문자열을 1글자 바꾸면 분기를 못 찾았다고 보고한다.
    String mutatedPolling = polling.replaceFirst("status\\.phase === 'FAILED'", "status.phase === 'FAILEX'");
    assertNotEquals(polling, mutatedPolling, "변이가 만들어지지 않았다");
    assertFalse(failedBranchOrderViolations(mutatedPolling).isEmpty(),
        "폴링 순서 검사가 1글자 변이를 못 잡는다");
    System.out.println("[G6 양성 대조군 ⓑⓒ] 끝 3줄 변이·phase 문자열 변이 → 둘 다 검출");
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // 도우미
  // ═══════════════════════════════════════════════════════════════════════════

  private static List<String> forbiddenTokensIn(String body) {
    return FAILED_HANDLER_FORBIDDEN_TOKENS.stream().filter(body::contains).toList();
  }

  /** FAILED 가로채기가 완료 처리 호출보다 앞인지 — 위반 사유 목록(빈 목록 = 정상). */
  private static List<String> failedBranchOrderViolations(String pollingBody) {
    List<String> violations = new ArrayList<>();
    int failedBranch = pollingBody.indexOf("status.phase === 'FAILED'");
    int failedCall = pollingBody.indexOf("handleAnalysisFailed(status)");
    int completionCall = pollingBody.indexOf("handleAnalysisCompletion(status)");
    if (failedBranch < 0) violations.add("startPolling에 status.phase === 'FAILED' 분기가 없다");
    if (failedCall < 0) violations.add("startPolling이 handleAnalysisFailed(status)를 부르지 않는다");
    if (completionCall < 0) {
      violations.add("startPolling의 handleAnalysisCompletion(status) 호출을 찾지 못했다(계약 기준점 소실)");
    }
    if (failedBranch >= 0 && failedCall >= 0 && failedBranch > failedCall) {
      violations.add("FAILED 분기 조건이 handleAnalysisFailed 호출보다 뒤다");
    }
    if (failedCall >= 0 && completionCall >= 0 && failedCall > completionCall) {
      violations.add("handleAnalysisFailed 호출이 handleAnalysisCompletion 호출보다 뒤다 — FAILED가 완료로 빠진다");
    }
    return violations;
  }

  /** 뒤에서부터 빈 줄·닫는 중괄호만 있는 줄을 건너뛰고 의미 있는 n줄을 순서대로 돌려준다. */
  private static List<String> lastMeaningfulLines(String body, int n) {
    String[] lines = body.split("\n", -1);
    List<String> picked = new ArrayList<>();
    for (int i = lines.length - 1; i >= 0 && picked.size() < n; i--) {
      String trimmed = lines[i].trim();
      if (trimmed.isEmpty() || trimmed.equals("}")) continue;
      picked.add(0, trimmed);
    }
    return picked;
  }

  /** {@code isSupportedFile()}의 추적파일 주석 첫 줄부터 메서드 끝까지(RG-3 구간). */
  private static String supportedFileNameRules(String source, String where) {
    String body = javaMethodBody(source, where, SUPPORTED_FILE_DECLARATION);
    int at = body.indexOf(TRACKER_COMMENT_HEAD);
    if (at < 0) {
      return fail(where + ": isSupportedFile() 본문에서 추적파일 주석 첫 줄을 찾지 못했다(RG-3 기준점 소실)");
    }
    return body.substring(at);
  }

  /** {@code runAnalysis()} 본문에서 0건 분기 블록만 자리표시로 가린 문자열(RG-12 비교 대상). */
  private static String runAnalysisWithZeroBranchMasked(String source, String where) {
    String body = javaMethodBody(source, where, RUN_ANALYSIS_DECLARATION);
    int occurrences = countOccurrences(body, ZERO_BRANCH_HEAD);
    if (occurrences != 1) {
      return fail(where + ": runAnalysis() 본문의 " + ZERO_BRANCH_HEAD + "가 " + occurrences
          + "개다(기대 1개) — 기준점이 바뀌었으니 G-02대로 멈춘다");
    }
    int at = body.indexOf(ZERO_BRANCH_HEAD);
    int open = body.indexOf('{', at);
    int close = matchingBrace(body, open);
    if (close < 0) {
      return fail(where + ": 0건 분기의 닫는 중괄호를 찾지 못했다");
    }
    return body.substring(0, at) + ZERO_BRANCH_PLACEHOLDER + body.substring(close + 1);
  }

  private static int matchingBrace(String source, int open) {
    int depth = 0;
    for (int i = open; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '{') depth++;
      else if (c == '}') {
        depth--;
        if (depth == 0) return i;
      }
    }
    return -1;
  }

  /**
   * 선언 줄 텍스트로 찾은 Java 메서드 본문 — 선언 줄 시작부터 짝 맞는 닫는 중괄호 줄 끝까지
   * (앞의 Javadoc·애노테이션은 범위 밖). 선언 텍스트가 <b>정확히 1개</b>일 것을 요구한다 —
   * 오버로드·접두어 겹침으로 엉뚱한 메서드를 비교하는 일을 막는다.
   */
  private static String javaMethodBody(String source, String where, String declaration) {
    int occurrences = countOccurrences(source, declaration);
    if (occurrences != 1) {
      return fail(where + "에서 선언 [" + declaration + "]을 정확히 1개 찾지 못했다(찾은 수="
          + occurrences + ") — 이름·시그니처가 바뀌었으니 G-02대로 멈춘다");
    }
    int at = source.indexOf(declaration);
    int lineStart = source.lastIndexOf('\n', at) + 1;
    int open = source.indexOf('{', at);
    if (open < 0) return fail(where + ": [" + declaration + "] 본문 여는 중괄호를 찾지 못했다");
    int close = matchingBrace(source, open);
    if (close < 0) return fail(where + ": [" + declaration + "] 중괄호 균형이 맞지 않는다");
    int lineEnd = source.indexOf('\n', close);
    return source.substring(lineStart, lineEnd < 0 ? source.length() : lineEnd);
  }

  /**
   * {@code function 이름(} 또는 {@code async function 이름(} 선언부터 짝 맞는 닫는 중괄호까지.
   * 선례 {@code PauseSettledFrontGatingContractTest.extractFunctionBody}와 같은 방식이다.
   */
  private static String jsFunctionBody(String source, String where, String name) {
    List<Integer> starts = new ArrayList<>();
    for (String prefix : List.of("\nfunction " + name + "(", "\nasync function " + name + "(")) {
      int from = 0;
      while (true) {
        int at = source.indexOf(prefix, from);
        if (at < 0) break;
        starts.add(at + 1);
        from = at + 1;
      }
    }
    if (starts.size() != 1) {
      return fail(where + "에서 function " + name + "( 선언을 정확히 1개 찾지 못했다(찾은 수="
          + starts.size() + ")");
    }
    int declAt = starts.get(0);
    int open = source.indexOf('{', declAt);
    if (open < 0) return fail(where + ": function " + name + " 본문 여는 중괄호를 찾지 못했다");
    int close = matchingBrace(source, open);
    if (close < 0) return fail(where + ": function " + name + "의 중괄호 균형이 맞지 않는다");
    return source.substring(declAt, close + 1);
  }

  /** 문자열·문자 리터럴은 남기고 주석만 공백으로 지운다(집계가 설명 문장을 세지 않게). */
  private static String blankComments(String source) {
    char[] out = source.toCharArray();
    boolean inString = false;
    boolean inChar = false;
    int i = 0;
    while (i < out.length) {
      char c = out[i];
      if (inString || inChar) {
        if (c == '\\') { i += 2; continue; }
        if (inString && c == '"') inString = false;
        else if (inChar && c == '\'') inChar = false;
        i++;
        continue;
      }
      if (c == '"') { inString = true; i++; continue; }
      if (c == '\'') { inChar = true; i++; continue; }
      if (c == '/' && i + 1 < out.length && out[i + 1] == '/') {
        while (i < out.length && out[i] != '\n') { out[i] = ' '; i++; }
        continue;
      }
      if (c == '/' && i + 1 < out.length && out[i + 1] == '*') {
        while (i < out.length && !(out[i] == '*' && i + 1 < out.length && out[i + 1] == '/')) {
          if (out[i] != '\n') out[i] = ' ';
          i++;
        }
        if (i + 1 < out.length) { out[i] = ' '; out[i + 1] = ' '; i += 2; }
        continue;
      }
      i++;
    }
    return new String(out);
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int from = 0;
    while (true) {
      int at = haystack.indexOf(needle, from);
      if (at < 0) return count;
      count++;
      from = at + needle.length();
    }
  }

  private static String mutateOneChar(String body) {
    int at = body.length() / 2;
    char original = body.charAt(at);
    char replacement = original == 'X' ? 'Y' : 'X';
    return body.substring(0, at) + replacement + body.substring(at + 1);
  }

  private static String shortName(String declaration) {
    int open = declaration.indexOf('(');
    String head = open < 0 ? declaration : declaration.substring(0, open);
    int space = head.lastIndexOf(' ');
    return (space < 0 ? head : head.substring(space + 1))
        + (open < 0 ? "" : declaration.substring(open, Math.min(declaration.length(), open + 40)));
  }

  private static String firstLine(String body) {
    int at = body.indexOf('\n');
    return (at < 0 ? body : body.substring(0, at)).trim();
  }

  private static String lastLine(String body) {
    int at = body.lastIndexOf('\n');
    return (at < 0 ? body : body.substring(at + 1)).trim();
  }

  private static String firstDifference(String expected, String actual) {
    int limit = Math.min(expected.length(), actual.length());
    for (int i = 0; i < limit; i++) {
      if (expected.charAt(i) != actual.charAt(i)) {
        int from = Math.max(0, i - 40);
        return "offset " + i + " 부근 기대=[" + expected.substring(from, Math.min(expected.length(), i + 40))
            + "] 실측=[" + actual.substring(from, Math.min(actual.length(), i + 40)) + "]";
      }
    }
    return "길이만 다르다: 기대 " + expected.length() + " / 실측 " + actual.length();
  }

  private static String normalizeEol(String text) {
    return text.replace("\r\n", "\n");
  }

  private static String readCurrentSource(String relative) {
    Path file = locate(relative);
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return fail("감시 대상 소스를 읽지 못했다: " + file + " (" + e + ")");
    }
  }

  /** 고정본을 클래스패스에서 읽고 {@code git hash-object} 값(C20)으로 바이트 동일성을 먼저 확인한다. */
  private static String readFixture(String resource, String expectedBlobSha1) throws Exception {
    byte[] bytes;
    try (InputStream in = CollectZeroUnchangedMethodsGuardTest.class.getResourceAsStream(resource)) {
      if (in == null) {
        return fail("착수 커밋 고정본이 클래스패스에 없다: " + resource + " — 비교의 기준이 없다.");
      }
      bytes = in.readAllBytes();
    }
    String actual = gitBlobSha1(bytes);
    System.out.println("[고정본] " + resource + " → blob=" + actual + " (" + bytes.length + " B)");
    assertEquals(expectedBlobSha1, actual,
        "고정본이 착수 커밋 blob과 바이트 단위로 같아야 한다: " + resource);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static String gitBlobSha1(byte[] content) throws Exception {
    java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
    sha1.update(("blob " + content.length + "\0").getBytes(StandardCharsets.UTF_8));
    sha1.update(content);
    StringBuilder sb = new StringBuilder();
    for (byte b : sha1.digest()) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  private static Path locate(String relative) {
    Path cursor = Path.of("").toAbsolutePath();
    while (cursor != null) {
      Path candidate = cursor.resolve(relative);
      if (Files.isRegularFile(candidate)) return candidate;
      cursor = cursor.getParent();
    }
    return fail("파일을 찾지 못했다: " + relative + " (cwd=" + Path.of("").toAbsolutePath() + ")");
  }
}
