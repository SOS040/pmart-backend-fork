#!/usr/bin/env bash
# LAN 배포 감시 (PC 1 에서 주기 실행 — 작업 스케줄러 등록은 watch-install.ps1).
#   deploy/lan/watch.sh            한 번 점검(필요하면 복구)하고 끝낸다
#   deploy/lan/watch.sh --dry-run  점검만 하고 복구는 하지 않는다
# - 각 PC 의 구성요소를 "다른 PC 에서 보듯이" 실제 프로토콜로 점검한다(PC 안의 healthcheck 는 정상인데 LAN 에서 불통인 경우를 잡기 위해).
#   앱 /actuator/health, PostgreSQL SSLRequest, Kafka ApiVersions, Redis PING, Zipkin /health, Connect 커넥터·작업 RUNNING.
# - 같은 구성요소가 연속 FAIL_LIMIT 회 실패하면 그 PC 에서 재시작한다(lan.sh restart). PC 마다 COOLDOWN 초 안에는 다시 재시작하지 않는다.
#   PC 의 구성요소가 전부 실패하면 Docker Desktop 이 꺼진 것으로 보고 예약 작업 'PMart Docker Desktop Start' 를 먼저 실행한다.
# - 배경: Docker Desktop VM 리셋 후 LAN 포트가 옛 컨테이너 IP 로 전달돼 47시간 불통이었다(docs/worklog/2026-10-27.md).
# - 상태·로그: $PMART_WATCH_DIR (기본 ~/.pmart-lan-watch). build/ 는 render 때 지워지므로 쓰지 않는다.
set -uo pipefail
cd "$(dirname "$0")/../.."
LAN=deploy/lan
OUT=$LAN/build
STATE="${PMART_WATCH_DIR:-$HOME/.pmart-lan-watch}"
FAIL_LIMIT="${PMART_WATCH_FAIL_LIMIT:-2}"
COOLDOWN="${PMART_WATCH_COOLDOWN:-600}"
DRY_RUN=false; [ "${1:-}" = "--dry-run" ] && DRY_RUN=true
mkdir -p "$STATE"
LOG="$STATE/watch.log"
[ -f "$LOG" ] && [ "$(stat -c %s "$LOG")" -gt 5242880 ] && mv -f "$LOG" "$LOG.1"
log() { echo "$(date '+%F %T') $*" | tee -a "$LOG"; }

[ -d "$OUT" ] || { log "build/ 없음 — deploy/lan/lan.sh render 먼저"; exit 1; }

src_dir() { for d in services platform-services; do [ -d "$d/$1" ] && { echo "$d/$1"; return; }; done; }
port_of() { awk '/^server:/{s=1;next} /^[^ ]/{s=0} s&&/^  port:/{print $2; exit}' "$(src_dir "$1")/src/main/resources/application.yml"; }

# host port 요청(printf 형식) 읽을바이트 → 응답 앞부분 16진수(타임아웃·거부면 빈 문자열).
# 바이트 수만 보면 엉뚱한 서버(HTTP 400 등)도 통과하므로 호출 쪽에서 내용을 확인한다.
tcp_probe() {
  timeout 6 bash -c "exec 3<>/dev/tcp/$1/$2 && printf '$3' >&3 && dd bs=1 count=$4 <&3 2>/dev/null" 2>/dev/null | od -An -tx1 | tr -d ' \n'
}

# 구성요소 하나 점검: 성공이면 0
check() {
  local ip="$1" c="$2" body
  case "$c" in
    postgres) [[ "$(tcp_probe "$ip" 15432 '\x00\x00\x00\x08\x04\xd2\x16\x2f' 1)" =~ ^(4e|53)$ ]] ;;  # SSLRequest → 'N'|'S'
    kafka)    [[ "$(tcp_probe "$ip" 9092 '\x00\x00\x00\x0b\x00\x12\x00\x00\x00\x00\x00\x01\x00\x01\x77' 8)" =~ ^.{8}00000001$ ]] ;; # ApiVersions v0 → 길이 + correlation id 1
    redis)    [ "$(tcp_probe "$ip" 6379 'PING\r\n' 5)" = 2b504f4e47 ] ;;                         # +PONG
    zipkin)   curl -sf -m 5 "http://$ip:9411/health" >/dev/null ;;
    connect)
      body="$(curl -sf -m 8 "http://$ip:8083/connectors?expand=status")" || return 1
      # 커넥터가 하나 이상 있고 RUNNING 이 아닌 상태(UNASSIGNED·FAILED·PAUSED)가 없어야 한다
      grep -q '"state":"RUNNING"' <<<"$body" && ! grep -q -E '"state":"(UNASSIGNED|FAILED|PAUSED|RESTARTING)"' <<<"$body" ;;
    *)        curl -sf -m 5 "http://$ip:$(port_of "$c")/actuator/health" | grep -q '"status":"UP"' ;;
  esac
}

for pc in $(ls "$OUT"); do
  read -r _ ip alias < "$OUT/$pc/target"
  comps="$(sed -e 's/#.*//' "$LAN/placement.conf" | sed -e ':a' -e '/\\$/N; s/\\\n//; ta' | awk -v p="$pc" '$1==p { for (i=4;i<=NF;i++) print $i }')"
  total=0; failed=(); due=()
  for c in $comps; do
    total=$((total + 1)); f="$STATE/fail-$pc-$c"
    if check "$ip" "$c"; then
      [ -f "$f" ] && { log "$pc $c 회복"; rm -f "$f"; }
    else
      n=$(( $(cat "$f" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$f"
      failed+=("$c"); [ "$n" -ge "$FAIL_LIMIT" ] && due+=("$c")
    fi
  done
  if [ ${#failed[@]} -eq 0 ]; then echo "$(date '+%F %T') $pc OK ($total)" > "$STATE/last-$pc"; continue; fi
  log "$pc 실패 ${#failed[@]}/$total: ${failed[*]}"
  [ ${#due[@]} -eq 0 ] && continue

  last=$(cat "$STATE/restart-$pc" 2>/dev/null || echo 0); now=$(date +%s)
  if [ $((now - last)) -lt "$COOLDOWN" ]; then log "$pc 재시작 보류(마지막 재시작 후 $((now - last))초 < ${COOLDOWN}초): ${due[*]}"; continue; fi
  if $DRY_RUN; then log "$pc (dry-run) 재시작 대상: ${due[*]}"; continue; fi

  if [ ${#failed[@]} -eq "$total" ]; then
    log "$pc 전부 실패 — Docker Desktop 예약 작업 실행"
    printf "Start-ScheduledTask 'PMart Docker Desktop Start'\n" > "$STATE/start-docker.ps1"
    sh deploy/remote/run-ps.sh "$alias" "$STATE/start-docker.ps1" >>"$LOG" 2>&1
  fi
  log "$pc 재시작: ${due[*]}"
  bash "$LAN/lan.sh" restart "$pc" "${due[@]}" >>"$LOG" 2>&1 || log "$pc 재시작 명령 실패(원격 Docker 응답 없음?)"
  echo "$now" > "$STATE/restart-$pc"
  for c in "${due[@]}"; do rm -f "$STATE/fail-$pc-$c"; done
done
