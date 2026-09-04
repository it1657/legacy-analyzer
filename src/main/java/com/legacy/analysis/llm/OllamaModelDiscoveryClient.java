package com.legacy.analysis.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 로컬 Ollama 서버에 실제로 설치(pull)된 모델 목록을 조회하는 전용 클라이언트 (REQ-003, 2026-09).
 *
 * {@link OpenAiCompatibleLlmClient}에 기능을 얹지 않고 별도 클래스로 분리한 이유: {@code GET /api/tags}는
 * OpenAI 호환 표준이 아니라 Ollama 자체 API이고, {@code OpenAiCompatibleLlmClient}는 vLLM/LocalAI 등
 * 다른 백엔드도 지원한다는 게 기존 설계 의도라 여기에 Ollama 전용 기능을 섞으면 그 일반성이 깨진다.
 *
 * 타임아웃은 채팅 호출용 {@code llm.local.read-timeout-sec}(기본 300초)와 분리해
 * {@code llm.local.tags-timeout-sec}(기본 5초)를 쓴다 — 관리자 화면에서 모델 등록 폼을 열 때마다
 * 최대 300초를 기다리게 하면 안 되기 때문이다.
 */
@Component
public class OllamaModelDiscoveryClient {

  private static final Logger log = LoggerFactory.getLogger(OllamaModelDiscoveryClient.class);

  private final WebClient webClient;
  private final long timeoutSec;

  public OllamaModelDiscoveryClient(
      @Value("${llm.local.url}") String baseUrl,
      @Value("${llm.local.tags-timeout-sec:5}") long timeoutSec) {
    this.timeoutSec = timeoutSec;
    HttpClient httpClient = HttpClient.create()
        .responseTimeout(Duration.ofSeconds(timeoutSec));
    this.webClient = WebClient.builder()
        .baseUrl(baseUrl)
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .build();
  }

  /**
   * 설치된 모델 이름 목록을 조회한다.
   * <ul>
   *   <li>조회 성공: {@code Optional.of(목록)} — 목록 자체가 비어 있을 수도 있다(Ollama는 응답했지만
   *       아직 모델을 하나도 pull하지 않은 경우. 이 경우도 "확실히 연결됨"으로 취급해 하드 차단 대상이다).</li>
   *   <li>조회 실패(타임아웃/연결거부/파싱오류): {@code Optional.empty()} — "확인 불가"로 취급해
   *       등록 검증을 건너뛴다(비Ollama LOCAL 백엔드일 가능성 포함).</li>
   * </ul>
   * 이 성공/실패 구분이 REQ-003 하드 검증 절충안(게이트1 사람 결정)의 핵심 신호다.
   * 예외는 절대 밖으로 던지지 않는다 — Ollama가 안 떠 있는 환경에서도 앱은 정상 동작해야 한다.
   */
  public Optional<List<String>> listInstalledModels() {
    try {
      Map<?, ?> response = webClient.get()
          .uri("/api/tags")
          .retrieve()
          .bodyToMono(Map.class)
          .block(Duration.ofSeconds(timeoutSec + 1));
      if (response == null) {
        log.warn("[Ollama 설치 모델 조회 실패] 응답 바디가 비어 있습니다.");
        return Optional.empty();
      }
      Object modelsObj = response.get("models");
      if (!(modelsObj instanceof List<?> models)) {
        log.warn("[Ollama 설치 모델 조회 실패] 응답에 models 배열이 없습니다.");
        return Optional.empty();
      }
      List<String> names = new ArrayList<>();
      for (Object item : models) {
        if (item instanceof Map<?, ?> model) {
          Object name = model.get("name");
          if (name != null) {
            names.add(String.valueOf(name));
          }
        }
      }
      log.info("[Ollama 설치 모델 조회] {}건 확인", names.size());
      return Optional.of(names);
    } catch (Exception e) {
      log.warn("[Ollama 설치 모델 조회 실패] {}", e.getMessage());
      return Optional.empty();
    }
  }
}
