package com.legacy.analysis;

import com.legacy.analysis.llm.LlmModelOption;
import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.analysis.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TASK-006 (work-order 2026-09-cost-stats-bugfix, REQ-002) —
 * <b>"이 세션은 어느 provider로 라우팅되는가"라는 판정의 단일 출처를 단언</b>하는 계약 테스트.
 *
 * <h2>왜 이 테스트가 필요한가</h2>
 * 같은 판정이 {@code ClaudeServiceImpl.resolveProvider()}(실제 호출 라우팅·API 키 가드용)와
 * {@code MainApiController.resolveEffectiveProvider()}(접근권한 가드·비용 계산용) <b>두 곳</b>에 존재한다.
 * 판정 자체를 한 곳으로 합치려면 두 클래스가 공유하는 새 컴포넌트를 만들어야 하는데, 이 저장소는
 * {@code isAnthropicMode()}도 이미 양쪽에 중복해 두는 관례를 갖고 있어 그 관례를 따르기로 했다.
 * 대신 <b>중복을 허용하는 대가로 "어긋나면 즉시 깨지는" 장치를 둔다</b> — 그것이 이 테스트다.
 *
 * <p><b>이 테스트가 깨지면 두 구현 중 하나가 낡은 것이며, B2(2026-09-18 등록)가 바로 그 상황이었다.</b>
 * 당시 비용 계산은 전역 {@code llm.provider} 설정만 보고 판정했기 때문에, 로컬 서버 배포에서 세션이
 * DB상 ANTHROPIC 모델을 선택해 실제로 과금이 발생해도 {@code estimated_cost}가 0원으로 기록됐다.
 * 라우팅 쪽({@code ClaudeServiceImpl})은 이미 DB를 보고 올바르게 판정하고 있었으므로, 결함의 정체는
 * "판정이 두 벌인데 한쪽만 갱신됐다"는 것이었다.
 *
 * <h2>무엇을 단언하는가</h2>
 * <ol>
 *   <li><b>DoD (a) 6칸 진리표</b> — {@code llm.provider}(anthropic/local) × DB 조회 결과(ANTHROPIC/LOCAL/없음)
 *       6가지 조합에서 두 구현이 <b>같은 값</b>을 반환하고, 그 값이 <b>기대값과도 일치</b>한다.</li>
 *   <li><b>DoD (a) null 협력자 3모드</b> — {@code llmModelOptionService}가 null인 인스턴스에서도
 *       {@code llm.provider}가 anthropic/local/미설정(null)인 3가지에 대해 양쪽이 같은 값을 낸다.
 *       이 가드는 원래 {@code ClaudeServiceImpl}에만 있었고 {@code MainApiController}에는 TASK-005에서
 *       추가됐다 — 즉 <b>두 구현이 가장 마지막까지 갈라져 있던 지점</b>이라 별도로 고정한다.</li>
 * </ol>
 *
 * <h2>단언을 두 겹으로 두는 이유</h2>
 * 각 케이스에서 <b>먼저 두 구현의 반환값을 서로 비교하고, 그다음 기대값과 비교</b>한다.
 * 앞 단언은 "다음 사이클에 누군가 한쪽만 고쳤을 때"를 잡고, 뒤 단언은 "양쪽을 똑같이 잘못 고쳤을 때"를
 * 잡는다. 앞 단언만 있으면 두 구현이 나란히 틀려도 통과하고, 뒤 단언만 있으면 실질적으로 구현별 테스트가
 * 두 벌 생길 뿐 "단일 출처"를 보장하지 못한다.
 *
 * <h2>탐지력 확인 (양성 대조군)</h2>
 * 통과하는 테스트가 아무것도 잡지 못하는 경우를 배제하기 위해, 한쪽 구현의 폴백을 <b>일부러 반대로
 * 뒤집어</b> 이 테스트가 실제로 Fail하는지 1회 확인한 뒤 원복했다(관측값은 dev 기록 참조).
 *
 * <h2>접근 방식</h2>
 * 두 메서드 모두 private이므로 리플렉션으로 호출한다 — 이 저장소의 기존 관례
 * ({@code ClaudeServiceImplNormalizeCommentTest}의 협력자 null 생성 + private 메서드 리플렉션,
 * {@code MainApiControllerLlmProviderTest}의 {@code llmProvider} 필드 리플렉션 주입)를 그대로 따른다.
 * 판정에 실제로 쓰이는 협력자({@code llmModelOptionService})와 설정값({@code llm.provider}) 외의
 * 의존성은 전부 null로 넘긴다.
 */
class LlmRoutingDecisionSingleSourceContractTest {

  /** 두 구현에 동일하게 넣는 조회 키. 판정 규칙만 비교하므로 키 자체에는 의미가 없다. */
  private static final String MODEL_KEY = "claude-sonnet-4-6";

  /** DB 조회 결과의 4가지 상태 — 6칸 진리표의 3가지 + 협력자 자체가 없는 경우. */
  private enum DbLookup {
    ANTHROPIC_MODEL,
    LOCAL_MODEL,
    NOT_FOUND,
    NO_SERVICE
  }

  /**
   * 같은 조회 결과를 내는 협력자를 두 구현에 각각 넘긴다(같은 mock 인스턴스를 공유하지 않는다 —
   * 한쪽 호출이 다른 쪽 검증에 영향을 주지 않게 하기 위함).
   */
  private LlmModelOptionService serviceFor(DbLookup lookup) {
    if (lookup == DbLookup.NO_SERVICE) {
      return null;
    }
    LlmModelOptionService service = mock(LlmModelOptionService.class);
    Optional<LlmModelOption> found = switch (lookup) {
      case ANTHROPIC_MODEL ->
          Optional.of(new LlmModelOption(MODEL_KEY, "Claude Sonnet", LlmProvider.ANTHROPIC, 0));
      case LOCAL_MODEL ->
          Optional.of(new LlmModelOption(MODEL_KEY, "사내 LLM", LlmProvider.LOCAL, 0));
      default -> Optional.empty();
    };
    when(service.findByModelKey(MODEL_KEY)).thenReturn(found);
    return service;
  }

  /** 라우팅 판정 정본: {@code ClaudeServiceImpl.resolveProvider()}. */
  private LlmProvider claudeServiceSide(String llmProvider, DbLookup lookup) throws Exception {
    ClaudeServiceImpl service =
        new ClaudeServiceImpl(null, null, null, null, null, serviceFor(lookup), null);
    Field field = ClaudeServiceImpl.class.getDeclaredField("llmProvider");
    field.setAccessible(true);
    field.set(service, llmProvider);

    Method method = ClaudeServiceImpl.class.getDeclaredMethod("resolveProvider", String.class);
    method.setAccessible(true);
    return (LlmProvider) method.invoke(service, MODEL_KEY);
  }

  /** 가드·비용 계산이 쓰는 판정: {@code MainApiController.resolveEffectiveProvider()}. */
  private LlmProvider controllerSide(String llmProvider, DbLookup lookup) throws Exception {
    MainApiController controller = new MainApiController(
        null, null, null, null, null, null, null, null, null, null, null, null, null,
        serviceFor(lookup), null);
    Field field = MainApiController.class.getDeclaredField("llmProvider");
    field.setAccessible(true);
    field.set(controller, llmProvider);

    Method method =
        MainApiController.class.getDeclaredMethod("resolveEffectiveProvider", String.class);
    method.setAccessible(true);
    return (LlmProvider) method.invoke(controller, MODEL_KEY);
  }

  /**
   * ① 두 구현이 서로 같은가(단일 출처) → ② 그 값이 기대값과 같은가(둘이 나란히 틀린 경우 탐지).
   * 이 순서를 지키는 이유는 클래스 Javadoc "단언을 두 겹으로 두는 이유" 참조.
   */
  private void assertBothImplementationsAgree(String llmProvider, DbLookup lookup,
      LlmProvider expected) throws Exception {
    LlmProvider claudeSide = claudeServiceSide(llmProvider, lookup);
    LlmProvider controllerSide = controllerSide(llmProvider, lookup);

    assertEquals(claudeSide, controllerSide,
        "판정이 어긋났다 — llm.provider=" + llmProvider + ", DB=" + lookup
            + " 에서 ClaudeServiceImpl.resolveProvider()=" + claudeSide
            + " 인데 MainApiController.resolveEffectiveProvider()=" + controllerSide
            + " 다. 두 구현 중 하나가 낡았다(B2와 같은 상황).");
    assertEquals(expected, claudeSide,
        "두 구현이 일치하지만 기대값과 다르다 — llm.provider=" + llmProvider + ", DB=" + lookup
            + " 의 기대 provider는 " + expected + " 다. 양쪽을 똑같이 잘못 고쳤을 가능성을 확인하라.");
  }

  // ---------- 6칸 진리표 (DoD a) ----------

  @Test
  void anthropic모드에서_DB가_ANTHROPIC이면_양쪽_모두_ANTHROPIC이다() throws Exception {
    assertBothImplementationsAgree("anthropic", DbLookup.ANTHROPIC_MODEL, LlmProvider.ANTHROPIC);
  }

  @Test
  void anthropic모드에서_DB가_LOCAL이면_양쪽_모두_LOCAL이다() throws Exception {
    // failover 대상 등으로 DB에 LOCAL 모델이 등록돼 있고 세션이 그걸 쓰는 경우 —
    // 전역 설정이 anthropic이어도 실제 호출은 로컬로 나가므로 과금 대상이 아니다.
    assertBothImplementationsAgree("anthropic", DbLookup.LOCAL_MODEL, LlmProvider.LOCAL);
  }

  @Test
  void anthropic모드에서_DB에_없는_키면_양쪽_모두_모드_기본값인_ANTHROPIC이다() throws Exception {
    assertBothImplementationsAgree("anthropic", DbLookup.NOT_FOUND, LlmProvider.ANTHROPIC);
  }

  @Test
  void local모드에서_DB가_ANTHROPIC이면_양쪽_모두_ANTHROPIC이다() throws Exception {
    // B2가 틀렸던 칸 — 전역 설정이 local이어도 세션이 DB상 ANTHROPIC 모델을 쓰면 실제 호출은
    // Anthropic으로 나가고 과금도 발생한다. 전역 설정을 근거로 LOCAL(=무료)로 판정하면 안 된다.
    assertBothImplementationsAgree("local", DbLookup.ANTHROPIC_MODEL, LlmProvider.ANTHROPIC);
  }

  @Test
  void local모드에서_DB가_LOCAL이면_양쪽_모두_LOCAL이다() throws Exception {
    assertBothImplementationsAgree("local", DbLookup.LOCAL_MODEL, LlmProvider.LOCAL);
  }

  @Test
  void local모드에서_DB에_없는_키면_양쪽_모두_모드_기본값인_LOCAL이다() throws Exception {
    assertBothImplementationsAgree("local", DbLookup.NOT_FOUND, LlmProvider.LOCAL);
  }

  // ---------- 협력자 null 3모드 (DoD a) ----------
  // 두 구현이 가장 마지막까지 갈라져 있던 지점이다: ClaudeServiceImpl은 원래 이 가드를 갖고 있었고,
  // MainApiController에는 TASK-005에서 추가됐다. DB 조회를 아예 하지 않으므로 모드 기본값이 곧 결과다.

  @Test
  void 협력자가_null이어도_anthropic모드면_양쪽_모두_ANTHROPIC이다() throws Exception {
    assertBothImplementationsAgree("anthropic", DbLookup.NO_SERVICE, LlmProvider.ANTHROPIC);
  }

  @Test
  void 협력자가_null이어도_local모드면_양쪽_모두_LOCAL이다() throws Exception {
    assertBothImplementationsAgree("local", DbLookup.NO_SERVICE, LlmProvider.LOCAL);
  }

  @Test
  void 협력자가_null이고_llm_provider가_미설정이면_양쪽_모두_기본값_ANTHROPIC이다() throws Exception {
    // llm.provider 미설정은 @Value의 matchIfMissing과 동일하게 anthropic으로 취급한다.
    assertBothImplementationsAgree(null, DbLookup.NO_SERVICE, LlmProvider.ANTHROPIC);
  }
}
