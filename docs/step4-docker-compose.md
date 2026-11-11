# STEP 4. Docker Compose 통합 환경

Spring Boot(`core-api`), PostgreSQL, Redis를 하나의 Compose 프로젝트(`integration-hub`)로 묶습니다. 실행 방법과 로그 명령은 [docker.md](docker.md)에 정리했습니다.

## 구성도

```
                        호스트 (127.0.0.1만 바인딩)
          :8080                 :5432                 :6379
            │                     │                     │
┌───────────┼─────────────────────┼─────────────────────┼──────────┐
│  Network: integration-hub_backend (bridge, 내부 DNS)             │
│           │                     │                     │          │
│     ┌─────┴─────┐  postgres:5432 ┌┴──────────┐ redis:6379 ┌┴───────┐│
│     │ core-api  │ ─────────────▶ │ postgres  │            │ redis  ││
│     │ (JRE 21)  │ ──────────────────────────────────────▶ │  7.4   ││
│     └───────────┘                └─────┬─────┘            └────────┘│
└────────────────────────────────────────┼─────────────────────────┘
                                         │
                       Volume: integration-hub_postgres-data
                       (/var/lib/postgresql/data)
```

## 요구사항별 구현

| # | 요구사항 | 구현 |
|---|----------|------|
| 1 | Multi-stage Dockerfile | build(JDK 21 + Gradle) → runtime(JRE 21, non-root). 소스와 빌드 도구는 최종 이미지에 포함되지 않음 |
| 2 | 서비스 정의 | `core-api`, `postgres`, `redis` |
| 3 | Container Network | 사용자 정의 bridge 네트워크 `backend`. 서비스 이름이 호스트 이름이 됨 (`jdbc:postgresql://postgres:5432/...`) |
| 4 | Named Volume | `postgres-data` → `/var/lib/postgresql/data` |
| 5 | 환경변수 기반 DB 연결 | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` → `application.yml`의 `${...}` |
| 6 | PostgreSQL Healthcheck | `pg_isready -U ... -d ...` (5초 간격, 10회) |
| 7 | 시작 순서 | `depends_on: condition: service_healthy`. DB와 Redis가 **접속 가능**해진 뒤에 core-api 시작 |
| 8 | Redis 연결 | `spring-boot-starter-data-redis` + `REDIS_HOST/PORT/PASSWORD`. Actuator `redis` health로 연결만 확인 |
| 9 | localhost 바인딩 | `127.0.0.1:8080`, `127.0.0.1:5432`, `127.0.0.1:6379` |
| 10 | `.env.example` | DB·Redis 비밀번호, 포트 예시 (실제 값 없음) |
| 11 | 비밀번호 커밋 방지 | `.gitignore`의 `.env`, `.env.*` (단, `!.env.example`). `.dockerignore`에서도 `.env` 제외. 필수 변수가 없으면 Compose 실행 거부 (`${VAR:?message}`) |
| 12 | 로그 확인 | [docker.md](docker.md#로그-확인) |

### `depends_on`만으로는 부족한 이유
`depends_on`에 조건을 주지 않으면 컨테이너 **프로세스가 시작된 시점**에 다음 서비스가 시작됩니다. PostgreSQL은 프로세스가 시작된 뒤에도 초기화(initdb) 중일 수 있어, 이때 접속하면 Spring Boot가 `Connection refused`로 종료됩니다. 그래서 `condition: service_healthy`로 Healthcheck가 성공할 때까지 기다립니다. `restart: unless-stopped`는 그래도 실패했을 때 재시도하는 안전장치입니다.

### 127.0.0.1 바인딩
| 설정 | 접근 가능 범위 |
|------|----------------|
| `"8080:8080"` | 호스트의 모든 네트워크 인터페이스 (0.0.0.0). 같은 Wi-Fi의 다른 기기도 접근 가능 |
| `"127.0.0.1:8080:8080"` | 호스트 자신만 |

DB와 Redis 포트는 로컬 도구(psql, IDE)로 접속할 때만 필요합니다. 컨테이너끼리는 `ports` 없이도 내부 네트워크로 통신합니다.

### Redis 보안 설정
- `--requirepass`로 비밀번호 인증을 켭니다. 비밀번호 없이 `ping`하면 `NOAUTH Authentication required`가 반환됩니다.
- Healthcheck의 `redis-cli`는 `REDISCLI_AUTH` 환경 변수로 인증합니다. 비밀번호가 명령줄(`ps`)에 노출되지 않습니다.
- 캐시를 아직 쓰지 않으므로 디스크 저장(`--save ''`, `--appendonly no`)은 끄고 Volume도 두지 않았습니다.

## 검증 결과

임의로 생성한 비밀번호를 환경 변수로 넘겨 실행했습니다. 검증이 끝난 뒤 컨테이너와 볼륨은 삭제했습니다.

### `docker compose up -d --build`
```
 Container integration-hub-postgres Started
 Container integration-hub-redis Started
 Container integration-hub-postgres Waiting
 Container integration-hub-redis Waiting
 Container integration-hub-redis Healthy
 Container integration-hub-postgres Healthy
 Container integration-hub-core-api Starting      ← DB, Redis가 healthy가 된 뒤 시작
 Container integration-hub-core-api Started
```

### `docker compose ps`
```
NAME                       SERVICE    STATUS                    PORTS
integration-hub-core-api   core-api   Up 12 seconds (healthy)   127.0.0.1:8080->8080/tcp
integration-hub-postgres   postgres   Up 17 seconds (healthy)   127.0.0.1:5432->5432/tcp
integration-hub-redis      redis      Up 17 seconds (healthy)   127.0.0.1:6379->6379/tcp
```

### Health API
```bash
curl http://localhost:8080/actuator/health
```
```json
{"status":"UP","groups":["liveness","readiness"],
 "components":{"db":{"status":"UP"},"diskSpace":{"status":"UP"},"livenessState":{"status":"UP"},
               "ping":{"status":"UP"},"readinessState":{"status":"UP"},"redis":{"status":"UP"},"ssl":{"status":"UP"}}}
```
`show-components: always`로 구성 요소별 상태만 보여주고, 접속 정보 같은 상세 내용(`show-details`)은 숨깁니다.

### 주문 등록과 DB 영속성
```bash
curl -i -X POST http://localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"customer-1","items":[{"productName":"keyboard","unitPrice":50000,"quantity":2},{"productName":"mouse","unitPrice":20000,"quantity":1}]}'
```
```http
HTTP/1.1 201
Location: http://localhost:8080/api/orders/1
```
```bash
docker compose exec postgres psql -U integrationhub -d integrationhub \
  -c "select o.id, o.customer_id, o.status, i.product_name, i.quantity from orders o join order_items i on i.order_id = o.id"
```
```
 id | customer_id | status  | product_name | quantity
----+-------------+---------+--------------+----------
  1 | customer-1  | CREATED | keyboard     |        2
  1 | customer-1  | CREATED | mouse        |        1
```
재고도 keyboard 1000 → 998, mouse 1000 → 999로 차감되었습니다.

### 재시작 후 데이터 보존
| 동작 | 컨테이너 | 볼륨 | `GET /api/orders/1` |
|------|----------|------|---------------------|
| `docker compose restart` | 유지(프로세스만 재시작) | 유지 | 200, 데이터 동일 |
| `docker compose down` → `up -d` | **삭제 후 새로 생성** | 유지 | 200, 데이터 동일 |
| `docker compose down -v` | 삭제 | **삭제** | 데이터 초기화 (Flyway가 스키마부터 다시 생성) |

`down` 후 다시 시작했을 때 core-api 로그에 `Schema "public" is up to date. No migration necessary.`가 출력됩니다. 기존 볼륨의 스키마를 그대로 사용했다는 뜻입니다.

### 테스트
- `TestcontainersConfiguration`에 Redis 7.4 컨테이너(`@ServiceConnection(name = "redis")`)를 추가했습니다.
- `EnterpriseIntegrationHubApplicationTests`는 이제 `/actuator/health`에서 `db`와 `redis`가 모두 UP인지 검증합니다.
- `./gradlew test` 결과 60개 테스트가 모두 통과했습니다.

## Docker Image, Container, Volume, Network

| 개념 | 한 줄 정의 | 비유 | 이 프로젝트 | 수명 |
|------|-----------|------|-------------|------|
| **Image** | 실행에 필요한 파일시스템과 실행 명령을 담은 **읽기 전용 템플릿** | 클래스 | `enterprise-integration-hub:local`, `postgres:17-alpine`, `redis:7.4-alpine` | `docker rmi` 전까지 유지 |
| **Container** | Image를 실행한 **격리된 프로세스**. Image 위에 쓰기 가능한 Layer가 하나 추가됨 | 인스턴스(`new`) | `integration-hub-core-api`, `-postgres`, `-redis` | 삭제하면 쓰기 Layer와 함께 사라짐 |
| **Volume** | 컨테이너와 **독립적으로 관리되는 영속 저장소** | 외장 디스크 | `integration-hub_postgres-data` | `docker volume rm` 또는 `down -v` 전까지 유지 |
| **Network** | 컨테이너끼리 통신하는 **가상 네트워크**. 이름 기반 DNS 제공 | 사내 LAN | `integration-hub_backend` (bridge) | Compose `down` 시 삭제, `up` 시 재생성 |

### Image
- Dockerfile의 각 명령이 **Layer**가 되고, 변경되지 않은 Layer는 캐시됩니다. 그래서 `build.gradle`을 소스보다 먼저 복사하면 소스만 바뀌었을 때 의존성 다운로드를 건너뜁니다.
- Multi-stage 빌드에서 최종 이미지에는 마지막 Stage(JRE + jar)만 남습니다. JDK와 Gradle 캐시는 빌드 Stage에만 있습니다.
- 하나의 Image로 여러 Container를 실행할 수 있습니다.

### Container
- Container 안에서 생긴 파일 변경은 그 Container의 쓰기 Layer에만 저장됩니다. `docker compose down`으로 Container를 지우면 함께 사라집니다.
- 그래서 DB처럼 남아야 하는 데이터는 Container가 아니라 Volume에 둡니다.
- `restart`는 같은 Container를 재시작하고, `down` 후 `up`하면 새 Container를 만듭니다.

### Volume
- Docker가 관리하는 호스트 디렉터리(`/var/lib/docker/volumes/<이름>/_data`)를 Container 경로에 연결합니다.
- **Named Volume과 Bind Mount 비교**

  | 구분 | Named Volume | Bind Mount |
  |------|-------------|------------|
  | 예시 | `postgres-data:/var/lib/postgresql/data` | `./data:/var/lib/postgresql/data` |
  | 위치 | Docker가 관리 | 호스트의 지정 경로 |
  | 권한 문제 | 적음 | 호스트 UID와 충돌 가능 |
  | 용도 | DB 데이터 | 소스 코드, 설정 파일 공유 |

### Network
- Compose는 기본적으로 프로젝트 네트워크를 만듭니다. 이 프로젝트는 이름(`backend`)을 명시했습니다.
- 같은 네트워크의 Container는 **서비스 이름으로** 서로를 찾습니다. Container IP(예: `172.18.0.3`)는 재생성할 때마다 바뀔 수 있으므로 IP를 직접 쓰지 않습니다.
- `ports`는 호스트와 Container를 연결하는 설정입니다. Container끼리 통신할 때는 필요 없습니다.
- 같은 서비스 안에서 `localhost`는 그 Container 자신을 뜻합니다. 그래서 core-api의 `DB_URL`은 `localhost`가 아니라 `postgres`를 사용합니다.

```bash
docker image ls enterprise-integration-hub          # Image
docker compose ps -a                                # Container
docker volume ls | grep integration-hub             # Volume
docker network inspect integration-hub_backend      # Network와 연결된 Container, IP
```

## 현재 한계
- 컨테이너 시간대가 UTC입니다. Docker에서 실행하면 `createdAt`이 UTC 기준(`06:53`)으로 저장되고, 로컬 `bootRun`에서는 KST로 저장됩니다. 시간 값을 `Instant`/`OffsetDateTime`으로 바꾸거나 `TZ`를 통일하는 작업이 필요합니다.
- Redis는 연결 확인만 합니다. 캐시 기능은 구현하지 않았습니다.
- 로그는 컨테이너를 삭제하면 사라집니다. 중앙 수집은 아직 없습니다.
