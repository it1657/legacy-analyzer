package com.legacy.analysis;

import com.legacy.analysis.llm.AnthropicErrorFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-002 (work-order 2026-10-counter-integrity-and-failure-reason v1) —
 * 이 사이클이 <b>건드리지 않기로 한 것</b>을 착수 커밋 {@code 9b5906b} 고정본과 대조해 못박는 가드.
 *
 * <p>두 종류를 본다.
 * <ul>
 *   <li><b>ⓐ 메서드 본문</b> — 화이트리스트 5파일 중 "0행"으로 지정된 메서드 33개. 같은 파일 안에서
 *       허용된 편집(재기준 호출·이력 4줄·안내 1줄 등)과 구분하려면 보호 단위가 파일이 아니라
 *       메서드 본문이어야 한다.</li>
 *   <li><b>ⓑ 파일 전체</b> — 바이트 단위로 고정해 두는 13파일(범위 밖 프로덕션·프런트·설정 +
 *       K1·K2·K3 테스트 파일. K1·K2·K3은 2026-10-test-gaps-k1-k3에서 흔들림을 없애려고 의도적으로
 *       고쳤고 그때 기대값을 갱신했다 — 아래 주석).</li>
 * </ul>
 *
 * <p><b>검사기의 탐지력은 같은 테스트 안의 양성 대조군으로 증명한다</b>(G-04) — 고정본 본문에 1글자,
 * 파일 바이트열에 1바이트만 바꾼 입력을 같은 비교기에 넣어 "불일치"가 나오는 것을 단언한다.
 * 그래야 "같다"는 음성 결과가 "비교기가 아무것도 못 봐서 같아 보인 것"이 아님을 알 수 있다.
 *
 * <p>줄바꿈: 비교 전에 {@code \r\n}을 {@code \n}으로 맞춘다(§0.5). 인덱스 줄바꿈은 TASK-002가
 * {@code git ls-files --eol}로 실측했고, 13파일 중 {@code ClaudeServiceImpl.java}만 인덱스가 CRLF다.
 * 그래서 ⓑ의 기대값은 raw blob 해시가 아니라 <b>LF 정규화 후의 git blob SHA-1</b>로 고정한다
 * (출처 명령은 {@code 05-dev-progress.md}에 기록).
 *
 * <p>마지막 한 건은 TASK-002 DoD (d)용이다 — 픽스처 도우미가 <b>실제</b> 클라이언트가 던진 예외를
 * 돌려주는지 확인하고 401 예외 메시지 원문을 출력한다. 이 TASK에서 <b>분류 판정은 하지 않는다</b>
 * (분류는 TASK-005). 이 사이클의 002 신규 실행 클래스는 1개로 예정돼 있어 별도 테스트 클래스를
 * 만들지 않고 여기에 함께 둔다.
 */
class CounterIntegrityUnchangedMethodsGuardTest {

  // ===================================================================
  // ⓐ 메서드 본문 비교 대상
  // ===================================================================

  /** 감시 대상 파일 하나 = 현재 소스 경로 + 착수 커밋 고정본 리소스 + 고정본 raw blob 해시 + 0행 메서드 목록. */
  private record BodyTarget(String label, String sourcePath, String fixture, String fixtureBlobSha1,
                            List<String> methods) {
  }

  private static final List<BodyTarget> BODY_TARGETS = List.of(
      new BodyTarget("MainApiController",
          "src/main/java/com/legacy/analysis/MainApiController.java",
          "/counterintegrity/MainApiController.java.before-9b5906b.txt",
          "4b6133bb81223980cd66df3f0a7173ab9c77b9ba",
          List.of("finalizeAnalysis", "settlePauseAtTerminal", "handleCreditExhaustedPause",
              "pauseSession", "resumeSession", "cancelSession", "confirmFailover",
              "analyzeFile", "getAnalysisStatus")),
      new BodyTarget("SessionState",
          "src/main/java/com/legacy/analysis/SessionState.java",
          "/counterintegrity/SessionState.java.before-9b5906b.txt",
          "0359652a91e25e6d4b0fbe63f19b208df242e989",
          List.of("addErrorLog", "hasSettledPause", "shouldStop", "getPendingFilePaths",
              "setPendingFilePaths", "addFailedFilePath", "removeFailedFilePath",
              "getTotalFiles", "getProcessedFiles", "setProcessedFiles")),
      new BodyTarget("AnalysisStatistics",
          "src/main/java/com/legacy/analysis/AnalysisStatistics.java",
          "/counterintegrity/AnalysisStatistics.java.before-9b5906b.txt",
          "0fd174726597f37f8691817bccefd58b22b42dcb",
          List.of("getSuccessCount", "setSuccessCount", "incrementSuccessCount",
              "getFailureCount", "setFailureCount", "incrementFailureCount",
              "getSkipCount", "setSkipCount", "incrementSkipCount")),
      new BodyTarget("ApiErrorHandler",
          "src/main/java/com/legacy/core/ApiErrorHandler.java",
          "/counterintegrity/ApiErrorHandler.java.before-9b5906b.txt",
          "2c72515e2fa982afc6bc42ae815198e99c90fff7",
          List.of("isRetryable", "calculateRetryDelay",
              "getUserFriendlyMessage", "getDetailedErrorLog", "logError")));

  /** 지정된 0행 메서드 수(9 + 10 + 9 + 5). 목록이 조용히 줄어드는 것을 막는다. */
  private static final int EXPECTED_METHOD_COUNT = 33;

  // ===================================================================
  // ⓑ 파일 무변경 대상 — 기대값은 LF 정규화 후 git blob SHA-1
  // ===================================================================

  private static final Map<String, String> FILE_TARGETS = new LinkedHashMap<>();

  static {
    // 2026-10-retry-and-classification TASK-005에서 의도적으로 바꾼 파일이라 기대값을 갱신했다. 출처:
    // python으로 CRLF를 LF로 바꾼 사본을 만들고 git hash-object src/main/java/com/legacy/analysis/ClaudeServiceImpl.java
    FILE_TARGETS.put("src/main/java/com/legacy/analysis/ClaudeServiceImpl.java",
        "1a797cc11700882b60a33ce59ff000e9403d363d");
    FILE_TARGETS.put("src/main/java/com/legacy/analysis/llm/AnthropicLlmClient.java",
        "0488d8ff9f253352e0b59852641647fc6b5bf954");
    FILE_TARGETS.put("src/main/java/com/legacy/analysis/SessionSourceAvailability.java",
        "444a61f4512d2c2c227fa1a96dc6d920bb95422a");
    FILE_TARGETS.put("src/main/java/com/legacy/analysis/AnalysisSessionManager.java",
        "7dd42286dd903e81fbefb4e559c8f52c6d73748b");
    FILE_TARGETS.put("src/main/java/com/legacy/analysis/UserActivityController.java",
        "98a227dd41acd58d63c11e15ce930863fd6ad05c");
    // 2026-10 TASK-006(β-화면)에서 의도적으로 바꾼 파일이라 기대값을 갱신했다. 출처:
    // git hash-object src/main/resources/static/js/dashboard.js
    FILE_TARGETS.put("src/main/resources/static/js/dashboard.js",
        "f9c0a7e9f1b578d130d0e198a57516e1ba955d7b");
    FILE_TARGETS.put("src/main/resources/templates/my-activity.html",
        "6d9f956224203de3c175e60f4e0fe52cf65896d7");
    FILE_TARGETS.put("src/main/resources/templates/admin/dashboard.html",
        "1600c1e4cd9620a1451d7b3ae6e415844f04c40c");
    FILE_TARGETS.put("build.gradle",
        "ad80e7c45290034333efc878bfb3818300520d3f");
    FILE_TARGETS.put("src/main/resources/application.properties",
        "ec2466d843c966d6852f91ee390ee461a57e148d");
    // 2026-10-test-gaps-k1-k3 TASK-002·TASK-003에서 의도적으로 바꾼 K1·K2·K3 테스트 파일이라 기대값을 갱신했다. 출처:
    // git hash-object src/test/java/com/legacy/analysis/<각 K 파일> (인덱스·작업 트리 모두 LF이라 LF 사본 없이 계산)
    FILE_TARGETS.put("src/test/java/com/legacy/analysis/MainApiControllerFailoverConfirmTest.java",
        "405b66f49b3c5c7a1a3790b44f6cf6548edc767c");
    FILE_TARGETS.put("src/test/java/com/legacy/analysis/MainApiControllerSessionOwnershipTest.java",
        "93a04f3530b36a253f5b94cb8a179c33e8d57349");
    FILE_TARGETS.put(
        "src/test/java/com/legacy/analysis/ClaudeServiceTokenCounterMultiSessionConcurrencyTest.java",
        "34efc1cbce8bc62c3ac0a42abb947eee53314a3b");
  }

  // ===================================================================
  // ⓐ 본문 비교
  // ===================================================================

  @Test
  void 영행_지정_메서드의_본문이_착수커밋_고정본과_문자단위로_같다() throws Exception {
    List<String> violations = new ArrayList<>();
    int compared = 0;

    for (BodyTarget target : BODY_TARGETS) {
      String current = normalizeEol(readCurrentSource(target.sourcePath()));
      String fixture = normalizeEol(readFixture(target.fixture(), target.fixtureBlobSha1()));

      for (String method : target.methods()) {
        String currentBody = methodBody(current, target.label() + "(현재)", method);
        String fixtureBody = methodBody(fixture, target.label() + "(고정본)", method);
        compared++;

        // 추출이 한쪽에서만 다르게 동작하지 않았음을 보이는 출력(§0.5).
        System.out.println("[002 본문] " + target.label() + "." + method
            + " | 현재 첫줄=" + firstLine(currentBody) + " | 현재 끝줄=" + lastLine(currentBody)
            + " | 고정본 첫줄=" + firstLine(fixtureBody) + " | 고정본 끝줄=" + lastLine(fixtureBody)
            + " | 길이 현재/고정본=" + currentBody.length() + "/" + fixtureBody.length());

        if (!sameBody(currentBody, fixtureBody)) {
          violations.add(target.label() + "." + method + ": 본문이 착수 커밋과 다르다 ("
              + firstDifference(fixtureBody, currentBody) + ")");
        }
      }
    }

    assertEquals(EXPECTED_METHOD_COUNT, compared,
        "0행 지정 메서드 수가 " + EXPECTED_METHOD_COUNT + "이 아니다 — 감시 목록이 줄어들었다.");
    assertTrue(violations.isEmpty(),
        "0행으로 지정된 메서드 본문이 바뀌었다(의도된 변경이면 work-order 부록 A·§0.3을 먼저 고쳐야 한다):\n  "
            + String.join("\n  ", violations));
  }

  @Test
  void 대조군_고정본_본문에_1글자만_바꾸면_같은_비교기가_불일치로_판정한다() throws Exception {
    List<String> detected = new ArrayList<>();

    for (BodyTarget target : BODY_TARGETS) {
      String fixture = normalizeEol(readFixture(target.fixture(), target.fixtureBlobSha1()));
      String method = target.methods().get(0);
      String body = methodBody(fixture, target.label() + "(고정본)", method);

      String mutated = mutateOneChar(body);
      assertNotEquals(body, mutated, target.label() + "." + method + ": 변이가 만들어지지 않았다");

      boolean same = sameBody(body, mutated);
      detected.add(target.label() + "." + method + " → " + (same ? "불일치 미검출(!!)" : "불일치 검출"));
      assertFalse(same,
          target.label() + "." + method + ": 1글자 변이를 같다고 판정했다 — 비교기가 결함을 못 잡는다.");

      // 자기 자신과는 같다고 판정해야 한다(비교기가 "항상 불일치"가 아님).
      assertTrue(sameBody(body, body), target.label() + ": 같은 문자열을 다르다고 판정했다");
    }

    System.out.println("[002 양성 대조군(본문)] " + detected);
  }

  // ===================================================================
  // ⓑ 파일 무변경 비교
  // ===================================================================

  @Test
  void 범위_밖_13파일이_착수커밋과_바이트단위로_같다() throws Exception {
    List<String> violations = new ArrayList<>();

    for (Map.Entry<String, String> entry : FILE_TARGETS.entrySet()) {
      Path file = locate(entry.getKey());
      byte[] normalized = normalizeEol(Files.readAllBytes(file));
      String actual = gitBlobSha1(normalized);

      System.out.println("[002 파일] " + entry.getKey() + " → LF정규화 blob=" + actual);

      if (!entry.getValue().equals(actual)) {
        violations.add(entry.getKey() + ": 기대 " + entry.getValue() + " / 실측 " + actual);
      }
    }

    assertEquals(13, FILE_TARGETS.size(), "무변경 감시 파일 수가 13이 아니다 — 목록이 줄어들었다.");
    assertTrue(violations.isEmpty(),
        "이 사이클이 손대지 않기로 한 파일이 바뀌었다:\n  " + String.join("\n  ", violations));
  }

  @Test
  void 대조군_1바이트만_바꾼_바이트열은_같은_해시_비교기가_불일치로_판정한다() throws Exception {
    List<String> detected = new ArrayList<>();

    for (Map.Entry<String, String> entry : FILE_TARGETS.entrySet()) {
      Path file = locate(entry.getKey());
      byte[] normalized = normalizeEol(Files.readAllBytes(file));

      byte[] mutated = normalized.clone();
      int at = mutated.length / 2;
      mutated[at] = (byte) (mutated[at] ^ 0x01);

      String mutatedSha1 = gitBlobSha1(mutated);
      detected.add(entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1)
          + " → " + (entry.getValue().equals(mutatedSha1) ? "불일치 미검출(!!)" : "불일치 검출"));

      assertNotEquals(entry.getValue(), mutatedSha1,
          entry.getKey() + ": 1바이트 변이가 같은 해시를 냈다 — 해시 비교기가 결함을 못 잡는다.");
    }

    System.out.println("[002 양성 대조군(파일)] " + detected);
  }

  // ===================================================================
  // 픽스처 도우미 — TASK-002 DoD (d). 분류 판정은 하지 않는다(TASK-005 몫).
  // ===================================================================

  @Test
  void 픽스처_도우미는_실제_AnthropicLlmClient가_던진_예외를_돌려준다() {
    for (AnthropicErrorFixtures.Case fixture : AnthropicErrorFixtures.Case.values()) {
      WebClientResponseException thrown = AnthropicErrorFixtures.thrownBy(fixture);

      assertEquals(AnthropicErrorFixtures.status(fixture), thrown.getStatusCode().value(),
          fixture + ": 픽스처 상태 코드가 예외에 그대로 담겨야 한다");

      String message = thrown.getMessage();
      assertTrue(message != null && !message.isBlank(), fixture + ": 예외 메시지가 비어 있다");
      // 300자 절단 뒤에도 request_id가 남아야 한다(TASK-006 사유 정규화 검증의 전제).
      assertTrue(message.contains("req_"),
          fixture + ": 예외 메시지에 request_id가 남아 있지 않다. 원문=" + message);
      // Spring이 붙이는 앞머리(상태 코드) + 클라이언트가 만드는 "Claude API %d 오류:" 형식.
      assertTrue(message.startsWith(fixture.status() + " "),
          fixture + ": 예외 메시지 앞머리가 상태 코드로 시작하지 않는다. 원문=" + message);
      assertTrue(message.contains("Claude API " + fixture.status() + " 오류:"),
          fixture + ": 클라이언트가 만드는 메시지 형식이 아니다. 원문=" + message);

      // 401 1벌만 전문, 나머지는 request_id 앞 8자만 남겨 출력한다(식별자 노출 최소화).
      boolean full = fixture == AnthropicErrorFixtures.Case.AUTH_401_1;
      System.out.println("[002 픽스처 예외] " + fixture + " (" + (full ? "전문" : "가림") + ") "
          + (full ? message : AnthropicErrorFixtures.maskRequestIds(message)));
    }
  }

  @Test
  void 픽스처_401_네벌은_request_id만_다르다() {
    String base = null;
    List<String> ids = new ArrayList<>();
    List<String> maskedIds = new ArrayList<>();

    for (AnthropicErrorFixtures.Case fixture : List.of(
        AnthropicErrorFixtures.Case.AUTH_401_1, AnthropicErrorFixtures.Case.AUTH_401_2,
        AnthropicErrorFixtures.Case.AUTH_401_3, AnthropicErrorFixtures.Case.AUTH_401_4)) {
      String body = AnthropicErrorFixtures.body(fixture);
      int at = body.indexOf("\"request_id\"");
      assertTrue(at > 0, fixture + ": 본문 끝에 request_id가 없다");

      String head = body.substring(0, at);
      if (base == null) {
        base = head;
      } else {
        assertEquals(base, head, fixture + ": request_id 앞부분이 다른 벌과 달라졌다");
      }
      // 서로 다른지는 원문으로 보고(가린 값은 넷 다 같아지므로 판정에 쓸 수 없다), 출력은 가린 값으로 한다.
      ids.add(body.substring(at));
      maskedIds.add(AnthropicErrorFixtures.maskRequestIds(body.substring(at)));
    }

    assertEquals(4, ids.size());
    assertEquals(4, ids.stream().distinct().count(), "4벌의 request_id가 서로 달라야 한다: " + maskedIds);
    System.out.println("[002 픽스처 401 4벌] 공통 앞부분=" + base + " / request_id(가림)=" + maskedIds);
  }

  // ===================================================================
  // 도우미
  // ===================================================================

  /** 비교기 — 줄바꿈만 맞춘 뒤 문자 단위로 같은지. */
  private static boolean sameBody(String a, String b) {
    return normalizeEol(a).equals(normalizeEol(b));
  }

  private static String normalizeEol(String text) {
    return text.replace("\r\n", "\n");
  }

  /** 바이트열의 {@code \r\n} → {@code \n}. */
  private static byte[] normalizeEol(byte[] content) {
    byte[] out = new byte[content.length];
    int n = 0;
    for (int i = 0; i < content.length; i++) {
      if (content[i] == '\r' && i + 1 < content.length && content[i + 1] == '\n') {
        continue;
      }
      out[n++] = content[i];
    }
    byte[] trimmed = new byte[n];
    System.arraycopy(out, 0, trimmed, 0, n);
    return trimmed;
  }

  /**
   * 메서드 선언부 첫 줄(어노테이션은 제외 — 전 대상에 일관 적용)부터 짝이 맞는 닫는 중괄호가 있는
   * 줄 끝까지. 본문 안 주석은 포함한다(§0.5의 추출 범위).
   *
   * <p>선언 후보는 "이름 바로 앞 글자가 식별자 문자가 아니고, 같은 줄의 이름 앞쪽에 접근 수정자가
   * 있는" 것으로 고르고 <b>정확히 1개</b>일 것을 요구한다 — 접두어가 겹치는 신규 메서드가 생겨
   * (예: {@code setFailureCount} vs {@code resetFailureCount}) 엉뚱한 메서드를 비교하는 일을 막는다.
   */
  private static String methodBody(String source, String where, String methodName) {
    List<Integer> declarations = new ArrayList<>();
    int from = 0;
    while (true) {
      int at = source.indexOf(methodName + "(", from);
      if (at < 0) {
        break;
      }
      from = at + methodName.length();
      if (at > 0 && Character.isJavaIdentifierPart(source.charAt(at - 1))) {
        continue;
      }
      int lineStart = source.lastIndexOf('\n', at) + 1;
      String before = source.substring(lineStart, at);
      if (before.contains("private ") || before.contains("public ") || before.contains("protected ")) {
        declarations.add(at);
      }
    }

    if (declarations.size() != 1) {
      return fail(where + "에서 " + methodName + " 선언을 정확히 1개 찾지 못했다(찾은 수="
          + declarations.size() + ") — 이름이 바뀌었거나 접두어가 겹치는 메서드가 생겼다. G-02대로 멈춘다.");
    }

    int declAt = declarations.get(0);
    int lineStart = source.lastIndexOf('\n', declAt) + 1;
    int open = source.indexOf('{', declAt);
    if (open < 0) {
      return fail(where + "." + methodName + ": 본문 여는 중괄호를 찾지 못했다");
    }

    int depth = 0;
    for (int i = open; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          int lineEnd = source.indexOf('\n', i);
          return source.substring(lineStart, lineEnd < 0 ? source.length() : lineEnd);
        }
      }
    }
    return fail(where + "." + methodName + ": 중괄호 균형이 맞지 않는다");
  }

  /** 본문 한가운데 글자 하나를 다른 글자로 바꾼 변이(양성 대조군 입력). */
  private static String mutateOneChar(String body) {
    int at = body.length() / 2;
    char original = body.charAt(at);
    char replacement = original == 'X' ? 'Y' : 'X';
    return body.substring(0, at) + replacement + body.substring(at + 1);
  }

  private static String firstLine(String body) {
    int at = body.indexOf('\n');
    return (at < 0 ? body : body.substring(0, at)).trim();
  }

  private static String lastLine(String body) {
    int at = body.lastIndexOf('\n');
    return (at < 0 ? body : body.substring(at + 1)).trim();
  }

  /** 불일치 보고용 — 처음 어긋난 지점과 주변 문자열. */
  private static String firstDifference(String expected, String actual) {
    int limit = Math.min(expected.length(), actual.length());
    for (int i = 0; i < limit; i++) {
      if (expected.charAt(i) != actual.charAt(i)) {
        int from = Math.max(0, i - 40);
        return "offset " + i + " 부근 기대=[" + expected.substring(from, Math.min(expected.length(), i + 40))
            + "] 실측=[" + actual.substring(from, Math.min(actual.length(), i + 40)) + "]";
      }
    }
    return "길이만 다르다: 기대 " + expected.length() + " / 실측 " + actual.length();
  }

  private static String readCurrentSource(String relative) {
    Path file = locate(relative);
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return fail("감시 대상 소스를 읽지 못했다: " + file + " (" + e + ")");
    }
  }

  /** 고정본을 클래스패스에서 읽고 {@code git rev-parse 9b5906b:{경로}} 해시로 바이트 동일성을 먼저 확인한다. */
  private static String readFixture(String resource, String expectedBlobSha1) throws Exception {
    byte[] bytes;
    try (InputStream in = CounterIntegrityUnchangedMethodsGuardTest.class.getResourceAsStream(resource)) {
      if (in == null) {
        return fail("착수 커밋 고정본이 클래스패스에 없다: " + resource
            + " — 이 파일이 없으면 비교의 기준이 없다.");
      }
      bytes = in.readAllBytes();
    }
    assertEquals(expectedBlobSha1, gitBlobSha1(bytes),
        "고정본이 착수 커밋 blob과 바이트 단위로 같아야 한다: " + resource);
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
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      cursor = cursor.getParent();
    }
    return fail("파일을 찾지 못했다: " + relative + " (cwd=" + Path.of("").toAbsolutePath() + ")");
  }
}
