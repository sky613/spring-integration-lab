# Docker 실행

## 실행

```bash
cp .env.example .env    # 최초 1회. DB_PASSWORD를 변경한다 (.env는 Git에 커밋하지 않음)
docker build -t enterprise-integration-hub:local .
docker compose up -d --build
curl http://localhost:8080/actuator/health   # {"status":"UP",...}
docker compose down        # 데이터 유지
docker compose down -v     # DB 볼륨까지 삭제
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

| 서비스 | 항목 | 값 |
|--------|------|----|
| postgres | 이미지 | `postgres:17-alpine` |
| postgres | 포트 | `${DB_PORT:-5432}:5432` |
| postgres | 볼륨 | `postgres-data` (컨테이너를 재생성해도 데이터 유지) |
| postgres | healthcheck | `pg_isready` |
| app | 포트 | `${APP_PORT:-8080}:8080` |
| app | `depends_on` | postgres가 healthy가 된 뒤 시작 |
| app | DB 접속 | `DB_URL=jdbc:postgresql://postgres:5432/...`, `DB_USERNAME`, `DB_PASSWORD` (`.env`에서 주입) |
| app | `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=75` |

`DB_PASSWORD`가 없으면 `docker compose`가 오류를 내며 시작하지 않습니다.

DB 볼륨은 최초 생성 시점의 비밀번호로 초기화됩니다. 나중에 `.env`의 비밀번호를 바꾸면 `docker compose down -v`로 볼륨을 다시 만들어야 합니다.
