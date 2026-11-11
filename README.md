# enterprise-integration-hub

Spring Boot 기반 Enterprise Integration Hub 프로젝트입니다.

## 빠른 시작

```bash
cp .env.example .env                 # DB·Redis 비밀번호 설정 (최초 1회)
./gradlew test                       # 테스트 (Docker 필요: Testcontainers)
docker compose up -d --build         # core-api + PostgreSQL + Redis 실행 (http://localhost:8080)
```

## 문서

| 문서 | 내용 |
|------|------|
| [docs/architecture.md](docs/architecture.md) | 기술 스택, 디렉터리 구조, 설정, 헬스 체크 |
| [docs/getting-started.md](docs/getting-started.md) | 로컬 실행, IDE 설정 |
| [docs/docker.md](docs/docker.md) | Docker Compose 실행, 구성, 로그 확인 |
| [docs/step1-java21-basics.md](docs/step1-java21-basics.md) | STEP 1. Java 21 핵심 문법 (주문 처리 예제) |
| [docs/step2-rest-api.md](docs/step2-rest-api.md) | STEP 2. 주문 관리 REST API (curl 예시 포함) |
| [docs/step3-postgresql-jpa.md](docs/step3-postgresql-jpa.md) | STEP 3. PostgreSQL + JPA + Flyway (SQL 로그 포함) |
| [docs/step4-docker-compose.md](docs/step4-docker-compose.md) | STEP 4. Docker Compose 통합 환경 (Image/Container/Volume/Network) |
