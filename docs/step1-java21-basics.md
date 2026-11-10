# STEP 1. Java 21 핵심 문법 복습

주문 데이터 처리 예제로 Java 핵심 문법을 복습합니다. Spring 없이 순수 Java로 작성했습니다.

- 코드: `src/main/java/com/example/integrationhub/study/step1`
- 테스트: `src/test/java/com/example/integrationhub/study/step1`
- 실행: `./gradlew test --tests 'com.example.integrationhub.study.step1.*'`

## 구성

| 파일 | 학습 개념 | 역할 |
|------|----------|------|
| `OrderStatus` | Enum, Switch Expression | 주문 상태와 전이 규칙 |
| `OrderItem`, `CreateOrderRequest`, `OrderSummary`, `OrderSearchCondition` | Record | 값 객체 / DTO |
| `Order` | Class, Encapsulation | 주문 도메인 객체 |
| `Repository<T, ID>` | Interface, Generic, Optional | 저장소 계약 |
| `AbstractInMemoryRepository<T, ID>` | Abstract Class, Map | 공통 저장 로직 |
| `InMemoryOrderRepository` | Inheritance | 주문 저장소 |
| `DiscountPolicy` | Polymorphism, sealed, Record Pattern | 할인 정책 |
| `OrderException` 외 2개 | Exception Handling | 도메인 예외 |
| `OrderService` | Collection, Lambda, Stream | 생성·상태 변경·검색·집계 |
| `OrderAsyncProcessor` | CompletableFuture, Thread | 병렬 결제 처리 |
| `LegacyOrderStatistics` | 비교용 | Stream 이전 방식 구현 |

## 1. Class / Interface / Abstract Class

| 구분 | 용도 | 예제 |
|------|------|------|
| Class | 상태 + 행위 | `Order` |
| Interface | 구현 없는 계약. 다중 구현 가능 | `Repository<T, ID>` |
| Abstract Class | 공통 상태·구현을 공유하고 일부만 하위 클래스에 위임 | `AbstractInMemoryRepository` |

```java
public abstract class AbstractInMemoryRepository<T, ID> implements Repository<T, ID> {
    private final Map<ID, T> store = new ConcurrentHashMap<>();
    protected abstract ID idOf(T entity);   // 하위 클래스가 결정
    public T save(T entity) { store.put(idOf(entity), entity); return entity; }
}
```

실무에서는 Interface로 계약을 정의하고, 공통 구현이 실제로 생길 때만 Abstract Class를 추가합니다.

## 2. Encapsulation / Inheritance / Polymorphism

- **Encapsulation**: `Order.status`는 private이고 setter가 없습니다. `changeStatus()`만 상태를 바꿀 수 있어서 전이 규칙을 우회할 수 없습니다.
- **Inheritance**: `InMemoryOrderRepository extends AbstractInMemoryRepository<Order, Long>`
- **Polymorphism**: `DiscountPolicy.apply()`는 같은 호출이라도 구현체(`None`, `Rate`, `Fixed`)에 따라 다르게 동작합니다.

```java
// Java 8: instanceof 후 캐스팅, 누락된 타입이 있어도 컴파일러가 모름
if (policy instanceof Rate) {
    Rate rate = (Rate) policy;
    return rate.getPercent() + "% 할인";
}

// Java 21: sealed + Record Pattern, 모든 경우를 다루지 않으면 컴파일 오류
return switch (policy) {
    case None none -> "할인 없음";
    case Rate(int percent) -> percent + "% 할인";
    case Fixed(BigDecimal discount) -> discount.toPlainString() + "원 할인";
};
```

## 3. Generic / Collection / Map

- `Repository<T, ID>`: 타입 파라미터로 엔티티와 ID 타입을 지정하므로 캐스팅이 필요 없습니다.
- `List.copyOf()`: 불변 방어적 복사 (Java 10+). Java 8에서는 `Collections.unmodifiableList(new ArrayList<>(list))`
- `ConcurrentHashMap`: 여러 Thread가 동시에 접근할 때 사용합니다.
- `EnumMap`: Enum을 Key로 쓰면 선언 순서가 유지되고 내부가 배열이라 빠릅니다.

## 4. Lambda / Stream API

```java
// Stream 이전 (LegacyOrderStatistics)
Map<String, BigDecimal> result = new HashMap<String, BigDecimal>();
for (Order order : orders) {
    if (order.getStatus() == OrderStatus.CANCELLED) continue;
    BigDecimal sum = result.get(order.getCustomerId());
    if (sum == null) sum = BigDecimal.ZERO;
    result.put(order.getCustomerId(), sum.add(order.totalAmount()));
}

// Stream (OrderService)
orders.stream()
      .filter(order -> order.getStatus() != OrderStatus.CANCELLED)
      .collect(Collectors.groupingBy(Order::getCustomerId,
               Collectors.reducing(BigDecimal.ZERO, Order::totalAmount, BigDecimal::add)));
```

| Java 8 | Java 16+ |
|--------|----------|
| `.collect(Collectors.toList())` (수정 가능한 List) | `.toList()` (불변 List) |

`LegacyComparisonTest`에서 두 구현의 결과가 같은지 검증합니다.

## 5. Optional

```java
// Java 8 이전: null 체크
Order order = repository.findById(id);
if (order == null) throw new OrderNotFoundException(id);

// Optional
repository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
service.findById(1L).map(Order::getCustomerId).orElse("unknown");
```

- 반환 타입으로만 사용합니다. 필드, 파라미터, Collection 원소로는 쓰지 않습니다.
- `get()` 대신 `orElseThrow()`, `orElse()`, `map()`을 사용합니다.

## 6. Record (Java 16+)

```java
// Java 8: 필드, 생성자, getter, equals, hashCode, toString 직접 작성 (수십 줄)
// Record: 한 줄 + 검증
public record OrderItem(String productName, BigDecimal unitPrice, int quantity) {
    public OrderItem {                       // Compact Constructor
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
    }
}
```

- 필드는 `final`이고 accessor 이름은 `getX()`가 아니라 `x()`입니다.
- 내부 List는 `List.copyOf()`로 복사해야 진짜 불변이 됩니다 (`CreateOrderRequest`).
- DTO와 값 객체에 적합합니다. 상태가 바뀌는 도메인 객체(`Order`)에는 Class를 씁니다.

## 7. Enum

```java
// Java 8: 상수 + if-else, 상태 추가 시 누락을 컴파일러가 잡지 못함
public static final int CREATED = 0;

// Java 21: Enum + Switch Expression
public boolean canTransitionTo(OrderStatus next) {
    return switch (this) {
        case CREATED -> next == PAID || next == CANCELLED;
        case PAID -> next == SHIPPED || next == CANCELLED;
        case SHIPPED -> next == DELIVERED;
        case DELIVERED, CANCELLED -> false;
    };
}
```

상태 전이 규칙을 Enum에 두면 규칙이 서비스 곳곳에 흩어지지 않습니다.

## 8. Exception Handling

```
RuntimeException
└── OrderException (abstract sealed)
    ├── OrderNotFoundException
    └── InvalidOrderStatusException
```

- **Checked Exception** (`IOException` 등): 호출자가 반드시 처리해야 합니다. 복구 가능한 외부 오류에 사용합니다.
- **Unchecked Exception** (`RuntimeException`): 비즈니스 규칙 위반에 사용합니다. Spring의 `@ExceptionHandler`에서 한 번에 처리하는 것이 실무 표준입니다.
- 공통 부모 예외를 두면 상위 계층에서 `catch (OrderException e)` 하나로 처리할 수 있습니다.

## 9. CompletableFuture와 Thread

```java
// Java 8: Thread Pool을 직접 만들고 shutdown을 잊기 쉬움
ExecutorService pool = Executors.newFixedThreadPool(10);
try { ... } finally { pool.shutdown(); }

// Java 21: Virtual Thread + try-with-resources (ExecutorService가 AutoCloseable)
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    processor.payAll(ids).join();
}
```

| 구분 | Platform Thread | Virtual Thread (Java 21) |
|------|-----------------|-------------------------|
| 매핑 | OS Thread 1:1 | JVM이 소수의 Carrier Thread 위에서 스케줄링 |
| 생성 비용 | 큼 (Pool 필요) | 작음 (작업마다 생성) |
| 적합한 작업 | CPU 연산 | I/O 대기 (DB, HTTP 호출) |

`OrderAsyncProcessor.payAll()` 흐름:
1. 주문마다 `supplyAsync(..., executor)`로 비동기 작업을 시작합니다.
2. `allOf()`로 모든 작업이 끝날 때까지 기다립니다.
3. `join()`으로 결과를 모읍니다. 하나라도 실패하면 `CompletionException`이 발생합니다.

공유 상태 주의: `Order.changeStatus()`는 "검사 후 변경"이므로 `synchronized`로 원자성을 보장합니다. `concurrentStatusChangeAllowsOnlyOneWinner` 테스트는 50개 Thread가 동시에 결제해도 1건만 성공하는지 검증합니다.

## 확인 문제

1. `Order`는 Class로, `OrderItem`은 Record로 만들었습니다. 그렇게 나눈 기준은 무엇일까요?
2. `CreateOrderRequest`의 Compact Constructor에서 `items = List.copyOf(items)`를 빼면 어떤 문제가 생길까요?
3. `DiscountPolicy`에서 `sealed`를 빼면 `describe()`의 switch에 어떤 변화가 필요할까요?
4. `Order.changeStatus()`에서 `synchronized`를 빼면 `concurrentStatusChangeAllowsOnlyOneWinner` 테스트가 실패할 수 있는 이유는 무엇일까요?
5. `.collect(Collectors.toList())`와 `.toList()`의 차이는 무엇이고, `Optional`을 메서드 파라미터나 필드로 쓰지 말아야 하는 이유는 무엇일까요?
