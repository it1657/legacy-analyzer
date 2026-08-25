package com.legacy.analysis.llm;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 관리자가 CRUD로 관리하는 "선택 가능한 LLM 모델" 한 건.
 *
 * 기존에 {@code index.html}/{@code ClaudeServiceImpl.SUPPORTED_MODELS}에 하드코딩돼 있던
 * 모델 드롭다운 항목(하이쿠/소넷/오퍼스)을 DB로 옮기기 위해 신설했다(2026-08-21 설계 확정,
 * 근거: analyzer-plan docs/chat/etc/2026-08-21-llm-model-db-crud-and-credit-exhaustion-failover-design.md).
 *
 * failover 대상({@code failoverTarget=true})은 "우선순위 목록"이 아니라 관리자가 지정한 로컬 모델
 * 정확히 0개 또는 1개만 존재해야 한다 — 크레딧소진 컨펌 시 전환할 대상이 명확해야 하기 때문이다.
 * 이 단일성 제약과 "활성 모델 최소 1개 유지" 제약은 엔티티/DB 레벨이 아니라 {@link LlmModelOptionService}가
 * 트랜잭션 안에서 검증한다(관리자 화면 특성상 동시성 리스크가 낮다고 이미 판단됨 — 근거:
 * analyzer-plan docs/chat/etc/2026-08-21-llm-model-min-active-guard-and-handoff.md).
 */
@Entity
@Table(name = "llm_model_options")
public class LlmModelOption {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // 실제 LLM 호출 시 model 파라미터로 그대로 전달되는 식별자 (예: claude-sonnet-4-6, qwen3-32b)
  @Column(name = "model_key", nullable = false, length = 200)
  private String modelKey;

  // 사용자 드롭다운에 노출할 표시명 (예: "Claude Sonnet (권장 · $3/$15 per 1M)")
  @Column(name = "display_name", nullable = false, length = 200)
  private String displayName;

  @Enumerated(EnumType.STRING)
  @Column(name = "provider", nullable = false, length = 20)
  private LlmProvider provider;

  // 사용자 드롭다운에서의 노출 순서 (오름차순)
  @Column(name = "display_order", nullable = false)
  private int displayOrder = 0;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  // 크레딧소진 컨펌("자체 LLM으로 진행하시겠습니까?") 수락 시 전환할 대상으로 지정됐는지 여부
  @Column(name = "is_failover_target", nullable = false)
  private boolean failoverTarget = false;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  public LlmModelOption() {
  }

  public LlmModelOption(String modelKey, String displayName, LlmProvider provider, int displayOrder) {
    this.modelKey = modelKey;
    this.displayName = displayName;
    this.provider = provider;
    this.displayOrder = displayOrder;
  }

  @PrePersist
  protected void onCreate() {
    LocalDateTime now = LocalDateTime.now();
    if (createdAt == null) createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }

  // Getter/Setter
  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }

  public String getModelKey() { return modelKey; }
  public void setModelKey(String modelKey) { this.modelKey = modelKey; }

  public String getDisplayName() { return displayName; }
  public void setDisplayName(String displayName) { this.displayName = displayName; }

  public LlmProvider getProvider() { return provider; }
  public void setProvider(LlmProvider provider) { this.provider = provider; }

  public int getDisplayOrder() { return displayOrder; }
  public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }

  public boolean isActive() { return active; }
  public void setActive(boolean active) { this.active = active; }

  public boolean isFailoverTarget() { return failoverTarget; }
  public void setFailoverTarget(boolean failoverTarget) { this.failoverTarget = failoverTarget; }

  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
