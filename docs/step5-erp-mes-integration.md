# STEP 5. ERP / MES 연계

core-api가 등록된 주문을 ERP로 전송하고 MES에서 생산 가능 여부를 확인합니다. 결과(성공/실패)는 `integration_history`에 기록합니다. ERP와 MES는 WireMock 컨테이너로 흉내 냅니다.

## 구성

```
                       ┌──────────── backend network ────────────┐
 사용자 ──HTTP──▶ core-api ──RestClient──▶ mock-erp  POST /api/orders
                   │  │   (Timeout 2s/3s) ▶ mock-mes  POST /api/production/availability
                   │  └──JPA──▶ postgres (orders, integration_history)
                   └────────▶ redis
```

| 서비스 | 역할 | 포트 (호스트) |
|--------|------|---------------|
| core-api | 주문 API, 연계 실행, 이력 저장 | 8080 |
| mock-erp | 주문 접수, `erpOrderNo` 발급 | 8081 |
| mock-mes | 재고·생산 가능 여부 응답 | 8082 |

## 처리 흐름 (`POST /api/integrations/orders/{id}/sync`)

```
1. 주문 조회        OrderService.findById      짧은 readOnly 트랜잭션 (없으면 404)
2. ERP 주문 전송     ErpClient.sendOrder         트랜잭션 없음 ─┐ 실패 시 FAILED(ERP) 기록 후 종료
3. MES 생산 가능 확인 MesClient.checkAvailability 트랜잭션 없음 ─┘ 실패·생산 불가 시 FAILED(MES) 기록
4. 이력 저장        IntegrationHistoryRepository.save  짧은 트랜잭션 (SUCCESS 또는 FAILED)
```

- 주문 등록(STEP 1, 2)은 기존 `POST /api/orders`를 그대로 씁니다. 연계는 Sync API를 호출할 때만 실행합니다. 그래서 기존 주문 API 계약과 응답 시간은 변하지 않습니다.
- `IntegrationService`에는 `@Transactional`이 없습니다. 외부 시스템을 기다리는 동안(최대 Read Timeout) DB 커넥션과 Lock을 잡고 있지 않기 위해서입니다. 테스트가 매 호출마다 `TransactionSynchronizationManager.isActualTransactionActive() == false`를 확인합니다.
- ERP가 성공하고 MES가 실패하면 `erpOrderNo`를 남깁니다. ERP에는 이미 주문이 들어갔다는 뜻이므로 재처리나 취소(보상)에 필요합니다.

## 구현 요소

| 요구사항 | 구현 |
|----------|------|
| Spring REST Client | `client/IntegrationClientConfig`: `erpRestClient`, `mesRestClient` Bean. `ErpClient`, `MesClient`가 호출 |
| 요청/응답 DTO | `ErpOrderRequest/Response`, `MesAvailabilityRequest/Response`. 외부 규격용이라 API DTO(`dto/`)와 분리 |
| Connection Timeout | `integration.{erp,mes}.connect-timeout` (기본 2s). `SimpleClientHttpRequestFactory#setConnectTimeout` |
| Read Timeout | `integration.{erp,mes}.read-timeout` (기본 3s). `SimpleClientHttpRequestFactory#setReadTimeout` |
| IntegrationHistory | `domain/IntegrationHistory` Entity, Flyway `V3__create_integration_history.sql` |
| IntegrationStatus | `SUCCESS`, `FAILED` |
| 실패 이벤트 저장 | 실패 시 `failedSystem`(ERP/MES), `errorType`, `errorMessage`(최대 500자)를 같은 행에 저장하고 WARN 로그 출력 |
| 연계 결과 조회 API | `GET /api/integrations/history`, `GET /api/integrations/history/{id}` |
| Mock 장애 시나리오 | `mock/erp/mappings/erp-orders.json`, `mock/mes/mappings/mes-availability.json` |

### 오류 유형 (`IntegrationErrorType`)

| 값 | 판단 기준 | 예 |
|----|-----------|----|
| `TIMEOUT` | 근본 원인이 `SocketTimeoutException` | `Read timed out`, `Connect timed out` |
| `CONNECTION_FAILED` | 근본 원인이 `SocketException`, `UnknownHostException` | `Connection reset`, 연결 거부 |
| `SERVER_ERROR` | 응답 5xx | ERP 500, MES 503 |
| `CLIENT_ERROR` | 응답 4xx | 요청 규격 불일치 |
| `INVALID_RESPONSE` | 그 밖의 `RestClientException` | 응답 본문 없음, JSON 해석 실패 |
| `REJECTED` | 통신은 성공, 업무적으로 거절 | MES `producible: false` |

예외 **타입**이 아니라 **근본 원인**으로 분류합니다. Timeout은 응답 본문을 읽는 도중에도 발생할 수 있습니다. 이때 RestClient는 `ResourceAccessException`이 아니라 `RestClientException("Error while extracting response...")`으로 감싸서 던집니다. 실제로 처음 구현에서는 Timeout이 `INVALID_RESPONSE`로 잘못 분류되어 테스트가 실패했습니다.

## Mock 장애 시나리오

주문의 `customerId`로 시나리오를 고릅니다. 그 밖의 값은 정상 응답합니다.

| customerId | 대상 | Mock 동작 | 기록 결과 |
|------------|------|-----------|-----------|
| (그 외) | - | ERP `ERP-{orderId}` 발급, MES `producible: true` | `SUCCESS` |
| `erp-error` | ERP | 500 응답 | `FAILED` / ERP / `SERVER_ERROR` |
| `erp-timeout` | ERP | 5초 뒤 응답 | `FAILED` / ERP / `TIMEOUT` (3초에서 끊음) |
| `erp-reset` | ERP | 연결 강제 종료 | `FAILED` / ERP / `CONNECTION_FAILED` |
| `mes-error` | MES | 503 응답 | `FAILED` / MES / `SERVER_ERROR`, `erpOrderNo` 있음 |
| `mes-timeout` | MES | 5초 뒤 응답 | `FAILED` / MES / `TIMEOUT`, `erpOrderNo` 있음 |
| `mes-reject` | MES | `producible: false` | `FAILED` / MES / `REJECTED`, `erpOrderNo` 있음 |
| (컨테이너 중지) | ERP | `docker compose stop mock-erp` | `FAILED` / ERP / `TIMEOUT` (`Connect timed out`, 2초) |

## API

| Method | URI | 설명 | 응답 |
|--------|-----|------|------|
| POST | `/api/integrations/orders/{id}/sync` | 연계 실행, 이력 1건 생성 | 201 + `Location` (성공·실패 모두), 주문 없음 404 |
| GET | `/api/integrations/history` | 이력 목록. `orderId`, `status`, `page`, `size`, `sort` (기본 `id,desc`) | 200 JSON 배열 + `X-Total-Count` |
| GET | `/api/integrations/history/{id}` | 이력 단건 | 200, 없음 404 |

외부 시스템이 실패해도 Sync API는 **201**을 반환합니다. 연계 요청 자체는 처리되었고 실패 결과가 이력으로 생성되었기 때문입니다. 성공 여부는 본문의 `status`로 판단합니다. 외부 장애를 core-api의 500으로 바꾸지 않는 것이 장애 전파 차단의 한 방법입니다.

### curl 예시

```bash
# 주문 등록 → 연계 성공
curl -s -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"customer-1","items":[{"productName":"keyboard","unitPrice":50000,"quantity":1}]}'
curl -i -X POST localhost:8080/api/integrations/orders/1/sync
```
```
HTTP/1.1 201
Location: http://localhost:8080/api/integrations/history/1

{"id":1,"orderId":1,"status":"SUCCESS","erpOrderNo":"ERP-1","failedSystem":null,"errorType":null,
 "errorMessage":null,"requestedAt":"2026-11-11T08:16:42.676157","completedAt":"2026-11-11T08:16:42.789609","durationMs":113}
```

```bash
# ERP Timeout (customerId=erp-timeout인 주문 3)
curl -s -X POST localhost:8080/api/integrations/orders/3/sync
```
```json
{"id":3,"orderId":3,"status":"FAILED","erpOrderNo":null,"failedSystem":"ERP","errorType":"TIMEOUT",
 "errorMessage":"java.net.SocketTimeoutException: Read timed out","requestedAt":"2026-11-11T08:16:42.872804",
 "completedAt":"2026-11-11T08:16:45.887833","durationMs":3015}
```

```bash
# MES 생산 불가 (customerId=mes-reject인 주문 7): ERP 주문 번호는 남는다
curl -s -X POST localhost:8080/api/integrations/orders/7/sync
```
```json
{"id":7,"orderId":7,"status":"FAILED","erpOrderNo":"ERP-7","failedSystem":"MES","errorType":"REJECTED",
 "errorMessage":"Production line under maintenance","durationMs":10, "...": "..."}
```

```bash
# 이력 조회
curl -i 'localhost:8080/api/integrations/history?status=FAILED&size=2'   # X-Total-Count: 7
curl -s 'localhost:8080/api/integrations/history?orderId=1'
curl -s localhost:8080/api/integrations/history/999
```
```json
{"type":"about:blank","title":"Not Found","status":404,"detail":"Integration history not found: 999","instance":"/api/integrations/history/999"}
```

Mock을 직접 호출할 수도 있습니다: `curl -s -X POST localhost:8081/api/orders -H 'Content-Type: application/json' -d '{"orderId":1}'`
(STEP 6부터 Compose의 Mock은 호스트 포트를 열지 않습니다. 직접 호출하려면 [getting-started.md](getting-started.md)처럼 Mock을 따로 띄웁니다.)

## 검증 결과

**자동 테스트** (`./gradlew test`, 70개 통과). `IntegrationApiIntegrationTest`는 WireMock 컨테이너에 실제 HTTP로 요청합니다. Compose와 같은 매핑 파일을 사용하고, Read Timeout은 1초로 줄였습니다.

| 테스트 | 검증 내용 |
|--------|-----------|
| `syncSucceedsWhenErpAndMesSucceed` | 201, `Location`, `SUCCESS`, `erpOrderNo=ERP-{id}`, 단건 조회 |
| `syncRecordsFailureEvent` ×4 | erp-error, erp-reset, mes-error, mes-reject → 실패 시스템, 오류 유형, ERP 번호 유무 |
| `syncRecordsTimeoutWithoutWaitingForSlowSystem` ×2 | erp-timeout, mes-timeout → `TIMEOUT`, 1000ms ≤ `durationMs` < 5000ms |
| `syncUnknownOrderReturns404`, `unknownHistoryReturns404` | 404 ProblemDetail |
| `historyCanBeFilteredByOrderAndStatus` | `orderId`, `status` 필터와 `X-Total-Count` |
| (모든 테스트 공통) | ERP·MES 호출 시점에 DB 트랜잭션 없음 (`@MockitoSpyBean`) |

**Docker Compose 수동 검증**

- `docker compose up -d --build`로 5개 서비스가 모두 healthy 상태가 됐습니다.
- 위 시나리오 표의 8가지 결과를 확인했습니다. Timeout 2건은 약 3015ms에서 끊겼고, ERP 컨테이너를 중지한 경우는 2012ms(Connect Timeout)에서 끊겼습니다.
- ERP를 중지한 동안에도 `/actuator/health`는 `UP`이었고 주문 API도 정상이었습니다. 외부 장애가 core-api로 전파되지 않았습니다.
- `psql`로 `integration_history` 8행을 확인했습니다. `docker compose logs core-api`에서 `Integration failed: ...` WARN 로그도 확인했습니다.

## 학습 정리

### EAI와 MCI

| | EAI (Enterprise Application Integration) | MCI (Multi-Channel Integration) |
|--|------------------------------------------|---------------------------------|
| 연결 대상 | 내부 업무 시스템끼리 (ERP ↔ MES ↔ SCM ↔ WMS) | 외부 채널 → 내부 시스템 (웹, 모바일, 창구, 대외기관) |
| 위치 | 기업 시스템 사이의 허브 | 채널과 업무 시스템 사이의 관문 |
| 주요 기능 | 데이터 변환·매핑, 라우팅, 프로토콜 변환, 비동기 전달, 재처리 | 채널별 전문(電文) 표준화, 인증, 세션, 거래 로그, 흐름 제어 |
| 이 프로젝트 | core-api가 ERP·MES를 연결하는 작은 EAI 허브 역할 | 사용자 요청을 받는 REST API가 단일 채널 진입점 역할 |

EAI가 없으면 시스템마다 서로 직접 연결(Point-to-Point)하게 되어 연결 수가 N×(N-1)/2로 늘어납니다. 허브를 두면 각 시스템은 허브와만 연결하면 됩니다. 이 프로젝트의 `ErpOrderRequest`처럼 **외부 규격 DTO를 내부 모델과 분리**하는 것도 EAI의 데이터 변환 역할에 해당합니다. ERP 규격이 바뀌어도 `Order` Entity나 주문 API는 바뀌지 않습니다.

### REST 기반 시스템 연계

- **장점:** HTTP와 JSON이라 언어나 플랫폼에 상관없이 연결되고, curl로 바로 확인할 수 있습니다.
- **고려할 점:**
  1. 규격(URI, 필드, 오류 응답)을 문서로 합의해야 합니다.
  2. Timeout을 반드시 설정해야 합니다.
  3. 같은 요청이 두 번 갈 수 있으므로 멱등성(같은 `orderId`는 한 번만 접수)을 고려해야 합니다.
  4. 4xx(내 요청이 잘못됨)와 5xx(상대 시스템 문제)는 재시도 여부가 다르므로 구분해서 처리합니다.

### 동기와 비동기 통신

| | 동기 (이번 MVP) | 비동기 (메시지 큐, 이벤트) |
|--|-----------------|---------------------------|
| 방식 | 요청 후 응답을 기다림 (HTTP) | 메시지를 보내고 바로 반환. 결과는 나중에 처리 |
| 결과 확인 | 즉시 (`SUCCESS`/`FAILED` 응답) | 상태 조회나 콜백, 이벤트로 확인 |
| 응답 시간 | 외부 시스템 응답 시간이 그대로 더해짐 (Timeout 때 최대 3초 이상) | 일정하고 짧음 |
| 외부 장애 시 | 요청이 바로 실패함 | 큐에 쌓였다가 복구 후 처리됨 |
| 복잡도 | 낮음 | 브로커, 재시도, 중복 처리, 순서 보장 등을 고려해야 함 |

### 장애 전파

외부 시스템이 느려지면 core-api의 요청 스레드가 응답을 기다리며 묶입니다. 요청이 계속 들어오면 Tomcat 스레드와 DB 커넥션이 고갈되어 **ERP와 관계없는 주문 조회 API까지 멈추게 됩니다**. 이번 구현에서 적용한 방어 수단은 다음과 같습니다.

1. **Timeout:** 무한정 기다리지 않고 2초, 3초에서 포기합니다.
2. **외부 호출 중 트랜잭션 없음:** 기다리는 동안 DB 커넥션을 잡지 않으므로 커넥션 풀이 고갈되지 않습니다.
3. **예외를 결과로 변환:** 외부 오류를 500으로 던지지 않고 `FAILED` 이력으로 남깁니다.
4. **`depends_on` 제외:** ERP·MES가 없어도 core-api는 시작되고 Health도 `UP`입니다.

다음 단계에서 고려할 수 있는 수단은 Circuit Breaker(연속 실패 시 호출 자체를 차단), Bulkhead(시스템별 스레드 풀 분리), Retry with Backoff, 비동기 메시지 큐입니다.

### API Timeout

| 종류 | 의미 | 발생 상황 | 이번 검증 |
|------|------|-----------|-----------|
| Connection Timeout | TCP 연결이 수립되기까지 기다리는 시간 | 서버 다운, 방화벽 차단, 잘못된 IP | ERP 컨테이너 중지 → `Connect timed out` 2012ms |
| Read Timeout | 연결 후 응답 데이터를 기다리는 시간 | 서버 처리 지연, DB Lock 대기 | Mock 5초 지연 → `Read timed out` 3015ms |

- Timeout을 설정하지 않으면 JDK 기본값은 무한 대기(0)입니다.
- Timeout 값은 상대 시스템의 정상 응답 시간(p99)보다 약간 길게 잡습니다. 동시에 사용자 응답 목표 시간보다는 짧아야 합니다.
- Read Timeout이 발생해도 **상대 시스템은 요청을 이미 처리했을 수 있습니다.** ERP가 주문을 받았는데 core-api는 `TIMEOUT`으로 기록하는 경우가 생깁니다. 그래서 재처리할 때 ERP 쪽 멱등성이 필요합니다.

## 한계와 다음 단계 후보

- **재시도 없음:** 재처리는 Sync API를 다시 호출해야 하며, 호출할 때마다 이력이 새로 생깁니다.
- **동시 호출:** 같은 주문을 동시에 Sync하면 ERP로 두 번 전송될 수 있습니다. 멱등 키나 중복 방지 처리가 없습니다.
- **ERP 보상 처리 없음:** MES 실패 시 ERP에 들어간 주문을 취소하는 Saga나 보상 트랜잭션이 없습니다.
- **주문 상태 무관:** 연계 결과가 주문 상태를 바꾸지 않습니다. `CANCELLED` 주문도 연계할 수 있습니다.
- **시간대 혼재:** `requestedAt`, `completedAt`은 STEP 4에서 확인한 시간대 문제(Docker UTC, 로컬 KST)를 그대로 가집니다.
- **장애 시나리오 선택 방식:** `customerId`로 고르는 방식은 학습용 Mock 규칙입니다. 실제 ERP·MES 규격이 아닙니다.
