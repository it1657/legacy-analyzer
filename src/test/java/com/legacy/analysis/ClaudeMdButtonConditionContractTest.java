package com.legacy.analysis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-004 (work-order 2026-10-collect-zero-and-claudemd-button v1, 게이트1 D4) —
 * <b>두 목록 화면의 {@code 📝 CLAUDE.md} 버튼 노출 조건이 한 벌로 유지되는지를 소스 텍스트로 고정하는
 * 계약 테스트</b>. 프런트 소스({@code templates/my-activity.html}·{@code templates/admin/dashboard.html})를
 * <b>텍스트로 읽어</b> 검사하며 브라우저 실행은 모델링하지 않는다.
 *
 * <p><b>무엇을 고정하는가</b> — 네 가지다.
 * <ol>
 *   <li>CLAUDE.md 버튼을 덧붙이는 블록은 <b>화면당 정확히 1개</b>다(사본이 늘면 한쪽만 고쳐 벌어진다).</li>
 *   <li>두 화면의 조건 문자열이 <b>공백 정규화 후 같다</b>(한쪽만 고치는 이력이 반복돼 왔다).</li>
 *   <li>그 조건은 {@code h.hasClaudeMd && (h.status === 'COMPLETED' || h.status === 'PAUSED')}다 —
 *       CLAUDE.md는 파일 처리 시작 전에 생성되므로 취소·실패한 분석에도 내용이 남는다. 상태 조건이
 *       없으면 결과물이 없는 행에도 버튼이 뜬다.</li>
 *   <li>버튼은 {@code actionBtn(s) +=}로 <b>덧붙인다</b> — {@code =} 대입으로 바뀌면 앞서 쌓인
 *       재개 버튼·경고·안내가 통째로 지워진다.</li>
 * </ol>
 *
 * <p><b>이 테스트가 못 잡는 것(범위 한계)</b>: 조건 블록 <b>안쪽의 런타임 로직</b>이다. 예를 들어 블록
 * 안에서 {@code pauseSettled === false}일 때 행동 칸을 비워 버리는 변이는 문면상 조건·덧붙임 형태가
 * 그대로라 여기서 걸리지 않는다. 그런 유형과 "조건이 바뀌어 특정 상태 행에서 버튼이 뜨거나 사라지는"
 * 실제 렌더 결과는 node 하네스({@code src/test/js/historyListSourceBadgeHarness.js}, 케이스 S5a~S5e)가
 * 본다. 두 장치는 역할이 다르며 서로를 대체하지 않는다 — 이 테스트는 <b>텍스트 대칭·단일 출처</b>,
 * 하네스는 <b>실행 결과</b>를 담당한다. CSS·실제 클릭은 둘 다 보지 않는다(실브라우저 관측 몫).
 *
 * <p><b>검사기는 실제 파일과 합성 입력에 똑같이 쓴다</b>(양성 대조군): 현재 템플릿 텍스트를 치환한
 * 합성 입력(A4·A5·A6·A9 + 덧붙임 변이)을 같은 {@link #collectViolations} 함수에 넣어 위반이 실제로
 * 검출되는지 단언한다. 이게 없으면 "검사기가 아무것도 안 찾아서 위반 0"인 경우와 구분되지 않는다.
 * 고정본 리소스 파일은 만들지 않는다(합성 입력으로 충분하다).
 */
class ClaudeMdButtonConditionContractTest {

  private static final String MY_ACTIVITY = "src/main/resources/templates/my-activity.html";
  private static final String ADMIN_DASHBOARD = "src/main/resources/templates/admin/dashboard.html";

  /** 버튼 라벨 — 하네스(S4·S5)가 쓰는 것과 같은 표지다. */
  private static final String BUTTON_LABEL = "📝 CLAUDE.md";

  /** 두 화면이 공유해야 하는 조건(공백 정규화 기준). */
  private static final String EXPECTED_CONDITION =
      "h.hasClaudeMd && (h.status === 'COMPLETED' || h.status === 'PAUSED')";

  // ════════════════════════════════════════════════════════════════════════════
  // 실제 파일
  // ════════════════════════════════════════════════════════════════════════════

  @Test
  void 두_화면의_CLAUDE_md_버튼_블록은_각_1개이고_조건과_덧붙임_형태가_한_벌로_같다() {
    String my = readOrFail(locate(MY_ACTIVITY));
    String admin = readOrFail(locate(ADMIN_DASHBOARD));

    List<Block> myBlocks = findButtonBlocks(my);
    List<Block> adminBlocks = findButtonBlocks(admin);
    System.out.println("[TASK-004 계약] my-activity 조건 = " + describe(myBlocks));
    System.out.println("[TASK-004 계약] admin/dashboard 조건 = " + describe(adminBlocks));

    List<String> violations = collectViolations(my, admin);
    assertEquals(List.of(), violations, "CLAUDE.md 버튼 조건 계약 위반. violations=" + violations);

    // 위 violations가 비어 있다는 것만으로는 "블록을 못 찾아서 조용히 통과"와 구분되지 않으므로
    // 블록이 실제로 1개씩 잡혔다는 것과 그 조건 원문을 함께 못박는다.
    assertEquals(1, myBlocks.size(), "my-activity의 CLAUDE.md 버튼 블록 수");
    assertEquals(1, adminBlocks.size(), "admin/dashboard의 CLAUDE.md 버튼 블록 수");
    assertEquals(EXPECTED_CONDITION, myBlocks.get(0).condition(), "my-activity 조건 원문");
    assertEquals(EXPECTED_CONDITION, adminBlocks.get(0).condition(), "admin/dashboard 조건 원문");
    assertTrue(myBlocks.get(0).appendLine().startsWith("actionBtn +="),
        "my-activity는 덧붙임이어야 한다: " + myBlocks.get(0).appendLine());
    assertTrue(adminBlocks.get(0).appendLine().startsWith("actionBtns +="),
        "admin/dashboard는 덧붙임이어야 한다: " + adminBlocks.get(0).appendLine());
  }

  // ════════════════════════════════════════════════════════════════════════════
  // 양성 대조군 — 같은 검사기를 합성 입력에 적용하면 위반이 잡힌다
  // ════════════════════════════════════════════════════════════════════════════

  /** A4 — 상태 조건에서 {@code COMPLETED}를 지우면(두 화면 모두) 조건 원문 단언이 잡는다. */
  @Test
  void 대조군_A4_상태조건에서_COMPLETED를_지운_합성입력은_위반으로_걸린다() {
    String my = mutateCondition(readOrFail(locate(MY_ACTIVITY)),
        "h.hasClaudeMd && (h.status === 'PAUSED')");
    String admin = mutateCondition(readOrFail(locate(ADMIN_DASHBOARD)),
        "h.hasClaudeMd && (h.status === 'PAUSED')");
    assertDetects("A4", my, admin);
  }

  /** A5 — {@code h.hasClaudeMd &&}를 지우면(상시 노출) 조건 원문 단언이 잡는다. */
  @Test
  void 대조군_A5_hasClaudeMd_절을_지운_합성입력은_위반으로_걸린다() {
    String my = mutateCondition(readOrFail(locate(MY_ACTIVITY)),
        "(h.status === 'COMPLETED' || h.status === 'PAUSED')");
    String admin = mutateCondition(readOrFail(locate(ADMIN_DASHBOARD)),
        "(h.status === 'COMPLETED' || h.status === 'PAUSED')");
    assertDetects("A5", my, admin);
  }

  /** A6 — 괄호 안에 {@code CANCELLED}를 더하면(취소 행에도 노출) 잡는다. */
  @Test
  void 대조군_A6_조건에_CANCELLED를_더한_합성입력은_위반으로_걸린다() {
    String mutated = "h.hasClaudeMd && (h.status === 'COMPLETED' || h.status === 'PAUSED'"
        + " || h.status === 'CANCELLED')";
    String my = mutateCondition(readOrFail(locate(MY_ACTIVITY)), mutated);
    String admin = mutateCondition(readOrFail(locate(ADMIN_DASHBOARD)), mutated);
    assertDetects("A6", my, admin);
  }

  /** A9 — <b>한 화면만</b> 고친 경우(두 화면 비대칭). 대칭 단언이 없으면 놓치는 유형이다. */
  @Test
  void 대조군_A9_admin만_고친_합성입력은_두_화면_불일치로_걸린다() {
    String my = readOrFail(locate(MY_ACTIVITY));
    String admin = mutateCondition(readOrFail(locate(ADMIN_DASHBOARD)),
        "h.hasClaudeMd && (h.status === 'COMPLETED' || h.status === 'PAUSED'"
            + " || h.status === 'CANCELLED')");
    List<String> violations = assertDetects("A9", my, admin);
    assertTrue(violations.stream().anyMatch(v -> v.contains("두 화면")),
        "한 화면만 고친 경우는 '두 화면' 불일치로도 잡혀야 한다. violations=" + violations);
  }

  /** 보강 대조군 — 덧붙임({@code +=})을 대입({@code =})으로 바꾸면 네 번째 검사가 잡는다. */
  @Test
  void 대조군_덧붙임을_대입으로_바꾼_합성입력은_위반으로_걸린다() {
    String my = readOrFail(locate(MY_ACTIVITY))
        .replace("actionBtn += `<button class=\"btn-sm btn-claudemd\"",
            "actionBtn = `<button class=\"btn-sm btn-claudemd\"");
    String admin = readOrFail(locate(ADMIN_DASHBOARD));
    List<String> violations = assertDetects("덧붙임→대입", my, admin);
    assertTrue(violations.stream().anyMatch(v -> v.contains("덧붙임")),
        "덧붙임 위반으로 잡혀야 한다. violations=" + violations);
  }

  private List<String> assertDetects(String label, String my, String admin) {
    List<String> violations = collectViolations(my, admin);
    System.out.println("[TASK-004 대조군 " + label + "] violations=" + violations);
    assertTrue(!violations.isEmpty(),
        "합성 입력(" + label + ")에서 같은 검사기가 위반을 검출해야 한다 — 검출 0건이면 "
            + "이 검사기는 실제 사고도 못 잡는다");
    return violations;
  }

  // ════════════════════════════════════════════════════════════════════════════
  // 검사기 (실제 파일과 합성 입력에 똑같이 쓴다)
  // ════════════════════════════════════════════════════════════════════════════

  /** CLAUDE.md 버튼을 덧붙이는 블록 하나 — 조건 문자열과 조건 바로 다음 줄. */
  private record Block(String condition, String appendLine) {}

  private static List<String> collectViolations(String myHtml, String adminHtml) {
    List<String> violations = new ArrayList<>();
    List<Block> my = findButtonBlocks(myHtml);
    List<Block> admin = findButtonBlocks(adminHtml);

    // ① 화면당 정확히 1개
    if (my.size() != 1) violations.add("my-activity의 CLAUDE.md 버튼 블록이 " + my.size() + "개다(1개를 기대)");
    if (admin.size() != 1) {
      violations.add("admin/dashboard의 CLAUDE.md 버튼 블록이 " + admin.size() + "개다(1개를 기대)");
    }
    if (my.size() != 1 || admin.size() != 1) return violations;

    String myCondition = my.get(0).condition();
    String adminCondition = admin.get(0).condition();

    // ② 두 화면 조건이 같다
    if (!myCondition.equals(adminCondition)) {
      violations.add("두 화면 조건이 다르다 — my=[" + myCondition + "] admin=[" + adminCondition + "]");
    }
    // ③ 조건 문자열이 정본과 같다
    if (!EXPECTED_CONDITION.equals(myCondition)) {
      violations.add("my-activity 조건이 정본과 다르다 — 기대=[" + EXPECTED_CONDITION
          + "] 실제=[" + myCondition + "]");
    }
    if (!EXPECTED_CONDITION.equals(adminCondition)) {
      violations.add("admin/dashboard 조건이 정본과 다르다 — 기대=[" + EXPECTED_CONDITION
          + "] 실제=[" + adminCondition + "]");
    }
    // ④ 조건 다음 줄이 덧붙임이다
    if (!my.get(0).appendLine().startsWith("actionBtn +=")) {
      violations.add("my-activity가 덧붙임(actionBtn +=)이 아니다 — 실제=[" + my.get(0).appendLine() + "]");
    }
    if (!admin.get(0).appendLine().startsWith("actionBtns +=")) {
      violations.add("admin/dashboard가 덧붙임(actionBtns +=)이 아니다 — 실제=["
          + admin.get(0).appendLine() + "]");
    }
    return violations;
  }

  /**
   * {@link #BUTTON_LABEL}이 들어간 줄마다 그 줄을 감싸는 가장 가까운 윗 {@code if (…) {} 줄을 찾아
   * 조건과 "조건 바로 다음 줄"을 뽑는다. 조건을 감싼 {@code if}를 못 찾으면 그 사실 자체를 블록으로
   * 남겨(조건 {@code (없음)}) 상위 검사가 위반으로 보게 한다.
   */
  private static List<Block> findButtonBlocks(String html) {
    String[] lines = html.replace("\r\n", "\n").split("\n", -1);
    List<Block> blocks = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      if (!lines[i].contains(BUTTON_LABEL)) continue;
      int conditionIndex = -1;
      for (int j = i; j >= 0; j--) {
        String trimmed = lines[j].trim();
        if (trimmed.startsWith("if (") && trimmed.endsWith(") {")) {
          conditionIndex = j;
          break;
        }
      }
      if (conditionIndex < 0) {
        blocks.add(new Block("(없음)", "(없음)"));
        continue;
      }
      String trimmed = lines[conditionIndex].trim();
      String condition = normalize(trimmed.substring("if (".length(),
          trimmed.length() - ") {".length()));
      String next = conditionIndex + 1 < lines.length ? lines[conditionIndex + 1].trim() : "";
      blocks.add(new Block(condition, next));
    }
    return blocks;
  }

  /** 들여쓰기·연속 공백 차이로 "다르다"가 나오지 않게 공백만 정규화한다. */
  private static String normalize(String text) {
    return text.trim().replaceAll("\\s+", " ");
  }

  /** 현재 텍스트의 조건식만 바꾼 합성 입력을 만든다(실제 파일은 읽기만 한다). */
  private static String mutateCondition(String html, String newCondition) {
    String target = "if (" + EXPECTED_CONDITION + ") {";
    if (!html.contains(target)) {
      return fail("합성 입력을 만들 조건 줄을 찾지 못했다 — 조건 문면이 바뀌었으면 멈추고 보고한다: "
          + target);
    }
    return html.replace(target, "if (" + newCondition + ") {");
  }

  private static String describe(List<Block> blocks) {
    if (blocks.isEmpty()) return "(블록 없음)";
    StringBuilder sb = new StringBuilder();
    for (Block b : blocks) {
      sb.append("[조건=").append(b.condition()).append(" / 다음줄=").append(b.appendLine()).append("]");
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
    return fail("감시 대상 소스를 찾지 못했다: " + relative);
  }

  private static String readOrFail(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return fail("감시 대상 소스를 읽지 못했다: " + path + " (" + e + ")");
    }
  }
}
