/* [AI 한글 주석 보완 완료] */
// 대량 분석 요청 DTO
package com.legacy;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 대량 분석 요청 정보 (향후 기능용)
 */
public class BatchAnalysisRequestDto {

  @JsonProperty("sourcePaths")
  private List<String> sourcePaths = new ArrayList<>();

  @JsonProperty("outputPath")
  private String outputPath;

  @JsonProperty("forceActive")
  private boolean forceActive = false;

  @JsonProperty("priority")
  private int priority = 5;

  @JsonProperty("metadata")
  private Map<String, Object> metadata = new HashMap<>();

  public BatchAnalysisRequestDto() {
  }

  /**
   * 입력 검증
   */
  public void validate() throws IllegalArgumentException {
    if (sourcePaths == null || sourcePaths.isEmpty()) {
      throw new IllegalArgumentException("분석 대상 경로가 비어있습니다.");
    }

    if (outputPath == null || outputPath.trim().isEmpty()) {
      throw new IllegalArgumentException("출력 경로가 비어있습니다.");
    }

    if (priority < 1 || priority > 10) {
      throw new IllegalArgumentException("우선순위는 1부터 10 사이여야 합니다.");
    }
  }

  // Getters and Setters
  public List<String> getSourcePaths() {
    return sourcePaths;
  }

  public void setSourcePaths(List<String> sourcePaths) {
    this.sourcePaths = sourcePaths;
  }

  public String getOutputPath() {
    return outputPath;
  }

  public void setOutputPath(String outputPath) {
    this.outputPath = outputPath;
  }

  public boolean isForceActive() {
    return forceActive;
  }

  public void setForceActive(boolean forceActive) {
    this.forceActive = forceActive;
  }

  public int getPriority() {
    return priority;
  }

  public void setPriority(int priority) {
    this.priority = priority;
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }

  public void setMetadata(Map<String, Object> metadata) {
    this.metadata = metadata;
  }
}
