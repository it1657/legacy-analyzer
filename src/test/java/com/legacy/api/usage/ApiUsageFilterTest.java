package com.legacy.api.usage;

import com.legacy.auth.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * ApiUsageFilter의 경로 필터링/인증 판별/IP 추출 우선순위/사용량 저장/예외 흡수를 검증한다.
 * 02-design-v1 4.3절 근거.
 *
 * <p>테스트 클래스를 프로덕션 클래스와 <b>동일 패키지</b>(com.legacy.api.usage)에 두어,
 * 자바의 protected 접근 규칙(같은 패키지 내 접근 가능)으로 {@code doFilterInternal}을
 * <b>리플렉션 없이 직접 호출</b>한다. 시그니처가 바뀌면 런타임이 아니라 컴파일 타임에 즉시 드러난다.
 * (게이트1 승인 패턴 — 이후 유사 Filter/Interceptor 테스트에서 우선 재사용할 것.)
 *
 * <p>SecurityContextHolder는 정적 전역 상태이므로 {@code @AfterEach}에서 반드시 clearContext()를 호출한다.
 */
class ApiUsageFilterTest {

  private ApiUsageRepository apiUsageRepository;
  private FilterChain filterChain;
  private ApiUsageFilter apiUsageFilter;

  @BeforeEach
  void setUp() {
    apiUsageRepository = mock(ApiUsageRepository.class);
    filterChain = mock(FilterChain.class);
    apiUsageFilter = new ApiUsageFilter(apiUsageRepository);
  }

  @AfterEach
  void tearDown() {
    // 전역 정적 상태 누수 방지(필수)
    SecurityContextHolder.clearContext();
  }

  private void authenticateAs(Long seq, String userId) {
    User user = ApiUsageTestFixtures.newUser(seq, userId, userId + "@example.com");
    Authentication authentication = mock(Authentication.class);
    org.mockito.Mockito.when(authentication.getPrincipal()).thenReturn(user);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private void authenticateWithNonUserPrincipal() {
    Authentication authentication = mock(Authentication.class);
    org.mockito.Mockito.when(authentication.getPrincipal()).thenReturn("anonymousUser");
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private MockHttpServletRequest apiRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/analysis/start");
    request.setRemoteAddr("4.4.4.4");
    return request;
  }

  private ApiUsage capturedSavedUsage() {
    ArgumentCaptor<ApiUsage> captor = ArgumentCaptor.forClass(ApiUsage.class);
    verify(apiUsageRepository).save(captor.capture());
    return captor.getValue();
  }

  // ---------- 1. 경로 필터링 ----------

  @Test
  void 추적_대상이_아닌_경로는_원본_요청응답_그대로_체인만_진행하고_저장하지_않는다()
      throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login");
    MockHttpServletResponse response = new MockHttpServletResponse();
    authenticateAs(7L, "alice"); // 인증돼 있어도 경로가 대상이 아니면 기록하지 않는다.

    apiUsageFilter.doFilterInternal(request, response, filterChain);

    verify(filterChain, times(1)).doFilter(request, response); // 래핑되지 않은 원본 그대로
    verifyNoInteractions(apiUsageRepository);
  }

  @Test
  void 정적_리소스_경로도_추적_대상이_아니다() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/css/style.css");
    MockHttpServletResponse response = new MockHttpServletResponse();
    authenticateAs(7L, "alice");

    apiUsageFilter.doFilterInternal(request, response, filterChain);

    verify(filterChain, times(1)).doFilter(request, response);
    verifyNoInteractions(apiUsageRepository);
  }

  @Test
  void api_경로와_admin_경로는_모두_추적_대상이라_래핑된_요청응답으로_체인이_진행된다()
      throws ServletException, IOException {
    for (String uri : new String[] {"/api/analysis/start", "/admin/users"}) {
      FilterChain chain = mock(FilterChain.class);
      MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
      MockHttpServletResponse response = new MockHttpServletResponse();

      apiUsageFilter.doFilterInternal(request, response, chain);

      verify(chain, times(1)).doFilter(any(ContentCachingRequestWrapper.class),
          any(ContentCachingResponseWrapper.class));
    }
  }

  // ---------- 2/3. 인증 판별 ----------

  @Test
  void 인증정보가_없으면_래핑된_요청응답으로_체인은_진행되지만_사용량을_저장하지_않는다()
      throws ServletException, IOException {
    // SecurityContextHolder 미설정 상태(getAuthentication()==null)
    MockHttpServletRequest request = apiRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    apiUsageFilter.doFilterInternal(request, response, filterChain);

    verify(filterChain, times(1)).doFilter(any(ContentCachingRequestWrapper.class),
        any(ContentCachingResponseWrapper.class));
    verify(apiUsageRepository, never()).save(any(ApiUsage.class));
  }

  @Test
  void principal이_User가_아니면_사용량을_저장하지_않는다() throws ServletException, IOException {
    authenticateWithNonUserPrincipal();
    MockHttpServletRequest request = apiRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    apiUsageFilter.doFilterInternal(request, response, filterChain);

    verify(filterChain, times(1)).doFilter(any(ContentCachingRequestWrapper.class),
        any(ContentCachingResponseWrapper.class));
    verify(apiUsageRepository, never()).save(any(ApiUsage.class));
  }

  // ---------- 4. IP 추출 우선순위 ----------

  @Test
  void IP는_X_Forwarded_For의_첫번째_값을_trim해서_사용한다() throws ServletException, IOException {
    authenticateAs(7L, "alice");
    MockHttpServletRequest request = apiRequest();
    request.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2");
    request.addHeader("X-Real-IP", "3.3.3.3");

    apiUsageFilter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

    assertEquals("1.1.1.1", capturedSavedUsage().getIpAddress());
  }

  @Test
  void X_Forwarded_For가_없으면_X_Real_IP를_사용한다() throws ServletException, IOException {
    authenticateAs(7L, "alice");
    MockHttpServletRequest request = apiRequest();
    request.addHeader("X-Real-IP", "3.3.3.3");

    apiUsageFilter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

    assertEquals("3.3.3.3", capturedSavedUsage().getIpAddress());
  }

  @Test
  void 두_헤더가_모두_없으면_remoteAddr를_사용한다() throws ServletException, IOException {
    authenticateAs(7L, "alice");
    MockHttpServletRequest request = apiRequest(); // remoteAddr=4.4.4.4

    apiUsageFilter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

    assertEquals("4.4.4.4", capturedSavedUsage().getIpAddress());
  }

  // ---------- 5. 정상 저장 ----------

  @Test
  void 인증된_사용자의_api_요청은_사용량_전_필드를_채워_저장한다() throws ServletException, IOException {
    authenticateAs(7L, "alice");
    MockHttpServletRequest request = apiRequest();
    request.setContent("{\"a\":1}".getBytes()); // 7 bytes
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(201);
    // 다운스트림이 요청 본문을 읽고 응답 본문을 쓰는 상황을 재현해야 캐싱 크기가 0이 아니게 된다.
    doAnswer(invocation -> {
      HttpServletRequest wrappedRequest = invocation.getArgument(0);
      wrappedRequest.getInputStream().readAllBytes();
      HttpServletResponse wrappedResponse = invocation.getArgument(1);
      wrappedResponse.getOutputStream().write("hello".getBytes()); // 5 bytes
      return null;
    }).when(filterChain).doFilter(any(), any());

    apiUsageFilter.doFilterInternal(request, response, filterChain);

    ApiUsage saved = capturedSavedUsage();
    assertEquals(7L, saved.getUserId());
    assertEquals("/api/analysis/start", saved.getEndpoint());
    assertEquals("POST", saved.getMethod());
    assertEquals(201, saved.getStatusCode());
    assertEquals(7L, saved.getRequestSize());
    assertEquals(5L, saved.getResponseSize());
    assertThat(saved.getExecutionTimeMs()).isGreaterThanOrEqualTo(0L);

    // 체인에는 반드시 캐싱 래퍼 타입이 전달된다.
    ArgumentCaptor<jakarta.servlet.ServletRequest> requestCaptor =
        ArgumentCaptor.forClass(jakarta.servlet.ServletRequest.class);
    ArgumentCaptor<jakarta.servlet.ServletResponse> responseCaptor =
        ArgumentCaptor.forClass(jakarta.servlet.ServletResponse.class);
    verify(filterChain).doFilter(requestCaptor.capture(), responseCaptor.capture());
    assertThat(requestCaptor.getValue()).isInstanceOf(ContentCachingRequestWrapper.class);
    assertThat(responseCaptor.getValue()).isInstanceOf(ContentCachingResponseWrapper.class);
  }

  // ---------- 6. 저장 중 예외 ----------

  @Test
  void 사용량_저장이_실패해도_예외를_전파하지_않고_체인은_이미_진행된_상태로_종료된다()
      throws ServletException, IOException {
    authenticateAs(7L, "alice");
    doThrow(new RuntimeException("DB 오류")).when(apiUsageRepository).save(any(ApiUsage.class));
    MockHttpServletRequest request = apiRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertDoesNotThrow(() -> apiUsageFilter.doFilterInternal(request, response, filterChain));

    // 저장은 체인 진행 이후(finally)에 일어나므로, 저장 실패와 무관하게 체인은 이미 1회 호출된 상태다.
    verify(filterChain, times(1)).doFilter(any(ContentCachingRequestWrapper.class),
        any(ContentCachingResponseWrapper.class));
    verify(apiUsageRepository, times(1)).save(any(ApiUsage.class));
  }
}
