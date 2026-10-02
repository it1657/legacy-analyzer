package com.legacy.analysis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-009 (work-order 2026-09-remaining-ux-fixes v4, REQ-002 프런트) —
 * <b>재개 진입점 3곳의 {@code pauseSettled} 게이팅을 정적으로 단언</b>하는 계약 테스트.
 * 프런트 소스(HTML/JS)를 <b>텍스트로 읽어</b> 검사한다. 브라우저 실행은 모델링하지 않는다 —
 * 실브라우저 관측(DoD 2~5)은 별도 절차(§A.4)로 사람이 로그인한 세션에서 수행한다.
 *
 * <h2>무엇을 단언하는가</h2>
 * <ol>
 *   <li><b>DoD 1</b> — 목록 화면 ②({@code my-activity.html} {@code renderHistory()})와
 *       ③({@code admin/dashboard.html} {@code renderMyHistory()})의 PAUSED 판정식 2개와
 *       미확정 안내 문면이 <b>문자 단위로 동일</b>하다. 이 저장소는 두 화면이 한쪽만 고쳐져 벌어진 이력이
 *       반복됐다(work-order TASK-009 DoD 1). v4부터 안내 문면도 동일성 대상이다.</li>
 *   <li><b>DoD 7</b> — 판정식이 {@code h.pauseSettled !== false} 형태라, 응답에 필드가 없는
 *       구버전 응답에서도 기존 동작(버튼 노출)이 유지된다.</li>
 *   <li><b>DoD 10 (v4)</b> — 안내 문면이 "새로 고치면 반드시 재개된다"를 단정하지 않고,
 *       해소되지 않을 때의 대안(처음부터 새로 분석)을 함께 제시한다.</li>
 *   <li><b>DoD 11 (v4)</b> — 자동 폴링·타이머 재시도·재개 API 자동 호출이 도입되지 않았다
 *       ({@code setInterval}/{@code setTimeout} 호출 수가 TASK-009 착수 전 기준선과 같다).</li>
 *   <li><b>DoD 8</b> (2026-10 TASK-008로 교체 — REQ-003 / 게이트1 D4) —
 *       {@code handleAnalysisPaused()}가 <b>확정된 일시정지에서는 제어 패널과 세션 식별자를 유지</b>하고,
 *       그 밖의 경우(failover 컨펌 거절·실패, 확정 대기 상한 초과)에는 <b>기존 세션 정리 3줄을 그대로</b>
 *       쓴다. 패널 숨김 줄이 무조건 실행되는 위치에 없는지도 함께 본다.
 *       <br>종전 DoD 8은 "세션 정리 3줄이 그대로다(게이트1 ④ 범위 한계)"였다 — 게이트1 D4로
 *       "분석 화면에서 바로 이어서 분석한다"가 확정되면서 그 범위 한계가 해소됐다.</li>
 *   <li><b>①</b> — {@code updateSessionControlPanel()}의 재개 버튼 노출 판단이
 *       {@code isPausedLocally && lastPolledPauseSettled}로 게이팅되고, 그 값은 폴링 응답의
 *       {@code status.pauseSettled !== false}에서 온다.</li>
 * </ol>
 *
 * <h2>양성 대조군 (§A.2 의무 — 정적 구조 단언의 탐지력)</h2>
 * TASK-009 착수 전 v1 문면("잠시 후 목록을 새로 고치면 재개할 수 있습니다")과, 한 글자만 다른 문면을
 * 같은 판정 함수에 넣어 <b>실제로 걸러짐</b>을 보인다({@link #대조군_v1_문면과_한_글자_차이는_같은_판정에_걸린다}).
 * 판정 함수가 무엇이든 통과시키는 빈 검사가 아님을 이 케이스가 증명한다.
 */
class PauseSettledFrontGatingContractTest {

  private static final String MY_ACTIVITY = "src/main/resources/templates/my-activity.html";
  private static final String ADMIN_DASHBOARD = "src/main/resources/templates/admin/dashboard.html";
  private static final String INDEX = "src/main/resources/templates/index.html";
  private static final String DASHBOARD_JS = "src/main/resources/static/js/dashboard.js";

  /**
   * TASK-009 착수 전(커밋 {@code 5380314}) 각 파일의 {@code setInterval(}/{@code setTimeout(} 호출 수.
   * DoD 11 — 이 TASK는 목록 화면에 자동 폴링·타이머 재시도를 도입하지 않는다(설계 §4.3 확정).
   * 다른 TASK가 정당하게 타이머를 추가하면 그 TASK에서 이 기준선을 갱신하고 사유를 남긴다.
   */
  private static final int TIMER_BASELINE_MY_ACTIVITY = 1;
  private static final int TIMER_BASELINE_ADMIN_DASHBOARD = 4;
  private static final int TIMER_BASELINE_INDEX = 0;
  private static final int TIMER_BASELINE_DASHBOARD_JS = 4;

  /** work-order TASK-009 작업 1이 지정한 판정식(②③ 공통, 문자 단위). */
  private static final String EXPECTED_SETTLED_CONDITION =
      "h.status === 'PAUSED' && h.sessionId && h.pauseSettled !== false";
  private static final String EXPECTED_UNSETTLED_CONDITION =
      "h.status === 'PAUSED' && h.sessionId";

  /** TASK-009 착수 전 v1 안내 문면(수동 갱신만 유도, 대안 없음) — 대조군 전용. 프로덕션에는 남아 있지 않다. */
  private static final String V1_HINT_WITHOUT_ALTERNATIVE = "잠시 후 목록을 새로 고치면 재개할 수 있습니다";

  // ---------------------------------------------------------------------------------------------
  // DoD 1 / 7 / 10 — 목록 화면 ②③
  // ---------------------------------------------------------------------------------------------

  @Test
  void DoD1_목록화면_2와3의_PAUSED_판정식_2개가_문자_단위로_동일하다() {
    PausedBranch mine = extractPausedBranch(readOrFail(locate(MY_ACTIVITY)), MY_ACTIVITY);
    PausedBranch admin = extractPausedBranch(readOrFail(locate(ADMIN_DASHBOARD)), ADMIN_DASHBOARD);

    assertEquals(mine.settledCondition, admin.settledCondition,
        "②③의 '확정 → 버튼' 판정식이 다르다(한쪽만 고쳐진 이력 재발)");
    assertEquals(mine.unsettledCondition, admin.unsettledCondition,
        "②③의 '미확정 → 안내' 판정식이 다르다");
    assertEquals(EXPECTED_SETTLED_CONDITION, mine.settledCondition, "work-order가 지정한 판정식과 다르다");
    assertEquals(EXPECTED_UNSETTLED_CONDITION, mine.unsettledCondition);
  }

  @Test
  void DoD1_v4_목록화면_2와3의_미확정_안내_문면이_문자_단위로_동일하다() {
    PausedBranch mine = extractPausedBranch(readOrFail(locate(MY_ACTIVITY)), MY_ACTIVITY);
    PausedBranch admin = extractPausedBranch(readOrFail(locate(ADMIN_DASHBOARD)), ADMIN_DASHBOARD);

    assertEquals(mine.settlingMarkup, admin.settlingMarkup,
        "②③의 미확정 안내 문면(템플릿 리터럴 전체)이 다르다 — v4 DoD 1은 문면도 동일성 대상이다");
  }

  @Test
  void DoD7_판정식은_pauseSettled가_없는_구버전_응답에서_기존_동작을_유지하는_형태다() {
    for (String file : List.of(MY_ACTIVITY, ADMIN_DASHBOARD)) {
      PausedBranch b = extractPausedBranch(readOrFail(locate(file)), file);
      assertTrue(b.settledCondition.contains("h.pauseSettled !== false"),
          file + ": 폴백은 `!== false`여야 한다(undefined → 버튼 노출). 실제: " + b.settledCondition);
      assertFalse(b.settledCondition.contains("pauseSettled === true"),
          file + ": `=== true`는 필드가 없는 응답에서 버튼을 숨겨 기존 동작을 깨뜨린다");
    }
  }

  @Test
  void DoD10_v4_미확정_안내는_반드시_재개된다고_단정하지_않고_대안을_함께_제시한다() {
    for (String file : List.of(MY_ACTIVITY, ADMIN_DASHBOARD)) {
      PausedBranch b = extractPausedBranch(readOrFail(locate(file)), file);
      assertNoHintViolation(b.settlingMarkup, file);
    }
  }

  @Test
  void DoD11_v4_미확정_안내_분기에_자동_폴링_타이머_재개_자동호출이_없고_파일별_타이머_호출수가_기준선과_같다() {
    for (String file : List.of(MY_ACTIVITY, ADMIN_DASHBOARD)) {
      PausedBranch b = extractPausedBranch(readOrFail(locate(file)), file);
      for (String forbidden : List.of("setInterval", "setTimeout", "fetch(", "resumeAnalysis(", "resumeMyAnalysis(")) {
        assertFalse(b.settlingMarkup.contains(forbidden),
            file + ": 미확정 안내 분기에 '" + forbidden + "'가 있다(자동 재개/폴링 금지, 설계 §4.3)");
      }
    }
    assertEquals(TIMER_BASELINE_MY_ACTIVITY, countTimerCalls(readOrFail(locate(MY_ACTIVITY))), MY_ACTIVITY);
    assertEquals(TIMER_BASELINE_ADMIN_DASHBOARD, countTimerCalls(readOrFail(locate(ADMIN_DASHBOARD))), ADMIN_DASHBOARD);
    assertEquals(TIMER_BASELINE_INDEX, countTimerCalls(readOrFail(locate(INDEX))), INDEX);
    assertEquals(TIMER_BASELINE_DASHBOARD_JS, countTimerCalls(readOrFail(locate(DASHBOARD_JS))), DASHBOARD_JS);
  }

  // ---------------------------------------------------------------------------------------------
  // ① 분석 화면 인라인 재개 버튼 + DoD 8
  // ---------------------------------------------------------------------------------------------

  @Test
  void 진입점1_updateSessionControlPanel의_재개버튼_노출은_isPausedLocally와_폴링_pauseSettled로_게이팅된다() {
    String js = readOrFail(locate(DASHBOARD_JS));
    String body = extractFunctionBody(js, "updateSessionControlPanel");

    assertTrue(body.contains("const showResume = isPausedLocally && lastPolledPauseSettled;"),
        "재개 버튼 노출 판단이 isPausedLocally && lastPolledPauseSettled가 아니다:\n" + body);
    assertTrue(body.contains("resumeBtn.style.display = showResume ? 'inline-block' : 'none'"),
        "#resumeBtn 표시가 showResume에 묶여 있지 않다");
    assertTrue(body.contains("const showSettling = isPausedLocally && !lastPolledPauseSettled;")
            && body.contains("settlingNotice.style.display = showSettling ? 'inline-block' : 'none'"),
        "미확정 구간 안내(#pauseSettlingNotice)가 같은 자리에 표시되도록 묶여 있지 않다");
    assertFalse(body.contains("resumeBtn.style.display = isPausedLocally ? 'inline-block' : 'none'"),
        "수정 전 판단식(isPausedLocally만 본다)이 남아 있다");

    // 값의 출처는 폴링 응답이며, 필드가 없으면 true(구버전 폴백)로 읽는다.
    String polling = extractFunctionBody(js, "startPolling");
    assertTrue(polling.contains("const polledPauseSettled = status.pauseSettled !== false;"),
        "폴링 응답의 pauseSettled를 `!== false`로 읽지 않는다");
    assertTrue(polling.contains("lastPolledPauseSettled = true;"),
        "새 폴링 세션마다 확정 여부를 초기화하지 않는다");

    // index.html에 안내 요소가 실제로 존재한다(없으면 getElementById가 null이라 안내가 영영 안 보인다).
    String index = readOrFail(locate(INDEX));
    assertTrue(index.contains("id=\"pauseSettlingNotice\"") && index.contains("id=\"resumeBtn\""),
        "index.html에 #pauseSettlingNotice 또는 #resumeBtn이 없다");
    assertTrue(index.contains(">⏸️ 일시정지 처리 중입니다</span>"), "①의 안내 문구가 ②③과 같은 본문이 아니다");
  }

  /**
   * DoD 8 (2026-10 TASK-008로 교체, REQ-003 / 게이트1 D4) —
   * {@code handleAnalysisPaused()}가 <b>확정된 일시정지에서는 세션을 정리하지 않고</b>,
   * <b>그 밖의 경우에는 기존 정리 3줄을 그대로</b> 쓴다.
   *
   * <p>왜 이 세 가지를 보는가: ⓘ PAUSED 분기에서 {@code currentSessionId}를 버리면 같은 화면의
   * '이어서 분석' 버튼이 누를 대상을 잃는다. ⓘⓘ 그 밖의 경로(failover 컨펌 거절·실패, 확정 대기
   * 상한 초과)에서 정리를 빼면 끝난 세션의 패널이 화면에 남는다. ⓘⓘⓘ 패널 숨김 줄이 함수 앞쪽의
   * 무조건 실행 위치로 되돌아가면 ⓘ이 무의미해진다(패널이 먼저 사라진다).
   */
  @Test
  void DoD8_v2_handleAnalysisPaused는_확정된_일시정지에서만_세션을_유지하고_그_외에는_기존_정리를_한다() {
    String body = extractFunctionBody(readOrFail(locate(DASHBOARD_JS)), "handleAnalysisPaused");

    // ⓘ phase === 'PAUSED' 분기가 있고, 그 분기는 return으로 끝나며 안에서 currentSessionId를 버리지 않는다.
    int pausedBranchAt = body.indexOf("if (status.phase === 'PAUSED'");
    assertTrue(pausedBranchAt >= 0,
        "handleAnalysisPaused()에 phase === 'PAUSED' 분기가 없다 — 확정된 일시정지에서 패널이 유지되지 않는다:\n"
            + body);
    int branchEndAt = body.indexOf("\n  }\n", pausedBranchAt);
    assertTrue(branchEndAt > pausedBranchAt, "phase === 'PAUSED' 분기의 닫는 중괄호를 찾지 못했다");
    String pausedBranch = body.substring(pausedBranchAt, branchEndAt);
    assertFalse(pausedBranch.contains("currentSessionId = null"),
        "확정된 일시정지 분기에서 currentSessionId를 버리면 같은 화면의 재개 버튼이 대상을 잃는다. 분기 본문:\n"
            + pausedBranch);
    assertTrue(pausedBranch.contains("return;"),
        "확정된 일시정지 분기가 return으로 끝나지 않으면 아래 정리 코드가 이어서 실행돼 패널이 사라진다:\n"
            + pausedBranch);

    // ⓘⓘ 그 외 경로(분기 뒤 fall-through)에는 기존 정리 3줄이 그 순서·그 모양 그대로 있다.
    // 이 3줄은 함수의 마지막 3줄이어야 한다 — 같은 단언이 CompletionPanelCounterSingleSourceContractTest
    // (G-07 화이트리스트 밖, 무수정 보호 대상)에도 있으므로 그 모양을 유지하는 것이 계약이다.
    String fallThrough = body.substring(branchEndAt);
    String cleanup = "  clearSessionFromStorage();\n  currentSessionId = null;\n  updateSessionControlPanel();\n}";
    assertTrue(body.endsWith(cleanup),
        "함수가 기존 세션 정리 3줄(clearSessionFromStorage / currentSessionId = null /"
            + " updateSessionControlPanel)로 끝나야 한다. 실제 끝부분:\n"
            + body.substring(Math.max(0, body.length() - 300)));
    assertTrue(fallThrough.contains("if (sessionControlPanel) sessionControlPanel.style.display = \"none\";"),
        "그 외 경로에서 패널을 숨기지 않는다 — 끝난 세션의 패널이 화면에 남는다");
    assertTrue(fallThrough.contains("isPausedLocally = false;"),
        "그 외 경로에서 isPausedLocally를 내리지 않으면 다음 분석의 버튼 판정이 오염된다");

    // ⓘⓘⓘ 패널 숨김 줄이 무조건 실행되는 위치(분기보다 앞)에 없다.
    String beforeBranch = body.substring(0, pausedBranchAt);
    assertFalse(beforeBranch.contains("sessionControlPanel.style.display = \"none\""),
        "패널 숨김 줄이 분기보다 앞(무조건 실행 위치)에 있다 — 확정된 일시정지에서도 패널이 먼저 사라진다:\n"
            + beforeBranch);
  }

  // ---------------------------------------------------------------------------------------------
  // 양성 대조군 — 판정 함수의 탐지력
  // ---------------------------------------------------------------------------------------------

  @Test
  void 대조군_v1_문면과_한_글자_차이는_같은_판정에_걸린다() {
    PausedBranch admin = extractPausedBranch(readOrFail(locate(ADMIN_DASHBOARD)), ADMIN_DASHBOARD);

    // (i) 착수 전 v1 문면(대안 없음)은 DoD 10 판정에 걸린다.
    String v1Markup = "<span class=\"pause-settling\">⏸️ 일시정지 처리 중입니다</span>"
        + "<span class=\"pause-settling-hint\">" + V1_HINT_WITHOUT_ALTERNATIVE + "</span>";
    List<String> v1Violations = hintViolations(v1Markup);
    assertFalse(v1Violations.isEmpty(), "v1 문면이 DoD 10 판정을 통과해 버렸다 — 판정 함수가 비어 있다");

    // (ii) "반드시"를 단정하는 문면도 걸린다.
    List<String> assertive = hintViolations(admin.settlingMarkup.replace("나타납니다.", "반드시 나타납니다."));
    assertFalse(assertive.isEmpty(), "'반드시' 단정 문면이 DoD 10 판정을 통과했다");

    // (iii) 한 글자만 달라도 동일성 단언(DoD 1)은 실패한다 — 문자 단위 비교임을 보인다.
    String mutated = admin.settlingMarkup.replace("처리 중입니다", "처리 중입니다.");
    assertNotEquals(admin.settlingMarkup, mutated);
    assertEquals(admin.settlingMarkup.length() + 1, mutated.length(), "대조군 변형이 한 글자 차이가 아니다");

    // (iv) 추출기는 파일당 정확히 한 분기만 찾는다 — 두 분기가 있는 합성 입력은 명시적으로 실패한다.
    String duplicated = readOrFail(locate(ADMIN_DASHBOARD));
    int at = duplicated.indexOf("} else if (" + EXPECTED_SETTLED_CONDITION + ") {");
    String twice = duplicated + "\n" + duplicated.substring(at);
    try {
      extractPausedBranch(twice, "합성-중복");
      fail("판정 분기가 두 번 나오는 입력을 추출기가 통과시켰다");
    } catch (AssertionError expected) {
      assertTrue(expected.getMessage().contains("정확히 1회"), expected.getMessage());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 도우미
  // ---------------------------------------------------------------------------------------------

  /** ②③ 공통 구조: `else if (확정조건) { … 버튼 … } else if (미확정조건) { … 안내 템플릿 리터럴 … }` */
  private record PausedBranch(String settledCondition, String unsettledCondition, String settlingMarkup) {}

  private static final Pattern PAUSED_BRANCH = Pattern.compile(
      "\\} else if \\((?<settled>[^\\n]*?h\\.pauseSettled[^\\n]*?)\\) \\{\\s*\\n"
          + "\\s*actionBtns? = `<button[^\\n]*이어서 분석</button>`;\\s*\\n"
          + "\\s*\\} else if \\((?<unsettled>[^\\n]*?)\\) \\{\\s*\\n"
          + "(?<comments>(?:\\s*//[^\\n]*\\n)*)"
          + "\\s*actionBtns? = `(?<markup><span class=\"pause-settling\"[^\\n]*)`;");

  private static PausedBranch extractPausedBranch(String source, String label) {
    Matcher m = PAUSED_BRANCH.matcher(source);
    List<PausedBranch> found = new ArrayList<>();
    while (m.find()) {
      found.add(new PausedBranch(m.group("settled"), m.group("unsettled"), m.group("markup")));
    }
    if (found.size() != 1) {
      return fail(label + ": PAUSED 판정 분기(확정 → 버튼 / 미확정 → 안내)가 정확히 1회 나와야 한다. 실제 " + found.size()
          + "회 — 구조가 바뀌었으면 이 테스트의 추출 패턴부터 다시 맞춰라(읽지 못한 채 통과시키지 않는다)");
    }
    return found.get(0);
  }

  /** DoD 10 — 단정 금지 + 대안 제시 + 본문 유지. 위반 목록이 비어 있으면 통과. */
  private static List<String> hintViolations(String markup) {
    List<String> v = new ArrayList<>();
    if (!markup.contains("⏸️ 일시정지 처리 중입니다")) v.add("본문 '⏸️ 일시정지 처리 중입니다'가 없다");
    if (!markup.contains("새로 고")) v.add("수동 갱신 유도 문구가 없다");
    if (!markup.contains("처음부터 새로 분석")) v.add("해소되지 않을 때의 대안(처음부터 새로 분석)이 없다(v4 DoD 10)");
    if (markup.contains("반드시")) v.add("'반드시'로 재개를 단정한다(v4 DoD 10)");
    if (markup.matches("(?s).*새로 고치면 재개할 수 있습니다.*")) v.add("'새로 고치면 재개할 수 있습니다'는 재개를 단정하는 v1 문면이다");
    return v;
  }

  private static void assertNoHintViolation(String markup, String label) {
    List<String> v = hintViolations(markup);
    assertTrue(v.isEmpty(), label + ": " + String.join(" / ", v) + "\n문면: " + markup);
  }

  private static int countTimerCalls(String source) {
    Matcher m = Pattern.compile("\\bset(Interval|Timeout)\\(").matcher(source);
    int n = 0;
    while (m.find()) n++;
    return n;
  }

  /** `function name(` 선언부부터 중괄호 균형이 맞는 곳까지(선언부의 여는 중괄호 포함, 닫는 중괄호 포함). */
  private static String extractFunctionBody(String source, String name) {
    Pattern decl = Pattern.compile("(?m)^function " + Pattern.quote(name) + "\\(");
    Matcher m = decl.matcher(source);
    if (!m.find()) return fail("dashboard.js에서 function " + name + "( 선언을 찾지 못했다");
    int open = source.indexOf('{', m.end());
    int depth = 0;
    for (int i = open; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '{') depth++;
      else if (c == '}') {
        depth--;
        if (depth == 0) return source.substring(open, i + 1);
      }
    }
    return fail("function " + name + "의 중괄호 균형이 맞지 않는다");
  }

  private static Path locate(String relative) {
    Path cursor = Path.of("").toAbsolutePath();
    while (cursor != null) {
      Path candidate = cursor.resolve(relative);
      if (Files.isRegularFile(candidate)) return candidate;
      cursor = cursor.getParent();
    }
    return fail("감시 대상 소스를 찾지 못했다: " + relative + " (탐색 시작=" + Path.of("").toAbsolutePath() + ")");
  }

  private static String readOrFail(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return fail("감시 대상 소스를 읽지 못했다: " + path + " (" + e + ")");
    }
  }
}
