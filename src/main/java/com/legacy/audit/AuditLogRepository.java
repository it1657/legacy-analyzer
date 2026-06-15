package com.legacy.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

  List<AuditLog> findByUserId(Long userId);

  List<AuditLog> findByAction(String action);

  List<AuditLog> findByTarget(String target);

  List<AuditLog> findByUserIdAndTimestampBetween(Long userId, LocalDateTime startTime,
      LocalDateTime endTime);

  List<AuditLog> findByTimestampBetween(LocalDateTime startTime, LocalDateTime endTime);

  List<AuditLog> findByTargetIdAndTarget(Long targetId, String target);

  List<AuditLog> findByOrderByTimestampDesc();

  // 최근 감사 로그 조회
  @Query(value = "SELECT * FROM audit_logs ORDER BY timestamp DESC LIMIT :limit",
      nativeQuery = true)
  List<AuditLog> findRecentLogs(@Param("limit") int limit);

  // 사용자별 활동 조회
  @Query(value = "SELECT * FROM audit_logs WHERE user_id = :userId ORDER BY timestamp DESC LIMIT :limit",
      nativeQuery = true)
  List<AuditLog> findUserActivityLogs(@Param("userId") Long userId,
      @Param("limit") int limit);

  // 액션별 통계
  @Query(value = "SELECT action, COUNT(*) as count FROM audit_logs "
      + "WHERE timestamp >= :startTime AND timestamp <= :endTime "
      + "GROUP BY action ORDER BY count DESC", nativeQuery = true)
  List<Object[]> getActionStatistics(@Param("startTime") LocalDateTime startTime,
      @Param("endTime") LocalDateTime endTime);
}
