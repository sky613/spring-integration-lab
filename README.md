# enterprise-integration-hub

Spring Boot 기반 Enterprise Integration Hub 프로젝트입니다.

## 빠른 시작

```bash
cp .env.example .env                 # DB·Redis·Keycloak 비밀번호 설정 (최초 1회)
./gradlew test                       # 테스트 (Docker 필요: Testcontainers)
docker compose up -d --build         # api-gateway(:8080) → core-api, PostgreSQL, Redis, mock-erp, mock-mes, keycloak(:8180)
TOKEN=$(scripts/get-token.sh user1)  # OIDC Authorization Code + PKCE 로그인 (비밀번호: .env의 KC_TEST_USER_PASSWORD)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/orders  # 모든 API는 Gateway + JWT 필요
```

## 문서

| 문서 | 내용 |
|------|------|
| [docs/architecture.md](docs/architecture.md) | 기술 스택, 디렉터리 구조, 설정, 헬스 체크 |
| [docs/getting-started.md](docs/getting-started.md) | 로컬 실행, IDE 설정 |
| [docs/docker.md](docs/docker.md) | Docker Compose 실행, 구성, 로그 확인 |
| [docs/step1-java21-basics.md](docs/step1-java21-basics.md) | STEP 1. Java 21 핵심 문법 (주문 처리 예제) |
| [docs/step2-rest-api.md](docs/step2-rest-api.md) | STEP 2. 주문 관리 REST API (curl 예시 포함) |
| [docs/step3-postgresql-jpa.md](docs/step3-postgresql-jpa.md) | STEP 3. PostgreSQL + JPA + Flyway (SQL 로그 포함) |
| [docs/step4-docker-compose.md](docs/step4-docker-compose.md) | STEP 4. Docker Compose 통합 환경 (Image/Container/Volume/Network) |
| [docs/step5-erp-mes-integration.md](docs/step5-erp-mes-integration.md) | STEP 5. ERP / MES 연계 (RestClient, Timeout, 연계 이력, 장애 시나리오) |
| [docs/step6-api-gateway.md](docs/step6-api-gateway.md) | STEP 6. Spring Cloud Gateway (Routing, Filter, 요청 ID, Rate Limiting, 장애 처리) |
| [docs/step7-security-keycloak.md](docs/step7-security-keycloak.md) | STEP 7. Keycloak + Spring Security (OIDC PKCE, JWT, RBAC, 401/403) |
| [docs/step8-resilience-redis.md](docs/step8-resilience-redis.md) | STEP 8. Redis 캐시, Retry·Backoff, Idempotency-Key, 중복 방지, 실패 재처리, 감사 로그 |
