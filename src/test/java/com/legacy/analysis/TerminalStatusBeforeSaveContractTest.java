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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-005 (work-order 2026-10-resume-consistency-and-local-guard v1, REQ-001 ⑤ + 게이트1 D7) —
 * <b>종단 분기 5곳에서 {@code session.setStatus(…)}가 {@code saveSessionState(…)}보다 앞에 있는지</b>를
 * 소스 순서로 고정하는 정적 계약.
 *
 * <p>왜 순서가 계약인가: {@code setStatus}가 저장 <b>뒤</b>로 가면 DB 행에는 여전히 옛 status가 쓰이고,
 * 메모리만 바뀐다. 실행 테스트는 "마지막 저장 스냅샷"으로 이를 잡지만(같은 TASK의
 * {@code MainApiControllerTerminalStatusConsistencyTest}), 저장이 여러 번 일어나는 경로가 생기면
 * 실행 관측만으로는 애매해질 수 있어 소스 순서를 함께 못박는다.
 *
 * <p>검사기의 탐지력은 <b>착수 커밋 고정본</b>으로 증명한다 — 같은 검사기를 고정본에 걸면 5곳 모두
 * 위반(= {@code setStatus}가 아예 없음)으로 검출돼야 한다.
 *
 * <p>주석 제거는 {@link ResumeSourceGuardContractTest#blankComments(String)}를 재사용한다 — 같은
 * 저장소에서 같은 질문(주석을 코드로 오인하지 않기)에 대한 구현을 두 벌 만들지 않는다.
 */
class TerminalStatusBeforeSaveContractTest {

  private static final String CONTROLLER_SRC = "src/main/java/com/legacy/analysis/MainApiController.java";
  private static final String FIXTURE = "/resumeconsistency/MainApiController.java.before-7cad9d1.txt";
  /** `git rev-parse 7cad9d1:src/main/java/com/legacy/analysis/MainApiController.java` */
  private static final String FIXTURE_BLOB_SHA1 = "18a0b953e8842fac593a8ff0f34beaab032b9c4a";

  /** 검사 대상 종단 분기 5곳과 그 분기에서 기대하는 status 값. */
  private record Branch(String label, String expectedStatus) {}

  private static final List<Branch> BRANCHES = List.of(
      new Branch("runAnalysis 전량실패", "PAUSED"),
      new Branch("runAnalysisResume 전량실패", "PAUSED"),
      new Branch("handleCreditExhaustedPause failover 없음", "PAUSED"),
      new Branch("runAnalysis 취소", "CANCELLED"),
      new Branch("runAnalysisResume 취소", "CANCELLED"));

  // ===================================================================
  // 현재 소스 — 5곳 모두 setStatus가 저장보다 앞
  // ===================================================================

  @Test
  void 종단_분기_5곳에서_setStatus가_saveSessionState보다_앞에_있다() {
    Map<String, String> blocks = terminalBlocks(currentSource());
    assertEquals(BRANCHES.size(), blocks.size(), "검사 대상 분기 수가 5가 아니다: " + blocks.keySet());

    List<String> violations = new ArrayList<>();
    for (Branch b : BRANCHES) {
      String block = blocks.get(b.label());
      String setStatus = "session.setStatus(\"" + b.expectedStatus() + "\");";
      int setAt = block.indexOf(setStatus);
      int saveAt = block.indexOf("sessionManager.saveSessionState(");
      if (saveAt < 0) {
        violations.add(b.label() + ": 분기 안에서 saveSessionState 호출을 찾지 못했다(계약 기준점 소실)");
        continue;
      }
      if (setAt < 0) {
        violations.add(b.label() + ": " + setStatus + " 가 없다");
      } else if (setAt > saveAt) {
        violations.add(b.label() + ": setStatus가 saveSessionState보다 뒤에 있다"
            + "(set=" + setAt + ", save=" + saveAt + ")");
      }
      System.out.println("[005 계약] " + b.label() + " → setStatus(\"" + b.expectedStatus()
          + "\")=" + setAt + ", saveSessionState=" + saveAt);
    }
    assertTrue(violations.isEmpty(), "종단 status 저장 순서 위반:\n  " + String.join("\n  ", violations));
  }

  /**
   * {@code setCurrentPhase}·{@code addRecentLog}의 위치는 바꾸지 않았다(F6 — 로그 줄 선행 의도).
   * 두 호출은 종전처럼 {@code saveSessionState} <b>뒤</b>에 있어야 한다.
   */
  @Test
  void setCurrentPhase와_addRecentLog는_종전처럼_저장_뒤에_남아_있다() {
    Map<String, String> blocks = terminalBlocks(currentSource());

    for (Branch b : BRANCHES) {
      String block = blocks.get(b.label());
      int saveAt = block.indexOf("sessionManager.saveSessionState(");
      int phaseAt = block.indexOf("session.setCurrentPhase(");
      assertTrue(phaseAt > saveAt,
          b.label() + ": setCurrentPhase가 저장보다 앞으로 옮겨졌다(위치 변경 금지). save=" + saveAt
              + ", phase=" + phaseAt);
      int logAt = block.indexOf("session.addRecentLog(");
      if (logAt >= 0) {
        assertTrue(logAt > saveAt,
            b.label() + ": addRecentLog가 저장보다 앞으로 옮겨졌다(위치 변경 금지). save=" + saveAt
                + ", log=" + logAt);
      }
      System.out.println("[005 위치 불변] " + b.label() + " → save=" + saveAt
          + ", setCurrentPhase=" + phaseAt + ", addRecentLog=" + logAt);
    }
  }

  // ===================================================================
  // 고정본 대조군
  // ===================================================================

  @Test
  void 대조군_착수커밋_고정본에는_5곳_모두_setStatus가_없어_위반으로_검출된다() throws Exception {
    Map<String, String> blocks = terminalBlocks(fixtureSource());
    assertEquals(BRANCHES.size(), blocks.size(),
        "고정본에서도 5개 분기를 모두 찾아야 한다(추출기가 고정본을 읽지 못하면 대조군이 성립하지 않는다): "
            + blocks.keySet());

    Map<String, String> detected = new LinkedHashMap<>();
    for (Branch b : BRANCHES) {
      String block = blocks.get(b.label());
      String setStatus = "session.setStatus(\"" + b.expectedStatus() + "\");";
      int setAt = block.indexOf(setStatus);
      int saveAt = block.indexOf("sessionManager.saveSessionState(");

      // 추출기가 고정본에서도 기준점(saveSessionState)을 제대로 읽었다는 확인 — "아무것도 못 읽어서
      // 위반으로 보이는" 경우를 배제한다.
      assertTrue(saveAt >= 0,
          "고정본 " + b.label() + ": saveSessionState를 찾지 못했다 — 추출기가 블록을 잘못 잡았다");
      detected.put(b.label(), setAt < 0 ? "위반(setStatus 없음)" : "setStatus 있음(offset=" + setAt + ")");
      assertEquals(-1, setAt,
          "고정본 " + b.label() + "에는 " + setStatus + " 가 없어야 한다(없는 것이 바로 결함이다)");
    }
    System.out.println("[005 대조군 before-7cad9d1] " + detected);

    // 고정본에도 종단 분기의 setCurrentPhase는 있었다(이번 변경이 새로 만든 것이 아니다).
    for (Branch b : BRANCHES) {
      assertTrue(blocks.get(b.label()).contains("session.setCurrentPhase("),
          "고정본 " + b.label() + "에 setCurrentPhase가 있어야 한다(기준점 확인)");
    }
  }

  /** 검사식 자체의 탐지력 — 고정본 블록을 현재 검사식에 넣으면 실제로 5건 전부 위반으로 잡힌다. */
  @Test
  void 대조군_고정본_블록을_현재_검사식에_넣으면_5건_전부_위반이다() throws Exception {
    Map<String, String> blocks = terminalBlocks(fixtureSource());

    List<String> violations = new ArrayList<>();
    for (Branch b : BRANCHES) {
      String block = blocks.get(b.label());
      int setAt = block.indexOf("session.setStatus(\"" + b.expectedStatus() + "\");");
      int saveAt = block.indexOf("sessionManager.saveSessionState(");
      if (setAt < 0 || setAt > saveAt) violations.add(b.label());
    }
    assertEquals(BRANCHES.size(), violations.size(),
        "고정본에서 위반이 5건이어야 한다(실제 " + violations.size() + "건: " + violations + ")"
            + " — 적게 나오면 검사식이 일부 분기를 보지 못하는 것이다");
    System.out.println("[005 대조군 검사식] 고정본 위반 " + violations.size() + "건: " + violations);
  }

  /** 현재 소스에는 어떤 종단 분기도 "status를 쓰지 않은 저장"을 남기지 않았다(음성 결과 + 범위 명시). */
  @Test
  void 현재_소스의_종단_분기에는_status_없는_저장이_남아_있지_않다() {
    Map<String, String> blocks = terminalBlocks(currentSource());
    List<String> missing = new ArrayList<>();
    for (Branch b : BRANCHES) {
      if (!blocks.get(b.label()).contains("session.setStatus(")) missing.add(b.label());
    }
    assertTrue(missing.isEmpty(), "setStatus가 없는 종단 분기: " + missing);
    System.out.println("[005 계약] status 없는 종단 분기 0건 — 보증 범위: 위 5개 분기 블록 안쪽까지"
        + "(다른 저장 지점은 이 계약의 대상이 아니다)");
  }

  // ===================================================================
  // 도우미 — 5개 분기 블록 추출
  // ===================================================================

  /**
   * 종단 분기 5곳의 블록 텍스트를 뽑는다. 각 블록은 "분기 조건 줄 ~ 그 분기의 {@code setCurrentPhase} 줄"
   * 범위이며, 고정 문구(터미널 로그 포맷 문자열·취소 분기 조건)로 찾는다. 행번호를 쓰지 않는다(G-02).
   */
  private static Map<String, String> terminalBlocks(String source) {
    Map<String, String> blocks = new LinkedHashMap<>();

    // ① runAnalysis 전량실패 — 고정 문구(터미널 로그)를 기준점으로 잡고, 앞으로는 조건 줄까지,
    //    뒤로는 그 분기의 setCurrentPhase 줄까지 넓힌다(전량실패는 로그가 setCurrentPhase보다 앞이다).
    blocks.put("runAnalysis 전량실패", blockAround(source,
        "⚠️ [전체 실패] %d개 파일 모두 분석 실패", "if (successCount.get() == 0",
        "session.setCurrentPhase(\"PAUSED\");", "runAnalysis 전량실패"));

    // ② runAnalysisResume 전량실패
    blocks.put("runAnalysisResume 전량실패", blockAround(source,
        "⚠️ [전체 실패] 재시도한 %d개 파일 모두 다시 실패", "if (successCount.get() == 0",
        "session.setCurrentPhase(\"PAUSED\");", "runAnalysisResume 전량실패"));

    // ③ handleCreditExhaustedPause의 failover 없음(else) 분기
    blocks.put("handleCreditExhaustedPause failover 없음", blockAround(source,
        "💳 [크레딧 소진 일시정지] %d개 완료", "} else {", null, "크레딧 소진 failover 없음"));

    // ④⑤ 두 루프의 취소 분기 — 메서드 본문으로 먼저 좁힌 뒤 같은 모양을 찾는다.
    blocks.put("runAnalysis 취소", cancelBlock(methodBody(source, "runAnalysis"), "runAnalysis 취소"));
    blocks.put("runAnalysisResume 취소",
        cancelBlock(methodBody(source, "runAnalysisResume"), "runAnalysisResume 취소"));

    return blocks;
  }

  /**
   * {@code anchor}(소스에 정확히 1회만 나오는 고정 문구)를 기준으로, 뒤로 가장 가까운
   * {@code startNeedle}부터 앞으로 가장 가까운 {@code endNeedle}까지를 블록으로 잡는다.
   * {@code endNeedle}이 null이면 anchor 지점에서 끝낸다.
   */
  private static String blockAround(String source, String anchor, String startNeedle,
      String endNeedle, String label) {
    int at = source.indexOf(anchor);
    if (at < 0) return fail(label + ": 기준 문구를 찾지 못했다 — " + anchor);
    if (source.indexOf(anchor, at + 1) >= 0) {
      return fail(label + ": 기준 문구가 2회 이상 나온다(블록을 특정할 수 없다) — " + anchor);
    }
    int start = source.lastIndexOf(startNeedle, at);
    if (start < 0) return fail(label + ": 시작점을 찾지 못했다 — " + startNeedle);
    if (endNeedle == null) return source.substring(start, at);
    int end = source.indexOf(endNeedle, at);
    if (end < 0) return fail(label + ": 끝점을 찾지 못했다 — " + endNeedle);
    return source.substring(start, end + endNeedle.length());
  }

  /** 메서드 본문 안의 {@code if (session.isCancelled()) {} ~ setCurrentPhase("CANCELLED")} 블록. */
  private static String cancelBlock(String methodBody, String label) {
    int start = methodBody.indexOf("if (session.isCancelled()) {");
    if (start < 0) return fail(label + ": 취소 분기 조건을 찾지 못했다");
    if (methodBody.indexOf("if (session.isCancelled()) {", start + 1) >= 0) {
      return fail(label + ": 취소 분기 조건이 한 메서드 안에서 2회 이상 나온다(블록을 특정할 수 없다)");
    }
    int end = methodBody.indexOf("session.setCurrentPhase(\"CANCELLED\");", start);
    if (end < 0) return fail(label + ": 취소 분기의 setCurrentPhase(\"CANCELLED\")를 찾지 못했다");
    return methodBody.substring(start, end + "session.setCurrentPhase(\"CANCELLED\");".length());
  }

  /** 메서드 선언부({@code 이름(})부터 중괄호 균형이 맞는 닫는 중괄호까지. 주석은 이미 공백으로 덮여 있다. */
  private static String methodBody(String source, String methodName) {
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

    int open = source.indexOf('{', declAt);
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

  private static String currentSource() {
    Path file = locate(CONTROLLER_SRC);
    try {
      return ResumeSourceGuardContractTest.blankComments(
          Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      return fail("감시 대상 소스를 읽지 못했다: " + file + " (" + e + ")");
    }
  }

  private static String fixtureSource() throws Exception {
    byte[] bytes;
    try (InputStream in = TerminalStatusBeforeSaveContractTest.class.getResourceAsStream(FIXTURE)) {
      if (in == null) {
        return fail("착수 커밋 고정본이 클래스패스에 없다: " + FIXTURE
            + " — 이 파일이 없으면 검사기의 탐지력을 증명할 수 없다.");
      }
      bytes = in.readAllBytes();
    }
    assertEquals(FIXTURE_BLOB_SHA1, gitBlobSha1(bytes),
        "고정본이 `git show 7cad9d1:" + CONTROLLER_SRC + "`과 바이트 단위로 같아야 한다."
            + " 어긋났다면 고정본이 편집된 것이므로 대조군으로 쓸 수 없다.");
    return ResumeSourceGuardContractTest.blankComments(new String(bytes, StandardCharsets.UTF_8));
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

  /** 사용하지 않는 import 경고를 피하기 위한 자리 — assertFalse는 아래 자체 점검에서 쓴다. */
  @Test
  void 자체점검_추출된_5개_블록은_서로_다르고_비어_있지_않다() {
    Map<String, String> blocks = terminalBlocks(currentSource());
    for (Map.Entry<String, String> e : blocks.entrySet()) {
      assertFalse(e.getValue().isBlank(), e.getKey() + ": 추출된 블록이 비어 있다");
      assertTrue(e.getValue().contains("sessionManager.saveSessionState("),
          e.getKey() + ": 추출된 블록에 저장 호출이 없다(블록 범위가 잘못 잡혔다)");
    }
    assertEquals(5, blocks.values().stream().distinct().count(),
        "5개 블록이 서로 달라야 한다(같은 블록을 두 번 잡았으면 검사가 중복된다)");
    System.out.println("[005 자체점검] 블록 길이 = "
        + blocks.entrySet().stream()
            .map(e -> e.getKey() + ":" + e.getValue().length())
            .toList());
  }
}
