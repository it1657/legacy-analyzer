package com.legacy.statistics;

import com.legacy.analysis.AnalysisHistory;
import com.legacy.api.usage.ApiUsage;
import com.legacy.auth.User;

/**
 * com.legacy.statistics 패키지 단위 테스트에서 공용으로 사용하는 픽스처 생성 헬퍼.
 * 리플렉션 없이 프로덕션 코드의 기존 생성자/setter만 사용한다
 * (02-design-v1 5.1절 근거, 공용 유틸로 승격하지 않고 이 패키지 내부에 독립적으로 유지).
 */
class StatisticsTestFixtures {

  private StatisticsTestFixtures() {
  }

  /** seq/active를 setter로 채운 User 인스턴스를 생성한다. */
  static User newUser(Long seq, String userId, String email, boolean active) {
    User user = new User(userId, email, "hash");
    user.setSeq(seq);
    user.setActive(active);
    return user;
  }

  /**
   * boxed 파라미터를 그대로 setter에 전달하는 AnalysisHistory 인스턴스를 생성한다.
   * 어떤 숫자 필드든 호출부에서 의도적으로 null을 넘길 수 있어야 하므로(NPE 관찰 케이스 구성용)
   * 이 팩토리는 null 방어 로직을 두지 않는다.
   */
  static AnalysisHistory newAnalysisHistory(Long id, Long userId, String status, Integer totalFiles,
      Long processingTimeMs, Long inputTokens, Long outputTokens) {
    AnalysisHistory history = new AnalysisHistory();
    history.setId(id);
    history.setUserId(userId);
    history.setStatus(status);
    history.setTotalFiles(totalFiles);
    history.setProcessingTimeMs(processingTimeMs);
    history.setInputTokens(inputTokens);
    history.setOutputTokens(outputTokens);
    return history;
  }

  /** 통계 합산에 필요한 필드(userId/requestSize/responseSize)만 채운 ApiUsage 인스턴스를 생성한다. */
  static ApiUsage newApiUsage(Long userId, long reqSize, long respSize) {
    return new ApiUsage(userId, "/api/test", "GET", reqSize, respSize, 200, 10L, "127.0.0.1");
  }
}
