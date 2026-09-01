package com.legacy.api.monitoring;

import com.legacy.analysis.SessionState;
import com.legacy.auth.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * com.legacy.api.monitoring 패키지 단위 테스트에서 공용으로 사용하는 픽스처 생성 헬퍼.
 * 리플렉션 없이 프로덕션 코드의 기존 생성자/setter만 사용한다
 * (02-design-v1 6.1절 근거, 공용 유틸로 승격하지 않고 이 패키지 내부에 독립적으로 유지).
 */
class MonitoringTestFixtures {

  private MonitoringTestFixtures() {
  }

  /** 소유자 판별에 쓰이는 userId만 채운 User 인스턴스를 생성한다. */
  static User newUser(String userId) {
    return new User(userId, userId + "@example.com", "hash");
  }

  /**
   * getPrincipal()/getAuthorities()를 stub한 Authentication mock을 생성한다.
   * ROLE_ADMIN 포함 여부는 hasAdminRole로 제어한다(principalOrNull에 null도 전달 가능).
   */
  static Authentication newAuthentication(User principalOrNull, boolean hasAdminRole) {
    Authentication authentication = mock(Authentication.class);
    when(authentication.getPrincipal()).thenReturn(principalOrNull);
    List<SimpleGrantedAuthority> authorities = hasAdminRole
        ? Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"))
        : Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
    org.mockito.Mockito.doReturn(authorities).when(authentication).getAuthorities();
    return authentication;
  }

  /** 소유자 판별에 사용되는 username을 채운 SessionState 인스턴스를 생성한다. */
  static SessionState newSessionState(String sessionId, String username) {
    SessionState session = new SessionState(sessionId, "src", "out");
    session.setUsername(username);
    return session;
  }
}
