package com.legacy.api.usage;

import com.legacy.auth.User;

import java.time.LocalDateTime;

/**
 * com.legacy.api.usage 패키지 단위 테스트에서 공용으로 사용하는 픽스처 생성 헬퍼.
 * 리플렉션 없이 프로덕션 코드의 기존 생성자/setter만 사용한다
 * (02-design-v1 4.1절 근거, 공용 유틸로 승격하지 않고 이 패키지 내부에 독립적으로 유지).
 */
class ApiUsageTestFixtures {

  private ApiUsageTestFixtures() {
  }

  /** seq를 setter로 채운 User 인스턴스를 생성한다. */
  static User newUser(Long seq, String userId, String email) {
    User user = new User(userId, email, "hash");
    user.setSeq(seq);
    return user;
  }

  /**
   * 8-arg 생성자로 만든 뒤 id/timestamp를 setter로 덮어쓴 ApiUsage 인스턴스를 생성한다.
   * (생성자가 timestamp를 now로 자동 세팅하므로 테스트에서 시각을 고정하려면 setter가 필요하다.)
   */
  static ApiUsage newApiUsage(Long id, Long userId, String endpoint, String method, long reqSize,
      long respSize, int statusCode, long execTimeMs, String ip, LocalDateTime timestamp) {
    ApiUsage usage = new ApiUsage(userId, endpoint, method, reqSize, respSize, statusCode,
        execTimeMs, ip);
    usage.setId(id);
    usage.setTimestamp(timestamp);
    return usage;
  }
}
