package com.legacy;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtTokenProvider {

  private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

  @Value("${jwt.secret-key:your-secret-key-must-be-at-least-256-bits-long-for-hs256-algorithm-security}")
  private String secretKey;

  @Value("${jwt.expiration-ms:900000}")
  private long expirationMs;

  // JWT 토큰 생성
  public String generateToken(Authentication authentication) {
    User user = (User) authentication.getPrincipal();
    return generateToken(user.getUsername(), user.getId());
  }

  public String generateToken(String username, Long userId) {
    SecretKey key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));

    return Jwts.builder()
        .subject(username)
        .claim("userId", userId)
        .issuedAt(new Date())
        .expiration(new Date(System.currentTimeMillis() + expirationMs))
        .signWith(key, SignatureAlgorithm.HS256)
        .compact();
  }

  // 토큰에서 사용자명 추출
  public String getUsernameFromToken(String token) {
    try {
      SecretKey key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
      Claims claims = Jwts.parser()
          .verifyWith(key)
          .build()
          .parseSignedClaims(token)
          .getPayload();
      return claims.getSubject();
    } catch (Exception e) {
      log.error("[JWT] 토큰 파싱 실패: {}", e.getMessage());
      return null;
    }
  }

  // 토큰에서 userId 추출
  public Long getUserIdFromToken(String token) {
    try {
      SecretKey key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
      Claims claims = Jwts.parser()
          .verifyWith(key)
          .build()
          .parseSignedClaims(token)
          .getPayload();
      return claims.get("userId", Long.class);
    } catch (Exception e) {
      log.error("[JWT] userId 추출 실패: {}", e.getMessage());
      return null;
    }
  }

  // 토큰 유효성 검사
  public boolean validateToken(String token) {
    try {
      SecretKey key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
      Jwts.parser()
          .verifyWith(key)
          .build()
          .parseSignedClaims(token);
      return true;
    } catch (Exception e) {
      log.error("[JWT] 토큰 검증 실패: {}", e.getMessage());
      return false;
    }
  }

  // 토큰에서 Claims 추출
  public Claims getClaimsFromToken(String token) {
    try {
      SecretKey key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
      return Jwts.parser()
          .verifyWith(key)
          .build()
          .parseSignedClaims(token)
          .getPayload();
    } catch (Exception e) {
      log.error("[JWT] Claims 추출 실패: {}", e.getMessage());
      return null;
    }
  }
}
