# Docker 실행

`core-api`(Spring Boot), `postgres`(PostgreSQL 17), `redis`(Redis 7.4), `mock-erp`·`mock-mes`(WireMock 3.13)를 Docker Compose로 함께 실행합니다. 설계 배경과 검증 결과는 [step4-docker-compose.md](step4-docker-compose.md), ERP/MES 연계는 [step5-erp-mes-integration.md](step5-erp-mes-integration.md)를 참고하세요.

## 실행

```bash
cp .env.example .env              # 최초 1회. DB_PASSWORD, REDIS_PASSWORD를 변경한다
docker compose up -d --build      # 이미지 빌드 후 5개 서비스 실행
docker compose ps                 # 상태 확인 (모두 healthy가 되면 준비 완료)
curl http://localhost:8080/actuator/health
docker compose down               # 컨테이너, 네트워크 삭제 (DB 데이터 유지)
docker compose down -v            # DB 볼륨까지 삭제 (데이터 초기화)
```

- `.env`에 `DB_PASSWORD`나 `REDIS_PASSWORD`가 없으면 `docker compose`가 오류를 내며 시작하지 않습니다.
- DB 볼륨은 최초 생성 시점의 비밀번호로 초기화됩니다. 나중에 `.env`의 `DB_PASSWORD`를 바꾸면 `docker compose down -v`로 볼륨을 다시 만들어야 합니다.
- 포트가 이미 사용 중이면 `.env`에서 `APP_PORT`, `DB_PORT`, `REDIS_PORT`, `ERP_PORT`, `MES_PORT`를 바꿉니다. 사용 여부는 `lsof -iTCP:8080 -sTCP:LISTEN`으로 확인합니다.

## 이미지 구성 (`Dockerfile`)

| Stage | Base Image | 내용 |
|-------|-----------|------|
| build | `eclipse-temurin:21-jdk` | Gradle 스크립트를 먼저 복사해 의존성 Layer를 캐시하고, 소스 복사 후 `bootJar` 생성 (`-x test`) |
| runtime | `eclipse-temurin:21-jre` | 빌드 결과 jar만 복사. JDK·Gradle·소스는 포함하지 않음. non-root `app` 사용자로 실행 |

- 테스트는 이미지 빌드에서 실행하지 않으므로 `./gradlew test`로 따로 수행합니다.
- `.dockerignore`가 `.env`, `.git`, `build` 등을 빌드 컨텍스트에서 제외합니다. 그래서 비밀번호 파일은 이미지에 들어가지 않습니다.

## Compose 구성 (`docker-compose.yml`)

| 서비스 | 이미지 | 호스트 포트 | Healthcheck | 시작 조건 |
|--------|--------|-------------|-------------|-----------|
| postgres | `postgres:17-alpine` | `127.0.0.1:${DB_PORT:-5432}` | `pg_isready` | |
| redis | `redis:7.4-alpine` | `127.0.0.1:${REDIS_PORT:-6379}` | `redis-cli ping` (인증 포함) | |
| mock-erp | `wiremock/wiremock:3.13.1` | `127.0.0.1:${ERP_PORT:-8081}` | `curl /__admin/health` | |
| mock-mes | `wiremock/wiremock:3.13.1` | `127.0.0.1:${MES_PORT:-8082}` | `curl /__admin/health` | |
| core-api | `enterprise-integration-hub:local` (빌드) | `127.0.0.1:${APP_PORT:-8080}` | `curl /actuator/health/readiness` | postgres, redis가 healthy |

- core-api는 mock-erp, mock-mes를 `depends_on`에 넣지 않습니다. 외부 시스템이 꺼져 있어도 core-api는 실행되고, 연계만 `FAILED`로 기록됩니다.
- Mock 응답은 `mock/erp/mappings`, `mock/mes/mappings`의 JSON을 읽기 전용으로 마운트합니다. 수정 후 `docker compose restart mock-erp mock-mes`로 반영합니다.

| 리소스 | 이름 | 설명 |
|--------|------|------|
| Network | `integration-hub_backend` (bridge) | 모든 서비스가 서비스 이름(`postgres`, `redis`, `mock-erp`, `mock-mes`)으로 서로 접근 |
| Volume | `integration-hub_postgres-data` | PostgreSQL 데이터 디렉터리. `down`으로 지워지지 않음 |

core-api에 전달되는 환경 변수

| 변수 | 값 |
|------|----|
| `DB_URL` | `jdbc:postgresql://postgres:5432/${DB_NAME}` |
| `DB_USERNAME`, `DB_PASSWORD` | `.env`에서 주입 |
| `REDIS_HOST`, `REDIS_PORT` | `redis`, `6379` (컨테이너 내부 포트) |
| `REDIS_PASSWORD` | `.env`에서 주입 |
| `ERP_BASE_URL`, `MES_BASE_URL` | `http://mock-erp:8080`, `http://mock-mes:8080` |

## 로그 확인

| 목적 | 명령 |
|------|------|
| 전체 서비스 로그 실시간 확인 | `docker compose logs -f` |
| 특정 서비스만 | `docker compose logs -f core-api` |
| 최근 N줄 | `docker compose logs --tail 100 core-api` |
| 특정 시점 이후 | `docker compose logs --since 10m core-api` |
| 타임스탬프 표시 | `docker compose logs -t postgres` |
| 키워드 검색 | `docker compose logs core-api \| grep -E 'ERROR\|WARN'` |
| 실행 SQL 확인 | `docker compose logs -f core-api \| grep -A10 'org.hibernate.SQL'` |
| 컨테이너 이름으로 확인 | `docker logs -f integration-hub-core-api` |
| Healthcheck 결과 | `docker inspect integration-hub-core-api --format '{{json .State.Health}}'` |
| 상태와 재시작 여부 | `docker compose ps -a` |
| 컨테이너 내부 접속 | `docker compose exec postgres psql -U integrationhub -d integrationhub` |

- 로그는 Docker 기본 `json-file` 드라이버로 저장됩니다.
- `docker compose down`으로 컨테이너를 삭제하면 로그도 함께 사라집니다. 오래 보관해야 하는 로그는 별도 수집 도구로 옮겨야 합니다.

## 문제 해결

| 증상 | 원인과 조치 |
|------|-------------|
| `required variable DB_PASSWORD is missing a value` | `.env`가 없음 → `cp .env.example .env` |
| core-api가 `unhealthy` 또는 재시작 반복 | `docker compose logs core-api`로 원인 확인 (DB 인증 실패, Flyway 오류 등) |
| `password authentication failed` | 볼륨 생성 후 `DB_PASSWORD`를 바꿈 → `docker compose down -v` 후 재실행 |
| `port is already allocated` | `.env`에서 해당 포트 변경 |
