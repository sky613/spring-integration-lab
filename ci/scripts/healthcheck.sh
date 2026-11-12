#!/usr/bin/env bash
# 개발 환경 배포 검증. 사용: ci/scripts/healthcheck.sh <기대하는 이미지 Tag>
#   1) 실행 중인 컨테이너가 방금 배포한 Tag인지  2) Readiness(DB·Redis 연결 포함)
#   3) Gateway → core-api 라우팅과 인증이 동작하는지 (토큰 없이 401)
set -euo pipefail
TAG=${1:?"usage: $0 <image-tag>"}

for c in integration-hub-core-api integration-hub-api-gateway; do
  image=$(docker inspect -f '{{.Config.Image}}' "$c")
  [[ "$image" == *":$TAG" ]] || { echo "FAIL $c runs $image (expected tag $TAG)"; exit 1; }
  status=$(docker inspect -f '{{.State.Health.Status}}' "$c")
  [ "$status" = healthy ] || { echo "FAIL $c health=$status"; exit 1; }
  docker exec "$c" curl -fsS --max-time 5 http://localhost:8080/actuator/health/readiness > /dev/null \
    || { echo "FAIL $c readiness"; exit 1; }
  echo "OK   $c $image healthy, readiness UP"
done

code=$(docker exec integration-hub-api-gateway curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://localhost:8080/api/orders)
[ "$code" = 401 ] || { echo "FAIL GET /api/orders without token: $code (expected 401)"; exit 1; }
echo "OK   Gateway → core-api 라우팅, 인증 필요(401)"
