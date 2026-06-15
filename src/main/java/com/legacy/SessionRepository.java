package com.legacy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * 분석 세션 데이터 저장소
 */
@Repository
public interface SessionRepository extends JpaRepository<SessionState, String> {
    List<SessionState> findByStatus(String status);
}
