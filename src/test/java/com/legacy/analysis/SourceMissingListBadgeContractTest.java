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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-003 (work-order 2026-10-resume-consistency-and-local-guard v1, REQ-004) —
 * 목록 화면 두 곳({@code my-activity.html renderHistory()} / {@code admin/dashboard.html renderMyHistory()})의
 * <b>원본 소실 표시가 문자 단위로 같고, 판정 순서가 맞는지</b>를 정적으로 단언한다.
 *
 * <p>이 저장소는 같은 목록을 그리는 두 화면이 한쪽만 고쳐져 벌어진 이력이 반복됐다
 * (선례 {@code PauseSettledFrontGatingContractTest} DoD 1). 그래서 새 분기도 같은 방식으로 고정한다.
 *
 * <p><b>순서가 왜 계약인가</b>: MISSING 분기가 "확정 → 버튼" 분기보다 <b>뒤</b>로 가면, 원본이 사라진
 * 세션에도 재개 버튼이 먼저 걸려 사람이 누르게 된다(서버가 거부하므로 기록은 안전하지만, 사전 표시로
 * 막자는 요구사항 자체가 무력화된다).
 *
 * <p>추출기의 탐지력은 <b>착수 커밋 고정본</b>(TASK-001이 떠 둔 두 HTML 사본)으로 증명한다 — 같은
 * 추출기를 고정본에 걸면 "MISSING 분기 없음"이 검출돼야 한다.
 */
class SourceMissingListBadgeContractTest {

  private static final String MY_ACTIVITY = "src/main/resources/templates/my-activity.html";
  private static final String ADMIN_DASHBOARD = "src/main/resources/templates/admin/dashboard.html";

  private static final String MY_ACTIVITY_FIXTURE = "/resumeconsistency/my-activity.html.before-7cad9d1.txt";
  private static final String ADMIN_DASHBOARD_FIXTURE = "/resumeconsistency/admin-dashboard.html.before-7cad9d1.txt";
  /** `git rev-parse 7cad9d1:src/main/resources/templates/my-activity.html` */
  private static final String MY_ACTIVITY_FIXTURE_BLOB_SHA1 = "ffe079d917314111957dbe1aec96ecca7fbb06ba";
  /** `git rev-parse 7cad9d1:src/main/resources/templates/admin/dashboard.html` */
  private static final String ADMIN_DASHBOARD_FIXTURE_BLOB_SHA1 = "9d3d3a9929b4bacc3eed8beafca212081a16264e";

  /** work-order TASK-003 작업 2가 지정한 MISSING 분기 조건(②③ 공통, 문자 단위). */
  private static final String EXPECTED_MISSING_CONDITION =
      "h.status === 'PAUSED' && h.sessionId && h.sourceAvailability === 'MISSING'";
  /** PARTIAL 덧붙임 조건(②③ 공통, 문자 단위). */
  private static final String EXPECTED_PARTIAL_CONDITION =
      "h.status === 'PAUSED' && h.sourceAvailability === 'PARTIAL'";
  /** 기존 "확정 → 버튼" 분기 조건 — MISSING 분기가 이보다 앞이어야 한다. */
  private static final String SETTLED_CONDITION =
      "h.status === 'PAUSED' && h.sessionId && h.pauseSettled !== false";

  // ===================================================================
  // 두 화면 동일성
  // ===================================================================

  @Test
  void 두_목록화면의_MISSING_분기_조건과_마크업이_문자_단위로_동일하다() {
    Branch mine = missingBranch(readOrFail(locate(MY_ACTIVITY)), MY_ACTIVITY);
    Branch admin = missingBranch(readOrFail(locate(ADMIN_DASHBOARD)), ADMIN_DASHBOARD);

    assertEquals(mine.condition(), admin.condition(),
        "②③의 MISSING 분기 조건이 다르다(한쪽만 고쳐진 이력 재발)");
    assertEquals(mine.markup(), admin.markup(),
        "②③의 MISSING 분기 문면(템플릿 리터럴 전체)이 다르다");
    assertEquals(EXPECTED_MISSING_CONDITION, mine.condition(), "work-order가 지정한 조건과 다르다");
    System.out.println("[MISSING 분기] 조건=" + mine.condition());
    System.out.println("[MISSING 분기] 문면=" + mine.markup());
  }

  @Test
  void 두_목록화면의_PARTIAL_덧붙임_조건과_문면이_문자_단위로_동일하다() {
    Branch mine = partialAppend(readOrFail(locate(MY_ACTIVITY)), MY_ACTIVITY);
    Branch admin = partialAppend(readOrFail(locate(ADMIN_DASHBOARD)), ADMIN_DASHBOARD);

    assertEquals(mine.condition(), admin.condition(), "②③의 PARTIAL 덧붙임 조건이 다르다");
    assertEquals(mine.markup(), admin.markup(), "②③의 PARTIAL 경고 문면이 다르다");
    assertEquals(EXPECTED_PARTIAL_CONDITION, mine.condition(), "work-order가 지정한 조건과 다르다");

    // 숫자는 Number()로 감싼다(서버 값이지만 innerHTML 조립이므로 방어적으로).
    assertTrue(mine.markup().contains("${Number(h.missingFileCount) || 0}"),
        "missingFileCount를 Number()로 감싸지 않았다: " + mine.markup());
    assertTrue(mine.markup().contains("${Number(h.pendingFileCount) || 0}"),
        "pendingFileCount를 Number()로 감싸지 않았다: " + mine.markup());
    System.out.println("[PARTIAL 덧붙임] 조건=" + mine.condition());
    System.out.println("[PARTIAL 덧붙임] 문면=" + mine.markup());
  }

  // ===================================================================
  // 순서
  // ===================================================================

  @Test
  void MISSING_분기가_확정_버튼_분기보다_앞에_있다() {
    for (String file : List.of(MY_ACTIVITY, ADMIN_DASHBOARD)) {
      String source = readOrFail(locate(file));
      int missingAt = source.indexOf(EXPECTED_MISSING_CONDITION);
      int settledAt = source.indexOf(SETTLED_CONDITION);
      assertTrue(missingAt >= 0, file + ": MISSING 분기 조건을 찾지 못했다");
      assertTrue(settledAt >= 0, file + ": 기존 '확정 → 버튼' 분기 조건을 찾지 못했다(계약 기준점 소실)");
      assertTrue(missingAt < settledAt,
          file + ": MISSING 분기가 '확정 → 버튼' 분기보다 앞에 있어야 한다."
              + " missing=" + missingAt + ", settled=" + settledAt);
      System.out.println("[순서] " + file + " missing=" + missingAt + " < settled=" + settledAt);
    }
  }

  // ===================================================================
  // 문면·금지 토큰
  // ===================================================================

  @Test
  void MISSING_문면에_사유와_보존_안내가_있고_금지_토큰이_없다() {
    for (String file : List.of(MY_ACTIVITY, ADMIN_DASHBOARD)) {
      Branch b = missingBranch(readOrFail(locate(file)), file);

      assertTrue(b.markup().contains("원본 소실 — 재개 불가"),
          file + ": 사유 문구 '원본 소실 — 재개 불가'가 없다: " + b.markup());
      assertTrue(b.markup().contains("보존"),
          file + ": 기록이 보존된다는 안내가 없다: " + b.markup());
      assertTrue(b.markup().contains("처음부터 새로 분석"),
          file + ": 처음부터 새로 분석하라는 안내가 없다: " + b.markup());

      // pauseSettled라는 글자를 넣으면 기존 PauseSettledFrontGatingContractTest의 추출 정규식이
      // 이 분기부터 매치를 시작해 "판정 분기가 2회"로 터진다(C7).
      // fetch(/resumeAnalysis(/타이머는 "사전 표시만 하고 자동 동작은 넣지 않는다"는 설계대로 금지.
      for (String forbidden : List.of("pauseSettled", "setTimeout", "setInterval",
          "fetch(", "resumeAnalysis(", "resumeMyAnalysis(")) {
        assertFalse(b.condition().contains(forbidden),
            file + ": MISSING 분기 조건에 '" + forbidden + "'가 있다: " + b.condition());
        assertFalse(b.markup().contains(forbidden),
            file + ": MISSING 분기 문면에 '" + forbidden + "'가 있다: " + b.markup());
      }

      // 누를 수 있는 것을 띄우지 않는다(사유 표시로 대체) — 이 분기에 버튼이 남으면 사전 표시가 무의미하다.
      // "이어서 분석"이라는 글자 자체는 금지하지 않는다 — title 안내가 "이어서 분석할 수 없습니다"로
      // 그 표현을 정당하게 쓴다. 금지 대상은 <button> 요소와 onclick 핸들러다.
      assertFalse(b.markup().contains("<button"),
          file + ": MISSING 분기에 <button> 요소가 남아 있다: " + b.markup());
      assertFalse(b.markup().contains("onclick="),
          file + ": MISSING 분기에 onclick 핸들러가 있다: " + b.markup());
    }
  }

  @Test
  void MISSING_분기_본문에는_pauseSettled라는_글자가_없어_기존_추출_정규식이_보호된다() {
    // 기존 계약 테스트의 PAUSED_BRANCH 정규식은 `} else if (…h.pauseSettled…) {` 로 매치를 시작한다.
    // 새 분기가 그 모양을 또 만들면 "정확히 1회"가 깨진다 — 두 파일에서 그 모양이 1회뿐임을 직접 센다.
    for (String file : List.of(MY_ACTIVITY, ADMIN_DASHBOARD)) {
      String source = readOrFail(locate(file));
      int count = countOccurrences(source, "} else if (" + SETTLED_CONDITION + ") {");
      assertEquals(1, count,
          file + ": `} else if (확정조건) {` 모양이 정확히 1회여야 한다(실제 " + count + "회)");
    }
  }

  // ===================================================================
  // 고정본 대조군
  // ===================================================================

  @Test
  void 대조군_착수커밋_고정본_두_HTML에는_같은_추출기가_MISSING_분기_없음을_검출한다() throws Exception {
    for (String[] pair : List.of(
        new String[] {MY_ACTIVITY_FIXTURE, MY_ACTIVITY_FIXTURE_BLOB_SHA1, MY_ACTIVITY},
        new String[] {ADMIN_DASHBOARD_FIXTURE, ADMIN_DASHBOARD_FIXTURE_BLOB_SHA1, ADMIN_DASHBOARD})) {
      String fixture = fixtureSource(pair[0], pair[1], pair[2]);

      // ① MISSING 분기·PARTIAL 덧붙임이 아예 없다 → 추출기가 실패(fail)해야 한다.
      try {
        missingBranch(fixture, "고정본 " + pair[0]);
        fail("고정본에 MISSING 분기가 없는데 추출기가 통과시켰다: " + pair[0]);
      } catch (AssertionError expected) {
        assertTrue(expected.getMessage().contains("정확히 1회"), expected.getMessage());
      }
      try {
        partialAppend(fixture, "고정본 " + pair[0]);
        fail("고정본에 PARTIAL 덧붙임이 없는데 추출기가 통과시켰다: " + pair[0]);
      } catch (AssertionError expected) {
        assertTrue(expected.getMessage().contains("정확히 1회"), expected.getMessage());
      }

      // ② 조건 문자열 자체가 0회다.
      assertEquals(0, countOccurrences(fixture, EXPECTED_MISSING_CONDITION),
          "고정본에 MISSING 조건이 있어서는 안 된다: " + pair[0]);
      assertEquals(0, countOccurrences(fixture, EXPECTED_PARTIAL_CONDITION),
          "고정본에 PARTIAL 조건이 있어서는 안 된다: " + pair[0]);
      assertEquals(0, countOccurrences(fixture, "원본 소실 — 재개 불가"),
          "고정본에 사유 문구가 있어서는 안 된다: " + pair[0]);

      // ③ 대조군이 "추출기가 아무것도 못 읽어서" 통과한 게 아님을 보인다 — 고정본에도 기존 "확정 → 버튼"
      //    분기는 정확히 1회 있고, 현재 파일과 같은 문자열이다.
      assertEquals(1, countOccurrences(fixture, "} else if (" + SETTLED_CONDITION + ") {"),
          "고정본에 기존 '확정 → 버튼' 분기가 1회 있어야 한다(추출 기준점 확인): " + pair[0]);

      System.out.println("[대조군 " + pair[0] + "] MISSING 조건 0회 / PARTIAL 조건 0회 /"
          + " 사유 문구 0회 / 기존 확정 분기 1회");
    }
  }

  // ===================================================================
  // 도우미
  // ===================================================================

  private record Branch(String condition, String markup) {}

  /** `} else if (조건) { …주석… actionBtn(s) = `마크업`;` 에서 조건과 마크업을 뽑는다. */
  private static final Pattern MISSING_BRANCH = Pattern.compile(
      "\\} else if \\((?<cond>[^\\n]*?h\\.sourceAvailability === 'MISSING')\\) \\{\\s*\\n"
          + "(?:\\s*//[^\\n]*\\n)*"
          + "\\s*actionBtns? = `(?<markup>[^\\n]*)`;");

  /** `if (조건) {\n actionBtn(s) += `마크업`;` 에서 조건과 마크업을 뽑는다. */
  private static final Pattern PARTIAL_APPEND = Pattern.compile(
      "\\bif \\((?<cond>[^\\n]*?h\\.sourceAvailability === 'PARTIAL')\\) \\{\\s*\\n"
          + "\\s*actionBtns? \\+= `(?<markup>[^\\n]*)`;");

  private static Branch missingBranch(String source, String label) {
    return extractExactlyOne(MISSING_BRANCH, source, label, "MISSING 분기(조건 → 사유 표시)");
  }

  private static Branch partialAppend(String source, String label) {
    return extractExactlyOne(PARTIAL_APPEND, source, label, "PARTIAL 덧붙임(조건 → 경고 표시)");
  }

  private static Branch extractExactlyOne(Pattern pattern, String source, String label, String what) {
    Matcher m = pattern.matcher(source);
    List<Branch> found = new ArrayList<>();
    while (m.find()) {
      found.add(new Branch(m.group("cond"), m.group("markup")));
    }
    if (found.size() != 1) {
      return fail(label + ": " + what + "가 정확히 1회 나와야 한다. 실제 " + found.size()
          + "회 — 구조가 바뀌었으면 이 테스트의 추출 패턴부터 다시 맞춰라(읽지 못한 채 통과시키지 않는다)");
    }
    return found.get(0);
  }

  private static int countOccurrences(String haystack, String needle) {
    int n = 0;
    int from = 0;
    while (true) {
      int at = haystack.indexOf(needle, from);
      if (at < 0) return n;
      n++;
      from = at + needle.length();
    }
  }

  /** 고정본을 클래스패스에서 읽고 git blob 해시로 바이트 동일성을 먼저 확인한다. */
  private static String fixtureSource(String resource, String expectedBlobSha1, String originalPath)
      throws Exception {
    byte[] bytes;
    try (InputStream in = SourceMissingListBadgeContractTest.class.getResourceAsStream(resource)) {
      if (in == null) {
        return fail("착수 커밋 고정본이 클래스패스에 없다: " + resource
            + " — 이 파일이 없으면 추출기의 탐지력을 증명할 수 없다.");
      }
      bytes = in.readAllBytes();
    }
    assertEquals(expectedBlobSha1, gitBlobSha1(bytes),
        "고정본이 `git show 7cad9d1:" + originalPath + "`과 바이트 단위로 같아야 한다."
            + " 어긋났다면 고정본이 편집된 것이므로 대조군으로 쓸 수 없다.");
    return new String(bytes, StandardCharsets.UTF_8);
  }

  /** git blob 해시({@code "blob " + 길이 + "\0" + 내용}의 SHA-1). */
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
