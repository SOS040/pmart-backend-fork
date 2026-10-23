#!/bin/sh
# Debezium Outbox 커넥터 자동 등록 (로컬, docker compose 의 connector-registrar 컨테이너에서 실행).
# 서비스 DB 를 주기적으로 훑어서, 공통 마이그레이션(V0_0_1)이 publication pmart_outbox 를 만든 DB 마다
# 커넥터 outbox-<service> 가 없으면 만든다. 서비스 목록을 따로 관리하지 않는다(DB 이름 규칙 order_service → order-service).
# - 이미 있는 커넥터는 건드리지 않는다. 설정을 바꿨으면 register-connector.sh 로 갱신(PUT).
# - RUN_ONCE=true 면 한 바퀴만 돈다.
# busybox sh/wget 만 쓴다(postgres:alpine 이미지).
set -u
CONNECT_URL="${CONNECT_URL:-http://connect:8083}"
DB_HOST="${DB_HOST:-postgres}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-pmart}"
export PGPASSWORD="${DB_PASSWORD:-pmart-local}"
INTERVAL="${INTERVAL:-10}"
RUN_ONCE="${RUN_ONCE:-false}"
TEMPLATE="${TEMPLATE:-/connectors/outbox-connector.template.json}"

psql_q() { psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$1" -Atqc "$2" 2>/dev/null; }

register() {
  db="$1"
  service="$(echo "$db" | tr '_' '-')"
  name="outbox-${service}"
  wget -q -O /dev/null "${CONNECT_URL}/connectors/${name}" 2>/dev/null && return 0
  body="$(sed -e "s|\${SERVICE_SLOT}|${db}|g" -e "s|\${SERVICE}|${service}|g" \
              -e "s|\${DB_HOST}|${DB_HOST}|g" -e "s|\${DB_PORT}|${DB_PORT}|g" \
              -e "s|\${DB_USER}|${DB_USER}|g" -e "s|\${DB_PASSWORD}|${PGPASSWORD}|g" \
              -e "s|\${DB_NAME}|${db}|g" "$TEMPLATE")"
  if wget -q -O /dev/null --header 'Content-Type: application/json' --post-data "$body" \
      "${CONNECT_URL}/connectors" 2>/dev/null; then
    echo "$(date -u +%FT%TZ) created ${name}"
  else
    echo "$(date -u +%FT%TZ) FAILED ${name} (Connect 로그 확인)" >&2
  fi
}

echo "connector-registrar: ${CONNECT_URL}, ${DB_HOST}:${DB_PORT}, 주기 ${INTERVAL}s"
while :; do
  for db in $(psql_q postgres "SELECT datname FROM pg_database WHERE NOT datistemplate AND datname NOT IN ('postgres','pmart') ORDER BY 1"); do
    [ "$(psql_q "$db" "SELECT 1 FROM pg_publication WHERE pubname = 'pmart_outbox'")" = "1" ] && register "$db"
  done
  [ "$RUN_ONCE" = "true" ] && break
  sleep "$INTERVAL"
done
