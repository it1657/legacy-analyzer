package com.legacy.analysis;

/**
 * Claude API 토큰 사용량 정보를 저장하는 클래스
 */
public class TokenUsage {
    private long inputTokens;
    private long outputTokens;
    private long totalTokens;
    private String modelName;

    public TokenUsage(long inputTokens, long outputTokens, String modelName) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = inputTokens + outputTokens;
        this.modelName = modelName;
    }

    public TokenUsage() {
        this.inputTokens = 0;
        this.outputTokens = 0;
        this.totalTokens = 0;
        this.modelName = "";
    }

    // Getter/Setter
    public long getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(long inputTokens) {
        this.inputTokens = inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(long outputTokens) {
        this.outputTokens = outputTokens;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    // 토큰 누적 메서드
    public void add(TokenUsage other) {
        if (other != null) {
            this.inputTokens += other.inputTokens;
            this.outputTokens += other.outputTokens;
            this.totalTokens += other.totalTokens;
            if (other.modelName != null && !other.modelName.isEmpty()) {
                this.modelName = other.modelName;
            }
        }
    }

    @Override
    public String toString() {
        return "TokenUsage{" +
                "inputTokens=" + inputTokens +
                ", outputTokens=" + outputTokens +
                ", totalTokens=" + totalTokens +
                ", modelName='" + modelName + '\'' +
                '}';
    }
}
