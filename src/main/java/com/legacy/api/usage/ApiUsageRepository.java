package com.legacy.api.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
public interface ApiUsageRepository extends JpaRepository<ApiUsage, Long> {

  List<ApiUsage> findByUserId(Long userId);

  List<ApiUsage> findByUserIdAndTimestampBetween(Long userId, LocalDateTime startTime,
      LocalDateTime endTime);

  List<ApiUsage> findByTimestampBetween(LocalDateTime startTime, LocalDateTime endTime);

  List<ApiUsage> findByUserIdOrderByTimestampDesc(Long userId);

  // 사용자별 API 호출 통계
  @Query(value = "SELECT user_id, COUNT(*) as count, SUM(request_size) as totalRequest, "
      + "SUM(response_size) as totalResponse FROM api_usage "
      + "WHERE timestamp >= :startTime AND timestamp <= :endTime "
      + "GROUP BY user_id ORDER BY count DESC", nativeQuery = true)
  List<Map<String, Object>> getUserApiStats(@Param("startTime") LocalDateTime startTime,
      @Param("endTime") LocalDateTime endTime);

  // 엔드포인트별 호출 통계
  @Query(value = "SELECT endpoint, method, COUNT(*) as count, AVG(execution_time_ms) as avgTime "
      + "FROM api_usage WHERE timestamp >= :startTime AND timestamp <= :endTime "
      + "GROUP BY endpoint, method ORDER BY count DESC", nativeQuery = true)
  List<Map<String, Object>> getEndpointStats(@Param("startTime") LocalDateTime startTime,
      @Param("endTime") LocalDateTime endTime);

  // 사용자별 일일 통계
  @Query(value = "SELECT DATE(timestamp) as date, user_id, COUNT(*) as count, "
      + "SUM(request_size) as totalRequest, SUM(response_size) as totalResponse "
      + "FROM api_usage WHERE user_id = :userId AND timestamp >= :startTime "
      + "AND timestamp <= :endTime GROUP BY DATE(timestamp), user_id "
      + "ORDER BY date DESC", nativeQuery = true)
  List<Map<String, Object>> getUserDailyStats(@Param("userId") Long userId,
      @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);
}
