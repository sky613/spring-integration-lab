# STEP 8. Redis 캐시와 장애 대응

재고 조회에 Redis 캐시를 적용하고, ERP/MES 연계에 제한된 재시도, 중복 처리 방지, 실패 재처리, 감사 로그를 추가했습니다.

## 요구사항별 구현

| # | 요구사항 | 구현 |
|---|----------|------|
| 1 | 재고 조회 Redis 캐시 | 신규 `GET /api/inventory/{productName}`(USER). `InventoryService`에 `@Cacheable` 적용 = Cache Aside |
| 2 | Cache TTL | `cache.inventory.ttl` 60s. 값은 JSON으로 저장(`inventory::keyboard` → `{"productName":"keyboard","quantity":996}`) |
| 3 | 재고 변경 시 캐시 무효화 | 주문 생성(차감), 취소·삭제(복구) 시 `InventoryChangedEvent` 발행 → **커밋 후**(`@TransactionalEventListener(AFTER_COMMIT)`) 해당 키 삭제 |
| 4 | API Timeout | ERP/MES Connect 2s·Read 3s(기존). Redis Connect·Command 500ms(core-api, Gateway). 재시도 전체 상한 4s < Gateway 10s |
| 5 | 제한된 Retry + Exponential Backoff | `RetryExecutor`: 최대 3회, 200ms → 400ms(×2, 최대 2s), 50~100% Jitter, 전체 4s 상한. **502/503/504, 연결 거부만** 재시도 |
| 6 | Idempotency Key | `POST /api/orders`의 `Idempotency-Key` 헤더(선택). ERP 호출에는 항상 `Idempotency-Key: order-{orderId}` |
| 7 | 중복 요청 방지 | 주문: 같은 키는 처음 결과 재생(DB unique). 연계: Redis 락(같은 주문 동시 실행 1건, 나머지 409) + 이미 SUCCESS면 ERP 재호출 없이 200 |
| 8 | 실패 연계 재처리 API | `POST /api/integrations/history/{id}/retry`(ADMIN, STEP 7): 새 이력 `trigger=RETRY`, `retryOf=원래 이력` |
| 9 | 최대 재시도 초과 시 실패 상태 유지 | 자동 재시도 소진 시 `FAILED` + `attemptCount` 기록. 원래 FAILED 이력은 재처리 후에도 그대로 남음 |
| 10 | 연계 이력 감사 로그 | 이력 컬럼 `requested_by`, `trigger_type`, `retry_of`, `attempt_count`(Flyway V4) + `AUDIT` Logger(요청 ID 포함) |

추가로 Gateway에 `/api/inventory/**` Route를 넣었습니다. STEP 6의 허용 목록 방식이므로 새 API는 Route도 함께 추가해야 합니다. 처음 Compose 검증에서 404가 나서 발견했습니다.

## 1. 재고 캐시 (Cache Aside)

```
조회: GET /api/inventory/keyboard
  Redis inventory::keyboard 있음 → 그대로 반환 (DB 조회 없음)
                           없음 → DB 조회 → Redis에 저장(TTL 60s) → 반환

변경: POST /api/orders (keyboard 2개)
  트랜잭션: 재고 FOR UPDATE → 차감 → 주문 저장 → InventoryChangedEvent 발행
  커밋 후:  Redis inventory::keyboard 삭제  ← 다음 조회는 DB의 새 값
  Rollback: 이벤트 전달 안 됨 (재고가 바뀌지 않았으므로 캐시 유지)
```

| 설계 | 이유 |
|------|------|
| 커밋 **후**에 삭제 | 커밋 전에 지우면, 그 사이 다른 요청이 DB의 **이전 값**을 읽어 다시 캐시에 넣는다 |
| 값을 갱신하지 않고 삭제 | 갱신 순서가 꼬이면(동시 주문) 오래된 값으로 덮어쓸 수 있다. 삭제 후 다음 조회가 DB에서 다시 채우는 쪽이 단순하고 안전하다 |
| TTL 60s | 무효화에 실패하거나 놓쳐도(Redis 일시 장애, 경쟁 상황) 오래된 값은 최대 60초만 유지된다 |
| JSON 직렬화 (`Jackson2JsonRedisSerializer`) | JDK 직렬화와 달리 `redis-cli`로 읽을 수 있고, 클래스 정보가 Redis에 남지 않는다 |
| `LoggingCacheErrorHandler` | Redis 장애 시 캐시 오류로 API를 실패시키지 않고 DB에서 읽는다 (Fail-open) |
| 없는 상품은 캐시하지 않음 | 404는 예외라 저장되지 않는다 (`disableCachingNullValues`) |

## 2. 연계 재시도 정책

| 오류 | 재시도 | 이유 |
|------|:------:|------|
| 502 / 503 / 504 | ✅ 최대 3회 | 상대가 "지금은 처리 못 함"을 명시. ERP에는 Idempotency-Key가 있어 다시 보내도 중복 접수되지 않는다 |
| 연결 거부, 호스트 없음 | ✅ | 요청이 서버에 **도달하지 않았으므로** 안전하다 |
| 500 | ❌ | 상대 시스템의 버그일 가능성이 높아 다시 보내도 같다 |
| 4xx | ❌ | 요청 자체가 잘못됨 |
| Read Timeout, Connection reset | ❌ | 상대가 **이미 처리했을 수 있고**, 느린 시스템에 부하를 더한다 |
| Connect Timeout | ❌ | `SocketTimeoutException`이라 Read Timeout과 메시지로만 구별된다. 이 구별에 의존하지 않으려고 재시도 대상에서 뺐다 (보수적 선택) |
| 응답 해석 실패, 업무 거절(MES 생산 불가) | ❌ | 결과가 바뀌지 않는다 |

- **Backoff:** 200ms → 400ms (×2, 최대 2s). 실제 대기는 50~100% 범위에서 무작위로 정합니다(Jitter). 여러 요청이 같은 순간에 일제히 재시도해 막 복구된 시스템을 다시 쓰러뜨리는 것(Thundering Herd)을 막기 위해서입니다.
- **시간 상한:** 다음 시도를 시작할 시점이 첫 시도 후 4초를 넘으면 멈춥니다. 재시도하는 오류는 모두 빨리 실패하는 종류라, ERP·MES 전체 처리 시간은 Gateway Response Timeout(10s)보다 짧습니다. 가장 오래 걸리는 경우는 ERP·MES 둘 다 Read Timeout일 때로 약 6초입니다.
- **기록:** `attemptCount`는 ERP와 MES 호출 횟수의 합입니다. 예를 들어 MES 503을 3번 받으면 ERP 1 + MES 3 = 4입니다.
- **구현:** 새 라이브러리 없이 `RetryExecutor`(약 60줄)로 직접 만들었습니다. Spring Retry나 Resilience4j는 AOP와 의존성이 추가되는데, 이번 요구사항(재시도 판정, 백오프, 상한)은 이 정도 코드로 충분합니다. Circuit Breaker가 필요해지면 Resilience4j 도입을 검토합니다.

## 3. 중복 처리 방지

### POST /api/orders: Idempotency-Key

```bash
curl -X POST localhost:8080/api/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: 5f0c9a1e-order-001' -H 'Content-Type: application/json' -d '{...}'
```

| 상황 | 응답 |
|------|------|
| 처음 | 201 + 주문 생성, 재고 차감 |
| 같은 사용자 + 같은 키 + 같은 본문 | 201, **같은 주문**, `Idempotent-Replayed: true`, 재고 추가 차감 없음 |
| 같은 키 + 다른 본문 | 422 `Idempotency-Key was already used with a different request` |
| 다른 사용자 + 같은 키 | 별개의 새 주문 (키는 사용자별, 다른 사용자의 주문이 노출되지 않음) |
| 첫 요청이 실패(재고 부족 409 등) | 키가 남지 않음 → 같은 키로 다시 시도 가능 |
| 키 없음 | 기존 동작 그대로 매번 새 주문 (하위 호환) |
| 형식 오류 (공백, 101자 이상) | 400 |

- **동작 방식:**
  - 키 기록(`idempotency_keys`, unique `(owner, idempotency_key)`)을 **먼저 INSERT**하고, 같은 트랜잭션에서 주문을 생성합니다.
  - 같은 키의 동시 요청은 PostgreSQL unique 인덱스에서 앞 트랜잭션이 끝날 때까지 기다린 뒤 충돌을 받습니다.
  - 충돌을 받은 쪽은 이미 저장된 결과를 다시 돌려줍니다(Replay). 동시에 5건을 보내도 주문 1건, 재고 차감 1회입니다.
- **Redis를 쓰지 않은 이유:** 키 기록과 주문 생성이 **같은 트랜잭션**이어야 합니다. 그래야 "주문은 생겼는데 키 기록은 없는" 상태가 생기지 않습니다.
- **Replay 응답의 본문:** 처음 응답을 그대로 저장한 것이 아니라 **현재 주문 상태**입니다. 그사이 상태가 바뀌었으면 바뀐 값이 나갑니다.

### 연계 Sync: Redis 락 + 성공 여부 확인

```
POST /orders/{id}/sync
  1) 주문 조회 (없으면 404)
  2) Redis SET integration:lock:order:{id} <token> NX PX 30000
       실패 → 409 "Integration for order N is already in progress"
       Redis 연결 불가 → 503 (중복 실행 위험이 있으므로 진행하지 않음, Fail-closed)
  3) 이미 SUCCESS 이력 있음 → ERP 호출 없이 기존 이력 200
  4) ERP(재시도) → MES(재시도) → 이력 저장 → 201
  5) 락 해제: Lua로 token이 같을 때만 DEL (TTL 만료 후 다른 요청이 건 락을 지우지 않음)
```

- 락을 잡은 **뒤에** 성공 여부를 확인합니다. 그래야 동시에 들어온 두 요청이 둘 다 "아직 성공 안 함"으로 판단하는 경쟁 상태를 막을 수 있습니다.
- ERP에는 항상 `Idempotency-Key: order-{orderId}`를 보냅니다. 락 TTL이 만료되는 극단적인 상황이나 Read Timeout 이후 재처리에서도 ERP가 중복을 걸러낼 수 있게 하기 위해서입니다. 다만 Mock(WireMock)은 실제로 중복을 거르지 않습니다.

## 4. 실패 재처리와 감사

```
erp-outage 주문 (ERP 처음 3번 503)
  sync   → 201 FAILED  attemptCount=3 trigger=SYNC              (자동 재시도 소진 → FAILED 유지)
  retry  → 201 SUCCESS attemptCount=2 trigger=RETRY retryOf=7   (새 이력. 이력 7은 FAILED 그대로)
  sync   → 200 SUCCESS (기존 이력 8, ERP 호출 없음)
  retry 7→ 200 SUCCESS (기존 이력 8)
  retry 8→ 409 (SUCCESS 이력은 재처리 대상 아님)
```

감사 로그는 `AUDIT` Logger로 남기고, 요청 ID가 함께 찍힙니다. 로그 수집기에서 이 Logger 이름으로 분리해 별도로 보관할 수 있습니다.

```
[0cf25bb4-...] AUDIT : integration SUCCESS historyId=8 orderId=10 trigger=RETRY retryOf=7 actor=admin1 failedSystem=null errorType=null attempts=2 durationMs=10 erpOrderNo=ERP-10
[9c85f5ea-...] AUDIT : integration skipped: already succeeded orderId=10 historyId=8 trigger=SYNC actor=admin1
[d06457f9-...] AUDIT : integration rejected: in progress orderId=11 trigger=SYNC actor=admin1
```

STEP 5의 `Integration failed: ...` WARN 로그는 이 감사 로그로 대체했습니다.

### Mock 시나리오 (추가)

| customerId | ERP 동작 | 결과 |
|------------|----------|------|
| `erp-unavailable` | 항상 503 | 3회 시도 후 `FAILED`, attemptCount=3 |
| `erp-flaky` | 첫 호출만 503 | 자동 재시도로 `SUCCESS`, attemptCount=3 (ERP 2 + MES 1) |
| `erp-outage` | 처음 3번 503, 이후 성공 | Sync는 `FAILED`, 재처리 API로 `SUCCESS` |

## 5. 장애 시 동작 (Redis 중지)

| 기능 | 동작 | 검증 결과 |
|------|------|-----------|
| Gateway Rate Limit | Fail-open (요청 허용, ERROR 로그) | 통과 |
| 재고 조회 | Fail-open: 캐시 오류는 WARN, DB에서 조회 | 200 (첫 요청 0.56s) |
| 주문 등록 | 정상 처리, 캐시 무효화 실패는 WARN (TTL로 만료) | 201 |
| 연계 Sync | Fail-closed: 락 없이 진행하면 중복 실행 위험 | 503 `Service temporarily unavailable` |
| 연계 이력 조회 | Redis 미사용 | 200 |
| Redis 재시작 후 | 자동 재연결 | 200 |

**발견한 문제:** Redis를 중지하자 처음에는 Gateway를 통한 **모든 API가 30초 이상 멈췄습니다**. `RedisRateLimiter`는 Redis 오류 시 요청을 허용하도록 되어 있지만, Timeout을 지정하지 않으면 Lettuce 기본값(60초)을 다 기다린 뒤에야 오류가 납니다. Gateway에도 Redis Timeout 500ms를 설정해 해결했습니다. 장애 대응에서 Timeout이 없는 의존성 하나가 전체를 멈출 수 있다는 예입니다.

## 6. 검증 결과

### 자동 테스트

`./gradlew test`로 **123개**(core-api 112개, api-gateway 11개)가 통과했습니다.

| 테스트 | 내용 |
|--------|------|
| `InventoryCacheIntegrationTest` (5) | Redis JSON 저장과 TTL(1~60s). DB를 직접 바꿔도 캐시 값 반환(캐시 적중). 주문·취소 후 키 삭제와 최신 값. Rollback 주문은 캐시 유지. 없는 상품 404와 미캐시 |
| `OrderIdempotencyIntegrationTest` (7) | 같은 키 재생과 재고 1회 차감. 다른 본문 422. 사용자별 키. 키 없으면 새 주문. 형식 오류 400. 실패 요청은 키 미소비. **동시 5건 → 주문 1건** |
| `IntegrationApiIntegrationTest` (+) | 오류별 시도 횟수와 ERP 호출 수(같은 Idempotency-Key): 500·reset 1회, 503 3회, MES 503 4회. erp-flaky 자동 복구. erp-outage FAILED 유지 후 재처리 SUCCESS. 중복 Sync 200. **동시 Sync 3건 → 1건 실행, 2건 409**. 감사 필드 |
| `RetryExecutorTest` (4) | 일시 오류 후 성공, 최대 횟수에서 중단, 재시도 불가 오류 즉시 중단, 시간 상한 |

**테스트가 실제로 잡아내는지 확인:** 캐시 삭제 코드와 재시도 판정(503 → false)을 일부러 망가뜨리자 관련 테스트 5건이 실패했습니다. 코드는 다시 되돌렸습니다.

### Docker Compose (Gateway + 실제 Keycloak 토큰)

| 항목 | 결과 |
|------|------|
| 재고 조회 → Redis | `inventory::keyboard` = `{"productName":"keyboard","quantity":996}`, TTL 60s |
| 주문 후 | 키 삭제(EXISTS 0), 조회 시 995 |
| Idempotency-Key 재요청 | 201 + `Idempotent-Replayed: true`, 같은 주문 ID, 재고 1회 차감 |
| 같은 키 다른 본문 / 다른 사용자 | 422 / 새 주문 |
| 시나리오별 Sync | customer-1 SUCCESS(2회), erp-error FAILED(1회), erp-unavailable FAILED(3회, 532ms), erp-flaky SUCCESS(3회), erp-timeout FAILED(1회, 3007ms), mes-error FAILED(4회) |
| erp-outage | FAILED(3회) → retry SUCCESS(retryOf=7) → sync 200 → retry 200. ERP 호출 4회 모두 `Idempotency-Key: order-10` |
| 동시 Sync 3건 | 201 1건, 409 2건 |
| 감사 로그 | 위 예시처럼 요청 ID, 실행자, 실행 유형, 시도 횟수가 기록됨 |
| Redis 중지 | 5절 표 |

## 7. 학습 정리

### Cache Aside (Lazy Loading)

애플리케이션이 캐시를 직접 관리하는 가장 흔한 패턴입니다. 읽을 때 캐시에 없으면 DB에서 읽어 캐시에 넣고, 쓸 때는 DB에 쓴 뒤 캐시를 지웁니다.

| 패턴 | 쓰기 방식 | 특징 |
|------|-----------|------|
| **Cache Aside** (이번 구현) | DB 쓰기 + 캐시 삭제 | 단순. 캐시 장애 시 DB로 대체 가능. 첫 조회는 느림(miss) |
| Read Through | 캐시가 DB에서 직접 읽음 | 캐시 계층이 DB를 알아야 함 |
| Write Through | 캐시와 DB에 함께 씀 | 항상 최신이지만 쓰기가 느려짐 |
| Write Behind | 캐시에 쓰고 나중에 DB 반영 | 빠르지만 캐시 장애 시 데이터 유실 위험 |

캐시는 **자주 읽고 가끔 바뀌는 데이터**에 효과가 큽니다. 재고처럼 정확성이 중요한 값은 화면 표시용 조회에만 캐시를 쓰고, **주문 시 재고 차감은 반드시 DB(FOR UPDATE)로** 합니다. 이번 구현도 주문 처리에는 캐시를 쓰지 않습니다.

### Cache Invalidation

"컴퓨터 과학에서 어려운 두 가지는 캐시 무효화와 이름 짓기"라는 말이 있을 정도로 까다로운 문제입니다.

- **언제 지우는가:** DB 커밋 **후**에 지웁니다. 커밋 전에 지우면 다른 요청이 이전 값을 다시 캐시에 넣을 수 있습니다.
- **커밋 후에 지워도 남는 경쟁:** "조회 A가 DB에서 이전 값을 읽음 → 쓰기 B가 커밋하고 캐시 삭제 → A가 이전 값을 캐시에 저장" 순서로 겹치면 이전 값이 남습니다. 이 경우 TTL이 상한이 됩니다.
- **지우지 못했을 때:** Redis 장애 등으로 삭제에 실패할 수 있습니다. 경고만 남기고 TTL로 만료를 기다립니다. 원래 처리(주문)는 실패시키지 않습니다.

### Retry

일시적인 장애(네트워크 순간 단절, 배포 중 503)를 사용자 개입 없이 넘기는 수단입니다. 잘못 쓰면 장애를 키우므로 다음을 지켜야 합니다.

1. **재시도할 오류만:** 다시 보내면 성공할 수 있고, 중복 처리 위험이 없는 오류만 재시도합니다.
2. **횟수와 시간 제한:** 무한 재시도는 장애 중인 시스템에 부하를 몇 배로 늘립니다.
3. **Exponential Backoff + Jitter:** 간격을 점점 늘리고 무작위로 흩어서, 복구 직후 요청이 몰리는 것을 막습니다.
4. **멱등성 확보:** 재시도로 같은 요청이 두 번 처리되어도 결과가 같아야 합니다(Idempotency-Key).
5. **계층별 중복 주의:** 클라이언트, Gateway, 서비스가 각각 3번씩 재시도하면 최대 27배가 됩니다. 이번 구현은 core-api 한 곳에서만 재시도합니다.

### Idempotency (멱등성)

같은 요청을 여러 번 보내도 한 번 보낸 것과 결과가 같은 성질입니다. HTTP에서 GET, PUT, DELETE는 정의상 멱등이지만 **POST는 멱등이 아닙니다**. 네트워크 오류로 응답을 받지 못한 클라이언트는 "처리됐는지" 알 수 없어서 다시 보냅니다. 서버가 이를 구별할 방법이 Idempotency-Key입니다.

| 적용 위치 | 방법 |
|-----------|------|
| 클라이언트 → core-api 주문 | `Idempotency-Key` 헤더 + DB unique 제약. 같은 키는 처음 결과 재생 |
| core-api → ERP | 주문마다 고정 키 `order-{id}`. ERP가 중복 접수를 거르도록 요청 |
| 연계 Sync API | 이미 성공한 주문은 같은 결과 반환 (자연스러운 멱등 설계) |

### Eventual Consistency (최종적 일관성)

모든 데이터가 **항상** 일치하지는 않지만, 시간이 지나면 **결국** 일치하는 성질입니다. 분산 시스템에서 가용성을 유지하기 위해 받아들이는 선택입니다.

- **재고 캐시:** DB가 바뀐 뒤 최대 TTL(60초) 동안 캐시가 이전 값일 수 있습니다. 무효화에 실패했거나 경쟁 상황인 경우입니다. 그래서 주문 시 재고 차감은 캐시가 아니라 DB로 판단합니다.
- **ERP·MES 연계:** 주문은 core-api에 즉시 저장되지만 ERP에 반영되는 시점은 다릅니다. 실패하면 FAILED로 남았다가 재처리 후에 일치합니다. MES 실패로 ERP에만 주문이 들어간 상태도 재처리나 보상으로 결국 맞춰야 합니다.
- **설계 원칙:** "언제 일치하는가(TTL, 재처리 주기)"와 "불일치하는 동안 무엇을 막는가(DB 기준 재고 차감)"를 명시합니다.

### Circuit Breaker (개념)

계속 실패하는 시스템을 매번 호출해 Timeout까지 기다리는 대신, 일정 기준을 넘으면 **호출 자체를 차단**하고 즉시 실패시키는 패턴입니다. 전기 차단기에서 이름이 왔습니다.

```
CLOSED (정상 호출)
  ── 실패율이 임계치 초과 (예: 최근 20건 중 50% 실패) ──▶ OPEN (호출하지 않고 즉시 실패, fallback)
  ◀── 시험 호출 성공 ── HALF_OPEN ◀── 대기 시간(예: 30s) 경과 ──┘
                     └── 시험 호출 실패 → 다시 OPEN
```

| | Retry | Circuit Breaker |
|--|-------|-----------------|
| 목적 | **일시적** 오류를 넘김 | **지속적** 장애에서 빨리 포기 |
| 장애 중 효과 | 부하를 늘릴 수 있음 | 부하를 끊어 상대의 복구 시간을 벌어 줌 |
| 호출자 효과 | 지연 증가 | 즉시 실패. Timeout 대기와 스레드 고갈 방지 |

둘은 함께 씁니다. Retry가 Circuit Breaker 안쪽에서 동작하게 해서, 차단된 동안에는 재시도도 하지 않게 합니다. 이번 구현에는 Circuit Breaker가 없습니다. ERP가 계속 503이면 요청마다 최대 3회 시도합니다. 도입한다면 Resilience4j `CircuitBreaker`를 `ErpClient`, `MesClient`별로 두고, OPEN일 때 `FAILED`(errorType: CIRCUIT_OPEN)로 기록하는 방식이 자연스럽습니다.

## 8. 한계와 다음 단계 후보

- **Idempotency 키 정리 없음:** `idempotency_keys`가 계속 쌓입니다. 보관 기간(예: 24시간)을 정하고 정리 배치를 만들어야 합니다.
- **Replay 본문:** 처음 응답 그대로가 아니라 주문의 현재 상태입니다. 주문이 삭제됐으면 404가 됩니다.
- **ERP 중복 방지는 ERP 몫:** `Idempotency-Key`는 보내지만 Mock은 실제로 중복을 거르지 않습니다. 실제 ERP와 규격 합의가 필요합니다.
- **재시도 중 스레드 점유:** Backoff 대기(최대 약 0.6초) 동안 요청 스레드를 붙잡습니다. 대량 처리라면 비동기 큐와 재처리 스케줄러가 적합합니다.
- **재처리는 수동만:** 자동 재처리 배치(FAILED 이력을 주기적으로 재시도)가 없습니다. 재처리 횟수 상한도 없습니다.
- **Circuit Breaker 없음:** 위 개념 설명 참고.
- **Redis 장애 시 연계 불가:** 연계 API는 503입니다. 대안으로 DB 기반 락(이력 IN_PROGRESS + 부분 unique 인덱스)을 둘 수 있지만, 처리 중 프로세스가 죽으면 남는 이력을 복구하는 로직이 필요합니다.
