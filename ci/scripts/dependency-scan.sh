#!/usr/bin/env bash
# 실행 JAR의 라이브러리 취약점 검사 (Trivy, SCA). Jenkins Dependency Vulnerability Scan 단계에서 실행한다.
#   종료 코드: 0 = HIGH/CRITICAL 없음, 2 = HIGH/CRITICAL 있음(보고서 확인), 그 외 = 검사 자체 실패
#   결과: build/reports/sca/<app>.json(전체), <app>.txt(HIGH/CRITICAL 표)
# Trivy 취약점 DB는 trivy-cache 볼륨에 저장해 빌드마다 다시 받지 않는다.
set -euo pipefail

TRIVY="docker run --rm -v trivy-cache:/root/.cache/ -v $PWD:$PWD -w $PWD aquasec/trivy:0.65.0"
OUT=build/reports/sca
mkdir -p "$OUT"

found=0
for target in core-api:build/libs api-gateway:api-gateway/build/libs; do
  app=${target%%:*}
  dir=${target#*:}
  # rootfs 모드여야 JAR 안의 라이브러리(pom.properties)를 분석한다 (fs 모드는 JAR를 건너뜀).
  $TRIVY rootfs --quiet --scanners vuln --format json -o "$OUT/$app.json" "$dir"
  $TRIVY convert --quiet --format table --severity HIGH,CRITICAL -o "$OUT/$app.txt" "$OUT/$app.json"
  $TRIVY convert --quiet --format table --severity HIGH,CRITICAL --exit-code 2 "$OUT/$app.json" > /dev/null || {
    rc=$?
    [ "$rc" -eq 2 ] || exit "$rc"
    found=1
  }
  counts=$(grep -o '"Severity": "[A-Z]*"' "$OUT/$app.json" | cut -d'"' -f4 | sort | uniq -c | xargs)
  echo "== $app: ${counts:-no vulnerabilities}"
done

[ "$found" -eq 0 ] || exit 2
