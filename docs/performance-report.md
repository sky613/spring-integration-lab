# STEP 9. 성능 테스트와 최적화 보고서

k6로 주요 API 5개(주문 등록, 주문 단건 조회, 주문 목록 조회, 재고 조회, ERP 연계)에 부하를 걸었습니다. 병목을 찾아 개선한 뒤 **같은 조건에서 다시 측정**했습니다. 이 문서의 수치는 모두 실제 측정값이며, 원본 결과(JSON)는 `perf/results/`에 있습니다.

## 요약

| 항목 | 결과 |
|------|------|
| 가장 큰 병목 | **주문 목록 조회**: 상태별 2만 건을 매번 정렬(인덱스 미활용) → DB CPU 포화. 100 VU에서 271 req/s, p95 693ms |
| 핵심 개선 | `(status, created_at)` 복합 인덱스(Flyway V5): 쿼리 20.3ms → 0.13ms, **처리량 2.8배(271 → 756 req/s), p95 693 → 265ms** |
| 그 외 개선 | SQL debug 로그 끄기(`SQL_LOG_LEVEL`): CPU 포화 API 처리량 +12~16%. G1 GC 명시: Full GC 제거(최대 Pause 230 → 113ms) |
| 개선하지 않은 것 | Connection Pool 크기(10 → 20 실험: 효과 없음), HTTP 연결 재사용(TIME_WAIT 29배 감소, 지연시간 효과 없음) |
| 남은 병목 | core-api CPU(요청마다 JWT 서명 검증 ≈ CPU 36%), 재고 행 Lock 경합(주문 등록), 목록 count 쿼리 |
| 오류율 | 개선 전후 전체 40회 측정 모두 **0%** |

## 1. 테스트 환경

| 항목 | 값 |
|------|-----|
| 호스트 | MacBook Pro Apple M1 Pro (8 Core), 16GB, macOS (Darwin 24.6) |
| Docker | Docker Desktop 29.2.1 (VM: 8 CPU, 8GB) |
| 부하 도구 | k6 1.0.0 (`grafana/k6:1.0.0` 컨테이너, CPU 2개). Compose `backend` 네트워크 안에서 `api-gateway:8080` 호출 (호스트 포트 포워딩 영향 제외) |
| 요청 경로 | k6 → api-gateway(Rate Limiter: Redis) → core-api(JWT 검증) → PostgreSQL / Redis / mock-erp·mock-mes |
| 자원 제한 | core-api 2 CPU·1GB, api-gateway 2 CPU·512MB, PostgreSQL 2 CPU·1GB, mock-erp·mock-mes 각 1 CPU (`perf/docker-compose.perf.yml`) |
| 애플리케이션 | Spring Boot 3.5.16, Java 21 (Temurin JRE), HikariCP Pool 10(기본), Tomcat 스레드 200(기본) |
| 데이터 | 주문 100,000건(상품 2개씩 = order_items 200,000행, 상태 5종 각 20%), 연계 이력 50,000건, 재고 3개 상품 |
| 인증 | Keycloak 실제 토큰(OIDC Authorization Code + PKCE). user1(USER), admin1(ADMIN, 연계 API) |

측정 전용 Override(`perf/docker-compose.perf.yml`)는 운영 설정이 아닙니다.
- **Gateway Rate Limit:** 사실상 끕니다. 부하 도구가 IP 1개라 기본값(초당 10건)이면 대부분 429가 됩니다.
- **진단 로그:** GC 로그와 Hikari Pool 상태 로그를 5초 주기로 켭니다.
- **WireMock 요청 기록:** 1,000건으로 제한합니다(2.5 참조).

### 동일 조건 보장 (`perf/run.sh`)

모든 측정(API × 동시 사용자 수)마다 아래 순서를 반복합니다.

1. **데이터 초기화**(`perf/seed.sql`)
   - 테이블을 비우고 같은 데이터를 다시 넣은 뒤 `VACUUM ANALYZE`로 실행 계획 통계를 맞춥니다.
   - 재고를 1억 개로 채워 재고 부족(409)이 섞이지 않게 합니다.
   - Redis 재고 캐시를 비웁니다.
2. **토큰 발급**: Access Token 유효시간이 5분이라 측정마다 새로 받습니다.
3. **Warm-up**: 10 VU로 15초 실행하고 결과는 버립니다(JIT, Connection Pool, 캐시 채우기).
4. **측정**: `constant-vus`로 30초 실행합니다.

| 측정 기준 | 정의 |
|------|------|
| Throughput | 30초 동안 완료한 요청 수 / 30초 (req/s) |
| 응답시간 | k6 `http_req_duration`(요청 전송~응답 수신)의 p50 / p95 / p99 |
| Error Rate | 기대 상태 코드(201/200)가 아닌 응답 비율 |
| ERP 연계 대상 | 반복마다 아직 연계하지 않은 주문을 하나씩 사용(주문 50,001번부터). 이미 성공한 주문은 ERP를 호출하지 않기 때문 |

## 2. 부하 조건

| 시나리오 | 요청 | 권한 |
|------|------|------|
| order-create | `POST /api/orders`: 상품 1개(3개 상품 중 무작위), 수량 1 | USER |
| order-get | `GET /api/orders/{id}`: 1~100,000 무작위 | USER |
| order-list | `GET /api/orders?status=PAID&sort=createdAt,desc&size=20&page=0~9` | USER |
| inventory | `GET /api/inventory/{productName}`: 3개 상품 무작위 (Redis 캐시) | USER |
| erp-sync | `POST /api/integrations/orders/{id}/sync`: ERP + MES Mock 호출 | ADMIN |

동시 가상 사용자(VU): **1(기준 성능) / 10 / 50 / 100**. VU는 응답을 받으면 대기 없이 바로 다음 요청을 보냅니다(Closed Model).

## 3. 변경 전 성능 (before)

| API | VU | Throughput (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | Error |
|-----|---:|---:|---:|---:|---:|---:|
| order-create | 1 | 411 | 2.12 | 3.40 | 5.02 | 0% |
| | 10 | 1,858 | 4.60 | 9.95 | 13.69 | 0% |
| | 50 | 1,745 | 26.85 | 53.82 | 74.23 | 0% |
| | 100 | 1,645 | 57.60 | 117.31 | 168.55 | 0% |
| order-get | 1 | 555 | 1.57 | 2.50 | 3.61 | 0% |
| | 10 | 3,128 | 2.44 | 5.74 | 15.23 | 0% |
| | 50 | 3,488 | 10.50 | 37.09 | 69.46 | 0% |
| | 100 | 3,376 | 23.09 | 70.83 | 100.48 | 0% |
| **order-list** | 1 | 119 | 8.04 | 9.99 | 11.25 | 0% |
| | 10 | 290 | 15.72 | 78.66 | 82.92 | 0% |
| | 50 | 270 | 193.22 | 307.41 | 379.29 | 0% |
| | 100 | 271 | 385.55 | **693.45** | 715.52 | 0% |
| inventory | 1 | 911 | 1.01 | 1.25 | 2.04 | 0% |
| | 10 | 3,933 | 1.80 | 4.55 | 14.79 | 0% |
| | 50 | 4,536 | 7.15 | 28.53 | 73.03 | 0% |
| | 100 | 4,529 | 15.87 | 68.12 | 88.53 | 0% |
| erp-sync | 1 | 108 | 8.74 | 12.28 | 16.12 | 0% |
| | 10 | 1,102 | 8.17 | 13.08 | 24.15 | 0% |
| | 50 | 1,096 | 42.80 | 76.21 | 92.98 | 0% |
| | 100 | 1,005 | 87.90 | 169.61 | 470.19 | 0% |

**관찰:**
- 모든 API가 10~50 VU 사이에서 처리량이 더 늘지 않았습니다(포화). 그 이후 VU를 늘리면 처리량은 그대로이고 대기시간만 늘었습니다(Little's Law: 응답시간 ≈ VU / 처리량).
- order-list는 10 VU에서 이미 포화되었고, 처리량이 다른 API의 1/6~1/15 수준입니다.

## 4. 병목 원인 분석

### 4.1 PostgreSQL EXPLAIN ANALYZE

core-api의 SQL 로그에서 실제 실행된 SQL을 가져와 같은 데이터로 실행했습니다.

**주문 목록 페이지 조회 (변경 전):** `idx_orders_status`만 있었습니다.

```
select ... from orders where status='PAID' order by created_at desc offset 100 rows fetch first 20 rows only

Limit (actual time=20.230..20.234 rows=20)
  -> Sort (Sort Key: created_at DESC, top-N heapsort)
     -> Bitmap Heap Scan on orders (rows=20000, Heap Blocks: exact=935)
        -> Bitmap Index Scan on idx_orders_status (rows=20000)
Execution Time: 20.324 ms
```

- 20건을 반환하려고 상태가 PAID인 **20,000행을 모두 읽고 정렬**합니다.
- 인덱스는 `status`로 찾는 데만 쓰이고, `created_at` 정렬에는 쓰이지 않습니다.

**개선 후 (`(status, created_at)` 복합 인덱스):**

```
Limit (actual time=0.095..0.098 rows=20)
  -> Index Scan Backward using idx_orders_status_created_at on orders (rows=120)
Execution Time: 0.130 ms
```

인덱스가 이미 `created_at` 순서이므로, 뒤에서부터 `offset + limit` = 120행만 읽고 멈춥니다.

| 쿼리 | 변경 전 | 변경 후 | 비고 |
|------|------:|------:|------|
| 목록 페이지 (status + created_at desc) | 20.3ms (20,000행 정렬) | **0.13ms** (120행) | Index Scan Backward |
| count (`X-Total-Count`) | 3.9ms | 4.3ms | 2만 행 집계는 그대로 → 남은 과제 |
| 상품 일괄 조회 (`order_id in (...)`) | 5.3ms* | (같음) | `idx_order_items_order_id` 사용 |
| 주문 단건 (`id = ?`) | 0.04ms | (같음) | PK Index Scan |
| 연계 성공 이력 (`order_id, status`) | 0.06ms | (같음) | STEP 8 V4 복합 인덱스 사용 |

\* 상품 일괄 조회 5.3ms는 측정용으로 서브쿼리 안에 목록 조회(정렬 포함)를 넣은 시간입니다. 실제 앱은 ID 목록을 IN 절로 넘깁니다.

**인덱스 사용 통계(`pg_stat_user_indexes`)에서 발견한 것:**
- `idx_integration_history_order_id`, `idx_integration_history_status`, `idx_orders_customer_id`는 한 번도 사용되지 않았습니다(`idx_scan` 0).
- `idx_integration_history_order_id`는 STEP 8에서 추가한 `(order_id, status)` 인덱스와 앞 컬럼이 같아서 중복입니다.
- 이번 변경 범위가 아니어서 삭제하지 않았습니다(7장 추가 과제).

### 4.2 JPA N+1 진단

SQL 로그(`org.hibernate.SQL`, 요청 ID 포함)에서 **요청 1건당 실행된 SQL 수**를 측정 구간별로 세었습니다.

| API | 요청당 SQL | 내용 | 판정 |
|-----|---:|------|------|
| order-list (주문 20건) | **3** | 주문 페이지 1 + count 1 + 상품 `in (20개 ID)` 1 | N+1 없음 |
| order-get | 2 | 주문 1 + 상품 1 | 정상 |
| order-create | 4 | 재고 `for update` 1 + 재고 update 1 + 주문 insert 1 + 상품 insert 1 | 정상 |
| erp-sync | 4 | 주문 + 상품 + 성공 이력 확인 + 이력 insert | 정상 |
| inventory | 0 | Redis 캐시 hit | DB 미접근 |

- `OrderResponse.from()`이 주문마다 `getItems()`(LAZY)를 호출하므로, 설정이 없으면 주문 20건에 상품 조회가 20번 실행됩니다(1 + 20 = N+1). 이 동작은 Hibernate 기본 동작에 따른 설명이며 따로 측정하지 않았습니다.
- STEP 3에서 넣은 `hibernate.default_batch_fetch_size: 100`이 이를 `in (...)` 1번으로 묶고 있음을 실제 SQL로 확인했습니다.

### 4.3 DB Connection Pool (HikariCP)

Hikari Housekeeper 로그(`Pool stats`, 5초 주기)를 측정 시각별로 대조했습니다(Pool 10).

| 측정 (before) | active | **waiting(최대)** | 해석 |
|------|---:|---:|------|
| order-list 100 VU | 10/10 | **90** | 느린 쿼리가 Connection을 오래 점유 |
| order-create 100 VU | 10/10 | **85** | 재고 행 Lock 대기 중에도 Connection 점유 |
| erp-sync 100 VU | 10/10 | 58 | |
| order-get 50~100 VU | 10/10 | 1~21 | |
| inventory 전 구간 | **0** | 0 | 캐시 hit는 Connection을 쓰지 않음 |

**Pool을 늘리면 빨라지는가? → 실험 결과 아니오** (100 VU, Pool 10 → 20)

| API | Pool 10 (req/s) | Pool 20 (req/s) | 차이 |
|-----|---:|---:|---:|
| order-create | 1,761 | 1,744 | -1% |
| order-get | 4,156 | 4,312 | +4% |
| order-list | 764 | 725 | -5% |
| erp-sync | 1,131 | 1,171 | +3% |

- Pool 20에서는 waiting이 0이 됐지만 처리량은 측정 오차(±5~10%) 안에서 그대로입니다.
- 대기 장소가 Pool에서 DB(Lock, CPU)나 core-api CPU로 옮겨졌을 뿐입니다(4.6 CPU 측정).
- 그래서 **Pool 10을 유지**했습니다. Connection을 늘리면 PostgreSQL의 프로세스와 메모리만 늘어납니다.

### 4.4 Thread Dump

100 VU 부하 중 `kill -3`(SIGQUIT)으로 Thread Dump를 떠서 Tomcat worker 103개를 대기 위치별로 분류했습니다. JRE 이미지라 `jcmd`가 없습니다.

| 상태 (stack 기준) | order-create | order-list |
|------|---:|---:|
| `HikariPool.getConnection` 대기 | **87** | **76** |
| 소켓 I/O(`Net.poll`, JDBC 응답 읽기) | 9 | 7 |
| 유휴(요청 대기) | 7 | 19 |
| JDBC 결과 처리 중 | 0 | 1 |

Connection을 가진 스레드는 10개뿐이고 나머지는 Connection을 기다립니다. Hikari waiting 값(85~90)과 일치합니다.

**order-create는 무엇을 기다리는가?** PostgreSQL `pg_stat_activity`를 2초 간격으로 10번 샘플링했습니다(활성 세션 평균).

| wait_event | 평균 세션 수 |
|------|---:|
| `Lock:tuple` | 2.1 |
| `Lock:transactionid` | 0.9 |
| `Client:ClientRead` | 0.6 |
| CPU(대기 없음) | 0.5 |
| `IO:WalSync` | 0.2 |

- 주문 등록의 병목은 **재고 행 Lock 경합**입니다.
- 모든 주문이 3개 상품의 재고 행에 `SELECT ... FOR UPDATE`를 겁니다(STEP 3의 비관적 Lock).
- 그래서 같은 상품 주문은 한 줄로 서서 처리됩니다. 정합성을 위한 의도된 설계이므로 이번에는 바꾸지 않았습니다(7장).

### 4.5 JVM Heap / GC

GC 로그(`-Xlog:gc*`)를 측정 전체(약 17분, 20회 측정)에 대해 집계했습니다.

**JVM이 Serial GC를 자동 선택하고 있었습니다.**
- 컨테이너 메모리가 1GB(1,792MB 미만)이면 JVM은 "서버급 머신"이 아니라고 판단합니다.
- 그래서 `-XX:+UseG1GC`가 없으면 단일 스레드 Stop-the-World 방식인 Serial GC를 씁니다.
- 기본 Compose(자원 제한 없음, 8 CPU·8GB VM)에서는 G1이 선택되므로, 자원을 제한한 환경에서만 드러나는 차이입니다.

| 항목 | before (Serial, 자동 선택) | after (G1, 명시) |
|------|------|------|
| Heap 상한 | 768MB (MaxRAMPercentage 75%) | 768MB |
| 실제 Heap 크기 (측정 종료 시점) | 141MB (Eden 40MB) | 220MB |
| Young GC | 6,476회, 평균 2.84ms | 2,916회 (Normal 2,756회 평균 2.79ms, Mixed 등 160회 평균 3.8~7.4ms) |
| **Full GC** | **8회 (부하 중 최대 230ms)** | **0회** |
| Concurrent 주기 Remark | — | 60회, 평균 15.7ms, 최대 113ms |
| **GC Pause 총합** | **19.2초** | **9.6초 (-50%)** |
| 최대 Pause | 230ms | 113ms |
| Pause p99 | 7.0ms | 22.5ms |

- Serial GC는 Heap을 작게 유지해서 부하 중 Young GC가 초당 약 9회 발생했습니다.
- Old 영역이 차면 Full GC(130~230ms) 동안 **모든 요청이 멈췄습니다**.
- 측정 최대 응답시간(0.4~1.3초)은 이런 정지와 겹칠 수 있습니다.

### 4.6 CPU 사용률과 JFR 프로파일

100 VU 부하 중 `docker stats`로 컨테이너 CPU를 3번 샘플링했습니다(after 설정 기준, 100% = 1 Core).

| API | core-api (상한 200%) | PostgreSQL (상한 200%) | api-gateway | 판정 |
|-----|---:|---:|---:|------|
| order-create | **207%** | 54% | 58% | core-api CPU + 재고 Lock |
| order-get | **192%** | 55% | 127% | core-api CPU |
| order-list | 85% | **201%** | 45% | **DB CPU** (count 쿼리) |
| inventory | **182%** | 0% | 152% | core-api CPU |
| erp-sync | **199%** | 57% | 62% | core-api CPU |

**core-api는 CPU를 어디에 쓰는가?**
- order-get 100 VU 부하 중 JFR(`-XX:StartFlightRecording`, profile 설정)로 30초를 기록했습니다.
- 실행 샘플 543개를 stack 기준으로 분류했습니다.
- 분류는 stack에서 처음 일치한 패키지 기준이라 근사치입니다.

| 분류 | 샘플 | 비중 |
|------|---:|---:|
| **JWT 검증** (Nimbus RSA 서명 검증 `MutableBigInteger`, Base64 decode) | 157 | 29% |
| Bearer 토큰 추출 정규식 (`DefaultBearerTokenResolver`) | 38 | 7% |
| Micrometer Observation (요청 메트릭) | 77 | 14% |
| `StrictHttpFirewall` 정규식 | 43 | 8% |
| JPA / JDBC | 40 | 7% |
| JSON 직렬화 | 31 | 6% |
| 기타 (Tomcat, Spring MVC 등) | 157 | 29% |

단건 조회처럼 가벼운 API에서는 **비즈니스 로직보다 요청마다 반복되는 보안 처리(JWT 서명 검증 + 토큰 파싱 ≈ 36%)가 CPU를 가장 많이 씁니다**.

### 4.7 SQL 로그 출력 비용

- `logging.level.org.hibernate.SQL: debug`(+ `format_sql`)는 SQL 1개마다 여러 줄을 콘솔에 출력합니다.
- 변경 전 측정 약 17분 동안 core-api 로그가 **1.08GB**(SQL 로그 269만 건) 쌓였습니다.
- SQL 로그를 끈 변경 후 측정에서는 141MB가 쌓였고, 그중 SQL 로그는 0건입니다. 나머지는 Hikari 진단 로그와 AUDIT 로그입니다.

### 4.8 Redis 캐시 적용 전후 비교 (재고 조회)

같은 after 설정에서 `spring.cache.type=none`(매번 DB 조회)과 Redis 캐시를 비교했습니다.

| VU | 캐시 없음 (req/s) | p50 / p95 (ms) | Redis 캐시 (req/s) | p50 / p95 (ms) | 처리량 |
|---:|---:|---:|---:|---:|---:|
| 1 | 758 | 1.22 / 1.53 | 893 | 1.02 / 1.33 | +18% |
| 10 | 3,633 | 2.11 / 4.23 | 3,921 | 1.82 / 4.19 | +8% |
| 50 | 4,352 | 7.87 / 29.28 | 4,717 | 7.06 / 24.29 | +8% |
| 100 | 4,404 | 17.50 / 54.44 | 4,912 | 15.24 / 53.41 | +12% |

| DB 영향 | 캐시 없음 | Redis 캐시 |
|------|------|------|
| 측정 중 DB 트랜잭션 수 (`pg_stat_database.xact_commit` 증가량) | 611,379 | 0에 수렴 (Pool active 0) |
| PostgreSQL CPU (100 VU) | (측정 안 함) | 0% |

- **응답시간 개선이 작은 이유:** 재고 테이블은 3행이고 unique index 조회라 DB 조회 자체가 이미 빠릅니다(0.1ms 미만). 응답시간의 대부분은 core-api의 JWT 처리 CPU(4.6)입니다.
- **캐시의 실제 가치:** **DB 부하 제거**입니다. 같은 DB를 쓰는 주문 API와 섞인 실제 트래픽에서는 이 61만 건의 트랜잭션이 Connection Pool과 DB CPU를 놓고 다른 요청과 경쟁합니다.
- **사용하지 않은 지표:** 캐시 hit 비율은 Micrometer 캐시 메트릭을 노출하지 않아 측정하지 않았습니다. Warm-up 이후 TTL(60s) 안에서 재고 변경이 없으므로 측정 구간은 사실상 전부 hit입니다.

## 5. 개선 내용

| # | 개선 | 변경 위치 | 근거 |
|---|------|------|------|
| 1 | **주문 목록 복합 인덱스** `(status, created_at)` 추가, 중복되는 `idx_orders_status` 삭제(앞 컬럼이 같아 대체됨) | `db/migration/V5__orders_status_created_at_index.sql` | 4.1 EXPLAIN: 20.3ms → 0.13ms |
| 2 | **SQL 로그 수준을 환경 변수로 분리** `SQL_LOG_LEVEL`. 기본값은 학습용 `debug`를 유지하고 부하·운영 환경은 `info` | `application.yml`, `perf/docker-compose.perf.yml` | 4.7 로그 1.08GB, CPU 포화 API에서 출력 비용 |
| 3 | **G1 GC 명시** `-XX:+UseG1GC` | `docker-compose.yml`(core-api), `perf/docker-compose.perf.yml` | 4.5 자원 제한 시 Serial 자동 선택 → Full GC 정지 |

- **2번에서 placeholder를 쓴 이유:** Spring의 env var 바인딩은 이름을 소문자로 바꾸는데, Logback 로거 이름 `org.hibernate.SQL`은 대소문자를 구분합니다. 그래서 `LOGGING_LEVEL_ORG_HIBERNATE_SQL`로는 지정할 수 없어 placeholder를 썼습니다.
- **1번 Migration 검증:** `./gradlew test` 123개(Testcontainers PostgreSQL)가 모두 통과했고, `flyway_schema_history` version 5 success도 확인했습니다.

### 개선별 효과 분리 (100 VU, 누적 적용)

| 단계 | order-create | order-get | order-list | inventory | erp-sync |
|------|---:|---:|---:|---:|---:|
| before | 1,645 | 3,376 | 271 | 4,529 | 1,005 |
| + 인덱스 (A) | — | — | **789** (p95 250ms) | — | — |
| + SQL 로그 info (B) | 1,581* | 3,908 (+16%) | 784 | 4,736 | 1,122 (+12%) |
| + G1 GC (C) | 1,761 | 4,156 | 764 | 5,057 | 1,131 |

(단위: req/s)

- **(A)** 인덱스만으로 order-list 처리량이 2.9배가 됐습니다. SQL 로그를 꺼도 order-list는 그대로였습니다. DB CPU가 병목이기 때문입니다.
- **(B)** core-api CPU가 포화된 API(order-get, erp-sync)에서 +12~16%였습니다.
  - \* order-create B 단계의 첫 측정은 1,254 req/s였는데, core-api 재시작 직후 첫 측정이라 JIT Warm-up이 부족했습니다.
  - 같은 조건으로 다시 측정하니 1,581이었습니다. 이후 단계는 재시작 뒤 버리는 부하를 한 번 먼저 걸고 측정했습니다.
- **(C)** G1과 Serial의 차이는 측정 편차(JVM 인스턴스마다 ±10%)와 비슷한 크기라서 단계별 수치만으로는 판단할 수 없었습니다. 그래서 같은 이미지에서 GC만 바꿔 order-create를 두 번씩 측정했습니다.

| order-create (req/s) | Serial (2회) | G1 (3회) |
|------|------|------|
| 10 VU | 1,819 / 1,806 | 1,645 / 1,672 / 1,636 |
| 100 VU | 1,789 / 1,760 | 1,730 / 1,761 |

- 처리량만 보면 CPU 2개 환경에서는 **G1이 10 VU에서 약 9% 느립니다**. G1의 동시 작업과 write barrier가 CPU를 나눠 쓰기 때문입니다.
- 대신 Full GC(최대 230ms 전체 정지)가 사라지고 GC Pause 총합이 절반이 됩니다(4.5).
- 이번에는 **긴 정지 제거(지연시간 안정성)를 우선해 G1을 유지**했습니다. 처리량 최우선이라면 Parallel GC 비교가 남은 과제입니다(7장).

### 시도했지만 적용하지 않은 것

| 시도 | 측정 결과 | 결정 |
|------|------|------|
| Hikari Pool 10 → 20 | 처리량 -5% ~ +4% (오차 범위) | 유지 (4.3) |
| HTTP 연결 재사용 `-Dhttp.maxConnections=100` (ERP/MES 호출 `HttpURLConnection` 유휴 연결 기본 5개) | TIME_WAIT 소켓 19,481 → 670 (29배 감소). erp-sync 처리량 1,234 → 1,117, p99 216 → 218ms (차이 없음) | 미적용. 같은 Docker 네트워크의 Mock은 연결 비용이 작음. 실제 원격 ERP(네트워크·TLS)에서는 다를 수 있어 7장 과제로 남김 |

## 6. 변경 후 성능 (after)

before와 같은 절차로 측정했습니다. core-api는 재시작한 뒤 버리는 부하를 한 번 걸고 시작했습니다.

| API | VU | Throughput (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | Error |
|-----|---:|---:|---:|---:|---:|---:|
| order-create | 1 | 450 | 2.03 | 2.85 | 4.22 | 0% |
| | 10 | 1,645 | 5.13 | 11.51 | 16.78 | 0% |
| | 50 | 1,717 | 27.35 | 53.32 | 72.74 | 0% |
| | 100 | 1,730 | 55.51 | 110.29 | 152.48 | 0% |
| order-get | 1 | 634 | 1.43 | 1.92 | 2.83 | 0% |
| | 10 | 3,225 | 2.38 | 5.33 | 15.87 | 0% |
| | 50 | 3,757 | 9.10 | 35.64 | 71.79 | 0% |
| | 100 | 3,909 | 19.38 | 62.53 | 89.58 | 0% |
| **order-list** | 1 | 227 | 4.12 | 5.46 | 7.14 | 0% |
| | 10 | 775 | 7.18 | 52.39 | 58.65 | 0% |
| | 50 | 755 | 79.54 | 108.42 | 164.30 | 0% |
| | 100 | 756 | 118.55 | **265.37** | 315.21 | 0% |
| inventory | 1 | 893 | 1.02 | 1.33 | 2.13 | 0% |
| | 10 | 3,921 | 1.82 | 4.19 | 15.52 | 0% |
| | 50 | 4,717 | 7.06 | 24.29 | 74.18 | 0% |
| | 100 | 4,912 | 15.24 | 53.41 | 90.47 | 0% |
| erp-sync | 1 | 126 | 7.44 | 9.96 | 12.64 | 0% |
| | 10 | 1,207 | 7.79 | 11.39 | 16.06 | 0% |
| | 50 | 1,198 | 34.82 | 75.85 | 89.79 | 0% |
| | 100 | 1,143 | 74.11 | 150.77 | **1,082.05** | 0% |

### 변경 전후 비교 (100 VU)

| API | Throughput | p50 | p95 | p99 |
|-----|------|------|------|------|
| order-create | 1,645 → 1,730 (+5%) | 57.6 → 55.5 | 117.3 → 110.3 | 168.6 → 152.5 |
| order-get | 3,376 → 3,909 (+16%) | 23.1 → 19.4 | 70.8 → 62.5 | 100.5 → 89.6 |
| **order-list** | **271 → 756 (+179%)** | **385.6 → 118.6** | **693.5 → 265.4** | **715.5 → 315.2** |
| inventory | 4,529 → 4,912 (+8%) | 15.9 → 15.2 | 68.1 → 53.4 | 88.5 → 90.5 |
| erp-sync | 1,005 → 1,143 (+14%) | 87.9 → 74.1 | 169.6 → 150.8 | 470.2 → **1,082.1** |

**결과 해석 (나빠진 항목 포함):**
- **order-list**:
  - 모든 부하에서 크게 개선됐습니다(1 VU에서도 p50 8.04 → 4.12ms).
  - 100 VU에서도 p95가 265ms라서 아직 느립니다. 남은 병목은 DB CPU(count 쿼리)입니다.
- **order-create 10 VU는 -11%(1,858 → 1,645)로 나빠졌습니다.**
  - 반복 측정(1,672 / 1,636)으로 재현되므로 실제 저하입니다.
  - 같은 이미지에서 Serial로 되돌리면 1,819 / 1,806으로 회복되므로, 원인은 인덱스나 로그가 아니라 G1입니다(5장).
- **erp-sync p99가 470 → 1,082ms로 나빠졌습니다.**
  - DB의 연계 이력 시간을 분석하니, 외부 호출의 99%는 133ms 이하이고 **1%만 1,010~1,095ms**로 따로 모여 있었습니다.
  - 정확히 1초 늦는 패턴은 TCP 연결 재시도(SYN 재전송 초기 대기 1초)의 특징입니다. 연결을 재사용하지 못해 새 연결이 몰릴 때 일어날 수 있습니다.
  - 같은 설정으로 다시 측정하면 p99가 216ms였습니다. **간헐적이고 재현되지 않아서 원인을 단정하지 않았습니다.** 1초 꼬리가 생긴 측정은 before에도 있었습니다(첫 측정의 max 1,310ms).
  - 개선 효과로 주장하지 않고 7장 과제로 남겼습니다.
- inventory와 order-get의 +8~16%는 SQL 로그 비용 감소와 GC 효과가 섞인 값입니다. 측정 편차가 ±5~10%이므로 작은 차이는 개선으로 단정하지 않습니다.

## 7. 추가 개선 과제

| 우선순위 | 과제 | 근거 (측정) | 방향 |
|---|------|------|------|
| 1 | 목록 count 쿼리 | after order-list 100 VU에서 DB CPU 201%, count 4.3ms(2만 행) | 무한 스크롤은 `Slice`(count 없음), 또는 count 캐시·근사값(`pg_class.reltuples`). API 계약(`X-Total-Count`) 변경이 필요해 이번 범위 밖 |
| 2 | JWT 검증 CPU | core-api CPU의 약 36% | 같은 토큰의 검증 결과를 만료 시각까지 캐시(Caffeine)하거나, Gateway에서 한 번만 검증하고 내부 구간은 mTLS로 신뢰 |
| 3 | 재고 행 Lock 경합 | order-create 활성 세션 대부분이 `Lock:tuple`·`Lock:transactionid` | 조건부 원자 UPDATE(`quantity = quantity - ? where quantity >= ?`)로 Lock 보유 시간 단축, 인기 상품 재고 분할, 또는 Redis 원자 차감 + 비동기 확정 |
| 4 | GC 선택 | G1: Full GC 제거, 10 VU 처리량 -9% | Parallel GC, CPU 3개 이상 배정 비교. 운영 자원 크기 기준으로 결정 |
| 5 | 외부 호출 연결 재사용 | 연결 재사용 실패 시 TIME_WAIT 19,481. erp-sync p99 1초 꼬리(간헐) | 연결 Pool이 있는 HTTP Client(JDK `HttpClient`, Apache HttpClient 5)로 교체. 실제 ERP 네트워크 환경에서 재측정 |
| 6 | 미사용·중복 인덱스 | `idx_integration_history_order_id`(V4 복합 인덱스와 중복), `idx_integration_history_status`, `idx_orders_customer_id` idx_scan 0 | 운영 통계로 사용 여부를 확인한 뒤 삭제(쓰기 비용 감소) |
| 7 | Micrometer Observation 비용 | core-api CPU의 약 14% | 필요한 메트릭만 남기도록 Observation 범위 조정 |
| 8 | 관측 수단 | 이번에는 GC 로그, Thread Dump, Hikari 로그, JFR을 수동 수집 | Actuator `metrics`/`prometheus`를 별도 관리 포트 + 인증으로 노출하고 Grafana 대시보드 구성 (현재 Security가 `/actuator/metrics`를 거부) |
| 9 | 측정 한계 | k6·Gateway·core-api·DB가 한 노트북(Docker VM 8 CPU)을 공유, 30초 측정 | 별도 부하 발생 장비, 장시간(Soak) 테스트, 측정 반복 횟수 늘리기(이번 편차 ±5~10%) |

## 8. 측정 중 발견한 측정 환경 문제

| 문제 | 증상 | 조치 |
|------|------|------|
| WireMock 요청 기록 무제한 | 두 번째 전체 측정에서 erp-sync가 1,102 → **16 req/s**로 떨어짐(오류 0%). Mock ERP CPU 628%, 메모리 2.1GB(GC 반복) | `--max-request-journal-entries 1000`, Mock CPU 1개 고정 후 erp-sync만 재측정. 다른 API는 ERP를 호출하지 않아 영향 없음 |
| k6 지표 시계열 폭증 | URL에 ID가 들어가 시계열 20만 개 경고(k6 메모리·CPU 사용) | 요청에 `name` tag를 붙여 하나로 묶은 뒤 **전체 before를 다시 측정**(최초 측정은 `perf/results/before-v1`) |
| core-api 관리 포트에도 Security 적용 | `management.server.port`를 분리해도 `/actuator/metrics` 401 | 보안 설정을 바꾸지 않고 GC 로그, `kill -3`, Hikari debug 로그, JFR로 관측 |
| 재시작 직후 측정 | 첫 측정만 25% 낮음(order-create 1,254 vs 1,581) | 재시작 후 버리는 부하 1회를 추가 |

## 9. 재현 방법

```bash
cp .env.example .env                       # 비밀번호 변경
set -a; source .env; set +a                # perf/run.sh가 DB_*, KC_TEST_*_PASSWORD를 사용
docker compose -f docker-compose.yml -f perf/docker-compose.perf.yml up -d --build --wait
perf/run.sh before                         # 전체 20회 (약 17분) → perf/results/before/*.json
VU_LEVELS="10 100" perf/run.sh probe order-list   # 일부 API·VU만

# 실험용 변수 (perf/docker-compose.perf.yml)
SQL_LOG_LEVEL=debug docker compose -f docker-compose.yml -f perf/docker-compose.perf.yml up -d core-api  # SQL 로그 켜기
DB_POOL_SIZE=20 ...   # Connection Pool 크기
CACHE_TYPE=none ...   # 재고 캐시 끄기
# before 조건 재현: V5 이전 커밋의 이미지 + SQL_LOG_LEVEL=debug + JAVA_TOOL_OPTIONS에서 -XX:+UseG1GC 제거

# 진단
docker kill -s QUIT integration-hub-core-api && docker logs --since 3s integration-hub-core-api   # Thread Dump
docker cp integration-hub-core-api:/tmp/gc.log .                                                  # GC 로그
docker logs integration-hub-core-api 2>&1 | grep 'Pool stats'                                      # Hikari Pool 상태
```

| 파일 | 내용 |
|------|------|
| `perf/docker-compose.perf.yml` | 측정 전용 Override (자원 제한, Rate Limit 해제, GC·Pool 진단 로그, 실험 변수) |
| `perf/seed.sql` | 측정 전 데이터 초기화 (주문 10만, 연계 이력 5만) |
| `perf/k6/load.js` | k6 시나리오 (API별 요청, 기대 상태 코드, 결과 JSON) |
| `perf/run.sh` | 초기화 → 토큰 → Warm-up → 측정 반복 |
| `perf/results/` | 측정 원본 결과(JSON). before, after, 단계별(step-*), 실험(probe-*, repeat-*, serial-*, no-cache) |
