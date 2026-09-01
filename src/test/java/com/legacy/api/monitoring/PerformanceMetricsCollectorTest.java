package com.legacy.api.monitoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PerformanceMetricsCollector를 mock 없이 직접 인스턴스화해 검증한다(순수 컴포넌트 테스트).
 * 02-design-v1 6.5절 근거.
 */
class PerformanceMetricsCollectorTest {

  private static final String SESSION_A = "session-A";
  private static final String SESSION_B = "session-B";
  private static final String FILE = "src/main/java/Foo.java";

  private PerformanceMetricsCollector collector;

  @BeforeEach
  void setUp() {
    collector = new PerformanceMetricsCollector();
  }

  /** 처리 시간이 확실히 0보다 크도록 잠시 대기한다. */
  private static void sleepBriefly() {
    try {
      Thread.sleep(5);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void startFileAnalysis와_endFileAnalysis가_정상_왕복하면_처리시간과_파일수가_누적된다() {
    collector.startFileAnalysis(SESSION_A, FILE);
    sleepBriefly();

    long processingTime = collector.endFileAnalysis(SESSION_A, FILE);

    assertThat(processingTime).isGreaterThan(0L);
    Map<String, Object> metrics = collector.getSessionMetrics(SESSION_A);
    assertEquals(processingTime, metrics.get("totalProcessingTime"));
    assertEquals(1, metrics.get("processedFiles"));
    @SuppressWarnings("unchecked")
    List<String> filesInProgress = (List<String>) metrics.get("filesInProgress");
    assertThat(filesInProgress).doesNotContain(FILE);
  }

  @Test
  void endFileAnalysis를_start_없이_호출하면_0을_반환하고_메트릭이_생성되지_않는다() {
    long processingTime = collector.endFileAnalysis(SESSION_A, FILE);

    assertEquals(0L, processingTime);
    assertThat(collector.getSessionMetrics(SESSION_A)).isEmpty();
  }

  @Test
  void startFileAnalysis는_진행중_파일_목록에_파일을_누적한다() {
    collector.startFileAnalysis(SESSION_A, "a1.java");
    collector.startFileAnalysis(SESSION_A, "a2.java");

    @SuppressWarnings("unchecked")
    List<String> filesInProgress =
        (List<String>) collector.getSessionMetrics(SESSION_A).get("filesInProgress");
    assertThat(filesInProgress).containsExactly("a1.java", "a2.java");
  }

  @Test
  void endFileAnalysis를_같은_파일에_두_번_호출하면_두번째는_0을_반환하고_카운트가_늘지_않는다() {
    // 시작 시각이 fileStartTimes에서 remove되므로 두 번째 호출은 조기 반환된다.
    collector.startFileAnalysis(SESSION_A, FILE);
    sleepBriefly();
    collector.endFileAnalysis(SESSION_A, FILE);

    long secondCall = collector.endFileAnalysis(SESSION_A, FILE);

    assertEquals(0L, secondCall);
    assertEquals(1, collector.getSessionMetrics(SESSION_A).get("processedFiles"));
  }

  @Test
  void 관찰케이스_다른_세션_ID로_종료하면_시간은_계산되지만_어느_세션에도_기록되지_않는다() {
    // PL 추가 관찰 케이스(게이트1에서 필수화하지 않았으나 포함). 판단이 아니라 관찰 기록이 목적.
    // fileStartTimes가 sessionId가 아닌 filePath만으로 키를 관리하므로,
    // 시작한 세션(A)과 다른 세션(B)으로 종료해도 처리 시간 자체는 정상 계산되어 반환된다.
    // 그러나 sessionMetrics.get(B)가 null이라 B에는 누적되지 않고,
    // A에도 processedFiles/totalProcessingTime이 남지 않으며 filesInProgress에는 파일이 그대로 남는다.
    collector.startFileAnalysis(SESSION_A, FILE);
    sleepBriefly();

    long processingTime = collector.endFileAnalysis(SESSION_B, FILE);

    assertThat(processingTime).isGreaterThan(0L);
    assertThat(collector.getSessionMetrics(SESSION_B)).isEmpty();
    Map<String, Object> metricsA = collector.getSessionMetrics(SESSION_A);
    assertThat(metricsA).doesNotContainKey("processedFiles");
    assertThat(metricsA).doesNotContainKey("totalProcessingTime");
    @SuppressWarnings("unchecked")
    List<String> filesInProgress = (List<String>) metricsA.get("filesInProgress");
    assertThat(filesInProgress).containsExactly(FILE); // 진행 중 목록에 계속 남아 있다
  }

  @Test
  void recordMemoryUsage는_메모리_사용량_4개_키를_합리적인_범위로_기록한다() {
    collector.recordMemoryUsage(SESSION_A);

    Map<String, Object> metrics = collector.getSessionMetrics(SESSION_A);
    assertThat(metrics).containsKeys("usedMemoryMB", "maxMemoryMB", "memoryUsagePercent",
        "lastMemoryCheckTime");
    assertThat((Long) metrics.get("usedMemoryMB")).isGreaterThanOrEqualTo(0L);
    assertThat((Long) metrics.get("maxMemoryMB")).isGreaterThanOrEqualTo(0L);
    double percent = (Double) metrics.get("memoryUsagePercent");
    assertTrue(percent >= 0.0 && percent <= 100.0, "memoryUsagePercent=" + percent);
    assertThat(metrics.get("lastMemoryCheckTime")).isInstanceOf(LocalDateTime.class);
  }

  @Test
  void getSessionMetrics가_반환한_맵을_수정해도_내부_상태는_변하지_않는다() {
    collector.recordMemoryUsage(SESSION_A);

    Map<String, Object> firstCall = collector.getSessionMetrics(SESSION_A);
    firstCall.put("usedMemoryMB", -999L);
    firstCall.put("injected", "x");

    Map<String, Object> secondCall = collector.getSessionMetrics(SESSION_A);
    assertThat(secondCall).doesNotContainKey("injected");
    assertThat((Long) secondCall.get("usedMemoryMB")).isGreaterThanOrEqualTo(0L);
  }

  @Test
  void getSystemMetrics는_활성_세션_수와_처리_파일_수_합계를_반환한다() {
    collector.startFileAnalysis(SESSION_A, "a1.java");
    collector.endFileAnalysis(SESSION_A, "a1.java");
    collector.startFileAnalysis(SESSION_A, "a2.java");
    collector.endFileAnalysis(SESSION_A, "a2.java");
    collector.startFileAnalysis(SESSION_B, "b1.java");
    collector.endFileAnalysis(SESSION_B, "b1.java");

    Map<String, Object> systemMetrics = collector.getSystemMetrics();

    assertEquals(2, systemMetrics.get("activeSessions"));
    assertEquals(3, systemMetrics.get("totalFilesProcessed"));
    assertThat(systemMetrics.get("timestamp")).isInstanceOf(LocalDateTime.class);
  }

  @Test
  void calculatePerformanceStats는_세션이_없으면_완전히_빈_맵을_반환한다() {
    // memoryUsagePercent 키조차 `if (metrics != null)` 블록 안에 있으므로 키가 하나도 없다(특성화 포인트).
    Map<String, Double> stats = collector.calculatePerformanceStats("unknown-session");

    assertThat(stats).isEmpty();
  }

  @Test
  void calculatePerformanceStats는_처리한_파일이_없으면_memoryUsagePercent만_반환한다() {
    collector.recordMemoryUsage(SESSION_A); // 세션은 존재하지만 processedFiles는 없음

    Map<String, Double> stats = collector.calculatePerformanceStats(SESSION_A);

    assertThat(stats).containsOnlyKeys("memoryUsagePercent");
  }

  @Test
  void calculatePerformanceStats는_처리한_파일이_있으면_3개_키를_모두_반환한다() {
    collector.startFileAnalysis(SESSION_A, "a1.java");
    sleepBriefly();
    collector.endFileAnalysis(SESSION_A, "a1.java");
    collector.startFileAnalysis(SESSION_A, "a2.java");
    sleepBriefly();
    collector.endFileAnalysis(SESSION_A, "a2.java");
    collector.recordMemoryUsage(SESSION_A);

    Map<String, Double> stats = collector.calculatePerformanceStats(SESSION_A);

    assertThat(stats).containsOnlyKeys("averageTimePerFile", "filesPerSecond", "memoryUsagePercent");
    long totalTime = (Long) collector.getSessionMetrics(SESSION_A).get("totalProcessingTime");
    assertEquals((double) totalTime / 2, stats.get("averageTimePerFile"), 0.0001);
    // filesPerSecond는 분모가 Math.max(1, totalTime/1000.0)이라 1초 미만이면 분모가 1.0으로 고정된다.
    assertEquals(2 / Math.max(1, totalTime / 1000.0), stats.get("filesPerSecond"), 0.0001);
  }
}
