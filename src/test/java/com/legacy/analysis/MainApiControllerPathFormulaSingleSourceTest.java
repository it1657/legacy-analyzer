package com.legacy.analysis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-005 (work-order 2026-09-outputpath-normalization v2, 게이트1 확정 ④ = 대안 D) —
 * <b>계정별 출력 경로 산식이 {@code MainApiController.java} 안에서 다시 복제되는 것을 막는 감시 테스트</b>.
 * 프로덕션 코드를 <b>텍스트로 읽어</b> 검사하며, 프로덕션 코드 변경은 0건이다.
 *
 * <p><b>왜 이런 테스트가 필요한가</b>: 이 파일에서 계정 세그먼트 산식
 * (username 정규화 → {@code Path.resolve(safeUsername)})은 <b>네 번까지 복제됐던 이력</b>이 있다.
 * 복제본마다 {@code outputPath.trim()} / blank 가드 / 경로 구분자 정규화 같은 세부가 조금씩 빠졌고,
 * 완료 판정이 <b>절대경로 문자열 일치 비교</b>라 한 글자만 어긋나도 화면 전체가 어긋난다
 * (2026-09 사이클에서는 Windows에서 후행 공백 입력이 {@code InvalidPathException}으로 이어져
 * 1단계 조회가 HTTP 500으로 죽었다).
 *
 * <p><b>산식의 단일 출처는 공용 헬퍼다</b> — {@code sanitizeUsername()} / {@code isCopyModeOutput()} /
 * {@code resolveUserOutputRoot()} / {@code resolveProjectOutputRoot()}. 새 호출부가 생기면
 * 산식을 옮겨 적지 말고 <b>이 헬퍼들을 부르면 된다</b>. 허용치가 2회인 이유는 산식의 원본이
 * {@code sanitizeUsername()}(읽기 측 단일 출처)과 {@code runAnalysis()}(쓰기 측 정답)
 * 두 곳에만 있기 때문이다.
 *
 * <p><b>층이 다른 회귀망</b>: 이 테스트는 <b>소스 텍스트</b>를 보는 정적 감시이고,
 * {@code MainApiControllerDashboardStatusPathContractTest}의 C5는 실제 호출 결과를 헬퍼 반환값과
 * 대조하는 <b>런타임</b> 감시다. 텍스트 검사를 우회하는 형태(변수명·정규식 표기를 바꾼 사본)는
 * C5가 잡고, 아직 런타임 검증이 없는 새 호출부는 이쪽이 잡는다.
 *
 * <p><b>양성 대조군 2종</b>(STRUCTURE.md 20절 — "검출되지 않았다"는 음성 결과만으로는
 * 이 테스트에 탐지력이 있는지 알 수 없다):
 * <ol>
 *   <li><b>가짜 소스 문자열</b> — 산식을 한 벌 더 복제해 넣은 문자열에서 반드시 검출된다.</li>
 *   <li><b>수정 전 실소스</b> — 작업 시작 커밋 {@code 98b2d15}의 {@code MainApiController.java}를
 *       테스트 리소스로 고정한 것({@code src/test/resources/pathformula/}). 이 파일은
 *       {@code git show 98b2d15:src/main/java/com/legacy/analysis/MainApiController.java}의 출력이며
 *       git blob 해시 {@code 1ab7952767c5d23f015168d658757da2afe6a4a1}로 원본과 <b>바이트 단위 동일</b>함이
 *       확인됐다. 여기서 <b>4번째 사본(986/990행)이 실제로 검출</b>되는 것이 이 감시 테스트가
 *       실물 결함을 잡는다는 유일한 증거다.</li>
 * </ol>
 */
class MainApiControllerPathFormulaSingleSourceTest {

  /** 검사 대상 프로덕션 소스(저장소 루트 기준 상대 경로). */
  private static final String SOURCE_RELATIVE_PATH =
      "src/main/java/com/legacy/analysis/MainApiController.java";

  /** 양성 대조군 (ii) — 수정 전 실소스 고정본(클래스패스 리소스). */
  private static final String BEFORE_FIXTURE_RESOURCE =
      "/pathformula/MainApiController.before-98b2d15.java.txt";

  /**
   * 계정 세그먼트 정규식 리터럴의 안정적인 앞부분. 전체 리터럴은 문자 클래스 뒤에 이스케이프 표기가
   * 붙지만 그 부분은 손대는 사람마다 달라질 수 있어 앞부분만 본다.
   */
  private static final String ACCOUNT_SEGMENT_REGEX_MARK = "[^a-zA-Z0-9_";

  /**
   * 허용치 2회 = {@code sanitizeUsername()}(읽기 측 단일 출처) + {@code runAnalysis()}(쓰기 측 정답).
   * <b>TASK-002 완료 후 기준</b>이다 — 착수 시점 실소스는 {@code getDashboardStatus()}의 인라인 사본까지
   * 3회였고, 그 사본이 제거되면서 2회가 됐다.
   */
  private static final int MAX_ACCOUNT_SEGMENT_REGEX = 2;

  /** {@code Path.resolve(<계정 세그먼트>)} 인라인 조합. */
  private static final Pattern INLINE_ACCOUNT_RESOLVE =
      Pattern.compile("\\.resolve\\(\\s*(?:safeUsername|sanitizeUsername\\()");

  /**
   * 허용치 2회 = {@code resolveUserOutputRoot()}(헬퍼) + {@code runAnalysis()}.
   * 그 밖의 위치에서 계정 세그먼트를 직접 이어 붙이면 그게 새 사본이다.
   */
  private static final int MAX_INLINE_ACCOUNT_RESOLVE = 2;

  /** 실패 메시지에 함께 실어 보내는 "왜 막혀 있는가" 안내(work-order TASK-005 작업내용 4). */
  private static final String WHY_THIS_IS_BLOCKED = String.join("\n",
      "",
      "── 왜 이 검사가 있는가 ────────────────────────────────────────",
      "계정별 출력 경로 산식(username 정규화 → outputPath.resolve(safeUsername))은",
      "이 파일 안에서 네 번까지 복제된 이력이 있다. 복제본마다 outputPath.trim() /",
      "blank 가드 / 경로 구분자 정규화가 조금씩 빠졌고, 완료 판정이 절대경로 문자열",
      "일치 비교라 한 글자만 어긋나도 완료 파일을 한 건도 인지하지 못한다.",
      "",
      "산식의 단일 출처는 공용 헬퍼다:",
      "  - sanitizeUsername(username)            : 계정명 정규화",
      "  - isCopyModeOutput(source, output)      : copy 모드 판정(trim 포함)",
      "  - resolveUserOutputRoot(s, o, username) : {output}/{safeUsername}",
      "  - resolveProjectOutputRoot(s, o, user)  : {output}/{safeUsername}/{srcName}",
      "",
      "새 호출부가 필요하면 산식을 옮겨 적지 말고 위 헬퍼를 호출하라.",
      "요청 파라미터를 직접 받는 자리라면 startAnalysis()와 같은 규칙으로",
      "경로 구분자를 '/'로 먼저 정규화해야 헬퍼의 문자열 비교가 성립한다.",
      "────────────────────────────────────────────────────────────");

  // ────────────────────────────────────────────────────────────────────────────
  // 검사 로직 (순수 함수 — 실소스/가짜 소스/수정 전 소스에 똑같이 적용된다)
  // ────────────────────────────────────────────────────────────────────────────

  /** 위반 사항을 사람이 읽을 수 있는 문장으로 돌려준다. 비어 있으면 위반 없음. */
  private static List<String> findViolations(String sourceText) {
    List<String> violations = new ArrayList<>();

    List<Integer> regexLines = occurrenceLines(sourceText,
        Pattern.compile(Pattern.quote(ACCOUNT_SEGMENT_REGEX_MARK)));
    if (regexLines.size() > MAX_ACCOUNT_SEGMENT_REGEX) {
      violations.add("계정 세그먼트 정규식 리터럴이 허용치 " + MAX_ACCOUNT_SEGMENT_REGEX
          + "회를 넘어 " + regexLines.size() + "회 나타난다. (행: " + regexLines + ")");
    }

    List<Integer> resolveLines = occurrenceLines(sourceText, INLINE_ACCOUNT_RESOLVE);
    if (resolveLines.size() > MAX_INLINE_ACCOUNT_RESOLVE) {
      violations.add("계정 세그먼트를 직접 이어 붙이는 resolve(safeUsername) 류 인라인 조합이 허용치 "
          + MAX_INLINE_ACCOUNT_RESOLVE + "회를 넘어 " + resolveLines.size() + "회 나타난다. (행: "
          + resolveLines + ")");
    }

    return violations;
  }

  /** 패턴이 나타나는 위치의 1-based 행 번호 목록(한 행에 여러 번이면 그 행을 여러 번 담는다). */
  private static List<Integer> occurrenceLines(String sourceText, Pattern pattern) {
    List<Integer> lines = new ArrayList<>();
    String[] allLines = sourceText.replace("\r\n", "\n").split("\n", -1);
    for (int i = 0; i < allLines.length; i++) {
      Matcher matcher = pattern.matcher(allLines[i]);
      while (matcher.find()) {
        lines.add(i + 1);
      }
    }
    return lines;
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 소스 로딩 (읽기 실패를 조용히 통과시키지 않는다 — work-order TASK-005 DoD 3)
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * gradle이 어느 디렉터리에서 실행되든 프로덕션 소스를 찾아낸다.
   * 현재 작업 디렉터리에서 시작해 부모로 올라가며 상대 경로를 시도한다.
   * <b>못 찾으면 조용히 통과하지 않고 명시적으로 실패한다</b> — 감시 테스트가 대상 파일을
   * 읽지 못한 채 "위반 0건"으로 GREEN이 되면 탐지력이 0인 채로 방치되기 때문이다.
   */
  private static Path locateProductionSource() {
    Path cursor = Path.of("").toAbsolutePath();
    while (cursor != null) {
      Path candidate = cursor.resolve(SOURCE_RELATIVE_PATH);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      cursor = cursor.getParent();
    }
    return fail("감시 대상 소스를 찾지 못했다: " + SOURCE_RELATIVE_PATH
        + " (탐색 시작=" + Path.of("").toAbsolutePath() + ")"
        + " — 파일이 옮겨졌다면 SOURCE_RELATIVE_PATH를 갱신하라. 읽지 못한 채 통과시키지 않는다.");
  }

  /** 읽기에 실패하면 조용히 넘어가지 않고 즉시 실패시킨다. */
  private static String readOrFail(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return fail("감시 대상 소스를 읽지 못했다: " + path + " (" + e + ")");
    }
  }

  /** 수정 전 실소스 고정본을 클래스패스에서 읽는다. 없으면 즉시 실패한다. */
  private static String readBeforeFixture() throws IOException {
    try (InputStream in = MainApiControllerPathFormulaSingleSourceTest.class
        .getResourceAsStream(BEFORE_FIXTURE_RESOURCE)) {
      if (in == null) {
        return fail("양성 대조군 (ii)용 수정 전 실소스 고정본이 클래스패스에 없다: "
            + BEFORE_FIXTURE_RESOURCE + " — 이 파일이 없으면 이 감시 테스트의 탐지력을 증명할 수 없다.");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  // ────────────────────────────────────────────────────────────────────────────

  /**
   * 본 검사 — 현재 프로덕션 소스에 산식 사본이 허용치를 넘게 존재하지 않는다.
   * (TASK-002로 {@code getDashboardStatus()}의 4번째 사본이 제거된 뒤의 기준이다.)
   */
  @Test
  void 프로덕션_소스에는_계정경로_산식_사본이_허용치를_넘게_존재하지_않는다() {
    Path source = locateProductionSource();
    String text = readOrFail(source);

    List<Integer> regexLines = occurrenceLines(text,
        Pattern.compile(Pattern.quote(ACCOUNT_SEGMENT_REGEX_MARK)));
    List<Integer> resolveLines = occurrenceLines(text, INLINE_ACCOUNT_RESOLVE);
    System.out.println("[single-source] source=" + source);
    System.out.println("[single-source] accountSegmentRegex count=" + regexLines.size()
        + " lines=" + regexLines + " (allowed " + MAX_ACCOUNT_SEGMENT_REGEX + ")");
    System.out.println("[single-source] inlineAccountResolve count=" + resolveLines.size()
        + " lines=" + resolveLines + " (allowed " + MAX_INLINE_ACCOUNT_RESOLVE + ")");

    List<String> violations = findViolations(text);
    assertTrue(violations.isEmpty(),
        "계정별 출력 경로 산식이 공용 헬퍼 밖에서 다시 복제됐다.\n  - "
            + String.join("\n  - ", violations) + "\n" + WHY_THIS_IS_BLOCKED);
  }

  /**
   * <b>양성 대조군 (i)</b> — 산식이 복제된 <b>가짜 소스 문자열</b>에는 같은 검사 로직이
   * 반드시 위반을 보고한다. 이게 실패하면 위 본 검사의 "위반 0건"은 아무것도 증명하지 못한다.
   */
  @Test
  void 양성대조군1_산식이_복제된_가짜_소스에서는_두_검사가_모두_검출한다() {
    String fakeSource = String.join("\n",
        "class Fake {",
        "  private static String sanitizeUsername(String username) {",
        "    return username.replaceAll(\"[^a-zA-Z0-9_\\\\-]\", \"_\");",
        "  }",
        "  private static String resolveUserOutputRoot(String o, String u) {",
        "    return Path.of(o.trim()).resolve(sanitizeUsername(u)).toString();",
        "  }",
        "  private void runAnalysis(String out, String username) {",
        "    String safeUsername = username.replaceAll(\"[^a-zA-Z0-9_\\\\-]\", \"_\");",
        "    Path root = Path.of(out.trim()).resolve(safeUsername);",
        "  }",
        "  public Map<String, Object> someNewEndpoint(String out, String username) {",
        "    // 여기가 새로 생긴 5번째 사본이다(헬퍼를 부르지 않고 산식을 옮겨 적었다)",
        "    String safeUsername = username.replaceAll(\"[^a-zA-Z0-9_\\\\-]\", \"_\");",
        "    Path root = Path.of(out).resolve(safeUsername);",
        "    return null;",
        "  }",
        "}");

    List<String> violations = findViolations(fakeSource);
    System.out.println("[positive-control-1 fake-source] violations=" + violations);
    assertEquals(2, violations.size(),
        "산식을 한 벌 더 복제한 가짜 소스에서 두 검사(정규식 리터럴 / 인라인 resolve)가 모두 검출해야 한다. "
            + "검출하지 못하면 이 감시 테스트는 탐지력이 없다. violations=" + violations);
  }

  /**
   * <b>양성 대조군 (ii)</b> — <b>수정 전 실소스</b>({@code 98b2d15})에서 4번째 사본이 실제로 검출된다.
   * 이 케이스가 이 감시 테스트가 <b>실물 결함</b>을 잡는다는 증거다.
   * 검출에 실패하면 work-order TASK-005 DoD 2에 따라 즉시 멈추고 보고해야 한다.
   */
  @Test
  void 양성대조군2_수정전_실소스에서는_4번째_인라인_사본이_검출된다() throws IOException {
    String beforeText = readBeforeFixture();

    List<Integer> regexLines = occurrenceLines(beforeText,
        Pattern.compile(Pattern.quote(ACCOUNT_SEGMENT_REGEX_MARK)));
    List<Integer> resolveLines = occurrenceLines(beforeText, INLINE_ACCOUNT_RESOLVE);
    System.out.println("[positive-control-2 before-98b2d15] accountSegmentRegex count="
        + regexLines.size() + " lines=" + regexLines);
    System.out.println("[positive-control-2 before-98b2d15] inlineAccountResolve count="
        + resolveLines.size() + " lines=" + resolveLines);

    // 수정 전에는 sanitizeUsername(795) / getDashboardStatus 인라인 사본(986) / runAnalysis(1319) 3회.
    assertEquals(3, regexLines.size(),
        "수정 전 실소스의 정규식 리터럴은 3회여야 한다(고정본이 바뀐 게 아닌지 확인). lines=" + regexLines);
    assertTrue(regexLines.contains(986),
        "제거 대상이었던 getDashboardStatus()의 인라인 사본(986행)이 고정본에 있어야 한다. lines=" + regexLines);
    assertTrue(resolveLines.contains(990),
        "제거 대상이었던 getDashboardStatus()의 resolve(safeUsername)(990행)이 고정본에 있어야 한다. "
            + "lines=" + resolveLines);

    List<String> violations = findViolations(beforeText);
    System.out.println("[positive-control-2 before-98b2d15] violations=" + violations);
    assertEquals(2, violations.size(),
        "수정 전 실소스에서 두 검사가 모두 위반을 보고해야 한다 — 이게 실패하면 이 감시 테스트는 "
            + "실물 결함을 잡지 못하는 것이므로 즉시 멈추고 보고해야 한다. violations=" + violations);
  }

  /**
   * <b>읽기 실패는 조용히 통과하지 않는다</b>(work-order TASK-005 DoD 3).
   * 대상 파일이 사라지거나 경로가 바뀌면 "위반 0건 → GREEN"이 아니라 명시적 실패가 되어야 한다.
   */
  @Test
  void 소스를_읽지_못하면_조용히_통과하지_않고_명시적으로_실패한다() {
    Path missing = Path.of("").toAbsolutePath()
        .resolve("src/main/java/com/legacy/analysis/__NoSuchFile__.java");

    AssertionError error = assertThrows(AssertionError.class, () -> readOrFail(missing));
    System.out.println("[read-failure-check] message=" + error.getMessage());
    assertTrue(error.getMessage().contains("__NoSuchFile__"),
        "실패 메시지에 읽지 못한 경로가 드러나야 한다: " + error.getMessage());

    // 실제 대상은 정상적으로 찾아진다(경로 해석이 gradle 실행 디렉터리에 의존하지 않는다).
    Path located = locateProductionSource();
    System.out.println("[read-failure-check] located=" + located);
    assertTrue(Files.isRegularFile(located), "감시 대상 소스가 실제로 존재해야 한다: " + located);
  }
}
