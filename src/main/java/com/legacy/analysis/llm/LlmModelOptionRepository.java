package com.legacy.analysis.llm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LlmModelOptionRepository extends JpaRepository<LlmModelOption, Long> {

  // 관리자 화면 전체 목록 (활성/비활성 무관, 노출 순서 기준)
  List<LlmModelOption> findAllByOrderByDisplayOrderAsc();

  // 사용자 드롭다운에 노출할 목록 (활성만)
  List<LlmModelOption> findByActiveTrueOrderByDisplayOrderAsc();

  Optional<LlmModelOption> findByModelKey(String modelKey);

  boolean existsByModelKey(String modelKey);

  // failover 대상은 정확히 0개 또는 1개만 존재해야 한다(LlmModelOptionService가 강제)
  Optional<LlmModelOption> findByFailoverTargetTrueAndActiveTrue();

  long countByActiveTrue();
}
