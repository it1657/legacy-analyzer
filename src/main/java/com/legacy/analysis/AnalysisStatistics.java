package com.legacy.analysis;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 분석 작업의 종합 통계 정보
 *
 * <p><b>successCount / failureCount / skipCount 세 카운터의 동시성 계약</b>
 * (2026-09-quick-fixes-batch REQ-003, 설계 §4.2 B안):
 * <ul>
 *   <li>분석 루프는 파일 하나를 끝낼 때마다 <b>여러 스레드에서 동시에</b> 이 세 값을 올린다
 *       (운영 기본 스레드 수 16 — {@code app.analysis.thread-pool-size}).
 *       그래서 <b>증가 전용 메서드</b> {@code incrementSuccessCount()} /
 *       {@code incrementSkipCount()} / {@code incrementFailureCount()}를 두고,
 *       <b>병렬 루프 안에서는 반드시 이 메서드만 쓴다.</b></li>
 *   <li>증가 메서드와 <b>대응 getter가 같은 락(this)</b>에 묶여 있다. 증가만 원자화하고 getter를
 *       빼면, 필드가 plain {@code int}(volatile 아님)이라 <b>진행률을 폴링하는 스레드가 뒤로 가는
 *       값을 읽는다</b>. 즉 getter의 {@code synchronized}는 장식이 아니라 계약의 일부다.</li>
 *   <li><b>기존 setter는 남겨 두되 병렬 루프에서 쓰지 않는다.</b> setter는 Jackson 역직렬화와
 *       {@code AnalysisSessionManager.updateStatistics()}가 쓰는 단일 스레드 경로용이고,
 *       "읽어서 +1 해서 되쓰기" 형태로 쓰면 원자성이 깨진다.
 *       루프 안에 setter 직접 호출이 다시 생기지 않는지는
 *       {@code MainApiControllerCounterIncrementSingleSourceTest}가 감시한다.</li>
 *   <li>필드 타입은 {@code int} 그대로다. {@code @JsonProperty}가 필드에 붙어 있고 이 객체가
 *       {@code SessionDetailDto.statistics}로 API 응답에 실리므로 <b>직렬화 형태를 바꾸지 않는다</b>
 *       (설계 §4.2 A안 = {@code AtomicInteger} 전환은 이 이유로 기각됐다).</li>
 * </ul>
 */
public class AnalysisStatistics {

  @JsonProperty("totalFiles")
  private int totalFiles = 0;

  @JsonProperty("successCount")
  private int successCount = 0;

  @JsonProperty("failureCount")
  private int failureCount = 0;

  @JsonProperty("skipCount")
  private int skipCount = 0;

  @JsonProperty("oversizeCount")
  private int oversizeCount = 0;

  @JsonProperty("totalProcessingTimeMs")
  private long totalProcessingTimeMs = 0;

  @JsonProperty("startTime")
  private LocalDateTime startTime;

  @JsonProperty("endTime")
  private LocalDateTime endTime;

  @JsonProperty("errorBreakdown")
  private Map<String, Integer> errorBreakdown = new HashMap<>();

  @JsonProperty("averageProcessingTimeMs")
  private double averageProcessingTimeMs = 0.0;

  @JsonProperty("successRate")
  private double successRate = 0.0;

  @JsonProperty("errorCategoryMap")
  private Map<String, Integer> errorCategoryMap = new HashMap<>();

  @JsonProperty("performanceMetrics")
  private Map<String, Double> performanceMetrics = new HashMap<>();

  // 기본 생성자
  public AnalysisStatistics() {
    this.startTime = LocalDateTime.now();
  }

  // Getter/Setter
  public int getTotalFiles() {
    return totalFiles;
  }

  public void setTotalFiles(int totalFiles) {
    this.totalFiles = totalFiles;
  }

  // successCount / failureCount / skipCount — 증가 메서드와 getter가 같은 락(this)에 묶여 있다.
  // 클래스 Javadoc의 "동시성 계약" 참고. setter는 남겨 두되 병렬 루프에서는 쓰지 않는다.

  public synchronized int getSuccessCount() {
    return successCount;
  }

  public void setSuccessCount(int successCount) {
    this.successCount = successCount;
  }

  /**
   * 성공 건수를 1 올린다. <b>병렬 분석 루프는 이 메서드를 쓴다</b>
   * ({@code setSuccessCount(외부카운터값)} 형태로 대입하면 늦게 도착한 낮은 값이 큰 값을 덮어쓴다).
   */
  public synchronized void incrementSuccessCount() {
    this.successCount++;
  }

  public synchronized int getFailureCount() {
    return failureCount;
  }

  public void setFailureCount(int failureCount) {
    this.failureCount = failureCount;
  }

  /**
   * 실패 건수를 1 올린다. <b>병렬 분석 루프는 이 메서드를 쓴다</b>
   * ({@code setFailureCount(getFailureCount() + 1)} 형태는 읽기·계산·쓰기가 전부 갈라져 손실이 크다).
   */
  public synchronized void incrementFailureCount() {
    this.failureCount++;
  }

  public synchronized int getSkipCount() {
    return skipCount;
  }

  public void setSkipCount(int skipCount) {
    this.skipCount = skipCount;
  }

  /**
   * 스킵(이미 처리됨) 건수를 1 올린다. <b>병렬 분석 루프는 이 메서드를 쓴다.</b>
   */
  public synchronized void incrementSkipCount() {
    this.skipCount++;
  }

  public int getOversizeCount() {
    return oversizeCount;
  }

  public void setOversizeCount(int oversizeCount) {
    this.oversizeCount = oversizeCount;
  }

  public long getTotalProcessingTimeMs() {
    return totalProcessingTimeMs;
  }

  public void setTotalProcessingTimeMs(long totalProcessingTimeMs) {
    this.totalProcessingTimeMs = totalProcessingTimeMs;
  }

  public LocalDateTime getStartTime() {
    return startTime;
  }

  public void setStartTime(LocalDateTime startTime) {
    this.startTime = startTime;
  }

  public LocalDateTime getEndTime() {
    return endTime;
  }

  public void setEndTime(LocalDateTime endTime) {
    this.endTime = endTime;
  }

  public Map<String, Integer> getErrorBreakdown() {
    return errorBreakdown;
  }

  public void setErrorBreakdown(Map<String, Integer> errorBreakdown) {
    this.errorBreakdown = errorBreakdown;
  }

  // 에러 타입 증가
  public void incrementErrorCount(String errorType) {
    errorBreakdown.put(errorType, errorBreakdown.getOrDefault(errorType, 0) + 1);
  }

  // 통계 계산
  public void calculateStatistics() {
    // 평균 처리 시간
    if (successCount > 0) {
      this.averageProcessingTimeMs = (double) totalProcessingTimeMs / successCount;
    }

    // 성공률
    int totalProcessed = successCount + failureCount + skipCount;
    if (totalProcessed > 0) {
      this.successRate = ((double) successCount / totalProcessed) * 100;
    }
  }

  public double getSuccessRate() {
    return successRate;
  }

  public void setSuccessRate(double successRate) {
    this.successRate = successRate;
  }

  public double getAverageProcessingTimeMs() {
    return averageProcessingTimeMs;
  }

  public void setAverageProcessingTimeMs(double averageProcessingTimeMs) {
    this.averageProcessingTimeMs = averageProcessingTimeMs;
  }

  public Map<String, Integer> getErrorCategoryMap() {
    return errorCategoryMap;
  }

  public void setErrorCategoryMap(Map<String, Integer> errorCategoryMap) {
    this.errorCategoryMap = errorCategoryMap;
  }

  public void incrementErrorCategory(String category) {
    this.errorCategoryMap.put(category,
        this.errorCategoryMap.getOrDefault(category, 0) + 1);
  }

  public Map<String, Double> getPerformanceMetrics() {
    return performanceMetrics;
  }

  public void setPerformanceMetrics(Map<String, Double> performanceMetrics) {
    this.performanceMetrics = performanceMetrics;
  }

  public void recordPerformanceMetric(String metricName, double value) {
    this.performanceMetrics.put(metricName, value);
  }
}
