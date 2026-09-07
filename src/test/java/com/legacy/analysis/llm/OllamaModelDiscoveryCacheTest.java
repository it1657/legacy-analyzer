package com.legacy.analysis.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OllamaModelDiscoveryCache}의 TTL 캐시 동작을 검증한다 (REQ-002, 2026-09).
 *
 * 같은 패키지의 다른 단위 테스트(LlmModelOptionServiceTest 등)와 동일하게 Spring 컨테이너를 띄우지
 * 않고 Mockito로 협력자({@link OllamaModelDiscoveryClient})만 목킹한다.
 *
 * TTL 만료 케이스는 시계를 조작할 수 있는 별도 시드를 두지 않고 {@code ttlSec = 0} + 짧은
 * {@code Thread.sleep()} 조합으로 만든다 — 만료 판단식이
 * {@code 경과 > ttlSec * 1000}이므로, ttl 0에 sleep이 보장하는 최소 경과 시간이면 항상 만료로
 * 판정돼 시간 의존 플래키가 생기지 않는다.
 */
class OllamaModelDiscoveryCacheTest {

  private static final long TTL_LONG_ENOUGH_SEC = 60L; // 테스트 수행 중 절대 만료되지 않는 값
  private static final long TTL_IMMEDIATE_EXPIRE_SEC = 0L;

  @Test
  void TTL_이내_재호출이면_client를_다시_부르지_않고_캐시된_결과를_준다() {
    OllamaModelDiscoveryClient client = mock(OllamaModelDiscoveryClient.class);
    when(client.listInstalledModels()).thenReturn(Optional.of(List.of("qwen3:8b", "qwen2.5-coder:7b")));
    OllamaModelDiscoveryCache cache = new OllamaModelDiscoveryCache(client, TTL_LONG_ENOUGH_SEC);

    Optional<List<String>> first = cache.getInstalledModelsCached();
    Optional<List<String>> second = cache.getInstalledModelsCached();
    Optional<List<String>> third = cache.getInstalledModelsCached();

    verify(client, times(1)).listInstalledModels();
    assertEquals(List.of("qwen3:8b", "qwen2.5-coder:7b"), first.orElseThrow());
    assertEquals(first.orElseThrow(), second.orElseThrow());
    assertEquals(first.orElseThrow(), third.orElseThrow());
  }

  @Test
  void TTL_만료_후_재호출이면_client를_다시_부른다() throws Exception {
    OllamaModelDiscoveryClient client = mock(OllamaModelDiscoveryClient.class);
    when(client.listInstalledModels()).thenReturn(Optional.of(List.of("qwen3:8b")));
    OllamaModelDiscoveryCache cache = new OllamaModelDiscoveryCache(client, TTL_IMMEDIATE_EXPIRE_SEC);

    cache.getInstalledModelsCached();
    Thread.sleep(10);
    cache.getInstalledModelsCached();

    verify(client, times(2)).listInstalledModels();
  }

  @Test
  void 조회_실패도_TTL_동안_캐시돼_client를_다시_부르지_않는다() {
    // 캐시를 둔 핵심 목적 — Ollama가 죽어 있을 때 매 요청이 타임아웃(기본 6초)까지 기다리지 않도록
    // 실패(Optional.empty())도 성공과 동일하게 캐시한다(02-design-v1 §2.1).
    OllamaModelDiscoveryClient client = mock(OllamaModelDiscoveryClient.class);
    when(client.listInstalledModels()).thenReturn(Optional.empty());
    OllamaModelDiscoveryCache cache = new OllamaModelDiscoveryCache(client, TTL_LONG_ENOUGH_SEC);

    Optional<List<String>> first = cache.getInstalledModelsCached();
    Optional<List<String>> second = cache.getInstalledModelsCached();

    verify(client, times(1)).listInstalledModels();
    assertTrue(first.isEmpty(), "조회 실패는 Optional.empty()로 전달돼야 함");
    assertTrue(second.isEmpty(), "TTL 이내 재호출은 캐시된 실패를 그대로 돌려줘야 함");
  }

  @Test
  void 성공에서_실패로_바뀌면_TTL_만료_후_실패로_갱신된다() throws Exception {
    OllamaModelDiscoveryClient client = mock(OllamaModelDiscoveryClient.class);
    when(client.listInstalledModels())
        .thenReturn(Optional.of(List.of("qwen3:8b")))
        .thenReturn(Optional.empty());
    OllamaModelDiscoveryCache cache = new OllamaModelDiscoveryCache(client, TTL_IMMEDIATE_EXPIRE_SEC);

    Optional<List<String>> before = cache.getInstalledModelsCached();
    Thread.sleep(10);
    Optional<List<String>> after = cache.getInstalledModelsCached();

    verify(client, times(2)).listInstalledModels();
    assertTrue(before.isPresent(), "첫 조회는 성공 결과여야 함");
    assertFalse(after.isPresent(), "TTL 만료 후에는 바뀐 실패 결과로 갱신돼야 함");
  }

  @Test
  void 실패에서_성공으로_바뀌는_경우도_TTL_만료_후_정상_갱신된다() throws Exception {
    // 위 케이스의 역방향 — 실패를 캐시하더라도 Ollama가 다시 뜨면 TTL 만료 시점에 회복돼야 한다.
    OllamaModelDiscoveryClient client = mock(OllamaModelDiscoveryClient.class);
    when(client.listInstalledModels())
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(List.of("qwen3:8b")));
    OllamaModelDiscoveryCache cache = new OllamaModelDiscoveryCache(client, TTL_IMMEDIATE_EXPIRE_SEC);

    Optional<List<String>> before = cache.getInstalledModelsCached();
    Thread.sleep(10);
    Optional<List<String>> after = cache.getInstalledModelsCached();

    verify(client, times(2)).listInstalledModels();
    assertTrue(before.isEmpty());
    assertEquals(List.of("qwen3:8b"), after.orElseThrow());
  }
}
