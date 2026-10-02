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
 * TASK-009 (work-order 2026-10-resume-consistency-and-local-guard v1, REQ-005 ⑧ / 게이트1 D5) —
 * <b>비상용 모델 목록(FALLBACK_MODEL_OPTIONS)으로 화면을 채우는 경로의 "단일 출처"</b>를 정적으로
 * 단언하는 계약 테스트. 프런트 소스({@code dashboard.js})를 <b>텍스트로 읽어</b> 검사하며, 브라우저
 * 실행은 모델링하지 않는다 — JS 분기·데이터 흐름은 {@code src/test/js/dashboardModelFallbackGuardHarness.js}
 * (node:vm, 케이스 F1~F10)가, 실화면은 실브라우저 관측(TASK-011 시나리오 E)이 맡는다.
 *
 * <h2>왜 이 계약이 필요한가</h2>
 * 착수 커밋에서는 모델 목록 조회가 실패하면 비상용 Claude 3종을 <b>선택 가능한 상태로</b> 채웠고,
 * 그 배포에서 동작하지 않을 수 있는 모델로 분석이 시작됐다(하네스 고정본 대조군 F10에서
 * {@code /api/start-analysis} 호출 1회로 재현). 이번 수정은 비상 목록을 채우는 6개 경로를
 * {@code populateFallbackModelOptions()} 한 곳으로 모으고, 그 함수가 차단 플래그
 * {@code modelOptionsFallbackActive}를 올리게 했다. 경로가 다시 늘어나면서 플래그를 빼먹으면
 * 같은 결함이 그대로 돌아오므로, "직접 호출은 헬퍼 본문 1곳뿐"을 계약으로 고정한다.
 *
 * <h2>무엇을 단언하는가</h2>
 * <ol>
 *   <li>{@code populateModelSelectOptions(FALLBACK_MODEL_OPTIONS)} 직접 호출이 파일 전체에서
 *       <b>1곳</b>이고, 그 1곳은 {@code populateFallbackModelOptions()} 본문 안이다.</li>
 *   <li>그 헬퍼를 부르는 지점이 <b>6곳</b>이고, 모두 헬퍼 본문 밖이다(자기 호출 없음).</li>
 *   <li>헬퍼 안에서 <b>채우기 → 플래그 올리기</b> 순서가 지켜진다. 역순이면
 *       {@code populateModelSelectOptions()}가 성공 경로에서 플래그를 내려 버려 차단이 사라진다.</li>
 *   <li>{@code isModelSelectUnavailable()}이 {@code modelOptionsFallbackActive}를 참조한다.</li>
 *   <li>{@code MainApiController}에 "거르지 않고 그대로 노출" 설명이 <b>0건</b>이다(동작이 바뀌었으므로
 *       그 문장이 남아 있으면 주석이 거짓이 된다).</li>
 * </ol>
 *
 * <h2>양성 대조군 (정적 단언의 탐지력)</h2>
 * 같은 검사기를 <b>착수 커밋 고정본</b>({@code dashboard.js.before-7cad9d1.txt},
 * {@code MainApiController.java.before-7cad9d1.txt})에 걸면 직접 호출 <b>6곳</b> / 헬퍼 <b>0곳</b> /
 * 옛 문장 <b>1건</b>이 검출돼야 한다. 검출되지 않으면 검사기가 아무것도 지키지 못한다는 뜻이다.
 * 고정본은 {@code git blob} 해시로 바이트 동일성을 먼저 확인한다.
 *
 * <p>주석 무시 검사기({@link ResumeSourceGuardContractTest#blankComments(String)})는 이 파일에서도
 * 재사용한다 — 현재 소스에는 직접 호출을 <b>설명하는 주석</b>이 2곳 있어서, 주석을 지우지 않으면
 * "직접 호출 3곳"으로 잘못 세게 된다. 지우기가 과하게 동작하지 않는지도 함께 단언한다.
 */
class ModelFallbackGuardSingleSourceContractTest {

  private static final String DASHBOARD_JS = "src/main/resources/static/js/dashboard.js";
  private static final String CONTROLLER_SRC = "src/main/java/com/legacy/analysis/MainApiController.java";

  private static final String JS_FIXTURE = "/resumeconsistency/dashboard.js.before-7cad9d1.txt";
  private static final String JS_FIXTURE_BLOB_SHA1 = "23ab549273eb618ad3bbcf55c5ce21c66c732019";
  private static final String CONTROLLER_FIXTURE = "/resumeconsistency/MainApiController.java.before-7cad9d1.txt";

  private static final String DIRECT_CALL = "populateModelSelectOptions(FALLBACK_MODEL_OPTIONS)";
  private static final String HELPER = "populateFallbackModelOptions";
  private static final String HELPER_DECL = "function populateFallbackModelOptions() {";
  private static final String FLAG = "modelOptionsFallbackActive";
  private static final String OLD_SENTENCE = "거르지 않고 그대로 노출";

  /** 기대 호출 지점 수 — work-order가 지정한 P5·P6·P7·P8(3갈래) 합계. */
  private static final int EXPECTED_HELPER_CALLS = 6;

  // ─────────────────────────────────────────── 1. 직접 호출은 헬퍼 본문 1곳뿐

  @Test
  void 비상목록_직접호출은_헬퍼_본문_안_1곳뿐이다() throws Exception {
    String js = blanked(currentJs());
    int count = countOccurrences(js, DIRECT_CALL);
    assertEquals(1, count,
        "비상 목록으로 select를 채우는 직접 호출은 populateFallbackModelOptions() 본문 1곳이어야 한다."
            + " 2곳 이상이면 그 경로는 차단 플래그를 올리지 않아 '동작하지 않는 모델로 분석 시작'이 되살아난다."
            + " 실제=" + count + "곳");

    int[] body = helperBodyRange(js);
    int at = js.indexOf(DIRECT_CALL);
    assertTrue(at >= body[0] && at < body[1],
        "그 1곳은 populateFallbackModelOptions() 본문(" + body[0] + ".." + body[1] + ") 안이어야 한다. 실제 위치=" + at);
  }

  // ─────────────────────────────────────────── 2. 헬퍼 호출 6곳

  @Test
  void 비상목록_경로는_모두_헬퍼를_통해_6곳에서_호출한다() throws Exception {
    String js = blanked(currentJs());
    int[] body = helperBodyRange(js);

    int calls = 0;
    int selfCalls = 0;
    for (int i = js.indexOf(HELPER + "()"); i >= 0; i = js.indexOf(HELPER + "()", i + 1)) {
      boolean isDeclaration = i >= "function ".length()
          && js.startsWith(HELPER_DECL, i - "function ".length());
      if (isDeclaration) continue;
      calls++;
      if (i >= body[0] && i < body[1]) selfCalls++;
    }
    assertEquals(EXPECTED_HELPER_CALLS, calls,
        "헬퍼 호출 지점이 " + EXPECTED_HELPER_CALLS + "곳이어야 한다(P5·P6·P7·P8 3갈래)."
            + " 줄어들었다면 어떤 실패 경로가 차단 없이 비상 목록을 쓰고 있을 수 있다. 실제=" + calls + "곳");
    assertEquals(0, selfCalls, "헬퍼가 자기 자신을 부르면 안 된다(무한 재귀).");
  }

  // ─────────────────────────────────────────── 3. 헬퍼 안의 순서

  @Test
  void 헬퍼는_채운_뒤에_플래그를_올린다_순서가_계약이다() throws Exception {
    String js = blanked(currentJs());
    int[] body = helperBodyRange(js);
    String helperBody = js.substring(body[0], body[1]);

    int fill = helperBody.indexOf(DIRECT_CALL);
    int raise = helperBody.indexOf(FLAG + " = true");
    assertTrue(fill >= 0, "헬퍼 본문에 비상 목록 채우기가 있어야 한다.");
    assertTrue(raise >= 0, "헬퍼 본문에서 차단 플래그를 올려야 한다.");
    assertTrue(fill < raise,
        "채우기(" + fill + ")가 플래그 올리기(" + raise + ")보다 앞서야 한다."
            + " populateModelSelectOptions()는 성공 경로에서 플래그를 내리므로, 순서가 뒤바뀌면 플래그가 즉시 지워져"
            + " 차단이 사라진다(이 순서 자체가 버그 재발 지점).");

    // 플래그를 내리는 쪽도 있어야 한다 — 정상 목록을 채운 경로에서 내려가야 provider 전환 시 회복된다.
    assertTrue(js.contains(FLAG + " = false"),
        "정상 목록을 채우는 경로에서 플래그를 내려야 한다(그래야 provider 탭 전환 시 회복된다).");
  }

  // ─────────────────────────────────────────── 4. 차단 판정이 플래그를 본다

  @Test
  void 차단_판정이_비상목록_플래그를_참조한다() throws Exception {
    String js = blanked(currentJs());
    String fn = functionBody(js, "function isModelSelectUnavailable()");
    assertTrue(fn.contains(FLAG),
        "isModelSelectUnavailable()이 " + FLAG + "를 참조해야 한다."
            + " 참조하지 않으면 비상 목록이 떠 있어도 분석 시작이 통과된다.");

    String msgFn = functionBody(js, "function getModelUnavailableAlertMessage()");
    assertTrue(msgFn.contains(FLAG),
        "안내 문구도 비상 목록 상황을 따로 구분해야 한다(provider 실패 문구와 섞이면 원인 추적이 어렵다).");
  }

  // ─────────────────────────────────────────── 5. 옛 설명 문장 제거

  @Test
  void 컨트롤러에_거르지_않고_노출한다는_옛_설명이_남아_있지_않다() throws Exception {
    String controller = Files.readString(locate(CONTROLLER_SRC), StandardCharsets.UTF_8);
    int count = countOccurrences(controller, OLD_SENTENCE);
    assertEquals(0, count,
        "확인 불가 시 '거르지 않고 그대로 노출'한다는 설명은 이번 변경으로 사실이 아니게 됐다."
            + " 남아 있으면 다음 담당자가 주석을 믿고 반대로 구현한다. 실제=" + count + "건");
  }

  // ─────────────────────────────────────────── 6. 고정본 대조군

  @Test
  void 대조군_착수커밋_고정본은_직접호출_6곳_헬퍼_0곳이다() throws Exception {
    String base = blanked(fixture(JS_FIXTURE, JS_FIXTURE_BLOB_SHA1));

    int direct = countOccurrences(base, DIRECT_CALL);
    int helper = countOccurrences(base, HELPER);
    int flag = countOccurrences(base, FLAG);

    System.out.println("[대조군 계수] 고정본 직접호출=" + direct + " / 헬퍼언급=" + helper + " / 플래그=" + flag);

    assertEquals(6, direct,
        "고정본에는 비상 목록 직접 호출이 6곳 흩어져 있어야 한다(그 분산이 바로 결함). 검출되지 않으면 검사기가 무력하다.");
    assertEquals(0, helper, "고정본에는 헬퍼가 없어야 한다.");
    assertEquals(0, flag, "고정본에는 차단 플래그가 없어야 한다 — 그래서 비상 목록으로 분석이 시작됐다.");

    String baseController = fixture(CONTROLLER_FIXTURE, null);
    assertEquals(1, countOccurrences(baseController, OLD_SENTENCE),
        "고정본 컨트롤러에는 '거르지 않고 그대로 노출' 설명이 1건 있어야 한다(현재는 0건이어야 함).");
  }

  // ─────────────────────────────────────────── 7. 주석 무시 검사기의 탐지력

  @Test
  void 대조군_주석_무시_검사기가_주석만_지우고_실제_호출은_남긴다() throws Exception {
    String raw = currentJs();
    String js = blanked(raw);

    int rawDirect = countOccurrences(raw, DIRECT_CALL);
    int blankedDirect = countOccurrences(js, DIRECT_CALL);
    System.out.println("[검사기 탐지력] 주석 포함 직접호출=" + rawDirect + " → 주석 제거 후=" + blankedDirect);

    assertTrue(rawDirect > blankedDirect,
        "현재 소스에는 직접 호출을 설명하는 주석이 있으므로, 주석을 지우면 계수가 줄어야 한다."
            + " 줄지 않으면 blankComments()가 동작하지 않는 것이고, 그러면 주석 한 줄로 이 계약을 통과시킬 수 있다."
            + " (주석포함=" + rawDirect + ", 제거후=" + blankedDirect + ")");

    // 반대 방향 — 지우기가 과해서 실제 코드를 삼키지 않았는지 확인한다(JS 템플릿 리터럴 오인 등).
    assertTrue(js.contains(HELPER_DECL), "헬퍼 선언은 주석 제거 후에도 남아 있어야 한다.");
    assertTrue(js.contains("function isModelSelectUnavailable()"),
        "차단 판정 함수 선언은 주석 제거 후에도 남아 있어야 한다.");
    assertEquals(EXPECTED_HELPER_CALLS + 1, countOccurrences(js, HELPER + "()"),
        "주석 제거 후 헬퍼 언급은 호출 " + EXPECTED_HELPER_CALLS + "곳 + 선언 1곳이어야 한다"
            + "(주석 속 헬퍼 언급은 지워져야 한다).");
    assertFalse(js.contains("비상 목록으로 채우는 경우는"),
        "주석 문장은 제거돼야 한다(제거되지 않으면 위 계수가 주석에 오염된다).");
  }

  // ─────────────────────────────────────────── 보조

  private static String currentJs() {
    try {
      return Files.readString(locate(DASHBOARD_JS), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return fail("감시 대상 소스를 읽지 못했다: " + DASHBOARD_JS + " (" + e + ")");
    }
  }

  /** 주석 무시 검사기는 ResumeSourceGuardContractTest의 것을 그대로 쓴다(같은 패키지, 단일 출처). */
  private static String blanked(String source) {
    return ResumeSourceGuardContractTest.blankComments(source);
  }

  /** 고정본을 클래스패스에서 읽는다. blobSha1이 주어지면 바이트 동일성을 먼저 확인한다. */
  private static String fixture(String resource, String blobSha1) throws Exception {
    byte[] bytes;
    try (InputStream in = ModelFallbackGuardSingleSourceContractTest.class.getResourceAsStream(resource)) {
      if (in == null) {
        return fail("착수 커밋 고정본이 클래스패스에 없다: " + resource
            + " — 이 파일이 없으면 검사기의 탐지력을 증명할 수 없다.");
      }
      bytes = in.readAllBytes();
    }
    if (blobSha1 != null) {
      assertEquals(blobSha1, gitBlobSha1(bytes),
          "고정본이 `git show 7cad9d1:" + DASHBOARD_JS + "`과 바이트 단위로 같아야 한다."
              + " 어긋났다면 고정본이 편집된 것이므로 대조군으로 쓸 수 없다.");
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static String gitBlobSha1(byte[] content) throws Exception {
    java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
    sha1.update(("blob " + content.length + "\u0000").getBytes(StandardCharsets.UTF_8));
    sha1.update(content);
    StringBuilder sb = new StringBuilder();
    for (byte b : sha1.digest()) sb.append(String.format("%02x", b));
    return sb.toString();
  }

  /** 헬퍼 본문의 [시작, 끝) 오프셋 — 선언 줄의 여는 중괄호부터 짝이 맞는 닫는 중괄호까지. */
  private static int[] helperBodyRange(String js) {
    int decl = js.indexOf(HELPER_DECL);
    if (decl < 0) fail("헬퍼 선언을 찾지 못했다: " + HELPER_DECL);
    int open = js.indexOf('{', decl);
    int close = matchBrace(js, open);
    return new int[] {open, close};
  }

  private static String functionBody(String js, String declaration) {
    int decl = js.indexOf(declaration);
    if (decl < 0) fail("함수 선언을 찾지 못했다: " + declaration);
    int open = js.indexOf('{', decl);
    return js.substring(open, matchBrace(js, open));
  }

  private static int matchBrace(String js, int open) {
    int depth = 0;
    for (int i = open; i < js.length(); i++) {
      char c = js.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}' && --depth == 0) {
        return i + 1;
      }
    }
    return fail("여는 중괄호의 짝을 찾지 못했다(offset " + open + ")");
  }

  private static int countOccurrences(String haystack, String needle) {
    int n = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
      n++;
    }
    return n;
  }

  private static Path locate(String relative) {
    Path cursor = Path.of("").toAbsolutePath();
    while (cursor != null) {
      Path candidate = cursor.resolve(relative);
      if (Files.isRegularFile(candidate)) return candidate;
      cursor = cursor.getParent();
    }
    return fail("소스를 찾지 못했다: " + relative);
  }
}
