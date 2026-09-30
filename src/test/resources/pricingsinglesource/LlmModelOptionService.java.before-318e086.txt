package com.legacy.analysis.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * "선택 가능한 LLM 모델" 목록({@link LlmModelOption})의 CRUD + 도메인 규칙을 담당하는 Service 계층.
 *
 * 기존 {@code com.legacy.admin.AdminController}는 Service 계층 없이 Repository를 직접 호출하는
 * 관행이었으나, 이번 기능은 "활성 모델 최소 1개 유지"/"failover 대상 단일성"처럼 여러 행을 함께
 * 봐야 하는 검증이 있어 신설했다(사람 확정 사항, 근거: analyzer-plan
 * docs/chat/etc/2026-08-21-llm-model-db-crud-and-credit-exhaustion-failover-design.md §2-3).
 *
 * 패키지 위치를 {@code com.legacy.analysis.llm}(= analysis 쪽)에 둔 이유: 이 프로젝트는
 * {@code com.legacy.admin → com.legacy.analysis} 방향의 의존만 허용하고 역방향은 금지한다.
 * 관리자 전용 CRUD 컨트롤러({@code com.legacy.admin.LlmModelAdminController})는 admin 패키지에
 * 두되, 실제 도메인 로직은 이 Service가 analysis 쪽에 남아 기존 의존 방향을 그대로 지킨다.
 *
 * 동시성: "활성 모델 0개 방지"/"failover 대상 2개 이상 방지" 검증은 각 메서드 안에서 트랜잭션을
 * 열고 그 안에서 카운트를 확인한 뒤 판단한다. 낙관적 잠금(@Version)은 쓰지 않는다 — 관리자
 * 화면이라 동시 요청 경합 가능성이 낮다고 이미 판단됨(근거: 위 문서의 후속 결정,
 * analyzer-plan docs/chat/etc/2026-08-21-llm-model-min-active-guard-and-handoff.md).
 */
@Service
public class LlmModelOptionService {

  private static final Logger log = LoggerFactory.getLogger(LlmModelOptionService.class);

  /** 활성 모델을 전부 비활성화/삭제하려 할 때 공통으로 쓰는 안내 메시지 (관리자 화면에 그대로 노출됨). */
  public static final String MIN_ACTIVE_GUARD_MESSAGE = "최소 1개 모델은 활성 상태여야 합니다.";

  private final LlmModelOptionRepository repository;
  // REQ-003(2026-09): 관리자 수동 등록 시 Ollama 실제 설치 여부를 하드 검증하는 데 쓴다.
  // 아래 테스트 편의용 1-인자 생성자로 만들어진 인스턴스에서는 null이며, 그 경우 검증을 건너뛴다.
  private final OllamaModelDiscoveryClient ollamaModelDiscoveryClient;

  @Autowired
  public LlmModelOptionService(LlmModelOptionRepository repository,
      OllamaModelDiscoveryClient ollamaModelDiscoveryClient) {
    this.repository = repository;
    this.ollamaModelDiscoveryClient = ollamaModelDiscoveryClient;
  }

  /**
   * 기존 단위 테스트(Ollama 검증과 무관한 케이스)가 그대로 쓸 수 있도록 유지하는 테스트 편의용
   * 오버로드. {@code ollamaModelDiscoveryClient=null}이면 {@link #createWithOllamaValidation}이
   * 검증을 건너뛴다("조회 실패"와 동일 취급). 프로덕션에서는 Spring이 위 2-인자
   * 생성자({@code @Autowired})만 사용한다 — {@code CodeContentRagService}에서 이미 쓰인
   * "운영용 @Autowired 생성자 + 테스트 편의 생성자 병존" 패턴을 그대로 따른다.
   */
  public LlmModelOptionService(LlmModelOptionRepository repository) {
    this(repository, null);
  }

  /** 관리자 화면용 전체 목록 (활성/비활성 무관, 노출 순서 기준). */
  @Transactional(readOnly = true)
  public List<LlmModelOption> listAll() {
    return repository.findAllByOrderByDisplayOrderAsc();
  }

  /** 사용자 드롭다운(GET /api/config/llm-models)에 노출할 목록 (활성만). */
  @Transactional(readOnly = true)
  public List<LlmModelOption> listActive() {
    return repository.findByActiveTrueOrderByDisplayOrderAsc();
  }

  /**
   * 활성 목록에 LOCAL provider 모델이 1건이라도 있는지 확인한다 — GET /api/config/llm-provider의
   * {@code availableProviders} 계산에 사용한다(REQ-001, 2026-09). 테이블 규모가 작아 별도 리포지토리
   * 쿼리를 추가하지 않고 {@link #listActive()} 결과를 스트림으로 걸러낸다.
   */
  @Transactional(readOnly = true)
  public boolean hasActiveLocalModel() {
    return listActive().stream().anyMatch(o -> o.getProvider() == LlmProvider.LOCAL);
  }

  @Transactional(readOnly = true)
  public Optional<LlmModelOption> findByModelKey(String modelKey) {
    if (modelKey == null || modelKey.isBlank()) return Optional.empty();
    return repository.findByModelKey(modelKey);
  }

  /** modelKey가 현재 활성 상태의 등록된 모델인지 확인한다 — ClaudeServiceImpl.setModel()의 검증에 사용. */
  @Transactional(readOnly = true)
  public boolean isActiveModel(String modelKey) {
    return findByModelKey(modelKey).map(LlmModelOption::isActive).orElse(false);
  }

  /** 현재 지정된 failover 대상(활성 상태의 로컬 모델)을 조회한다 — 크레딧소진 컨펌 흐름에서 사용. */
  @Transactional(readOnly = true)
  public Optional<LlmModelOption> getActiveFailoverTarget() {
    return repository.findByFailoverTargetTrueAndActiveTrue();
  }

  @Transactional
  public LlmModelOption create(String modelKey, String displayName, LlmProvider provider, int displayOrder) {
    if (modelKey == null || modelKey.isBlank()) {
      throw new IllegalArgumentException("모델 키는 비어 있을 수 없습니다.");
    }
    if (displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException("표시명은 비어 있을 수 없습니다.");
    }
    if (provider == null) {
      throw new IllegalArgumentException("provider는 비어 있을 수 없습니다.");
    }
    if (repository.existsByModelKey(modelKey)) {
      throw new IllegalStateException("이미 존재하는 모델 키입니다: " + modelKey);
    }
    LlmModelOption option = new LlmModelOption(modelKey.trim(), displayName.trim(), provider, displayOrder);
    LlmModelOption saved = repository.save(option);
    log.info("[LLM 모델 등록] modelKey={}, provider={}", saved.getModelKey(), saved.getProvider());
    return saved;
  }

  /**
   * 관리자 등록 API(POST /api/admin/llm-models) 전용 — LOCAL provider 등록 시 Ollama 실제 설치
   * 여부를 하드 검증한다(2026-09 게이트1 사람 결정: 절충안).
   * <ul>
   *   <li>{@code provider != LOCAL}(예: ANTHROPIC): 검증 없이 기존 {@link #create}와 동일하게 통과
   *       — discovery 호출 자체를 하지 않는다.</li>
   *   <li>{@code provider == LOCAL} 이고 조회 성공인데 modelKey가 목록에 없음: 등록 거부
   *       ({@link IllegalStateException}).</li>
   *   <li>{@code provider == LOCAL} 이고 조회 실패(비Ollama 백엔드 가능성 포함): 자유 입력 허용,
   *       경고 로그만 남기고 통과.</li>
   *   <li>{@code ollamaModelDiscoveryClient}가 null(테스트 편의 생성자 경유): "조회 실패"와 동일 취급.</li>
   * </ul>
   * 기존 {@link #create}는 의도적으로 그대로 둔다 — {@link #seedDefaultsIfEmpty()}/
   * {@link #seedLocalFromEnvIfConfigured(String)}가 기동 시점에 그 메서드를 호출하므로, 거기에
   * 하드 검증을 끼워 넣으면 애플리케이션 기동 자체가 Ollama 가용성에 의존하게 된다(설계 §3.2-bis).
   */
  @Transactional
  public LlmModelOption createWithOllamaValidation(String modelKey, String displayName, LlmProvider provider,
      int displayOrder) {
    if (provider == LlmProvider.LOCAL && modelKey != null && !modelKey.isBlank()
        && ollamaModelDiscoveryClient != null) {
      Optional<List<String>> discovered = ollamaModelDiscoveryClient.listInstalledModels();
      if (discovered.isPresent() && !discovered.get().contains(modelKey.trim())) {
        throw new IllegalStateException(
            "Ollama에 설치되지 않은 모델입니다: " + modelKey.trim() + " (조회된 설치 모델 목록에 없음)");
      }
      if (discovered.isEmpty()) {
        log.warn("[LLM 모델 등록] Ollama 조회 실패로 검증을 건너뛰고 자유 입력을 허용합니다. modelKey={}", modelKey);
      }
    }
    return create(modelKey, displayName, provider, displayOrder);
  }

  /** 표시명/노출순서만 수정한다 — modelKey/provider는 생성 후 불변(변경이 필요하면 삭제 후 재등록). */
  @Transactional
  public LlmModelOption update(Long id, String displayName, int displayOrder) {
    LlmModelOption option = getOrThrow(id);
    if (displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException("표시명은 비어 있을 수 없습니다.");
    }
    option.setDisplayName(displayName.trim());
    option.setDisplayOrder(displayOrder);
    return repository.save(option);
  }

  /**
   * 활성/비활성 전환. false로 전환하는 요청이 "활성 모델 0개"를 만들면 거부한다
   * (사람 확정 사항 — 근거: 클래스 상단 주석의 min-active-guard 문서).
   */
  @Transactional
  public LlmModelOption setActive(Long id, boolean active) {
    LlmModelOption option = getOrThrow(id);
    if (!active && option.isActive()) {
      long activeCount = repository.countByActiveTrue();
      if (activeCount <= 1) {
        throw new IllegalStateException(MIN_ACTIVE_GUARD_MESSAGE);
      }
    }
    option.setActive(active);
    return repository.save(option);
  }

  /**
   * 삭제. 대상이 "현재 유일한 활성 모델"이면 거부한다(활성 모델 0개 방지, setActive와 동일한 규칙).
   */
  @Transactional
  public void delete(Long id) {
    LlmModelOption option = getOrThrow(id);
    if (option.isActive()) {
      long activeCount = repository.countByActiveTrue();
      if (activeCount <= 1) {
        throw new IllegalStateException(MIN_ACTIVE_GUARD_MESSAGE);
      }
    }
    repository.deleteById(id);
    log.info("[LLM 모델 삭제] modelKey={}", option.getModelKey());
  }

  /**
   * failover 대상 지정/해제. 대상은 "우선순위 목록"이 아니라 정확히 0개 또는 1개만 존재해야 하므로,
   * 지정 시 기존에 지정돼 있던 다른 행이 있으면 함께 해제한다. 비활성 모델이나 Anthropic 모델은
   * failover 대상(= 크레딧소진 시 전환할 "자체 LLM")으로 지정할 수 없다.
   */
  @Transactional
  public LlmModelOption setFailoverTarget(Long id, boolean isTarget) {
    LlmModelOption option = getOrThrow(id);
    if (isTarget) {
      if (!option.isActive()) {
        throw new IllegalStateException("비활성 모델은 failover 대상으로 지정할 수 없습니다.");
      }
      if (option.getProvider() != LlmProvider.LOCAL) {
        throw new IllegalStateException("failover 대상은 로컬(자체 호스팅) 모델만 지정할 수 있습니다.");
      }
      repository.findByFailoverTargetTrueAndActiveTrue()
          .filter(existing -> !existing.getId().equals(id))
          .ifPresent(existing -> {
            existing.setFailoverTarget(false);
            repository.save(existing);
          });
    }
    option.setFailoverTarget(isTarget);
    return repository.save(option);
  }

  /**
   * 애플리케이션 기동 시 테이블이 비어 있으면 기존에 하드코딩돼 있던 3개 Claude 모델을 그대로
   * 시드한다(Phase 6). 이미 데이터가 있으면 아무 것도 하지 않는다 — 재기동 때마다 중복 삽입되지 않게.
   */
  @Transactional
  public void seedDefaultsIfEmpty() {
    if (repository.count() > 0) return;
    create("claude-sonnet-4-6", "Claude Sonnet (권장 · $3/$15 per 1M)", LlmProvider.ANTHROPIC, 0);
    create("claude-opus-4-8", "Claude Opus (고품질 · $15/$75 per 1M)", LlmProvider.ANTHROPIC, 1);
    create("claude-haiku-4-5-20251001", "Claude Haiku (빠름/저비용 · $0.80/$4 per 1M)", LlmProvider.ANTHROPIC, 2);
    log.info("[LLM 모델 기본값 시드 완료] 기존 하드코딩 3종(sonnet/opus/haiku) 삽입");
  }

  /**
   * llm.local.model이 설정돼 있고 아직 DB에 그 modelKey가 없으면 LOCAL provider로 1건 자동
   * 등록한다(REQ-002, 2026-09). {@link #seedDefaultsIfEmpty()}와 달리 "테이블이 비었는지"가 아니라
   * "이 modelKey가 이미 있는지"로 멱등성을 판단한다 — Claude 기본 3종이 이미 시드된 배포에서도
   * 로컬 모델을 추가로 시드할 수 있어야 하기 때문이다. failoverTarget은 절대 자동으로 true로
   * 설정하지 않는다 — failover 대상 지정은 관리자가 명시적으로 하는 별개의 행위다.
   *
   * 이 메서드는 기존 {@link #create}를 그대로 호출한다 — Ollama 하드 검증이 붙은
   * {@code createWithOllamaValidation()}이 아니다. 애플리케이션 기동 시점에는 Ollama가 아직
   * 준비되지 않았을 수 있어, 기동 경로에 하드 검증을 걸면 기동 자체가 Ollama 가용성에 의존하게 된다.
   */
  @Transactional
  public void seedLocalFromEnvIfConfigured(String llmLocalModel) {
    if (llmLocalModel == null || llmLocalModel.isBlank()) return;
    String modelKey = llmLocalModel.trim();
    if (repository.existsByModelKey(modelKey)) return;
    int nextOrder = (int) repository.count();
    create(modelKey, "로컬 모델: " + modelKey + " (무료 · 자체 호스팅)", LlmProvider.LOCAL, nextOrder);
    log.info("[LLM 로컬 모델 env 자동 시드] modelKey={}", modelKey);
  }

  private LlmModelOption getOrThrow(Long id) {
    return repository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 모델입니다: id=" + id));
  }
}
