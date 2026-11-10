# Docker 실행

## 실행

```bash
docker build -t enterprise-integration-hub:local .
docker compose up -d --build
curl http://localhost:8080/actuator/health   # {"status":"UP",...}
docker compose down
```

호스트의 8080 포트가 이미 사용 중이면 다른 포트로 매핑합니다.

```bash
APP_PORT=18080 docker compose up -d
```

포트 사용 여부 확인: `lsof -iTCP:8080 -sTCP:LISTEN`

## 이미지 구성 (`Dockerfile`)

| Stage | Base Image | 내용 |
|-------|-----------|------|
| build | `eclipse-temurin:21-jdk` | 의존성 Layer 캐시 후 `bootJar` 생성 (테스트는 `-x test`로 생략) |
| runtime | `eclipse-temurin:21-jre` | JRE만 포함, non-root `app` 사용자로 실행 |

- 테스트는 이미지 빌드에서 실행하지 않으므로 `./gradlew test`로 별도 수행합니다.
- 일반 `jar` 태스크는 비활성화되어 실행 가능한 boot jar만 생성됩니다.

## Compose 설정 (`docker-compose.yml`)

| 항목 | 값 |
|------|----|
| 포트 | `${APP_PORT:-8080}:8080` |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=75` |
| 재시작 정책 | `unless-stopped` |
