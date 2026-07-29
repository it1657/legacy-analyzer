package com.legacy.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JwtAuthenticationFilter.doFilterInternal의 토큰 추출/인증 설정 분기를 검증한다.
 * 모든 시나리오에서 필터 체인이 절대 끊기지 않는지(doFilter 1회 호출) 공통으로 확인한다.
 */
class JwtAuthenticationFilterTest {

  private JwtTokenProvider jwtTokenProvider;
  private org.springframework.security.core.userdetails.UserDetailsService userDetailsService;
  private HttpServletRequest request;
  private HttpServletResponse response;
  private FilterChain filterChain;
  private JwtAuthenticationFilter jwtAuthenticationFilter;

  @BeforeEach
  void setUp() {
    SecurityContextHolder.clearContext();
    jwtTokenProvider = mock(JwtTokenProvider.class);
    userDetailsService = mock(org.springframework.security.core.userdetails.UserDetailsService.class);
    request = mock(HttpServletRequest.class);
    response = mock(HttpServletResponse.class);
    filterChain = mock(FilterChain.class);
    jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtTokenProvider, userDetailsService);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void Authorization_헤더의_유효한_Bearer_토큰으로_인증_컨텍스트가_설정된다() throws Exception {
    String token = "valid-bearer-token";
    when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
    when(jwtTokenProvider.validateToken(token)).thenReturn(true);
    when(jwtTokenProvider.getUsernameFromToken(token)).thenReturn("hong");

    UserDetails userDetails = mock(UserDetails.class);
    List<GrantedAuthority> authorities = Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
    doReturn(authorities).when(userDetails).getAuthorities();
    when(userDetailsService.loadUserByUsername("hong")).thenReturn(userDetails);

    jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    assertSame(userDetails, authentication.getPrincipal());
    assertEquals(authorities, authentication.getAuthorities());
    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void 헤더와_쿼리파라미터_모두_없으면_인증_컨텍스트가_설정되지_않는다() throws Exception {
    when(request.getHeader("Authorization")).thenReturn(null);
    when(request.getParameter("token")).thenReturn(null);
    when(request.getRequestURI()).thenReturn("/api/analysis/list");

    jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void 헤더가_없고_쿼리파라미터_token에_유효한_토큰이_있으면_해당_토큰으로_인증된다() throws Exception {
    String token = "sse-query-token";
    when(request.getHeader("Authorization")).thenReturn(null);
    when(request.getParameter("token")).thenReturn(token);
    when(jwtTokenProvider.validateToken(token)).thenReturn(true);
    when(jwtTokenProvider.getUsernameFromToken(token)).thenReturn("sse-user");

    UserDetails userDetails = mock(UserDetails.class);
    doReturn(Collections.emptyList()).when(userDetails).getAuthorities();
    when(userDetailsService.loadUserByUsername("sse-user")).thenReturn(userDetails);

    jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    assertSame(userDetails, authentication.getPrincipal());
    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void 쿼리파라미터_token이_문자열_null이면_토큰으로_취급되지_않는다() throws Exception {
    when(request.getHeader("Authorization")).thenReturn(null);
    when(request.getParameter("token")).thenReturn("null");
    when(request.getRequestURI()).thenReturn("/api/analysis/list");

    jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    verify(jwtTokenProvider, never()).validateToken(any());
    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void validateToken이_false이면_인증_컨텍스트가_설정되지_않는다() throws Exception {
    String token = "invalid-token";
    when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
    when(jwtTokenProvider.validateToken(token)).thenReturn(false);

    jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void validateToken이_true이지만_username이_null이면_인증_컨텍스트가_설정되지_않는다() throws Exception {
    String token = "valid-but-no-username";
    when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
    when(jwtTokenProvider.validateToken(token)).thenReturn(true);
    when(jwtTokenProvider.getUsernameFromToken(token)).thenReturn(null);

    jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void loadUserByUsername이_UsernameNotFoundException을_던지면_예외가_전파되지_않고_인증도_설정되지_않는다() throws Exception {
    String token = "valid-token-unknown-user";
    when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
    when(jwtTokenProvider.validateToken(token)).thenReturn(true);
    when(jwtTokenProvider.getUsernameFromToken(token)).thenReturn("ghost");
    when(userDetailsService.loadUserByUsername("ghost"))
        .thenThrow(new UsernameNotFoundException("사용자를 찾을 수 없습니다: ghost"));

    assertDoesNotThrow(() -> jwtAuthenticationFilter.doFilterInternal(request, response, filterChain));

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void analyze_folder_stream_요청에_토큰이_전혀_없어도_예외_없이_체인이_진행된다() throws Exception {
    when(request.getHeader("Authorization")).thenReturn(null);
    when(request.getParameter("token")).thenReturn(null);
    when(request.getRequestURI()).thenReturn("/api/analysis/analyze-folder-stream");

    assertDoesNotThrow(() -> jwtAuthenticationFilter.doFilterInternal(request, response, filterChain));

    assertNull(SecurityContextHolder.getContext().getAuthentication());
    verify(filterChain, times(1)).doFilter(request, response);
    assertTrue(request.getRequestURI().contains("analyze-folder-stream"));
  }
}
