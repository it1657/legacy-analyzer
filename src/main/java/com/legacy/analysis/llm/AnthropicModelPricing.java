package com.legacy.analysis.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 *
 * <h2>이 클래스가 단가의 유일 정본이다 (REQ-001, 2026-09)</h2>
 * <p>단가 숫자는 이 클래스에만 둔다. <b>사용자 화면에 보이는 단가도 여기서 파생된다</b> —
 * {@code GET /api/config/llm-models}의 {@code pricing} 필드가 {@link #findKnown(String)}의 결과를
 * 실어 보내고, 프런트는 그 값을 표시명 뒤에 붙일 뿐 자기 쪽에 숫자를 두지 않는다. 그래서
 * <b>화면에 단가가 보인다면 그 숫자는 비용 계산이 쓰는 바로 그 값</b>이다.
 *
 * <p>그전에는 같은 단가가 DB 시드 표시명·프런트 폴백 목록·관리자 입력란 안내 문구에 문자열로
 * 복제돼 있었다. DB 시드는 테이블이 완전히 비었을 때만 도는 구조라, 이 클래스의 단가를 고쳐도 이미
 * 시드된 운영 DB의 표시명은 따라오지 않았다 — 즉 복제본은 언제든 조용히 어긋날 수 있었다.
 * <b>표시용 단가를 새로 만들 일이 생기면 그 복제를 다시 만들지 말고 {@code pricing} 필드를 쓴다.</b>
 *
 * <h2>{@link #of(String)} 와 {@link #findKnown(String)} 의 역할 구분</h2>
 * <ul>
 *   <li>{@code of()} = <b>계산용</b>. "모른다"를 반환할 수 없고, 모르는 키에는 WARN을 남긴다.</li>
 *   <li>{@code findKnown()} = <b>표시용</b>. "모른다"({@link Optional#empty()})를 반환하고
 *       <b>WARN을 남기지 않는다.</b> 목록 조회는 화면 진입마다 일어나므로 WARN을 남기면 로그가
 *       묻히고, 계산 경로의 WARN 억제 집합까지 오염된다.</li>
 * </ul>
 * 매칭 규칙은 {@code findKnown()} 한 곳에만 있고 {@code of()}가 그 위에 폴백을 얹는다 — 두 메서드가
 * 각자 조회 순서를 갖게 되면 이 사이클이 제거하려는 복제가 클래스 안에서 되살아난다.
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

    /**
     * 화면에 그대로 붙일 수 있는 단가 표기. 예: {@code "$3/$15 per 1M"}, {@code "$0.80/$4 per 1M"}.
     *
     * <p>정수면 소수점을 붙이지 않고, 아니면 소수 둘째 자리까지 쓴다({@link Locale#ROOT} 고정 —
     * 로케일에 따라 소수점이 쉼표가 되면 표기가 흔들린다). 기존 DB 시드 표시명에 박혀 있던 단가
     * 문자열과 <b>글자 단위로 같게</b> 맞춘 형식이다(표시 연속성 — 사용자가 보던 문구가 이 변경으로
     * 달라지지 않아야 한다).
     *
     * <p>포맷을 자바 한 곳에 두는 이유: 이 저장소에는 JS 단위테스트 프레임워크가 없어
     * {@code 0.8 -> "0.80"} 같은 규칙을 프런트에 두면 검증이 약해지고, 규칙이 두 언어에 복제된다.
     */
    public String label() {
      return "$" + formatAmount(inputPerMillionTokens)
          + "/$" + formatAmount(outputPerMillionTokens)
          + " per 1M";
    }

    private static String formatAmount(double amount) {
      String format = amount == Math.rint(amount) ? "%.0f" : "%.2f";
      return String.format(Locale.ROOT, format, amount);
    }
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
    return findKnown(modelKey).orElseGet(() -> {
      warnUnknownModelOnce(modelKey);
      return UNKNOWN_MODEL_FALLBACK;
    });
  }

  /**
   * <b>아는 단가만</b> 돌려준다 — 조회 순서 ①정확 키 → ②패밀리 매칭까지만 보고, 모르면
   * {@link Optional#empty()}다. 미지 모델 폴백(③)으로 내려가지 않으므로 {@code of()}와 달리
   * "모른다"를 표현할 수 있다.
   *
   * <p><b>WARN을 남기지 않는다.</b> 표시용 조회는 사용자가 화면에 들어올 때마다 목록 전체에 대해
   * 일어난다 — 여기서 경고를 남기면 로그가 묻히고, 더 나쁘게는 {@code of()}의 모델키당 1회 억제
   * 집합을 먼저 채워서 <b>계산 경로의 경고가 사라진다</b>. LOCAL 모델키도 이 메서드로는 조용히
   * empty가 된다.
   *
   * @param modelKey 모델 식별자({@code null}/빈 문자열 허용 — 예외 없이 empty)
   * @return 알려진 단가, 모르면 {@link Optional#empty()}
   */
  public static Optional<Pricing> findKnown(String modelKey) {
    String normalized = modelKey == null ? "" : modelKey.trim().toLowerCase(Locale.ROOT);

    Pricing exactMatch = EXACT_KEY_PRICING.get(normalized);
    if (exactMatch != null) {
      return Optional.of(exactMatch);
    }

    // claude- 접두를 포함해 좁힌다 — opus-coder:7b 같은 로컬 모델명이 걸리지 않게.
    if (normalized.contains("claude-opus")) {
      return Optional.of(OPUS);
    }
    if (normalized.contains("claude-sonnet")) {
      return Optional.of(SONNET);
    }
    if (normalized.contains("claude-haiku")) {
      return Optional.of(HAIKU);
    }

    return Optional.empty();
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
