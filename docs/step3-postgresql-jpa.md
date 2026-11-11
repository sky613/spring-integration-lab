# STEP 3. PostgreSQL 데이터베이스 연동

STEP 2의 In-Memory 저장소를 PostgreSQL 17 + Spring Data JPA로 교체했습니다. REST API 계약(URI, 요청/응답 JSON)은 그대로 유지합니다.

## 변경 요약

| 항목 | STEP 2 | STEP 3 |
|------|--------|--------|
| 저장소 | `InMemoryOrderRepository` (ConcurrentHashMap) | `OrderRepository extends JpaRepository` |
| 스키마 | 없음 | Flyway `V1__create_order_tables.sql` |
| 재고 | 없음 | `inventory` 테이블. 주문 생성 시 차감, 취소·삭제 시 복구 |
| 목록 조회 | 전체 반환 | `status`, `page`, `size`, `sort` 파라미터 + `X-Total-Count` 헤더 |
| 동시성 | `synchronized` | 주문: `@Version` 낙관적 Lock, 재고: `SELECT ... FOR UPDATE` 비관적 Lock |
| 테스트 DB | 없음 | Testcontainers `postgres:17-alpine` |

추가된 오류 응답

| 상황 | Status |
|------|--------|
| 재고 부족 | 409 `Insufficient stock for monitor: requested 9999, available 499` |
| `inventory`에 없는 상품 | 400 `Unknown product: tablet` |
| 같은 주문을 동시에 수정 (`@Version` 충돌) | 409 |
| 잘못된 정렬 필드 (`sort=unknown`) | 400 |
| 잘못된 상태 값 (`status=UNKNOWN`) | 400 |

## 테이블과 Entity 관계

```
orders (1) ──< order_items (N)          inventory
  id PK          id PK                    id PK
  customer_id    order_id FK → orders.id  product_name UNIQUE
  status         product_name             quantity (CHECK >= 0)
  created_at     unit_price
  version        quantity
```

```java
// Order: 1:N 양방향, 연관관계의 주인은 FK를 가진 OrderItem
@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
private List<OrderItem> items = new ArrayList<>();

// OrderItem: N:1, FK(order_id) 관리
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "order_id", nullable = false)
private Order order;
```

- **연관관계의 주인:** FK가 있는 쪽(`OrderItem.order`)만 FK 값을 변경합니다. `mappedBy` 쪽은 읽기 전용입니다.
- **연관관계 편의 메서드:** `Order.addItem()`이 `items.add()`와 `item.assignOrder(this)`를 함께 호출해 양쪽 참조를 맞춥니다.
- **cascade / orphanRemoval:** 주문을 저장·삭제하면 주문 상품도 함께 처리됩니다. 주문이 Aggregate Root 역할을 합니다.
- **`inventory`와의 관계:** `order_items`와 FK 없이 `product_name`으로만 연결합니다. 상품 관리 기능이 생기면 `products` 테이블로 분리할 수 있습니다.
- **`unit_price numeric`:** scale을 고정하지 않았습니다. `numeric(15,2)`로 고정하면 조회 결과가 `50000.00`이 되어 응답 JSON이 달라지기 때문입니다.

## Flyway 마이그레이션

```
src/main/resources/db/migration
├── V1__create_order_tables.sql   # 테이블, Index, 제약조건
└── V2__seed_inventory.sql        # 학습용 초기 재고 (keyboard 1000, mouse 1000, monitor 500)
```

- **실행 시점:** 애플리케이션이 시작될 때 `flyway_schema_history`에 기록되지 않은 버전만 순서대로 실행합니다.
- **역할 분담:** `ddl-auto: validate`로 설정했습니다. Hibernate는 스키마를 만들지 않고, Entity와 테이블이 일치하는지만 검증합니다.
- **변경 규칙:** 이미 적용된 파일은 수정하지 않습니다. 스키마를 바꿀 때는 `V3__...sql`처럼 새 파일을 추가합니다.

```
Migrating schema "public" to version "1 - create order tables"
Migrating schema "public" to version "2 - seed inventory"
Successfully applied 2 migrations to schema "public", now at version v2
```

## 학습 내용

### JPA와 Hibernate 차이
| 구분 | 설명 |
|------|------|
| JPA (Jakarta Persistence) | ORM 표준 **명세**(Interface와 Annotation): `@Entity`, `EntityManager`, JPQL |
| Hibernate | JPA의 **구현체**. Dialect, Batch Fetch, Statistics 등 확장 기능 제공 |
| Spring Data JPA | JPA 위의 추상화. `JpaRepository` Interface만 선언하면 구현체를 Proxy로 생성 |

코드는 `jakarta.persistence.*`(JPA 표준)를 사용합니다. Hibernate 고유 기능은 설정(`default_batch_fetch_size`)과 테스트(`Statistics`)에서만 씁니다.

### 영속성 컨텍스트 (Persistence Context)
트랜잭션 범위 안에서 Entity를 관리하는 1차 캐시입니다.
- **동일성 보장:** 같은 트랜잭션에서 같은 id를 두 번 조회하면 같은 인스턴스를 반환하고, SQL은 1번만 실행합니다 (`persistenceContextReturnsSameInstanceWithinTransaction` 테스트).
- **Dirty Checking:** `changeStatus()`는 `save()`를 호출하지 않습니다. 커밋할 때 변경을 감지해 UPDATE를 실행합니다.
- **자동 Flush:** JPQL 쿼리를 실행하기 전에 그때까지의 변경을 DB에 반영합니다. 아래 SQL 로그에서 첫 번째 재고 UPDATE가 두 번째 재고 SELECT보다 먼저 실행되는 이유입니다.
- **준영속 (Detached):** 트랜잭션이 끝난 뒤 Entity의 지연 로딩 필드에 접근하면 `LazyInitializationException`이 발생합니다 (`lazyLoadingOutsideTransactionFails` 테스트).
  - `open-in-view: false` 설정 때문에 Controller에서는 지연 로딩이 동작하지 않습니다.
  - 그래서 Service 트랜잭션 안에서 DTO 변환을 끝냅니다.

### Lazy / Eager Loading
| 관계 | 기본값 | 이 프로젝트 |
|------|--------|-------------|
| `@OneToMany`, `@ManyToMany` | LAZY | LAZY |
| `@ManyToOne`, `@OneToOne` | **EAGER** | LAZY로 명시 |

EAGER는 필요 없을 때도 JOIN이나 추가 SELECT를 실행하고, JPQL에서는 N+1의 원인이 됩니다. 실무에서는 모두 LAZY로 두고, 필요한 곳에서만 Fetch Join, `@EntityGraph`, Batch Fetch로 함께 조회합니다.

### Transaction
```java
@Service
@Transactional(readOnly = true)          // 기본: 읽기 전용 (Flush 생략, Dirty Checking 생략)
public class OrderService {
    @Transactional                        // 쓰기 메서드만 덮어쓴다
    public OrderResponse create(OrderRequest request) { ... }
}
```
- **Rollback 기준:** `RuntimeException`이 발생하면 Rollback합니다. Checked Exception은 기본적으로 Commit하므로 필요하면 `rollbackFor`를 지정합니다.
- **Proxy 기반 동작:** `@Transactional`은 Proxy가 처리합니다. 같은 클래스 안에서 `this.method()`로 호출하면 트랜잭션이 적용되지 않습니다.
- **Rollback 검증:** `rollbackWhenStockIsInsufficient` 테스트에서 확인합니다.
  1. keyboard 재고 차감에 성공합니다.
  2. mouse 재고가 부족해 예외가 발생합니다.
  3. keyboard 재고가 원래 값 10으로 돌아오고, 주문도 저장되지 않습니다.
- **Lock:**
  - 재고는 `@Lock(PESSIMISTIC_WRITE)`로 행을 잠급니다. PostgreSQL에서는 `for no key update`로 실행됩니다.
  - 여러 상품을 잠글 때는 상품명 순서로 잠가 Deadlock을 방지합니다.
  - 주문은 `@Version`을 사용합니다. 그래서 UPDATE에 `where id=? and version=?` 조건이 붙습니다.

### N+1 문제
주문 N건을 조회한 뒤 각 주문의 `items`에 접근하면 SELECT가 N번 더 실행되는 문제입니다.

| 해결 방법 | 특징 |
|-----------|------|
| Fetch Join (`join fetch o.items`) | 1번에 조회. 단, 컬렉션 Fetch Join과 페이징을 함께 쓰면 메모리에서 페이징하는 경고(HHH90003004) 발생 |
| `@EntityGraph` | Annotation으로 Fetch Join. 위와 같은 제약 |
| **`default_batch_fetch_size`** (적용) | 지연 로딩 시 여러 주문의 items를 IN 절 하나로 조회. 페이징과 함께 사용 가능 |

`batchFetchAvoidsNPlusOne` 테스트는 주문 5건 목록을 조회할 때 SQL이 **2번**(orders 1번 + order_items 1번)만 실행되는지 검증합니다. Batch Fetch가 없으면 6번 실행됩니다.

### SQL 실행 로그
`application.yml`
```yaml
spring.jpa.properties.hibernate.format_sql: true
logging.level.org.hibernate.SQL: debug
# 바인딩 파라미터 값까지 보려면: logging.level.org.hibernate.orm.jdbc.bind: trace
```
`System.out`으로 출력하는 `show-sql` 대신 Logger를 사용합니다. Logger를 쓰면 로그 레벨과 출력 위치를 제어할 수 있습니다.

### DB Index 기본 개념
Index는 정렬된 B-Tree 자료구조입니다. 테이블 전체를 읽지(Seq Scan) 않고 조건에 맞는 행만 찾습니다(Index Scan).
- 조회는 빨라지지만 INSERT/UPDATE마다 Index도 갱신해야 하므로 쓰기 비용과 저장 공간이 늘어납니다.
- PK와 UNIQUE 제약조건은 Index를 자동으로 만듭니다. **FK는 PostgreSQL이 자동으로 만들지 않습니다.**

| Index | 용도 |
|-------|------|
| `idx_orders_status` | 상태별 검색 (`GET /api/orders?status=PAID`) |
| `idx_orders_customer_id` | 고객별 조회 (향후) |
| `idx_order_items_order_id` | 주문별 상품 조회 (Batch Fetch IN 절, JOIN) |
| `inventory_product_name_key` | UNIQUE 제약조건이 자동 생성. 재고 조회와 Lock에 사용 |

```
explain select * from orders where status = 'PAID';
Index Scan using idx_orders_status on orders
  Index Cond: ((status)::text = 'PAID'::text)
```
데이터가 적거나 조건에 맞는 행의 비율이 높으면, PostgreSQL은 Index가 있어도 Seq Scan이 더 싸다고 판단할 수 있습니다.

## 실제 SQL 실행 결과

로컬 PostgreSQL 17에서 API를 호출하며 수집한 로그입니다.

### 주문 생성 (`POST /api/orders`, keyboard 2 + mouse 1)
```sql
select i1_0.id, i1_0.product_name, i1_0.quantity from inventory i1_0
 where i1_0.product_name=? for no key update          -- keyboard 행 Lock
update inventory set product_name=?, quantity=? where id=?   -- 다음 쿼리 전 자동 Flush
select ... from inventory i1_0 where i1_0.product_name=? for no key update   -- mouse 행 Lock
insert into orders (created_at, customer_id, status, version) values (?, ?, ?, ?)
insert into order_items (order_id, product_name, quantity, unit_price) values (?, ?, ?, ?)
insert into order_items (order_id, product_name, quantity, unit_price) values (?, ?, ?, ?)
update inventory set product_name=?, quantity=? where id=?   -- 커밋 시 Flush
```

### 목록 조회 (`GET /api/orders?page=0&size=1&sort=id,desc`)
```sql
select o1_0.id, ... from orders o1_0 order by o1_0.id desc offset ? rows fetch first ? rows only
select count(o1_0.id) from orders o1_0                        -- 전체 건수 (X-Total-Count)
select i1_0.order_id, i1_0.id, ... from order_items i1_0 where i1_0.order_id=? order by i1_0.id   -- Batch Fetch
```
첫 페이지의 결과가 size보다 적으면 Spring Data는 count 쿼리를 생략합니다.

### 주문 취소 (`PATCH /api/orders/1/status`, `CANCELLED`)
```sql
select ... from orders o1_0 where o1_0.id=?
select ... from order_items i1_0 where i1_0.order_id=? order by i1_0.id      -- 지연 로딩
select ... from inventory i1_0 where i1_0.product_name=? for no key update
update orders set customer_id=?, status=?, version=? where id=? and version=?   -- 낙관적 Lock
update inventory set product_name=?, quantity=? where id=?
select ... from inventory i1_0 where i1_0.product_name=? for no key update
update inventory set product_name=?, quantity=? where id=?
```

### 실행 후 DB 상태
```
 id | customer_id |  status   | version          id | order_id | product_name | unit_price | quantity
----+-------------+-----------+---------        ----+----------+--------------+------------+----------
  1 | customer-1  | CANCELLED |       1           1 |        1 | keyboard     |      50000 |        2
                                                  2 |        1 | mouse        |      20000 |        1
 product_name | quantity
--------------+----------
 keyboard     |     1000     ← 주문 1 취소로 복구, 재고 부족으로 실패한 주문의 차감분은 Rollback
 mouse        |     1000     ← 주문 1 취소로 복구
 monitor      |      500     ← 주문 2 삭제로 복구
```

## curl 예시 (추가된 기능)

```bash
# 상태별 검색 + 페이징 + 정렬
curl -i 'http://localhost:8080/api/orders?status=CANCELLED&page=0&size=10&sort=createdAt,desc'
```
```http
HTTP/1.1 200
X-Total-Count: 1
Content-Type: application/json

[{"id": 1, "customerId": "customer-1", "status": "CANCELLED", "items": [...], "totalAmount": 120000, "createdAt": "2026-11-11T15:20:37.155402"}]
```

```bash
# 재고 부족 → 409, 이미 차감한 keyboard 재고도 Rollback
curl -i -X POST http://localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"customer-3","items":[{"productName":"keyboard","unitPrice":50000,"quantity":1},{"productName":"monitor","unitPrice":300000,"quantity":9999}]}'
```
```http
HTTP/1.1 409
Content-Type: application/problem+json

{"type": "about:blank", "title": "Conflict", "status": 409, "detail": "Insufficient stock for monitor: requested 9999, available 499", "instance": "/api/orders"}
```

```bash
# 없는 상품 → 400
curl -i -X POST http://localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"customer-3","items":[{"productName":"tablet","unitPrice":1,"quantity":1}]}'
```
```http
HTTP/1.1 400
{"type": "about:blank", "title": "Bad Request", "status": 400, "detail": "Unknown product: tablet", "instance": "/api/orders"}
```

```bash
# 잘못된 정렬 필드 → 400
curl -i 'http://localhost:8080/api/orders?sort=unknown'
```
```http
HTTP/1.1 400
{"type": "about:blank", "title": "Bad Request", "status": 400, "detail": "Invalid sort property: unknown", "instance": "/api/orders"}
```

나머지 API의 요청과 응답은 [step2-rest-api.md](step2-rest-api.md)와 같습니다.

## 테스트

| 테스트 | 내용 |
|--------|------|
| `OrderServiceIntegrationTest` (9개, Testcontainers) | DB 저장 확인(JdbcTemplate), 재고 부족·없는 상품 Rollback, 취소 시 재고 복구, 삭제 시 재고 복구, 페이징·정렬·상태 검색, N+1(SQL 횟수), 1차 캐시, LazyInitializationException |
| `OrderApiIntegrationTest` (1개, Testcontainers) | 생성 → 상태별 목록 → 상태 변경 → 조회 → 삭제 → 404 |
| `OrderControllerTest` (15개, `@WebMvcTest`) | 기존 API 계약, 페이징 파라미터, 409/400 오류 응답 |

```bash
./gradlew test   # Docker 실행 필요 (Testcontainers)
```

## 현재 한계
- 재고를 조회하거나 변경하는 API가 없습니다. 초기 재고는 Flyway seed로만 넣습니다.
- `inventory`와 `order_items`는 FK 없이 상품명으로 연결되어 있습니다.
- 낙관적 Lock 충돌(409)이 나면 클라이언트가 재시도해야 합니다.
