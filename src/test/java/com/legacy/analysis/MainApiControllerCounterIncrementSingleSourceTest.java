package com.legacy.analysis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-007 (work-order 2026-09-quick-fixes-batch v2, 설계 T4 = 게이트1 ⑤ 채택) —
 * <b>{@code AnalysisStatistics}의 카운터 setter가 병렬 분석 루프 안에서 다시 직접 호출되는 것을
 * 막는 감시 테스트</b>. 프로덕션 소스를 <b>텍스트로 읽어</b> 검사하며, 프로덕션 코드 변경은 0줄이다.
 *
 * <p><b>왜 이런 테스트가 필요한가</b>: {@code successCount}/{@code skipCount}/{@code failureCount}는
 * plain {@code int}이고, 병렬 루프에서 setter로 값을 <b>대입</b>하면 늦게 도착한 낮은 값이 큰 값을
 * 덮어쓴다. 실제로 이 결함은 <b>같은 패턴이 두 벌</b>({@code runAnalysis()} / {@code runAnalysisResume()})
 * 복제된 채로 운영에 나갔고, 파일 2개 실행에서 최종 카운터가 1로 남는
 * ({@code expected: <2> but was: <1>}) 형태로 관측됐다.
 * TASK-006이 증가 전용 메서드({@code incrementSuccessCount()} 계열)로 전환했지만,
 * <b>setter는 Jackson 역직렬화를 위해 남아 있으므로</b> 새 호출부를 쓰는 사람이 다시 setter를
 * 집어들 수 있다. 이 테스트가 그 자리를 막는다.
 *
 * <hr>
 *
 * <h2>루프 안/밖을 어떻게 구분하는가 (work-order TASK-007 작업 내용 4)</h2>
 *
 * <b>단순 전역 grep은 쓸 수 없다.</b> {@code grep "setSuccessCount("} 하나로는 이 파일에서 18건이
 * 걸리는데, 그중 <b>단 한 건도 카운터 결함이 아니다</b> — 전부 {@code history.setSuccessCount(...)} /
 * {@code dto.setSuccessCount(...)}, 즉 <b>{@code AnalysisHistory} / {@code SessionDetailDto}라는
 * 다른 클래스</b>의 동명 setter이고, 루프가 끝난 뒤 결과를 저장하는 정상 호출이다.
 * 그래서 두 층으로 좁힌다:
 *
 * <ol>
 *   <li><b>1층 — 메서드 범위로 좁힌다.</b> 검사 대상은 {@code runAnalysis(} /
 *       {@code runAnalysisResume(} <b>선언부</b>에서 시작해 중괄호 균형이 맞는 지점까지의 본문뿐이다
 *       (선언부는 행 머리의 접근제어자로 식별하므로 호출부·주석 언급과 구분된다).
 *       중괄호를 세기 전에 <b>문자열 리터럴 / 문자 리터럴 / 주석을 먼저 제거</b>한다 —
 *       {@code "{"} 같은 리터럴 하나에 범위가 통째로 어긋나기 때문이다.
 *       이 두 메서드 밖(예: {@code AnalysisSessionManager}의 단일 스레드 집계 경로)은 검사하지 않는다.</li>
 *   <li><b>2층 — 수신자로 좁힌다.</b> 1층 범위 안에서 {@code setSuccessCount(} /
 *       {@code setSkipCount(} / {@code setFailureCount(} 호출을 찾은 뒤, <b>바로 앞의 수신자 토큰</b>을
 *       본다.
 *       <ul>
 *         <li>{@code getStatistics()} → <b>위반</b>. 이것이 수정 전 실소스의 형태다
 *             ({@code session.getStatistics().setSuccessCount(sc)}).</li>
 *         <li>{@link #ALLOWED_RECEIVERS}({@code history} / {@code dto}) → 정상. 다른 클래스의 setter다.</li>
 *         <li>그 밖의 수신자(예: {@code stats.setSuccessCount(...)}처럼 통계 객체를 지역변수에 받아
 *             우회하는 형태)나 수신자 없는 호출 → <b>위반</b>.</li>
 *       </ul>
 *       화이트리스트 방식이므로 <b>새 수신자 변수명이 생기면 이 테스트가 먼저 걸린다.</b> 그때는
 *       그 변수의 타입이 {@code AnalysisStatistics}가 <b>아님</b>을 확인한 뒤
 *       {@link #ALLOWED_RECEIVERS}에 추가하라. 확인 없이 추가하면 감시가 뚫린다.</li>
 * </ol>
 *
 * <p><b>이 범위 안에서는 setter 사용 자체를 금지한다</b>(루프 밖의 초기화라도). 두 메서드는 전부
 * 병렬 실행 구간을 품고 있어 "루프 밖"을 텍스트로 안전하게 가려내기 어렵고, 카운터를 통째로 다시
 * 세팅해야 하는 정당한 필요가 생기면 그때 리뷰를 거치는 편이 낫기 때문이다.
 *
 * <hr>
 *
 * <h2>양성 대조군 2종 (STRUCTURE.md 20절 / work-order §0.4)</h2>
 *
 * "검출 0건"이라는 <b>음성 결과만으로는 이 테스트에 탐지력이 있는지 알 수 없다.</b>
 * <ol>
 *   <li><b>가짜 소스 문자열</b> — setter 직접 호출을 한 벌 넣은 문자열에서 반드시 검출된다.
 *       정상 호출({@code history.setSuccessCount})은 <b>같이 걸리지 않아야</b> 한다(오탐 확인까지 겸한다).</li>
 *   <li><b>작업 시작 커밋 {@code 6d9673e}의 실소스 고정본</b> —
 *       {@code src/test/resources/counteratomicity/MainApiController.before-6d9673e.java.txt}.
 *       {@code git cat-file blob}으로 뽑았고 git blob 해시
 *       <b>{@code 96cd1ffef0081512167b9f9f381841c0f7b2a64b}</b>로 원본과 <b>바이트 단위 동일</b>함이
 *       확인됐다({@code git hash-object <고정본>}의 출력이 같다).
 *       여기서 <b>수정 전 6곳(1483·1488·1496 / 1759·1763·1768)이 전부 검출</b>되는 것이
 *       이 감시 테스트가 실물 결함을 잡는다는 유일한 증거다.</li>
 * </ol>
 *
 * <p><b>고정본이 검사를 오염시키지 않는 이유</b>: 이 테스트는 디렉터리를 훑지 않는다.
 * 검사 대상은 {@link #SOURCE_RELATIVE_PATH} <b>파일 하나</b>이고, 고정본은
 * {@link #BEFORE_FIXTURE_RESOURCE}라는 <b>명시적 리소스 이름</b>으로만 읽는다. 즉
 * {@code src/} 아래에 고정본이 몇 개 있든 본 검사 결과는 달라지지 않는다.
 * (참고: 저장소에는 직전 사이클이 남긴 {@code pathformula/} 고정본도 있다. 사람이 손으로
 * {@code grep -rn "setSuccessCount(" src/}를 돌리면 <b>고정본 쪽도 함께 걸리므로</b>,
 * 수동 확인은 {@code src/main/}으로 범위를 좁혀야 한다.)
 *
 * <p><b>층이 다른 회귀망</b>: 이 테스트는 <b>소스 텍스트</b>를 보는 정적 감시이고,
 * {@code AnalysisStatisticsConcurrencyTest}는 실제로 스레드를 돌려 최종값·가시성을 재는
 * <b>런타임</b> 감시다. 런타임 쪽은 "지금 코드가 맞게 동작하는가"를 보고, 이쪽은 "앞으로 누가
 * 틀린 형태를 다시 써 넣는가"를 본다.
 */
class MainApiControllerCounterIncrementSingleSourceTest {

  /** 검사 대상 프로덕션 소스(저장소 루트 기준 상대 경로). <b>디렉터리를 훑지 않는다.</b> */
  private static final String SOURCE_RELATIVE_PATH =
      "src/main/java/com/legacy/analysis/MainApiController.java";

  /** 양성 대조군 (ii) — 작업 시작 커밋 {@code 6d9673e}의 실소스 고정본(클래스패스 리소스). */
  private static final String BEFORE_FIXTURE_RESOURCE =
      "/counteratomicity/MainApiController.before-6d9673e.java.txt";

  /** 고정본의 git blob 해시. {@code git hash-object <고정본>}과 일치해야 한다. */
  private static final String BEFORE_FIXTURE_BLOB_SHA1 =
      "96cd1ffef0081512167b9f9f381841c0f7b2a64b";

  /** 검사 범위가 되는 두 메서드(같은 패턴의 사본 2벌). 선언부를 행 머리 접근제어자로 식별한다. */
  private static final String[] GUARDED_METHODS = {"runAnalysis", "runAnalysisResume"};

  /** 감시 대상 setter 3종. */
  private static final Pattern COUNTER_SETTER =
      Pattern.compile("set(?:Success|Skip|Failure)Count\\s*\\(");

  /** setter 호출 바로 앞에 붙은 수신자 토큰(메서드 호출 수신자면 {@code ()}까지 포함). */
  private static final Pattern TRAILING_RECEIVER =
      Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\s*(\\(\\s*\\))?\\s*\\.\\s*$");

  /**
   * <b>정상 수신자 화이트리스트</b> — {@code AnalysisStatistics}가 <b>아닌</b> 다른 클래스의
   * 동명 setter다. {@code history} = {@code AnalysisHistory}, {@code dto} = {@code SessionDetailDto}.
   * 여기에 이름을 추가하기 전에 <b>반드시 그 변수의 선언 타입을 확인하라.</b>
   */
  private static final Set<String> ALLOWED_RECEIVERS = Set.of("history", "dto");

  /** 실패 메시지에 함께 실어 보내는 "왜 막혀 있는가" 안내. */
  private static final String WHY_THIS_IS_BLOCKED = String.join("\n",
      "",
      "── 왜 이 검사가 있는가 ────────────────────────────────────────",
      "successCount / skipCount / failureCount는 plain int이고, 병렬 분석 루프는",
      "여러 스레드에서 동시에 이 값을 올린다(운영 기본 16스레드).",
      "setter로 '대입'하면 늦게 도착한 낮은 값이 큰 값을 덮어쓰고,",
      "'읽어서 +1 해서 되쓰기'는 읽기·계산·쓰기가 전부 갈라져 손실이 더 크다.",
      "실제로 파일 2개 실행에서 최종 카운터가 1로 남은 사례가 관측됐다.",
      "",
      "이 두 메서드 안에서는 증가 전용 메서드를 써라:",
      "  - session.getStatistics().incrementSuccessCount()",
      "  - session.getStatistics().incrementSkipCount()",
      "  - session.getStatistics().incrementFailureCount()",
      "이 메서드들은 대응 getter와 같은 락에 묶여 있어, 폴링으로 읽는 값이",
      "뒤로 가지 않는 것까지 보장한다(AnalysisStatistics 클래스 Javadoc 참고).",
      "",
      "카운터를 통째로 다시 세팅해야 하는 정당한 사유가 있다면,",
      "이 테스트를 고치기 전에 리뷰를 받아라.",
      "────────────────────────────────────────────────────────────");

  // ────────────────────────────────────────────────────────────────────────────
  // 검사 로직 (순수 함수 — 실소스/가짜 소스/수정 전 고정본에 똑같이 적용된다)
  // ────────────────────────────────────────────────────────────────────────────

  /** 검출된 위반 1건. */
  private record Violation(String method, int line, String receiver, String text) {
    @Override
    public String toString() {
      return method + "() " + line + "행: 수신자 '" + receiver + "' → " + text.trim();
    }
  }

  /**
   * {@link #GUARDED_METHODS} 두 메서드 본문 안에서 카운터 setter 직접 호출을 찾는다.
   * 메서드를 찾지 못하면 <b>조용히 "위반 0건"으로 통과시키지 않고</b> 즉시 실패시킨다 —
   * 메서드명이 바뀌었는데 감시가 빈 채로 GREEN이 되는 것이 이 테스트의 최악 실패 모드다.
   */
  private static List<Violation> findViolations(String sourceText, boolean requireMethodsPresent) {
    String[] rawLines = sourceText.replace("\r\n", "\n").split("\n", -1);
    String[] sanitized = sanitizeForBraceCounting(rawLines);

    List<Violation> violations = new ArrayList<>();
    for (String method : GUARDED_METHODS) {
      int[] range = locateMethodBody(rawLines, sanitized, method);
      if (range == null) {
        if (requireMethodsPresent) {
          fail("감시 대상 메서드 선언을 찾지 못했다: " + method + "() — 이름이 바뀌었다면 "
              + "GUARDED_METHODS를 갱신하라. 찾지 못한 채 '위반 0건'으로 통과시키지 않는다.");
        }
        continue;
      }
      for (int i = range[0]; i <= range[1]; i++) {
        Matcher setter = COUNTER_SETTER.matcher(rawLines[i]);
        while (setter.find()) {
          String receiver = receiverOf(rawLines[i], setter.start());
          if (!ALLOWED_RECEIVERS.contains(receiver)) {
            violations.add(new Violation(method, i + 1, receiver, rawLines[i]));
          }
        }
      }
    }
    return violations;
  }

  /** setter 호출 바로 앞의 수신자 토큰. 수신자가 없으면 {@code "(수신자 없음)"}. */
  private static String receiverOf(String line, int setterStart) {
    Matcher m = TRAILING_RECEIVER.matcher(line.substring(0, setterStart));
    if (!m.find()) {
      return "(수신자 없음)";
    }
    return m.group(1) + (m.group(2) != null ? "()" : "");
  }

  /**
   * 메서드 <b>선언부</b>를 찾아 본문의 시작/끝 행 인덱스(0-based, 양끝 포함)를 돌려준다.
   * 선언부는 "행 머리가 접근제어자 + 같은 행에 {@code 메서드명(}"으로 식별하므로,
   * 호출부({@code new Thread(() -> runAnalysis(...))})나 주석 언급과 구분된다.
   */
  private static int[] locateMethodBody(String[] rawLines, String[] sanitized, String method) {
    Pattern declaration = Pattern.compile(
        "^\\s*(?:private|public|protected)\\b.*\\b" + Pattern.quote(method) + "\\s*\\(");
    for (int i = 0; i < rawLines.length; i++) {
      if (!declaration.matcher(rawLines[i]).find()) {
        continue;
      }
      int depth = 0;
      boolean opened = false;
      for (int j = i; j < sanitized.length; j++) {
        for (char c : sanitized[j].toCharArray()) {
          if (c == '{') {
            depth++;
            opened = true;
          } else if (c == '}') {
            depth--;
          }
        }
        if (opened && depth <= 0) {
          return new int[] {i, j};
        }
      }
      return new int[] {i, rawLines.length - 1};
    }
    return null;
  }

  /**
   * 중괄호를 세기 전에 <b>문자열 리터럴 / 문자 리터럴 / 주석을 공백으로 지운다.</b>
   * {@code "{"} 같은 리터럴이나 주석 속 중괄호 하나에 메서드 범위가 통째로 어긋나기 때문이다.
   * 반환 배열은 원본과 <b>행 수가 같다</b>(행 번호가 어긋나지 않도록).
   */
  private static String[] sanitizeForBraceCounting(String[] rawLines) {
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
        if (c == '/' && k + 1 < line.length() && line.charAt(k + 1) == '/') {
          break;                                   // 행 주석 — 남은 부분 전체 무시
        }
        if (c == '/' && k + 1 < line.length() && line.charAt(k + 1) == '*') {
          inBlockComment = true;
          k++;
          continue;
        }
        if (c == '"' || c == '\'') {
          char quote = c;
          k++;
          while (k < line.length()) {              // 리터럴 종료까지 건너뛴다
            char d = line.charAt(k);
            if (d == '\\') {
              k += 2;
              continue;
            }
            if (d == quote) {
              break;
            }
            k++;
          }
          continue;
        }
        sb.append(c);
      }
      out[i] = sb.toString();
    }
    return out;
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 소스 로딩 (읽기 실패를 조용히 통과시키지 않는다)
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * gradle이 어느 디렉터리에서 실행되든 프로덕션 소스 <b>파일 하나</b>를 찾아낸다.
   * 못 찾으면 조용히 통과하지 않고 명시적으로 실패한다.
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
  private static byte[] readBeforeFixtureBytes() throws IOException {
    try (InputStream in = MainApiControllerCounterIncrementSingleSourceTest.class
        .getResourceAsStream(BEFORE_FIXTURE_RESOURCE)) {
      if (in == null) {
        return fail("양성 대조군 (ii)용 수정 전 실소스 고정본이 클래스패스에 없다: "
            + BEFORE_FIXTURE_RESOURCE + " — 이 파일이 없으면 이 감시 테스트의 탐지력을 증명할 수 없다.");
      }
      return in.readAllBytes();
    }
  }

  // ────────────────────────────────────────────────────────────────────────────

  /**
   * <b>본 검사</b> — 현재 프로덕션 소스의 {@code runAnalysis()} / {@code runAnalysisResume()} 안에
   * 카운터 setter 직접 호출이 <b>0건</b>이다(TASK-006 전환 후 기준).
   */
  @Test
  void 병렬분석_루프와_재개_루프_안에는_카운터_setter_직접호출이_없다() {
    Path source = locateProductionSource();
    String text = readOrFail(source);

    List<Violation> violations = findViolations(text, true);
    System.out.println("[counter-atomicity] source=" + source);
    System.out.println("[counter-atomicity] guardedMethods="
        + String.join(", ", GUARDED_METHODS) + " / allowedReceivers=" + ALLOWED_RECEIVERS);
    System.out.println("[counter-atomicity] violations=" + violations.size() + " " + violations);

    assertTrue(violations.isEmpty(),
        "병렬 분석 루프 / 재개 루프 안에서 AnalysisStatistics의 카운터 setter를 직접 호출하고 있다.\n  - "
            + String.join("\n  - ", violations.stream().map(Violation::toString).toList())
            + "\n" + WHY_THIS_IS_BLOCKED);
  }

  /**
   * <b>양성 대조군 (i)</b> — setter 직접 호출을 넣은 <b>가짜 소스</b>에서 검사 로직이 반드시 검출한다.
   * <b>동시에 오탐도 확인한다</b>: 같은 가짜 소스에 넣어 둔 정상 호출
   * ({@code history.setSuccessCount(...)})과 <b>다른 메서드</b>의 setter 호출은 걸리지 않아야 한다.
   * 이게 실패하면 위 본 검사의 "위반 0건"은 아무것도 증명하지 못한다.
   */
  @Test
  void 양성대조군1_setter를_직접_호출하는_가짜_소스에서는_검출하고_정상호출은_걸리지_않는다() {
    String fakeSource = String.join("\n",
        "class Fake {",
        "  private void runAnalysis(String sessionId) {",
        "    // 여기가 위반이다 — 병렬 루프 안에서 통계 setter를 직접 부른다",
        "    session.getStatistics().setSuccessCount(sc);",
        "    AnalysisStatistics stats = session.getStatistics();",
        "    stats.setSkipCount(ac);                       // 지역변수로 우회한 형태도 위반이다",
        "    // 아래는 정상이다 — AnalysisHistory의 동명 setter(루프 밖 결과 저장)",
        "    history.setSuccessCount(session.getStatistics().getSuccessCount());",
        "    history.setFailureCount(session.getStatistics().getFailureCount());",
        "    String brace = \"{ 이 중괄호는 리터럴이라 범위 계산을 흔들면 안 된다\";",
        "  }",
        "  private void runAnalysisResume(String sessionId) {",
        "    session.getStatistics().setFailureCount(session.getStatistics().getFailureCount() + 1);",
        "  }",
        "  private void 감시대상이_아닌_메서드() {",
        "    // 이 메서드는 GUARDED_METHODS가 아니므로 걸리지 않아야 한다",
        "    session.getStatistics().setSuccessCount(0);",
        "  }",
        "}");

    List<Violation> violations = findViolations(fakeSource, true);
    System.out.println("[positive-control-1 fake-source] violations=" + violations.size()
        + " " + violations);

    assertEquals(3, violations.size(),
        "가짜 소스에서 위반 3건(runAnalysis의 getStatistics() 1건 + 지역변수 우회 1건, "
            + "runAnalysisResume 1건)이 검출돼야 한다. 검출하지 못하면 이 감시 테스트는 탐지력이 없다. "
            + "violations=" + violations);

    // 오탐 확인 — history 수신자와 감시 범위 밖 메서드는 한 건도 걸리지 않는다.
    assertTrue(violations.stream().noneMatch(v -> "history".equals(v.receiver())),
        "AnalysisHistory의 동명 setter(정상 호출)를 위반으로 잡으면 오탐이다. violations=" + violations);
    assertTrue(violations.stream().allMatch(v -> GUARDED_METHODS[0].equals(v.method())
            || GUARDED_METHODS[1].equals(v.method())),
        "감시 범위 밖 메서드의 호출이 섞이면 오탐이다. violations=" + violations);
  }

  /**
   * <b>양성 대조군 (ii)</b> — <b>작업 시작 커밋 {@code 6d9673e}의 실소스</b>에서 수정 전 6곳이
   * 전부 검출된다. 이 케이스가 이 감시 테스트가 <b>실물 결함</b>을 잡는다는 증거다.
   *
   * <p>고정본이 원본과 바이트 단위로 같은지도 같은 실행에서 확인한다 — git blob 해시는
   * {@code "blob " + 길이 + "\0" + 내용}의 SHA-1이므로, 이 값이 맞으면 내용이 한 바이트도 다르지 않다.
   */
  @Test
  void 양성대조군2_수정전_실소스_고정본에서는_setter_직접호출_6곳이_모두_검출된다() throws Exception {
    byte[] fixtureBytes = readBeforeFixtureBytes();

    String actualBlobSha1 = gitBlobSha1(fixtureBytes);
    System.out.println("[positive-control-2 before-6d9673e] fixtureBytes=" + fixtureBytes.length
        + " blobSha1=" + actualBlobSha1);
    assertEquals(BEFORE_FIXTURE_BLOB_SHA1, actualBlobSha1,
        "고정본이 `git show 6d9673e:" + SOURCE_RELATIVE_PATH + "`와 바이트 단위로 같아야 한다. "
            + "어긋났다면 고정본이 편집된 것이므로 대조군으로 쓸 수 없다.");

    String beforeText = new String(fixtureBytes, StandardCharsets.UTF_8);
    List<Violation> violations = findViolations(beforeText, true);
    System.out.println("[positive-control-2 before-6d9673e] violations=" + violations.size());
    violations.forEach(v -> System.out.println("[positive-control-2 before-6d9673e]   " + v));

    assertEquals(6, violations.size(),
        "수정 전 실소스에서는 setter 직접 호출 6곳이 검출돼야 한다 — 이게 실패하면 이 감시 테스트는 "
            + "실물 결함을 잡지 못하는 것이므로 즉시 멈추고 보고해야 한다. violations=" + violations);

    List<Integer> lines = violations.stream().map(Violation::line).sorted().toList();
    assertEquals(List.of(1483, 1488, 1496, 1759, 1763, 1768), lines,
        "검출 위치가 수정 전 실소스의 6곳(runAnalysis 1483·1488·1496 / "
            + "runAnalysisResume 1759·1763·1768)과 일치해야 한다. lines=" + lines);
    assertTrue(violations.stream().allMatch(v -> "getStatistics()".equals(v.receiver())),
        "수정 전 6곳은 전부 session.getStatistics() 수신자여야 한다. violations=" + violations);
  }

  /**
   * <b>읽기 실패 / 감시 대상 소실은 조용히 통과하지 않는다.</b>
   * 대상 파일이나 메서드가 사라지면 "위반 0건 → GREEN"이 아니라 명시적 실패가 되어야 한다.
   */
  @Test
  void 소스나_감시대상_메서드를_찾지_못하면_조용히_통과하지_않고_명시적으로_실패한다() {
    Path missing = Path.of("").toAbsolutePath()
        .resolve("src/main/java/com/legacy/analysis/__NoSuchFile__.java");
    AssertionError readError = assertThrows(AssertionError.class, () -> readOrFail(missing));
    System.out.println("[read-failure-check] message=" + readError.getMessage());
    assertTrue(readError.getMessage().contains("__NoSuchFile__"),
        "실패 메시지에 읽지 못한 경로가 드러나야 한다: " + readError.getMessage());

    // 메서드명이 바뀐 상황: 위반 0건이지만 감시는 빈 채다 → 통과시키면 안 된다.
    String renamed = String.join("\n",
        "class Renamed {",
        "  private void 이름이_바뀐_분석루프() {",
        "    session.getStatistics().setSuccessCount(sc);",
        "  }",
        "}");
    AssertionError methodError =
        assertThrows(AssertionError.class, () -> findViolations(renamed, true));
    System.out.println("[method-missing-check] message=" + methodError.getMessage());
    assertTrue(methodError.getMessage().contains("runAnalysis"),
        "실패 메시지에 찾지 못한 메서드명이 드러나야 한다: " + methodError.getMessage());

    Path located = locateProductionSource();
    System.out.println("[read-failure-check] located=" + located);
    assertTrue(Files.isRegularFile(located), "감시 대상 소스가 실제로 존재해야 한다: " + located);
  }

  /**
   * <b>범위 계산이 리터럴/주석에 흔들리지 않는다</b>(1층의 전제).
   * 문자열 안의 중괄호 하나에 메서드 범위가 어긋나면 감시가 통째로 무력해진다.
   */
  @Test
  void 메서드_범위_계산은_문자열과_주석_속_중괄호에_흔들리지_않는다() {
    String tricky = String.join("\n",
        "class Tricky {",
        "  private void runAnalysis() {",
        "    String s = \"} 이 닫는 중괄호는 리터럴이다\";",
        "    // } 주석 속 닫는 중괄호",
        "    /* } 블록 주석 속 닫는 중괄호 */",
        "    char c = '}';",
        "    session.getStatistics().setSuccessCount(sc);",   // 7행 — 반드시 범위 안이어야 검출된다
        "  }",
        "  private void runAnalysisResume() {",
        "  }",
        "}");

    List<Violation> violations = findViolations(tricky, true);
    System.out.println("[brace-robustness] violations=" + violations);
    assertEquals(1, violations.size(),
        "리터럴·주석 속 중괄호 때문에 메서드 범위가 일찍 끊기면 7행의 위반을 놓친다. violations="
            + violations);
    assertEquals(7, violations.get(0).line(),
        "검출 위치는 7행이어야 한다. violations=" + violations);
  }

  /** git blob 해시({@code "blob " + 길이 + "\0" + 내용}의 SHA-1)를 계산한다. */
  private static String gitBlobSha1(byte[] content) throws Exception {
    java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
    sha1.update(("blob " + content.length + "\0").getBytes(StandardCharsets.UTF_8));
    sha1.update(content);
    StringBuilder hex = new StringBuilder();
    for (byte b : sha1.digest()) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }
}
