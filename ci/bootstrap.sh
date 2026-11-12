#!/usr/bin/env bash
# CI 인프라 초기 설정 (최초 1회, 다시 실행해도 안전). docker compose -f ci/docker-compose.ci.yml up 이후 실행한다.
#   Gitea: 관리자·읽기 전용 Bot 계정, 비공개 저장소, push Webhook(→ Jenkins notifyCommit)
#   Nexus: 관리자 비밀번호 변경, 익명 접근 차단, Docker Registry(docker-hosted), 배포 전용 계정(최소 권한)
#
#   set -a; source ci/.env; set +a; ci/bootstrap.sh
#
# 비밀번호는 명령줄 인자로 넘기지 않는다(curl 설정 파일 사용 → 프로세스 목록에 노출되지 않음).
set -euo pipefail

: "${GITEA_ADMIN_USERNAME:?}" "${GITEA_ADMIN_PASSWORD:?}" "${GITEA_BOT_USERNAME:?}" "${GITEA_BOT_PASSWORD:?}"
: "${NEXUS_ADMIN_PASSWORD:?}" "${NEXUS_CI_USERNAME:?}" "${NEXUS_CI_PASSWORD:?}"
GITEA=http://localhost:${GITEA_PORT:-3000}
NEXUS=http://localhost:${NEXUS_PORT:-8091}
REPO=enterprise-integration-hub
REPO_URL_INTERNAL=http://gitea:3000/${GITEA_ADMIN_USERNAME}/${REPO}.git

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
# curl 인증 설정 파일 (권한 600)
auth_file() { (umask 077; printf 'user = "%s:%s"\n' "$1" "$2" > "$TMP/$3"); echo "$TMP/$3"; }
wait_for() { until curl -fsS -o /dev/null "$1"; do echo "waiting for $1"; sleep 5; done; }

# ---------- Gitea ----------
wait_for "$GITEA/api/healthz"
# 관리자는 API가 없으므로 컨테이너 안 CLI로 만든다. 비밀번호는 환경 변수로 전달한다.
docker exec -u git -e PW="$GITEA_ADMIN_PASSWORD" ci-gitea sh -c \
  "gitea admin user create --admin --username '$GITEA_ADMIN_USERNAME' --password \"\$PW\" --email '$GITEA_ADMIN_USERNAME@example.com' --must-change-password=false" \
  2>&1 | grep -v 'already exists' || true
GA=$(auth_file "$GITEA_ADMIN_USERNAME" "$GITEA_ADMIN_PASSWORD" gitea)
gitea_api() { curl -sS -K "$GA" -H 'Content-Type: application/json' "$@"; }

gitea_api -o /dev/null -X POST "$GITEA/api/v1/admin/users" --data @- <<EOF
{"username":"$GITEA_BOT_USERNAME","password":"$GITEA_BOT_PASSWORD","email":"$GITEA_BOT_USERNAME@example.com","must_change_password":false}
EOF
gitea_api -o /dev/null -X POST "$GITEA/api/v1/user/repos" \
  --data "{\"name\":\"$REPO\",\"private\":true,\"default_branch\":\"main\"}"
gitea_api -o /dev/null -X PUT "$GITEA/api/v1/repos/$GITEA_ADMIN_USERNAME/$REPO/collaborators/$GITEA_BOT_USERNAME" \
  --data '{"permission":"read"}'
# push 이벤트 → Jenkins Git Plugin notifyCommit. Jenkins는 해당 URL을 쓰는 Job의 변경 확인(Poll)을 즉시 실행한다.
HOOK_URL="http://jenkins:8080/git/notifyCommit?url=$REPO_URL_INTERNAL"
if ! gitea_api "$GITEA/api/v1/repos/$GITEA_ADMIN_USERNAME/$REPO/hooks" | grep -q 'notifyCommit'; then
  gitea_api -o /dev/null -X POST "$GITEA/api/v1/repos/$GITEA_ADMIN_USERNAME/$REPO/hooks" \
    --data "{\"type\":\"gitea\",\"active\":true,\"events\":[\"push\"],\"config\":{\"url\":\"$HOOK_URL\",\"content_type\":\"json\",\"http_method\":\"post\"}}"
fi
echo "Gitea ready: $GITEA/$GITEA_ADMIN_USERNAME/$REPO (webhook → $HOOK_URL)"

# ---------- Nexus ----------
until [ "$(curl -s -o /dev/null -w '%{http_code}' "$NEXUS/service/rest/v1/status/writable")" = 200 ]; do
  echo "waiting for Nexus"; sleep 10
done
# 최초 기동 시에만 있는 임시 비밀번호 파일. 있으면 관리자 비밀번호를 .env 값으로 바꾼다.
if INITIAL=$(docker exec ci-nexus cat /nexus-data/admin.password 2>/dev/null); then
  NI=$(auth_file admin "$INITIAL" nexus-initial)
  printf '%s' "$NEXUS_ADMIN_PASSWORD" | curl -fsS -K "$NI" -X PUT -H 'Content-Type: text/plain' \
    --data-binary @- "$NEXUS/service/rest/v1/security/users/admin/change-password"
  docker exec ci-nexus rm -f /nexus-data/admin.password
fi
NA=$(auth_file admin "$NEXUS_ADMIN_PASSWORD" nexus)
nexus_api() { curl -sS -K "$NA" -H 'Content-Type: application/json' "$@"; }

# Community Edition 사용 약관 동의 (API로 받은 문구를 그대로 돌려보내야 한다)
EULA=$(nexus_api "$NEXUS/service/rest/v1/system/eula")
if printf '%s' "$EULA" | grep -q '"accepted" *: *false'; then
  printf '%s' "$EULA" | sed 's/"accepted" *: *false/"accepted": true/' \
    | nexus_api -o /dev/null -X POST --data-binary @- "$NEXUS/service/rest/v1/system/eula"
fi
nexus_api -o /dev/null -X PUT "$NEXUS/service/rest/v1/security/anonymous" \
  --data '{"enabled":false,"userId":"anonymous","realmName":"NexusAuthorizingRealm"}'
# docker login(Bearer Token)을 받으려면 Docker Token Realm이 필요하다.
nexus_api -o /dev/null -X PUT "$NEXUS/service/rest/v1/security/realms/active" \
  --data '["NexusAuthenticatingRealm","DockerToken"]'
if [ "$(nexus_api -o /dev/null -w '%{http_code}' "$NEXUS/service/rest/v1/repositories/docker/hosted/docker-hosted")" != 200 ]; then
  nexus_api -o /dev/null -X POST "$NEXUS/service/rest/v1/repositories/docker/hosted" --data '{
    "name":"docker-hosted","online":true,
    "storage":{"blobStoreName":"default","strictContentTypeValidation":true,"writePolicy":"allow"},
    "docker":{"v1Enabled":false,"forceBasicAuth":true,"httpPort":8082}}'
fi
# 최소 권한: Maven snapshots와 docker-hosted 읽기·쓰기만 가능한 배포 역할
nexus_api -o /dev/null -X POST "$NEXUS/service/rest/v1/security/roles" --data '{
  "id":"ci-deploy","name":"ci-deploy","description":"CI deployer (maven-snapshots, docker-hosted)",
  "privileges":["nx-repository-view-maven2-maven-snapshots-*","nx-repository-view-docker-docker-hosted-*"],"roles":[]}'
nexus_api -o /dev/null -X POST "$NEXUS/service/rest/v1/security/users" --data @- <<EOF
{"userId":"$NEXUS_CI_USERNAME","firstName":"CI","lastName":"Deployer","emailAddress":"$NEXUS_CI_USERNAME@example.com",
 "password":"$NEXUS_CI_PASSWORD","status":"active","roles":["ci-deploy"]}
EOF
echo "Nexus ready: $NEXUS (Maven: maven-snapshots, Docker: localhost:${NEXUS_REGISTRY_PORT:-8092})"
