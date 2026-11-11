# 아키텍처

## 기술 스택

| 항목 | 버전 |
|------|------|
| Java | 21 (Gradle Toolchain) |
| Spring Boot | 3.5.16 |
| Gradle | 8.14.3 (Wrapper 포함) |
| 의존성 | Spring Web, Validation, Actuator |

DB와 Redis는 아직 포함하지 않습니다.

## 디렉터리 구조

```
src/main/java/com/example/integrationhub
├── EnterpriseIntegrationHubApplication.java
├── controller/   # REST Controller (OrderController)
├── service/      # 비즈니스 로직 (OrderService)
├── repository/   # 데이터 접근 계층 (OrderRepository, InMemoryOrderRepository)
├── domain/       # Entity (Order, OrderItem, OrderStatus)
├── dto/          # API 요청/응답 (OrderRequest, OrderStatusRequest, OrderResponse)
├── exception/    # 도메인 예외, GlobalExceptionHandler
└── study/        # 학습용 예제 (Spring Bean 아님, docs/step1-java21-basics.md 참고)
src/main/resources/application.yml
src/test/java/com/example/integrationhub
├── EnterpriseIntegrationHubApplicationTests.java   # /actuator/health 통합 테스트
├── controller/   # OrderControllerTest(@WebMvcTest), OrderApiIntegrationTest
└── service/      # OrderServiceTest
```

`controller` → `service` → `repository` 방향의 계층 구조를 따릅니다. 주문 API 상세는 [step2-rest-api.md](step2-rest-api.md)를 참고하세요.

## 설정 (`application.yml`)

| 항목 | 값 | 설명 |
|------|----|------|
| `spring.application.name` | `enterprise-integration-hub` | |
| `server.port` | `${SERVER_PORT:8080}` | 환경 변수 `SERVER_PORT`로 변경 가능 |
| `management.endpoints.web.exposure.include` | `health,info` | 노출되는 Actuator Endpoint |
| `management.endpoint.health.probes.enabled` | `true` | Liveness/Readiness Probe 활성화 |

## 헬스 체크

| Endpoint | 설명 |
|----------|------|
| `GET /actuator/health` | 전체 상태 |
| `GET /actuator/health/liveness` | Liveness Probe |
| `GET /actuator/health/readiness` | Readiness Probe |
| `GET /actuator/info` | 애플리케이션 정보 |
