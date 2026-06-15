package com.legacy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AnalysisHistoryRepository extends JpaRepository<AnalysisHistory, Long> {
  List<AnalysisHistory> findByUserId(Long userId);

  List<AnalysisHistory> findByUserIdOrderByCreatedAtDesc(Long userId);

  List<AnalysisHistory> findByStatus(String status);

  AnalysisHistory findBySessionId(String sessionId);
}
