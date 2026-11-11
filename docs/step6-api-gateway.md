# STEP 6. Spring Cloud Gateway

모든 외부 요청이 `api-gateway`를 거쳐 core-api로 가도록 구성했습니다. 인증은 다음 단계에서 구현합니다.

```
Client ──▶ api-gateway :8080 ──▶ core-api ──▶ mock-erp, mock-mes
           (유일한 공개 포트)      (내부 전용)    (integration 네트워크 전용)
```

## 1. Spring Cloud 버전 선택

| 항목 | 값 | 근거 |
|------|----|------|
| Spring Boot | 3.5.16 (기존) | |
| Spring Cloud | **2025.0.3** (Northfields) | 공식 [Supported Versions](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions) 표에서 `2025.0.x ↔ Spring Boot 3.5.x`. 2025.0 라인의 최신 패치(Maven Central 기준) |
| Spring Cloud Gateway | 4.3.5 | 2025.0.3 BOM에 포함된 버전 |
| Starter | `spring-cloud-starter-gateway-server-webflux` | 4.3부터 바뀐 이름. 기존 `spring-cloud-starter-gateway`는 deprecated |

`start.spring.io`는 이제 Boot 4.x용 Release Train(2025.1)만 안내합니다. 2025.1은 **Boot 4.0 이상**용이므로 이 프로젝트(3.5)에는 맞지 않습니다. 설정 Prefix도 4.3부터 `spring.cloud.gateway.server.webflux.*`로 바뀌었습니다.

WebFlux(Netty) 방식 Gateway를 선택했습니다. 이미 있는 Redis로 공식 `RequestRateLimiter`(Redis Token Bucket)를 바로 쓸 수 있기 때문입니다.

## 2. 프로젝트 구조

루트 프로젝트(core-api)는 그대로 두고 `api-gateway/`를 Gradle 서브프로젝트로 추가했습니다(`settings.gradle`의 `include 'api-gateway'`).

| 명령 | 대상 |
|------|------|
| `./gradlew test` | 두 프로젝트 모두 |
| `./gradlew :bootRun` | core-api (8080) |
| `./gradlew :api-gateway:bootRun` | Gateway (로컬 8000 → `CORE_API_URL` 기본 `http://localhost:8080`) |

`./gradlew bootRun`처럼 프로젝트를 지정하지 않으면 두 앱을 모두 실행하려 합니다. 이때 첫 앱이 끝나지 않아 두 번째 앱은 시작되지 않습니다.

## 3. 요구사항별 구현

| # | 요구사항 | 구현 |
|---|----------|------|
| 1 | 호환 버전 | Spring Cloud 2025.0.3 BOM (`api-gateway/build.gradle`) |
| 2 | api-gateway 서비스 | `api-gateway/` (`ApiGatewayApplication`), `api-gateway/Dockerfile` |
| 3 | core-api 라우팅 | Route `core-api`: `Path=/api/orders,/api/orders/**,/api/integrations/**` → `CORE_API_URL` (STEP 8에서 `/api/inventory/**` 추가) |
| 4 | ERP/MES 노출 제한 | 3중 차단: ① 허용 목록 Route(그 외 경로 404) ② Mock에 호스트 포트 없음 ③ Mock은 `integration` 네트워크에만 있어 Gateway도 접근 불가 |
| 5 | 요청 ID 전파 | Gateway `RequestIdFilter` → `X-Request-Id` → core-api `RequestIdFilter`(MDC) → 로그, ERP/MES 요청 헤더 |
| 6 | 공통 요청/응답 로그 | Gateway `AccessLogFilter`: 요청 1줄 + 응답 1줄 (requestId, method, path, status, durationMs, client) |
| 7 | 기본 Rate Limiting | `RequestRateLimiter` + `RedisRateLimiter`, 클라이언트 IP별 초당 10개 충전, 최대 20개. 초과 시 429 |
| 8 | Gateway 장애 처리 | Connect Timeout 2s, Response Timeout 10s(초과 504), 연결 불가 503, 오류 본문의 requestId 통일 |
| 9 | Docker Compose 연동 | `api-gateway` 서비스(127.0.0.1:8080), core-api healthy 이후 시작, core-api와 Mock의 호스트 포트 제거 |

### 요청 ID 전파

```
Client ── X-Request-Id: abc (없거나 형식이 이상하면 Gateway가 UUID 생성)
  └▶ Gateway   로그: request  requestId=abc ... / response requestId=abc status=201
      └▶ core-api  MDC requestId=abc → 모든 로그 줄 "[abc]" (SQL 로그 포함)
          └▶ ERP, MES  요청 헤더 X-Request-Id: abc
Client ◀── 응답 헤더 X-Request-Id: abc, 오류 본문 "requestId":"abc"
```

- **형식 검증:** `[A-Za-z0-9-]{1,64}`인 값만 받아들입니다. 클라이언트가 보낸 값이 그대로 로그에 찍히므로, 줄바꿈 등을 넣어 로그 줄을 위조하거나 지나치게 긴 값을 보내는 것을 막습니다.
- **WebFilter로 구현한 이유:** Gateway의 `GlobalFilter`는 Route가 매칭된 요청에만 실행됩니다. 404(Route 없음)에도 요청 ID와 로그를 남기려면 WebFlux의 `WebFilter`로 만들어야 합니다.
- **beforeCommit에서 처리하는 이유:** 응답 헤더와 응답 로그는 응답이 커밋되기 직전에 기록합니다. 그래야 오류 처리기가 만든 응답(429, 503, 504)의 상태 코드도 남길 수 있습니다.

### Filter 구성과 실행 순서

| 순서 | Filter | 종류 | 적용 범위 |
|------|--------|------|-----------|
| 1 | `RequestIdFilter` | WebFilter | 모든 요청 |
| 2 | `AccessLogFilter` | WebFilter | 모든 요청 |
| 3 | `UpstreamUnavailableFilter` | GlobalFilter | Route 매칭 요청. 연결 실패 → 503 |
| 4 | `PreserveHostHeader` | GatewayFilter (default) | 클라이언트 Host 전달 |
| 5 | `RequestRateLimiter` | GatewayFilter (default) | Token 부족 시 429, 이후 Filter 실행 안 함 |
| 6 | `NettyRoutingFilter` 등 | 내장 GlobalFilter | core-api로 전달 |

### 구현 중 발견한 문제

| 문제 | 원인 | 조치 |
|------|------|------|
| core-api가 꺼지면 **500** | Gateway 기본 동작은 연결 오류를 500으로 처리 | `UpstreamUnavailableFilter`가 `ConnectException`, `UnknownHostException`을 **503**으로 변환 |
| `Location: http://core-api:8080/api/orders/1` | Gateway가 Host 헤더를 core-api 주소로 바꿔 전달해서, core-api가 Host로 만든 Location에 **내부 주소가 노출됨** | `PreserveHostHeader` → `http://localhost:8080/api/orders/1` |
| 오류 본문의 requestId가 헤더와 다름 | Spring Boot 기본 ErrorAttributes는 내부 요청 ID(`7876dc30-95`)를 씀 | `ErrorResponseConfig`에서 `X-Request-Id` 값으로 교체 |
| 기본 KeyResolver | `PrincipalNameKeyResolver`는 인증이 없으면 키가 비어 403 | 클라이언트 IP `KeyResolver` 지정 |

각 문제를 먼저 실패하는 테스트로 재현한 뒤 수정했습니다.

## 4. 오류 응답

| 상황 | Status | 본문 예 |
|------|--------|---------|
| Route 없음 (ERP/MES, `/actuator/env` 등) | 404 | `{"path":"/erp/api/orders","status":404,"error":"Not Found","requestId":"..."}` |
| Rate Limit 초과 | 429 | 본문 없음. `X-RateLimit-Remaining: 0`, `X-RateLimit-Burst-Capacity: 20`, `X-RateLimit-Replenish-Rate: 10` |
| core-api 연결 불가 | 503 | `{"path":"/api/orders","status":503,"error":"Service Unavailable","requestId":"e2e-down-2"}` |
| core-api 응답이 10초 초과 | 504 | `{"status":504,"error":"Gateway Timeout",...}` |
| core-api가 반환한 오류 (400, 404, 409) | 그대로 | core-api의 ProblemDetail을 변환 없이 전달 |

## 5. 검증 결과

### 자동 테스트

`./gradlew test`로 **84개**(core-api 73개, api-gateway 11개)가 모두 통과했습니다.

| 테스트 | 검증 내용 |
|--------|-----------|
| `GatewayRoutingTest` (9개) | 실제 Netty Gateway → WireMock(core-api 스텁)으로 라우팅, 요청 ID 생성·유지·교체(3종), 본문 전달과 Location Host, 내부 경로 5종 404, Response Timeout 504, Health(redis UP) |
| `GatewayRateLimitTest` | 버킷 2개로 줄여 3번째 요청이 429인지 |
| `GatewayCoreApiDownTest` | core-api 연결 거부 시 503, 본문 requestId = 헤더 |
| `RequestIdFilterTest` (core-api) | MDC 설정과 정리, 잘못된 값 교체 |
| `IntegrationApiIntegrationTest#propagatesRequestIdToErpAndMes` | ERP, MES WireMock 요청 기록에서 같은 `X-Request-Id` 확인 |

### Docker Compose: Gateway를 통한 기존 주문 API

`docker compose up -d --build` 후 6개 서비스가 모두 healthy 상태가 됐습니다. 호스트에 열린 API 포트는 `127.0.0.1:8080`(Gateway)뿐입니다.

| 요청 (모두 `localhost:8080` = Gateway) | 결과 |
|------------------------------------------|------|
| `POST /api/orders` | 201, `Location: http://localhost:8080/api/orders/3`, `X-Request-Id`, `X-RateLimit-Remaining: 19` |
| `GET /api/orders?status=CREATED&sort=id,desc` | 200, `X-Total-Count: 1` |
| `GET /api/orders/1` / `GET /api/orders/999` | 200 / 404 ProblemDetail (core-api 응답 그대로) |
| `PATCH /api/orders/1/status` `{"status":"PAID"}` | 200 `PAID` |
| `POST /api/orders` 빈 값 | 400 (core-api 검증 오류 그대로) |
| `DELETE /api/orders/2` | 204 |
| `POST /api/integrations/orders/1/sync`, `GET /api/integrations/history?orderId=1` | 201 `SUCCESS`, 200 |

| 노출 제한·장애 시나리오 | 결과 |
|-------------------------|------|
| `/actuator/env`, `/actuator/metrics`, `/erp/api/orders`, `/api/production/availability` | 모두 404 |
| 호스트에서 `localhost:8081`, `8082` 접속 | 연결 거부 (포트 없음) |
| Gateway 컨테이너에서 `curl http://mock-erp:8080` | 이름 해석 실패 (네트워크 분리). core-api에서는 200 |
| `--path-as-is`로 `/api/orders/../actuator/env` 등 4종 | Gateway가 core-api로 전달하지만 core-api가 `..`를 정규화하지 않아 404/400. 내부 Endpoint에는 도달하지 않음 |
| 동시 40건 요청 | 200 20건, 429 20건 (버킷 20) |
| `docker compose stop core-api` | 503 + `requestId`, Gateway Health는 `UP` 유지. `start` 후 바로 200 |
| `X-Request-Id: trace-demo-1`로 연계 실행 | Gateway 로그 2줄, core-api 로그 `[trace-demo-1]`, ERP 요청 기록 1건 |

```
# docker compose logs api-gateway
request  requestId=trace-demo-1 method=POST path=/api/integrations/orders/1/sync client=172.18.0.1
response requestId=trace-demo-1 method=POST path=/api/integrations/orders/1/sync status=201 CREATED durationMs=59
# docker compose logs core-api
2026-11-11T08:33:35.863Z DEBUG 1 --- [enterprise-integration-hub] [nio-8080-exec-3] [trace-demo-1] org.hibernate.SQL : ...
```

## 6. 학습 정리

### Reverse Proxy

클라이언트는 Gateway 주소만 알고, Gateway가 뒤쪽 서버에 대신 요청한 뒤 응답을 돌려줍니다. 반대로 Forward Proxy는 클라이언트 쪽에서 외부로 나가는 요청을 대신합니다.

- **내부 구조 은닉:** core-api의 주소, 포트, 개수가 바뀌어도 클라이언트는 그대로입니다.
- **공통 관문:** 로그, 요청 ID, Rate Limit, (다음 단계) 인증을 한곳에서 처리합니다.
- **주의할 점:** Host, Location, X-Forwarded-* 같은 헤더를 신경 써야 합니다. 이번에 내부 주소가 Location으로 노출된 문제가 대표적인 예입니다.

### API Routing

Route는 **ID + 목적지 URI + Predicate(조건) + Filter(가공)**로 구성됩니다.

```yaml
routes:
  - id: core-api
    uri: http://core-api:8080
    predicates:
      - Path=/api/orders,/api/orders/**,/api/integrations/**
```

- **Predicate 종류:** Path, Method, Header, Host, Query, 시간 등을 조합할 수 있습니다.
- **허용 목록 방식:** 공개할 경로만 나열하면 새 내부 API가 추가되어도 자동으로 외부에 노출되지 않습니다. 반대로 `/**`를 전부 넘기면 core-api `/actuator`까지 외부로 열립니다.

### Filter

| 구분 | 실행 범위 | 이 프로젝트 |
|------|-----------|-------------|
| WebFilter (WebFlux) | 모든 요청 (Route 없는 404 포함) | 요청 ID, 접근 로그 |
| GlobalFilter (Gateway) | Route가 매칭된 모든 요청 | 연결 실패 → 503 |
| GatewayFilter (Route별 / default-filters) | 지정한 Route | PreserveHostHeader, RequestRateLimiter |

- **Pre / Post 처리:** Filter는 `chain.filter(exchange)`를 호출하기 전에 요청을 가공(Pre)하고, 반환된 Mono에 연산을 붙여 응답을 가공(Post)합니다.
- **실행 순서:** `@Order`나 `Ordered`로 정하고, 숫자가 작을수록 먼저 실행됩니다.
- **중단:** Rate Limiter처럼 응답을 바로 끝내면 이후 Filter와 라우팅은 실행되지 않습니다.

### Load Balancing 개념

여러 인스턴스에 요청을 나눠 보내 처리량과 가용성을 높입니다.

| 방식 | 설명 |
|------|------|
| Round Robin | 순서대로 돌아가며 보냄 (Spring Cloud LoadBalancer 기본) |
| Random / Weighted | 무작위 또는 서버 성능에 따른 비중 |
| Least Connections | 연결이 가장 적은 서버 |
| IP Hash / Sticky | 같은 클라이언트를 같은 서버로 보냄 (세션 유지) |

Spring Cloud Gateway에서는 `uri: lb://core-api`처럼 쓰면 Spring Cloud LoadBalancer가 인스턴스 목록(Eureka, Consul, Kubernetes, 정적 설정) 중 하나를 고릅니다. 이번 구성은 core-api가 1개라 `http://core-api:8080`을 직접 지정했습니다. Docker Compose에서 `--scale core-api=2`를 하면 Docker DNS가 여러 IP를 돌려주지만, 연결을 재사용하므로 균등하게 분산된다는 보장은 없습니다. Health 기반으로 장애 인스턴스를 빼는 기능도 없습니다. Rate Limit 버킷은 Redis에 있으므로 Gateway를 여러 대 띄워도 한도가 공유됩니다.

### API Gateway와 EAI 차이

| | API Gateway | EAI (STEP 5의 core-api 연계) |
|--|-------------|------------------------------|
| 위치 | 외부 클라이언트와 내부 서비스 사이 (North-South) | 내부 시스템끼리 (East-West) |
| 관심사 | 진입 통제: 라우팅, 인증, Rate Limit, 로그, 요청 ID | 업무 통합: 데이터 변환, 오케스트레이션(ERP → MES), 재처리, 이력 |
| 업무 로직 | 없음 (요청·응답 내용을 해석하지 않음) | 있음 (주문 → ERP 규격 변환, MES 결과 해석, 성공·실패 판단) |
| 상태 | 거의 없음 (Rate Limit 카운터 정도) | 연계 이력, 실패 이벤트, 재처리 대상 |
| 실패 처리 | 503, 504, 429로 빠르게 응답 | FAILED 기록, 재시도, 보상 |
| 이 프로젝트 | `api-gateway` | `IntegrationService`, `ErpClient`, `MesClient` |

Gateway에 업무 로직(데이터 변환, 여러 시스템 순차 호출)을 넣기 시작하면 Gateway가 비대해지고 모든 서비스의 배포에 묶이게 됩니다. 이번 구성에서도 Gateway는 ERP/MES를 알지 못하고, 연계는 core-api가 담당합니다.

## 7. 한계와 다음 단계 후보

- **인증 없음:** 다음 단계에서 구현합니다. 인증 후에는 Rate Limit 기준을 IP에서 사용자로 바꿀 수 있습니다.
- **Rate Limit 기준 IP:** Docker에서는 호스트의 모든 요청이 같은 브리지 IP(`172.18.0.1`)로 보여 하나의 버킷을 공유합니다. 앞단에 Load Balancer를 두면 신뢰할 Proxy를 지정해 `X-Forwarded-For`를 사용해야 합니다(`ClientIp`의 `shortcut:` 주석).
- **Redis 장애 시 Fail-open:** Redis가 장애나면 `RedisRateLimiter`는 요청을 허용합니다. 서비스는 계속되지만 Rate Limit은 동작하지 않습니다.
- **재시도·Circuit Breaker 없음:** Retry 필터(GET만), Resilience4j Circuit Breaker와 fallback을 다음 후보로 둡니다.
- **`..` 경로:** Gateway가 `..`가 든 경로를 거르지 않고 core-api로 전달합니다. 지금은 core-api가 404로 막지만, 뒤쪽 서버의 경로 정규화 방식에 의존하는 상태입니다.
- **Health 표시:** Gateway Health에 Discovery Client가 `UNKNOWN`으로 표시됩니다(사용하지 않음). 전체 상태(`UP`)에는 영향이 없습니다.
- **Mock 접근:** Compose의 Mock은 호스트에서 접근할 수 없습니다. 로컬 개발 때는 [getting-started.md](getting-started.md)의 `docker run` 명령으로 띄웁니다.
