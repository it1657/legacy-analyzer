package com.legacy.analysis.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 기동 시 {@code llm_model_options} 테이블이 비어 있으면 기존에 하드코딩돼 있던
 * Claude 3종(소넷/오퍼스/하이쿠)을 기본값으로 시드한다(Phase 6, 2026-08-25).
 *
 * {@link LlmModelOptionService#seedDefaultsIfEmpty()}는 Phase 1(T5)에서 이미 구현·테스트돼
 * 있었으나 실제로 호출하는 지점이 없어 지금까지는 동작하지 않고 있었다 — 이 클래스가 그 호출부다.
 * {@code com.legacy.auth.DataInitializer}(관리자/테스트 계정 시딩)의 기존 관례
 * ({@link CommandLineRunner}, "이미 있으면 스킵"의 멱등성)를 그대로 따르되, 이 프로젝트는
 * 기능(도메인)별 패키지 구조라 auth 도메인과 무관한 LLM 모델 시딩은 별도 클래스로 분리해
 * {@code com.legacy.analysis.llm} 패키지 안에 둔다(auth → analysis.llm 역방향 의존을 만들지 않기 위함).
 */
@Component
public class LlmModelOptionSeedInitializer implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(LlmModelOptionSeedInitializer.class);

  private final LlmModelOptionService llmModelOptionService;

  // REQ-002(2026-09): .env의 LLM_LOCAL_MODEL이 설정된 배포는 그 모델을 DB에도 1건 자동 등록해,
  // local provider의 선택지가 0개가 되는 퇴행을 막는다(설계 §1.2). 미설정이면 no-op.
  @Value("${llm.local.model:}")
  private String llmLocalModel;

  @Autowired
  public LlmModelOptionSeedInitializer(LlmModelOptionService llmModelOptionService) {
    this.llmModelOptionService = llmModelOptionService;
  }

  @Override
  public void run(String... args) throws Exception {
    log.info("[LLM 모델 기본값 시드] 확인 시작");
    llmModelOptionService.seedDefaultsIfEmpty();
    llmModelOptionService.seedLocalFromEnvIfConfigured(llmLocalModel);
    log.info("[LLM 모델 기본값 시드] 확인 완료");
  }
}
