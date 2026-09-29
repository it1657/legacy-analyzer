package com.legacy.analysis.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anthropic(Claude) 모델의 토큰 단가를 결정한다 (REQ-003, 2026-09).
 *
 * <p>스프링 빈이 아니라 <b>정적 유틸</b>이다 — 상태(단가 표)가 불변이고, 스프링 컨텍스트 없이 단위
 * 테스트할 수 있어야 하며, 호출부인 {@code MainApiController}의 생성자 의존성을 더 늘리지 않기
 * 위해서다. 같은 패키지의 {@code LlmResult}(record)와 {@code com.legacy.rag.ChunkerRouter}
 * (정적 로거를 가진 비-빈 클래스)의 기존 관례를 따른다.
 *
 * <h2>왜 이 클래스가 생겼는가</h2>
 * 종전 단가 결정은 {@code MainApiController.calculateEstimatedCost()} 안의
 * {@code contains("opus")} / {@code contains("sonnet")} / else 3분기였다. 이 방식에는 두 가지 결함이
 * 있었다.
 * <ol>
 *   <li><b>단가를 모르는 모델이 조용히 최저 단가로 떨어졌다.</b> else 분기가 haiku($0.80/$4)였기
 *       때문에, 신규 Claude 모델이 등록되면 실제 과금보다 적게 추정하면서도 아무 경고가 남지 않았다.
 *       이제 미지 모델은 <b>알려진 단가 중 최댓값</b>으로 추정하고 WARN을 남긴다 — 과소 추정보다
 *       과대 추정이 안전하고, 무엇보다 운영자가 조치할 수 있어야 한다.</li>
 *   <li><b>매칭이 너무 넓었다.</b> {@code contains("opus")}는 {@code opus-coder:7b} 같은 로컬 모델명에도
 *       걸린다. DB에 LOCAL로 등록돼 있으면 비용 계산 이전 단계에서 0으로 걸러지지만, DB에 없는 키가
 *       들어오는 비정상 경로에서는 로컬 모델을 Claude Opus 단가로 오판한다. 그래서 패밀리 매칭은
 *       {@code claude-} 접두를 포함해 좁혔다.</li>
 * </ol>
 *
 * <h2>조회 순서</h2>
 * <ol>
 *   <li><b>정확 키 매핑</b> — DB 시드 3종({@code LlmModelOptionService.seedDefaultsIfEmpty()}가 넣는 키).</li>
 *   <li><b>패밀리 토큰 매칭</b> — 소문자 정규화 후 {@code claude-opus}/{@code claude-sonnet}/{@code claude-haiku}
 *       포함 여부. 시드에 없는 세대(예: {@code claude-sonnet-5})를 받아내는 자리다.</li>
 *   <li><b>미지 모델 폴백</b> — 최고 단가로 추정하고 모델키당 1회 WARN.</li>
 * </ol>
 *
 * <p>단가 값 자체는 종전 하드코딩 값을 그대로 옮긴 것이다(이번 변경은 "결정 구조"만 바꾼다).
 * {@code null}이나 빈 문자열도 예외를 던지지 않고 ③으로 흐른다 — 비용 추정이 분석 자체를 실패시키면
 * 안 된다.
 */
public final class AnthropicModelPricing {

  private static final Logger log = LoggerFactory.getLogger(AnthropicModelPricing.class);

  /**
   * 1M 토큰당 USD 단가.
   *
   * @param inputPerMillionTokens  입력(프롬프트) 100만 토큰당 USD
   * @param outputPerMillionTokens 출력(생성) 100만 토큰당 USD
   */
  public record Pricing(double inputPerMillionTokens, double outputPerMillionTokens) {
  }

  private static final Pricing OPUS = new Pricing(15.00, 75.00);
  private static final Pricing SONNET = new Pricing(3.00, 15.00);
  private static final Pricing HAIKU = new Pricing(0.80, 4.00);

  /** ① 정확 키 — DB 시드 3종과 동일한 모델키. */
  private static final Map<String, Pricing> EXACT_KEY_PRICING = Map.of(
      "claude-opus-4-8", OPUS,
      "claude-sonnet-4-6", SONNET,
      "claude-haiku-4-5-20251001", HAIKU);

  /**
   * ③ 미지 모델 폴백 — 알려진 단가 중 <b>최댓값</b>.
   * 최저 단가로 떨어뜨리면 과금을 과소 추정하고, 그 오차가 조용히 누적된다.
   */
  private static final Pricing UNKNOWN_MODEL_FALLBACK = OPUS;

  /**
   * 이미 WARN을 남긴 모델키. 파일 수백 개를 분석하는 세션에서는 이 조회가 파일마다 일어나므로,
   * 억제하지 않으면 같은 경고가 수백 줄 찍혀 아무도 읽지 않게 된다.
   */
  private static final Set<String> warnedModelKeys = ConcurrentHashMap.newKeySet();

  private AnthropicModelPricing() {
  }

  /**
   * 모델키에 해당하는 단가를 돌려준다. 어떤 입력에도 예외를 던지지 않으며, 알 수 없는 모델은
   * 최고 단가로 추정하고 WARN을 남긴다(모델키당 1회).
   *
   * @param modelKey 모델 식별자({@code null}/빈 문자열 허용)
   * @return 1M 토큰당 입력/출력 단가
   */
  public static Pricing of(String modelKey) {
    String normalized = modelKey == null ? "" : modelKey.trim().toLowerCase(Locale.ROOT);

    Pricing exactMatch = EXACT_KEY_PRICING.get(normalized);
    if (exactMatch != null) {
      return exactMatch;
    }

    // claude- 접두를 포함해 좁힌다 — opus-coder:7b 같은 로컬 모델명이 걸리지 않게.
    if (normalized.contains("claude-opus")) {
      return OPUS;
    }
    if (normalized.contains("claude-sonnet")) {
      return SONNET;
    }
    if (normalized.contains("claude-haiku")) {
      return HAIKU;
    }

    warnUnknownModelOnce(modelKey);
    return UNKNOWN_MODEL_FALLBACK;
  }

  /** 같은 모델키에 대한 WARN은 첫 1회만 남긴다. */
  private static void warnUnknownModelOnce(String modelKey) {
    if (!warnedModelKeys.add(String.valueOf(modelKey))) {
      return;
    }
    log.warn("[단가 조회] 등록되지 않은 모델이라 단가를 알 수 없어 최고 단가(입력 ${}/출력 ${} per 1M)로 추정했습니다."
            + " 실제 단가를 반영하려면 이 모델의 단가를 등록하세요. modelKey={}",
        UNKNOWN_MODEL_FALLBACK.inputPerMillionTokens(),
        UNKNOWN_MODEL_FALLBACK.outputPerMillionTokens(),
        modelKey);
  }
}
