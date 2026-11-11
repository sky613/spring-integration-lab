# STEP 7. Keycloak + Spring Security 인증·인가

Keycloak(IAM)이 로그인과 토큰 발급을 맡고, core-api는 OAuth2 Resource Server로서 JWT를 검증하고 역할별로 API 접근을 제어합니다. core-api와 Gateway는 사용자 비밀번호를 받지도 저장하지도 않습니다.

```
            ① 로그인 (Authorization Code + PKCE)
 Client ─────────────────────────────────────▶ Keycloak :8180  (개발용 IAM)
   │   ◀──────────── ② Access Token (JWT) ─────┘    │
   │                                                │ ④ JWKS(공개키)
   │ ③ Authorization: Bearer <JWT>                  ▼
   └──▶ api-gateway :8080 ──(헤더 그대로)──▶ core-api: 서명·만료·iss·aud 검증 → 역할 확인 → 200 / 401 / 403
```

## 요구사항별 구현

| # | 요구사항 | 구현 |
|---|----------|------|
| 1 | Keycloak Docker 서비스 | `keycloak` (26.4.5, `start-dev --import-realm`, 127.0.0.1:8180) |
| 2 | Realm 구성 | `keycloak/realm-integration-hub.dev.json`: `integration-hub`, 브루트포스 방지, 가입·비밀번호 재설정 비활성, Access Token 5분 |
| 3 | Client 등록 | `integration-hub-web`: **Public Client**, Authorization Code만 허용(Implicit·Password·Client Credentials 비활성), **PKCE S256 필수**, redirect URI 1개 고정, `aud`에 `integration-hub-api` 추가 |
| 4 | 테스트 사용자 | `user1`(USER), `admin1`(USER, ADMIN). 비밀번호는 파일에 없고 `.env`의 `KC_TEST_*_PASSWORD`가 기동 시 주입됨 |
| 5 | ADMIN / USER 역할 | Realm Role `USER`, `ADMIN` |
| 6 | JWT 검증 | JWKS 서명(RS256), `exp`/`nbf`, `iss`, `aud=integration-hub-api` (Spring Boot `spring.security.oauth2.resourceserver.jwt.*`) |
| 7 | Resource Server | `security/SecurityConfig`: Stateless, CSRF 비활성(쿠키 미사용), `realm_access.roles` → `ROLE_*` 변환 |
| 8 | API별 역할 권한 | URL 허용 목록 + `hasRole`, 목록에 없으면 `denyAll` (아래 표) |
| 9 | 401 / 403 처리 | `SecurityProblemHandler`: ProblemDetail JSON + 표준 `WWW-Authenticate` 헤더 + INFO 로그 |
| 10 | 인증 실패 통합 테스트 | `SecurityIntegrationTest` 18건: 실제 서명한 JWT로 운영과 같은 검증 경로 |

### 권한 표

| API | USER | ADMIN | 비고 |
|-----|:----:|:-----:|------|
| `GET /api/orders`, `GET /api/orders/{id}` | ✅ | (USER 역할 필요) | 주문 조회 |
| `POST /api/orders` | ✅ | (USER 역할 필요) | 주문 등록 |
| `PATCH /api/orders/{id}/status`, `DELETE /api/orders/{id}` | ❌ 403 | ✅ | 명세에 없어 ADMIN으로 결정 |
| `POST /api/integrations/orders/{id}/sync` | ❌ 403 | ✅ | 연계 실행 |
| `GET /api/integrations/history`, `/history/{id}` | ❌ 403 | ✅ | 연계 이력 조회 |
| `POST /api/integrations/history/{id}/retry` (신규) | ❌ 403 | ✅ | 실패 재처리. FAILED가 아니면 409 |
| `/actuator/health/**`, `/actuator/info` | 공개 | 공개 | Docker Healthcheck용 |
| 그 외 모든 경로 | ❌ | ❌ | `denyAll` |

역할은 자동으로 상속되지 않습니다. `admin1`이 주문 API를 쓸 수 있는 것은 Keycloak에서 USER 역할도 함께 부여받았기 때문입니다. 누가 무엇을 할 수 있는지는 IAM(Keycloak)에서 관리합니다.

### 실패 재처리 (`POST /api/integrations/history/{id}/retry`)

FAILED 이력의 주문을 다시 연계하고 **새 이력**을 만듭니다. 기존 FAILED 이력은 그대로 남아 재처리 기록이 됩니다. 재처리를 확인할 수 있도록 Mock에 `erp-flaky` 시나리오를 추가했습니다(첫 호출만 503, 이후 성공, WireMock Scenario).

### 401 / 403 응답

```
HTTP/1.1 401
WWW-Authenticate: Bearer error="invalid_token", error_description="...", error_uri="https://tools.ietf.org/html/rfc6750#section-3.1"
Content-Type: application/problem+json
{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Invalid or expired access token","instance":"/api/orders"}

HTTP/1.1 403
WWW-Authenticate: Bearer error="insufficient_scope", ...
{"type":"about:blank","title":"Forbidden","status":403,"detail":"You do not have permission to access this resource","instance":"/api/integrations/history"}
```

| 상황 | Status | `detail` |
|------|--------|----------|
| Authorization 헤더 없음 | 401 | `Authentication required` (`WWW-Authenticate: Bearer`) |
| 형식 오류, 서명 위조, 만료, issuer·audience 불일치 | 401 | `Invalid or expired access token` |
| 유효한 토큰이지만 역할 없음 | 403 | `You do not have permission to access this resource` |

- 본문에는 검증 실패의 내부 사유(만료 시각, 서명 오류 등)를 넣지 않습니다. 표준 `WWW-Authenticate` 헤더와 서버 로그에만 남깁니다.
- 로그에는 요청 ID가 함께 찍힙니다. 예: `[51669f7d-...] SecurityProblemHandler : 403 GET /api/integrations/history: user=user1`

## 로그인: Authorization Code + PKCE

`scripts/get-token.sh <username>`이 브라우저 로그인과 같은 단계를 curl로 수행합니다(개발 도구).

```bash
TOKEN=$(scripts/get-token.sh user1)     # 비밀번호 프롬프트 (입력이 화면에 보이지 않음)
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/orders
```

| 단계 | 요청 | 핵심 |
|------|------|------|
| 1 | `GET /auth?response_type=code&client_id&redirect_uri&state&code_challenge&code_challenge_method=S256` | 무작위 `code_verifier`를 만들고 해시(`code_challenge`)만 보냄 |
| 2 | Keycloak 로그인 폼에 사용자 이름·비밀번호 제출 | 비밀번호는 **Keycloak에만** 전달됨. 응답: `redirect_uri?code=...&state=...` |
| 3 | `POST /token` (`code` + `code_verifier`) | Keycloak이 `SHA256(code_verifier) == code_challenge` 확인 후 토큰 발급 |

- **PKCE가 필요한 이유:** Public Client(SPA, 모바일, CLI)는 Client Secret을 안전하게 보관할 수 없습니다. 그래서 요청마다 일회용 비밀값(`code_verifier`)을 만듭니다. 중간에 `code`를 가로채도 이 값이 없으면 토큰으로 바꿀 수 없습니다.
- **`state`:** 다른 요청의 응답이 섞이지 않았는지 확인하는 값입니다(CSRF 방지). 스크립트도 이 값을 확인합니다.
- **Password Grant 비활성화:** 앱이 사용자 비밀번호를 직접 받아 Keycloak에 전달하는 Password Grant(Direct Access Grants)는 껐습니다. OAuth 2.1에서 제거된 방식이고, "앱이 비밀번호를 다루지 않는다"는 원칙에 어긋나기 때문입니다.

## 개발 설정과 운영 설정 분리

| 항목 | 이 저장소 (개발 전용) | 운영에서 해야 할 것 |
|------|----------------------|----------------------|
| Keycloak 실행 | `start-dev` (HTTP, 캐시 비활성, 내장 dev-file DB) | `kc.sh build` 후 `start`, HTTPS(TLS), 외부 DB(PostgreSQL), 클러스터링 |
| Realm 파일 | `realm-integration-hub.dev.json` (테스트 사용자 포함, `displayName`에 DEV ONLY 표시) | 테스트 사용자 없는 별도 Realm 설정. 사용자는 IdP 연동(LDAP·AD·SAML) 또는 관리 절차로 생성 |
| 비밀번호 | `.env`(Git 제외)의 개발용 값 | Secret Manager. 관리자 계정은 기동 후 임시 계정을 제거 |
| Redirect URI | `http://localhost:8099/callback` | 실제 HTTPS 프론트엔드 주소만 |
| core-api 설정 | `OAUTH2_ISSUER_URI`, `OAUTH2_JWK_SET_URI` **기본값 없음** | 환경별로 주입. 값이 없으면 `Could not resolve placeholder`로 기동 실패를 확인함 |
| 테스트 | `TestJwtIssuer`가 서명한 토큰 (`src/test` 전용) | 해당 없음 |

애플리케이션 코드에는 개발 전용 분기(프로파일별 보안 완화 등)가 없습니다. 환경마다 달라지는 것은 토큰을 발급하는 주체(issuer)와 공개키 주소뿐입니다.

## 검증 결과

### 자동 테스트

`./gradlew test`로 **104개**(core-api 93개, api-gateway 11개)가 통과했습니다.

| 테스트 | 내용 |
|--------|------|
| `SecurityIntegrationTest` (18) | 토큰 없음 401. 형식 오류·만료·issuer 불일치·audience 불일치·신뢰하지 않는 키 각 401(`invalid_token`, 내부 사유 비노출). USER의 주문 조회·등록 허용. USER의 ADMIN API 6종 403(`insufficient_scope`). ADMIN 이력 조회 허용. ADMIN 역할만 있으면 주문 API 403. 역할 없는 토큰 403. 목록 밖 경로 403. Health 공개 |
| `IntegrationApiIntegrationTest` (+2) | 재처리: FAILED → 새 SUCCESS 이력 + 기존 이력 유지 + 성공 이력 재처리 409. 없는 이력 404 |
| 기존 기능 테스트 | `@WithMockUser`로 인증을 대체하고 기능 검증은 그대로 유지 |

**테스트가 실제로 잡아내는지 확인:** audience 검증 설정을 일부러 끄고 실행하자 `wrong audience → 401` 1건만 실패했습니다. 설정은 다시 되돌렸습니다.

### Docker Compose (실제 Keycloak, Gateway 경유)

7개 서비스가 healthy 상태가 된 뒤 `scripts/get-token.sh`로 실제 로그인했습니다.

| 확인 항목 | 결과 |
|-----------|------|
| user1 토큰 Claim | `iss=http://localhost:8180/realms/integration-hub`, `aud=integration-hub-api`, `azp=integration-hub-web`, `realm_access.roles=[USER]`, 유효 300초 |
| admin1 토큰 | `realm_access.roles=[ADMIN, USER]` |
| 틀린 비밀번호 | 로그인 실패 (code 미발급) |
| Password Grant 시도 | `unauthorized_client: Client not allowed for direct access grants` |
| PKCE 없이 인가 요청 | `error=invalid_request, Missing parameter: code_challenge_method` |
| 등록되지 않은 redirect_uri | `Invalid parameter: redirect_uri` |

| 요청 (localhost:8080 = Gateway) | 결과 |
|------------------------------|------|
| 토큰 없음 / `abc.def.ghi` | 401 `Authentication required` / 401 `Invalid or expired access token` |
| USER: `POST /api/orders`, `GET /api/orders`, `GET /api/orders/2` | 201, 200, 200 |
| USER: PATCH 상태, DELETE, sync, history, retry | 모두 403 |
| ADMIN: sync, history, PATCH 상태, DELETE | 201, 200, 200, 204 |
| ADMIN: `erp-flaky` 주문 sync → FAILED 이력 retry | 201 `SUCCESS`, `erpOrderNo=ERP-3` |
| ADMIN: SUCCESS 이력 retry | 409 |
| ADMIN: `/actuator/env` | 404 (Gateway에 Route 없음) |

## 학습 정리

### Authentication vs Authorization

| | Authentication (인증) | Authorization (인가) |
|--|----------------------|----------------------|
| 질문 | 누구인가? | 무엇을 할 수 있는가? |
| 이 프로젝트 | Keycloak 로그인 → core-api의 JWT 서명·만료·issuer·audience 검증 | `realm_access.roles` → `hasRole("ADMIN")` 등 URL별 규칙 |
| 실패 응답 | **401 Unauthorized** (이름과 달리 "인증 안 됨") | **403 Forbidden** (누군지는 알지만 권한 없음) |

### SSO (Single Sign-On)

한 번 로그인하면 같은 IdP(Keycloak)를 신뢰하는 여러 애플리케이션을 다시 로그인하지 않고 쓰는 방식입니다. Keycloak은 로그인 후 자기 도메인에 SSO 세션 쿠키를 둡니다. 다른 Client가 인가 요청을 보내면 로그인 화면 없이 바로 `code`를 발급합니다. 앱마다 비밀번호를 따로 관리하지 않아도 되고, 비밀번호 정책, MFA, 계정 잠금, 로그아웃을 한곳에서 통제합니다.

### IAM (Identity and Access Management)

"누가(Identity) 무엇에(Access) 접근할 수 있는가"를 조직 차원에서 관리하는 체계입니다. 사용자·그룹·역할 관리, 인증(비밀번호, MFA, 외부 IdP 연동), 권한 부여·회수, 감사 로그를 포함합니다. 이 프로젝트에서는 Keycloak이 IAM 역할을 맡습니다. core-api는 IAM이 발급한 증명(토큰)만 검증하고 사용자 DB를 갖지 않습니다.

### OAuth2

**권한 위임(Authorization)** 프레임워크입니다. 사용자의 비밀번호를 앱에 넘기지 않고, 앱이 사용자 대신 API를 호출할 수 있는 Access Token을 받는 방법을 정의합니다.

| 역할 | 이 프로젝트 |
|------|-------------|
| Resource Owner | 사용자 (user1, admin1) |
| Client | `integration-hub-web` (get-token.sh, 향후 웹 화면) |
| Authorization Server | Keycloak |
| Resource Server | core-api |

Grant Type은 Authorization Code(+PKCE), Client Credentials(서버 간 통신), Refresh Token 등이 있습니다. OAuth2 자체는 "이 토큰으로 무엇을 할 수 있는가"만 다룹니다. "로그인한 사람이 누구인가"는 정의하지 않습니다.

### OIDC (OpenID Connect)

OAuth2 위에 **인증(로그인)**을 표준화한 계층입니다. `scope=openid`로 요청하면 다음이 추가됩니다.

- **ID Token:** 누가, 언제, 어떻게 로그인했는지 담은 JWT. 클라이언트가 사용자를 식별하는 용도입니다.
- **UserInfo Endpoint, Discovery:** `/.well-known/openid-configuration`에서 issuer, 토큰·JWKS 주소 등을 표준 형식으로 제공합니다.

API 호출에는 **Access Token**을 쓰고, ID Token을 API에 보내지 않습니다. 이 프로젝트도 core-api가 `aud=integration-hub-api`인 Access Token만 받습니다.

### JWT (JSON Web Token)

`Header.Payload.Signature`를 Base64URL로 인코딩한 토큰입니다.

- **검증 방식:** Keycloak이 개인키로 서명하고, core-api는 공개키(JWKS)로 서명을 확인합니다. 그래서 매 요청마다 Keycloak에 묻지 않고 core-api 혼자 검증할 수 있습니다(Stateless).
- **주요 Claim:** `iss`(발급자), `aud`(대상 API), `exp`(만료), `sub`(사용자 ID), `realm_access.roles`(역할).
- **주의할 점:**
  - Payload는 **암호화가 아니라 인코딩**입니다. 누구나 읽을 수 있으므로 비밀 정보를 넣으면 안 됩니다.
  - 발급된 토큰은 만료 전까지 취소하기 어렵습니다. 그래서 수명을 짧게(5분) 하고 Refresh Token으로 갱신합니다.
  - `aud`를 검증하지 않으면 다른 서비스용으로 발급된 토큰도 받아들이게 됩니다. 테스트 `wrong audience → 401`이 이를 확인합니다.

### RBAC (Role-Based Access Control)

권한을 사용자에게 직접 주지 않고 **역할에 권한을 묶은 뒤, 사용자에게 역할을 부여**하는 방식입니다. 사용자가 늘거나 업무가 바뀌어도 역할 부여만 바꾸면 됩니다.

- **이 프로젝트의 구조:** `user1 → USER → 주문 조회·등록`, `admin1 → USER + ADMIN → 전체`.
- **역할 정의 위치:**
  - 역할 목록과 부여는 Keycloak(IAM)이 관리합니다.
  - 역할별로 허용되는 API는 core-api `SecurityConfig`가 정합니다.
- **허용 목록:** 목록에 없는 경로를 `denyAll`로 막기 때문에, 새 API를 추가하면 권한도 반드시 함께 정해야 합니다.
- **한계와 확장:** 역할만으로는 "자기 주문만 조회" 같은 데이터 단위 조건을 표현할 수 없습니다. 이런 조건은 ABAC(속성 기반)나 소유자 확인 로직이 필요합니다.

## 한계와 다음 단계 후보

- **주문 소유자 검사 없음:** USER는 다른 고객의 주문도 조회할 수 있습니다. 토큰의 `sub`와 주문의 `customerId`를 연결하는 정책이 필요합니다.
- **누가 실행했는지 기록 없음:** 연계 이력에 실행자가 남지 않습니다. 감사 목적이라면 토큰의 `preferred_username`을 이력에 저장할 수 있습니다.
- **Gateway는 토큰을 검증하지 않음:** 선택한 구조대로입니다. Gateway에서 먼저 걸러내려면 Gateway도 Resource Server로 만들어야 합니다.
- **Refresh Token, 로그아웃 미사용:** 스크립트는 Access Token만 출력합니다. 실제 웹 클라이언트는 Refresh Token 갱신과 RP-Initiated Logout을 구현해야 합니다.
- **Keycloak 데이터 미보존:** dev-file이 컨테이너 내부에만 있어 `down` 후 `up`하면 Realm 파일에서 다시 만들어집니다. 관리 콘솔에서 바꾼 내용은 사라집니다.
- **이전 문서의 curl 예시:** STEP 2~6 문서의 curl 예시는 인증 도입 전 기준입니다. 지금은 `-H "Authorization: Bearer $TOKEN"`이 필요합니다.
