#!/usr/bin/env bash
# Debezium outbox 커넥터 heartbeat 쓰기(2026-11-25 — 사용자 "응 넣어", PR 백엔드가 찾음: pc3 cdc_inventory·mfc_inbound·mfc_quality 슬롯
# confirmed_flush 가 약 3.2GB 뒤처짐 → 그만큼 WAL 이 남아 디스크를 쓴다). 쓰기가 거의 없는 DB 는 heartbeat.interval.ms 만으로는 슬롯 확인 위치가
# 앞으로 가지 않는다(공유 PostgreSQL 의 다른 DB WAL 만 쌓임). 그래서 DB 마다 작은 표 pmart_heartbeat 를 publication pmart_outbox 에 넣고,
# 커넥터가 heartbeat 마다 한 줄을 고쳐 쓴다(heartbeat.action.query). table.include.list 가 outbox_event 만이라 업무 사건으로는 나가지 않는다.
#
# 멱등. 두 단계:
#   1) PC 2~5 PostgreSQL 의 publication pmart_outbox 가 있는 DB 마다 표·publication 넣기
#   2) Connect(PC 3) 의 outbox-* 커넥터 설정에 heartbeat.action.query 가 없으면 넣고 PUT(커넥터가 새 설정으로 다시 뜬다)
# 새로 생기는 DB·커넥터는 deploy/local/connector-registrar.sh·outbox-connector.template.json 이 같은 것을 한다.
# 비밀번호·커넥터 설정 값은 출력하지 않는다.
set -euo pipefail
LAN="$(cd "$(dirname "$0")" && pwd)"
CONNECT="${CONNECT:-http://192.168.0.222:8083}"
PCS="${PCS:-pc2 pc3 pc4 pc5}"
Q="INSERT INTO pmart_heartbeat (id, ts) VALUES (1, now()) ON CONFLICT (id) DO UPDATE SET ts = EXCLUDED.ts"
DDL="CREATE TABLE IF NOT EXISTS public.pmart_heartbeat (id integer PRIMARY KEY, ts timestamptz NOT NULL);
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'pmart_outbox' AND schemaname = 'public' AND tablename = 'pmart_heartbeat') THEN
    ALTER PUBLICATION pmart_outbox ADD TABLE public.pmart_heartbeat;
  END IF;
END \$\$;"

for pc in $PCS; do
  host="pmart-$pc"
  dbs="$(ssh "$host" "docker exec pmart-lan-postgres-1 psql -U pmart -p 15432 -d postgres -Atc \"SELECT datname FROM pg_database WHERE NOT datistemplate AND datname NOT IN ('postgres','pmart') ORDER BY 1\"" 2>/dev/null | tr -d '\r')"
  n=0
  for db in $dbs; do
    has="$(ssh "$host" "docker exec pmart-lan-postgres-1 psql -U pmart -p 15432 -d $db -Atc \"SELECT 1 FROM pg_publication WHERE pubname = 'pmart_outbox'\"" 2>/dev/null | tr -d '\r')"
    [ "$has" = "1" ] || continue
    printf '%s' "$DDL" | ssh "$host" "docker exec -i pmart-lan-postgres-1 psql -U pmart -p 15432 -d $db -q -v ON_ERROR_STOP=1" >/dev/null
    n=$((n + 1))
  done
  echo "$pc: heartbeat 표·publication $n DB"
done

changed=0; same=0
for name in $(curl -fsS "$CONNECT/connectors" | tr -d '[]"' | tr ',' ' '); do
  case "$name" in outbox-*) ;; *) continue ;; esac
  cfg="$(curl -fsS "$CONNECT/connectors/$name/config")"
  new="$(printf '%s' "$cfg" | python -I -c "
import json, sys
c = json.load(sys.stdin)
q = sys.argv[1]
if c.get('heartbeat.action.query') == q:
    sys.exit(3)
c['heartbeat.action.query'] = q
c.setdefault('heartbeat.interval.ms', '10000')
print(json.dumps(c))
" "$Q")" && rc=0 || rc=$?
  if [ "$rc" = 3 ]; then same=$((same + 1)); continue; fi
  [ "$rc" = 0 ] || { echo "$name: 설정 읽기 실패" >&2; exit 1; }
  curl -fsS -o /dev/null -X PUT -H 'Content-Type: application/json' --data "$new" "$CONNECT/connectors/$name/config"
  changed=$((changed + 1))
done
echo "커넥터: 바꿈 $changed · 이미 있음 $same"
