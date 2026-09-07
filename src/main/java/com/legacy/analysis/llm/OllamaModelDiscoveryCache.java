package com.legacy.analysis.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@link OllamaModelDiscoveryClient}의 설치 모델 조회 결과를 짧은 TTL 동안 캐싱하는 컴포넌트
 * (REQ-002, 2026-09).
 *
 * 도입 이유: 일반 사용자 화면(대시보드)이 로드될 때마다 discovery를 직접 호출하면, Ollama가 느리거나
 * 죽어 있는 배포에서 사용자 수 × 화면 진입 횟수만큼 최대 {@code llm.local.tags-timeout-sec + 1}초
 * (기본 6초)의 블로킹이 발생한다. TTL(기본 15초) 동안 결과를 재사용해 이 비용을 상수로 묶는다.
 *
 * <b>실패(Optional.empty())도 성공과 동일하게 캐시한다</b> — Ollama가 완전히 다운된 상황에서
 * "실패를 캐시하지 않으면" 매 요청이 타임아웃까지 기다리게 되어, 캐시를 둔 목적 자체가 사라지기
 * 때문이다(02-design-v1 §2.1).
 *
 * 이 컴포넌트는 <b>사용자 화면용 새 소비자({@code MainApiController})에서만</b> 사용한다.
 * 관리자 모델 등록 폼({@code LlmModelAdminController})은 기존처럼 {@link OllamaModelDiscoveryClient}를
 * 직접 호출한다 — 등록 시점에는 "지금 이 순간"의 설치 상태를 봐야 하므로 최신성이 빈도보다 중요하다
 * (02-design-v1 §2.4의 "등록 시점 하드 게이트 vs 로드 시점 소프트 필터" 구분).
 */
@Component
public class OllamaModelDiscoveryCache {

  private static final Logger log = LoggerFactory.getLogger(OllamaModelDiscoveryCache.class);

  private final OllamaModelDiscoveryClient client;
  private final long ttlSec;

  // 캐시 갱신 자체는 getInstalledModelsCached()의 synchronized 블록이 보호하지만, 필드 자체는
  // 다른 스레드의 읽기 가시성을 위해 volatile로 둔다.
  private volatile CachedResult cache;

  public OllamaModelDiscoveryCache(
      OllamaModelDiscoveryClient client,
      @Value("${llm.local.discovery-cache-ttl-sec:15}") long ttlSec) {
    this.client = client;
    this.ttlSec = ttlSec;
  }

  /**
   * 설치 모델 목록을 캐시를 거쳐 조회한다. TTL이 만료된 경우에만 실제 discovery를 호출한다.
   *
   * 메서드 전체를 {@code synchronized}로 감싼 이유: 캐시 만료 판단 → 재조회 → 캐시 갱신을 원자적으로
   * 처리해, 캐시가 만료되는 순간 여러 스레드가 동시에 Ollama를 때리는 썬더링 허드를 막기 위함이다.
   *
   * @return 조회 성공 시 {@code Optional.of(모델명 목록)}, 조회 실패("확인 불가") 시 {@code Optional.empty()}
   */
  public synchronized Optional<List<String>> getInstalledModelsCached() {
    CachedResult current = cache;
    if (current != null && !isExpired(current)) {
      return current.models();
    }
    Optional<List<String>> fresh = client.listInstalledModels();
    cache = new CachedResult(fresh, System.currentTimeMillis());
    log.debug("[Ollama 설치 모델 캐시 갱신] available={}, ttlSec={}", fresh.isPresent(), ttlSec);
    return fresh;
  }

  /** TTL 만료 판단 — 캐시 적재 시각으로부터 {@code ttlSec}초가 지났으면 만료로 본다. */
  private boolean isExpired(CachedResult result) {
    return System.currentTimeMillis() - result.fetchedAtEpochMillis() > ttlSec * 1000;
  }

  /**
   * 캐시 1건. 성공/실패를 모두 담기 위해 {@code Optional}을 그대로 보관한다
   * (null은 "캐시 없음"과 구분되지 않아 쓰지 않는다).
   */
  private record CachedResult(Optional<List<String>> models, long fetchedAtEpochMillis) {
  }
}
