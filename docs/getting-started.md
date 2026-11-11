# 로컬 개발 환경

## 로컬 실행 (Gradle)

로컬에 JDK 21이 없어도 됩니다. Gradle Toolchain(foojay resolver)이 JDK 21을 자동으로 내려받아 컴파일·테스트에 사용합니다. Gradle 실행에는 JDK 17 이상이 필요합니다.

PostgreSQL과 Redis가 필요합니다. Docker로 DB와 Redis만 띄우고, 앱은 Gradle로 실행합니다.

```bash
cp .env.example .env                 # 최초 1회, DB_PASSWORD·REDIS_PASSWORD 변경
docker compose up -d postgres redis mock-erp mock-mes  # DB, Redis, ERP·MES Mock (localhost:8081, 8082)
set -a; source .env; set +a          # DB_PASSWORD, REDIS_PASSWORD 등을 현재 셸에 export
./gradlew bootRun                    # 실행 (http://localhost:8080), Flyway 마이그레이션 자동 적용
```

Redis가 없어도 API는 동작하지만 `/actuator/health`가 `DOWN`으로 표시됩니다.

```bash
./gradlew test       # 테스트. Testcontainers가 PostgreSQL·Redis·WireMock 컨테이너를 직접 띄우므로 Docker만 실행 중이면 된다
```

- 포트를 바꾸려면 `SERVER_PORT=18080 ./gradlew bootRun`
- 로컬 5432 포트가 사용 중이면 `.env`에 `DB_PORT=15432`를 설정하고 `DB_URL=jdbc:postgresql://localhost:15432/integrationhub`로 실행합니다.

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
