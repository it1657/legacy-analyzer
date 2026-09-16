package com.legacy.analysis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-010 (work-order 2026-09-remaining-ux-fixes v1 원문 정본, REQ-004 / 게이트1 ⑫) —
 * <b>전량실패 PAUSED 완료 패널 카운터의 "단일 출처"를 정적으로 단언</b>하는 계약 테스트.
 * 프런트 소스(dashboard.js / index.html)를 <b>텍스트로 읽어</b> 검사한다. 브라우저 실행은 모델링하지 않는다 —
 * JS 분기·데이터 흐름은 {@code src/test/js/dashboardPausedAllFailedHarness.js}(node:vm)가, 실화면은
 * 실브라우저 관측(§A.4, DoD 1·2·7)이 맡는다.
 *
 * <h2>무엇을 단언하는가</h2>
 * <ol>
 *   <li><b>DoD 3 (게이트1 ⑫)</b> — {@code cr_success}/{@code cr_already}/{@code cr_failed}에 값을 대입하는 지점이
 *       {@code dashboard.js} 전체에서 <b>각 1개소</b>이고, 그 1개소는 {@code fillCompletionResultPanel()} 안에 있다.
 *       정상 완료({@code showCompletionResult})와 전량실패 PAUSED({@code showAllFailedPausedResult})는 둘 다 그 함수를
 *       호출할 뿐 카운터를 직접 채우지 않는다.</li>
 *   <li><b>작업 3</b> — 전량실패 판별식이 서버 {@code runAnalysis()}의 전량실패 분기와 같은 형태
 *       ({@code successCount === 0 && alreadyCount === 0 && failedCount > 0})다.</li>
 *   <li><b>작업 1</b> — {@code handleAnalysisPaused()}가 그 판별을 거쳐 {@code showAllFailedPausedResult()}를 호출한다.</li>
 *   <li><b>작업 4</b> — {@code index.html}의 {@code #completionResultPanel} 안에 전량실패 전용 안내({@code #cr_allFailedNotice})와
 *       제목 요소({@code #cr_title})가 실제로 존재한다(없으면 {@code getElementById}가 null이라 안내가 영영 안 보인다).</li>
 *   <li><b>DoD 5 / 작업 5</b> — {@code handleAnalysisPaused()}와 이번에 추가된 함수들이 파일 배지 마킹
 *       ({@code globalFilesCache}/{@code renderDividedGrid}/{@code isCompleted})에 손대지 않는다.</li>
 * </ol>
 *
 * <h2>양성 대조군 (§A.2 의무 — 정적 단언의 탐지력)</h2>
 * 카운터 대입 줄을 한 번 더 복제한 합성 입력을 같은 계수 함수에 넣어 <b>2개소로 실제로 걸러짐</b>을 보인다.
 * TASK-010 착수 전 고정본(커밋 {@code cce9ca2})에는 {@code fillCompletionResultPanel}/{@code isAllFailedPause}/
 * {@code #cr_allFailedNotice}가 없어 아래 단언들이 RED다(dev 기록의 두 시점 실행 참조).
 */
class CompletionPanelCounterSingleSourceContractTest {

  private static final String DASHBOARD_JS = "src/main/resources/static/js/dashboard.js";
  private static final String INDEX = "src/main/resources/templates/index.html";

  /** work-order TASK-010 작업 3이 지정한 판별식(서버 분기와 동일 의미, 프런트 필드명). */
  private static final String EXPECTED_ALL_FAILED_CONDITION =
      "successCount === 0 && alreadyCount === 0 && failedCount > 0";

  private static final List<String> COUNTER_IDS = List.of("cr_success", "cr_already", "cr_failed");

  @Test
  void DoD3_카운터_3종에_값을_대입하는_지점은_dashboard_js_전체에서_각_1개소이고_fillCompletionResultPanel_안에_있다() {
    String js = readOrFail(locate(DASHBOARD_JS));
    String filler = extractFunctionBody(js, "fillCompletionResultPanel");
    for (String id : COUNTER_IDS) {
      assertEquals(1, countAssignments(js, id), id + " 대입 지점이 1개소가 아니다(게이트1 ⑫ 두 벌 금지)");
      assertEquals(1, countAssignments(filler, id), id + " 대입 지점이 fillCompletionResultPanel() 안에 있지 않다");
    }
  }

  @Test
  void DoD3_정상완료와_전량실패_진입점은_둘_다_fillCompletionResultPanel을_호출할_뿐_카운터를_직접_채우지_않는다() {
    String js = readOrFail(locate(DASHBOARD_JS));
    for (String fn : List.of("showCompletionResult", "showAllFailedPausedResult")) {
      String body = extractFunctionBody(js, fn);
      assertTrue(body.contains("fillCompletionResultPanel("), fn + "()가 fillCompletionResultPanel()을 호출하지 않는다:\n" + body);
      for (String id : COUNTER_IDS) {
        assertEquals(0, countAssignments(body, id), fn + "()가 " + id + "를 직접 채운다(두 벌)");
      }
    }
    // 제목/안내 모드는 두 진입점이 서로 다른 값으로 setCompletionPanelMode()를 부른다(정상 완료=false, 전량실패=true).
    assertTrue(extractFunctionBody(js, "showCompletionResult").contains("setCompletionPanelMode(false);"),
        "정상 완료 진입점이 제목/안내를 정상 모드로 되돌리지 않는다");
    assertTrue(extractFunctionBody(js, "showAllFailedPausedResult").contains("setCompletionPanelMode(true);"),
        "전량실패 진입점이 제목/안내를 PAUSED 전용 모드로 바꾸지 않는다");
  }

  @Test
  void 작업3_전량실패_판별식이_서버_runAnalysis_전량실패_분기와_같은_형태다() {
    String body = extractFunctionBody(readOrFail(locate(DASHBOARD_JS)), "isAllFailedPause");
    assertTrue(body.contains("return " + EXPECTED_ALL_FAILED_CONDITION + ";"),
        "판별식이 '" + EXPECTED_ALL_FAILED_CONDITION + "'가 아니다:\n" + body);
    // 필드 누락(구버전 응답) 폴백: 없으면 0으로 읽어 전량실패로 오판하지 않는다.
    assertTrue(body.contains("status.failedCount || 0"), "failedCount 누락 폴백(|| 0)이 없다");
  }

  @Test
  void 작업1_handleAnalysisPaused는_전량실패일_때만_showAllFailedPausedResult를_호출하고_세션_정리는_그대로다() {
    String body = extractFunctionBody(readOrFail(locate(DASHBOARD_JS)), "handleAnalysisPaused");
    assertTrue(body.contains("if (isAllFailedPause(status)) {") && body.contains("showAllFailedPausedResult(status);"),
        "handleAnalysisPaused()가 전량실패 판별을 거쳐 패널을 열지 않는다:\n" + body);
    assertFalse(body.contains("showCompletionResult("),
        "handleAnalysisPaused()가 정상 완료 진입점을 그대로 불러 '완료'처럼 보이게 한다(작업 2 위반)");
    // TASK-009 DoD 8 / 게이트1 ④ — 세션 정리 3줄과 패널 숨김은 이번 TASK에서도 그대로다.
    String cleanup = "  clearSessionFromStorage();\n  currentSessionId = null;\n  updateSessionControlPanel();\n}";
    assertTrue(body.endsWith(cleanup), "handleAnalysisPaused()의 세션 정리 3줄이 바뀌었다");
    assertTrue(body.contains("if (sessionControlPanel) sessionControlPanel.style.display = \"none\";"), "패널 숨김 줄이 바뀌었다");
  }

  @Test
  void 작업4_index_html의_완료_패널_안에_전량실패_전용_안내와_제목_요소가_실제로_존재한다() {
    String index = readOrFail(locate(INDEX));
    int panelAt = index.indexOf("id=\"completionResultPanel\"");
    assertTrue(panelAt >= 0, "index.html에 #completionResultPanel이 없다");
    int noticeAt = index.indexOf("id=\"cr_allFailedNotice\"", panelAt);
    int titleAt = index.indexOf("id=\"cr_title\"", panelAt);
    int successAt = index.indexOf("id=\"cr_success\"", panelAt);
    assertTrue(noticeAt > panelAt, "index.html #completionResultPanel 안에 #cr_allFailedNotice가 없다");
    assertTrue(titleAt > panelAt, "index.html #completionResultPanel 안에 #cr_title이 없다");
    assertTrue(noticeAt < successAt, "전량실패 안내는 카운터 표보다 앞(위)에 있어야 한다");
    // 안내는 기본 숨김이고 JS(setCompletionPanelMode)만 연다.
    String noticeTag = index.substring(noticeAt, index.indexOf('>', noticeAt));
    assertTrue(noticeTag.contains("display:none"), "#cr_allFailedNotice가 기본 숨김이 아니다: " + noticeTag);
    // 안내 문면: 전량실패임과 재시도 경로를 담는다(작업 4).
    String noticeBlock = index.substring(noticeAt, index.indexOf("</div>", noticeAt));
    assertTrue(noticeBlock.contains("전체 파일 처리 실패") && noticeBlock.contains("이어서 분석"),
        "전량실패 안내 문면에 '전체 파일 처리 실패' 또는 재시도 경로('이어서 분석')가 없다:\n" + noticeBlock);
  }

  @Test
  void DoD5_handleAnalysisPaused와_신설_함수들은_파일_배지_마킹_로직에_손대지_않는다() {
    String js = readOrFail(locate(DASHBOARD_JS));
    for (String fn : List.of("handleAnalysisPaused", "showAllFailedPausedResult", "isAllFailedPause",
        "setCompletionPanelMode", "fillCompletionResultPanel")) {
      String body = extractFunctionBody(js, fn);
      for (String forbidden : List.of("globalFilesCache", "renderDividedGrid(", "isCompleted", "status = 'FAILED'")) {
        assertFalse(body.contains(forbidden), fn + "()에 파일 배지 마킹 관련 코드('" + forbidden + "')가 있다(사람 결정 (a) 위반)");
      }
    }
  }

  @Test
  void 대조군_카운터_대입_줄을_복제한_합성_입력은_2개소로_걸러진다() {
    String js = readOrFail(locate(DASHBOARD_JS));
    String line = "document.getElementById('cr_success').textContent = `${success}개`;";
    assertTrue(js.contains(line), "대조군 기준 줄을 찾지 못했다");
    String twice = js + "\nfunction synthetic() {\n  const success = 0;\n  " + line + "\n}\n";
    assertEquals(2, countAssignments(twice, "cr_success"), "계수 함수가 복제된 대입 줄을 세지 못한다(빈 검사)");
    assertEquals(1, countAssignments(js, "cr_success"));
  }

  // ---------------------------------------------------------------------------------------------
  // 도우미
  // ---------------------------------------------------------------------------------------------

  /** `getElementById('{id}').textContent =` 형태의 대입 지점 수. 주석 속 언급은 세지 않는다(대입 구문만). */
  private static int countAssignments(String source, String id) {
    Matcher m = Pattern.compile("getElementById\\('" + Pattern.quote(id) + "'\\)\\.textContent\\s*=").matcher(source);
    int n = 0;
    while (m.find()) n++;
    return n;
  }

  /** `function name(` 선언부부터 중괄호 균형이 맞는 곳까지(여는 중괄호 포함, 닫는 중괄호 포함). */
  private static String extractFunctionBody(String source, String name) {
    Pattern decl = Pattern.compile("(?m)^function " + Pattern.quote(name) + "\\(");
    Matcher m = decl.matcher(source);
    if (!m.find()) return fail("dashboard.js에서 function " + name + "( 선언을 찾지 못했다(TASK-010 미적용 상태이거나 이름이 바뀜)");
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
