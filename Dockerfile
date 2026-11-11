# syntax=docker/dockerfile:1

# ---- Build stage: compile and package with JDK 21 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy build scripts first so the dependency layer is cached between source changes
# settings.gradle includes api-gateway, so its build script must exist; only the root (core-api) project is built here
COPY gradlew settings.gradle build.gradle ./
COPY api-gateway/build.gradle api-gateway/
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon :dependencies > /dev/null

COPY src src
# Tests run via ./gradlew test outside the image; skip them here to keep image builds fast
RUN ./gradlew --no-daemon :bootJar -x test && cp build/libs/*.jar app.jar

# ---- Runtime stage: JRE only, non-root user ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /workspace/app.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
