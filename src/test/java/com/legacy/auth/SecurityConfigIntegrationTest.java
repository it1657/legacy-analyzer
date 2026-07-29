package com.legacy.auth;

import com.legacy.api.usage.ApiUsageFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SecurityConfig의 경로별 인가 규칙(permitAll/역할별 보호/CSRF 예외)을
 * @WebMvcTest 슬라이스 + @Import(SecurityConfig.class)로 실측 검증한다.
 * DataInitializer는 @Component이며 @WebMvcTest가 컴포넌트 스캔을 하지 않으므로
 * 별도 조치 없이 격리된다(@SpringBootTest/전체 컴포넌트 스캔은 사용하지 않음).
 */
@WebMvcTest(controllers = SecurityConfigIntegrationTest.DummyController.class)
@Import({SecurityConfig.class, SecurityConfigIntegrationTest.DummyController.class})
class SecurityConfigIntegrationTest {

  // 실제 애플리케이션 진입점(com.legacy.core.LegacyAnalyzerApplication)이
  // 이 테스트 패키지(com.legacy.auth)의 상위 패키지에 있지 않아 @WebMvcTest의
  // 기본 @SpringBootConfiguration 탐색(테스트 클래스 패키지에서 상위로 탐색)이 실패한다.
  // @ComponentScan이 없는 빈 로컬 설정만 제공해 탐색 실패를 해소하며,
  // 이 자체는 컴포넌트 스캔을 수행하지 않으므로 DataInitializer 격리 원칙(전체 컴포넌트 스캔 금지)과 무관하다.
  // (참고: 컴포넌트 스캔이 전혀 없는 구성이라 controllers 속성만으로는 더미 컨트롤러가
  // 등록되지 않아, @Import로 더미 컨트롤러 빈을 명시적으로 등록한다.)
  @SpringBootConfiguration
  static class TestConfig {
  }

  @Autowired
  private MockMvc mockMvc;

  @MockBean
  private UserDetailsService userDetailsService;

  @MockBean
  private JwtTokenProvider jwtTokenProvider;

  @MockBean
  private ApiUsageFilter apiUsageFilter;

  @BeforeEach
  void setUp() throws Exception {
    // ApiUsageFilter mock의 기본 동작은 doFilter 호출 시 아무 것도 하지 않아 체인이 끊기므로,
    // 실제 필터처럼 체인을 통과시키는 pass-through 스텁을 명시적으로 설정한다
    // (02-design-v1.md 4.2절 코드 그대로 사용 — 이 스텁이 없으면 모든 테스트가 실패한다).
    doAnswer(invocation -> {
      ServletRequest req = invocation.getArgument(0);
      ServletResponse res = invocation.getArgument(1);
      FilterChain chain = invocation.getArgument(2);
      chain.doFilter(req, res);
      return null;
    }).when(apiUsageFilter).doFilter(any(), any(), any());
  }

  @Test
  void admin_ping_무인증_요청은_permitAll로_통과한다() throws Exception {
    mockMvc.perform(get("/admin/ping"))
        .andExpect(status().isOk());
  }

  @Test
  void my_activity_무인증_요청은_permitAll로_통과한다() throws Exception {
    mockMvc.perform(get("/my-activity"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser
  void api_echo는_csrf_토큰_없이도_통과한다() throws Exception {
    mockMvc.perform(post("/api/echo"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser
  void auth_dummy는_csrf_토큰_없이도_통과한다() throws Exception {
    mockMvc.perform(post("/auth/dummy"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser
  void secure_echo는_csrf_토큰_없이_요청하면_실제_관측된_상태코드로_거부된다() throws Exception {
    // 사전에 403을 단정하지 않고 실측 결과를 그대로 특성화 테스트로 고정한다.
    // 실제 로컬 실행 결과: 403 Forbidden (CSRF 보호가 정상 동작).
    // 버그 의심 기록: 이 앱은 SessionCreationPolicy.STATELESS이면서 CSRF 토큰 저장소는
    // 기본값(세션 기반)이라, 이론적으로는 STATELESS 환경에서 세션 기반 CSRF 토큰을
    // 정상적으로 발급/보관할 방법이 없는 구조적 조합으로 보인다.
    // 이 테스트는 403으로 막히는 "현재 동작"을 고정할 뿐이고, 세션을 쓸 수 없는 상태에서
    // 실제 브라우저 클라이언트가 정상적인 흐름으로 CSRF 토큰을 발급받아 통과하는 경로가
    // 존재하는지는 확인되지 않았다 — "버그 의심"으로 05-dev-progress.md에 별도 기록함
    // (06-qa-results.md/bug-suspects.md 기록은 QA 담당).
    mockMvc.perform(post("/secure/echo"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser
  void secure_echo는_csrf_토큰을_첨부하면_통과한다() throws Exception {
    mockMvc.perform(post("/secure/echo").with(csrf()))
        .andExpect(status().isOk());
  }

  @Test
  void secure_echo_무인증_요청은_CSRF_토큰_유무와_무관하게_실제_관측된_상태코드로_거부된다() throws Exception {
    // 사전에 302를 단정했으나 실제 로컬 실행 결과는 403 Forbidden(Invalid CSRF Token)이었다.
    // POST 요청은 인증 여부를 확인하는 인가(authorizeHttpRequests) 단계 이전에
    // CsrfFilter가 먼저 CSRF 토큰 검증에서 막기 때문으로 보인다(9/10번 GET 시나리오와
    // 달리 302 리다이렉트로 이어지지 않음 — 이 필터 순서 차이도 실측으로만 확인 가능했다).
    mockMvc.perform(post("/secure/echo"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "USER")
  void api_admin_ping은_ADMIN_역할이_없으면_403이다() throws Exception {
    mockMvc.perform(get("/api/admin/ping"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void api_admin_ping은_ADMIN_역할이_있으면_200이다() throws Exception {
    mockMvc.perform(get("/api/admin/ping"))
        .andExpect(status().isOk());
  }

  @Test
  void api_admin_ping_무인증_요청은_실제_관측된_상태코드로_리다이렉트된다() throws Exception {
    // 실제 로컬 실행 결과: 302 Found, Location: http://localhost/auth/login.
    // 7번(POST)과 달리 GET 요청은 CsrfFilter 검증 대상이 아니라서(폼 서브밋 메서드만 검사)
    // authorizeHttpRequests 단계까지 도달해 LoginUrlAuthenticationEntryPoint가 정상 동작한다.
    mockMvc.perform(get("/api/admin/ping"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "http://localhost/auth/login"));
  }

  @RestController
  static class DummyController {

    @GetMapping("/admin/ping")
    String adminPing() {
      return "pong";
    }

    @GetMapping("/my-activity")
    String myActivity() {
      return "ok";
    }

    @PostMapping("/api/echo")
    String apiEcho() {
      return "ok";
    }

    @GetMapping("/api/admin/ping")
    String apiAdminPing() {
      return "pong";
    }

    @PostMapping("/auth/dummy")
    String authDummy() {
      return "ok";
    }

    @PostMapping("/secure/echo")
    String secureEcho() {
      return "ok";
    }
  }
}
