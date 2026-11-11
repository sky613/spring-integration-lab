# Docker 실행

`api-gateway`(Spring Cloud Gateway), `core-api`(Spring Boot), `postgres`(PostgreSQL 17), `redis`(Redis 7.4), `mock-erp`·`mock-mes`(WireMock 3.13)를 Docker Compose로 함께 실행합니다. 설계 배경과 검증 결과는 [step4-docker-compose.md](step4-docker-compose.md), ERP/MES 연계는 [step5-erp-mes-integration.md](step5-erp-mes-integration.md), Gateway는 [step6-api-gateway.md](step6-api-gateway.md)를 참고하세요.

## 실행

```bash
cp .env.example .env              # 최초 1회. DB_PASSWORD, REDIS_PASSWORD를 변경한다
docker compose up -d --build      # 이미지 2개(core-api, api-gateway) 빌드 후 6개 서비스 실행
docker compose ps                 # 상태 확인 (모두 healthy가 되면 준비 완료)
curl http://localhost:8080/actuator/health     # Gateway 자신의 Health
curl http://localhost:8080/api/orders          # Gateway → core-api
docker compose down               # 컨테이너, 네트워크 삭제 (DB 데이터 유지)
docker compose down -v            # DB 볼륨까지 삭제 (데이터 초기화)
```

- `.env`에 `DB_PASSWORD`나 `REDIS_PASSWORD`가 없으면 `docker compose`가 오류를 내며 시작하지 않습니다.
- DB 볼륨은 최초 생성 시점의 비밀번호로 초기화됩니다. 나중에 `.env`의 `DB_PASSWORD`를 바꾸면 `docker compose down -v`로 볼륨을 다시 만들어야 합니다.
- 포트가 이미 사용 중이면 `.env`에서 `GATEWAY_PORT`, `DB_PORT`, `REDIS_PORT`를 바꿉니다. 사용 여부는 `lsof -iTCP:8080 -sTCP:LISTEN`으로 확인합니다.

## 이미지 구성 (`Dockerfile`, `api-gateway/Dockerfile`)

두 이미지 모두 프로젝트 루트를 Build Context로 사용합니다. Gradle Wrapper와 `settings.gradle`을 함께 쓰기 때문입니다. 각 이미지는 자기 프로젝트만 빌드합니다(`:bootJar`, `:api-gateway:bootJar`).

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
| mock-erp | `wiremock/wiremock:3.13.1` | 없음 (내부 전용) | `curl /__admin/health` | |
| mock-mes | `wiremock/wiremock:3.13.1` | 없음 (내부 전용) | `curl /__admin/health` | |
| core-api | `enterprise-integration-hub:local` (빌드) | 없음 (Gateway 경유) | `curl /actuator/health/readiness` | postgres, redis가 healthy |
| api-gateway | `enterprise-integration-hub-gateway:local` (빌드) | `127.0.0.1:${GATEWAY_PORT:-8080}` | `curl /actuator/health/readiness` | core-api, redis가 healthy |
| keycloak | `quay.io/keycloak/keycloak:26.4.5` (`start-dev`, 개발 전용) | `127.0.0.1:${KEYCLOAK_PORT:-8180}` | `/health/ready` (관리 포트 9000, bash `/dev/tcp`) | |

- Keycloak은 브라우저(클라이언트)가 직접 로그인해야 하므로 호스트 포트를 엽니다. Gateway를 거치지 않습니다.
- core-api는 keycloak을 `depends_on`에 넣지 않습니다. 서명 검증용 공개키(JWKS)는 첫 토큰 검증 시점에 가져옵니다.
- Keycloak 데이터는 컨테이너 내부(dev-file)에만 있어 `down` 후 `up`하면 Realm 파일에서 다시 가져옵니다.

- core-api는 mock-erp, mock-mes를 `depends_on`에 넣지 않습니다. 외부 시스템이 꺼져 있어도 core-api는 실행되고, 연계만 `FAILED`로 기록됩니다.
- Mock 응답은 `mock/erp/mappings`, `mock/mes/mappings`의 JSON을 읽기 전용으로 마운트합니다. 수정 후 `docker compose restart mock-erp mock-mes`로 반영합니다.

| 리소스 | 이름 | 설명 |
|--------|------|------|
| Network | `integration-hub_backend` (bridge) | api-gateway, core-api, postgres, redis, keycloak |
| Network | `integration-hub_integration` (bridge) | core-api, mock-erp, mock-mes. Gateway는 여기에 없으므로 ERP/MES에 접근할 수 없음 |
| Volume | `integration-hub_postgres-data` | PostgreSQL 데이터 디렉터리. `down`으로 지워지지 않음 |

core-api에 전달되는 환경 변수

| 변수 | 값 |
|------|----|
| `DB_URL` | `jdbc:postgresql://postgres:5432/${DB_NAME}` |
| `DB_USERNAME`, `DB_PASSWORD` | `.env`에서 주입 |
| `REDIS_HOST`, `REDIS_PORT` | `redis`, `6379` (컨테이너 내부 포트) |
| `REDIS_PASSWORD` | `.env`에서 주입 |
| `ERP_BASE_URL`, `MES_BASE_URL` | `http://mock-erp:8080`, `http://mock-mes:8080` |

core-api의 JWT 검증 설정

| 변수 | 값 |
|------|----|
| `OAUTH2_ISSUER_URI` | `http://localhost:${KEYCLOAK_PORT}/realms/integration-hub` (토큰의 `iss`, 외부 주소) |
| `OAUTH2_JWK_SET_URI` | `http://keycloak:8080/realms/integration-hub/protocol/openid-connect/certs` (내부 주소) |

api-gateway에 전달되는 환경 변수

| 변수 | 값 |
|------|----|
| `SERVER_PORT` | `8080` (로컬 실행 기본값은 8000) |
| `CORE_API_URL` | `http://core-api:8080` |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Rate Limiter 상태 저장용 |
| (선택) `RATE_LIMIT_REPLENISH_RATE`, `RATE_LIMIT_BURST_CAPACITY`, `GATEWAY_RESPONSE_TIMEOUT` | 기본 10, 20, 10s |

## 로그 확인

| 목적 | 명령 |
|------|------|
| 전체 서비스 로그 실시간 확인 | `docker compose logs -f` |
| 특정 서비스만 | `docker compose logs -f core-api` |
| 최근 N줄 | `docker compose logs --tail 100 core-api` |
| 특정 시점 이후 | `docker compose logs --since 10m core-api` |
| 타임스탬프 표시 | `docker compose logs -t postgres` |
| 키워드 검색 | `docker compose logs core-api \| grep -E 'ERROR\|WARN'` |
| 요청 ID로 Gateway·core-api 로그 추적 | `docker compose logs api-gateway core-api \| grep <X-Request-Id>` |
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
