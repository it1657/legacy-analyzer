package com.legacy.auth;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

/**
 * com.legacy.auth 패키지 단위 테스트에서 공용으로 사용하는 픽스처 생성 헬퍼.
 * newJwtTokenProvider를 제외한 모든 메서드는 프로덕션 코드의 생성자/getter/setter만 사용한다.
 */
class AuthTestFixtures {

  private AuthTestFixtures() {
  }

  /** 임의 description을 가진 Role 인스턴스를 생성한다. */
  static Role newRole(String name) {
    return new Role(name, name + " 역할");
  }

  /** seq/active/roles를 setter로 채운 User 인스턴스를 생성한다. */
  static User newUser(Long seq, String userId, String email, boolean active, Set<Role> roles) {
    User user = new User(userId, email, "hash");
    user.setSeq(seq);
    user.setActive(active);
    user.setRoles(roles);
    return user;
  }

  /**
   * secretKey/expirationMs를 리플렉션으로 주입한 JwtTokenProvider 인스턴스를 생성한다.
   * 두 필드는 @Value로 주입되어 Spring context 없이는 setter/생성자로 채울 수 없으므로,
   * 이 메서드에 한해 ReflectionTestUtils 사용을 예외적으로 허용한다.
   */
  static JwtTokenProvider newJwtTokenProvider(String secretKey, long expirationMs) {
    JwtTokenProvider provider = new JwtTokenProvider();
    ReflectionTestUtils.setField(provider, "secretKey", secretKey);
    ReflectionTestUtils.setField(provider, "expirationMs", expirationMs);
    return provider;
  }

  /** SecurityContext에 인증된 User principal을 세팅한다. */
  static void setAuthenticatedUser(User user) {
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
  }
}
