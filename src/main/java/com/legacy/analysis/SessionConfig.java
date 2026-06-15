package com.legacy.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 세션 및 에러 핸들링 설정값 관리
 */
@Component
@ConfigurationProperties(prefix = "app.analysis")
public class SessionConfig {

  // 에러 핸들링 설정
  private int maxRetries = 5;
  private long initialRetryDelayMs = 1000;
  private long maxRetryDelayMs = 30000;
  private int connectTimeoutSec = 10;
  private int readTimeoutSec = 30;

  // 세션 관리 설정
  private int sessionTimeoutMinutes = 240;
  private int autoSaveIntervalSec = 30;
  private boolean enableSessionPersistence = true;
  private String sessionStoragePath = ".analysis-sessions";

  // Getter/Setter
  public int getMaxRetries() {
    return maxRetries;
  }

  public void setMaxRetries(int maxRetries) {
    this.maxRetries = maxRetries;
  }

  public long getInitialRetryDelayMs() {
    return initialRetryDelayMs;
  }

  public void setInitialRetryDelayMs(long initialRetryDelayMs) {
    this.initialRetryDelayMs = initialRetryDelayMs;
  }

  public long getMaxRetryDelayMs() {
    return maxRetryDelayMs;
  }

  public void setMaxRetryDelayMs(long maxRetryDelayMs) {
    this.maxRetryDelayMs = maxRetryDelayMs;
  }

  public int getConnectTimeoutSec() {
    return connectTimeoutSec;
  }

  public void setConnectTimeoutSec(int connectTimeoutSec) {
    this.connectTimeoutSec = connectTimeoutSec;
  }

  public int getReadTimeoutSec() {
    return readTimeoutSec;
  }

  public void setReadTimeoutSec(int readTimeoutSec) {
    this.readTimeoutSec = readTimeoutSec;
  }

  public int getSessionTimeoutMinutes() {
    return sessionTimeoutMinutes;
  }

  public void setSessionTimeoutMinutes(int sessionTimeoutMinutes) {
    this.sessionTimeoutMinutes = sessionTimeoutMinutes;
  }

  public int getAutoSaveIntervalSec() {
    return autoSaveIntervalSec;
  }

  public void setAutoSaveIntervalSec(int autoSaveIntervalSec) {
    this.autoSaveIntervalSec = autoSaveIntervalSec;
  }

  public boolean isEnableSessionPersistence() {
    return enableSessionPersistence;
  }

  public void setEnableSessionPersistence(boolean enableSessionPersistence) {
    this.enableSessionPersistence = enableSessionPersistence;
  }

  public String getSessionStoragePath() {
    return sessionStoragePath;
  }

  public void setSessionStoragePath(String sessionStoragePath) {
    this.sessionStoragePath = sessionStoragePath;
  }
}
