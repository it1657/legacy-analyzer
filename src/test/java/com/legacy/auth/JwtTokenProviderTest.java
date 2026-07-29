package com.legacy.auth;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JwtTokenProvider의 토큰 생성/검증/파싱 로직을 Spring context 없이 순수 단위 테스트한다.
 * secretKey/expirationMs는 AuthTestFixtures.newJwtTokenProvider(...)를 통해 ReflectionTestUtils로 주입한다.
 * 아래 SECRET은 이 테스트에서만 사용하는 임의 256bit 이상 문자열이며, 운영 시크릿 값을 재사용하지 않는다.
 */
class JwtTokenProviderTest {

  private static final String SECRET =
      "unit-test-only-jwt-secret-key-do-not-use-in-production-0123456789abcdef";
  private static final String OTHER_SECRET =
      "another-unit-test-only-jwt-secret-key-0123456789abcdef0123456789abcdef";
  private static final long EXPIRATION_MS = 900_000L;

  private JwtTokenProvider jwtTokenProvider;

  @BeforeEach
  void setUp() {
    jwtTokenProvider = AuthTestFixtures.newJwtTokenProvider(SECRET, EXPIRATION_MS);
  }

  @Test
  void userId와_seq로_토큰을_생성하면_검증에_성공하고_추출값이_일치한다() {
    String token = jwtTokenProvider.generateToken("hong", 10L);

    assertTrue(jwtTokenProvider.validateToken(token));
    assertEquals("hong", jwtTokenProvider.getUsernameFromToken(token));
    assertEquals(10L, jwtTokenProvider.getSeqFromToken(token));
  }

  @Test
  void Authentication으로_토큰을_생성하면_principal_User의_userId_seq가_추출된다() {
    User user = AuthTestFixtures.newUser(20L, "kim", "kim@test.com", true, new HashSet<>());
    Authentication authentication = mock(Authentication.class);
    when(authentication.getPrincipal()).thenReturn(user);

    String token = jwtTokenProvider.generateToken(authentication);

    assertEquals(user.getUserId(), jwtTokenProvider.getUsernameFromToken(token));
    assertEquals(user.getSeq(), jwtTokenProvider.getSeqFromToken(token));
  }

  @Test
  void 형식이_깨진_문자열은_validateToken이_false를_반환하고_예외가_전파되지_않는다() {
    assertFalse(jwtTokenProvider.validateToken("invalid.token.value"));
  }

  @Test
  void 다른_secret으로_서명된_토큰은_validateToken이_false를_반환한다() {
    JwtTokenProvider otherProvider = AuthTestFixtures.newJwtTokenProvider(OTHER_SECRET, EXPIRATION_MS);
    String token = otherProvider.generateToken("hong", 10L);

    assertFalse(jwtTokenProvider.validateToken(token));
  }

  @Test
  void 즉시_만료되도록_구성한_provider의_토큰은_validateToken이_false를_반환한다() {
    JwtTokenProvider expiredProvider = AuthTestFixtures.newJwtTokenProvider(SECRET, -1000L);
    String token = expiredProvider.generateToken("hong", 10L);

    assertFalse(jwtTokenProvider.validateToken(token));
  }

  @Test
  void 유효하지_않은_토큰이면_getUsernameFromToken이_null을_반환한다() {
    assertNull(jwtTokenProvider.getUsernameFromToken("invalid.token.value"));
  }

  @Test
  void 유효하지_않은_토큰이면_getSeqFromToken이_null을_반환한다() {
    assertNull(jwtTokenProvider.getSeqFromToken("invalid.token.value"));
  }

  @Test
  void 유효하지_않은_토큰이면_getClaimsFromToken이_null을_반환한다() {
    assertNull(jwtTokenProvider.getClaimsFromToken("invalid.token.value"));
  }

  @Test
  void 정상_토큰의_getClaimsFromToken은_subject와_seq_claim이_일치한다() {
    String token = jwtTokenProvider.generateToken("hong", 10L);

    Claims claims = jwtTokenProvider.getClaimsFromToken(token);

    assertEquals("hong", claims.getSubject());
    assertEquals(10L, claims.get("seq", Long.class));
  }
}
