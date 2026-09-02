package com.legacy.auth;

import com.legacy.api.usage.ApiUsageFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

  private final UserDetailsService userDetailsService;

  @Autowired
  public SecurityConfig(UserDetailsService userDetailsService) {
    this.userDetailsService = userDetailsService;
  }

  @Bean
  public JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
    return new JwtAuthenticationFilter(jwtTokenProvider, userDetailsService);
  }

  // 비밀번호 암호화
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  // 인증 매니저
  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
      throws Exception {
    return config.getAuthenticationManager();
  }

  // @Component 로 등록된 ApiUsageFilter가 서블릿 컨테이너에 중복 등록되는 것을 방지.
  // Security 필터 체인 내에서 JWT 이후에 명시적으로 등록하므로 여기서는 비활성화.
  @Bean
  public FilterRegistrationBean<ApiUsageFilter> disableApiUsageAutoRegistration(
      ApiUsageFilter filter) {
    FilterRegistrationBean<ApiUsageFilter> reg = new FilterRegistrationBean<>(filter);
    reg.setEnabled(false);
    return reg;
  }

  // SecurityFilterChain 설정
  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http,
      JwtAuthenticationFilter jwtAuthenticationFilter,
      ApiUsageFilter apiUsageFilter) throws Exception {
    // 쿠키의 값을 클라이언트가 그대로 읽어 헤더로 되돌려 보내는 더블 서브밋 패턴을 성립시키기 위해
    // 마스킹 없는 요청 핸들러를 명시한다(기본값은 BREACH 대응 XOR 마스킹 핸들러라 쿠키 값과 헤더
    // 기대값이 서로 달라진다). setCsrfRequestAttributeName(null)은 지연 토큰 로딩을 끄는 설정으로,
    // 이를 켜야 안전한 GET 요청 응답에도 XSRF-TOKEN 쿠키가 즉시 발급된다.
    // (Spring Security 6 공식 SPA/쿠키 클라이언트 레시피, 02-design-v2 2.2절)
    CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
    requestHandler.setCsrfRequestAttributeName(null);

    http
        .csrf(csrf -> csrf
            // STATELESS 세션 정책과 맞물리도록 CSRF 토큰 저장소를 세션 기반 기본값에서 쿠키 기반으로 전환.
            // withHttpOnlyFalse()는 클라이언트 JS가 XSRF-TOKEN 쿠키를 읽을 수 있게 하기 위한 설정이다.
            // (아래 ignoringRequestMatchers 3건은 기존 동작 유지를 위해 그대로 둔다 —
            //  현재 프런트엔드 요청은 전부 /api/**, /auth/** 하위라 CSRF 검사 대상이 아니다.)
            .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            .csrfTokenRequestHandler(requestHandler)
            .ignoringRequestMatchers("/h2-console/**")
            .ignoringRequestMatchers("/auth/**")
            .ignoringRequestMatchers("/api/**")
        )
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(authz -> authz
            // 공개 엔드포인트
            .requestMatchers("/", "/auth/login", "/h2-console/**").permitAll()
            .requestMatchers("/css/**", "/js/**", "/images/**").permitAll()
            // 관리자/사용자 페이지는 permitAll (클라이언트에서 토큰 검증)
            .requestMatchers("/admin/**", "/my-activity").permitAll()
            // API는 역할별 보호
            .requestMatchers("/api/admin/**").hasRole("ADMIN")
            // API는 인증 필수
            .requestMatchers("/api/**").authenticated()
            // 나머지는 인증 필요
            .anyRequest().authenticated()
        )
        .userDetailsService(userDetailsService)
        .formLogin(form -> form.disable()) // Form login 비활성화 (JWT 기반 인증 사용)
        .exceptionHandling(exception -> exception
            .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/auth/login"))
        )
        // JWT 처리 후 API 사용량 기록 순서 보장
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(apiUsageFilter, JwtAuthenticationFilter.class);

    // H2 콘솔을 위한 헤더 설정
    http.headers(headers -> headers.frameOptions(frameOptions -> frameOptions.disable()));

    return http.build();
  }
}
