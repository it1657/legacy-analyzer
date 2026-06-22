package com.legacy.core;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

import java.net.Socket;

/**
 * 앱 시작 시 localhost:5432 포트를 감지해 DB 프로필을 자동 선택한다.
 * - PostgreSQL 컨테이너(도커) 실행 중 → postgres 프로필
 * - PostgreSQL 미실행(인텔리제이 로컬) → h2 프로필
 * -Dspring.profiles.active=xxx 로 수동 지정한 경우 자동 선택을 건너뜀.
 */
public class DatasourceAutoSelector implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

  private static final String POSTGRES_HOST = "127.0.0.1";
  private static final int POSTGRES_PORT = 5432;
  private static final int CONNECT_TIMEOUT_MS = 500;

  @Override
  public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
    ConfigurableEnvironment env = event.getEnvironment();

    // 이미 프로필이 명시적으로 지정된 경우 자동 선택 건너뜀
    if (env.getActiveProfiles().length > 0) {
      System.out.println("[DB AutoSelect] 프로필 수동 지정 감지: "
          + String.join(", ", env.getActiveProfiles()) + " → 자동 선택 건너뜀");
      return;
    }

    if (isPostgresAvailable()) {
      env.addActiveProfile("postgres");
      System.out.println("[DB AutoSelect] PostgreSQL(:" + POSTGRES_PORT + ") 감지 → postgres 프로필 활성화");
    } else {
      env.addActiveProfile("h2");
      System.out.println("[DB AutoSelect] PostgreSQL 미실행 → h2 프로필 활성화");
    }
  }

  private boolean isPostgresAvailable() {
    try (Socket socket = new Socket()) {
      socket.connect(
          new java.net.InetSocketAddress(POSTGRES_HOST, POSTGRES_PORT),
          CONNECT_TIMEOUT_MS
      );
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
