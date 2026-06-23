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
    http
        .csrf(csrf -> csrf
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
