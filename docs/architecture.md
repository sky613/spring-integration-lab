# 아키텍처

## 기술 스택

| 항목 | 버전 |
|------|------|
| Java | 21 (Gradle Toolchain) |
| Spring Boot | 3.5.16 |
| Gradle | 8.14.3 (Wrapper 포함) |
| DB | PostgreSQL 17, Spring Data JPA (Hibernate), Flyway |
| Cache | Redis 7.4 (재고 조회 캐시, 연계 중복 실행 방지 락, Gateway Rate Limit) |
| 외부 연계 | Spring `RestClient` → ERP, MES (Mock: WireMock 3.13) |
| API Gateway | Spring Cloud 2025.0.3 (Northfields, Boot 3.5.x 호환), Gateway Server WebFlux 4.3.5 |
| 인증·인가 | Keycloak 26.4.5 (OIDC, 개발용), Spring Security OAuth2 Resource Server (JWT, RBAC) |
| 실행 환경 | Docker Compose (api-gateway, core-api, postgres, redis, mock-erp, mock-mes, keycloak) |
| 의존성 | Spring Web, Validation, Actuator |
| 테스트 | JUnit 5, Testcontainers |


## 구성

```
Client ──▶ api-gateway (:8080, 유일한 공개 포트) ──▶ core-api ──▶ postgres, redis
              │ Routing · 요청 ID · 로그 · Rate Limit       └──▶ mock-erp, mock-mes (integration 네트워크)
              └──▶ redis (Rate Limit 버킷)
```

Gradle 멀티 프로젝트입니다. 루트 프로젝트(`:`)가 core-api이고, `:api-gateway`는 별도 Spring Boot 애플리케이션입니다.

## 디렉터리 구조

```
api-gateway/                       # Spring Cloud Gateway (STEP 6, docs/step6-api-gateway.md)
├── Dockerfile, build.gradle
└── src/main/java/com/example/gateway
    ├── filter/   # RequestIdFilter, AccessLogFilter (WebFilter), UpstreamUnavailableFilter (GlobalFilter)
    └── config/   # RateLimitConfig (KeyResolver), ErrorResponseConfig
src/main/java/com/example/integrationhub
├── EnterpriseIntegrationHubApplication.java
├── config/       # CacheConfig (Redis 캐시 TTL·직렬화, 캐시 장애 시 Fail-open)
├── controller/   # REST Controller (OrderController, IntegrationController, InventoryController)
├── service/      # OrderService, IdempotentOrderService, InventoryService(+캐시 무효화), IntegrationService, IntegrationLock
├── client/       # 외부 시스템 RestClient (ErpClient, MesClient), 연계 DTO, Timeout 설정, RetryExecutor
├── repository/   # Spring Data JPA (OrderRepository, InventoryRepository, IntegrationHistoryRepository)
├── domain/       # JPA Entity (Order, OrderItem, Inventory, IntegrationHistory), Enum
├── dto/          # API 요청/응답 (OrderRequest, OrderStatusRequest, OrderResponse, IntegrationHistoryResponse)
├── exception/    # 도메인 예외, GlobalExceptionHandler
├── filter/       # RequestIdFilter (X-Request-Id → MDC, 로그·ERP/MES 호출에 전파)
├── security/     # SecurityConfig (JWT 검증, URL별 역할 권한), SecurityProblemHandler (401/403)
└── study/        # 학습용 예제 (Spring Bean 아님, docs/step1-java21-basics.md 참고)
src/main/resources/application.yml
src/main/resources/db/migration/   # Flyway (V1 스키마, V2 초기 재고, V3 연계 이력, V4 Idempotency-Key·감사 컬럼)
mock/erp/mappings, mock/mes/mappings # WireMock 응답·장애 시나리오 정의 (Compose와 테스트가 함께 사용)
src/test/java/com/example/integrationhub
├── TestcontainersConfiguration.java              # PostgreSQL 17, Redis 7.4, WireMock(ERP, MES) 컨테이너
├── EnterpriseIntegrationHubApplicationTests.java   # /actuator/health (db, redis UP) 통합 테스트
├── controller/   # OrderControllerTest(@WebMvcTest), OrderApiIntegrationTest, IntegrationApiIntegrationTest
└── service/      # OrderServiceIntegrationTest (트랜잭션, 재고, N+1)
```

`controller` → `service` → `repository` 방향의 계층 구조를 따릅니다. 주문 API 상세는 [step2-rest-api.md](step2-rest-api.md)를 참고하세요.

## 설정 (`application.yml`)

| 항목 | 값 | 설명 |
|------|----|------|
| `spring.application.name` | `enterprise-integration-hub` | |
| `server.port` | `${SERVER_PORT:8080}` | 환경 변수 `SERVER_PORT`로 변경 가능 |
| `spring.datasource.*` | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | 기본 URL `jdbc:postgresql://localhost:5432/integrationhub`. 비밀번호는 기본값 없음 |
| `spring.jpa.hibernate.ddl-auto` | `validate` | 스키마는 Flyway가 관리 |
| `spring.jpa.open-in-view` | `false` | DTO 변환은 Service 트랜잭션 안에서 |
| `hibernate.default_batch_fetch_size` | `100` | N+1 완화 |
| `spring.data.web.pageable.max-page-size` | `100` | 페이지 크기 상한 |
| `spring.data.redis.*` | `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | 기본 `localhost:6379`. Connect·Command Timeout 500ms |
| `cache.inventory.ttl` | `INVENTORY_CACHE_TTL` | 재고 캐시 TTL, 기본 60s |
| `integration.retry.*` | `INTEGRATION_RETRY_MAX_ATTEMPTS` 등 | 최대 3회, 200ms × 2배 Backoff, 전체 4s 상한 |
| `integration.lock-ttl` | | 연계 중복 실행 방지 락 30s |
| `integration.erp.*` | `ERP_BASE_URL`, `ERP_CONNECT_TIMEOUT`, `ERP_READ_TIMEOUT` | 기본 `http://localhost:8081`, 2s, 3s |
| `integration.mes.*` | `MES_BASE_URL`, `MES_CONNECT_TIMEOUT`, `MES_READ_TIMEOUT` | 기본 `http://localhost:8082`, 2s, 3s |
| `management.endpoint.health.show-components` | `always` | health에 db, redis 상태 표시 (상세 정보는 숨김) |
| `spring.security.oauth2.resourceserver.jwt.*` | `OAUTH2_ISSUER_URI`, `OAUTH2_JWK_SET_URI`, audience `integration-hub-api` | 기본값 없음 (환경마다 필수) |
| `logging.pattern.correlation` | `[%X{requestId:-}] ` | 모든 로그 줄에 요청 ID 표시 |
| `logging.level.org.hibernate.SQL` | `SQL_LOG_LEVEL` | 실행 SQL 로그, 기본 `debug`(학습용). 부하·운영 환경은 `info` (STEP 9) |
| `management.endpoints.web.exposure.include` | `health,info` | 노출되는 Actuator Endpoint |
| `management.endpoint.health.probes.enabled` | `true` | Liveness/Readiness Probe 활성화 |

## 헬스 체크

| Endpoint | 설명 |
|----------|------|
| `GET /actuator/health` | 전체 상태 |
| `GET /actuator/health/liveness` | Liveness Probe |
| `GET /actuator/health/readiness` | Readiness Probe |
| `GET /actuator/info` | 애플리케이션 정보 |
