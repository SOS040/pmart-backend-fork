#!/usr/bin/env bash
# 서비스별 Debezium Outbox 커넥터 등록 (로컬). 사용: ./register-connector.sh order-service order_service
# 환경변수로 덮어쓰기: CONNECT_URL, DB_HOST, DB_PORT, DB_USER, DB_PASSWORD
set -euo pipefail
SERVICE="${1:?service-name}"
DB_NAME="${2:?db-name}"
CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"
DB_HOST="${DB_HOST:-postgres}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-pmart}"
DB_PASSWORD="${DB_PASSWORD:-pmart-local}"
SERVICE_SLOT="${SERVICE//-/_}"

HERE="$(cd "$(dirname "$0")" && pwd)"
BODY="$(sed -e "s|\${SERVICE_SLOT}|${SERVICE_SLOT}|g" -e "s|\${SERVICE}|${SERVICE}|g" \
            -e "s|\${DB_HOST}|${DB_HOST}|g" -e "s|\${DB_PORT}|${DB_PORT}|g" \
            -e "s|\${DB_USER}|${DB_USER}|g" -e "s|\${DB_PASSWORD}|${DB_PASSWORD}|g" \
            -e "s|\${DB_NAME}|${DB_NAME}|g" "${HERE}/connectors/outbox-connector.template.json")"

# 있으면 config 갱신(PUT), 없으면 생성(POST)
NAME="outbox-${SERVICE}"
if curl -sf "${CONNECT_URL}/connectors/${NAME}" >/dev/null 2>&1; then
  echo "$BODY" | node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>process.stdout.write(JSON.stringify(JSON.parse(d).config)))' \
    | curl -sf -X PUT -H 'Content-Type: application/json' --data-binary @- "${CONNECT_URL}/connectors/${NAME}/config" >/dev/null
  echo "updated ${NAME}"
else
  echo "$BODY" | curl -sf -X POST -H 'Content-Type: application/json' --data-binary @- "${CONNECT_URL}/connectors" >/dev/null
  echo "created ${NAME}"
fi
curl -s "${CONNECT_URL}/connectors/${NAME}/status"; echo
