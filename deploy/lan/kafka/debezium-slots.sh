#!/usr/bin/env bash
# Debezium 복제 슬롯(pmart_outbox_*) 조회·삭제 — PC 1 Git Bash 에서. Kafka 를 새로 만들 때(ADR-0037 전환 계획 5장 5단계) 쓴다.
#   deploy/lan/kafka/debezium-slots.sh list              PostgreSQL PC 마다 슬롯 이름·DB·활성 여부·밀린 WAL 크기
#   deploy/lan/kafka/debezium-slots.sh drop --execute    비활성 슬롯을 모두 지운다(--execute 없으면 지울 목록만 보인다)
# - 왜 지우나: 슬롯은 "Debezium 이 어디까지 읽었나"를 PostgreSQL 쪽에 기억한다. Connect 의 오프셋(Kafka _connect.offsets)과 짝이라,
#   Kafka 를 새로 만들어 오프셋이 사라지면 옛 슬롯과 어긋난다. 지우면 커넥터를 다시 등록할 때 새 슬롯이 생긴다
#   (snapshot.mode=no_data — 그 시점부터의 outbox 행만 보낸다. 지운 뒤 등록 전에 쓰인 outbox 행은 보내지 않는다).
# - 지우는 것은 슬롯뿐이다. 업무 데이터·outbox 테이블·publication(pmart_outbox)은 그대로 둔다.
# - 활성 슬롯(Connect 가 붙어 있음)은 지우지 않는다 — Connect 를 먼저 멈춘다.
# - 논리 슬롯은 만든 DB 에 접속해서만 지울 수 있다(pg_drop_replication_slot) — 슬롯마다 그 DB 로 접속한다.
set -euo pipefail
cd "$(dirname "$0")/../../.."
OUT=deploy/lan/build
cmd="${1:-list}"; execute=false; [ "${2:-}" = "--execute" ] && execute=true

pg_pcs() { grep -l '^  postgres:' "$OUT"/*/compose.yml 2>/dev/null | xargs -r -n1 dirname | xargs -r -n1 basename; }
psql_on() { # pc db sql → 결과(구분자 |, 머리줄 없음)
  local host; host="$(awk '{print $3}' "$OUT/$1/target")"
  printf '%s\n' "$3" | ssh -o BatchMode=yes -o ConnectTimeout=10 "$host" \
    "docker exec -i pmart-lan-postgres-1 psql -U pmart -p 15432 -d $2 -At -v ON_ERROR_STOP=1" 2> >(grep -av -E "post-quantum|store now|pq.html" >&2)
}
LIST_SQL="select slot_name, database, active, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) from pg_replication_slots where slot_name like 'pmart\_outbox\_%' order by slot_name;"

[ -n "$(pg_pcs)" ] || { echo "PostgreSQL PC 를 찾지 못했다 — lan.sh render 먼저" >&2; exit 1; }
total=0; dropped=0; skipped=0
for pc in $(pg_pcs); do
  rows="$(psql_on "$pc" pmart "$LIST_SQL")" || { echo "$pc: 조회 실패" >&2; exit 1; }
  n=$(printf '%s' "$rows" | grep -c . || true); total=$((total + n))
  echo "=== $pc: 슬롯 $n개"
  [ -n "$rows" ] || continue
  while IFS='|' read -r slot db active lag; do
    case "$cmd" in
      list) printf '  %-45s db=%-32s active=%s lag=%s\n' "$slot" "$db" "$active" "$lag" ;;
      drop)
        if [ "$active" = t ]; then echo "  건너뜀(활성 — Connect 를 먼저 멈춘다): $slot"; skipped=$((skipped + 1)); continue; fi
        if $execute; then
          psql_on "$pc" "$db" "select pg_drop_replication_slot('$slot');" >/dev/null && echo "  삭제: $slot ($db)"
          dropped=$((dropped + 1))
        else
          echo "  삭제 예정: $slot ($db, lag=$lag)"
        fi ;;
      *) sed -n '2,4p' "$0"; exit 2 ;;
    esac
  done <<<"$rows"
done
echo "합계: 슬롯 $total개, 삭제 $dropped, 활성이라 건너뜀 $skipped"
[ "$cmd" = drop ] && ! $execute && echo "(지우지 않았다 — 실제로 지우려면 drop --execute)"
exit 0
