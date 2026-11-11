# STEP 2. Spring Boot REST API 개발

STEP 1의 주문 예제를 Spring Boot REST API로 옮깁니다. 저장소는 임시 In-Memory 방식입니다.

## 계층 구조

```
HTTP 요청
  → OrderController   (@RestController)  요청 파싱, @Valid 검증, Status Code 결정
  → OrderService      (@Service)         비즈니스 흐름, DTO ↔ Entity 변환
  → OrderRepository   (Interface)        저장소 계약
      └ InMemoryOrderRepository (@Repository)  ConcurrentHashMap 저장
예외 발생 시
  → GlobalExceptionHandler (@RestControllerAdvice)  ProblemDetail 응답
```

| 패키지 | 파일 | 역할 |
|--------|------|------|
| `controller` | `OrderController` | REST Endpoint |
| `service` | `OrderService` | 비즈니스 로직 |
| `repository` | `OrderRepository`, `InMemoryOrderRepository` | 저장소 |
| `domain` | `Order`, `OrderItem`, `OrderStatus` | Entity, 상태 전이 규칙 |
| `dto` | `OrderRequest`, `OrderStatusRequest`, `OrderResponse` | API 요청/응답 |
| `exception` | `GlobalExceptionHandler`, `OrderNotFoundException`, `InvalidOrderStatusException` | 예외 처리 |

## API 목록

| Method | URI | 설명 | 성공 | 실패 |
|--------|-----|------|------|------|
| POST | `/api/orders` | 주문 생성 | 201 + `Location` | 400 검증 실패 |
| GET | `/api/orders` | 주문 목록 | 200 | |
| GET | `/api/orders/{id}` | 주문 단건 조회 | 200 | 404, 400(id 형식 오류) |
| PATCH | `/api/orders/{id}/status` | 주문 상태 변경 | 200 | 400, 404, 409 |
| DELETE | `/api/orders/{id}` | 주문 삭제 | 204 | 404 |

## 핵심 개념

### REST API 설계 원칙
- URI는 리소스(명사, 복수형)를 나타내고 행위는 HTTP Method로 표현합니다. 예: `/api/orders`, `/createOrder`는 사용하지 않습니다.
- 생성 시 `201 Created`와 `Location` 헤더로 새 리소스 주소를 알려줍니다.
- 상태만 바꾸는 부분 수정은 `PATCH`, 응답 본문이 없는 삭제는 `204 No Content`를 사용합니다.
- 오류 Status Code
  - `400`: 요청 형식 오류
  - `404`: 리소스 없음
  - `409`: 현재 상태와 충돌. 예: 결제된 주문을 다시 `CREATED`로 변경

### @RestController / @Service / @Repository
모두 `@Component`의 특수화입니다. Component Scan으로 Bean이 등록되며, 역할을 드러내는 이름을 씁니다.
- `@RestController`는 `@Controller` + `@ResponseBody`입니다. 반환값을 JSON으로 직렬화합니다.
- `@Repository`는 데이터 접근 예외를 Spring의 `DataAccessException`으로 변환하는 대상이 됩니다.

### @RequestMapping
Class에 `@RequestMapping("/api/orders")`로 공통 경로를 선언합니다. Method에는 `@GetMapping`, `@PostMapping` 등 HTTP Method별 축약형을 사용합니다.

### Dependency Injection: Constructor Injection
```java
@Service
public class OrderService {
    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {   // 생성자가 하나면 @Autowired 생략 가능
        this.orderRepository = orderRepository;
    }
}
```
| 방식 | 문제점 / 장점 |
|------|---------------|
| Field Injection (`@Autowired private ...`) | final 불가, Spring 없이 테스트 불가, 의존성이 숨겨짐 |
| Setter Injection | 객체가 불완전한 상태로 존재할 수 있음 |
| **Constructor Injection** | final로 불변 보장, 필수 의존성 누락 시 생성 불가, `new`로 테스트 가능 (`OrderServiceTest`) |

`OrderService`는 구현체가 아니라 `OrderRepository` Interface에 의존합니다. 그래서 다음 단계에서 JPA 구현으로 바꿔도 Service 코드는 그대로입니다.

### DTO와 Entity 분리
| 구분 | 클래스 | 특징 |
|------|--------|------|
| Entity | `Order` | 식별자와 상태를 가지며 상태 전이 규칙을 지킴, 변경 가능 |
| Request DTO | `OrderRequest`, `OrderStatusRequest` | 입력 형식과 검증 규칙, Record |
| Response DTO | `OrderResponse` | 외부에 노출할 필드만, Record |

Entity를 그대로 응답하면 내부 구조가 API 스펙이 됩니다. 그러면 필드 하나를 바꿔도 클라이언트가 깨지고, 노출하면 안 되는 필드가 새어 나갈 수 있습니다. 변환은 `OrderRequest.toEntity()`와 `OrderResponse.from(Order)`에서 합니다.

### Bean Validation과 @Valid
```java
public record OrderRequest(
        @NotBlank String customerId,
        @NotEmpty @Valid List<Item> items) {          // @Valid: 중첩 객체까지 검증
    public record Item(@NotBlank String productName,
                       @NotNull @PositiveOrZero BigDecimal unitPrice,
                       @Positive int quantity) { }
}

public ResponseEntity<OrderResponse> create(@Valid @RequestBody OrderRequest request)
```
검증에 실패하면 Controller 메서드가 실행되지 않고 `MethodArgumentNotValidException`이 발생합니다.

역할 구분:
- **형식 검증** (필수값, 범위): Bean Validation이 담당합니다.
- **비즈니스 규칙** (상태 전이): Entity가 담당합니다.

### @ExceptionHandler와 Global Exception Handling
`@RestControllerAdvice`에 `@ExceptionHandler`를 모아 모든 Controller의 예외를 한 곳에서 처리합니다.
- `ResponseEntityExceptionHandler`를 상속하면 JSON 파싱 오류, 타입 불일치 같은 Spring MVC 표준 예외도 같은 형식으로 응답합니다.
- 응답 형식은 Spring 6 표준 `ProblemDetail`(RFC 9457, `application/problem+json`)입니다.

| 예외 | Status |
|------|--------|
| `MethodArgumentNotValidException` | 400 + `errors` 필드 목록 |
| `HttpMessageNotReadableException` (잘못된 Enum 값 등) | 400 |
| `MethodArgumentTypeMismatchException` (`/api/orders/abc`) | 400 |
| `OrderNotFoundException` | 404 |
| `InvalidOrderStatusException` | 409 |

### Spring Bean Lifecycle 기초
```
Bean 생성(생성자) → 의존성 주입 → @PostConstruct → 사용 → Context 종료 → @PreDestroy
```
`InMemoryOrderRepository`에 `@PostConstruct`, `@PreDestroy` 로그가 있습니다. 애플리케이션을 시작하고 종료하면 다음 로그가 출력됩니다.
```
InMemoryOrderRepository initialized
InMemoryOrderRepository destroyed: clearing 0 orders
```
- Bean의 기본 Scope는 **Singleton**입니다. 하나의 인스턴스를 모든 요청 Thread가 공유합니다.
- 그래서 Bean에 상태를 둘 때는 `ConcurrentHashMap`, `AtomicLong`처럼 Thread-safe한 자료구조를 사용합니다.

## curl 요청/응답 예시

로컬 서버(`./gradlew bootRun`)에서 실제로 실행한 결과입니다. 응답 헤더 일부와 JSON 줄바꿈은 정리했습니다.

### 1. 주문 생성: `POST /api/orders`
```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"customer-1","items":[{"productName":"keyboard","unitPrice":50000,"quantity":2},{"productName":"mouse","unitPrice":20000,"quantity":1}]}'
```
```http
HTTP/1.1 201
Location: http://localhost:8080/api/orders/1
Content-Type: application/json

{
  "id": 1,
  "customerId": "customer-1",
  "status": "CREATED",
  "items": [
    {"productName": "keyboard", "unitPrice": 50000, "quantity": 2, "amount": 100000},
    {"productName": "mouse", "unitPrice": 20000, "quantity": 1, "amount": 20000}
  ],
  "totalAmount": 120000,
  "createdAt": "2026-11-11T14:08:55.418953"
}
```

검증 실패:
```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"","items":[{"productName":"keyboard","unitPrice":-1,"quantity":0}]}'
```
```http
HTTP/1.1 400
Content-Type: application/problem+json

{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Invalid request content.",
  "instance": "/api/orders",
  "errors": [
    {"field": "items[0].unitPrice", "message": "0 이상이어야 합니다"},
    {"field": "customerId", "message": "공백일 수 없습니다"},
    {"field": "items[0].quantity", "message": "0보다 커야 합니다"}
  ]
}
```
> `message`는 JVM/요청 Locale에 따라 언어가 달라집니다. `errors`의 순서는 보장되지 않습니다.

### 2. 주문 목록: `GET /api/orders`
```bash
curl -i http://localhost:8080/api/orders
```
```http
HTTP/1.1 200
Content-Type: application/json

[
  {"id": 1, "customerId": "customer-1", "status": "CREATED", "items": [...], "totalAmount": 120000, "createdAt": "2026-11-11T14:08:55.418953"}
]
```

### 3. 주문 단건 조회: `GET /api/orders/{id}`
```bash
curl -i http://localhost:8080/api/orders/1
```
```http
HTTP/1.1 200
Content-Type: application/json

{"id": 1, "customerId": "customer-1", "status": "CREATED", "items": [...], "totalAmount": 120000, "createdAt": "2026-11-11T14:08:55.418953"}
```

없는 주문:
```bash
curl -i http://localhost:8080/api/orders/99
```
```http
HTTP/1.1 404
Content-Type: application/problem+json

{"type": "about:blank", "title": "Not Found", "status": 404, "detail": "Order not found: 99", "instance": "/api/orders/99"}
```

### 4. 주문 상태 변경: `PATCH /api/orders/{id}/status`
```bash
curl -i -X PATCH http://localhost:8080/api/orders/1/status \
  -H 'Content-Type: application/json' \
  -d '{"status":"PAID"}'
```
```http
HTTP/1.1 200
Content-Type: application/json

{"id": 1, "customerId": "customer-1", "status": "PAID", "items": [...], "totalAmount": 120000, "createdAt": "2026-11-11T14:08:55.418953"}
```

허용되지 않는 상태 전이 (`PAID → CREATED`):
```bash
curl -i -X PATCH http://localhost:8080/api/orders/1/status \
  -H 'Content-Type: application/json' \
  -d '{"status":"CREATED"}'
```
```http
HTTP/1.1 409
Content-Type: application/problem+json

{"type": "about:blank", "title": "Conflict", "status": 409, "detail": "Order 1 cannot change status from PAID to CREATED", "instance": "/api/orders/1/status"}
```

존재하지 않는 상태 값:
```bash
curl -i -X PATCH http://localhost:8080/api/orders/1/status \
  -H 'Content-Type: application/json' \
  -d '{"status":"UNKNOWN"}'
```
```http
HTTP/1.1 400
Content-Type: application/problem+json

{"type": "about:blank", "title": "Bad Request", "status": 400, "detail": "Failed to read request", "instance": "/api/orders/1/status"}
```

### 5. 주문 삭제: `DELETE /api/orders/{id}`
```bash
curl -i -X DELETE http://localhost:8080/api/orders/1
```
```http
HTTP/1.1 204
```

이미 삭제된 주문:
```http
HTTP/1.1 404
Content-Type: application/problem+json

{"type": "about:blank", "title": "Not Found", "status": 404, "detail": "Order not found: 1", "instance": "/api/orders/1"}
```

## 테스트

| 테스트 | 범위 | 방식 |
|--------|------|------|
| `OrderControllerTest` (12개) | Controller, Validation, ExceptionHandler | `@WebMvcTest` + `@MockitoBean OrderService` |
| `OrderServiceTest` (4개) | Service + In-Memory Repository | Spring 없이 생성자로 의존성 주입 |
| `OrderApiIntegrationTest` (1개) | 생성 → 목록 → 상태 변경 → 조회 → 삭제 → 404 | `@SpringBootTest` + `MockMvc` |

```bash
./gradlew test --tests 'com.example.integrationhub.controller.*' --tests 'com.example.integrationhub.service.*'
```

## 현재 한계
- 애플리케이션을 재시작하면 데이터가 사라집니다 (In-Memory).
- 목록 조회에 Paging과 검색 조건이 없습니다.
- 인증과 인가가 없습니다.
