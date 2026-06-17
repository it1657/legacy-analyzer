package com.legacy.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
public interface AnalysisHistoryRepository extends JpaRepository<AnalysisHistory, Long> {
  List<AnalysisHistory> findByUserId(Long userId);

  List<AnalysisHistory> findByUserIdOrderByCreatedAtDesc(Long userId);

  List<AnalysisHistory> findByStatus(String status);

  AnalysisHistory findBySessionId(String sessionId);

  // 토큰 통계 쿼리
  @Query("SELECT SUM(ah.inputTokens) FROM AnalysisHistory ah WHERE ah.userId = :userId")
  Long getTotalInputTokensByUser(@Param("userId") Long userId);

  @Query("SELECT SUM(ah.outputTokens) FROM AnalysisHistory ah WHERE ah.userId = :userId")
  Long getTotalOutputTokensByUser(@Param("userId") Long userId);

  @Query("SELECT SUM(ah.totalTokens) FROM AnalysisHistory ah WHERE ah.userId = :userId")
  Long getTotalTokensByUser(@Param("userId") Long userId);

  @Query("SELECT SUM(ah.estimatedCost) FROM AnalysisHistory ah WHERE ah.userId = :userId")
  Double getTotalCostByUser(@Param("userId") Long userId);

  @Query("SELECT SUM(ah.inputTokens) FROM AnalysisHistory ah")
  Long getTotalInputTokensSystem();

  @Query("SELECT SUM(ah.outputTokens) FROM AnalysisHistory ah")
  Long getTotalOutputTokensSystem();

  @Query("SELECT SUM(ah.totalTokens) FROM AnalysisHistory ah")
  Long getTotalTokensSystem();

  @Query("SELECT SUM(ah.estimatedCost) FROM AnalysisHistory ah")
  Double getTotalCostSystem();

  @Query("SELECT ah.modelName, SUM(ah.totalTokens) FROM AnalysisHistory ah GROUP BY ah.modelName")
  List<Object[]> getTokensByModel();

  @Query("SELECT ah.modelName, SUM(ah.estimatedCost) FROM AnalysisHistory ah GROUP BY ah.modelName")
  List<Object[]> getCostByModel();

  @Query("SELECT ah FROM AnalysisHistory ah WHERE ah.createdAt >= :startDate AND ah.createdAt < :endDate ORDER BY ah.createdAt DESC")
  List<AnalysisHistory> findByDateRange(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);
}
