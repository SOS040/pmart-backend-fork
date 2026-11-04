#!/usr/bin/env bash
# LAN Debezium outbox 커넥터에 traceparent 헤더를 더한다(trace-correlation-plan.md 작업 B, 2026-11-04).
#   deploy/lan/connectors-add-traceparent.sh [check|apply]   (기본 check — 바꿀 커넥터 목록만)
#
# 순서: 먼저 V0_0_4(outbox_event.traceparent) 가 들어간 이미지로 모든 outbox 서비스를 배포한다(열이 없는 DB 에 이 설정이 들어가면
# 그 커넥터가 실패한다). 그 뒤 apply.
#
# 각 커넥터의 현재 설정을 읽어 transforms.outbox.table.fields.additional.placement 에 traceparent:header:traceparent 만 더해
# 다시 넣는다(PUT /connectors/<이름>/config). DB 비밀번호 등 다른 값은 그대로 되돌려 넣고 출력하지 않는다.
# 새로 만드는 커넥터는 deploy/local/connectors/outbox-connector.template.json(이미 포함)을 쓴다.
set -euo pipefail
mode="${1:-check}"
cd "$(dirname "$0")/../.."
CONNECT="${CONNECT_URL:-http://$(awk '/^pc[0-9]/ && / connect( |$)/ {print $2; exit}' deploy/lan/placement.conf):8083}"
KEY="transforms.outbox.table.fields.additional.placement"
ADD="traceparent:header:traceparent"
names="$(curl -sf "$CONNECT/connectors" | python -c 'import sys,json; print("\n".join(sorted(json.load(sys.stdin))))' | tr -d '\r')"
todo=0; done_=0
for n in $names; do
  cfg="$(curl -sf "$CONNECT/connectors/$n/config")"
  if printf %s "$cfg" | python -c "import sys,json; c=json.load(sys.stdin); sys.exit(0 if '$ADD' in c.get('$KEY','') else 1)"; then
    done_=$((done_ + 1)); continue
  fi
  todo=$((todo + 1))
  if [ "$mode" = apply ]; then
    printf %s "$cfg" | python -c "import sys,json; c=json.load(sys.stdin); c['$KEY']=(c.get('$KEY','')+',$ADD').lstrip(','); print(json.dumps(c))" \
      | curl -sf -X PUT -H 'Content-Type: application/json' --data-binary @- "$CONNECT/connectors/$n/config" >/dev/null
    state="$(curl -sf "$CONNECT/connectors/$n/status" | python -c 'import sys,json; s=json.load(sys.stdin); print(s["connector"]["state"], [t["state"] for t in s.get("tasks",[])])')"
    echo "갱신 $n → $state"
  else
    echo "대상 $n"
  fi
done
echo "이미 있음 $done_ · ${mode} 대상 $todo (connect: $CONNECT)"
