#!/usr/bin/env bash
# 개발용: OIDC Authorization Code + PKCE 흐름을 curl로 수행해 Access Token을 출력한다.
# 브라우저 로그인과 같은 순서로 동작한다 (인가 요청 → Keycloak 로그인 폼 → code → code_verifier로 토큰 교환).
#
#   TOKEN=$(scripts/get-token.sh user1)          # 비밀번호는 프롬프트로 입력 (화면에 표시되지 않음)
#   curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/orders
#
# 비밀번호는 저장하지 않고 curl에도 표준 입력으로 넘긴다 (프로세스 목록에 노출되지 않음).
# 자동화가 필요하면 KC_PASSWORD 환경 변수로 줄 수 있다.
set -euo pipefail

USERNAME=${1:?"usage: $0 <username>"}
ISSUER=${OAUTH2_ISSUER_URI:-http://localhost:8180/realms/integration-hub}
CLIENT_ID=${OIDC_CLIENT_ID:-integration-hub-web}
REDIRECT_URI=${OIDC_REDIRECT_URI:-http://localhost:8099/callback}

if [ -z "${KC_PASSWORD:-}" ]; then
  read -r -s -p "Password for ${USERNAME}: " KC_PASSWORD
  echo >&2
fi

COOKIES=$(mktemp)
trap 'rm -f "$COOKIES"' EXIT

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# PKCE: code_verifier(무작위 비밀값)는 클라이언트만 알고, 인가 요청에는 그 해시(code_challenge)만 보낸다.
CODE_VERIFIER=$(openssl rand 32 | b64url)
CODE_CHALLENGE=$(printf '%s' "$CODE_VERIFIER" | openssl dgst -sha256 -binary | b64url)
STATE=$(openssl rand -hex 16)

# 1) 인가 요청 → Keycloak 로그인 폼 (HTML)
LOGIN_PAGE=$(curl -sSf -c "$COOKIES" -b "$COOKIES" -G "$ISSUER/protocol/openid-connect/auth" \
  --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "response_type=code" \
  --data-urlencode "scope=openid" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" \
  --data-urlencode "state=$STATE" \
  --data-urlencode "code_challenge=$CODE_CHALLENGE" \
  --data-urlencode "code_challenge_method=S256")
FORM_ACTION=$(printf '%s' "$LOGIN_PAGE" | grep -o 'action="[^"]*login-actions/authenticate[^"]*"' \
  | head -1 | sed -E 's/^action="(.*)"$/\1/; s/&amp;/\&/g')
if [ -z "$FORM_ACTION" ]; then
  echo "login form not found (check Keycloak at $ISSUER)" >&2
  exit 1
fi

# 2) 사용자 인증 (Keycloak 로그인 폼 제출) → redirect_uri?code=...&state=...
REDIRECT=$(printf '%s' "$KC_PASSWORD" | curl -sS -o /dev/null -w '%{redirect_url}' -c "$COOKIES" -b "$COOKIES" \
  --data-urlencode "username=$USERNAME" --data-urlencode "password@-" "$FORM_ACTION")
CODE=$(printf '%s' "$REDIRECT" | sed -nE 's/.*[?&]code=([^&]+).*/\1/p')
RETURNED_STATE=$(printf '%s' "$REDIRECT" | sed -nE 's/.*[?&]state=([^&]+).*/\1/p')
if [ -z "$CODE" ]; then
  echo "login failed for $USERNAME (wrong password or user disabled)" >&2
  exit 1
fi
# state가 다르면 다른 요청의 응답이다 (CSRF 방지)
if [ "$RETURNED_STATE" != "$STATE" ]; then
  echo "state mismatch" >&2
  exit 1
fi

# 3) code + code_verifier → Access Token. code를 가로채도 code_verifier가 없으면 토큰을 받을 수 없다.
TOKEN_RESPONSE=$(curl -sSf "$ISSUER/protocol/openid-connect/token" \
  --data-urlencode "grant_type=authorization_code" \
  --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "code=$CODE" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" \
  --data-urlencode "code_verifier=$CODE_VERIFIER")
printf '%s' "$TOKEN_RESPONSE" | sed -nE 's/.*"access_token":"([^"]+)".*/\1/p'
