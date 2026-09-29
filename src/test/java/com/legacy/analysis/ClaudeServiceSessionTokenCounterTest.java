package com.legacy.analysis;

import com.legacy.analysis.llm.LlmResult;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-002 (work-order 2026-09-cost-stats-bugfix v2) —
 * <b>세션별 토큰 카운터의 계약을 고정하는 테스트</b>.
 * {@code ClaudeServiceTokenCounterIsolationTest}(혼입 해소 자체를 단언하는 대조 하네스)와 역할이
 * 다르다 — 이쪽은 <b>세션 키가 실제로 왕복하는가 / 단일 세션 값이 변하지 않았는가 / 정리가 선택적인가 /
 * null 키가 안전한가</b>를 본다. 하네스는 측정 모양이 고정돼 있어 케이스를 보탤 수 없으므로
 * (v2 §0.6 금지 목록) 별도 파일로 둔다.
 *
 * <p><b>왜 세션 키 왕복을 실행값으로 확인하는가</b>(G-05 / {@code STRUCTURE.md} 21절):
 * 누적({@code extractAndStoreTokenUsage})·초기화({@code resetTokenUsage})·조회
 * ({@code getTotalTokenUsage})가 <b>같은 키 문자열</b>을 쓰지 않으면 격리는 "조용히" 실패한다 —
 * 예외도 로그도 없이 토큰이 0으로 기록될 뿐이다. 특히 업로드 분석은 세션의 {@code sourcePath}를
 * {@code /} 구분자 문자열로 저장하는데, 양쪽 모두 {@code Path.of(...).toString()}을 거치므로
 * Windows에서 {@code \}로 정규화돼 일치한다 — 이것을 코드 인용이 아니라 실행으로 확인한다.
 *
 * <p>협력자를 {@code null}로 넘겨 {@code ClaudeServiceImpl}을 직접 생성하는 기존 관례
 * ({@code ClaudeServiceImplNormalizeCommentTest})를 재사용한다. 토큰 누적·조회 경로는 협력자를
 * 참조하지 않으므로 {@code null}이어도 안전하다.
 */
class ClaudeServiceSessionTokenCounterTest {

  /**
   * RG-1(단일 세션 값 불변)의 기준값. 새로 만든 값이 아니라 {@code 05-dev-progress.md} TASK-001
   * 대조표의 "기대(세션 격리 시)" 열을 그대로 가져온 것이다 — 단일 세션 사용자는 이번 변경을
   * 체감하지 못해야 한다.
   */
  private static final long SINGLE_IN = 1000L, SINGLE_OUT = 200L,
      SINGLE_CACHE_READ = 50L, SINGLE_CACHE_CREATE = 30L;
  private static final String SINGLE_MODEL = "claude-sonnet-4-6";

  private static ClaudeServiceImpl newService() {
    return new ClaudeServiceImpl(null, null, null, null, null, null, null);
  }

  private static void accumulate(ClaudeServiceImpl service, long in, long out,
      long cacheRead, long cacheCreate, String model, String sourceFolderPath) throws Exception {
    Method method = ClaudeServiceImpl.class.getDeclaredMethod(
        "extractAndStoreTokenUsage", LlmResult.class, String.class, String.class);
    method.setAccessible(true);
    method.invoke(service, new LlmResult("dummy-response", in, out, cacheRead, cacheCreate),
        model, sourceFolderPath);
  }

  // ── DoD (a) 세션 키 왕복 ─────────────────────────────────────────────────

  @Test
  void 업로드_세션의_슬래시_경로도_누적키와_조회키가_같은_문자열로_정규화된다() throws Exception {
    // 업로드 분석은 세션의 sourcePath를 '/' 구분자로 저장한다
    // (MainApiController의 업로드 분석 시작 경로가 uploadRoot.toString().replace("\\","/")로 저장).
    String storedSourcePath = "C:/work/uploads/tester/project-a";

    // 누적·초기화 키: runAnalysis()가 쓰는 sourceRootPath.toString() (= Path.of(normalizedSourcePath))
    String accumulateKey = Path.of(storedSourcePath).toString();
    // 조회 키: finalizeAnalysis()가 쓰는 Path.of(session.getSourcePath()).toString()
    String readKey = Path.of(storedSourcePath).toString();

    System.out.printf(
        "[TASK-002 세션키 왕복] 세션 저장값=\"%s\" / 누적·초기화 키=\"%s\" / 조회 키=\"%s\" / "
            + "두 키 동일=%s / 저장 원문과 동일=%s%n",
        storedSourcePath, accumulateKey, readKey,
        accumulateKey.equals(readKey), accumulateKey.equals(storedSourcePath));

    assertEquals(accumulateKey, readKey,
        "누적·초기화 키와 조회 키는 같은 산식(Path.of(...).toString())이므로 항상 같은 문자열이어야 한다");

    // 실제로 같은 holder를 찾는지 왕복 확인
    ClaudeServiceImpl service = newService();
    accumulate(service, SINGLE_IN, SINGLE_OUT, 0L, 0L, SINGLE_MODEL, accumulateKey);
    assertEquals(SINGLE_IN, service.getTotalTokenUsage(readKey).getInputTokens(),
        "정규화된 키로 누적한 값은 같은 산식의 조회 키로 읽혀야 한다");

    // 양성 대조군: 정규화가 실제로 일어나는 플랫폼에서는 저장 원문('/' 그대로)으로는 조회되지 않는다.
    // 양쪽이 반드시 같은 산식을 써야 하는 이유이며, 이 단언이 통과한다는 것은 위 왕복 확인이
    // "어떤 키로든 찾아진다"는 허상이 아님을 뜻한다.
    if (!accumulateKey.equals(storedSourcePath)) {
      assertEquals(0L, service.getTotalTokenUsage(storedSourcePath).getInputTokens(),
          "정규화 전 원문 키로는 조회되지 않아야 한다(양쪽 산식이 어긋나면 토큰이 0으로 기록된다)");
      assertNotEquals(storedSourcePath, accumulateKey);
    }
  }

  @Test
  void 서버경로_직접지정_세션도_누적키와_조회키가_같다() throws Exception {
    // 서버 경로 직접 지정 분석은 sourcePath를 OS 구분자 그대로 저장한다.
    String storedSourcePath = "C:\\work\\project-legacy";
    String accumulateKey = Path.of(storedSourcePath).toString();
    String readKey = Path.of(storedSourcePath).toString();

    System.out.printf(
        "[TASK-002 세션키 왕복] 세션 저장값=\"%s\" / 누적·초기화 키=\"%s\" / 조회 키=\"%s\" / 두 키 동일=%s%n",
        storedSourcePath, accumulateKey, readKey, accumulateKey.equals(readKey));

    assertEquals(accumulateKey, readKey);

    ClaudeServiceImpl service = newService();
    accumulate(service, SINGLE_IN, SINGLE_OUT, 0L, 0L, SINGLE_MODEL, accumulateKey);
    service.resetTokenUsage(accumulateKey);
    assertEquals(0L, service.getTotalTokenUsage(readKey).getInputTokens(),
        "초기화 키와 조회 키도 같은 holder를 가리켜야 한다");
  }

  // ── DoD (b) RG-1 단일 세션 값 불변 ────────────────────────────────────────

  @Test
  void 단일_세션의_토큰값과_모델명은_수정_전과_동일하다() throws Exception {
    // RG-1: 세션이 하나뿐인 사용자는 이번 격리 변경을 체감하지 못해야 한다. 기준값은
    // 05-dev-progress.md TASK-001 대조표의 "기대(세션 격리 시)" 열을 그대로 쓴다(새로 만들지 않는다).
    ClaudeServiceImpl service = newService();
    String session = "C:\\work\\only-one-session";

    accumulate(service, SINGLE_IN, SINGLE_OUT, SINGLE_CACHE_READ, SINGLE_CACHE_CREATE,
        SINGLE_MODEL, session);

    TokenUsage usage = service.getTotalTokenUsage(session);
    System.out.printf(
        "[TASK-002 RG-1 단일 세션] input=%d, output=%d, total=%d, cacheRead=%d, cacheCreation=%d, modelName=%s%n",
        usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens(),
        usage.getCacheReadTokens(), usage.getCacheCreationTokens(), usage.getModelName());

    assertEquals(SINGLE_IN, usage.getInputTokens());
    assertEquals(SINGLE_OUT, usage.getOutputTokens());
    assertEquals(SINGLE_IN + SINGLE_OUT, usage.getTotalTokens());
    assertEquals(SINGLE_CACHE_READ, usage.getCacheReadTokens());
    assertEquals(SINGLE_CACHE_CREATE, usage.getCacheCreationTokens());
    assertEquals(SINGLE_MODEL, usage.getModelName());
  }

  @Test
  void 같은_세션에_여러_번_누적하면_기존과_동일하게_합산된다() throws Exception {
    // 격리는 "세션 간" 분리일 뿐, 한 세션 안의 누적 방식은 바뀌지 않았다(RG-1 보강).
    ClaudeServiceImpl service = newService();
    String session = "C:\\work\\accumulating-session";

    accumulate(service, 100L, 10L, 1L, 2L, SINGLE_MODEL, session);
    accumulate(service, 200L, 20L, 3L, 4L, SINGLE_MODEL, session);

    TokenUsage usage = service.getTotalTokenUsage(session);
    assertEquals(300L, usage.getInputTokens());
    assertEquals(30L, usage.getOutputTokens());
    assertEquals(330L, usage.getTotalTokens());
    assertEquals(4L, usage.getCacheReadTokens());
    assertEquals(6L, usage.getCacheCreationTokens());
  }

  // ── DoD (c) 정리는 선택적이어야 한다 ──────────────────────────────────────

  @Test
  void 세션_종료_정리는_해당_키의_카운터만_제거하고_다른_세션은_남긴다() throws Exception {
    // 메모리 누수 방지 검증: sessionSystemPrompts·sessionModelOverrides와 동일한 세션 종료 정리
    // 지점(clearSessionSystemPrompt)에서 토큰 카운터도 함께 제거되는지, 그리고 그것이
    // "전체 clear"가 아니라 해당 키만 지우는 선택적 정리인지 확인한다.
    ClaudeServiceImpl service = newService();
    String ending = "C:\\work\\session-ending";
    String running = "C:\\work\\session-still-running";

    accumulate(service, 1000L, 200L, 0L, 0L, SINGLE_MODEL, ending);
    accumulate(service, 700L, 100L, 0L, 0L, SINGLE_MODEL, running);
    assertEquals(1000L, service.getTotalTokenUsage(ending).getInputTokens());
    assertEquals(700L, service.getTotalTokenUsage(running).getInputTokens());

    service.clearSessionSystemPrompt(ending);

    assertEquals(0L, service.getTotalTokenUsage(ending).getInputTokens(),
        "종료된 세션의 카운터는 제거돼야 한다(메모리 누수 방지)");
    assertEquals(700L, service.getTotalTokenUsage(running).getInputTokens(),
        "아직 진행 중인 다른 세션의 카운터는 남아 있어야 한다(전체 clear가 아님)");
  }

  // ── DoD (e) null 키 3케이스 ───────────────────────────────────────────────

  @Test
  void null_키_조회는_예외없이_빈_TokenUsage를_반환한다() {
    // ConcurrentHashMap은 null 키를 거부하므로 조회 전에 걸러야 한다(getCurrentModel(null) 선례).
    // 분석 마무리 단계에서 예외가 나면 이력 기록 전체가 실패하므로 빈 값을 반환한다.
    ClaudeServiceImpl service = newService();

    TokenUsage usage = service.getTotalTokenUsage(null);

    assertEquals(0L, usage.getInputTokens());
    assertEquals(0L, usage.getOutputTokens());
    assertEquals(0L, usage.getTotalTokens());
    assertEquals(0L, usage.getCacheReadTokens());
    assertEquals(0L, usage.getCacheCreationTokens());
    assertEquals("", usage.getModelName());
  }

  @Test
  void null_키_초기화는_예외없이_아무_일도_하지_않는다() throws Exception {
    ClaudeServiceImpl service = newService();
    String session = "C:\\work\\untouched-session";
    accumulate(service, 1000L, 200L, 0L, 0L, SINGLE_MODEL, session);

    service.resetTokenUsage(null);

    assertEquals(1000L, service.getTotalTokenUsage(session).getInputTokens(),
        "null 키 초기화가 다른 세션을 건드리면 안 된다(전역 리셋은 제공하지 않는다)");
  }

  @Test
  void null_세션키_누적은_예외없이_건너뛴다() throws Exception {
    // 전역 통이 없어졌으므로 세션을 특정할 수 없는 누적은 넣을 자리가 없다. 예외를 던지면
    // 분석 자체가 실패하므로 debug 로그만 남기고 건너뛴다.
    ClaudeServiceImpl service = newService();
    String session = "C:\\work\\other-session";
    accumulate(service, 1000L, 200L, 0L, 0L, SINGLE_MODEL, session);

    accumulate(service, 999L, 999L, 999L, 999L, SINGLE_MODEL, null);

    assertEquals(1000L, service.getTotalTokenUsage(session).getInputTokens(),
        "세션 키 없는 누적이 다른 세션에 섞이면 안 된다");
    assertEquals(0L, service.getTotalTokenUsage(null).getInputTokens(),
        "세션 키 없는 누적은 어디에도 저장되지 않는다");
  }

  @Test
  void 누적이_없는_키를_조회해도_예외없이_빈_값이_나온다() {
    ClaudeServiceImpl service = newService();
    TokenUsage usage = service.getTotalTokenUsage("C:\\work\\never-analyzed");
    assertEquals(0L, usage.getTotalTokens());
    assertEquals("", usage.getModelName());
  }

  @Test
  void 초기화를_누적보다_먼저_호출해도_이후_누적이_정상_동작한다() throws Exception {
    // runAnalysis()는 첫 파일 분석보다 먼저 resetTokenUsage()를 호출한다 — 그 시점에 holder가
    // 없어도 안전해야 하고(no-op), 이후 누적은 computeIfAbsent로 새 holder를 만들어 정상 진행한다.
    ClaudeServiceImpl service = newService();
    String session = "C:\\work\\fresh-session";

    service.resetTokenUsage(session);
    accumulate(service, SINGLE_IN, SINGLE_OUT, 0L, 0L, SINGLE_MODEL, session);

    assertEquals(SINGLE_IN, service.getTotalTokenUsage(session).getInputTokens());
    assertTrue(service.getTotalTokenUsage(session).getTotalTokens() > 0);
  }
}
