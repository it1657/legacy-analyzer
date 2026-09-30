package com.legacy.analysis.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TASK-001 (work-order 2026-09-pricing-source-unification v1, REQ-002) — 단가 문자열이
 * <b>어디에 몇 개 있는지</b>를 재는 스캐너와 그 스캐너의 <b>탐지력 증명</b>을 담는다.
 *
 * <h2>이 TASK에서 넣는 것 / 넣지 않는 것</h2>
 * <p>여기에는 <b>스캐너와 양성 대조군만</b> 둔다. "현재 {@code src/main}에 허용 목록 밖 단가 리터럴이
 * 0건"이라는 <b>본 단언은 TASK-006</b>이 이 파일에 추가한다(work-order가 명시한 유일한 후속 편집 허용
 * 대상). 착수 시점에는 그 단언이 RED이므로 지금 넣지 않는다 — 대신 착수 시점 {@code src/main} 스캔
 * 결과는 {@code 05-dev-progress.md}에 <b>관측값으로만</b> 기록했다(TASK-001 DoD (d)).
 *
 * <h2>왜 고정본 사본을 대조군으로 쓰는가</h2>
 * <p>"0건"류의 음성 결과는 검사가 실제로 동작할 때만 의미가 있다. 그래서 착수 커밋
 * {@code 318e086}의 실소스 3개를 <b>바이트 그대로</b> 테스트 리소스로 떠 두고, 같은 스캐너를 거기에
 * 적용해 <b>3건 / 3건 / 1건이 실제로 검출됨</b>을 매 실행 확인한다. 고정본이 편집되면 대조군으로 쓸 수
 * 없으므로 git blob 해시도 같은 실행에서 대조한다.
 *
 * <p>고정본에 단가가 3/3/1건 있다는 사실이 이 사이클의 출발점이다 — 같은 단가 숫자가 코드 정본
 * ({@link AnthropicModelPricing}) 밖에 <b>네 벌</b>로 복제돼 있고, DB 시드는 테이블이 완전히 비었을
 * 때만 돌기 때문에 코드 단가를 고쳐도 이미 시드된 DB의 표시명은 따라오지 않는다.
 *
 * <h2>{@code $}가 들어간 정상 코드를 잡지 않는다</h2>
 * <p>JS 템플릿 리터럴({@code ${...}})과 정규식 치환 참조({@code "$1"})는 이 저장소 실소스에 실제로
 * 있다. 스캐너가 이것들을 잡으면 TASK-006의 "0건" 단언이 영구히 RED가 되므로, 음성 반례로 함께
 * 고정한다.
 *
 * <h2>TASK-006에서 추가된 것 (v4 기준)</h2>
 * <p>위 "이 TASK에서 넣는 것 / 넣지 않는 것" 절은 <b>TASK-001 시점 서술</b>이다. TASK-006이
 * work-order의 허용(G-07 화이트리스트)에 따라 이 파일에 아래를 추가했고, TASK-001의 기존 단언은
 * 하나도 삭제·완화하지 않았다.
 * <ul>
 *   <li><b>(A)</b> 현재 {@code src/main} 3디렉터리에 허용 목록 밖 단가 리터럴 0건 — 순회는 파일시스템
 *       기준(git 명령 비의존, QA의 {@code git archive} 사본엔 {@code .git}이 없다)이고, 같은 실행에서
 *       <b>순회 대조군</b>(파일 수 &gt; 0 / 필수 4파일 포함 / 허용 파일 원시 검출 ≥ 1 / 디코드 불가 0건)을
 *       함께 단언한다(§0.2 C7).</li>
 *   <li><b>(B)</b> {@code dashboard.js}가 API {@code pricing}으로 문구를 조립하는 구조(RG-4·RG-5 정적 보강).</li>
 *   <li><b>(C)</b> 관리자 표시명 입력란·모달 안내(TASK-005 산출물 고정).</li>
 *   <li><b>양성 대조군</b> — (A)(B)(C)의 <b>같은 검사 함수</b>를 고정본 3개에 적용하면 각각 위반이 잡힌다.
 *       "현재는 통과, 고정본은 위반"이 한 클래스 안에서 매 실행 확인된다.</li>
 *   <li><b>드리프트 모의</b> — 허용 블록 밖/안을 <b>메모리에서</b> 변조한 사본이 위반으로 잡힌다(워킹트리 무수정).</li>
 *   <li><b>결속 단언(v4 X1)</b> — 현재 {@code LEGACY_SEED_DISPLAY_NAMES}의 3쌍이 착수 커밋 고정본의
 *       시드 3행과 완전히 같다. 기존 테스트의 옛 원문은 사람이 옮겨 적은 리터럴이라 <b>맵과 같이 고치면
 *       통과</b>하지만, 고정본은 사람이 고칠 수 없으므로 그쪽에 묶는다.</li>
 * </ul>
 *
 * <p>참고: 이 파일 자체에는 단가 리터럴이 반례·기대값으로 들어 있다. TASK-006 (A)의 스캔 범위는
 * {@code src/main/java}·{@code src/main/resources/static}·{@code src/main/resources/templates}뿐이므로
 * {@code src/test}는 대상이 아니다(G-14는 {@code src/main}의 주석·문구를 규율한다).
 */
class PricingSingleSourceContractTest {

  /**
   * 단가 리터럴 스캐너 정규식 — {@code $숫자/$숫자} 형태. 소수점과 {@code /} 앞뒤 공백,
   * {@code $} 뒤 공백 1개까지 허용한다(work-order TASK-001 3번).
   */
  static final Pattern PRICING_LITERAL =
      Pattern.compile("\\$\\s?\\d+(?:\\.\\d+)?\\s*/\\s*\\$\\s?\\d+(?:\\.\\d+)?");

  /** 검출 1건 = (1부터 시작하는 행번호, 그 행의 원문). */
  record Hit(int line, String content) {
    @Override
    public String toString() {
      return line + ": " + content.trim();
    }
  }

  // ── 착수 커밋 318e086 고정본 (클래스패스 리소스) ────────────────────────────

  private static final String FIXTURE_DIR = "/pricingsinglesource/";

  private static final String SERVICE_FIXTURE = FIXTURE_DIR + "LlmModelOptionService.java.before-318e086.txt";
  private static final String DASHBOARD_JS_FIXTURE = FIXTURE_DIR + "dashboard.js.before-318e086.txt";
  private static final String ADMIN_HTML_FIXTURE = FIXTURE_DIR + "admin-dashboard.html.before-318e086.txt";

  /** {@code git rev-parse 318e086:<원본경로>} 값. 고정본이 원본과 한 바이트도 다르지 않다는 증거. */
  private static final String SERVICE_FIXTURE_BLOB_SHA1 = "3ddce669f88acc3639cd0e97fb7c9f96bc3e504a";
  private static final String DASHBOARD_JS_FIXTURE_BLOB_SHA1 = "bc17be93789566f3fabff5225a0e4a5f58dc0804";
  private static final String ADMIN_HTML_FIXTURE_BLOB_SHA1 = "54b3d469a5b16f592a2e7c737d8ec659d23ebd8b";

  // ────────────────────────────────────────────────────────────────────────────
  // 스캐너 (TASK-006이 재사용한다 — static)
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * 텍스트에서 단가 리터럴이 들어 있는 행을 모두 찾아 (행번호, 행 원문)으로 돌려준다.
   * 한 행에 여러 개가 있어도 <b>행 단위로 1건</b>이다.
   */
  static List<Hit> scanText(String text) {
    List<Hit> hits = new ArrayList<>();
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (PRICING_LITERAL.matcher(lines[i]).find()) {
        hits.add(new Hit(i + 1, lines[i]));
      }
    }
    return hits;
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 양성 대조군 — 착수 커밋 고정본에서 3건 / 3건 / 1건이 검출된다
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * 양성 대조군 ① — DB 시드 {@code seedDefaultsIfEmpty()}의 표시명 3벌.
   */
  @Test
  void 양성대조군_고정본_LlmModelOptionService에서_시드_표시명_3건이_검출된다() throws Exception {
    List<Hit> hits = scanFixture(SERVICE_FIXTURE, SERVICE_FIXTURE_BLOB_SHA1);

    assertEquals(3, hits.size(),
        "착수 커밋 고정본의 시드 표시명 3건이 검출돼야 한다 — 이게 실패하면 스캐너가 실물을 못 잡는 것이므로"
            + " TASK-006의 '0건' 단언은 아무것도 증명하지 못한다. hits=" + hits);
    assertEquals(List.of(232, 233, 234), lineNumbers(hits), "검출 행번호. hits=" + hits);
    assertEquals(
        List.of(
            "create(\"claude-sonnet-4-6\", \"Claude Sonnet (권장 · $3/$15 per 1M)\", LlmProvider.ANTHROPIC, 0);",
            "create(\"claude-opus-4-8\", \"Claude Opus (고품질 · $15/$75 per 1M)\", LlmProvider.ANTHROPIC, 1);",
            "create(\"claude-haiku-4-5-20251001\", \"Claude Haiku (빠름/저비용 · $0.80/$4 per 1M)\", LlmProvider.ANTHROPIC, 2);"),
        trimmedContents(hits),
        "검출된 3행은 시드 create(...) 호출이어야 한다. hits=" + hits);
  }

  /**
   * 양성 대조군 ② — 프런트 비상용 폴백 목록 {@code FALLBACK_MODEL_OPTIONS}의 표시명 3벌.
   */
  @Test
  void 양성대조군_고정본_dashboard_js에서_폴백목록_표시명_3건이_검출된다() throws Exception {
    List<Hit> hits = scanFixture(DASHBOARD_JS_FIXTURE, DASHBOARD_JS_FIXTURE_BLOB_SHA1);

    assertEquals(3, hits.size(),
        "착수 커밋 고정본의 FALLBACK_MODEL_OPTIONS 표시명 3건이 검출돼야 한다. hits=" + hits);
    assertEquals(List.of(602, 603, 604), lineNumbers(hits), "검출 행번호. hits=" + hits);
    assertEquals(
        List.of(
            "{ modelKey: 'claude-sonnet-4-6', displayName: 'Claude Sonnet (권장 · $3/$15 per 1M)' },",
            "{ modelKey: 'claude-opus-4-8', displayName: 'Claude Opus (고품질 · $15/$75 per 1M)' },",
            "{ modelKey: 'claude-haiku-4-5-20251001', displayName: 'Claude Haiku (빠름/저비용 · $0.80/$4 per 1M)' }"),
        trimmedContents(hits),
        "검출된 3행은 폴백 목록 항목이어야 한다. hits=" + hits);
  }

  /**
   * 양성 대조군 ③ — 관리자 표시명 입력란 placeholder의 네 번째 사본.
   */
  @Test
  void 양성대조군_고정본_admin_dashboard_html에서_placeholder_1건이_검출된다() throws Exception {
    List<Hit> hits = scanFixture(ADMIN_HTML_FIXTURE, ADMIN_HTML_FIXTURE_BLOB_SHA1);

    assertEquals(1, hits.size(),
        "착수 커밋 고정본의 관리자 placeholder 1건이 검출돼야 한다. hits=" + hits);
    assertEquals(List.of(916), lineNumbers(hits), "검출 행번호. hits=" + hits);
    assertEquals(
        List.of("<input type=\"text\" id=\"llmModelDisplayName\" required placeholder=\"예: Claude Sonnet (권장 · $3/$15 per 1M)\">"),
        trimmedContents(hits),
        "검출된 1행은 #llmModelDisplayName의 placeholder여야 한다. hits=" + hits);
  }

  /**
   * 고정본 3개의 합이 7건이고, 그 7건이 코드 정본 밖의 <b>복제</b>라는 사실을 한 줄로 고정한다.
   * TASK-006 (A)는 이 7건이 "허용 블록 안 3건"으로 줄어드는 것을 단언한다.
   */
  @Test
  void 양성대조군_고정본_3개_합계_7건이_코드정본_밖_복제다() throws Exception {
    int service = scanFixture(SERVICE_FIXTURE, SERVICE_FIXTURE_BLOB_SHA1).size();
    int js = scanFixture(DASHBOARD_JS_FIXTURE, DASHBOARD_JS_FIXTURE_BLOB_SHA1).size();
    int admin = scanFixture(ADMIN_HTML_FIXTURE, ADMIN_HTML_FIXTURE_BLOB_SHA1).size();

    System.out.println("[pricing-scan before-318e086] service=" + service + " dashboardJs=" + js
        + " adminHtml=" + admin + " total=" + (service + js + admin));
    assertEquals(7, service + js + admin,
        "착수 커밋 시점 복제 건수(시드 3 + 폴백 3 + placeholder 1)");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 파라미터화 반례 — 스캐너가 무엇을 잡고 무엇을 안 잡는가
  // ────────────────────────────────────────────────────────────────────────────

  /** 단가 표기의 변형들. 하나라도 빠지면 그 모양으로 드리프트가 숨을 수 있다. */
  @ParameterizedTest(name = "검출돼야 한다: {0}")
  @ValueSource(strings = {
      "$3/$15",
      "$0.80/$4",
      "$15 / $75",
      "$ 9/$ 99 per 1M",
      "$1.25/$6.50",
      "Claude Sonnet (권장 · $3/$15 per 1M)",
      "placeholder=\"예: Claude Haiku (빠름/저비용 · $0.80/$4 per 1M)\"",
  })
  void 반례_양성_단가표기_변형은_검출된다(String input) {
    List<Hit> hits = scanText(input);
    assertEquals(1, hits.size(), "단가 표기를 검출하지 못했다: <" + input + ">");
    assertEquals(1, hits.get(0).line(), "한 줄 입력의 행번호는 1이다");
  }

  /**
   * {@code $}가 들어가지만 단가가 아닌 것들. 특히 JS 템플릿 리터럴과 정규식 치환 참조는
   * 이 저장소 실소스({@code dashboard.js}의 {@code ${...}},
   * {@code com.legacy.core.PresentationGeneratorService}의 {@code "$1"})에 실제로 존재한다 —
   * 이걸 잡으면 TASK-006의 "0건"은 영구히 달성 불가가 된다.
   */
  @ParameterizedTest(name = "검출되면 안 된다: {0}")
  @ValueSource(strings = {
      "${}",
      "$",
      "3/15",
      "`${m.displayName}`",
      "\"${}/${}\"",
      ".replaceAll(\"\\\\*\\\\*(.*?)\\\\*\\\\*\", \"$1\")",
      "\"[$1]\"",
      "option.textContent = `${m.displayName} / ${m.modelKey}`;",
      "const label = `${a}/${b}`;",
  })
  void 반례_음성_단가가_아닌_달러표기는_검출되지_않는다(String input) {
    List<Hit> hits = scanText(input);
    assertTrue(hits.isEmpty(), "단가가 아닌데 검출됐다(오탐): <" + input + "> hits=" + hits);
  }

  /** 여러 행 입력에서 행번호가 1부터 정확히 세어진다(고정본 행번호 단언의 신뢰 근거). */
  @Test
  void 스캐너는_행번호를_1부터_정확히_센다() {
    String text = String.join("\n", "첫 줄", "$3/$15", "셋째 줄", "3/15", "$15/$75");
    List<Hit> hits = scanText(text);
    assertEquals(List.of(2, 5), lineNumbers(hits), "hits=" + hits);
  }

  // ════════════════════════════════════════════════════════════════════════════
  // TASK-006 (A) — 현재 src/main 에 허용 목록 밖 단가 리터럴이 0건
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * (A) 본 단언 + <b>순회 대조군</b>(work-order §0.2 C7).
   *
   * <p>"0건"은 검사가 실제로 파일을 읽었을 때만 의미가 있다. 순회 루트가 잘못 풀리거나 확장자 필터가
   * 과하게 걸려 <b>0개 파일을 읽으면 검출도 0건 → 거짓 GREEN</b>이 된다. 그래서 같은 실행에서
   * ⓐ 순회 파일 수 &gt; 0 ⓑ 필수 4파일 포함 ⓒ 허용 파일 원시 검출 ≥ 1건 ⓓ 디코드 불가 0건을 함께 단언한다.
   */
  @Test
  void A_현재_src_main에_허용목록_밖_단가리터럴이_0건이다() {
    MainScan scan = walkMainSources();

    // ── 순회 대조군 ⓐ — 파일을 하나도 못 읽고 "0건"이 되는 경로를 막는다 ──
    assertTrue(scan.relPaths().size() > 0,
        "순회한 파일이 0개다 — 이 상태에서 '검출 0건'은 아무것도 증명하지 못한다(거짓 GREEN)."
            + " 순회 루트=" + SCAN_ROOTS + ", 프로젝트 루트=" + projectRoot());
    System.out.println("[pricing-scan main] 순회 파일 " + scan.relPaths().size()
        + "개 / 디코드 불가 " + scan.undecodable().size() + "개 / 순회 루트=" + SCAN_ROOTS);

    // ── 순회 대조군 ⓑ — 정말 봐야 하는 4파일이 순회 목록에 들어 있는가 ──
    for (String must : List.of(PRICING_SRC, SERVICE_SRC, DASHBOARD_JS_SRC, ADMIN_HTML_SRC)) {
      assertTrue(scan.relPaths().contains(must),
          "순회 목록에 반드시 있어야 하는 파일이 없다: " + must
              + " — 확장자 필터나 순회 루트가 잘못된 것이다. 순회 " + scan.relPaths().size() + "개");
    }

    // ── 순회 대조군 ⓒ — 허용 파일에서 원시 검출이 실제로 1건 이상 나오는가(허용 목록으로 걸러지기 전) ──
    List<Hit> pricingRaw = scanText(scan.texts().get(PRICING_SRC));
    assertTrue(pricingRaw.size() >= 1,
        "허용 파일 " + ALLOWED_FILE + " 에서 원시 검출이 1건도 없다 — 스캐너가 이 실행에서 동작하지 않는다는 뜻이므로"
            + " 다른 파일의 '0건'도 신뢰할 수 없다.");
    System.out.println("[pricing-scan main] 허용 파일 원시 검출 " + pricingRaw.size() + "건 " + pricingRaw);

    // ── 순회 대조군 ⓓ — 디코드 불가 파일을 조용히 건너뛰지 않는다 ──
    assertEquals(List.of(), scan.undecodable(),
        "UTF-8로 디코드하지 못한 파일이 있다. 조용히 건너뛰면 그 파일의 단가 리터럴이 '0건'에 숨는다"
            + " — 이 사이클에서 CP949 오독을 이미 겪었으므로 실재하는 경로다.");

    // ── 본 단언 ──
    List<String> violations = checkPricingSingleSource(scan.texts());
    assertEquals(List.of(), violations,
        "src/main 에 허용 목록 밖 단가 리터럴이 있다. 허용은 딱 두 곳 — " + ALLOWED_FILE + " 파일 전체와 "
            + BLOCK_HOLDER_FILE + " 의 " + LEGACY_MAP_NAME + " 선언 블록 안 3건뿐이다."
            + " 화면·운영 표면에 단가 사본이 다시 생기면 코드 단가를 고쳐도 그쪽이 따라오지 않는다.");

    // ── 최종 관측표 출력(DoD (b) — 착수 시점 9건과 나란히 놓을 근거) ──
    System.out.println("[pricing-scan main] 파일별 검출 관측표:");
    scan.texts().forEach((path, text) -> {
      List<Hit> hits = scanText(text);
      if (!hits.isEmpty()) {
        System.out.println("  " + path + " : " + hits.size() + "건");
        hits.forEach(h -> System.out.println("      " + h.line() + ": " + h.content().trim()));
      }
    });
  }

  /** 허용 블록이 <b>이름으로</b> 찾아지고 그 안 검출이 정확히 3건임을 별도로 고정한다(작업내용 1). */
  @Test
  void A_허용블록은_이름으로_찾아지고_블록_안_검출이_정확히_3건이다() {
    String service = walkMainSources().texts().get(SERVICE_SRC);
    Block block = extractLegacySeedBlock(service);

    assertNotNull(block, LEGACY_MAP_NAME + " 선언 블록을 이름으로 찾지 못했다(행번호로 찾지 않는다).");
    List<Hit> inside = hitsInside(scanText(service), block);
    assertEquals(3, inside.size(),
        "허용 블록 안 검출은 정확히 3건이어야 한다(옛 시드 원문 3벌). block="
            + block.startLine() + "~" + block.endLine() + "행, inside=" + inside);
    assertEquals(0, hitsOutside(scanText(service), block).size(),
        "허용 블록 밖 검출은 0건이어야 한다. outside=" + hitsOutside(scanText(service), block));
    System.out.println("[pricing-scan block] " + LEGACY_MAP_NAME + " 블록 "
        + block.startLine() + "~" + block.endLine() + "행, 블록 안 검출 " + inside.size() + "건");
  }

  /**
   * 양성 대조군(작업내용 4) — <b>(A)와 같은 검사 함수</b>를 착수 커밋 고정본에 적용하면 위반이 잡힌다.
   * "현재는 통과, 고정본은 위반"이 한 클래스 안에서 매 실행 확인된다.
   */
  @Test
  void A_대조군_고정본_LlmModelOptionService는_블록부재와_블록밖_3건으로_위반된다() throws Exception {
    // 해시 검증은 기존 경로로 먼저 통과시킨다(어긋나면 여기서 이미 RED)
    List<Hit> hits = scanFixture(SERVICE_FIXTURE, SERVICE_FIXTURE_BLOB_SHA1);
    assertEquals(3, hits.size(), "고정본 검출 3건 전제. hits=" + hits);

    String fixture = fixtureText(SERVICE_FIXTURE);
    assertNull(extractLegacySeedBlock(fixture),
        "착수 커밋에는 " + LEGACY_MAP_NAME + " 블록이 아직 없다 — 있으면 이 대조군이 성립하지 않는다.");

    List<String> violations = checkPricingSingleSource(Map.of(SERVICE_SRC, fixture));
    System.out.println("[pricing-scan 대조군 service] 위반 " + violations.size() + "건 " + violations);
    assertEquals(4, violations.size(),
        "블록 부재 1건 + 블록 부재 상태의 검출 3건 = 4건이 잡혀야 한다. violations=" + violations);
    assertTrue(violations.get(0).contains("찾지 못했다"),
        "첫 위반은 블록 부재여야 한다. violations=" + violations);
    assertEquals(3, violations.stream().filter(s -> s.contains("(블록 부재)")).count(),
        "시드 3행이 각각 위반으로 잡혀야 한다. violations=" + violations);
  }

  /**
   * 드리프트 모의(작업내용 5, G-04 ②) — 허용 블록 <b>밖</b>에 단가 한 줄을 끼운 <b>메모리 사본</b>이
   * (A) 검사에서 위반으로 잡힌다. 워킹트리는 건드리지 않는다.
   */
  @Test
  void A_드리프트모의_허용블록_밖에_단가를_끼우면_위반으로_잡힌다() {
    String service = walkMainSources().texts().get(SERVICE_SRC);
    assertEquals(List.of(), checkPricingSingleSource(Map.of(SERVICE_SRC, service)),
        "변조 전 원본은 통과해야 한다(대조군의 기준선).");

    String drifted = service.replace("@Service\n", "@Service\n// 주석에 단가 예시: $9/$99 per 1M\n");
    assertNotEquals(service, drifted, "변조가 실제로 적용되지 않았다 — 끼워넣기 기준점(@Service)을 못 찾았다.");

    List<String> violations = checkPricingSingleSource(Map.of(SERVICE_SRC, drifted));
    System.out.println("[pricing-scan 드리프트 블록밖] 위반 " + violations.size() + "건 " + violations);
    assertEquals(1, violations.size(), "끼운 1행이 위반으로 잡혀야 한다. violations=" + violations);
    assertTrue(violations.get(0).contains("허용 블록 밖"),
        "'허용 블록 밖'으로 분류돼야 한다. violations=" + violations);
  }

  /**
   * 드리프트 모의(작업내용 5) — 허용 블록 <b>안</b>에 4번째 항목을 끼운 메모리 사본이
   * "블록 안 3건 아님"으로 잡힌다. 허용 블록이 "무엇이든 넣어도 되는 구멍"이 되지 않게 한다.
   */
  @Test
  void A_드리프트모의_허용블록_안에_4번째_항목을_끼우면_3건_아님으로_잡힌다() {
    String service = walkMainSources().texts().get(SERVICE_SRC);
    Block block = extractLegacySeedBlock(service);
    assertNotNull(block, "블록 전제");

    String mutatedBlock = block.text().replace(
        " per 1M)\");",
        " per 1M)\",\n      \"claude-sonnet-9-9\", \"Claude Nine (테스트 · $9/$99 per 1M)\");");
    assertNotEquals(block.text(), mutatedBlock, "변조가 적용되지 않았다 — 블록 종결부 모양이 바뀌었는지 확인하라.");
    String drifted = service.replace(block.text(), mutatedBlock);

    List<String> violations = checkPricingSingleSource(Map.of(SERVICE_SRC, drifted));
    System.out.println("[pricing-scan 드리프트 블록안] 위반 " + violations.size() + "건 " + violations);
    assertEquals(1, violations.size(), "'블록 안 3건 아님' 1건이 잡혀야 한다. violations=" + violations);
    assertTrue(violations.get(0).contains("4건"),
        "블록 안이 4건임이 메시지에 나와야 한다. violations=" + violations);
  }

  // ════════════════════════════════════════════════════════════════════════════
  // TASK-006 (B) — dashboard.js 구조 (RG-4·RG-5 정적 보강)
  // ════════════════════════════════════════════════════════════════════════════

  @Test
  void B_현재_dashboard_js는_pricing으로_문구를_조립하고_폴백에_단가가_없다() {
    String js = walkMainSources().texts().get(DASHBOARD_JS_SRC);
    List<String> violations = checkDashboardJsStructure(js);
    assertEquals(List.of(), violations, "dashboard.js 구조 단언 위반. violations=" + violations);
  }

  /** 양성 대조군(작업내용 4) — 같은 (B) 검사 함수를 고정본에 적용하면 위반이 잡힌다. */
  @Test
  void B_대조군_고정본_dashboard_js는_폴백_단가와_조립함수_부재로_위반된다() throws Exception {
    List<Hit> hits = scanFixture(DASHBOARD_JS_FIXTURE, DASHBOARD_JS_FIXTURE_BLOB_SHA1);
    assertEquals(3, hits.size(), "고정본 검출 3건 전제. hits=" + hits);

    List<String> violations = checkDashboardJsStructure(fixtureText(DASHBOARD_JS_FIXTURE));
    System.out.println("[pricing-scan 대조군 js] 위반 " + violations.size() + "건 " + violations);
    assertTrue(violations.stream().anyMatch(s -> s.contains("FALLBACK_MODEL_OPTIONS 블록에 $")),
        "고정본 폴백 목록에는 단가가 있으므로 $ 위반이 잡혀야 한다. violations=" + violations);
    assertTrue(violations.stream().anyMatch(s -> s.contains("formatModelOptionLabel 함수를 찾지 못했다")),
        "고정본에는 조립 함수가 없으므로 부재 위반이 잡혀야 한다. violations=" + violations);
  }

  // ════════════════════════════════════════════════════════════════════════════
  // TASK-006 (C) — 관리자 화면 안내 (TASK-005 산출물 고정)
  // ════════════════════════════════════════════════════════════════════════════

  @Test
  void C_현재_관리자화면은_placeholder에_단가가_없고_모달에_도움말이_있다() {
    String html = walkMainSources().texts().get(ADMIN_HTML_SRC);
    List<String> violations = checkAdminGuidance(html);
    assertEquals(List.of(), violations, "관리자 화면 단언 위반. violations=" + violations);
  }

  /** 양성 대조군(작업내용 4) — 같은 (C) 검사 함수를 고정본에 적용하면 위반이 잡힌다. */
  @Test
  void C_대조군_고정본_admin_dashboard_html은_placeholder_단가와_도움말_부재로_위반된다() throws Exception {
    List<Hit> hits = scanFixture(ADMIN_HTML_FIXTURE, ADMIN_HTML_FIXTURE_BLOB_SHA1);
    assertEquals(1, hits.size(), "고정본 검출 1건 전제. hits=" + hits);

    List<String> violations = checkAdminGuidance(fixtureText(ADMIN_HTML_FIXTURE));
    System.out.println("[pricing-scan 대조군 admin] 위반 " + violations.size() + "건 " + violations);
    assertEquals(2, violations.size(),
        "placeholder $ 1건 + 도움말 부재 1건 = 2건이 잡혀야 한다. violations=" + violations);
    assertTrue(violations.stream().anyMatch(s -> s.contains("placeholder")),
        "placeholder 위반이 잡혀야 한다. violations=" + violations);
    assertTrue(violations.stream().anyMatch(s -> s.contains(ADMIN_GUIDANCE_KEYWORD)),
        "도움말 핵심어 부재 위반이 잡혀야 한다. violations=" + violations);
  }

  /**
   * DoD (h-1) — 주석 제거가 실제로 판정을 갈랐음을 <b>관측값</b>으로 남긴다(G-05: 코드 인용이 아니라
   * 실행 결과로 확인한다). 고정본·현재 각각의 블록 행 범위와 주석 제거 전·후 핵심어 출현을 출력한다.
   *
   * <p>여기서 단언하는 것은 "제거 전에는 양쪽 모두 핵심어가 있고, 제거 후에는 현재만 남는다"다 —
   * 즉 <b>주석 제거가 두 판정을 갈라놓은 유일한 요인</b>임을 한 곳에서 보인다.
   */
  @Test
  void C_주석제거가_현재와_고정본의_판정을_갈랐음을_관측값으로_남긴다() throws Exception {
    scanFixture(ADMIN_HTML_FIXTURE, ADMIN_HTML_FIXTURE_BLOB_SHA1); // blob 해시 검증 경로 선통과
    Block fixtureModal = extractHtmlDivBlock(fixtureText(ADMIN_HTML_FIXTURE), MODAL_MARKER);
    Block currentModal =
        extractHtmlDivBlock(walkMainSources().texts().get(ADMIN_HTML_SRC), MODAL_MARKER);

    assertNotNull(fixtureModal, "고정본 모달 블록");
    assertNotNull(currentModal, "현재 모달 블록");

    int fixtureBefore = countOccurrences(fixtureModal.text(), ADMIN_GUIDANCE_KEYWORD);
    int fixtureAfter =
        countOccurrences(stripHtmlCommentsForGuidance(fixtureModal.text()), ADMIN_GUIDANCE_KEYWORD);
    int currentBefore = countOccurrences(currentModal.text(), ADMIN_GUIDANCE_KEYWORD);
    int currentAfter =
        countOccurrences(stripHtmlCommentsForGuidance(currentModal.text()), ADMIN_GUIDANCE_KEYWORD);

    System.out.println("[admin-guidance 고정본] 모달 " + fixtureModal.startLine() + "~"
        + fixtureModal.endLine() + "행 / '" + ADMIN_GUIDANCE_KEYWORD + "' 주석제거 전 " + fixtureBefore
        + "건(행 " + keywordLines(fixtureModal) + ") → 후 " + fixtureAfter + "건");
    System.out.println("[admin-guidance 현재] 모달 " + currentModal.startLine() + "~"
        + currentModal.endLine() + "행 / '" + ADMIN_GUIDANCE_KEYWORD + "' 주석제거 전 " + currentBefore
        + "건(행 " + keywordLines(currentModal) + ") → 후 " + currentAfter + "건");

    assertTrue(fixtureBefore > 0,
        "고정본 블록에도 주석 안 '자동'이 있어야 한다 — 이게 0이면 주석 제거 규칙(R1~R3)이 애초에 필요 없었다는"
            + " 뜻이므로 결정 근거를 다시 봐야 한다.");
    assertEquals(0, fixtureAfter,
        "고정본의 '자동'은 전부 HTML 주석 안이라 제거 후 0건이어야 한다(그래서 '도움말 부재' 위반이 된다).");
    assertTrue(currentAfter > 0,
        "현재 파일은 주석 제거 후에도 도움말 <div>의 '자동'이 남아야 한다 — 남지 않으면 도움말이 주석"
            + " 안에만 있다는 뜻이다.");
    assertTrue(currentBefore > currentAfter,
        "현재 파일도 주석 안에 '자동'이 있으므로 제거로 줄어야 한다 — 줄지 않으면 제거가 동작하지 않은 것이다.");
  }

  // ════════════════════════════════════════════════════════════════════════════
  // TASK-006 작업내용 7 — 옛 시드 원문 결속 (v4 X1)
  // ════════════════════════════════════════════════════════════════════════════

  /**
   * 본 단언 — 현재 {@code LEGACY_SEED_DISPLAY_NAMES}의 (modelKey, 옛 표시명) 3쌍이
   * <b>착수 커밋 고정본의 시드 3행과 완전히 같다</b>.
   *
   * <p>왜 필요한가: 게이트1 D5는 "옛 시드 원문과 글자 하나까지 같은 행만 교체"다. 맵의 값이 1글자라도
   * 다르면 운영 DB의 옛 행은 <b>조용히 정리되지 않는다</b>(교체 0건 로그만 남고 오류는 없다).
   * 맵의 modelKey가 틀리면 {@code migrateLegacySeedDisplayNames()}가 맵 조회 {@code null}에서 조용히
   * 건너뛰므로 역시 숨는다. 기존 테스트의 원문 리터럴은 <b>사람이 옮겨 적은 것</b>이라 맵과 같은 오타를
   * 공유하면 통과한다 — <b>고정본은 사람이 고칠 수 없으므로</b> 그쪽에 묶는다.
   */
  @Test
  void 결속_LEGACY_SEED_DISPLAY_NAMES_3쌍이_착수커밋_시드_원문과_같다() throws Exception {
    Map<String, String> expected = expectedLegacyPairsFromFixture();
    Map<String, String> actual = actualLegacyPairsFromCurrentSource();

    expected.forEach((k, v) -> System.out.println("[legacy-binding 기대(고정본)] " + k + " => " + v));
    actual.forEach((k, v) -> System.out.println("[legacy-binding 실제(현재소스)] " + k + " => " + v));

    assertEquals(expected, actual,
        "옛 시드 원문이 착수 커밋과 어긋났다. 맵 값이 1글자라도 다르면 운영 DB의 옛 행이 조용히 정리되지 않는다"
            + "(D5는 equals 완전 일치만 교체한다). 유니코드 이스케이프(\\u00b7 등)로 바꾼 경우에도 어긋난다 —"
            + " 이스케이프 금지, 원문 문자 그대로 둘 것. 기대=" + expected + " 실제=" + actual);
  }

  /** 양성 대조군 ⓒ-1 — 블록 안 <b>값</b> 1글자 변조(`고품질` → `고 품질`)가 불일치로 잡힌다. */
  @Test
  void 결속_대조군_값_1글자_변조는_불일치로_잡힌다() throws Exception {
    Map<String, String> expected = expectedLegacyPairsFromFixture();
    Map<String, String> mutated = pairsFromServiceText(mutateInsideBlock("고품질", "고 품질"));

    assertEquals(3, mutated.size(), "변조 후에도 쌍은 3개여야 한다(값만 바뀜). mutated=" + mutated);
    assertNotEquals(expected, mutated, "값 1글자 변조가 검출되지 않았다 — 결속 단언이 무력하다.");
    assertNotEquals(expected.get(OPUS_KEY), mutated.get(OPUS_KEY),
        "어긋난 modelKey는 " + OPUS_KEY + " 여야 한다.");
    System.out.println("[legacy-binding 대조군 ⓒ-1] " + OPUS_KEY
        + " 기대=<" + expected.get(OPUS_KEY) + "> 변조=<" + mutated.get(OPUS_KEY) + ">");
  }

  /**
   * 양성 대조군 ⓒ-2 — 블록 안 <b>modelKey</b> 1글자 변조(`claude-opus-4-8` → `claude-opus-4-7`)가
   * 불일치로 잡힌다. 이것이 {@code migrateLegacySeedDisplayNames()}의 "맵 조회 null이면 조용히 건너뜀"
   * 경로를 막는 대조군이다.
   */
  @Test
  void 결속_대조군_modelKey_1글자_변조는_불일치로_잡힌다() throws Exception {
    Map<String, String> expected = expectedLegacyPairsFromFixture();
    Map<String, String> mutated = pairsFromServiceText(mutateInsideBlock(OPUS_KEY, OPUS_KEY_TYPO));

    assertEquals(3, mutated.size(), "변조 후에도 쌍은 3개여야 한다(키만 바뀜). mutated=" + mutated);
    assertNotEquals(expected, mutated, "modelKey 1글자 변조가 검출되지 않았다 — 조용히 건너뜀 경로가 열린다.");
    assertFalse(mutated.containsKey(OPUS_KEY), "기대 쪽 키가 변조본에서 누락돼야 한다: " + OPUS_KEY);
    assertTrue(mutated.containsKey(OPUS_KEY_TYPO), "변조본에는 미지 키가 생겨야 한다: " + OPUS_KEY_TYPO);
    System.out.println("[legacy-binding 대조군 ⓒ-2] 기대 키 누락=" + OPUS_KEY
        + " / 변조본 미지 키=" + OPUS_KEY_TYPO);
  }

  /** 양쪽이 "정확히 3쌍"인지 별도로 고정한다 — 0쌍 vs 0쌍의 거짓 일치를 막는다. */
  @Test
  void 결속_기대와_실제_양쪽이_정확히_3쌍이다() throws Exception {
    assertEquals(3, expectedLegacyPairsFromFixture().size(),
        "고정본 시드 쌍이 3개여야 한다 — 0개면 빈 맵끼리 비교해 거짓으로 통과한다.");
    assertEquals(3, actualLegacyPairsFromCurrentSource().size(),
        "현재 블록 쌍이 3개여야 한다 — 0개면 빈 맵끼리 비교해 거짓으로 통과한다.");
  }

  // ════════════════════════════════════════════════════════════════════════════
  // TASK-006 검사 함수 — (A)(B)(C)가 고정본·메모리 사본에도 그대로 적용된다
  // ════════════════════════════════════════════════════════════════════════════

  private static final String PRICING_SRC = "src/main/java/com/legacy/analysis/llm/AnthropicModelPricing.java";
  private static final String SERVICE_SRC = "src/main/java/com/legacy/analysis/llm/LlmModelOptionService.java";
  private static final String DASHBOARD_JS_SRC = "src/main/resources/static/js/dashboard.js";
  private static final String ADMIN_HTML_SRC = "src/main/resources/templates/admin/dashboard.html";

  /** (A) 스캔 범위 — 이 3디렉터리뿐이다(G-14 v2: {@code src/test}는 대상이 아니다). */
  private static final List<String> SCAN_ROOTS =
      List.of("src/main/java", "src/main/resources/static", "src/main/resources/templates");

  /** 허용 ① — 단가 정본. 파일 전체가 허용이다(Javadoc 설명용 표기 포함). */
  private static final String ALLOWED_FILE = "AnthropicModelPricing.java";
  /** 허용 ② — 이 파일은 아래 블록 안 3건만 허용한다. */
  private static final String BLOCK_HOLDER_FILE = "LlmModelOptionService.java";
  private static final String LEGACY_MAP_NAME = "LEGACY_SEED_DISPLAY_NAMES";

  /** TASK-005 도움말의 핵심어. 이 단어로 존재를 단언한다(work-order TASK-005 작업내용 1). */
  private static final String ADMIN_GUIDANCE_KEYWORD = "자동";

  /** 관리자 모달을 <b>이름으로</b> 찾는 표식. */
  private static final String MODAL_MARKER = "id=\"llmModelModal\"";

  /** R2 — 여는 {@code <!--}부터 가장 가까운 {@code -->}까지(비탐욕, 여러 줄 걸침 허용). */
  private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

  private static final String OPUS_KEY = "claude-opus-4-8";
  private static final String OPUS_KEY_TYPO = "claude-opus-4-7";

  /** {@code LEGACY_SEED_DISPLAY_NAMES} 선언을 <b>이름으로</b> 찾는 패턴(행번호로 찾지 않는다). */
  private static final Pattern LEGACY_MAP_DECL =
      Pattern.compile("\\bstatic\\b[^=;]*\\b" + LEGACY_MAP_NAME + "\\b\\s*=");

  /** 순회 결과 — 상대경로 목록 / 경로→텍스트 / 디코드 불가 목록. */
  record MainScan(List<String> relPaths, Map<String, String> texts, List<String> undecodable) {
  }

  /** 선언 블록 1개 — 원문과 1부터 시작하는 행 범위. */
  record Block(String text, int startLine, int endLine) {
  }

  /**
   * {@code src/main} 3디렉터리를 <b>파일시스템 기준</b>으로 순회한다(§0.2 C7).
   *
   * <p>{@code git ls-files}·{@code git grep} 같은 git 명령에 의존하지 않는다 — QA는 {@code git archive}
   * 사본에서 검증하고 그 사본에는 {@code .git}이 없다.
   */
  static MainScan walkMainSources() {
    Path root = projectRoot();
    List<String> relPaths = new ArrayList<>();
    Map<String, String> texts = new LinkedHashMap<>();
    List<String> undecodable = new ArrayList<>();
    for (String scanRoot : SCAN_ROOTS) {
      Path dir = root.resolve(scanRoot);
      if (!Files.isDirectory(dir)) {
        return fail("스캔 대상 디렉터리가 없다: " + scanRoot + " (프로젝트 루트=" + root + ")");
      }
      List<Path> files;
      try (Stream<Path> walk = Files.walk(dir)) {
        files = walk.filter(Files::isRegularFile).sorted().toList();
      } catch (IOException e) {
        return fail("스캔 순회 실패: " + scanRoot + " (" + e + ")");
      }
      for (Path file : files) {
        String relPath = root.relativize(file).toString().replace('\\', '/');
        String text = decodeUtf8StrictOrNull(file);
        if (text == null) {
          undecodable.add(relPath);
          continue;
        }
        relPaths.add(relPath);
        texts.put(relPath, text);
      }
    }
    return new MainScan(relPaths, texts, undecodable);
  }

  /**
   * (A) 검사 본체 — 상대경로→텍스트 맵에서 <b>허용 목록 밖</b> 검출을 위반 설명 목록으로 돌려준다.
   * 빈 목록이면 통과다. 실제 {@code src/main}·고정본·메모리 변조본에 <b>같은 함수</b>가 적용된다.
   */
  static List<String> checkPricingSingleSource(Map<String, String> sources) {
    List<String> violations = new ArrayList<>();
    sources.forEach((path, text) -> {
      List<Hit> hits = scanText(text);
      if (path.endsWith(ALLOWED_FILE)) {
        return; // 정본 — 파일 전체 허용
      }
      if (!path.endsWith(BLOCK_HOLDER_FILE)) {
        hits.forEach(h -> violations.add(path + ":" + h.line() + " " + h.content().trim()));
        return;
      }
      Block block = extractLegacySeedBlock(text);
      if (block == null) {
        violations.add(path + ": " + LEGACY_MAP_NAME + " 선언 블록을 찾지 못했다"
            + " — 허용 블록이 없으면 이 파일의 검출 " + hits.size() + "건은 전부 허용 목록 밖이다.");
        hits.forEach(h -> violations.add(path + ":" + h.line() + " (블록 부재) " + h.content().trim()));
        return;
      }
      List<Hit> inside = hitsInside(hits, block);
      if (inside.size() != 3) {
        violations.add(path + ": " + LEGACY_MAP_NAME + " 블록(" + block.startLine() + "~"
            + block.endLine() + "행) 안 검출이 3건이어야 하는데 " + inside.size() + "건이다. inside=" + inside);
      }
      hitsOutside(hits, block)
          .forEach(h -> violations.add(path + ":" + h.line() + " (허용 블록 밖) " + h.content().trim()));
    });
    return violations;
  }

  /** (B) 검사 본체 — {@code dashboard.js}가 API {@code pricing}으로 문구를 조립하는 구조인가. */
  static List<String> checkDashboardJsStructure(String js) {
    List<String> violations = new ArrayList<>();

    String fallback = extractJsConstBlock(js, "FALLBACK_MODEL_OPTIONS");
    if (fallback == null) {
      violations.add("FALLBACK_MODEL_OPTIONS 선언 블록을 찾지 못했다");
    } else if (fallback.contains("$")) {
      violations.add("FALLBACK_MODEL_OPTIONS 블록에 $ 가 있다(D4: 폴백도 단가 미표시) — "
          + fallback.lines().filter(l -> l.contains("$")).toList());
    }

    String format = extractJsFunctionBody(js, "formatModelOptionLabel");
    if (format == null) {
      violations.add("formatModelOptionLabel 함수를 찾지 못했다 — 단가는 API pricing에서 파생돼야 한다");
    } else {
      if (!format.contains("pricing")) {
        violations.add("formatModelOptionLabel 본문이 pricing 을 참조하지 않는다");
      }
      if (!format.contains("label")) {
        violations.add("formatModelOptionLabel 본문이 label 을 참조하지 않는다");
      }
      List<Hit> literals = scanText(format);
      if (!literals.isEmpty()) {
        violations.add("formatModelOptionLabel 본문에 단가 리터럴이 있다(정본 밖 복제): " + literals);
      }
    }

    String populate = extractJsFunctionBody(js, "populateModelSelectOptions");
    if (populate == null) {
      violations.add("populateModelSelectOptions 함수를 찾지 못했다");
    } else {
      if (!populate.contains("formatModelOptionLabel(")) {
        violations.add("populateModelSelectOptions 본문에 formatModelOptionLabel( 호출이 없다");
      }
      if (!populate.contains(".textContent =")) {
        violations.add("populateModelSelectOptions 본문에 .textContent = 대입이 없다(RG-4: XSS 방어)");
      }
      for (String rhs : innerHtmlAssignments(populate)) {
        if (!rhs.equals("''") && !rhs.equals("\"\"")) {
          violations.add("populateModelSelectOptions 본문이 innerHTML 에 '' 외 값을 대입한다(RG-4): " + rhs);
        }
      }
    }
    return violations;
  }

  /**
   * (C) 검사 본체 — 관리자 표시명 입력란과 모달 안내. 현재 파일과 고정본에 <b>같은 함수</b>를 쓴다.
   *
   * <h3>'자동' 검사는 HTML 주석을 제거한 뒤 한다 (work-order v5 작업내용 3 R1~R3, §0.2 C8)</h3>
   * <p>착수 커밋 고정본의 모달에도 Ollama 자동완성을 설명하는 <b>HTML 주석</b>에 "자동"이 들어 있어,
   * 주석을 포함해 세면 고정본도 통과해 작업내용 4의 "도움말 부재 위반" 대조군이 성립하지 않는다.
   * 의미상으로도 D6·REQ-003이 요구한 것은 <b>관리자가 화면에서 읽는 안내</b>이므로 렌더링되지 않는
   * 주석은 "도움말이 있다"의 근거가 될 수 없다. 덤으로 주석에만 핵심어를 적고 실제 문구를 빼먹는
   * 회귀도 막는다.
   *
   * <p>§0.2 C8의 원칙은 <b>"부재(0건) 단언은 주석 포함 전체 텍스트 / 사람이 화면에서 읽는 안내의 존재
   * 단언은 렌더링되는 텍스트만"</b>이다. 그래서 같은 클래스 안에서도 취급이 갈린다 — (A)와 아래
   * placeholder {@code $} 검사는 <b>부재</b> 단언이라 주석을 포함해 원문 그대로 보고, 이 '자동' 검사만
   * <b>존재</b> 단언이라 주석을 뺀다. 주석 제거는 이 검사에만 쓰고 다른 단언에 재사용하지 않는다.
   */
  static List<String> checkAdminGuidance(String html) {
    List<String> violations = new ArrayList<>();

    // placeholder $ 0건 — 부재 단언이므로 원문 속성값 그대로 본다(주석 제거와 무관).
    String inputLine = firstLineContaining(html, "id=\"llmModelDisplayName\"");
    if (inputLine == null) {
      violations.add("#llmModelDisplayName 입력란을 찾지 못했다");
    } else if (inputLine.contains("$")) {
      violations.add("#llmModelDisplayName placeholder 에 $ 가 있다(네 번째 단가 사본): " + inputLine.trim());
    }

    // R1 ① 블록 추출(이름으로 찾는다, 행번호로 찾지 않는다)
    Block modal = extractHtmlDivBlock(html, MODAL_MARKER);
    if (modal == null) {
      violations.add("#llmModelModal 블록을 찾지 못했다");
      return violations;
    }
    // R3 — Thymeleaf 프로토타입 전용 주석은 실제로 렌더링되므로 R2로 지우면 안 된다. 만나면 멈춘다.
    if (modal.text().contains("<!--/*/")) {
      violations.add("#llmModelModal 블록에 Thymeleaf 프로토타입 전용 주석 <!--/*/ 이 있다"
          + " — 이 형태는 렌더링되므로 R2의 주석 제거로 지우면 안 된다. 지원하지 않는 형태이니 PL에 보고할 것.");
      return violations;
    }
    // R1 ② 주석 제거 (R2: 비탐욕 + DOTALL, 공백 1칸 치환)
    String rendered = stripHtmlCommentsForGuidance(modal.text());
    // R3 — 제거가 실패했는데 "자동 없음"으로 조용히 통과하는 경로를 막는다.
    if (rendered.contains("<!--")) {
      violations.add("#llmModelModal 블록에서 HTML 주석 제거 후에도 <!-- 가 남아 있다(닫히지 않은 주석)"
          + " — 제거 실패를 '도움말 없음'으로 오판할 수 있으므로 RED로 둔다.");
      return violations;
    }
    // R1 ③ 결과 텍스트에서 핵심어 검색
    if (!rendered.contains(ADMIN_GUIDANCE_KEYWORD)) {
      violations.add("#llmModelModal 블록의 렌더링 텍스트에 도움말 핵심어 \""
          + ADMIN_GUIDANCE_KEYWORD + "\" 가 없다(HTML 주석은 화면에 보이지 않으므로 안내로 인정하지 않는다)");
    }
    return violations;
  }

  // ── 결속 단언용 — 기대/실제 쌍 추출(추출기는 각각 한 벌뿐이다) ──────────────

  /**
   * 기대값 — 착수 커밋 고정본에서 뽑는다. <b>기존 blob 해시 검증 경로 그대로</b>
   * ({@link #scanFixture}) 읽고, 그 검출 3행(= 시드 {@code create(...)} 3행)에서 쌍을 뽑는다.
   */
  private static Map<String, String> expectedLegacyPairsFromFixture() throws Exception {
    List<Hit> hits = scanFixture(SERVICE_FIXTURE, SERVICE_FIXTURE_BLOB_SHA1);
    Map<String, String> pairs = extractSeedPairs(trimmedContents(hits));
    assertEquals(3, pairs.size(),
        "고정본 시드 3행에서 (modelKey, 옛 표시명) 쌍 3개가 나와야 한다. hits=" + hits + " pairs=" + pairs);
    return pairs;
  }

  /** 실제값 — 현재 소스의 허용 블록에서 뽑는다((A)와 <b>같은 블록 추출 함수</b>를 쓴다). */
  private static Map<String, String> actualLegacyPairsFromCurrentSource() {
    return pairsFromServiceText(walkMainSources().texts().get(SERVICE_SRC));
  }

  private static Map<String, String> pairsFromServiceText(String serviceSource) {
    Block block = extractLegacySeedBlock(serviceSource);
    assertNotNull(block, LEGACY_MAP_NAME + " 선언 블록을 찾지 못했다 — 결속 단언의 실제값을 뽑을 수 없다.");
    Map<String, String> pairs = extractSeedPairs(List.of(block.text().split("\n", -1)));
    assertEquals(3, pairs.size(),
        LEGACY_MAP_NAME + " 블록에서 쌍 3개가 나와야 한다. block=" + block.text() + " pairs=" + pairs);
    return pairs;
  }

  /** 현재 서비스 텍스트의 허용 블록 <b>안에서만</b> 치환한 메모리 사본(워킹트리 무수정). */
  private static String mutateInsideBlock(String from, String to) {
    String service = walkMainSources().texts().get(SERVICE_SRC);
    Block block = extractLegacySeedBlock(service);
    assertNotNull(block, "변조 전제: 블록이 있어야 한다");
    assertTrue(block.text().contains(from), "변조 대상 문자열이 블록 안에 없다: <" + from + ">");
    return service.replace(block.text(), block.text().replace(from, to));
  }

  /**
   * 각 행의 <b>첫 두 문자열 리터럴</b>을 (modelKey, 표시명) 쌍으로 모은다.
   *
   * <p>고정본의 {@code create("k", "v", LlmProvider.ANTHROPIC, n);} 3행과 현재 블록의
   * {@code "k", "v",} 3행이 <b>같은 모양</b>(그 행의 첫 두 리터럴)이라 추출기를 한 벌로 공유한다.
   * 리터럴은 <b>원문 그대로</b>(이스케이프 해석 없이) 담는다 — 누가 {@code \u00b7} 식으로 바꾸면
   * 런타임 값이 같아도 결속 단언이 RED가 되며, 그것이 의도된 제약이다.
   */
  static Map<String, String> extractSeedPairs(List<String> lines) {
    Map<String, String> pairs = new LinkedHashMap<>();
    for (String line : lines) {
      List<String> literals = stringLiterals(line);
      if (literals.size() >= 2) {
        pairs.put(literals.get(0), literals.get(1));
      }
    }
    return pairs;
  }

  /**
   * {@code LEGACY_SEED_DISPLAY_NAMES} 선언 블록을 <b>이름으로</b> 찾아 선언 행 ~ 종결 {@code ;} 까지
   * 돌려준다(행번호로 찾지 않는다). 못 찾으면 {@code null}.
   *
   * <p>(A)의 허용 목록 판정과 작업내용 7의 결속 단언이 <b>이 함수 하나</b>를 공유한다 —
   * 같은 블록을 찾는 추출기를 두 벌 만들지 않는다.
   */
  static Block extractLegacySeedBlock(String javaSource) {
    String[] lines = javaSource.split("\n", -1);
    List<Integer> declLines = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      if (LEGACY_MAP_DECL.matcher(lines[i]).find()) {
        declLines.add(i);
      }
    }
    if (declLines.isEmpty()) {
      return null;
    }
    if (declLines.size() > 1) {
      return fail(LEGACY_MAP_NAME + " 선언 후보가 " + declLines.size() + "개다(1개여야 한다): "
          + declLines.stream().map(i -> i + 1).toList());
    }
    int start = declLines.get(0);
    for (int i = start; i < lines.length; i++) {
      if (stripStringLiterals(lines[i]).indexOf(';') >= 0) {
        return new Block(String.join("\n", List.of(lines).subList(start, i + 1)), start + 1, i + 1);
      }
    }
    return null;
  }

  // ── 텍스트 유틸 ────────────────────────────────────────────────────────────

  /**
   * 한 행의 이중인용 문자열 리터럴들을 <b>원문 그대로</b> 돌려준다(이스케이프를 해석하지 않는다).
   * 닫히지 않은 리터럴을 만나면 그 지점까지만 돌려준다.
   */
  static List<String> stringLiterals(String line) {
    List<String> out = new ArrayList<>();
    int i = 0;
    while (i < line.length()) {
      if (line.charAt(i) != '"') {
        i++;
        continue;
      }
      StringBuilder sb = new StringBuilder();
      int j = i + 1;
      boolean closed = false;
      while (j < line.length()) {
        char c = line.charAt(j);
        if (c == '\\' && j + 1 < line.length()) {
          sb.append(c).append(line.charAt(j + 1));
          j += 2;
          continue;
        }
        if (c == '"') {
          closed = true;
          break;
        }
        sb.append(c);
        j++;
      }
      if (!closed) {
        return out;
      }
      out.add(sb.toString());
      i = j + 1;
    }
    return out;
  }

  /** 구분자 판정용 — 이중인용 문자열 리터럴 내용을 지운 행을 돌려준다. */
  private static String stripStringLiterals(String line) {
    StringBuilder out = new StringBuilder();
    int i = 0;
    boolean inside = false;
    while (i < line.length()) {
      char c = line.charAt(i);
      if (inside) {
        if (c == '\\' && i + 1 < line.length()) {
          i += 2;
          continue;
        }
        if (c == '"') {
          inside = false;
        }
        i++;
        continue;
      }
      if (c == '"') {
        inside = true;
        i++;
        continue;
      }
      out.append(c);
      i++;
    }
    return out.toString();
  }

  /** JS {@code const NAME = ...;} 선언 블록을 이름으로 찾아 종결 {@code ;} 까지 돌려준다. */
  static String extractJsConstBlock(String js, String name) {
    String[] lines = js.split("\n", -1);
    Pattern decl = Pattern.compile("^\\s*(?:const|let|var)\\s+" + Pattern.quote(name) + "\\s*=");
    for (int i = 0; i < lines.length; i++) {
      if (!decl.matcher(lines[i]).find()) {
        continue;
      }
      for (int j = i; j < lines.length; j++) {
        if (stripStringLiterals(lines[j]).indexOf(';') >= 0) {
          return String.join("\n", List.of(lines).subList(i, j + 1));
        }
      }
      return null;
    }
    return null;
  }

  /** JS {@code function name(...) { ... }} 본문을 이름으로 찾아 중괄호 균형까지 돌려준다. */
  static String extractJsFunctionBody(String js, String name) {
    String[] lines = js.split("\n", -1);
    Pattern decl = Pattern.compile("^\\s*(?:async\\s+)?function\\s+" + Pattern.quote(name) + "\\s*\\(");
    for (int i = 0; i < lines.length; i++) {
      if (!decl.matcher(lines[i]).find()) {
        continue;
      }
      int depth = 0;
      boolean opened = false;
      for (int j = i; j < lines.length; j++) {
        for (char c : lines[j].toCharArray()) {
          if (c == '{') {
            depth++;
            opened = true;
          } else if (c == '}') {
            depth--;
          }
        }
        if (opened && depth == 0) {
          return String.join("\n", List.of(lines).subList(i, j + 1));
        }
      }
      return null;
    }
    return null;
  }

  /** 본문 안 {@code innerHTML = <rhs>} 의 rhs 들을 돌려준다(RG-4 판정용). */
  private static List<String> innerHtmlAssignments(String body) {
    List<String> out = new ArrayList<>();
    Matcher m = Pattern.compile("innerHTML\\s*=\\s*([^;\\n]*)").matcher(body);
    while (m.find()) {
      out.add(m.group(1).trim());
    }
    return out;
  }

  /**
   * {@code <div ...marker...>} 부터 짝 맞는 {@code </div>} 까지를 행 범위와 함께 돌려준다.
   * <b>이름(marker)으로</b> 찾는다 — 행번호로 찾지 않는다. 못 찾으면 {@code null}.
   */
  static Block extractHtmlDivBlock(String html, String marker) {
    String[] lines = html.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (!lines[i].contains(marker) || !lines[i].contains("<div")) {
        continue;
      }
      int depth = 0;
      for (int j = i; j < lines.length; j++) {
        depth += countOccurrences(lines[j], "<div");
        depth -= countOccurrences(lines[j], "</div>");
        if (depth == 0) {
          return new Block(String.join("\n", List.of(lines).subList(i, j + 1)), i + 1, j + 1);
        }
      }
      return null;
    }
    return null;
  }

  /**
   * R2 — HTML 주석을 <b>공백 1칸</b>으로 치환한다. 여는 {@code <!--}부터 <b>가장 가까운</b> 닫는
   * {@code -->}까지를 한 덩어리로 보고(비탐욕, 여러 줄 걸침 허용) 지운다.
   *
   * <p>빈 문자열이 아니라 공백 1칸인 이유: 주석 앞뒤 글자가 붙어 <b>없던 낱말이 생기는</b> 것을 막는다
   * (예: {@code 자<!--x-->동} 을 빈 문자열로 지우면 "자동"이 만들어져 거짓 통과가 된다).
   * 이 함수는 (C)의 '자동' 검사에만 쓴다(§0.2 C8 적용 범위).
   */
  private static String stripHtmlCommentsForGuidance(String blockText) {
    return HTML_COMMENT.matcher(blockText).replaceAll(" ");
  }

  /** 블록 안에서 핵심어가 나오는 행번호(파일 기준)를 돌려준다 — DoD (h-1) 관측용. */
  private static List<Integer> keywordLines(Block block) {
    List<Integer> out = new ArrayList<>();
    String[] lines = block.text().split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].contains(ADMIN_GUIDANCE_KEYWORD)) {
        out.add(block.startLine() + i);
      }
    }
    return out;
  }

  private static int countOccurrences(String text, String needle) {
    int count = 0;
    int from = 0;
    while (true) {
      int at = text.indexOf(needle, from);
      if (at < 0) {
        return count;
      }
      count++;
      from = at + needle.length();
    }
  }

  private static String firstLineContaining(String text, String needle) {
    for (String line : text.split("\n", -1)) {
      if (line.contains(needle)) {
        return line;
      }
    }
    return null;
  }

  private static List<Hit> hitsInside(List<Hit> hits, Block block) {
    return hits.stream().filter(h -> h.line() >= block.startLine() && h.line() <= block.endLine()).toList();
  }

  private static List<Hit> hitsOutside(List<Hit> hits, Block block) {
    return hits.stream().filter(h -> h.line() < block.startLine() || h.line() > block.endLine()).toList();
  }

  /**
   * 프로젝트 루트를 찾는다 — {@code src/main/java}와 {@code src/main/resources}를 함께 가진 조상
   * 디렉터리. git 명령에 의존하지 않으므로 {@code git archive} 사본에서도 동작한다.
   */
  private static Path projectRoot() {
    Path cursor = Path.of("").toAbsolutePath();
    while (cursor != null) {
      if (Files.isDirectory(cursor.resolve("src/main/java"))
          && Files.isDirectory(cursor.resolve("src/main/resources"))) {
        return cursor;
      }
      cursor = cursor.getParent();
    }
    return fail("프로젝트 루트를 찾지 못했다(src/main/java 와 src/main/resources 를 함께 가진 디렉터리)."
        + " 현재 작업 디렉터리=" + Path.of("").toAbsolutePath());
  }

  /** UTF-8 <b>엄격</b> 디코드. 실패하면 {@code null} — 호출부가 목록에 모아 RED로 만든다. */
  private static String decodeUtf8StrictOrNull(Path file) {
    try {
      CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT);
      return decoder.decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
    } catch (IOException e) {
      return null;
    }
  }

  /** 고정본 원문 텍스트(해시 검증은 호출부가 {@link #scanFixture}로 먼저 통과시킨다). */
  private static String fixtureText(String resource) throws IOException {
    return new String(readFixtureBytes(resource), StandardCharsets.UTF_8);
  }

  // ────────────────────────────────────────────────────────────────────────────

  /** 고정본을 클래스패스에서 읽고, git blob 해시로 바이트 동일성을 확인한 뒤 스캔한다. */
  private static List<Hit> scanFixture(String resource, String expectedBlobSha1) throws Exception {
    byte[] bytes = readFixtureBytes(resource);
    String actual = gitBlobSha1(bytes);
    assertEquals(expectedBlobSha1, actual,
        "고정본이 `git show 318e086:<원본>`과 바이트 단위로 같아야 한다. 어긋났다면 고정본이 편집된 것이므로"
            + " 대조군으로 쓸 수 없다. resource=" + resource);
    return scanText(new String(bytes, StandardCharsets.UTF_8));
  }

  private static byte[] readFixtureBytes(String resource) throws IOException {
    try (InputStream in = PricingSingleSourceContractTest.class.getResourceAsStream(resource)) {
      if (in == null) {
        return fail("착수 커밋 고정본이 클래스패스에 없다: " + resource
            + " — 이 파일이 없으면 스캐너의 탐지력을 증명할 수 없다.");
      }
      return in.readAllBytes();
    }
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

  private static List<Integer> lineNumbers(List<Hit> hits) {
    return hits.stream().map(Hit::line).toList();
  }

  private static List<String> trimmedContents(List<Hit> hits) {
    return hits.stream().map(h -> h.content().trim()).toList();
  }
}
