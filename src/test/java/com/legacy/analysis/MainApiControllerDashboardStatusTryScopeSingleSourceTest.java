package com.legacy.analysis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-009 S1 (work-order 2026-09-quick-fixes-batch v5 §0.11 (B) / 작업 8) —
 * <b>{@code getDashboardStatus()}의 위임 호출 3줄이 기존 {@code catch (Exception e)}의 보호 범위
 * 안에 있는지</b>를 프로덕션 소스를 <b>텍스트로 읽어</b> 감시한다. 게이트1 ⑧(a)(try 범위 확장)의
 * <b>전용 근거</b>이며, 이 클래스 자체는 프로덕션 코드를 한 줄도 바꾸지 않는다.
 *
 * <h2>왜 이 테스트가 따로 필요한가 (§0.11 — "공허한 통과" 차단)</h2>
 * REQ-004의 동작 케이스 R2/R3를 GREEN으로 만드는 <b>최소 조건은 {@code folderPathStr} 파싱 검사
 * 하나뿐</b>이다. 즉 ⑧(a)를 구현하지 않아도 R2/R3는 GREEN이 되므로, "R2/R3가 통과했다"는 사실은
 * ⑧(a)가 반영됐다는 근거가 되지 못한다. 판정 기준은 <b>"이 단언이 통과했다"가 아니라
 * "이 변경을 빼면 이 단언이 깨진다"</b>이며, 이 클래스는 그 성질을 갖도록 설계됐다 —
 * 커밋 3분할에서 <b>C0·C1에서 RED, C2(⑧(a) 단독 커밋)에서 GREEN</b>으로 전이한다.
 *
 * <h2>무엇을 어떻게 본다는 것인가 (2층 판정)</h2>
 * <ol>
 *   <li><b>1층 — 범위 확정</b>: 소스를 중괄호 균형(문자열·문자 리터럴·주석 제거 후)으로 훑어
 *       {@code getDashboardStatus} 메서드 본문의 행 범위를 확정한다. 파일 전역 grep으로는
 *       다른 메서드의 {@code try}/{@code catch}가 섞여 아무것도 판정할 수 없다.</li>
 *   <li><b>2층 — 보호 범위 판정</b>: 그 범위 안에서 <b>마지막</b> {@code catch (Exception ...)}을 찾고
 *       (메서드 본문 중간의 {@code catch (Exception ignored)}와 구분된다), <b>그 catch가 실제로 달려 있는
 *       try</b>를 브레이스 매칭으로 역산한다. 그런 다음 감시 대상 3줄이 그 try와 catch <b>사이</b>에
 *       있는지 본다.</li>
 * </ol>
 *
 * <h2>감시 대상 3줄</h2>
 * <ul>
 *   <li>{@code resolveUserOutputRoot(} — 내부 805행 {@code Path.of(outputPath.trim())}이 던질 수 있다</li>
 *   <li>{@code resolveProjectOutputRoot(} — 내부 815행 {@code Path.of(sourcePath)} / 818행
 *       {@code getFileName().toString()}(루트 경로면 NPE)이 던질 수 있다</li>
 *   <li>{@code Path.of(folderPathStr)} — 파싱 불가 문자열이면 그 자리에서 던진다</li>
 * </ul>
 * 수정 전에는 이 3줄이 전부 {@code try}보다 <b>앞</b>이었고, 이 프로젝트에는 전역 예외 처리기
 * ({@code @ControllerAdvice})가 0건이라 여기서 던져진 예외는 응답 없이 그대로 빠져나갔다(HTTP 500).
 *
 * <h2>판정 기준을 work-order 문구에서 한 단계 일반화한 이유 (dev 판단 — 반드시 읽을 것)</h2>
 * work-order v5 작업 8은 <b>{@code try (Stream<Path> stream = Files.walk(} 줄의 인덱스</b>를 기준으로
 * 세 비교를 하라고 적고 있다. 그런데 <b>그 문구 그대로는 Java 문법상 셋을 동시에 만족시킬 수 없다</b>:
 * try-with-resources의 자원 표현식({@code Files.walk(folderPath)})은 {@code try} 키워드보다 <b>앞에</b>
 * 선언된 변수만 참조할 수 있으므로, {@code Path folderPath = Path.of(folderPathStr);}를 try 안으로
 * 넣으면 {@code Files.walk(folderPath)}가 컴파일되지 않는다. 자원 표현식에 {@code Path.of(folderPathStr)}을
 * 인라인하면 이번엔 그 토큰이 <b>try 줄 자체</b>에 놓여 "try 인덱스 &lt; Path.of(folderPathStr) 인덱스"가
 * 성립하지 않는다.
 *
 * <p>그래서 구현은 <b>기존 try-with-resources를 감싸는 바깥 {@code try}로 범위를 확장</b>했고(기존
 * {@code catch (Exception e)}가 그 바깥 try에 달린다 — 실행 순서와 자원 닫힘 시점이 바뀌지 않는 형태),
 * 이 테스트는 기준점을 <b>"기존 catch가 달려 있는 try"</b>로 잡는다. 이는 work-order 문구의 <b>의도</b>
 * (= 세 줄이 기존 catch의 보호 범위 안에 들어올 것)를 그대로 판정하며, 오히려 더 강하다 —
 * "catch보다 앞"뿐 아니라 <b>"그 catch에 실제로 달린 try보다 뒤"</b>까지 함께 보기 때문이다.
 * 이 어긋남과 근거는 {@code 05-dev-progress.md} TASK-009 절에 기록했다(PL 판단 요청 사항).
 *
 * <h2>양성 대조군 / 조용한 통과 방지</h2>
 * 음성 결과("위반 0건")를 근거로 쓰려면 같은 검사가 실제 위반을 검출한다는 것을 같은 실행 안에서
 * 보여야 한다(STRUCTURE.md 20절, work-order §0.4). 대조군은 저장소에 이미 있는 <b>수정 전 실소스
 * 고정본</b>({@value #BEFORE_FIXTURE_RESOURCE})이며, 여기서는 try가 위임 호출보다 <b>뒤</b>라는 사실이
 * 행번호(995/996/998 vs 1000)까지 특정돼 검출돼야 한다. 고정본은 <b>읽기 전용</b>이고 blob 해시로
 * 무결성을 함께 확인한다. 소스·메서드·감시 대상 줄을 찾지 못하면 "위반 0건"으로 통과시키지 않고
 * 명시적으로 실패한다(TASK-007이 확립한 관행).
 */
class MainApiControllerDashboardStatusTryScopeSingleSourceTest {

  /** 검사 대상 프로덕션 소스(저장소 루트 기준 상대 경로). <b>디렉터리를 훑지 않는다.</b> */
  private static final String SOURCE_RELATIVE_PATH =
      "src/main/java/com/legacy/analysis/MainApiController.java";

  /**
   * 양성 대조군 — 작업 시작 커밋 {@code 6d9673e}의 실소스 고정본(클래스패스 리소스).
   * TASK-007이 보존한 파일을 <b>재사용</b>하며 수정하지 않는다(work-order §0.10 / §19 동결).
   */
  private static final String BEFORE_FIXTURE_RESOURCE =
      "/counteratomicity/MainApiController.before-6d9673e.java.txt";

  /** 고정본의 git blob 해시. {@code git hash-object <고정본>}과 일치해야 한다. */
  private static final String BEFORE_FIXTURE_BLOB_SHA1 =
      "96cd1ffef0081512167b9f9f381841c0f7b2a64b";

  /** 감시 대상 메서드. 선언부를 행 머리 접근제어자로 식별한다. */
  private static final String GUARDED_METHOD = "getDashboardStatus";

  /**
   * 기존 catch의 보호 범위 안에 있어야 하는 3줄. 값은 <b>소스에 그대로 나타나는 토큰</b>이며,
   * 메서드 범위 안에서 <b>처음 나타나는 줄</b>을 위치로 삼는다.
   */
  private static final List<String> GUARDED_TARGETS = List.of(
      "resolveUserOutputRoot(",
      "resolveProjectOutputRoot(",
      "Path.of(folderPathStr)");

  /** 실제 디렉터리 순회를 여는 줄 — 확장된 try 범위 <b>안</b>에 그대로 남아 있어야 한다. */
  private static final String WALK_TOKEN = "Files.walk(";

  /** 메서드 본문의 마지막 {@code catch (Exception ...)} — 잔여 예외를 흡수하는 그 catch다. */
  private static final Pattern CATCH_EXCEPTION =
      Pattern.compile("catch\\s*\\(\\s*(?:final\\s+)?Exception\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\)");

  /** {@code try} 키워드로 시작하는 줄(블록 try / try-with-resources 공통). */
  private static final Pattern TRY_LINE = Pattern.compile("(^|[^A-Za-z0-9_$])try\\s*[({]");

  /** 실패 메시지에 함께 실어 보내는 "왜 막혀 있는가" 안내. */
  private static final String WHY_THIS_IS_BLOCKED = String.join("\n",
      "",
      "── 왜 이 검사가 있는가 ────────────────────────────────────────",
      "getDashboardStatus()는 요청 파라미터(folderPath / outputPath)를 그대로",
      "Path.of()에 넘기는 위임 호출 3줄을 갖고 있다. 이 줄들이 기존",
      "catch (Exception e)의 보호 범위 밖에 있으면, 사용자가 넣은 경로 한 글자",
      "때문에 응답이 아예 만들어지지 않는다 — 이 프로젝트에는 @ControllerAdvice가",
      "0건이라 그대로 HTTP 500이 된다(실제로 folderPath 후행 공백에서 재현됐다).",
      "",
      "진입 가드(isParsablePath)가 1차 방어이고, try 범위 확장이 2차(심층) 방어다.",
      "가드가 미처 거르지 못하는 형태 — 예: 파일시스템 루트 경로(getFileName()이",
      "null이라 818행에서 NPE) — 는 이 try 범위만이 흡수할 수 있다.",
      "",
      "위임 호출을 try 밖으로 다시 꺼내야 하는 정당한 사유가 있다면,",
      "이 테스트를 고치기 전에 리뷰를 받아라.",
      "────────────────────────────────────────────────────────────");

  // ────────────────────────────────────────────────────────────────────────────
  // 검사 로직 (순수 함수 — 실소스/가짜 소스/수정 전 고정본에 똑같이 적용된다)
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * 한 번의 검사 결과. 행번호는 <b>1-based</b>(소스 편집기와 같은 기준)다.
   *
   * @param tryLine     기존 catch가 달려 있는 try의 행번호
   * @param catchLine   그 catch의 행번호
   * @param targetLines 감시 대상 토큰별 첫 등장 행번호
   * @param walkLine    {@code Files.walk(}의 행번호
   * @param violations  "보호 범위 밖" 위반 목록(비어 있으면 전부 보호 범위 안)
   */
  private record TryScope(int tryLine, int catchLine, Map<String, Integer> targetLines,
      int walkLine, List<String> violations) {
  }

  /**
   * {@link #GUARDED_METHOD} 본문에서 "기존 catch가 달린 try"를 찾아 감시 대상 3줄이 그 안에 있는지 본다.
   * 소스/메서드/catch/try/대상 줄 중 <b>하나라도 찾지 못하면 조용히 통과시키지 않고 즉시 실패</b>시킨다 —
   * 감시가 빈 채로 GREEN이 되는 것이 이 테스트의 최악 실패 모드다.
   */
  private static TryScope analyze(String sourceText, String sourceLabel) {
    String[] rawLines = sourceText.replace("\r\n", "\n").split("\n", -1);
    String[] sanitized = sanitizeForBraceCounting(rawLines);

    int[] range = locateMethodBody(rawLines, sanitized, GUARDED_METHOD);
    if (range == null) {
      return fail("[" + sourceLabel + "] 감시 대상 메서드 선언을 찾지 못했다: " + GUARDED_METHOD
          + "() — 이름이 바뀌었다면 GUARDED_METHOD를 갱신하라. 찾지 못한 채 통과시키지 않는다.");
    }

    // 메서드 본문의 "마지막" catch (Exception ...) — 중간의 catch (Exception ignored)와 구분된다.
    int catchIdx = -1;
    for (int i = range[0]; i <= range[1]; i++) {
      if (CATCH_EXCEPTION.matcher(sanitized[i]).find()) {
        catchIdx = i;
      }
    }
    if (catchIdx < 0) {
      return fail("[" + sourceLabel + "] " + GUARDED_METHOD
          + "() 안에서 잔여 예외를 흡수하는 catch (Exception ...)을 찾지 못했다."
          + " 이 catch가 사라지면 try 범위 확장은 아무 의미가 없다." + WHY_THIS_IS_BLOCKED);
    }

    // 그 catch가 실제로 달려 있는 try를 역산한다 — try 블록이 닫히는 줄이 catch 줄과 같아야 한다.
    int tryIdx = -1;
    for (int i = range[0]; i < catchIdx; i++) {
      if (!TRY_LINE.matcher(sanitized[i]).find()) {
        continue;
      }
      if (tryBlockEndLine(sanitized, i) == catchIdx) {
        tryIdx = i;
      }
    }
    if (tryIdx < 0) {
      return fail("[" + sourceLabel + "] " + (catchIdx + 1) + "행의 catch에 달린 try를 찾지 못했다"
          + " — 브레이스 매칭이 어긋났거나 구조가 크게 바뀐 것이다. 통과시키지 않는다.");
    }

    Map<String, Integer> targetLines = new LinkedHashMap<>();
    for (String token : GUARDED_TARGETS) {
      int found = firstLineContaining(sanitized, range, token);
      if (found < 0) {
        return fail("[" + sourceLabel + "] 감시 대상 줄을 찾지 못했다: '" + token + "' in "
            + GUARDED_METHOD + "() — 호출 형태가 바뀌었다면 GUARDED_TARGETS를 갱신하라."
            + " 찾지 못한 채 '위반 0건'으로 통과시키지 않는다." + WHY_THIS_IS_BLOCKED);
      }
      targetLines.put(token, found + 1);
    }

    int walkIdx = firstLineContaining(sanitized, range, WALK_TOKEN);
    if (walkIdx < 0) {
      return fail("[" + sourceLabel + "] 디렉터리 순회 줄('" + WALK_TOKEN + "')을 찾지 못했다"
          + " — 스캔 본체가 사라졌다면 이 감시의 전제가 무너진 것이다.");
    }

    List<String> violations = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : targetLines.entrySet()) {
      int line = entry.getValue();
      if (line <= tryIdx + 1) {
        violations.add("'" + entry.getKey() + "' " + line + "행이 try(" + (tryIdx + 1)
            + "행)보다 앞이다 — 여기서 던져진 예외는 " + (catchIdx + 1) + "행 catch가 흡수하지 못한다");
      } else if (line >= catchIdx + 1) {
        violations.add("'" + entry.getKey() + "' " + line + "행이 catch(" + (catchIdx + 1)
            + "행)보다 뒤다 — 보호 범위 밖이다");
      }
    }
    return new TryScope(tryIdx + 1, catchIdx + 1, targetLines, walkIdx + 1, violations);
  }

  /**
   * 메서드 범위 안에서 토큰이 처음 나타나는 행 인덱스(0-based). 없으면 -1.
   *
   * <p><b>반드시 {@link #sanitizeForBraceCounting(String[])}를 거친 배열을 넘긴다</b> —
   * 원문으로 찾으면 <b>주석이 언급한 코드 토큰</b>이 잡혀 위치가 통째로 어긋난다.
   * 실제로 이 TASK의 C1 커밋이 추가한 설명 주석("아래 {@code resolveUserOutputRoot()}가 …")이
   * 위임 호출보다 앞줄에서 걸려 <b>C2에서 오탐 3건</b>이 났고, 그래서 코드 텍스트만 보도록 고쳤다.
   * 주석에 메서드명을 적는 것은 정상적인 일이므로, 감시 쪽이 코드만 보는 것이 맞다.
   */
  private static int firstLineContaining(String[] sanitizedLines, int[] range, String token) {
    for (int i = range[0]; i <= range[1]; i++) {
      if (sanitizedLines[i].contains(token)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * {@code try} 줄에서 시작해 그 블록이 닫히는 행 인덱스(0-based)를 돌려준다.
   * {@code } catch (...) {} 형태에서는 <b>닫는 중괄호가 있는 그 줄</b>이 반환된다
   * (같은 줄 뒤쪽의 여는 중괄호는 세지 않는다 — depth가 0이 되는 즉시 멈추기 때문).
   */
  private static int tryBlockEndLine(String[] sanitized, int tryLineIdx) {
    int depth = 0;
    boolean opened = false;
    for (int i = tryLineIdx; i < sanitized.length; i++) {
      for (char c : sanitized[i].toCharArray()) {
        if (c == '{') {
          depth++;
          opened = true;
        } else if (c == '}') {
          depth--;
          if (opened && depth == 0) {
            return i;
          }
        }
      }
    }
    return -1;
  }

  /**
   * 메서드 <b>선언부</b>를 찾아 본문의 시작/끝 행 인덱스(0-based, 양끝 포함)를 돌려준다.
   * 선언부는 "행 머리가 접근제어자 + 같은 행에 {@code 메서드명(}"으로 식별하므로,
   * 호출부나 주석 언급과 구분된다. (TASK-007 감시 테스트와 같은 로직이다.)
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

  /** gradle이 어느 디렉터리에서 실행되든 프로덕션 소스 <b>파일 하나</b>를 찾아낸다. */
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
    try (InputStream in = MainApiControllerDashboardStatusTryScopeSingleSourceTest.class
        .getResourceAsStream(BEFORE_FIXTURE_RESOURCE)) {
      if (in == null) {
        return fail("양성 대조군용 수정 전 실소스 고정본이 클래스패스에 없다: " + BEFORE_FIXTURE_RESOURCE
            + " — 이 파일이 없으면 이 감시 테스트의 탐지력을 증명할 수 없다.");
      }
      return in.readAllBytes();
    }
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

  private static void print(String label, TryScope scope) {
    System.out.println("[try-scope " + label + "] try=" + scope.tryLine() + "행 catch="
        + scope.catchLine() + "행 Files.walk=" + scope.walkLine() + "행 targets="
        + scope.targetLines());
    System.out.println("[try-scope " + label + "] violations=" + scope.violations().size()
        + " " + scope.violations());
  }

  // ────────────────────────────────────────────────────────────────────────────

  /**
   * <b>S1 본 검사</b> — 현재 프로덕션 소스에서 위임 호출 3줄이 전부 기존
   * {@code catch (Exception e)}의 보호 범위(= 그 catch가 달린 try 안) 에 있다.
   *
   * <p>게이트1 ⑧(a)를 되돌리면(= try를 다시 995행 뒤로 내리면) 이 케이스는 즉시 RED가 된다.
   */
  @Test
  void 위임호출_3줄이_기존_catch의_보호범위_안에_있다() {
    Path source = locateProductionSource();
    TryScope scope = analyze(readOrFail(source), "production");
    System.out.println("[try-scope production] source=" + source);
    print("production", scope);

    assertTrue(scope.violations().isEmpty(),
        "getDashboardStatus()의 위임 호출이 기존 catch (Exception e)의 보호 범위 밖에 있다.\n  - "
            + String.join("\n  - ", scope.violations()) + "\n" + WHY_THIS_IS_BLOCKED);
    assertTrue(scope.walkLine() > scope.tryLine() && scope.walkLine() < scope.catchLine(),
        "디렉터리 순회(Files.walk)도 같은 try 안에 남아 있어야 한다 — 기존 IOException 처리가 "
            + "빠지면 안 된다. try=" + scope.tryLine() + " walk=" + scope.walkLine()
            + " catch=" + scope.catchLine());
  }

  /**
   * <b>양성 대조군(필수)</b> — <b>작업 시작 커밋 {@code 6d9673e}의 실소스 고정본</b>에서는
   * 같은 검사가 "try가 위임 호출보다 뒤"를 <b>검출</b>한다. 이 케이스가 없으면 위 본 검사의
   * "위반 0건"은 아무것도 증명하지 못한다(STRUCTURE.md 20절 / work-order §0.4).
   *
   * <p>고정본이 원본과 바이트 단위로 같은지도 같은 실행에서 확인한다 — git blob 해시는
   * {@code "blob " + 길이 + "\0" + 내용}의 SHA-1이므로, 이 값이 맞으면 한 바이트도 다르지 않다.
   * <b>고정본은 읽기 전용이며 이 TASK에서 수정하지 않는다.</b>
   */
  @Test
  void 양성대조군_수정전_실소스_고정본에서는_try가_위임호출보다_뒤임을_검출한다() throws Exception {
    byte[] fixtureBytes = readBeforeFixtureBytes();

    String actualBlobSha1 = gitBlobSha1(fixtureBytes);
    System.out.println("[try-scope before-6d9673e] fixtureBytes=" + fixtureBytes.length
        + " blobSha1=" + actualBlobSha1);
    assertEquals(BEFORE_FIXTURE_BLOB_SHA1, actualBlobSha1,
        "고정본이 `git show 6d9673e:" + SOURCE_RELATIVE_PATH + "`와 바이트 단위로 같아야 한다. "
            + "어긋났다면 고정본이 편집된 것이므로 대조군으로 쓸 수 없다.");

    TryScope scope = analyze(new String(fixtureBytes, StandardCharsets.UTF_8), "before-6d9673e");
    print("before-6d9673e", scope);

    assertEquals(3, scope.violations().size(),
        "수정 전 실소스에서는 위임 호출 3줄이 전부 try 밖(앞)이라 3건이 검출돼야 한다 — "
            + "검출하지 못하면 이 감시 테스트는 탐지력이 없는 것이므로 즉시 멈추고 보고해야 한다. "
            + "violations=" + scope.violations());
    assertEquals(1000, scope.tryLine(),
        "수정 전 고정본에서 catch가 달린 try는 1000행(try (Stream<Path> stream = Files.walk(folderPath)))이다.");
    assertEquals(1058, scope.catchLine(), "수정 전 고정본의 catch (Exception e)는 1058행이다.");
    assertEquals(Map.of("resolveUserOutputRoot(", 995,
            "resolveProjectOutputRoot(", 996,
            "Path.of(folderPathStr)", 998),
        scope.targetLines(),
        "수정 전 고정본의 위임 호출 3줄은 995/996/998행이다. targets=" + scope.targetLines());
    assertTrue(scope.violations().stream().allMatch(v -> v.contains("보다 앞이다")),
        "수정 전 3건은 전부 'try보다 앞' 형태여야 한다. violations=" + scope.violations());
  }

  /**
   * <b>검사 로직이 실제로 위반을 잡는지</b>를 가짜 소스로 한 번 더 확인한다(대조군 (i)).
   * 같은 실행에서 <b>고친 형태는 통과</b>하는 것까지 본다 — 무조건 검출하는 검사라면 탐지력이 없다.
   */
  @Test
  void 양성대조군_가짜소스에서_try_밖이면_검출하고_try_안이면_통과한다() {
    String broken = String.join("\n",
        "class Fake {",
        "  public Map<String, Object> getDashboardStatus(Map<String, String> request) {",
        "    String userOutputPathStr = resolveUserOutputRoot(a, b, c);",
        "    Path outputRootPath = resolveProjectOutputRoot(a, b, c);",
        "    Path folderPath = Path.of(folderPathStr);",
        "    String brace = \"{ 이 중괄호는 리터럴이라 범위 계산을 흔들면 안 된다\";",
        "    try (Stream<Path> stream = Files.walk(folderPath)) {",
        "      return resultData;",
        "    } catch (Exception e) {",
        "      return resultData;",
        "    }",
        "  }",
        "}");
    TryScope brokenScope = analyze(broken, "fake-broken");
    print("fake-broken", brokenScope);
    assertEquals(3, brokenScope.violations().size(),
        "위임 3줄이 try 앞에 있는 가짜 소스에서 3건이 검출돼야 한다. " + brokenScope.violations());

    String fixed = String.join("\n",
        "class Fake {",
        "  public Map<String, Object> getDashboardStatus(Map<String, String> request) {",
        "    // 아래 resolveUserOutputRoot()/resolveProjectOutputRoot()/Path.of(folderPathStr)는",
        "    // 주석 속 언급이다 — 코드가 아니므로 위치로 잡히면 안 된다(실제로 오탐이 났던 형태).",
        "    try {",
        "      String userOutputPathStr = resolveUserOutputRoot(a, b, c);",
        "      Path outputRootPath = resolveProjectOutputRoot(a, b, c);",
        "      Path folderPath = Path.of(folderPathStr);",
        "      try (Stream<Path> stream = Files.walk(folderPath)) {",
        "        return resultData;",
        "      }",
        "    } catch (Exception e) {",
        "      return resultData;",
        "    }",
        "  }",
        "}");
    TryScope fixedScope = analyze(fixed, "fake-fixed");
    print("fake-fixed", fixedScope);
    assertTrue(fixedScope.violations().isEmpty(),
        "고친 형태(위임 3줄이 바깥 try 안)는 통과해야 한다 — 무조건 검출하는 검사라면 탐지력이 없다. "
            + fixedScope.violations());
    assertEquals(5, fixedScope.tryLine(), "바깥 try는 5행이다. scope=" + fixedScope);
    assertEquals(12, fixedScope.catchLine(),
        "안쪽 try-with-resources가 아니라 catch가 달린 바깥 try를 기준으로 잡아야 한다. scope=" + fixedScope);
    assertEquals(Map.of("resolveUserOutputRoot(", 6,
            "resolveProjectOutputRoot(", 7,
            "Path.of(folderPathStr)", 8),
        fixedScope.targetLines(),
        "주석(3~4행)이 아니라 코드(6~8행)를 위치로 잡아야 한다. targets=" + fixedScope.targetLines());
  }

  /**
   * <b>읽기 실패 / 감시 대상 소실은 조용히 통과하지 않는다.</b>
   * 대상 파일·메서드·catch·감시 대상 줄이 사라지면 "위반 0건 → GREEN"이 아니라 명시적 실패가 된다.
   */
  @Test
  void 소스나_감시대상을_찾지_못하면_조용히_통과하지_않고_명시적으로_실패한다() {
    Path missing = Path.of("").toAbsolutePath()
        .resolve("src/main/java/com/legacy/analysis/__NoSuchFile__.java");
    AssertionError readError = assertThrows(AssertionError.class, () -> readOrFail(missing));
    System.out.println("[silent-pass-check] read=" + readError.getMessage());
    assertTrue(readError.getMessage().contains("__NoSuchFile__"),
        "실패 메시지에 읽지 못한 경로가 드러나야 한다: " + readError.getMessage());

    AssertionError methodError = assertThrows(AssertionError.class,
        () -> analyze("class X {\n  private void 이름이_바뀐_메서드() {\n  }\n}", "no-method"));
    System.out.println("[silent-pass-check] method=" + methodError.getMessage());
    assertTrue(methodError.getMessage().contains(GUARDED_METHOD),
        "실패 메시지에 찾지 못한 메서드명이 드러나야 한다: " + methodError.getMessage());

    AssertionError catchError = assertThrows(AssertionError.class, () -> analyze(String.join("\n",
        "class X {",
        "  public Map<String, Object> getDashboardStatus(Map<String, String> request) {",
        "    Path folderPath = Path.of(folderPathStr);",
        "    return resultData;",
        "  }",
        "}"), "no-catch"));
    System.out.println("[silent-pass-check] catch=" + catchError.getMessage());
    assertTrue(catchError.getMessage().contains("catch (Exception ...)"),
        "catch가 없으면 명시적으로 실패해야 한다: " + catchError.getMessage());

    AssertionError targetError = assertThrows(AssertionError.class, () -> analyze(String.join("\n",
        "class X {",
        "  public Map<String, Object> getDashboardStatus(Map<String, String> request) {",
        "    try {",
        "      return resultData;",
        "    } catch (Exception e) {",
        "      return resultData;",
        "    }",
        "  }",
        "}"), "no-target"));
    System.out.println("[silent-pass-check] target=" + targetError.getMessage());
    assertTrue(targetError.getMessage().contains("resolveUserOutputRoot("),
        "감시 대상 줄이 사라지면 '위반 0건'이 아니라 명시적 실패여야 한다: " + targetError.getMessage());

    Path located = locateProductionSource();
    System.out.println("[silent-pass-check] located=" + located);
    assertTrue(Files.isRegularFile(located), "감시 대상 소스가 실제로 존재해야 한다: " + located);
  }
}
