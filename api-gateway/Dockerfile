# syntax=docker/dockerfile:1
# Build context는 프로젝트 루트다 (Gradle Wrapper와 settings.gradle을 공유). docker-compose.yml 참고.

# ---- Build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY api-gateway/build.gradle api-gateway/
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon :api-gateway:dependencies > /dev/null

COPY api-gateway/src api-gateway/src
RUN ./gradlew --no-daemon :api-gateway:bootJar -x test && cp api-gateway/build/libs/*.jar app.jar

# ---- Runtime stage: JRE only, non-root user ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /workspace/app.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
