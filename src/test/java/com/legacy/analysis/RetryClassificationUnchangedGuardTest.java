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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-003 (work-order 2026-10-retry-and-classification v1) —
 * 재시도·분류 사이클이 <b>건드리지 않기로 한 것</b>을 착수 커밋 {@code f68f28e} 고정본과 대조해
 * 못박는 가드. 직전 사이클 가드({@code CounterIntegrityUnchangedMethodsGuardTest})와 같은 방식이고,
 * 감시 대상만 이 사이클 범위에 맞췄다(두 가드의 파일 감시 목록은 겹치지 않는다).
 *
 * <p>두 종류를 본다.
 * <ul>
 *   <li><b>ⓐ 선언 단위 본문</b> — 이 사이클이 고치는 3파일 안에서 "0행"으로 지정된 선언 15개.
 *       파일 전체로 묶으면 허용된 편집과 구분할 수 없으므로 보호 단위를 선언 하나로 둔다.</li>
 *   <li><b>ⓑ 파일 전체</b> — 아예 손대지 않는 7파일.</li>
 * </ul>
 *
 * <p><b>찾는 기준은 선언 줄 전체 문자열</b>이다(직전 가드는 {@code 이름(} 방식이었는데, 그 방식으로는
 * 오버로드가 둘인 {@code analyzeCodeWithClaude}를 가려낼 수 없다). 선언 줄이 파일 안에 정확히 한 번
 * 나오는 것도 함께 단언한다 — 줄이 바뀌었으면 비교가 아니라 멈춤이어야 한다(G-02).
 *
 * <p><b>검사기의 탐지력은 같은 테스트 안의 양성 대조군으로 증명한다</b>(G-04) — 고정본 본문에 1글자,
 * 파일 바이트열에 1바이트만 바꾼 입력을 같은 비교기에 넣어 "불일치"가 나오는 것을 단언한다.
 * 음성 결과("같다")가 "비교기가 아무것도 못 봐서 같아 보인 것"이 아님을 그렇게 가린다.
 *
 * <p>줄바꿈: 비교 전에 {@code \r\n}을 {@code \n}으로 맞춘다. 감시 대상 중
 * {@code ClaudeServiceImpl.java}만 인덱스가 CRLF다. 그래서 ⓑ의 기대값은 raw blob 해시가 아니라
 * <b>LF 정규화 후의 git blob SHA-1</b>로 고정한다(출처 명령은 {@code 05-dev-progress.md}에 기록).
 * ⓐ의 고정본 해시는 {@code git show}로 떠낸 바이트 그대로이므로 raw blob 값이다.
 */
class RetryClassificationUnchangedGuardTest {

  // ===================================================================
  // ⓐ 선언 단위 본문 비교 대상
  // ===================================================================

  /** 감시 대상 파일 하나 = 현재 소스 경로 + 착수 커밋 고정본 리소스 + 고정본 raw blob 해시 + 0행 선언 줄 목록. */
  private record BodyTarget(String label, String sourcePath, String fixture, String fixtureBlobSha1,
                            List<String> declarations) {
  }

  private static final List<BodyTarget> BODY_TARGETS = List.of(
      new BodyTarget("ClaudeServiceImpl",
          "src/main/java/com/legacy/analysis/ClaudeServiceImpl.java",
          "/retryclassification/ClaudeServiceImpl.java.before-f68f28e.txt",
          "0b3b6b2fc16d675f19ade0f8d23c5fcde4e86193",
          List.of(
              "    private LlmProvider resolveProvider(String modelKey) {",
              "    private boolean requiresAnthropicApiKey(String sourceFolderPath) {",
              "    public String generateSessionClaudeMd(String customRequirements, Set<String> extensions, String sourceFolderPath) {",
              "    public String analyzeCodeWithClaude(String sourceCode, String fileName, String sourceFolderPath) {",
              "    private String buildSimilarCodeContext(String excludeFilePath, String sourceCode, String sourceFolderPath) {",
              "    private String generateProjectReadmeWithClaude(String projectStructure, String sourceFolderPath) {",
              "    private String mergeCommentsIntoCode(String sourceCode, String jsonResponse, String extension) {",
              "    private void extractAndStoreTokenUsage(LlmResult result, String modelUsed, String sourceFolderPath) {")),
      new BodyTarget("ApiErrorHandler",
          "src/main/java/com/legacy/core/ApiErrorHandler.java",
          "/retryclassification/ApiErrorHandler.java.before-f68f28e.txt",
          "fc35369fdb5391d9068e64515fffc6954ec1849a",
          List.of(
              "  public static int httpStatusOf(Throwable t) {",
              "  public static String failureReasonLabel(String errorTypeName) {",
              "  public enum ErrorType {",
              "  private static final Map<String, String> FAILURE_REASON_LABELS = Map.of(")),
      new BodyTarget("RetryHandler",
          "src/main/java/com/legacy/analysis/RetryHandler.java",
          "/retryclassification/RetryHandler.java.before-f68f28e.txt",
          "8e9e6d67d2a64837cb290206beadf358f188329b",
          List.of(
              "  public RetryHandler(ApiErrorHandler apiErrorHandler, SessionConfig sessionConfig) {",
              "  private ApiErrorHandler.ErrorType classifyError(Exception e) {",
              "  public interface RetryableTask<T> {")));

  /** 지정된 0행 선언 수(8 + 4 + 3). 목록이 조용히 줄어드는 것을 막는다. */
  private static final int EXPECTED_BODY_COUNT = 15;

  // ===================================================================
  // ⓑ 파일 무변경 대상 — 기대값은 LF 정규화 후 git blob SHA-1
  // ===================================================================

  private static final Map<String, String> FILE_TARGETS = new LinkedHashMap<>();

  static {
    FILE_TARGETS.put("src/main/java/com/legacy/analysis/MainApiController.java",
        "cbf7a159a83eb3e7fdef3e7af9ec0987ebaf697a");
    FILE_TARGETS.put("src/main/java/com/legacy/analysis/SessionState.java",
        "d73eaeca4b1b951c5aa4931dafa95b46293ddc6e");
    FILE_TARGETS.put("src/main/java/com/legacy/analysis/AnalysisException.java",
        "56f6ee38969a35a8e5f9e6ea9ae46e0e5cc0733f");
    FILE_TARGETS.put("src/main/java/com/legacy/analysis/llm/OpenAiCompatibleLlmClient.java",
        "69a95d93d66b104eb1b61bc6b9ef57cef14f3b10");
    FILE_TARGETS.put("src/main/java/com/legacy/analysis/llm/LlmClientResolver.java",
        "e11aa59a1559957eddb5478e3c9c5b755b7c7e54");
    FILE_TARGETS.put("src/main/java/com/legacy/analysis/llm/LlmClient.java",
        "8d673494687865fe884a737a79c81f3204831b87");
    FILE_TARGETS.put("src/main/java/com/legacy/core/FileIoErrorHandler.java",
        "ff8b5780e50f481e8b00c334c33e4b2fea26d635");
  }

  private static final int EXPECTED_FILE_COUNT = 7;

  // ===================================================================
  // ⓐ 본문 비교 (G-a) · 양성 대조군 (G-b)
  // ===================================================================

  @Test
  void Ga_영행_지정_선언의_본문이_착수커밋_고정본과_문자단위로_같다() throws Exception {
    List<String> violations = new ArrayList<>();
    int compared = 0;

    for (BodyTarget target : BODY_TARGETS) {
      String current = normalizeEol(readCurrentSource(target.sourcePath()));
      String fixture = normalizeEol(readFixture(target.fixture(), target.fixtureBlobSha1()));

      for (String declaration : target.declarations()) {
        String currentBody = declarationBody(current, target.label() + "(현재)", declaration);
        String fixtureBody = declarationBody(fixture, target.label() + "(고정본)", declaration);
        compared++;

        // 추출이 한쪽에서만 다르게 동작하지 않았음을 보이는 출력.
        System.out.println("[RC 본문] " + target.label() + " | 첫줄=" + firstLine(currentBody)
            + " | 현재 끝줄=" + lastLine(currentBody) + " | 고정본 끝줄=" + lastLine(fixtureBody)
            + " | 길이 현재/고정본=" + currentBody.length() + "/" + fixtureBody.length());

        if (!sameBody(currentBody, fixtureBody)) {
          violations.add(target.label() + " " + firstLine(declaration) + ": 본문이 착수 커밋과 다르다 ("
              + firstDifference(fixtureBody, currentBody) + ")");
        }
      }
    }

    assertEquals(EXPECTED_BODY_COUNT, compared,
        "0행 지정 선언 수가 " + EXPECTED_BODY_COUNT + "이 아니다 — 감시 목록이 줄어들었다.");
    assertTrue(violations.isEmpty(),
        "0행으로 지정된 본문이 바뀌었다(의도된 변경이면 work-order §0.4·§0.5를 먼저 고쳐야 한다):\n  "
            + String.join("\n  ", violations));
  }

  @Test
  void Gb_대조군_고정본_본문에_1글자만_바꾸면_같은_비교기가_불일치로_판정한다() throws Exception {
    List<String> detected = new ArrayList<>();

    for (BodyTarget target : BODY_TARGETS) {
      String fixture = normalizeEol(readFixture(target.fixture(), target.fixtureBlobSha1()));

      for (String declaration : target.declarations()) {
        String body = declarationBody(fixture, target.label() + "(고정본)", declaration);
        String mutated = mutateOneChar(body);
        assertNotEquals(body, mutated, target.label() + ": 변이가 만들어지지 않았다");

        boolean same = sameBody(body, mutated);
        detected.add(target.label() + " " + firstLine(declaration)
            + " → " + (same ? "불일치 미검출(!!)" : "불일치 검출"));
        assertFalse(same,
            target.label() + " " + firstLine(declaration)
                + ": 1글자 변이를 같다고 판정했다 — 비교기가 결함을 못 잡는다.");

        // 자기 자신과는 같다고 판정해야 한다(비교기가 "항상 불일치"가 아님).
        assertTrue(sameBody(body, body), target.label() + ": 같은 문자열을 다르다고 판정했다");
      }
    }

    assertEquals(EXPECTED_BODY_COUNT, detected.size(),
        "양성 대조군 건수가 " + EXPECTED_BODY_COUNT + "이 아니다 — 대조 대상이 줄어들었다.");
    detected.forEach(line -> System.out.println("[RC 양성 대조군(본문)] " + line));
  }

  // ===================================================================
  // ⓑ 파일 무변경 비교 (G-c) · 양성 대조군 (G-d)
  // ===================================================================

  @Test
  void Gc_범위_밖_7파일이_착수커밋과_바이트단위로_같다() throws Exception {
    List<String> violations = new ArrayList<>();

    for (Map.Entry<String, String> entry : FILE_TARGETS.entrySet()) {
      Path file = locate(entry.getKey());
      byte[] normalized = normalizeEol(Files.readAllBytes(file));
      String actual = gitBlobSha1(normalized);

      System.out.println("[RC 파일] " + entry.getKey() + " | LF정규화 바이트=" + normalized.length
          + " | blob=" + actual);

      if (!entry.getValue().equals(actual)) {
        violations.add(entry.getKey() + ": 기대 " + entry.getValue() + " / 실측 " + actual);
      }
    }

    assertEquals(EXPECTED_FILE_COUNT, FILE_TARGETS.size(),
        "무변경 감시 파일 수가 " + EXPECTED_FILE_COUNT + "이 아니다 — 목록이 줄어들었다.");
    assertTrue(violations.isEmpty(),
        "이 사이클이 손대지 않기로 한 파일이 바뀌었다:\n  " + String.join("\n  ", violations));
  }

  @Test
  void Gd_대조군_1바이트만_바꾼_바이트열은_같은_해시_비교기가_불일치로_판정한다() throws Exception {
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
      // 변이하지 않은 바이트열은 같다고 판정해야 한다(비교기가 "항상 불일치"가 아님).
      assertEquals(entry.getValue(), gitBlobSha1(normalized),
          entry.getKey() + ": 같은 바이트열을 다르다고 판정했다");
    }

    assertEquals(EXPECTED_FILE_COUNT, detected.size(),
        "양성 대조군 건수가 " + EXPECTED_FILE_COUNT + "이 아니다 — 대조 대상이 줄어들었다.");
    detected.forEach(line -> System.out.println("[RC 양성 대조군(파일)] " + line));
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
   * 선언 줄이 <b>줄 전체로 정확히 한 번</b> 나오는 위치. 0개면 이름·시그니처가 바뀐 것이고,
   * 2개 이상이면 비교 대상이 모호한 것이다 — 둘 다 비교가 아니라 멈춤이어야 한다.
   */
  private static int declarationStart(String source, String where, String declaration) {
    List<Integer> hits = new ArrayList<>();
    int from = 0;
    while (true) {
      int at = source.indexOf(declaration, from);
      if (at < 0) {
        break;
      }
      from = at + 1;
      int end = at + declaration.length();
      boolean lineStart = at == 0 || source.charAt(at - 1) == '\n';
      boolean lineEnd = end == source.length() || source.charAt(end) == '\n';
      if (lineStart && lineEnd) {
        hits.add(at);
      }
    }
    if (hits.size() != 1) {
      fail(where + "에서 선언 줄을 정확히 1개 찾지 못했다(찾은 수=" + hits.size() + "): "
          + declaration.trim() + " — 선언이 바뀌었다. G-02대로 멈춘다.");
    }
    return hits.get(0);
  }

  /**
   * 선언 줄 첫 글자부터 그 선언이 끝나는 줄 끝까지. 선언 줄에 {@code &#123;}가 있으면 블록이므로
   * 짝이 맞는 닫는 중괄호가 있는 줄까지, 없으면 필드 선언이므로 첫 {@code ;}가 있는 줄까지.
   * 본문 안 주석은 포함한다.
   */
  private static String declarationBody(String source, String where, String declaration) {
    int at = declarationStart(source, where, declaration);
    String body = declaration.contains("{")
        ? blockBody(source, where, at)
        : statementBody(source, where, at);

    // 추출이 엉뚱한 지점에서 끝나면 조용히 통과하지 않게 끝 모양을 함께 단언한다.
    String last = lastLine(body);
    if (declaration.contains("{")) {
      assertEquals("}", last, where + ": 블록 추출이 닫는 중괄호 줄에서 끝나지 않았다 — " + declaration.trim());
    } else {
      assertTrue(last.endsWith(";"),
          where + ": 필드 선언 추출이 세미콜론 줄에서 끝나지 않았다 — " + declaration.trim());
    }
    return body;
  }

  private static String blockBody(String source, String where, int declAt) {
    int open = source.indexOf('{', declAt);
    int depth = 0;
    for (int i = open; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return source.substring(declAt, lineEndFrom(source, i));
        }
      }
    }
    return fail(where + ": 중괄호 균형이 맞지 않는다");
  }

  private static String statementBody(String source, String where, int declAt) {
    int semi = source.indexOf(';', declAt);
    if (semi < 0) {
      return fail(where + ": 선언을 끝내는 세미콜론을 찾지 못했다");
    }
    return source.substring(declAt, lineEndFrom(source, semi));
  }

  private static int lineEndFrom(String source, int at) {
    int lineEnd = source.indexOf('\n', at);
    return lineEnd < 0 ? source.length() : lineEnd;
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

  /** 고정본을 클래스패스에서 읽고 {@code git rev-parse f68f28e:{경로}} 해시로 바이트 동일성을 먼저 확인한다. */
  private static String readFixture(String resource, String expectedBlobSha1) throws Exception {
    byte[] bytes;
    try (InputStream in = RetryClassificationUnchangedGuardTest.class.getResourceAsStream(resource)) {
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
