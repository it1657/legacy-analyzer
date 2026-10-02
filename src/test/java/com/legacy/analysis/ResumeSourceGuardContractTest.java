package com.legacy.analysis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-002 (work-order 2026-10-resume-consistency-and-local-guard v1, REQ-004) —
 * <b>원본 판정이 "부작용보다 먼저"라는 순서가 소스에 남아 있는지</b>를 고정하는 정적 계약.
 *
 * <p>왜 정적 계약이 필요한가: 거부 동작은 실행 테스트로 확인되지만, 나중에 누가 판정 호출을
 * 상태 변경 <b>뒤로</b> 옮기면 "거부는 되는데 세션은 이미 더럽혀진" 상태가 된다. 그 순서는 실행
 * 테스트로 잡기 어려워(두 동작 모두 같은 응답을 낸다) 소스 순서를 직접 고정한다.
 *
 * <p>검사기의 탐지력은 <b>착수 커밋 고정본</b>(`MainApiController.java.before-7cad9d1.txt`)으로
 * 증명한다 — 같은 검사기를 고정본에 걸면 위반이 검출돼야 한다. 검출되지 않으면 검사기가 아무것도
 * 보지 못하는 것이므로 현재 파일의 "위반 0건"도 증거가 되지 않는다.
 */
class ResumeSourceGuardContractTest {

  private static final String CONTROLLER_SRC = "src/main/java/com/legacy/analysis/MainApiController.java";
  private static final String FIXTURE = "/resumeconsistency/MainApiController.java.before-7cad9d1.txt";
  /** `git rev-parse 7cad9d1:src/main/java/com/legacy/analysis/MainApiController.java` */
  private static final String FIXTURE_BLOB_SHA1 = "18a0b953e8842fac593a8ff0f34beaab032b9c4a";

  /** 재개 공통 로직에서 "상태 변경"으로 보는 호출들 — 판정은 이들 전부보다 앞이어야 한다. */
  private static final List<String> STATE_MUTATIONS =
      List.of("session.setResumedAt(", "session.setStatus(", "session.setCurrentPhase(",
          "session.setPendingFilePaths(");

  // ===================================================================
  // 현재 소스 — 위반 0건
  // ===================================================================

  @Test
  void 재개_공통로직은_어떤_상태변경보다_먼저_원본을_판정한다() {
    String body = methodBody(currentSource(), "resumePendingFilesInThread");

    int checkAt = body.indexOf("SessionSourceAvailability.check(");
    assertTrue(checkAt >= 0,
        "resumePendingFilesInThread가 SessionSourceAvailability.check(를 불러야 한다 — 판정 사본을 쓰면 안 된다");

    for (String mutation : STATE_MUTATIONS) {
      int at = body.indexOf(mutation);
      if (at < 0) continue;   // 그 호출이 없으면 순서 검사 대상이 아니다
      assertTrue(checkAt < at,
          "원본 판정(SessionSourceAvailability.check)이 " + mutation + " 보다 앞에 있어야 한다"
              + " — 뒤로 가면 거부되더라도 세션이 이미 변경된다. check=" + checkAt + ", " + mutation + "=" + at);
    }
    System.out.println("[현재] resumePendingFilesInThread: check 위치=" + checkAt
        + ", 첫 상태변경 위치=" + firstMutationOffset(body));
  }

  @Test
  void 재개_공통로직에_filter_Files_exists_판정_사본이_남아_있지_않다() {
    String body = methodBody(currentSource(), "resumePendingFilesInThread");

    assertEquals(0, countOccurrences(body, "Files::exists"),
        "판정은 SessionSourceAvailability 한 곳에서만 한다 — filter(Files::exists) 사본이 남으면"
            + " 같은 질문의 답이 두 곳에서 갈라진다. 본문:\n" + body);
    assertEquals(0, countOccurrences(body, "Files.exists("), "같은 이유로 직접 Files.exists( 호출도 두지 않는다");
  }

  @Test
  void failover_컨펌은_모델전환보다_먼저_원본을_판정한다() {
    String body = methodBody(currentSource(), "confirmFailover");

    int checkAt = body.indexOf("SessionSourceAvailability.check(");
    int setModelAt = body.indexOf("claudeService.setModel(");
    int confirmedAtAt = body.indexOf("setFailoverConfirmedAt(");

    assertTrue(checkAt >= 0, "confirmFailover가 SessionSourceAvailability.check(를 불러야 한다");
    assertTrue(setModelAt >= 0, "confirmFailover에서 claudeService.setModel( 호출을 찾지 못했다(계약 기준점 소실)");
    assertTrue(checkAt < setModelAt,
        "원본 판정이 claudeService.setModel( 보다 앞이어야 한다 — 뒤로 가면 거부돼도 모델 전환이 남는다."
            + " check=" + checkAt + ", setModel=" + setModelAt);
    assertTrue(confirmedAtAt < 0 || checkAt < confirmedAtAt,
        "원본 판정이 setFailoverConfirmedAt( 보다 앞이어야 한다. check=" + checkAt
            + ", setFailoverConfirmedAt=" + confirmedAtAt);
    System.out.println("[현재] confirmFailover: check=" + checkAt + ", setModel=" + setModelAt
        + ", setFailoverConfirmedAt=" + confirmedAtAt);
  }

  @Test
  void 재개_루프_시작부에_빈_목록_2차_안전장치가_있고_그_분기에서_저장하지_않는다() {
    String body = methodBody(currentSource(), "runAnalysisResume");

    int guardAt = body.indexOf("fileList == null || fileList.isEmpty()");
    assertTrue(guardAt >= 0,
        "runAnalysisResume 시작부에 빈 목록 2차 안전장치(fileList == null || fileList.isEmpty())가 있어야 한다");

    int tryAt = body.indexOf("\n    try {");
    assertTrue(tryAt >= 0, "runAnalysisResume의 try 블록 시작을 찾지 못했다(계약 기준점 소실)");
    assertTrue(guardAt < tryAt,
        "2차 안전장치는 try 블록 진입 전에 있어야 한다 — 이력 저장·RAG 색인·카운터 초기화를 모두 건너뛰어야 한다."
            + " guard=" + guardAt + ", try=" + tryAt);

    // 안전장치 분기 본문에 saveSessionState가 없어야 한다(C3 — 저장하면 DB 대기 목록이 소실된다).
    String guardBlock = body.substring(guardAt, tryAt > guardAt ? tryAt : body.length());
    assertEquals(0, countOccurrences(guardBlock, "saveSessionState("),
        "2차 안전장치 분기에서 saveSessionState를 부르면 메모리에서 이미 비워진 대기 목록이 DB를 덮어써"
            + " 안전장치가 스스로 기록을 손상시킨다. 분기 본문:\n" + guardBlock);
    System.out.println("[현재] runAnalysisResume: guard=" + guardAt + ", try=" + tryAt
        + ", guard 분기 내 saveSessionState=0");
  }

  // ===================================================================
  // 고정본 대조군 — 같은 검사기가 착수 커밋에서는 위반을 검출한다
  // ===================================================================

  @Test
  void 대조군_착수커밋_고정본에는_같은_검사기가_위반을_검출한다() throws Exception {
    String fixture = fixtureSource();

    String resumeBody = methodBody(fixture, "resumePendingFilesInThread");
    String confirmBody = methodBody(fixture, "confirmFailover");
    String resumeLoopBody = methodBody(fixture, "runAnalysisResume");

    // ① 판정 도우미 호출이 아예 없다 → "판정이 먼저"라는 계약이 성립하지 않는다.
    int fixtureCheck = resumeBody.indexOf("SessionSourceAvailability.check(");
    assertEquals(-1, fixtureCheck,
        "고정본에는 SessionSourceAvailability.check( 호출이 없어야 한다(없다는 것이 바로 결함)");
    assertEquals(-1, confirmBody.indexOf("SessionSourceAvailability.check("),
        "고정본 confirmFailover에도 판정 호출이 없어야 한다");

    // ② 판정 사본(filter(Files::exists))이 재개 공통 로직에 있다 → 현재 검사가 잡는 바로 그 위반.
    int fixtureFilesExists = countOccurrences(resumeBody, "Files::exists");
    assertEquals(1, fixtureFilesExists,
        "고정본 resumePendingFilesInThread에는 filter(Files::exists) 판정 사본이 1곳 있어야 한다"
            + "(이것이 없는 파일을 조용히 버려 기록을 손상시킨 지점이다)");

    // ③ 재개 루프 시작부에 빈 목록 2차 안전장치가 없다.
    assertEquals(-1, resumeLoopBody.indexOf("fileList == null || fileList.isEmpty()"),
        "고정본 runAnalysisResume에는 빈 목록 안전장치가 없어야 한다");

    System.out.println("[대조군 before-7cad9d1] resume.check=" + fixtureCheck
        + ", resume.Files::exists=" + fixtureFilesExists
        + ", confirm.check=" + confirmBody.indexOf("SessionSourceAvailability.check(")
        + ", resumeLoop.emptyGuard="
        + resumeLoopBody.indexOf("fileList == null || fileList.isEmpty()"));
  }

  /**
   * 검사기 자체의 탐지력 — 고정본에서 추출한 {@code resumePendingFilesInThread} 본문을 현재 검사
   * 로직에 그대로 통과시키면 <b>실제로 실패한다</b>. 추출기와 검사식이 모두 살아 있다는 증명이다.
   */
  @Test
  void 대조군_고정본_본문을_현재_검사식에_넣으면_실패한다() throws Exception {
    String fixtureBody = methodBody(fixtureSource(), "resumePendingFilesInThread");

    boolean checkPresent = fixtureBody.contains("SessionSourceAvailability.check(");
    boolean copyAbsent = countOccurrences(fixtureBody, "Files::exists") == 0;

    assertTrue(!checkPresent || !copyAbsent,
        "고정본이 현재 계약을 이미 만족한다면 이 계약은 아무것도 보호하지 않는다(검사식 무력화 신호)");
    System.out.println("[대조군 검사식] 고정본에 대해 checkPresent=" + checkPresent
        + ", filter(Files::exists) 사본 없음=" + copyAbsent + " → 현재 계약 2개 모두 위반");
  }

  /**
   * 주석 제거기 자체의 대조군 — 주석만 지우고 코드는 남기는지 확인한다. 이 검사가 없으면
   * "위반 0건"이 실제로 코드가 깨끗해서인지, 제거기가 코드까지 날려서인지 구분할 수 없다.
   */
  @Test
  void 대조군_주석제거기는_주석만_지우고_코드는_남긴다() {
    String sample = ""
        + "int a = 1;                 // filter(Files::exists) 라고 주석에 적음\n"
        + "/* filter(Files::exists) 블록 주석 */\n"
        + "String s = \"// 문자열 안의 슬래시두개는 주석이 아니다\";\n"
        + "list.stream().filter(Files::exists).count();\n"
        + "char slash = '/';\n";

    String blanked = blankComments(sample);

    assertEquals(sample.length(), blanked.length(), "길이(=문자 오프셋)가 보존돼야 한다");
    assertEquals(1, countOccurrences(blanked, "filter(Files::exists)"),
        "주석 2곳은 지워지고 실제 코드 1곳만 남아야 한다. 결과:\n" + blanked);
    assertTrue(blanked.contains("int a = 1;"), "코드는 남아야 한다");
    assertTrue(blanked.contains("list.stream().filter(Files::exists).count();"), "코드는 남아야 한다");
    assertTrue(blanked.contains("// 문자열 안의 슬래시두개는 주석이 아니다"),
        "문자열 리터럴 안의 //는 주석이 아니므로 남아야 한다. 결과:\n" + blanked);
    assertEquals(sample.chars().filter(c -> c == '\n').count(),
        blanked.chars().filter(c -> c == '\n').count(), "줄 수가 보존돼야 한다");
    System.out.println("[대조군 주석제거기]\n" + blanked);
  }

  // ===================================================================
  // 도우미
  // ===================================================================

  private static int firstMutationOffset(String body) {
    int min = Integer.MAX_VALUE;
    for (String mutation : STATE_MUTATIONS) {
      int at = body.indexOf(mutation);
      if (at >= 0) min = Math.min(min, at);
    }
    return min == Integer.MAX_VALUE ? -1 : min;
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

  /**
   * 주석(줄 주석·블록 주석)의 내용을 <b>같은 길이의 공백으로 덮는다</b> — 문자 위치와 줄 구조가
   * 보존되므로 순서 비교(indexOf 오프셋)에 그대로 쓸 수 있다.
   *
   * <p>왜 필요한가: 이 계약은 <b>코드</b>의 순서·존재를 검사한다. 주석에 설명으로 적힌 토큰
   * (예: "종전에는 filter(Files::exists)로 …"라는 변경 이유 설명)이 위반으로 잡히면, 설명을 남길
   * 수 없게 되거나 반대로 주석만 고쳐 계약을 피해갈 수 있다. 문자열 리터럴 안의 {@code //}도
   * 주석으로 오인하지 않도록 리터럴 상태를 함께 따라간다.
   */
  static String blankComments(String source) {
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
        if (i < out.length) { out[i] = ' '; out[i + 1] = ' '; i += 2; }
        continue;
      }
      i++;
    }
    return new String(out);
  }

  /**
   * 메서드 선언부({@code 이름(})부터 중괄호 균형이 맞는 닫는 중괄호까지를 돌려준다.
   * 선언 위 애노테이션·Javadoc은 범위 밖이다(선례 {@code PauseSettledFrontGatingContractTest}의
   * {@code extractFunctionBody}와 같은 방식). 주석은 공백으로 덮여 있다.
   */
  private static String methodBody(String source, String methodName) {
    int declAt = -1;
    int from = 0;
    while (true) {
      int at = source.indexOf(methodName + "(", from);
      if (at < 0) break;
      // 호출부가 아니라 선언부인지: 같은 줄 앞쪽에 반환형/수식자가 있고 뒤에 `) {`로 블록이 열린다.
      int lineStart = source.lastIndexOf('\n', at) + 1;
      String before = source.substring(lineStart, at);
      if (before.contains("private ") || before.contains("public ") || before.contains("protected ")) {
        declAt = at;
        break;
      }
      from = at + methodName.length();
    }
    if (declAt < 0) return fail("MainApiController에서 " + methodName + " 선언을 찾지 못했다");

    int open = source.indexOf('{', declAt);
    if (open < 0) return fail(methodName + " 선언 뒤에 여는 중괄호가 없다");
    int depth = 0;
    for (int i = open; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '{') depth++;
      else if (c == '}') {
        depth--;
        if (depth == 0) return source.substring(open, i + 1);
      }
    }
    return fail(methodName + "의 중괄호 균형이 맞지 않는다");
  }

  /** 현재 {@code MainApiController} 원문(주석은 공백으로 덮음 — 코드만 검사한다). */
  private static String currentSource() {
    Path file = locate(CONTROLLER_SRC);
    try {
      return blankComments(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      return fail("감시 대상 소스를 읽지 못했다: " + file + " (" + e + ")");
    }
  }

  /** 고정본을 클래스패스에서 읽고 git blob 해시로 바이트 동일성을 먼저 확인한다. */
  private static String fixtureSource() throws Exception {
    byte[] bytes;
    try (InputStream in = ResumeSourceGuardContractTest.class.getResourceAsStream(FIXTURE)) {
      if (in == null) {
        return fail("착수 커밋 고정본이 클래스패스에 없다: " + FIXTURE
            + " — 이 파일이 없으면 검사기의 탐지력을 증명할 수 없다.");
      }
      bytes = in.readAllBytes();
    }
    assertEquals(FIXTURE_BLOB_SHA1, gitBlobSha1(bytes),
        "고정본이 `git show 7cad9d1:" + CONTROLLER_SRC + "`과 바이트 단위로 같아야 한다."
            + " 어긋났다면 고정본이 편집된 것이므로 대조군으로 쓸 수 없다.");
    return blankComments(new String(bytes, StandardCharsets.UTF_8));
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
