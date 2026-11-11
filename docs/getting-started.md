# 로컬 개발 환경

## 로컬 실행 (Gradle)

로컬에 JDK 21이 없어도 됩니다. Gradle Toolchain(foojay resolver)이 JDK 21을 자동으로 내려받아 컴파일·테스트에 사용합니다. Gradle 실행에는 JDK 17 이상이 필요합니다.

PostgreSQL과 Redis가 필요합니다. Docker로 DB와 Redis만 띄우고, 앱은 Gradle로 실행합니다.

Gradle 프로젝트는 2개입니다. 루트(`:`)가 core-api이고 `:api-gateway`가 Gateway입니다. `./gradlew bootRun`처럼 프로젝트를 지정하지 않으면 두 프로젝트의 `bootRun`이 모두 선택됩니다. 이때 첫 번째 앱이 끝나지 않으므로 두 번째는 실행되지 않습니다. 반드시 `:bootRun` 또는 `:api-gateway:bootRun`으로 지정합니다.

```bash
cp .env.example .env                 # 최초 1회, 비밀번호 항목(DB, Redis, Keycloak, 테스트 사용자) 변경
docker compose up -d postgres redis keycloak  # PostgreSQL 17, Redis 7.4, Keycloak (localhost:8180)
set -a; source .env; set +a          # 비밀번호와 OAUTH2_ISSUER_URI, OAUTH2_JWK_SET_URI를 현재 셸에 export
./gradlew :bootRun                   # core-api (http://localhost:8080), Flyway 마이그레이션 자동 적용
./gradlew :api-gateway:bootRun       # (다른 터미널, 선택) Gateway http://localhost:8000 → core-api 8080
TOKEN=$(scripts/get-token.sh user1)  # 로그인 (비밀번호 입력), Access Token 5분 유효
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/orders
```

core-api는 `OAUTH2_ISSUER_URI`, `OAUTH2_JWK_SET_URI`에 기본값이 없어서, 이 값이 없으면 시작에 실패합니다. 개발용 인증 설정이 다른 환경에 그대로 들어가지 않도록 의도한 동작입니다. 테스트는 `src/test/resources/application.properties`와 `TestJwtIssuer`를 사용하므로 Keycloak이 필요 없습니다.

Compose의 mock-erp, mock-mes는 내부 네트워크 전용이라 호스트 포트가 없습니다. 로컬 core-api에서 연계 API를 쓰려면 Mock을 따로 띄웁니다.

```bash
docker run -d --rm --name local-mock-erp -p 127.0.0.1:8081:8080 \
  -v "$PWD/mock/erp/mappings:/home/wiremock/mappings:ro" wiremock/wiremock:3.13.1
docker run -d --rm --name local-mock-mes -p 127.0.0.1:8082:8080 \
  -v "$PWD/mock/mes/mappings:/home/wiremock/mappings:ro" wiremock/wiremock:3.13.1
docker stop local-mock-erp local-mock-mes   # 종료
```

Redis가 없어도 API는 동작하지만 `/actuator/health`가 `DOWN`으로 표시됩니다.

```bash
./gradlew test       # 두 프로젝트 테스트. Testcontainers가 PostgreSQL·Redis·WireMock 컨테이너를 직접 띄우므로 Docker만 실행 중이면 된다
./gradlew :api-gateway:test   # Gateway 테스트만
```

- 포트를 바꾸려면 `SERVER_PORT=18080 ./gradlew :bootRun`
- 로컬 5432 포트가 사용 중이면 `.env`에 `DB_PORT=15432`를 설정하고 `DB_URL=jdbc:postgresql://localhost:15432/integrationhub`로 실행합니다.

## IDE 사용 방법

### IntelliJ IDEA
1. `File > Open`에서 프로젝트 루트(`build.gradle`가 있는 폴더)를 선택하고 Gradle 프로젝트로 엽니다.
2. `Settings > Build, Execution, Deployment > Build Tools > Gradle`에서 **Gradle JVM**을 17 이상으로 지정합니다.
3. `File > Project Structure > Project SDK`를 JDK 21로 지정합니다. (없으면 `Add SDK > Download JDK`에서 21 선택)
4. `EnterpriseIntegrationHubApplication`의 `main`을 실행하거나 Gradle 탭의 루트 프로젝트 `bootRun`을 실행합니다. Gateway는 `api-gateway` 프로젝트의 `ApiGatewayApplication`입니다.

### VS Code
1. **Extension Pack for Java**와 **Spring Boot Extension Pack**을 설치합니다.
2. 폴더를 열면 Gradle 프로젝트가 자동으로 import됩니다.
3. JDK 21을 `java.configuration.runtimes`에 등록한 뒤 Spring Boot Dashboard에서 실행합니다.
