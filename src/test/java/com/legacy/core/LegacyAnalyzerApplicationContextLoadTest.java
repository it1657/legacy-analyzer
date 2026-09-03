package com.legacy.core;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Spring Boot 전체 컨텍스트가 정상적으로 로드되는지 검증하는 영구 회귀 테스트.
 * 2026-09-rag-service-boot-fix(REQ-001) 재발 방지: 이 사건 이전까지 모든 테스트가 Spring
 * 컨테이너 없이 new로 직접 인스턴스를 생성해 검증했기 때문에, 생성자 다중화로 인한
 * BeanInstantiationException(앱 완전 기동 실패)이 단위 테스트 단계에서 전혀 검출되지
 * 않았다. 이 테스트는 매 ./gradlew test 실행마다 전체 빈 그래프를 실제로 조립해 이런
 * 유형의 결함을 조기에 잡는다. 실제 Postgres/외부 API 없이도 항상 안전하게 돌 수 있도록
 * h2 프로필 + 인메모리 데이터소스로 오버라이드한다(외부 인프라 의존 없음).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("h2")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:contextLoadTest;DB_CLOSE_DELAY=-1;MODE=MySQL"
})
class LegacyAnalyzerApplicationContextLoadTest {

  @Test
  void contextLoads() {
    // 컨텍스트 로딩 자체가 예외 없이 끝나면 성공 — 별도 assertion 불필요(Spring Boot 관례).
  }
}
