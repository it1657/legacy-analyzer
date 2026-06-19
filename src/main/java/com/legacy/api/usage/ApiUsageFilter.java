package com.legacy.api.usage;

import com.legacy.auth.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

@Component
public class ApiUsageFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(ApiUsageFilter.class);

  private final ApiUsageRepository apiUsageRepository;

  @Autowired
  public ApiUsageFilter(ApiUsageRepository apiUsageRepository) {
    this.apiUsageRepository = apiUsageRepository;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {

    // API 엔드포인트만 추적 (정적 파일, 로그인 페이지 제외)
    String uri = request.getRequestURI();
    if (!shouldTrack(uri)) {
      filterChain.doFilter(request, response);
      return;
    }

    // 요청 래핑 (요청 본문 캐싱)
    ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request);

    // 응답 래핑 (응답 본문 캐싱)
    ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

    long startTime = System.currentTimeMillis();

    try {
      // 요청 계속 처리
      filterChain.doFilter(requestWrapper, responseWrapper);
    } finally {
      long executionTime = System.currentTimeMillis() - startTime;

      // 현재 인증된 사용자 정보 가져오기
      Long userId = null;
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication != null && authentication.getPrincipal() instanceof User) {
        userId = ((User) authentication.getPrincipal()).getSeq();
      }

      // API 사용량 기록 (인증된 사용자만)
      if (userId != null) {
        try {
          byte[] requestBody = requestWrapper.getContentAsByteArray();
          byte[] responseBody = responseWrapper.getContentAsByteArray();

          long requestSize = requestBody.length;
          long responseSize = responseBody.length;
          int statusCode = responseWrapper.getStatus();
          String ipAddress = getClientIpAddress(request);

          ApiUsage apiUsage = new ApiUsage(
              userId,
              uri,
              request.getMethod(),
              requestSize,
              responseSize,
              statusCode,
              executionTime,
              ipAddress);

          apiUsageRepository.save(apiUsage);

          log.debug("[API 사용량 기록] userId={}, endpoint={}, method={}, size={}+{}bytes, time={}ms",
              userId, uri, request.getMethod(), requestSize, responseSize, executionTime);
        } catch (Exception e) {
          log.error("[API 사용량 기록 실패]", e);
        }
      }

      // 응답 본문 다시 쓰기
      responseWrapper.copyBodyToResponse();
    }
  }

  // 추적할 경로 결정
  private boolean shouldTrack(String uri) {
    // API 엔드포인트만 추적
    return uri.startsWith("/api/") || uri.startsWith("/admin/");
  }

  // 클라이언트 IP 주소 추출
  private String getClientIpAddress(HttpServletRequest request) {
    String xForwardedFor = request.getHeader("X-Forwarded-For");
    if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
      return xForwardedFor.split(",")[0].trim();
    }

    String xRealIp = request.getHeader("X-Real-IP");
    if (xRealIp != null && !xRealIp.isEmpty()) {
      return xRealIp;
    }

    return request.getRemoteAddr();
  }
}
