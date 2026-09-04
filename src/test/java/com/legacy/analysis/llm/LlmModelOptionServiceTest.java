package com.legacy.analysis.llm;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * {@link LlmModelOptionService}의 CRUD + 도메인 규칙(활성 모델 최소 1개 유지, failover 대상
 * 단일성)을 순수 단위 테스트로 검증한다(com.legacy.admin 패키지 테스트와 동일하게 Mockito로
 * Repository를 목킹하고 Spring 컨테이너는 기동하지 않는다).
 *
 * "최소 1개 활성 모델 유지" 규칙 근거: analyzer-plan
 * docs/chat/etc/2026-08-21-llm-model-min-active-guard-and-handoff.md
 */
class LlmModelOptionServiceTest {

  private LlmModelOptionRepository repository;
  private LlmModelOptionService service;

  private LlmModelOptionService newService() {
    repository = mock(LlmModelOptionRepository.class);
    // save()는 전달받은 엔티티를 그대로 반환(실제 JPA save처럼) — id는 테스트에서 미리 세팅해서 넘긴다.
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    return new LlmModelOptionService(repository);
  }

  private LlmModelOption fixture(Long id, String modelKey, LlmProvider provider, boolean active, boolean failoverTarget) {
    LlmModelOption option = new LlmModelOption(modelKey, modelKey + " 표시명", provider, 0);
    option.setId(id);
    option.setActive(active);
    option.setFailoverTarget(failoverTarget);
    return option;
  }

  // ===================== create =====================

  @Test
  void create_정상_등록시_저장된_엔티티를_반환한다() {
    service = newService();
    when(repository.existsByModelKey("qwen3-32b")).thenReturn(false);

    LlmModelOption saved = service.create("qwen3-32b", "Qwen3 32B", LlmProvider.LOCAL, 5);

    assertEquals("qwen3-32b", saved.getModelKey());
    assertEquals("Qwen3 32B", saved.getDisplayName());
    assertEquals(LlmProvider.LOCAL, saved.getProvider());
    assertEquals(5, saved.getDisplayOrder());
    verify(repository).save(any());
  }

  @Test
  void create_이미_존재하는_모델키면_거부한다() {
    service = newService();
    when(repository.existsByModelKey("claude-sonnet-4-6")).thenReturn(true);

    assertThrows(IllegalStateException.class,
        () -> service.create("claude-sonnet-4-6", "표시명", LlmProvider.ANTHROPIC, 0));
    verify(repository, never()).save(any());
  }

  @Test
  void create_표시명이_비어있으면_거부한다() {
    service = newService();
    assertThrows(IllegalArgumentException.class,
        () -> service.create("model-key", "  ", LlmProvider.ANTHROPIC, 0));
  }

  @Test
  void create_provider가_없으면_거부한다() {
    service = newService();
    assertThrows(IllegalArgumentException.class,
        () -> service.create("model-key", "표시명", null, 0));
  }

  // ===================== update =====================

  @Test
  void update_존재하지_않는_id면_거부한다() {
    service = newService();
    when(repository.findById(99L)).thenReturn(Optional.empty());

    assertThrows(IllegalArgumentException.class, () -> service.update(99L, "새 표시명", 1));
  }

  @Test
  void update_정상_수정시_표시명과_노출순서가_바뀐다() {
    service = newService();
    LlmModelOption existing = fixture(1L, "claude-sonnet-4-6", LlmProvider.ANTHROPIC, true, false);
    when(repository.findById(1L)).thenReturn(Optional.of(existing));

    LlmModelOption updated = service.update(1L, "새 표시명", 9);

    assertEquals("새 표시명", updated.getDisplayName());
    assertEquals(9, updated.getDisplayOrder());
    assertEquals("claude-sonnet-4-6", updated.getModelKey(), "modelKey는 생성 후 불변");
  }

  // ===================== setActive (최소 1개 활성 유지) =====================

  @Test
  void setActive_비활성화해도_활성_모델이_2개_이상_남으면_허용된다() {
    service = newService();
    LlmModelOption target = fixture(1L, "claude-haiku", LlmProvider.ANTHROPIC, true, false);
    when(repository.findById(1L)).thenReturn(Optional.of(target));
    when(repository.countByActiveTrue()).thenReturn(2L);

    LlmModelOption result = service.setActive(1L, false);

    assertFalse(result.isActive());
  }

  @Test
  void setActive_유일한_활성_모델을_비활성화하려_하면_거부한다() {
    service = newService();
    LlmModelOption target = fixture(1L, "claude-haiku", LlmProvider.ANTHROPIC, true, false);
    when(repository.findById(1L)).thenReturn(Optional.of(target));
    when(repository.countByActiveTrue()).thenReturn(1L);

    IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.setActive(1L, false));
    assertEquals(LlmModelOptionService.MIN_ACTIVE_GUARD_MESSAGE, ex.getMessage());
    verify(repository, never()).save(any());
  }

  @Test
  void setActive_이미_비활성인_모델을_다시_비활성화해도_카운트_검증을_하지_않는다() {
    service = newService();
    LlmModelOption target = fixture(1L, "claude-haiku", LlmProvider.ANTHROPIC, false, false);
    when(repository.findById(1L)).thenReturn(Optional.of(target));

    LlmModelOption result = service.setActive(1L, false);

    assertFalse(result.isActive());
    verify(repository, never()).countByActiveTrue();
  }

  @Test
  void setActive_활성화는_카운트_검증_없이_항상_허용된다() {
    service = newService();
    LlmModelOption target = fixture(1L, "claude-haiku", LlmProvider.ANTHROPIC, false, false);
    when(repository.findById(1L)).thenReturn(Optional.of(target));

    LlmModelOption result = service.setActive(1L, true);

    assertTrue(result.isActive());
    verify(repository, never()).countByActiveTrue();
  }

  // ===================== delete (최소 1개 활성 유지) =====================

  @Test
  void delete_유일한_활성_모델을_삭제하려_하면_거부한다() {
    service = newService();
    LlmModelOption target = fixture(1L, "claude-haiku", LlmProvider.ANTHROPIC, true, false);
    when(repository.findById(1L)).thenReturn(Optional.of(target));
    when(repository.countByActiveTrue()).thenReturn(1L);

    IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.delete(1L));
    assertEquals(LlmModelOptionService.MIN_ACTIVE_GUARD_MESSAGE, ex.getMessage());
    verify(repository, never()).deleteById(anyLong());
  }

  @Test
  void delete_활성_모델이_2개_이상이면_삭제가_허용된다() {
    service = newService();
    LlmModelOption target = fixture(1L, "claude-haiku", LlmProvider.ANTHROPIC, true, false);
    when(repository.findById(1L)).thenReturn(Optional.of(target));
    when(repository.countByActiveTrue()).thenReturn(2L);

    service.delete(1L);

    verify(repository).deleteById(1L);
  }

  @Test
  void delete_비활성_모델은_카운트_검증_없이_항상_삭제_허용된다() {
    service = newService();
    LlmModelOption target = fixture(1L, "claude-haiku", LlmProvider.ANTHROPIC, false, false);
    when(repository.findById(1L)).thenReturn(Optional.of(target));

    service.delete(1L);

    verify(repository, never()).countByActiveTrue();
    verify(repository).deleteById(1L);
  }

  // ===================== setFailoverTarget (단일성 + 활성/LOCAL 제약) =====================

  @Test
  void setFailoverTarget_비활성_모델은_대상으로_지정할_수_없다() {
    service = newService();
    LlmModelOption target = fixture(1L, "qwen3-32b", LlmProvider.LOCAL, false, false);
    when(repository.findById(1L)).thenReturn(Optional.of(target));

    assertThrows(IllegalStateException.class, () -> service.setFailoverTarget(1L, true));
  }

  @Test
  void setFailoverTarget_ANTHROPIC_모델은_대상으로_지정할_수_없다() {
    service = newService();
    LlmModelOption target = fixture(1L, "claude-sonnet-4-6", LlmProvider.ANTHROPIC, true, false);
    when(repository.findById(1L)).thenReturn(Optional.of(target));

    assertThrows(IllegalStateException.class, () -> service.setFailoverTarget(1L, true));
  }

  @Test
  void setFailoverTarget_지정시_기존에_지정돼_있던_다른_모델은_자동으로_해제된다() {
    service = newService();
    LlmModelOption newTarget = fixture(2L, "qwen3-32b", LlmProvider.LOCAL, true, false);
    LlmModelOption oldTarget = fixture(1L, "qwen3-8b", LlmProvider.LOCAL, true, true);
    when(repository.findById(2L)).thenReturn(Optional.of(newTarget));
    when(repository.findByFailoverTargetTrueAndActiveTrue()).thenReturn(Optional.of(oldTarget));

    LlmModelOption result = service.setFailoverTarget(2L, true);

    assertTrue(result.isFailoverTarget());
    assertFalse(oldTarget.isFailoverTarget(), "기존 대상은 자동으로 해제되어야 함(정확히 0/1개만 유지)");
    verify(repository).save(oldTarget);
  }

  @Test
  void setFailoverTarget_해제는_활성_LOCAL_제약_없이_항상_허용된다() {
    service = newService();
    LlmModelOption target = fixture(1L, "qwen3-32b", LlmProvider.LOCAL, true, true);
    when(repository.findById(1L)).thenReturn(Optional.of(target));

    LlmModelOption result = service.setFailoverTarget(1L, false);

    assertFalse(result.isFailoverTarget());
  }

  // ===================== 조회 계열 =====================

  @Test
  void isActiveModel_등록되어있고_활성이면_true() {
    service = newService();
    when(repository.findByModelKey("claude-sonnet-4-6"))
        .thenReturn(Optional.of(fixture(1L, "claude-sonnet-4-6", LlmProvider.ANTHROPIC, true, false)));

    assertTrue(service.isActiveModel("claude-sonnet-4-6"));
  }

  @Test
  void isActiveModel_비활성이면_false() {
    service = newService();
    when(repository.findByModelKey("claude-sonnet-4-6"))
        .thenReturn(Optional.of(fixture(1L, "claude-sonnet-4-6", LlmProvider.ANTHROPIC, false, false)));

    assertFalse(service.isActiveModel("claude-sonnet-4-6"));
  }

  @Test
  void isActiveModel_등록되지_않은_모델키면_false() {
    service = newService();
    when(repository.findByModelKey("unknown-model")).thenReturn(Optional.empty());

    assertFalse(service.isActiveModel("unknown-model"));
  }

  // ===================== seedDefaultsIfEmpty =====================

  @Test
  void seedDefaultsIfEmpty_테이블이_비어있으면_기존_3종_모델을_시드한다() {
    service = newService();
    when(repository.count()).thenReturn(0L);
    when(repository.existsByModelKey(any())).thenReturn(false);

    service.seedDefaultsIfEmpty();

    verify(repository, times(3)).save(any());
  }

  @Test
  void seedDefaultsIfEmpty_이미_데이터가_있으면_아무것도_하지_않는다() {
    service = newService();
    when(repository.count()).thenReturn(1L);

    service.seedDefaultsIfEmpty();

    verify(repository, never()).save(any());
  }

  // ============ createWithOllamaValidation (REQ-003 절충안, 2026-09) ============

  /** OllamaModelDiscoveryClient를 목킹해 주입한 서비스(2-인자 생성자) — 하드 검증 경로 검증용. */
  private LlmModelOptionService newServiceWithDiscovery(OllamaModelDiscoveryClient discoveryClient) {
    repository = mock(LlmModelOptionRepository.class);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    return new LlmModelOptionService(repository, discoveryClient);
  }

  @Test
  void createWithOllamaValidation_LOCAL이고_조회성공_목록에있으면_등록된다() {
    OllamaModelDiscoveryClient discoveryClient = mock(OllamaModelDiscoveryClient.class);
    when(discoveryClient.listInstalledModels())
        .thenReturn(Optional.of(java.util.List.of("qwen2.5-coder:7b", "llama3:8b")));
    service = newServiceWithDiscovery(discoveryClient);
    when(repository.existsByModelKey("qwen2.5-coder:7b")).thenReturn(false);

    LlmModelOption saved = service.createWithOllamaValidation(
        "qwen2.5-coder:7b", "Qwen2.5 Coder 7B", LlmProvider.LOCAL, 3);

    assertEquals("qwen2.5-coder:7b", saved.getModelKey());
    verify(repository).save(any());
  }

  @Test
  void createWithOllamaValidation_LOCAL이고_조회성공_목록에없으면_거부된다() {
    // 게이트1 사람 결정(절충안): 조회가 성공한 배포(= Ollama 확실히 연결됨)에서는
    // 설치되지 않은 모델명을 등록조차 못하게 하드 차단한다.
    OllamaModelDiscoveryClient discoveryClient = mock(OllamaModelDiscoveryClient.class);
    when(discoveryClient.listInstalledModels())
        .thenReturn(Optional.of(java.util.List.of("qwen2.5-coder:7b")));
    service = newServiceWithDiscovery(discoveryClient);

    IllegalStateException e = assertThrows(IllegalStateException.class,
        () -> service.createWithOllamaValidation("없는모델:1b", "없는 모델", LlmProvider.LOCAL, 3));

    assertTrue(e.getMessage().contains("Ollama에 설치되지 않은 모델입니다"));
    verify(repository, never()).save(any());
  }

  @Test
  void createWithOllamaValidation_LOCAL이고_조회실패면_자유입력이_허용된다() {
    // 비Ollama LOCAL 백엔드(vLLM/LocalAI 등)나 Ollama 미기동 환경에서는 검증 자체가 불가능하므로
    // 기존처럼 자유 텍스트 입력을 허용한다(경고 로그만 남김).
    OllamaModelDiscoveryClient discoveryClient = mock(OllamaModelDiscoveryClient.class);
    when(discoveryClient.listInstalledModels()).thenReturn(Optional.empty());
    service = newServiceWithDiscovery(discoveryClient);
    when(repository.existsByModelKey("임의모델:1b")).thenReturn(false);

    LlmModelOption saved = service.createWithOllamaValidation("임의모델:1b", "임의 모델", LlmProvider.LOCAL, 3);

    assertEquals("임의모델:1b", saved.getModelKey());
    verify(repository).save(any());
  }

  @Test
  void createWithOllamaValidation_ANTHROPIC이면_discovery를_호출하지_않는다() {
    OllamaModelDiscoveryClient discoveryClient = mock(OllamaModelDiscoveryClient.class);
    service = newServiceWithDiscovery(discoveryClient);
    when(repository.existsByModelKey("claude-sonnet-4-6")).thenReturn(false);

    service.createWithOllamaValidation("claude-sonnet-4-6", "Claude Sonnet", LlmProvider.ANTHROPIC, 0);

    verify(discoveryClient, never()).listInstalledModels();
    verify(repository).save(any());
  }

  @Test
  void createWithOllamaValidation_discoveryClient가_없으면_검증없이_통과한다() {
    // 1-인자(테스트 편의) 생성자로 만든 인스턴스 — "조회 실패"와 동일 취급이어야 한다.
    service = newService();
    when(repository.existsByModelKey("임의모델:1b")).thenReturn(false);

    LlmModelOption saved = service.createWithOllamaValidation("임의모델:1b", "임의 모델", LlmProvider.LOCAL, 3);

    assertEquals("임의모델:1b", saved.getModelKey());
  }

  // ===================== hasActiveLocalModel (REQ-001, 2026-09) =====================

  @Test
  void hasActiveLocalModel_활성_LOCAL_모델이_있으면_true다() {
    service = newService();
    when(repository.findByActiveTrueOrderByDisplayOrderAsc()).thenReturn(java.util.List.of(
        fixture(1L, "claude-sonnet-4-6", LlmProvider.ANTHROPIC, true, false),
        fixture(2L, "qwen2.5-coder:7b", LlmProvider.LOCAL, true, false)));

    assertTrue(service.hasActiveLocalModel());
  }

  @Test
  void hasActiveLocalModel_활성_LOCAL_모델이_없으면_false다() {
    service = newService();
    when(repository.findByActiveTrueOrderByDisplayOrderAsc()).thenReturn(java.util.List.of(
        fixture(1L, "claude-sonnet-4-6", LlmProvider.ANTHROPIC, true, false)));

    assertFalse(service.hasActiveLocalModel());
  }

  // ===================== seedLocalFromEnvIfConfigured (REQ-002, 2026-09) =====================

  @Test
  void seedLocalFromEnvIfConfigured_설정값이_있고_미등록이면_LOCAL로_등록한다() {
    service = newService();
    when(repository.existsByModelKey("qwen2.5-coder:7b")).thenReturn(false);
    when(repository.count()).thenReturn(3L);

    service.seedLocalFromEnvIfConfigured("qwen2.5-coder:7b");

    ArgumentCaptor<LlmModelOption> captor = ArgumentCaptor.forClass(LlmModelOption.class);
    verify(repository).save(captor.capture());
    assertEquals("qwen2.5-coder:7b", captor.getValue().getModelKey());
    assertEquals(LlmProvider.LOCAL, captor.getValue().getProvider());
    // displayOrder는 기존 행 수(count) 뒤에 붙는다.
    assertEquals(3, captor.getValue().getDisplayOrder());
  }

  @Test
  void seedLocalFromEnvIfConfigured_이미_등록된_modelKey면_중복등록하지_않는다() {
    service = newService();
    when(repository.existsByModelKey("qwen2.5-coder:7b")).thenReturn(true);

    service.seedLocalFromEnvIfConfigured("qwen2.5-coder:7b");

    verify(repository, never()).save(any());
  }

  @Test
  void seedLocalFromEnvIfConfigured_값이_비어있으면_아무것도_하지_않는다() {
    service = newService();

    service.seedLocalFromEnvIfConfigured(null);
    service.seedLocalFromEnvIfConfigured("");
    service.seedLocalFromEnvIfConfigured("   ");

    verify(repository, never()).save(any());
    verify(repository, never()).existsByModelKey(any());
  }

  @Test
  void seedLocalFromEnvIfConfigured_등록된_모델은_failoverTarget이_false다() {
    // failover 대상 지정은 관리자의 명시적 행위여야 하므로 자동 시드가 이를 대신하면 안 된다(설계 §1.3).
    service = newService();
    when(repository.existsByModelKey("qwen2.5-coder:7b")).thenReturn(false);
    when(repository.count()).thenReturn(0L);

    service.seedLocalFromEnvIfConfigured("qwen2.5-coder:7b");

    ArgumentCaptor<LlmModelOption> captor = ArgumentCaptor.forClass(LlmModelOption.class);
    verify(repository).save(captor.capture());
    assertFalse(captor.getValue().isFailoverTarget());
  }
}
