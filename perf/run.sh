#!/usr/bin/env bash
# 성능 측정 실행. API × 동시 사용자 수마다 [데이터 초기화 → 토큰 발급 → Warm-up(버림) → 측정]을 반복한다.
#
#   set -a; source .env; set +a      # DB_*, KC_TEST_USER_PASSWORD, KC_TEST_ADMIN_PASSWORD
#   docker compose -f docker-compose.yml -f perf/docker-compose.perf.yml up -d --build --wait
#   perf/run.sh before                          # 전체 (결과: perf/results/before/<api>-<vus>.json)
#   VU_LEVELS="10" perf/run.sh after order-list  # 일부만
#
# k6는 Docker 이미지로 Compose의 backend 네트워크 안에서 실행한다 (호스트 포트 포워딩 영향 제외).
set -euo pipefail

LABEL=${1:?"usage: $0 <label> [api ...]"}
shift
APIS=${*:-"order-create order-get order-list inventory erp-sync"}
VU_LEVELS=${VU_LEVELS:-"1 10 50 100"}
DURATION=${DURATION:-30s}
WARMUP=${WARMUP:-15s}
OUT=perf/results/$LABEL
K6_IMAGE=grafana/k6:1.0.0

cd "$(dirname "$0")/.."
mkdir -p "$OUT"

reset_data() {
  docker exec -i integration-hub-postgres psql -q -U "${DB_USERNAME:-integrationhub}" -d "${DB_NAME:-integrationhub}" \
    -v ON_ERROR_STOP=1 < perf/seed.sql
  docker exec integration-hub-redis sh -c 'redis-cli --scan --pattern "inventory::*" | xargs -r redis-cli del' > /dev/null
}

# Access Token 유효시간이 5분이므로 측정마다 새로 받는다. 비밀번호는 환경 변수로만 전달한다.
issue_tokens() {
  USER_TOKEN=$(KC_PASSWORD=${KC_TEST_USER_PASSWORD:?} scripts/get-token.sh user1)
  ADMIN_TOKEN=$(KC_PASSWORD=${KC_TEST_ADMIN_PASSWORD:?} scripts/get-token.sh admin1)
  export USER_TOKEN ADMIN_TOKEN
}

# k6 <api> <vus> <duration> <start-id> <summary-file|"">
k6() {
  docker run --rm --network integration-hub_backend --cpus 2 -e USER_TOKEN -e ADMIN_TOKEN \
    -v "$PWD/perf/k6:/scripts:ro" -v "$PWD/$OUT:/out" "$K6_IMAGE" run -q --no-color \
    -e API="$1" -e VUS="$2" -e DURATION="$3" -e START_ID="$4" -e SUMMARY="$5" /scripts/load.js > /dev/null
}

for api in $APIS; do
  for vus in $VU_LEVELS; do
    reset_data
    issue_tokens
    # Warm-up: JIT 컴파일, Connection Pool, 캐시를 채운다. 연계는 측정과 겹치지 않는 주문(95,001~)을 쓴다.
    k6 "$api" 10 "$WARMUP" 95001 ""
    echo "$(date -u +%FT%TZ) start $api vus=$vus"
    k6 "$api" "$vus" "$DURATION" 50001 "/out/$api-$vus.json"
    cat "$OUT/$api-$vus.json"
  done
done
