#!/usr/bin/env bash
# LAN 배포 감시 (PC 1 에서 주기 실행 — 작업 스케줄러 등록은 watch-install.ps1).
#   deploy/lan/watch.sh            한 번 점검(필요하면 복구)하고 끝낸다
#   deploy/lan/watch.sh --dry-run  점검만 하고 복구는 하지 않는다
# - 각 PC 의 구성요소를 "다른 PC 에서 보듯이" 실제 프로토콜로 점검한다(PC 안의 healthcheck 는 정상인데 LAN 에서 불통인 경우를 잡기 위해).
#   앱 /actuator/health, PostgreSQL SSLRequest, Kafka ApiVersions, Redis PING, Zipkin /health, Connect 커넥터·작업 RUNNING.
# - 같은 구성요소가 연속 FAIL_LIMIT 회 실패하면 그 PC 에서 재시작한다(lan.sh restart). PC 마다 COOLDOWN 초 안에는 다시 재시작하지 않는다.
#   PC 의 구성요소가 전부 실패하면 Docker Desktop 이 꺼진 것으로 보고 예약 작업 'PMart Docker Desktop Start' 를 먼저 실행한다.
# - PC 마다 컴퓨터 이름(~/.ssh/config HostName)으로 실제 LAN IP 를 조회해 placement.conf 와 비교한다(ipcheck.sh, 판정이 바뀔 때만 로그).
#   IP 변경이 확정(CHANGED)되면 재시작으로는 복구되지 않으므로(다른 PC 들이 옛 IP 를 가리킴) 모든 재시작을 보류하고 알리기만 한다.
#   placement.conf 수정·재배포는 하지 않는다 — README "IP 변경 시 복구" 절차로 사람이 한다.
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
# 포트: 서비스 yml, 없으면 설정 저장소 정본 <앱>.yml(ADR-0029 로 옮긴 앱)
port_of() { local y p; y="$(src_dir "$1")/src/main/resources/application.yml"; p="$(awk '/^server:/{s=1;next} /^[^ ]/{s=0} s&&/^  port:/{print $2; exit}' "$y")"; [ -z "$p" ] && [ -f "$LAN/.config-repo/$1.yml" ] && p="$(awk '/^server:/{s=1;next} /^[^ ]/{s=0} s&&/^  port:/{print $2; exit}' "$LAN/.config-repo/$1.yml")"; echo "$p"; }

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
    kafka-[123]) [[ "$(tcp_probe "$ip" 9092 '\x00\x00\x00\x0b\x00\x12\x00\x00\x00\x00\x00\x01\x00\x01\x77' 8)" =~ ^.{8}00000001$ ]] ;; # ApiVersions v0 → 길이 + correlation id 1
    redis)    [ "$(tcp_probe "$ip" 6379 'PING\r\n' 5)" = 2b504f4e47 ] ;;                         # +PONG
    zipkin)   curl -sf -m 5 "http://$ip:9411/health" >/dev/null ;;
    keycloak) curl -sf -m 5 "http://$ip:8180/realms/pmart-staff/.well-known/openid-configuration" >/dev/null ;;
    s3)       [ "$(curl -s -m 5 -o /dev/null -w '%{http_code}' "http://$ip:8333/")" != 000 ] ;;    # SeaweedFS S3: 응답만 오면 됨(익명은 403)
    # 백오피스 웹(Nginx) 두 개 — 2026-11-13 부터 HTTPS(80·8081 은 301 로 넘기기만, ADR-0057). 예전 점검(http 200)과 hr-backoffice-web 점검 없음(기본 actuator)은
    # 늘 실패해 10분마다 재시작했다(2026-11-15 발견). 사설 CA 로 HTTPS 200 을 본다
    backoffice-web|hr-backoffice-web)
      [ "$(curl -s -m 5 --ssl-revoke-best-effort --cacert "$LAN/tls/pmart-lan-root-ca.crt" -o /dev/null -w '%{http_code}'         "https://$ip$([ "$c" = hr-backoffice-web ] && echo :8444)/")" = 200 ] ;;
    es-search|es-logs) curl -sf -m 5 "http://$ip:9200/_cluster/health" | grep -qE '"status":"(green|yellow)"' ;; # 단일 노드라 yellow 도 정상(ADR-0031·0038)
    kibana)   [ "$(curl -s -m 5 -o /dev/null -w '%{http_code}' "http://$ip:5601/api/status")" = 200 ] ;;
    logstash-logs) timeout 3 bash -c "exec 3<>/dev/tcp/$ip/5044" 2>/dev/null ;;   # beats 포트는 HTTP 가 아니라(curl 은 항상 000) TCP 연결만 확인
    # 검색 Logstash 는 LAN 에 여는 포트가 없다(Kafka 를 읽고 ES 에 쓰기만) — 그 PC 에 SSH 로 들어가 API(9600)가 답하는지 본다
    logstash-search)
      body="$(ssh -o BatchMode=yes -o ConnectTimeout=10 "$(awk -v ip="$ip" '$2 == ip {print $3; exit}' "$OUT"/*/target)" \
        "docker exec pmart-lan-logstash-search-1 curl -s -m 5 localhost:9600/" 2>/dev/null)" || return 1
      grep -q '"status":"green"' <<<"$body" ;;
    # RAG 전용 pgvector·Ollama(ADR-0051)도 LAN 포트가 없다 — 그 PC 에 SSH 로 들어가 컨테이너 안에서 본다. 없던 때는 기본(actuator)으로
    # 늘 실패해 10분마다 재시작됐고, Ollama 재시작은 올려 둔 모델을 내려 첫 질문이 실패했다(2026-11-09)
    rag-pgvector|ollama)
      ssh -o BatchMode=yes -o ConnectTimeout=10 "$(awk -v ip="$ip" '$2 == ip {print $3; exit}' "$OUT"/*/target)" \
        "docker exec pmart-lan-$c-1 $([ "$c" = ollama ] && echo 'ollama list' || echo 'pg_isready -q -U pmart')" >/dev/null 2>&1 ;;
    # 사내 메일(ADR-0056): 메일 서버는 LAN 포트가 없다 — 그 PC 에서 컨테이너 안 SMTP(25)·IMAP(143) 대기 확인. 웹메일은 LAN HTTPS(사설 CA, ADR-0057)
    mailserver)
      ssh -o BatchMode=yes -o ConnectTimeout=10 "$(awk -v ip="$ip" '$2 == ip {print $3; exit}' "$OUT"/*/target)" \
        "docker exec pmart-lan-mailserver-1 sh -c 'ss -ltn | grep -q \":25 \" && ss -ltn | grep -q \":143 \"'" >/dev/null 2>&1 ;;
    # 사서함 사이드카(ADR-0056 결정 10): LAN 포트가 없다 — 그 PC 에서 컨테이너 안 actuator(bash /dev/tcp — 이미지에 curl 이 없을 수 있어서)
    mail-provisioner)
      # 명령은 표준 입력으로 넘긴다 — 인자로 넘기면 원격 Windows 셸이 따옴표·공백을 깨 요청이 "GET" 만 가서 늘 실패하고 5분마다 재시작됐다(2026-11-15 발견)
      body="$(printf '%s\n' 'exec 3<>/dev/tcp/localhost/8513; printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3; cat <&3' \
        | ssh -o BatchMode=yes -o ConnectTimeout=10 "$(awk -v ip="$ip" '$2 == ip {print $3; exit}' "$OUT"/*/target)" \
          "docker exec -i pmart-lan-mail-provisioner-1 bash" 2>/dev/null)" || return 1
      grep -q '"status":"UP"' <<<"$body" ;;
    roundcube)
      # --ssl-revoke-best-effort: Windows curl(schannel)은 사설 CA 에 폐기 목록(CRL)이 없어 "revocation status unknown" 으로 실패한다(2026-11-12)
      [ "$(curl -s -m 5 --ssl-revoke-best-effort --cacert "$LAN/tls/pmart-lan-root-ca.crt" -o /dev/null -w '%{http_code}' "https://$ip:8190/")" = 200 ] ;;
    prometheus|alertmanager) [ "$(curl -s -m 5 -o /dev/null -w '%{http_code}' "http://$ip:$([ "$c" = prometheus ] && echo 9090 || echo 9093)/-/ready")" = 200 ] ;;
    grafana)  [ "$(curl -s -m 5 -o /dev/null -w '%{http_code}' "http://$ip:3000/api/health")" = 200 ] ;;
    # Kafka 클러스터 지표(ADR-0037). 브로커 수는 보지 않는다 — 브로커가 죽은 것은 경보(KafkaBrokerDown)가 알리고,
    # exporter 를 재시작해도 고쳐지지 않는다. 규칙이 없을 때는 기본(actuator)으로 늘 실패해 10분마다 재시작됐다(2026-11-06)
    # 본문을 먼저 받는다: pipefail 아래 `curl | grep -q` 는 grep 이 먼저 끝나 curl 이 23(쓰기 실패)으로 끝나면 실패가 된다
    kafka-exporter)
      body="$(curl -sf -m 8 "http://$ip:9308/metrics")" || return 1
      grep -q '^kafka_brokers ' <<<"$body" ;;
    connect)
      body="$(curl -sf -m 8 "http://$ip:8083/connectors?expand=status")" || return 1
      # 커넥터가 하나 이상 있고 RUNNING 이 아닌 상태(UNASSIGNED·FAILED·PAUSED)가 없어야 한다
      grep -q '"state":"RUNNING"' <<<"$body" && ! grep -q -E '"state":"(UNASSIGNED|FAILED|PAUSED|RESTARTING)"' <<<"$body" ;;
    *)        curl -sf -m 5 "http://$ip:$(port_of "$c")/actuator/health" | grep -q '"status":"UP"' ;;
  esac
}

# IP 변경 감지: 이름은 한 번에 조회하고, 판정이 이전과 달라질 때만 로그를 남긴다
source "$LAN/ipcheck.sh"
declare -A IPNAME=() IPSTAT=()
names=()
for pc in $(ls "$OUT"); do
  read -r _ ip alias < "$OUT/$pc/target"
  # pmart-pc1 = 이 PC(2026-11-15) — SSH 설정에 없어 ssh -G 가 별칭을 그대로 돌려준다. 자기 IP 는 확인하지 않는다
  if [ "$alias" = pmart-pc1 ]; then IPSTAT[$pc]=SKIP; continue; fi
  n="$(ipcheck_hostname "$alias")"
  if [ -z "$n" ] || is_ipv4 "$n"; then IPSTAT[$pc]=SKIP; else IPNAME[$pc]="$n"; names+=("$n"); fi
done
resolved=""; [ ${#names[@]} -gt 0 ] && resolved="$(ipcheck_resolve "${names[@]}")"
lan="$(awk '$1=="LAN"{print $2}' <<<"$resolved")"
ip_changed=false
for pc in $(ls "$OUT"); do
  read -r _ ip alias < "$OUT/$pc/target"
  [ -n "${IPNAME[$pc]:-}" ] && IPSTAT[$pc]="$(ipcheck_classify "$alias" "$ip" "$(awk -v n="${IPNAME[$pc]}" '$1=="NAME" && $2==n {print $3}' <<<"$resolved")" "$lan")"
  st="${IPSTAT[$pc]}"; detail="${st#* }"
  if [ "$st" != "$(cat "$STATE/ip-$pc" 2>/dev/null || echo OK)" ]; then
    case "$st" in
      OK)            log "$pc IP 정상($ip)" ;;
      LOOKUP_FAIL)   log "$pc 이름 조회 실패(${IPNAME[$pc]}) — IP 변경이 아니라 이름 해석 문제" ;;
      NO_LAN_ADDR*)  log "$pc 이름 조회 결과에 LAN 주소 없음($detail) — IP 변경이 아니라 이름 해석 문제" ;;
      CHANGED*)      log "$pc IP 변경 확인: $ip → $detail (호스트 키 일치). placement.conf 수정·render·전체 재배포 필요(README 'IP 변경 시 복구')" ;;
      UNVERIFIED*)   log "$pc 이름이 배치와 다른 LAN 주소($detail)를 가리키나 같은 PC 인지 확인 안 됨(호스트 키 불일치·응답 없음)" ;;
      AMBIGUOUS*)    log "$pc 호스트 키가 일치하는 LAN 주소가 여러 개($detail) — 직접 확인 필요" ;;
    esac
    echo "$st" > "$STATE/ip-$pc"
  fi
  [[ "$st" == CHANGED* ]] && ip_changed=true
done

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
  if $ip_changed; then log "$pc 재시작 보류 — IP 변경이 확인돼 재시작으로는 복구되지 않음: ${due[*]}"; continue; fi
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

# ---- LAN HTTPS 인증서 만료(docs/architecture/lan-https-private-ca.md T5) — 30일 안에 끝나는 인증서를 하루 한 번 로그 ----
if [ -f "$LAN/tls/tls.sh" ]; then
  source "$LAN/tls/tls.sh"
  today="$(date +%F)"
  if [ "$(cat "$STATE/tls-checked" 2>/dev/null)" != "$today" ]; then
    exp="$(tls_expiring 2592000)"
    [ -n "$exp" ] && log "LAN HTTPS 인증서 만료 30일 이내 — deploy/lan/lan.sh tls-issue <pc>(루트 CA 면 새 CA·전 기기 재설치): $(tr '\n' ';' <<<"$exp")"
    echo "$today" > "$STATE/tls-checked"
  fi
fi

# ---- Kafka 이벤트 이력 토픽 지표(ADR-0034) ----
# kafka-1(ADR-0037, 한 노드가 클러스터 전체를 본다)에서 토픽 크기·오프셋·보관 설정을 재서 그 PC 의 pmart-lan/metrics/kafka_topics.prom 에 쓴다(host-disk 가 내보낸다).
# 읽기만 한다(재시작·설정 변경 없음). 실패해도 위 점검·복구에는 영향이 없다 — 멈추면 Prometheus 의 KafkaTopicMetricsStale 이 알린다.
pc_of() { grep -l "^  $1:" "$OUT"/*/compose.yml 2>/dev/null | head -1 | xargs -r dirname | xargs -r basename; }
# kafka-1 PC 의 pmart-lan/metrics/<이름> 으로 올린다(그 PC 의 host-disk 만 textfile 수집기가 있다).
# .tmp 로 올린 뒤 이름을 바꾼다(node-exporter 는 *.prom 만 읽는다 — 반쯤 쓴 파일을 읽지 않게)
push_textfile() {
  local file="$1" name="$2" kpc host
  kpc="$(pc_of kafka-1)"; [ -n "$kpc" ] || return 0
  host="$(awk '{print $3}' "$OUT/$kpc/target")"
  scp -q -o BatchMode=yes -o ConnectTimeout=10 "$file" "$host:pmart-lan/metrics/$name.tmp" 2>/dev/null \
    && ssh -o BatchMode=yes -o ConnectTimeout=10 "$host" \
      "powershell -NoProfile -Command \"Move-Item -Force \$env:USERPROFILE\\pmart-lan\\metrics\\$name.tmp \$env:USERPROFILE\\pmart-lan\\metrics\\$name\"" >/dev/null 2>&1 \
    || log "$kpc 지표 $name 전송 실패(pmart-lan/metrics 가 없으면 lan.sh deploy $kpc 후 생긴다)"
}
search_group() { sed -n 's/^PMART_SEARCH_GROUP_ID=//p' "$LAN/elastic/search-index.env" 2>/dev/null | head -1; }

kafka_metrics() {
  local kpc host tmp
  kpc="$(pc_of kafka-1)"
  [ -n "$kpc" ] || return 0
  host="$(awk '{print $3}' "$OUT/$kpc/target")"
  tmp="$STATE/kafka_topics.prom"
  # 맨 앞에 검색 색인 소비 그룹을 붙여 보낸다(topic-metrics.sh 가 그 그룹 지연을 잰다). CR 은 지운다 — 컨테이너 sh 가 CRLF 를 못 읽는다(2026-11-04 지표 1시간 멈춤)
  { printf 'SEARCH_GROUP=%s\n' "$(search_group)"; tr -d '\r' < "$LAN/kafka/topic-metrics.sh"; } \
    | ssh -o BatchMode=yes -o ConnectTimeout=10 "$host" "docker exec -i pmart-lan-kafka-1-1 sh" 2>/dev/null > "$tmp.new" \
    || { log "$kpc Kafka 토픽 지표 측정 실패"; return 0; }
  grep -q '^pmart_kafka_topic_metrics_timestamp_seconds ' "$tmp.new" || { log "$kpc Kafka 토픽 지표 형식 이상 — 쓰지 않음"; return 0; }
  mv -f "$tmp.new" "$tmp"
  push_textfile "$tmp" kafka_topics.prom
}
kafka_metrics

# ---- 검색 색인 파이프라인 지표 ----
# Logstash 통계(9600 은 외부에 열지 않아 컨테이너 안에서 curl)와 ES 의 마지막 반영 시각을 search_index.prom 으로.
# 읽기만 한다. 가져오기에 실패해도 파일은 쓴다(pmart_logstash_up 0) — 경보가 그것으로 알린다.
search_index_metrics() {
  local lpc epc lhost eip tmp py
  lpc="$(pc_of logstash-search)"; epc="$(pc_of es-search)"
  [ -n "$lpc" ] && [ -n "$epc" ] || return 0
  lhost="$(awk '{print $3}' "$OUT/$lpc/target")"; eip="$(awk '{print $2}' "$OUT/$epc/target")"
  tmp="$STATE/search_index"
  ssh -o BatchMode=yes -o ConnectTimeout=10 "$lhost" \
    "docker exec pmart-lan-logstash-search-1 curl -s -m 10 localhost:9600/_node/stats/pipelines/search-index" 2>/dev/null > "$tmp.logstash.json" || : > "$tmp.logstash.json"
  curl -s -m 10 "http://$eip:9200/products/_search" -H 'Content-Type: application/json' \
    -d '{"size":0,"aggs":{"last":{"max":{"field":"updatedAtMs"}}}}' > "$tmp.es.json" 2>/dev/null || : > "$tmp.es.json"
  py="$(command -v python || command -v python3 || command -v py)" || { log "검색 색인 지표: python 없음"; return 0; }
  "$py" -I "$LAN/elastic/search-index-metrics.py" "$tmp.logstash.json" "$tmp.es.json" > "$tmp.prom.new" 2>>"$LOG" \
    || { log "검색 색인 지표 변환 실패"; return 0; }
  mv -f "$tmp.prom.new" "$tmp.prom"
  push_textfile "$tmp.prom" search_index.prom
}
search_index_metrics
