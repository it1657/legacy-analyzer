package com.legacy.analysis.llm;

import org.junit.jupiter.api.Test;

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
}
