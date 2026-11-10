# enterprise-integration-hub

Spring Boot 기반 Enterprise Integration Hub 프로젝트입니다.

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
├── controller/   # REST Controller
├── service/      # 비즈니스 로직
└── repository/   # 데이터 접근 계층
src/main/resources/application.yml
```

## 로컬 실행 (Gradle)

로컬에 JDK 21이 없어도 됩니다. Gradle Toolchain(foojay resolver)이 JDK 21을 자동으로 내려받아 컴파일·테스트에 사용합니다. Gradle 실행에는 JDK 17 이상이 필요합니다.

```bash
./gradlew test       # 테스트
./gradlew bootRun    # 실행 (http://localhost:8080)
```

포트를 바꾸려면 `SERVER_PORT=18080 ./gradlew bootRun`

## Docker 실행

```bash
docker build -t enterprise-integration-hub:local .
docker compose up -d --build
curl http://localhost:8080/actuator/health   # {"status":"UP",...}
docker compose down
```

호스트의 8080 포트가 이미 사용 중이면 다른 포트로 매핑합니다.

```bash
APP_PORT=18080 docker compose up -d
```

포트 사용 여부 확인: `lsof -iTCP:8080 -sTCP:LISTEN`

## 헬스 체크

| Endpoint | 설명 |
|----------|------|
| `GET /actuator/health` | 전체 상태 |
| `GET /actuator/health/liveness` | Liveness Probe |
| `GET /actuator/health/readiness` | Readiness Probe |

## IDE 사용 방법

### IntelliJ IDEA
1. `File > Open`에서 프로젝트 루트(`build.gradle`가 있는 폴더)를 선택하고 Gradle 프로젝트로 엽니다.
2. `Settings > Build, Execution, Deployment > Build Tools > Gradle`에서 **Gradle JVM**을 17 이상으로 지정합니다.
3. `File > Project Structure > Project SDK`를 JDK 21로 지정합니다. (없으면 `Add SDK > Download JDK`에서 21 선택)
4. `EnterpriseIntegrationHubApplication`의 `main`을 실행하거나 Gradle 탭의 `bootRun`을 실행합니다.

### VS Code
1. **Extension Pack for Java**와 **Spring Boot Extension Pack**을 설치합니다.
2. 폴더를 열면 Gradle 프로젝트가 자동으로 import됩니다.
3. JDK 21을 `java.configuration.runtimes`에 등록한 뒤 Spring Boot Dashboard에서 실행합니다.
