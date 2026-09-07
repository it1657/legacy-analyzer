package com.legacy.analysis;

import com.legacy.analysis.llm.LlmClient;
import com.legacy.analysis.llm.LlmClientResolver;
import com.legacy.analysis.llm.LlmModelOption;
import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.analysis.llm.LlmProvider;
import com.legacy.analysis.llm.LlmResult;
import com.legacy.core.ApiErrorHandler;
import com.legacy.core.ProjectTypeDetector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REQ-001(2026-09, anthropic-guard-bypass-fixes) 회귀 테스트 — Anthropic API 키 가드가
 * "전역 llm.provider 설정값"(isAnthropicMode)이 아니라 "이 세션이 실제로 라우팅될 provider"
 * (requiresAnthropicApiKey → resolveProvider)를 기준으로 동작하는지 검증한다.
 *
 * 수정 전 버그: llm.provider=local로 뜬 서버라도 세션이 setModel()로 DB상 ANTHROPIC provider인
 * 모델을 선택하면 isAnthropicMode()가 false라 가드를 그냥 통과했고, API 키가 없는 상태에서도
 * Anthropic 클라이언트 호출이 시도되어 사용자 소스코드가 외부로 나갈 수 있었다. 아래
 * "local_모드에서_세션이_ANTHROPIC_모델로_오버라이드되면_..." 3개 테스트가 그 핵심 회귀 케이스로,
 * 수정 전 코드에서는 반드시 실패한다(가드를 통과해 예외가 나지 않으므로).
 *
 * 가드 지점 3곳(generateSessionClaudeMd / analyzeCodeWithClaude의 README 분기 / 일반 분기)을
 * 전부 각각 커버한다. 필드 주입은 기존 ClaudeServiceImplModelSwitchTest의 리플렉션 패턴을 그대로
 * 재사용하고, 실제 HTTP 호출 없이 검증하기 위해 LlmClient는 호출 횟수만 세는 가짜를 쓴다.
 */
class ClaudeServiceImplApiKeyGuardRoutingTest {

  private static final String ANTHROPIC_MODEL = "claude-sonnet-5";
  private static final String LOCAL_MODEL = "qwen3-32b";
  private static final String MOCK_KEY = "MOCK_KEY_FOR_TEST";
  private static final String REAL_KEY = "sk-real-key-not-mock";
  private static final String SESSION = "/source/guard-routing-session";

  /** 실제 LLM 호출 대신 호출 횟수만 기록하는 가짜 클라이언트. 빈 JSON 배열을 반환해 안전하게 종료시킨다. */
  private static class CountingLlmClient implements LlmClient {
    int callCount;

    @Override
    public LlmResult call(String systemPrompt, String userContent, String model, int maxTokens) {
      callCount++;
      return new LlmResult("[]", 10, 5, 0, 0);
    }
  }

  private CountingLlmClient llmClient;

  /**
   * llm_model_options(DB)에 ANTHROPIC 모델 1건 / LOCAL 모델 1건이 등록된 상태를 가정한 서비스.
   * 두 provider 모두 같은 가짜 클라이언트로 resolve되므로 호출 횟수만으로 가드 통과 여부를 판단할 수 있다.
   */
  private ClaudeServiceImpl newService(String llmProvider, String apiKey) throws Exception {
    LlmModelOptionService optionService = mock(LlmModelOptionService.class);
    when(optionService.isActiveModel(anyString())).thenReturn(true);
    when(optionService.findByModelKey(anyString())).thenReturn(Optional.empty());
    when(optionService.findByModelKey(ANTHROPIC_MODEL))
        .thenReturn(Optional.of(new LlmModelOption(ANTHROPIC_MODEL, "Claude Sonnet", LlmProvider.ANTHROPIC, 1)));
    when(optionService.findByModelKey(LOCAL_MODEL))
        .thenReturn(Optional.of(new LlmModelOption(LOCAL_MODEL, "Qwen3 32B", LlmProvider.LOCAL, 2)));
    return newService(llmProvider, apiKey, optionService);
  }

  /** llmModelOptionService를 null로 넘기는 구버전 테스트 편의 생성 패턴(기존 다수 테스트의 전제)을 그대로 재현한다. */
  private ClaudeServiceImpl newServiceWithoutOptionService(String llmProvider, String apiKey) throws Exception {
    return newService(llmProvider, apiKey, null);
  }

  private ClaudeServiceImpl newService(String llmProvider, String apiKey,
      LlmModelOptionService optionService) throws Exception {
    llmClient = new CountingLlmClient();
    LlmClientResolver resolver = new LlmClientResolver(llmClient, llmClient);
    ClaudeServiceImpl service = new ClaudeServiceImpl(
        new ApiErrorHandler(), null, new SessionConfig(), new ProjectTypeDetector(),
        resolver, optionService, null);
    setField(service, "llmProvider", llmProvider);
    setField(service, "apiModel", ANTHROPIC_MODEL);
    setField(service, "llmLocalModel", LOCAL_MODEL);
    setField(service, "apiKey", apiKey);
    setField(service, "systemPromptFilename", "prompts/prompt-base.md");
    return service;
  }

  private void setField(Object target, String name, Object value) throws Exception {
    Field field = ClaudeServiceImpl.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private String generateClaudeMd(ClaudeServiceImpl service) {
    // 추가 요구사항이 있어야 LLM 호출 경로(=가드 지점)까지 도달한다.
    return service.generateSessionClaudeMd("추가 요구사항: 트랜잭션 경계를 설명할 것", Set.of(".java"), SESSION);
  }

  // ---------------------------------------------------------------------
  // 가드 1: generateSessionClaudeMd (CLAUDE.md 생성) — 차단 시 예외 대신 표준 템플릿 반환
  // ---------------------------------------------------------------------

  @Test
  void CLAUDEmd생성_anthropic모드에서_API키가_정상이면_가드를_통과해_LLM을_호출한다() throws Exception {
    ClaudeServiceImpl service = newService("anthropic", REAL_KEY);

    generateClaudeMd(service);

    assertEquals(1, llmClient.callCount, "API 키가 정상이면 기존과 동일하게 LLM 호출이 일어나야 함");
  }

  @Test
  void CLAUDEmd생성_anthropic모드에서_API키가_MOCK이면_가드가_LLM호출을_막는다() throws Exception {
    ClaudeServiceImpl service = newService("anthropic", MOCK_KEY);

    generateClaudeMd(service);

    assertEquals(0, llmClient.callCount, "API 키 미설정 시 표준 템플릿으로 대체되고 LLM 호출은 없어야 함");
  }

  @Test
  void CLAUDEmd생성_local모드에서_API키가_없어도_가드가_로컬호출을_막지_않는다() throws Exception {
    ClaudeServiceImpl service = newService("local", MOCK_KEY);

    generateClaudeMd(service);

    assertEquals(1, llmClient.callCount, "local 모델로 라우팅되면 Anthropic 키 유무와 무관하게 호출돼야 함");
  }

  @Test
  void CLAUDEmd생성_local모드에서_세션이_ANTHROPIC모델로_오버라이드되고_API키가_없으면_가드가_막는다() throws Exception {
    // REQ-001 핵심 회귀 케이스 — 수정 전에는 isAnthropicMode()=false라 가드를 통과해 호출됐다.
    ClaudeServiceImpl service = newService("local", MOCK_KEY);
    service.setModel(SESSION, ANTHROPIC_MODEL);

    generateClaudeMd(service);

    assertEquals(0, llmClient.callCount, "실제 라우팅 대상이 ANTHROPIC이면 API 키 없이 호출되면 안 됨");
  }

  // ---------------------------------------------------------------------
  // 가드 2: analyzeCodeWithClaude — README 분기
  // ---------------------------------------------------------------------

  @Test
  void README분석_anthropic모드에서_API키가_정상이면_가드를_통과해_LLM을_호출한다() throws Exception {
    ClaudeServiceImpl service = newService("anthropic", REAL_KEY);

    service.analyzeCodeWithClaude("# 프로젝트 구조", "README.md", SESSION);

    assertEquals(1, llmClient.callCount);
  }

  @Test
  void README분석_anthropic모드에서_API키가_MOCK이면_인증예외를_던진다() throws Exception {
    ClaudeServiceImpl service = newService("anthropic", MOCK_KEY);

    AnalysisException ex = assertThrows(AnalysisException.class,
        () -> service.analyzeCodeWithClaude("# 프로젝트 구조", "README.md", SESSION));

    assertEquals(ApiErrorHandler.ErrorType.API_AUTHENTICATION, ex.getErrorType());
    assertEquals(0, llmClient.callCount);
  }

  @Test
  void README분석_local모드에서_API키가_없어도_가드가_로컬호출을_막지_않는다() throws Exception {
    ClaudeServiceImpl service = newService("local", MOCK_KEY);

    service.analyzeCodeWithClaude("# 프로젝트 구조", "README.md", SESSION);

    assertEquals(1, llmClient.callCount);
  }

  @Test
  void README분석_local모드에서_세션이_ANTHROPIC모델로_오버라이드되고_API키가_없으면_인증예외를_던진다() throws Exception {
    // REQ-001 핵심 회귀 케이스 — 수정 전에는 예외 없이 Anthropic 호출이 시도됐다.
    ClaudeServiceImpl service = newService("local", MOCK_KEY);
    service.setModel(SESSION, ANTHROPIC_MODEL);

    AnalysisException ex = assertThrows(AnalysisException.class,
        () -> service.analyzeCodeWithClaude("# 프로젝트 구조", "README.md", SESSION));

    assertEquals(ApiErrorHandler.ErrorType.API_AUTHENTICATION, ex.getErrorType());
    assertEquals(0, llmClient.callCount);
  }

  // ---------------------------------------------------------------------
  // 가드 3: analyzeCodeWithClaude — 일반 파일 분기
  // ---------------------------------------------------------------------

  @Test
  void 일반파일분석_anthropic모드에서_API키가_정상이면_가드를_통과해_LLM을_호출한다() throws Exception {
    ClaudeServiceImpl service = newService("anthropic", REAL_KEY);

    service.analyzeCodeWithClaude("public class Foo {}", "Foo.java", SESSION);

    assertEquals(1, llmClient.callCount);
  }

  @Test
  void 일반파일분석_anthropic모드에서_API키가_MOCK이면_인증예외를_던진다() throws Exception {
    ClaudeServiceImpl service = newService("anthropic", MOCK_KEY);

    AnalysisException ex = assertThrows(AnalysisException.class,
        () -> service.analyzeCodeWithClaude("public class Foo {}", "Foo.java", SESSION));

    assertEquals(ApiErrorHandler.ErrorType.API_AUTHENTICATION, ex.getErrorType());
    assertEquals(0, llmClient.callCount);
  }

  @Test
  void 일반파일분석_local모드에서_API키가_없어도_가드가_로컬호출을_막지_않는다() throws Exception {
    ClaudeServiceImpl service = newService("local", MOCK_KEY);

    service.analyzeCodeWithClaude("public class Foo {}", "Foo.java", SESSION);

    assertEquals(1, llmClient.callCount);
  }

  @Test
  void 일반파일분석_local모드에서_세션이_ANTHROPIC모델로_오버라이드되고_API키가_없으면_인증예외를_던진다() throws Exception {
    // REQ-001 핵심 회귀 케이스 — 수정 전에는 예외 없이 Anthropic 호출이 시도됐다.
    ClaudeServiceImpl service = newService("local", MOCK_KEY);
    service.setModel(SESSION, ANTHROPIC_MODEL);

    AnalysisException ex = assertThrows(AnalysisException.class,
        () -> service.analyzeCodeWithClaude("public class Foo {}", "Foo.java", SESSION));

    assertEquals(ApiErrorHandler.ErrorType.API_AUTHENTICATION, ex.getErrorType());
    assertEquals(0, llmClient.callCount);
  }

  // ---------------------------------------------------------------------
  // llmModelOptionService == null (구버전 테스트 편의 생성자) — 기존 동작 100% 보존 확인
  // ---------------------------------------------------------------------

  @Test
  void 옵션서비스가_null이면_local모드는_기존처럼_API키_없이도_통과한다() throws Exception {
    ClaudeServiceImpl service = newServiceWithoutOptionService("local", MOCK_KEY);

    service.analyzeCodeWithClaude("public class Foo {}", "Foo.java", SESSION);

    assertEquals(1, llmClient.callCount, "DB 조회 없이 모드별 기본값(LOCAL) 폴백이 유지돼야 함");
  }

  @Test
  void 옵션서비스가_null이면_anthropic모드는_기존처럼_API키_미설정_시_인증예외를_던진다() throws Exception {
    ClaudeServiceImpl service = newServiceWithoutOptionService("anthropic", MOCK_KEY);

    AnalysisException ex = assertThrows(AnalysisException.class,
        () -> service.analyzeCodeWithClaude("public class Foo {}", "Foo.java", SESSION));

    assertEquals(ApiErrorHandler.ErrorType.API_AUTHENTICATION, ex.getErrorType());
    assertEquals(0, llmClient.callCount);
  }
}
