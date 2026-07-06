# ===================================================================
# 1단계: 빌드 스테이지 - Gradle로 실행 가능한 JAR 생성
# ===================================================================
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /workspace

# 의존성 캐시 레이어 분리 (소스 변경 시에도 의존성은 재다운로드하지 않도록)
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon || true

# 소스 복사 후 실제 빌드 (테스트는 이미지 빌드 단계에서 생략)
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# ===================================================================
# 2단계: 실행 스테이지 - JRE만 포함한 경량 이미지
# ===================================================================
FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=builder /workspace/build/libs/*-SNAPSHOT.jar app.jar

EXPOSE 8803

ENTRYPOINT ["java", "-jar", "app.jar"]
