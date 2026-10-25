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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-001 (work-order 2026-10-resume-tail-fixes v1 전문 + v2 §V2 — REQ-001 ①·REQ-002) —
 * O62 "업로드 분석을 일시정지했다가 <b>같은 화면</b>에서 이어서 끝내면 결과가 사용자 폴더로
 * 돌아오지 않는다"를 화면 코드 <b>글자</b>로 본다.
 *
 * <p><b>왜 글자로 보는가</b>(설계 §5, 게이트1 ④): 이 저장소의 시험 의존성에는 JS 실행기가 없고
 * 들이는 것은 설정 변경이라 금지다. 그래서 O62의 "동작" 증거는 공개 주소 실기동(TASK-002)이
 * 맡고, 단위 시험은 "고친 줄이 있는가(WB1)"와 "지키는 성질이 그대로인가(WB2)"만 본다.
 *
 * <p><b>WB1</b>(고치기 전 RED): {@code resumeAnalysis()}의 성공 분기 안에, 같은 세션일 때
 * 업로드 표시를 되살리는 줄이 {@code startPolling();}보다 <b>앞</b>에 있는가.
 *
 * <p><b>WB2</b>(지키는 쪽): 일시정지가 표시를 내리는 줄, 완료가 표시 참일 때만 돌려쓰기를
 * 부르는 가드는 그대로이고, 표시를 참으로 만드는 자리가 <b>시작과 재개 두 곳뿐</b>인가.
 * 단언 순서는 "고정된 성질 먼저, 개수는 마지막"이다 — 고치기 전 RED 자리가 개수 단언
 * 한 줄이 되도록 한 것이다(설계 §5, RS-2).
 *
 * <p><b>추출기 양성 대조</b>: 함수 본문은 선언 {@code function 이름(}부터 짝 맞는 닫는 중괄호까지
 * 끊는데, 문자열·주석 안 중괄호를 건너뛰는 방식으로 센다. 같은 저장소
 * {@code PauseSettledFrontGatingContractTest}가 쓰는 "중괄호만 세는" 방식과 <b>같은 결과</b>인지를
 * 결과 단언보다 먼저 확인한다(RT-16: 하네스 유효성을 먼저). 합성 문자열 양성 대조도 함께 둔다 —
 * 찾는 줄이 없다는 결과가 "판정기가 아무것도 못 찾는 상태"여서 나온 것이 아님을 보인다.
 */
class DashboardResumeUploadWriteBackContractTest {

  private static final String DASHBOARD_JS = "src/main/resources/static/js/dashboard.js";

  /** 되살리기 줄을 알아보는 두 조각 — 둘이 같은 줄에 있어야 "같은 세션일 때만 켠다"가 된다. */
  private static final String REVIVE_ASSIGN = "isUploadModeSession = true";
  private static final String REVIVE_SAME_SESSION = "uploadSessionIdForWriteback === currentSessionId";

  private static final String SUCCESS_BRANCH = "if (data.success) {";
  private static final String START_POLLING = "startPolling();";
  private static final String LOWER_FLAG = "isUploadModeSession = false";
  private static final String WRITE_BACK_CALL = "performWriteBack();";
  private static final String WRITE_BACK_GUARD = "if (isUploadModeSession) {";

  /** 표시를 참으로 만드는 자리 — 업로드 분석 시작 1곳 + 같은 화면 재개 1곳. */
  private static final int EXPECTED_REVIVE_ASSIGN_LINES = 2;

  /** 문자열 안 이스케이프 문자. 코드 포인트로 둔다 — 히어독이 역슬래시를 먹는 사고를 피한다. */
  private static final char BACKSLASH = (char) 92;

  @Test
  void WB1_같은화면_재개가_업로드_표시를_되살리는_줄이_폴링_재시작보다_앞에_있다() {
    String source = readOrFail(locate(DASHBOARD_JS));

    // 하네스 유효성 ① — 두 추출기가 같은 본문을 준다.
    String body = extractFunctionBody(source, "resumeAnalysis");
    assertEquals(extractFunctionBodyBraceOnly(source, "resumeAnalysis"), body,
        "WB1 (하네스 ①): 문자열·주석을 건너뛰는 추출기와 중괄호만 세는 추출기의"
            + " resumeAnalysis 본문이 다르다. 두 방식이 갈리면 아래 자리 비교를 믿을 수 없다.");

    // 하네스 유효성 ② — 비교에 쓰는 두 좌표가 본문에 정확히 한 번씩 있다.
    assertEquals(1, countLinesContaining(body, SUCCESS_BRANCH),
        "WB1 (하네스 ②): resumeAnalysis 본문의 성공 분기 줄 수. 본문=" + body);
    assertEquals(1, countLinesContaining(body, START_POLLING),
        "WB1 (하네스 ②): resumeAnalysis 본문의 폴링 재시작 줄 수. 본문=" + body);

    // 하네스 유효성 ③ — 합성 문자열 양성 대조. 되살리기 줄이 있는 본문에서는 찾아낸다.
    List<Integer> syntheticHits = reviveLineIndexes(SYNTHETIC_RESUME_WITH_REVIVE);
    assertEquals(List.of(2), syntheticHits,
        "WB1 (하네스 ③): 합성 양성 대조 — 되살리기 줄이 있는 합성 본문에서 그 줄을 찾지 못했다."
            + " 합성 본문=" + SYNTHETIC_RESUME_WITH_REVIVE);

    // 결과 단언 — 대상 줄이 있고, 성공 분기 안이며, 폴링 재시작보다 앞이다.
    List<String> lines = List.of(body.split("\n"));
    List<Integer> hits = reviveLineIndexes(body);
    assertEquals(1, hits.size(),
        "WB1 (결과): resumeAnalysis 본문에서 \"같은 세션이면 업로드 표시를 참으로\" 하는 줄이"
            + " 정확히 1줄이 아니다(" + hits.size() + "줄). 찾는 조각 둘=\"" + REVIVE_ASSIGN
            + "\" + \"" + REVIVE_SAME_SESSION + "\" | 본문=" + body);
    int reviveLine = hits.get(0);
    int successLine = firstLineIndexContaining(lines, SUCCESS_BRANCH);
    int pollingLine = firstLineIndexContaining(lines, START_POLLING);
    assertTrue(successLine < reviveLine,
        "WB1 (결과): 되살리기 줄이 성공 분기 안이 아니다 (성공 분기 " + successLine
            + "번째 줄 / 되살리기 " + reviveLine + "번째 줄). 본문=" + body);
    assertTrue(reviveLine < pollingLine,
        "WB1 (결과): 되살리기 줄이 폴링 재시작보다 뒤다 (되살리기 " + reviveLine
            + "번째 줄 / 폴링 재시작 " + pollingLine + "번째 줄). 본문=" + body);
  }

  @Test
  void WB2_일시정지는_표시를_내리고_완료는_표시가_참일때만_돌려쓰기를_부르며_참으로_만드는_자리가_두곳이다() {
    String source = readOrFail(locate(DASHBOARD_JS));

    // 하네스 유효성 ① — 두 추출기가 같은 본문을 준다(감시 대상 두 함수 모두).
    String pausedBody = extractFunctionBody(source, "handleAnalysisPaused");
    assertEquals(extractFunctionBodyBraceOnly(source, "handleAnalysisPaused"), pausedBody,
        "WB2 (하네스 ①): 두 추출기의 handleAnalysisPaused 본문이 다르다.");
    String completionBody = extractFunctionBody(source, "handleAnalysisCompletion");
    assertEquals(extractFunctionBodyBraceOnly(source, "handleAnalysisCompletion"), completionBody,
        "WB2 (하네스 ①): 두 추출기의 handleAnalysisCompletion 본문이 다르다.");

    // 하네스 유효성 ② — 합성 문자열 양성 대조. 줄 세기와 가드 찾기가 둘 다 동작한다.
    assertEquals(2, countLinesContaining(SYNTHETIC_FLAG_SOURCE, REVIVE_ASSIGN),
        "WB2 (하네스 ②): 합성 양성 대조 — 표시를 참으로 만드는 줄 2개를 세지 못했다. 합성="
            + SYNTHETIC_FLAG_SOURCE);
    assertEquals(WRITE_BACK_GUARD, guardLineBefore(SYNTHETIC_GUARD_SOURCE, WRITE_BACK_CALL),
        "WB2 (하네스 ②): 합성 양성 대조 — 돌려쓰기 호출 앞의 가드 줄을 찾지 못했다. 합성="
            + SYNTHETIC_GUARD_SOURCE);

    // 고정된 성질 ㉠ — 일시정지는 업로드 표시를 내린다(일시정지 때 돌려쓰기·서버 정리를 하지 않는다).
    assertEquals(1, countLinesContaining(pausedBody, LOWER_FLAG),
        "WB2 (결과 ㉠): handleAnalysisPaused가 업로드 표시를 내리는 줄 수. 본문=" + pausedBody);

    // 고정된 성질 ㉡ — 완료는 표시가 참일 때만 돌려쓰기를 부른다.
    assertEquals(1, countLinesContaining(completionBody, WRITE_BACK_CALL),
        "WB2 (결과 ㉡): handleAnalysisCompletion의 돌려쓰기 호출 줄 수. 본문=" + completionBody);
    assertEquals(WRITE_BACK_GUARD, guardLineBefore(completionBody, WRITE_BACK_CALL),
        "WB2 (결과 ㉡): 돌려쓰기 호출 바로 앞의 코드 줄이 업로드 표시 가드가 아니다. 본문="
            + completionBody);

    // 개수는 마지막 — 표시를 참으로 만드는 자리가 시작과 재개 두 곳뿐이다(고치기 전에는 1곳).
    assertEquals(EXPECTED_REVIVE_ASSIGN_LINES, countLinesContaining(source, REVIVE_ASSIGN),
        "WB2 (결과 ㉢): dashboard.js 전체에서 업로드 표시를 참으로 만드는 줄 수."
            + " 기대 " + EXPECTED_REVIVE_ASSIGN_LINES + "(업로드 분석 시작 1곳 + 같은 화면 재개 1곳)."
            + " 찾는 문자열=\"" + REVIVE_ASSIGN + "\"");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 합성 양성 대조용 문자열 — 판정기가 "찾을 수 있는 상태"임을 보이는 대조군이다.
  // ────────────────────────────────────────────────────────────────────────────

  private static final String SYNTHETIC_RESUME_WITH_REVIVE = String.join("\n",
      "function resumeAnalysis() {",
      "  if (data.success) {",
      "    if (uploadSessionIdForWriteback && uploadSessionIdForWriteback === currentSessionId) isUploadModeSession = true;",
      "    startPolling();",
      "  }",
      "}");

  private static final String SYNTHETIC_FLAG_SOURCE = String.join("\n",
      "isUploadModeSession = true;",
      "isUploadModeSession = false;",
      "if (x) isUploadModeSession = true;");

  private static final String SYNTHETIC_GUARD_SOURCE = String.join("\n",
      "  // 주석 줄은 가드로 보지 않는다",
      "  if (isUploadModeSession) {",
      "",
      "    performWriteBack();",
      "  }");

  // ────────────────────────────────────────────────────────────────────────────
  // 판정 도우미
  // ────────────────────────────────────────────────────────────────────────────

  /** 되살리기 줄(두 조각이 같은 줄에 있는 줄)의 0부터 세는 줄 번호 목록. */
  private static List<Integer> reviveLineIndexes(String text) {
    String[] lines = text.split("\n");
    List<Integer> hits = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].contains(REVIVE_ASSIGN) && lines[i].contains(REVIVE_SAME_SESSION)) {
        hits.add(i);
      }
    }
    return hits;
  }

  private static int countLinesContaining(String text, String needle) {
    int found = 0;
    for (String line : text.split("\n")) {
      if (line.contains(needle)) {
        found++;
      }
    }
    return found;
  }

  private static int firstLineIndexContaining(List<String> lines, String needle) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains(needle)) {
        return i;
      }
    }
    return fail("본문에서 \"" + needle + "\"가 든 줄을 찾지 못했다");
  }

  /** {@code needle}이 든 줄 바로 앞의 "빈 줄도 한 줄 주석도 아닌" 줄(앞뒤 공백 제거). */
  private static String guardLineBefore(String text, String needle) {
    String[] lines = text.split("\n");
    for (int i = 0; i < lines.length; i++) {
      if (!lines[i].contains(needle)) {
        continue;
      }
      for (int j = i - 1; j >= 0; j--) {
        String candidate = lines[j].trim();
        if (!candidate.isEmpty() && !candidate.startsWith("//")) {
          return candidate;
        }
      }
      return fail("\"" + needle + "\" 앞에 코드 줄이 없다");
    }
    return fail("본문에서 \"" + needle + "\"가 든 줄을 찾지 못했다");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 함수 본문 추출 — 두 방식을 함께 두고 결과가 같은지 양성 대조한다
  // ────────────────────────────────────────────────────────────────────────────

  private static Matcher declarationMatcher(String source, String name) {
    Pattern decl = Pattern.compile("(?m)^(?:async )?function " + Pattern.quote(name + "("));
    Matcher matcher = decl.matcher(source);
    if (!matcher.find()) {
      fail("dashboard.js에서 function " + name + "( 선언을 찾지 못했다");
    }
    return matcher;
  }

  /**
   * 선언부부터 짝 맞는 닫는 중괄호까지(여는·닫는 중괄호 포함). 문자열(따옴표 셋 모두)과
   * 주석(한 줄·여러 줄) 안의 중괄호는 세지 않는다 — 템플릿 문자열의 {@code ${...}}가
   * 깊이를 흔들지 않게 하려는 것이다.
   */
  private static String extractFunctionBody(String source, String name) {
    Matcher matcher = declarationMatcher(source, name);
    int open = source.indexOf('{', matcher.end());
    int depth = 0;
    boolean inLineComment = false;
    boolean inBlockComment = false;
    char quote = 0;
    for (int i = open; i < source.length(); i++) {
      char c = source.charAt(i);
      char next = (i + 1 < source.length()) ? source.charAt(i + 1) : '\0';
      if (inLineComment) {
        if (c == '\n') {
          inLineComment = false;
        }
        continue;
      }
      if (inBlockComment) {
        if (c == '*' && next == '/') {
          inBlockComment = false;
          i++;
        }
        continue;
      }
      if (quote != 0) {
        if (c == BACKSLASH) {
          i++;
        } else if (c == quote) {
          quote = 0;
        }
        continue;
      }
      if (c == '/' && next == '/') {
        inLineComment = true;
        i++;
        continue;
      }
      if (c == '/' && next == '*') {
        inBlockComment = true;
        i++;
        continue;
      }
      if (c == '\'' || c == '"' || c == '`') {
        quote = c;
        continue;
      }
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return source.substring(open, i + 1);
        }
      }
    }
    return fail("function " + name + "의 중괄호 균형이 맞지 않는다(문자열·주석 건너뛰기 방식)");
  }

  /**
   * 중괄호만 세는 방식 — 같은 저장소 {@code PauseSettledFrontGatingContractTest}의 추출기와
   * 같은 알고리즘이다(선언 정규식에만 {@code async}를 허용해 대조 대상 함수를 넓혔다).
   * 양성 대조 전용이며 판정에는 쓰지 않는다.
   */
  private static String extractFunctionBodyBraceOnly(String source, String name) {
    Matcher matcher = declarationMatcher(source, name);
    int open = source.indexOf('{', matcher.end());
    int depth = 0;
    for (int i = open; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return source.substring(open, i + 1);
        }
      }
    }
    return fail("function " + name + "의 중괄호 균형이 맞지 않는다(중괄호만 세는 방식)");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 파일 찾기·읽기 — 같은 저장소 다른 계약 시험과 같은 모양
  // ────────────────────────────────────────────────────────────────────────────

  private static Path locate(String relative) {
    Path cursor = Path.of("").toAbsolutePath();
    while (cursor != null) {
      Path candidate = cursor.resolve(relative);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      cursor = cursor.getParent();
    }
    return fail("감시 대상 소스를 찾지 못했다: " + relative
        + " (탐색 시작=" + Path.of("").toAbsolutePath() + ")");
  }

  private static String readOrFail(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return fail("감시 대상 소스를 읽지 못했다: " + path + " (" + e + ")");
    }
  }
}
