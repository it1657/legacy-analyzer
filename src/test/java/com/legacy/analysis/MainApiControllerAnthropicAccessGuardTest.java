package com.legacy.analysis;

import com.legacy.analysis.llm.LlmModelOption;
import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.analysis.llm.LlmProvider;
import com.legacy.auth.Role;
import com.legacy.auth.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-004(2026-09-anthropic-access-control) 서버측 Anthropic 사용 권한 가드를 검증한다.
 * 04-work-order-v2 TASK-004 / 02-design-v2 4·8절 근거.
 *
 * <p><b>케이스 배치(v2 정정)</b>: 5개 매트릭스는 {@code uploadAnalysis} 기준이다.
 * {@code startAnalysis}는 프로덕션 코드에서 이미 {@code !isAdmin(authentication)} 조기 반환으로
 * admin 전용이라, 비인가 사용자 케이스가 그 게이트에 먼저 걸린다 — 가드가 없어도 통과하는 vacuous test가
 * 되거나 기대값 자체가 성립하지 않는다. 그래서 startAnalysis에는 실제로 의미가 성립하는 2개
 * (admin 통과 / 기존 admin 게이트 회귀)만 둔다. 총 7개.
 *
 * <p><b>20절(음성결과 양성대조군) 준수</b>: "차단된다"(음성)와 "정확히 같은 입력인데 권한만 다르면 차단되지
 * 않는다"(양성 대조군)를 한 스위트에 함께 둔다. 특히 케이스5(권한 없는 사용자 + LOCAL 모델 → 통과)는
 * 권한 통제가 로컬 사용까지 막지 않는다는 증거다 — 이 프로젝트의 "설정만으로 로컬 전환" 목표와 직결된다.
 *
 * <p>생성자는 기존 {@code MainApiController*Test}들과 동일하게 "필요한 의존성만 mock, 나머지는 null" 패턴을 쓴다.
 */
class MainApiControllerAnthropicAccessGuardTest {

  /** 가드를 통과했을 때만 도달하는 지점(createSession)에 심어두는 표식. */
  private static final String SENTINEL = "SENTINEL: 가드를 통과해 createSession까지 도달함";

  private static final String ANTHROPIC_MODEL_KEY = "claude-sonnet-5";
  private static final String LOCAL_MODEL_KEY = "qwen3-32b";
  private static final String ANTHROPIC_DENIED_MESSAGE =
      "Anthropic 모델을 사용할 권한이 없습니다. 관리자에게 Anthropic 사용 권한을 요청하세요.";
  private static final String ADMIN_ONLY_MESSAGE =
      "서버 경로 직접 지정 분석은 관리자만 사용할 수 있습니다. 원격 업로드 분석을 이용해 주세요.";

  @TempDir
  Path tempDir;

  private ClaudeService claudeService;
  private AnalysisSessionManager sessionManager;
  private LlmModelOptionService llmModelOptionService;
  private MainApiController controller;

  @BeforeEach
  void setUp() throws Exception {
    claudeService = mock(ClaudeService.class);
    sessionManager = mock(AnalysisSessionManager.class);
    llmModelOptionService = mock(LlmModelOptionService.class);

    controller = new MainApiController(
        claudeService, null, sessionManager, null, null, null, null, null, null, null, null, null, null,
        llmModelOptionService, null);
    setField("llmProvider", "anthropic");
    setField("uploadStoragePath", tempDir.toString());

    // DB에 등록된 모델 2종 — provider 판정의 기준이 된다.
    when(llmModelOptionService.findByModelKey(ANTHROPIC_MODEL_KEY)).thenReturn(
        Optional.of(new LlmModelOption(ANTHROPIC_MODEL_KEY, "Claude Sonnet", LlmProvider.ANTHROPIC, 0)));
    when(llmModelOptionService.findByModelKey(LOCAL_MODEL_KEY)).thenReturn(
        Optional.of(new LlmModelOption(LOCAL_MODEL_KEY, "Qwen3 32B", LlmProvider.LOCAL, 1)));
  }

  private void setField(String name, Object value) throws Exception {
    Field field = MainApiController.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(controller, value);
  }

  // ── 인증 픽스처 (기존 MainApiControllerSessionOwnershipTest 패턴 재사용) ──────────

  /** ANTHROPIC_USER도 ADMIN도 없는 일반 사용자 — "비인가 사용자". */
  private Authentication plainUserAuth() {
    return authOf("u1", 1L, new Role("USER", "일반 사용자 역할"));
  }

  /** ANTHROPIC_USER Role을 보유한 일반 사용자 — 대조군. */
  private Authentication anthropicUserAuth() {
    return authOf("u2", 2L, new Role("USER", "일반 사용자 역할"),
        new Role("ANTHROPIC_USER", "Anthropic(Claude API) 사용 권한"));
  }

  /** ANTHROPIC_USER Role은 없고 ADMIN만 가진 관리자 — 대조군. */
  private Authentication adminAuth() {
    return authOf("admin", 99L, new Role("ADMIN", "관리자 역할"));
  }

  private Authentication authOf(String loginId, Long seq, Role... roles) {
    User user = new User(loginId, loginId + "@example.com", "hash");
    user.setSeq(seq);
    user.setRoles(Set.of(roles));
    return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
  }

  // ── 호출 헬퍼 ────────────────────────────────────────────────────────────

  /**
   * 가드를 통과하면 곧바로 도달하는 createSession에 표식 예외를 심는다.
   * "차단되지 않았다"를 결정적으로 확인하면서, 실제 분석 스레드가 기동되는 것을 막는 용도다.
   */
  private void sentinelAtCreateSession() {
    when(sessionManager.createSession(anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException(SENTINEL));
  }

  private MultipartFile[] oneFile() {
    MultipartFile file = mock(MultipartFile.class);
    when(file.getOriginalFilename()).thenReturn("Foo.java");
    return new MultipartFile[] { file };
  }

  private Map<String, Object> uploadAnalysis(String selectedModel, Authentication authentication) {
    return controller.uploadAnalysis(oneFile(), "session-1", selectedModel, "proj", null, null, authentication);
  }

  private Map<String, Object> startAnalysis(String selectedModel, Authentication authentication) {
    Map<String, String> request = new HashMap<>();
    // 가드보다 뒤에 있는 경로 검증에서 걸리도록 존재하지 않는 경로를 준다 —
    // "가드를 통과해 그 다음 단계까지 갔다"는 사실 자체를 에러 메시지로 구분하기 위해서다.
    request.put("sourcePath", tempDir.resolve("no-such-dir").toString());
    request.put("model", selectedModel);
    return controller.startAnalysis(request, authentication);
  }

  // ===================================================================
  // uploadAnalysis — 5개 매트릭스 (음성 2 + 양성 대조군 3)
  // ===================================================================

  @Test
  void 케이스1_비인가_사용자가_ANTHROPIC_모델키로_업로드분석하면_차단되고_부작용도_없다() {
    Map<String, Object> result = uploadAnalysis(ANTHROPIC_MODEL_KEY, plainUserAuth());

    assertEquals(ANTHROPIC_DENIED_MESSAGE, result.get("error"));
    assertNull(result.get("sessionId"), "차단됐으므로 세션이 발급되면 안 된다");
    // 부작용 미발생 — 모델 전환도, 세션 생성도 일어나지 않아야 한다.
    verify(claudeService, never()).setModel(anyString(), anyString());
    verify(sessionManager, never()).createSession(anyString(), anyString(), anyString());
  }

  @Test
  void 케이스2_비인가_사용자가_모델을_비워도_서버_기본모드가_anthropic이면_차단된다() throws Exception {
    // effective provider 폴백 경로 — 요청에 model이 없어도 실제로는 anthropic으로 라우팅되므로 막아야 한다.
    setField("llmProvider", "anthropic");
    when(claudeService.getCurrentModel(anyString())).thenReturn("claude-haiku-4-5-20251001");
    // DB에 없는 모델키 → isAnthropicMode() 폴백으로 판정되는 경로임을 명시한다.
    when(llmModelOptionService.findByModelKey("claude-haiku-4-5-20251001")).thenReturn(Optional.empty());

    Map<String, Object> result = uploadAnalysis(null, plainUserAuth());

    assertEquals(ANTHROPIC_DENIED_MESSAGE, result.get("error"));
    verify(claudeService, never()).setModel(anyString(), anyString());
    verify(sessionManager, never()).createSession(anyString(), anyString(), anyString());
  }

  @Test
  void 케이스3_ANTHROPIC_USER_보유_사용자는_같은_입력으로_차단되지_않는다() {
    // 양성 대조군 — 케이스1과 입력이 완전히 같고 역할만 다르다.
    sentinelAtCreateSession();

    RuntimeException thrown = assertThrows(RuntimeException.class,
        () -> uploadAnalysis(ANTHROPIC_MODEL_KEY, anthropicUserAuth()));

    assertEquals(SENTINEL, thrown.getMessage(), "가드를 통과해 세션 생성까지 진행돼야 한다");
    verify(claudeService).setModel(anyString(), eq(ANTHROPIC_MODEL_KEY));
  }

  @Test
  void 케이스4_admin은_ANTHROPIC_USER_역할이_없어도_차단되지_않는다() {
    // 양성 대조군 — Role 없이 admin 권한만으로 통과해야 한다.
    sentinelAtCreateSession();

    RuntimeException thrown = assertThrows(RuntimeException.class,
        () -> uploadAnalysis(ANTHROPIC_MODEL_KEY, adminAuth()));

    assertEquals(SENTINEL, thrown.getMessage());
    verify(claudeService).setModel(anyString(), eq(ANTHROPIC_MODEL_KEY));
  }

  @Test
  void 케이스5_권한_없는_사용자도_LOCAL_모델이면_항상_통과한다() {
    // 가장 중요한 대조군 — 권한 통제가 로컬 사용까지 막으면 "설정만으로 로컬 전환" 목표와 정면 배치된다.
    // 케이스1과 사용자(권한 없음)는 완전히 같고 모델 provider만 LOCAL로 다르다.
    sentinelAtCreateSession();

    RuntimeException thrown = assertThrows(RuntimeException.class,
        () -> uploadAnalysis(LOCAL_MODEL_KEY, plainUserAuth()));

    assertEquals(SENTINEL, thrown.getMessage(), "LOCAL 모델은 권한과 무관하게 통과해야 한다");
    verify(claudeService).setModel(anyString(), eq(LOCAL_MODEL_KEY));
  }

  // ===================================================================
  // startAnalysis — 2개 (admin 게이트 뒤 심층 방어 확인용)
  // ===================================================================

  @Test
  void startAnalysis_admin은_ANTHROPIC_모델키로도_가드에_막히지_않는다() {
    Map<String, Object> result = startAnalysis(ANTHROPIC_MODEL_KEY, adminAuth());

    // 가드를 통과했으므로 그 뒤의 경로 검증까지 진행돼 "경로" 에러가 나온다.
    assertNotEquals(ANTHROPIC_DENIED_MESSAGE, result.get("error"));
    assertEquals("올바르지 않은 원본 소스 경로입니다.", result.get("error"));
    verify(claudeService).setModel(anyString(), eq(ANTHROPIC_MODEL_KEY));
  }

  @Test
  void startAnalysis_비admin은_가드_추가_후에도_기존_admin_전용_게이트에서_그대로_차단된다() {
    // 회귀 가드 — 심층 방어를 추가하면서 기존 방어(admin 게이트)의 순서나 동작을 바꾸면 안 된다.
    Map<String, Object> result = startAnalysis(ANTHROPIC_MODEL_KEY, plainUserAuth());

    assertEquals(ADMIN_ONLY_MESSAGE, result.get("error"), "기존 admin 게이트 메시지가 그대로여야 한다");
    verify(claudeService, never()).setModel(anyString(), anyString());
    verify(sessionManager, never()).createSession(anyString(), anyString(), anyString());
    // admin 게이트가 새 가드보다 여전히 앞에 있음 — 뒤로 밀렸다면 provider 조회가 먼저 일어났을 것이다.
    verify(llmModelOptionService, never()).findByModelKey(anyString());
  }
}
