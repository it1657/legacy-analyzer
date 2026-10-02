package com.legacy.analysis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-006 (work-order 2026-10-resume-consistency-and-local-guard v1, REQ-002 ③ + RG-2) —
 * <b>{@code historyId}가 PAUSED에서도 폴링 응답에 실리고, {@code finalizeAnalysis()}는 손대지 않았는지</b>를
 * 소스로 고정하는 정적 계약.
 *
 * <p>왜 정적 계약인가: 실행 테스트는 "지금 이 경로에서 historyId가 내려온다"를 보이지만, 나중에 누가
 * {@code historyId} 대입을 다시 COMPLETED 전용 블록으로 되돌리면 전량실패 PPT 버튼이 조용히 죽는다.
 * 그 조건식 자체를 고정한다.
 *
 * <p>검사기의 탐지력은 <b>착수 커밋 고정본</b>으로 증명한다 — 고정본의 {@code getAnalysisStatus}에서는
 * {@code historyId} 대입이 <b>COMPLETED 전용 블록 안에만</b> 있다는 것이 검출돼야 한다.
 *
 * <p>구조·개수 검사의 주석 제거는 {@link ResumeSourceGuardContractTest#blankComments(String)}를
 * 재사용한다. 단 <b>RG-2 해시는 주석을 포함한 원문</b>으로 계산한다(§0.5의 해시 대조 범위와 일치 —
 * 주석만 바뀐 변경도 잡아야 한다).
 */
class HistoryIdPausedExposureContractTest {

  private static final String CONTROLLER_SRC = "src/main/java/com/legacy/analysis/MainApiController.java";
  private static final String FIXTURE = "/resumeconsistency/MainApiController.java.before-7cad9d1.txt";
  /** `git rev-parse 7cad9d1:src/main/java/com/legacy/analysis/MainApiController.java` */
  private static final String FIXTURE_BLOB_SHA1 = "18a0b953e8842fac593a8ff0f34beaab032b9c4a";

  /** 착수 커밋의 {@code finalizeAnalysis} 본문 sha256 — RG-2(정상 완료 경로 불변). */
  private static final String FINALIZE_ANALYSIS_BODY_SHA256 =
      "a39a2c7e9c65535d6bbc34d9cc624e02d733d4d2ab30231c395fcad83bbeb5bc";

  private static final String HISTORY_ID_ASSIGN = "dto.setHistoryId(Long.parseLong(historyIdStr))";
  private static final String COMPLETED_ONLY_BLOCK = "if (\"COMPLETED\".equals(phase)) {";
  private static final String COMPLETED_OR_PAUSED_BLOCK =
      "if (\"COMPLETED\".equals(phase) || \"PAUSED\".equals(phase)) {";

  // ===================================================================
  // 현재 소스
  // ===================================================================

  @Test
  void getAnalysisStatus는_COMPLETED와_PAUSED_둘_다에서_historyId를_싣는다() {
    String body = methodBody(currentSource(), "getAnalysisStatus");

    int assignAt = body.indexOf(HISTORY_ID_ASSIGN);
    assertTrue(assignAt >= 0, "getAnalysisStatus에서 historyId 대입을 찾지 못했다(계약 기준점 소실)");
    assertEquals(1, countOccurrences(body, HISTORY_ID_ASSIGN),
        "historyId 대입이 1곳이어야 한다(사본이 생기면 조건이 갈라진다)");

    int pausedBlockAt = body.indexOf(COMPLETED_OR_PAUSED_BLOCK);
    assertTrue(pausedBlockAt >= 0,
        "`" + COMPLETED_OR_PAUSED_BLOCK + "` 블록이 없다 — historyId가 PAUSED에서 실리지 않는다");
    assertTrue(pausedBlockAt < assignAt,
        "historyId 대입이 COMPLETED||PAUSED 블록 안에 있어야 한다."
            + " block=" + pausedBlockAt + ", assign=" + assignAt);

    // 대입이 COMPLETED 전용 블록 쪽에 다시 들어가 있지 않은지 — 두 블록의 상대 위치로 확인한다.
    int completedOnlyAt = body.indexOf(COMPLETED_ONLY_BLOCK);
    assertTrue(completedOnlyAt >= 0, "COMPLETED 전용 블록이 사라졌다(4개 필드가 어디로 갔는지 확인 필요)");
    assertTrue(completedOnlyAt < pausedBlockAt,
        "COMPLETED 전용 블록이 COMPLETED||PAUSED 블록보다 앞이어야 한다(구조가 바뀌었다)");

    System.out.println("[006 계약] getAnalysisStatus: COMPLETED전용블록=" + completedOnlyAt
        + " < COMPLETED||PAUSED블록=" + pausedBlockAt + " < historyId대입=" + assignAt);
  }

  @Test
  void COMPLETED_전용_필드_4개는_여전히_COMPLETED_전용이다() {
    String body = methodBody(currentSource(), "getAnalysisStatus");
    int completedOnlyAt = body.indexOf(COMPLETED_ONLY_BLOCK);
    int pausedBlockAt = body.indexOf(COMPLETED_OR_PAUSED_BLOCK);
    assertTrue(completedOnlyAt >= 0 && pausedBlockAt > completedOnlyAt, "계약 기준점 소실");

    // COMPLETED 전용 블록의 범위 = 전용 블록 시작 ~ COMPLETED||PAUSED 블록 시작
    String completedOnly = body.substring(completedOnlyAt, pausedBlockAt);
    for (String setter : java.util.List.of(
        "dto.setAvgTimePerFile(", "dto.setFinalSummary(", "dto.setReadmePath(", "dto.setReadmeContent(")) {
      assertTrue(completedOnly.contains(setter),
          setter + " 가 COMPLETED 전용 블록에 없다(PAUSED로 새어 나가면 안 되는 필드다)");
      assertEquals(1, countOccurrences(body, setter),
          setter + " 가 getAnalysisStatus 안에서 1곳이어야 한다");
    }
    assertFalse(completedOnly.contains(HISTORY_ID_ASSIGN),
        "historyId 대입이 COMPLETED 전용 블록에 남아 있다(PAUSED에서 안 실린다)");
    System.out.println("[006 계약] COMPLETED 전용 4필드는 전용 블록 유지, historyId만 분리됨");
  }

  @Test
  void RG2_finalizeAnalysis_본문은_착수_커밋과_동일하다() {
    // 해시는 주석까지 포함한 원문으로 계산한다 — §0.5의 "메서드 선언부~닫는 중괄호" 범위와 같게 해
    // 주석만 바뀐 변경도 잡는다(선언 위 애노테이션·Javadoc은 범위 밖).
    String body = methodBodyRaw(currentSourceRaw(), "finalizeAnalysis");
    String hash = sha256(body);

    System.out.println("[006 RG-2] finalizeAnalysis 본문 sha256=" + hash
        + " (행수=" + body.split("\n").length + ")");
    assertEquals(FINALIZE_ANALYSIS_BODY_SHA256, hash,
        "finalizeAnalysis() 본문이 바뀌었다 — RG-2(정상 완료 경로 불변) 위반."
            + " 본문 첫 줄: " + body.split("\n")[0].trim());
  }

  /** 두 분석 루프가 historyId를 세션 메타데이터에 넣는다 — 이게 없으면 폴링 응답에 실을 값 자체가 없다. */
  @Test
  void 두_분석_루프가_historyId를_세션_메타데이터에_넣는다() {
    String source = currentSource();
    String assign = "session.updateMetadata(\"historyId\", history.getId().toString())";

    String runAnalysis = methodBody(source, "runAnalysis");
    String runAnalysisResume = methodBody(source, "runAnalysisResume");

    assertTrue(runAnalysis.contains(assign),
        "runAnalysis()가 historyId를 메타데이터에 넣지 않는다");
    assertTrue(runAnalysisResume.contains(assign),
        "runAnalysisResume()가 historyId를 메타데이터에 넣지 않는다(재시작 후 재개에서 비어 있게 된다)");
    System.out.println("[006 계약] runAnalysis·runAnalysisResume 둘 다 historyId 메타데이터 대입 있음");
  }

  // ===================================================================
  // 고정본 대조군
  // ===================================================================

  @Test
  void 대조군_착수커밋_고정본은_historyId를_COMPLETED_전용으로만_싣는다() throws Exception {
    String fixture = fixtureSource();
    String body = methodBody(fixture, "getAnalysisStatus");

    // ① COMPLETED||PAUSED 블록이 아예 없다 → PAUSED에서 historyId가 실리지 않는다(= 결함).
    assertEquals(0, countOccurrences(body, COMPLETED_OR_PAUSED_BLOCK),
        "고정본에 COMPLETED||PAUSED 블록이 있어서는 안 된다(없는 것이 바로 결함이다)");

    // ② 기준점 확인 — 고정본에도 historyId 대입과 COMPLETED 전용 블록은 1곳씩 있고, 대입이 그 블록 안이다.
    int completedOnlyAt = body.indexOf(COMPLETED_ONLY_BLOCK);
    int assignAt = body.indexOf(HISTORY_ID_ASSIGN);
    assertTrue(completedOnlyAt >= 0,
        "고정본에서 COMPLETED 전용 블록을 찾지 못했다 — 추출기가 본문을 읽지 못한 것");
    assertTrue(assignAt > completedOnlyAt,
        "고정본의 historyId 대입이 COMPLETED 전용 블록 뒤에 있어야 한다(그 블록 안이라는 뜻)");
    assertEquals(1, countOccurrences(body, HISTORY_ID_ASSIGN), "고정본의 historyId 대입은 1곳");

    // ③ 두 루프에는 메타데이터 대입이 아예 없다.
    String assign = "session.updateMetadata(\"historyId\", history.getId().toString())";
    assertFalse(methodBody(fixture, "runAnalysis").contains(assign),
        "고정본 runAnalysis()에 historyId 메타데이터 대입이 있어서는 안 된다");
    assertFalse(methodBody(fixture, "runAnalysisResume").contains(assign),
        "고정본 runAnalysisResume()에 historyId 메타데이터 대입이 있어서는 안 된다");

    // ④ 전량실패 분기에는 사유 요약 대입이 없다(REQ-002 ④가 새로 넣은 것).
    assertEquals(0, countOccurrences(fixture, "session.summarizeFileFailureReasons()"),
        "고정본에 사유 요약 호출이 있어서는 안 된다");
    assertEquals(0, countOccurrences(fixture, "session.recordFileFailureReason("),
        "고정본에 사유 기록 호출이 있어서는 안 된다");

    System.out.println("[006 대조군 before-7cad9d1] COMPLETED||PAUSED 블록 0건 /"
        + " historyId 대입 1건(COMPLETED 전용 블록 안, offset=" + assignAt + ") /"
        + " 두 루프 메타데이터 대입 0건 / summarize·record 호출 0건");
  }

  /** 검사식 자체의 탐지력 — 고정본 본문을 현재 검사식에 넣으면 실제로 실패한다. */
  @Test
  void 대조군_고정본_본문을_현재_검사식에_넣으면_실패한다() throws Exception {
    String body = methodBody(fixtureSource(), "getAnalysisStatus");
    boolean pausedBlockPresent = body.contains(COMPLETED_OR_PAUSED_BLOCK);

    assertFalse(pausedBlockPresent,
        "고정본이 현재 계약을 이미 만족한다면 이 계약은 아무것도 보호하지 않는다(검사식 무력화 신호)");
    System.out.println("[006 대조군 검사식] 고정본에 대해 COMPLETED||PAUSED 블록 존재="
        + pausedBlockPresent + " → 현재 계약 위반");
  }

  /** 현재 소스의 `historyId`·사유 요약 호출 수를 세어 "사본 0건"을 범위와 함께 남긴다. */
  @Test
  void 사유_요약과_사유_기록_호출이_지정된_지점에만_있다() {
    String source = currentSource();

    assertEquals(2, countOccurrences(source, "session.summarizeFileFailureReasons()"),
        "사유 요약 호출은 두 전량실패 분기 2곳이어야 한다");
    assertEquals(3, countOccurrences(source, "session.recordFileFailureReason("),
        "사유 기록 호출은 두 루프의 FAILED 분기 2곳 + PARTIAL 처리 1곳 = 3곳이어야 한다");

    // 사유 기록은 크레딧 소진을 제외해야 한다 — 두 FAILED 분기의 가드 존재 확인.
    assertEquals(2, countOccurrences(source,
            "if (!\"INSUFFICIENT_CREDITS\".equals(fileState.getErrorType())) {"),
        "두 FAILED 분기에 크레딧 소진 제외 가드가 각각 있어야 한다");

    System.out.println("[006 계약] summarize 2곳 / record 3곳 / 크레딧 제외 가드 2곳"
        + " — 보증 범위: MainApiController 전체 텍스트에서의 호출 수(주석 제외)");
  }

  // ===================================================================
  // 도우미
  // ===================================================================

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

  private static String sha256(String text) {
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : digest) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) {
      return fail("sha256 계산 실패: " + e);
    }
  }

  /**
   * 메서드 선언부({@code 이름(})부터 중괄호 균형이 맞는 닫는 중괄호까지.
   * <b>RG-2 해시 계산에 쓰므로 주석을 지우지 않은 원문</b>을 쓴다(§0.5의 해시 대조 방법과 동일 범위).
   */
  private static String methodBodyRaw(String source, String methodName) {
    int declAt = -1;
    int from = 0;
    while (true) {
      int at = source.indexOf(methodName + "(", from);
      if (at < 0) break;
      int lineStart = source.lastIndexOf('\n', at) + 1;
      String before = source.substring(lineStart, at);
      if (before.contains("private ") || before.contains("public ") || before.contains("protected ")) {
        declAt = at;
        break;
      }
      from = at + methodName.length();
    }
    if (declAt < 0) return fail("MainApiController에서 " + methodName + " 선언을 찾지 못했다");

    int lineStart = source.lastIndexOf('\n', declAt) + 1;
    int open = source.indexOf('{', declAt);
    int depth = 0;
    for (int i = open; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '{') depth++;
      else if (c == '}') {
        depth--;
        if (depth == 0) {
          int lineEnd = source.indexOf('\n', i);
          return source.substring(lineStart, lineEnd < 0 ? source.length() : lineEnd);
        }
      }
    }
    return fail(methodName + "의 중괄호 균형이 맞지 않는다");
  }

  /** 구조·개수 검사용 — 주석을 공백으로 덮은 본문(주석에 적힌 설명을 코드로 오인하지 않게). */
  private static String methodBody(String source, String methodName) {
    return ResumeSourceGuardContractTest.blankComments(methodBodyRaw(source, methodName));
  }

  /** 구조·개수 검사용 현재 소스 — 주석을 공백으로 덮는다. */
  private static String currentSource() {
    return ResumeSourceGuardContractTest.blankComments(currentSourceRaw());
  }

  /** RG-2 해시 계산용 현재 소스 — <b>주석을 포함한 원문</b>(§0.5의 해시 대조 범위와 같게 한다). */
  private static String currentSourceRaw() {
    Path file = locate(CONTROLLER_SRC);
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return fail("감시 대상 소스를 읽지 못했다: " + file + " (" + e + ")");
    }
  }

  private static String fixtureSource() throws Exception {
    byte[] bytes;
    try (InputStream in = HistoryIdPausedExposureContractTest.class.getResourceAsStream(FIXTURE)) {
      if (in == null) {
        return fail("착수 커밋 고정본이 클래스패스에 없다: " + FIXTURE
            + " — 이 파일이 없으면 검사기의 탐지력을 증명할 수 없다.");
      }
      bytes = in.readAllBytes();
    }
    assertEquals(FIXTURE_BLOB_SHA1, gitBlobSha1(bytes),
        "고정본이 `git show 7cad9d1:" + CONTROLLER_SRC + "`과 바이트 단위로 같아야 한다.");
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
}
