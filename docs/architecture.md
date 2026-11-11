# 아키텍처

## 기술 스택

| 항목 | 버전 |
|------|------|
| Java | 21 (Gradle Toolchain) |
| Spring Boot | 3.5.16 |
| Gradle | 8.14.3 (Wrapper 포함) |
| DB | PostgreSQL 17, Spring Data JPA (Hibernate), Flyway |
| 의존성 | Spring Web, Validation, Actuator |
| 테스트 | JUnit 5, Testcontainers |

Redis는 아직 포함하지 않습니다.

## 디렉터리 구조

```
src/main/java/com/example/integrationhub
├── EnterpriseIntegrationHubApplication.java
├── controller/   # REST Controller (OrderController)
├── service/      # 비즈니스 로직 (OrderService)
├── repository/   # Spring Data JPA (OrderRepository, InventoryRepository)
├── domain/       # JPA Entity (Order, OrderItem, Inventory), OrderStatus
├── dto/          # API 요청/응답 (OrderRequest, OrderStatusRequest, OrderResponse)
├── exception/    # 도메인 예외, GlobalExceptionHandler
└── study/        # 학습용 예제 (Spring Bean 아님, docs/step1-java21-basics.md 참고)
src/main/resources/application.yml
src/main/resources/db/migration/   # Flyway (V1 스키마, V2 초기 재고)
src/test/java/com/example/integrationhub
├── TestcontainersConfiguration.java              # PostgreSQL 17 컨테이너 (@ServiceConnection)
├── EnterpriseIntegrationHubApplicationTests.java   # /actuator/health 통합 테스트
├── controller/   # OrderControllerTest(@WebMvcTest), OrderApiIntegrationTest
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
| `logging.level.org.hibernate.SQL` | `debug` | 실행 SQL 로그 |
| `management.endpoints.web.exposure.include` | `health,info` | 노출되는 Actuator Endpoint |
| `management.endpoint.health.probes.enabled` | `true` | Liveness/Readiness Probe 활성화 |

## 헬스 체크

| Endpoint | 설명 |
|----------|------|
| `GET /actuator/health` | 전체 상태 |
| `GET /actuator/health/liveness` | Liveness Probe |
| `GET /actuator/health/readiness` | Readiness Probe |
| `GET /actuator/info` | 애플리케이션 정보 |
