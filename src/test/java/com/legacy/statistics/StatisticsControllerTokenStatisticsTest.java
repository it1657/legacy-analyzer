package com.legacy.statistics;

import com.legacy.analysis.AnalysisHistoryRepository;
import com.legacy.api.usage.ApiUsageRepository;
import com.legacy.auth.User;
import com.legacy.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * StatisticsController.getTokenStatistics()/getMyTokenStatistics()를 검증한다.
 * 02-design-v1 5.4절 근거.
 *
 * <p>getTokenStatistics의 평균 토큰 계산은 `mapToLong(AnalysisHistory::getInputTokens)`처럼 null 방어가 없는
 * 메서드 참조를 사용하므로 해당 필드가 null이면 언박싱 NPE가 발생할 수 있다(NPE 관찰 #2).
 * 관찰 케이스는 실제 실행 결과를 그대로 기록하며, 정상/버그 여부는 이 사이클에서 확정하지 않는다(게이트2 판단).
 */
class StatisticsControllerTokenStatisticsTest {

  private UserRepository userRepository;
  private AnalysisHistoryRepository analysisHistoryRepository;
  private ApiUsageRepository apiUsageRepository;
  private Authentication authentication;
  private StatisticsController statisticsController;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    analysisHistoryRepository = mock(AnalysisHistoryRepository.class);
    apiUsageRepository = mock(ApiUsageRepository.class);
    authentication = mock(Authentication.class);
    statisticsController = new StatisticsController(userRepository, analysisHistoryRepository,
        apiUsageRepository);
  }

  private static Map<?, ?> bodyOf(ResponseEntity<?> response) {
    return (Map<?, ?>) response.getBody();
  }

  // ---------- getTokenStatistics ----------

  @Test
  void getTokenStatistics는_토큰_집계가_null이면_0으로_처리한다() {
    // 리포지토리 토큰 집계 4종을 stub하지 않으면 Mockito 기본값 null이 반환된다.
    ResponseEntity<?> response = statisticsController.getTokenStatistics();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<?, ?> body = bodyOf(response);
    // 특성화 포인트: `x != null ? x : 0` 삼항의 두 피연산자(Long / int)가 long으로 승격되므로
    // 기본값은 Integer 0이 아니라 Long 0L로 박싱된다.
    assertEquals(0L, body.get("total_input_tokens"));
    assertEquals(0L, body.get("total_output_tokens"));
    assertEquals(0L, body.get("total_tokens"));
    assertEquals(0.0, ((Number) body.get("total_cost")).doubleValue(), 0.0001);
  }

  @Test
  void getTokenStatistics는_토큰_집계가_존재하면_그대로_반영한다() {
    when(analysisHistoryRepository.getTotalInputTokensSystem()).thenReturn(1000L);
    when(analysisHistoryRepository.getTotalOutputTokensSystem()).thenReturn(400L);
    when(analysisHistoryRepository.getTotalTokensSystem()).thenReturn(1400L);
    when(analysisHistoryRepository.getTotalCostSystem()).thenReturn(2.5);

    ResponseEntity<?> response = statisticsController.getTokenStatistics();

    Map<?, ?> body = bodyOf(response);
    assertEquals(1000L, body.get("total_input_tokens"));
    assertEquals(400L, body.get("total_output_tokens"));
    assertEquals(1400L, body.get("total_tokens"));
    assertEquals(2.5, ((Number) body.get("total_cost")).doubleValue(), 0.0001);
  }

  @Test
  void getTokenStatistics는_모델명이나_값이_null인_모델별_집계_행을_건너뛴다() {
    // getSystemStatistics와 동일한 null-skip 로직이 이 메서드에도 중복 구현돼 있다(리팩터링 없이 그대로 특성화).
    when(analysisHistoryRepository.getTokensByModel()).thenReturn(Arrays.asList(
        new Object[] {"gpt-4", 1000L},
        new Object[] {null, 500L},
        new Object[] {"claude", null}));
    when(analysisHistoryRepository.getCostByModel()).thenReturn(Arrays.asList(
        new Object[] {"gpt-4", 1.5},
        new Object[] {"claude", null}));

    ResponseEntity<?> response = statisticsController.getTokenStatistics();

    Map<?, ?> body = bodyOf(response);
    Map<?, ?> tokensByModel = (Map<?, ?>) body.get("tokens_by_model");
    Map<?, ?> costByModel = (Map<?, ?>) body.get("cost_by_model");
    assertEquals(Collections.singleton("gpt-4"), tokensByModel.keySet());
    assertEquals(1000L, tokensByModel.get("gpt-4"));
    assertEquals(Collections.singleton("gpt-4"), costByModel.keySet());
    assertEquals(1.5, ((Number) costByModel.get("gpt-4")).doubleValue(), 0.0001);
  }

  @Test
  void getTokenStatistics는_분석이_없으면_평균_토큰_키_자체를_담지_않는다() {
    // 값이 0인 것이 아니라 `if (!allAnalysis.isEmpty())` 블록 전체가 스킵되어 키가 존재하지 않는다.
    when(analysisHistoryRepository.findAll()).thenReturn(Collections.emptyList());

    ResponseEntity<?> response = statisticsController.getTokenStatistics();

    Map<?, ?> body = bodyOf(response);
    assertThat(body.containsKey("avg_input_tokens")).isFalse();
    assertThat(body.containsKey("avg_output_tokens")).isFalse();
  }

  @Test
  void getTokenStatistics는_분석이_있으면_평균_입출력_토큰을_반올림해_담는다() {
    when(analysisHistoryRepository.findAll()).thenReturn(Arrays.asList(
        StatisticsTestFixtures.newAnalysisHistory(1L, 1L, "COMPLETED", 1, 1L, 100L, 50L),
        StatisticsTestFixtures.newAnalysisHistory(2L, 1L, "COMPLETED", 1, 1L, 201L, 75L)));

    ResponseEntity<?> response = statisticsController.getTokenStatistics();

    Map<?, ?> body = bodyOf(response);
    assertEquals(151L, body.get("avg_input_tokens"));  // Math.round(150.5)
    assertEquals(63L, body.get("avg_output_tokens"));  // Math.round(62.5)
  }

  @Test
  void 관찰케이스_getTokenStatistics는_inputTokens가_null이_섞이면_500을_반환한다() {
    // 관찰 목적 테스트(정상/버그 판단 아님, 게이트2 판단 대상).
    // 실측 결과(2026-09-01): `mapToLong(AnalysisHistory::getInputTokens)` 언박싱 지점에서
    // NullPointerException이 실제로 발생했고, try-catch가 이를 잡아 HTTP 500 +
    // message = "토큰 통계 조회 실패: null"(NPE의 getMessage()가 null)을 반환했다.
    when(analysisHistoryRepository.findAll()).thenReturn(Arrays.asList(
        StatisticsTestFixtures.newAnalysisHistory(1L, 1L, "COMPLETED", 1, 1L, 100L, 50L),
        StatisticsTestFixtures.newAnalysisHistory(2L, 1L, "COMPLETED", 1, 1L, null, 75L)));

    ResponseEntity<?> response = statisticsController.getTokenStatistics();

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertEquals("토큰 통계 조회 실패: null", bodyOf(response).get("message"));
  }

  @Test
  void 관찰케이스_getTokenStatistics는_outputTokens가_null이_섞여도_동일하게_500을_반환한다() {
    // inputTokens 평균은 정상 계산된 뒤 outputTokens 파이프라인에서 독립적으로 NPE가 발생한다.
    when(analysisHistoryRepository.findAll()).thenReturn(Arrays.asList(
        StatisticsTestFixtures.newAnalysisHistory(1L, 1L, "COMPLETED", 1, 1L, 100L, 50L),
        StatisticsTestFixtures.newAnalysisHistory(2L, 1L, "COMPLETED", 1, 1L, 201L, null)));

    ResponseEntity<?> response = statisticsController.getTokenStatistics();

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertEquals("토큰 통계 조회 실패: null", bodyOf(response).get("message"));
  }

  @Test
  void getTokenStatistics는_조회_실패_시_500과_토큰_통계_조회_실패_메시지를_반환한다() {
    when(analysisHistoryRepository.getTotalInputTokensSystem())
        .thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = statisticsController.getTokenStatistics();

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertEquals("토큰 통계 조회 실패: DB 오류", bodyOf(response).get("message"));
  }

  // ---------- getMyTokenStatistics ----------

  @Test
  void getMyTokenStatistics는_인증정보가_없으면_401을_반환한다() {
    // 예외 경로가 아니라 명시적 if 분기다(NPE로 catch에 빠지는 것이 아님).
    ResponseEntity<?> response = statisticsController.getMyTokenStatistics(null);

    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    assertEquals("인증되지 않은 사용자", bodyOf(response).get("message"));
  }

  @Test
  void getMyTokenStatistics는_principal이_User가_아니면_401을_반환한다() {
    // 명시적 instanceof 체크이므로 ClassCastException 경유가 아니다
    // (admin 사이클 REQ-002의 getCurrentUser가 캐스팅 예외로 처리되던 것과 메커니즘이 다름).
    when(authentication.getPrincipal()).thenReturn("anonymousUser");

    ResponseEntity<?> response = statisticsController.getMyTokenStatistics(authentication);

    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    assertEquals("인증되지 않은 사용자", bodyOf(response).get("message"));
  }

  @Test
  void getMyTokenStatistics는_본인_토큰_집계_4개_필드를_반환한다() {
    User alice = StatisticsTestFixtures.newUser(7L, "alice", "a@example.com", true);
    when(authentication.getPrincipal()).thenReturn(alice);
    when(analysisHistoryRepository.getTotalInputTokensByUser(7L)).thenReturn(300L);
    when(analysisHistoryRepository.getTotalOutputTokensByUser(7L)).thenReturn(120L);
    when(analysisHistoryRepository.getTotalTokensByUser(7L)).thenReturn(420L);
    when(analysisHistoryRepository.getTotalCostByUser(7L)).thenReturn(0.42);

    ResponseEntity<?> response = statisticsController.getMyTokenStatistics(authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<?, ?> body = bodyOf(response);
    assertEquals(300L, body.get("input_tokens"));
    assertEquals(120L, body.get("output_tokens"));
    assertEquals(420L, body.get("total_tokens"));
    assertEquals(0.42, ((Number) body.get("total_cost")).doubleValue(), 0.0001);
  }

  @Test
  void getMyTokenStatistics는_토큰_집계가_null이면_0으로_처리한다() {
    User alice = StatisticsTestFixtures.newUser(7L, "alice", "a@example.com", true);
    when(authentication.getPrincipal()).thenReturn(alice);

    ResponseEntity<?> response = statisticsController.getMyTokenStatistics(authentication);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<?, ?> body = bodyOf(response);
    // getTokenStatistics와 동일하게 기본값이 Long 0L로 박싱된다(삼항 수치 승격).
    assertEquals(0L, body.get("input_tokens"));
    assertEquals(0L, body.get("output_tokens"));
    assertEquals(0L, body.get("total_tokens"));
    assertEquals(0.0, ((Number) body.get("total_cost")).doubleValue(), 0.0001);
  }

  @Test
  void getMyTokenStatistics는_조회_실패_시_500과_getTokenStatistics와_동일한_메시지를_반환한다() {
    // 특성화 포인트: 서로 다른 두 엔드포인트가 동일한 메시지 텍스트("토큰 통계 조회 실패: ")를 사용하고,
    // 셀프서비스 계열임에도 400이 아니라 500(INTERNAL_SERVER_ERROR)을 사용한다.
    User alice = StatisticsTestFixtures.newUser(7L, "alice", "a@example.com", true);
    when(authentication.getPrincipal()).thenReturn(alice);
    when(analysisHistoryRepository.getTotalInputTokensByUser(7L))
        .thenThrow(new RuntimeException("DB 오류"));

    ResponseEntity<?> response = statisticsController.getMyTokenStatistics(authentication);

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertEquals("토큰 통계 조회 실패: DB 오류", bodyOf(response).get("message"));
  }
}
