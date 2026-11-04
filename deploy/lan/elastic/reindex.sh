#!/usr/bin/env bash
# 검색 색인 재색인(ADR-0034, 절차 REINDEX.md §2) — PC 1 Git Bash 에서. 단계마다 조건을 확인하고, 어긋나면 그 자리에서 멈춘다.
#   deploy/lan/elastic/reindex.sh status                 별칭·색인 건수·소비 그룹 지연·Logstash 큐·DLT
#   deploy/lan/elastic/reindex.sh check <새 색인>         start 의 사전 확인만(바꾸는 것 없음)
#   deploy/lan/elastic/reindex.sh start <새 색인>         사전 확인 → Kafka 백업 → search-index.env 를 새 색인·새 그룹으로 → es-init·logstash 재배포
#   deploy/lan/elastic/reindex.sh wait [최대 초]          반영 완료(지연 0·큐 0·DLT 증가 없음·건수 일치)까지 기다린 뒤 전 문서 대조
#   deploy/lan/elastic/reindex.sh switch                 별칭 products 를 새 색인으로(한 요청, 원자적). 직전에 완료 조건을 다시 본다
#   deploy/lan/elastic/reindex.sh finish                 env 를 별칭으로 되돌려 logstash 재배포 → 옛 소비 그룹·Elasticsearch PC 의 옛 정의 파일 삭제
#   deploy/lan/elastic/reindex.sh rollback               별칭을 이전 색인으로 되돌린다(switch 의 반대)
#   deploy/lan/elastic/reindex.sh drop <색인>            옛 색인 삭제 — 별칭이 가리키지 않을 때만
# 새 색인 정의 파일(search-service src/main/resources/elasticsearch/<새 색인>.json)과 SearchIndex.INDICES 를 먼저 바꿔 둔다.
# 새 소비 그룹 이름은 색인 번호에서 만든다(products-v4 → search-index-logstash-v4). 진행 상태는 deploy/lan/.reindex.state(git 제외 — build/ 는 render 가 지운다)에 둔다.
# 옛 색인은 drop 을 따로 실행할 때까지 남는다(되돌림용). search-service 새 이미지는 switch 뒤에 배포한다(새 필드를 쓰는 쿼리가 옛 색인에서는 그 조건만 못 맞춘다).
set -euo pipefail
cd "$(dirname "$0")/../../.."
LAN=deploy/lan
OUT=$LAN/build
ENVF=$LAN/elastic/search-index.env
DEFS=services/search-service/src/main/resources/elasticsearch
STATE=$LAN/.reindex.state
ALIAS=products
PY="$(command -v python || command -v python3 || command -v py)" || { echo "python 이 없다" >&2; exit 1; }

die() { echo "중단: $*" >&2; exit 1; }
note() { echo "[$(date +%H:%M:%S)] $*"; }
pc_of() { grep -l "^  $1:" "$OUT"/*/compose.yml 2>/dev/null | head -1 | xargs -r dirname | xargs -r basename; }
host_of() { awk '{print $3}' "$OUT/$1/target"; }
ip_of() { awk '{print $2}' "$OUT/$1/target"; }
quiet_ssh() { ssh -o BatchMode=yes -o ConnectTimeout=10 "$@" 2> >(grep -av -E 'post-quantum|store now|pq.html' >&2); }
envval() { sed -n "s/^$1=//p" "$ENVF" | tr -d '\r' | head -1; }
setenv() { sed -i "s|^$1=.*|$1=$2|" "$ENVF"; [ "$(envval "$1")" = "$2" ] || die "$ENVF 의 $1 을 바꾸지 못했다"; }

ls "$OUT"/*/target >/dev/null 2>&1 || die "build/ 가 없다 — deploy/lan/lan.sh render 먼저"
EPC="$(pc_of elasticsearch)"; LPC="$(pc_of logstash)"; KPC="$(pc_of kafka)"; PPC="$(pc_of prometheus)"
[ -n "$EPC" ] && [ -n "$LPC" ] && [ -n "$KPC" ] || die "elasticsearch·logstash·kafka 배치를 찾지 못했다(render 확인)"
ES="http://$(ip_of "$EPC"):9200"

es() { curl -sf -m 30 "$ES$1" "${@:2}"; }
json() { "$PY" -I -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
alias_target() { es "/_alias/$ALIAS" | json "','.join(sorted(d))" 2>/dev/null || true; }
exists() { [ "$(curl -s -o /dev/null -w '%{http_code}' -m 10 -I "$ES/$1")" = 200 ]; }
count() { es "/$1/_count" | json "d['count']"; }
kx() { quiet_ssh "$(host_of "$KPC")" "docker exec -i pmart-lan-kafka-1 sh"; }
# 그룹 지연 합. 그룹이 없거나 아직 커밋한 오프셋이 없으면 '-'
lag() {
  printf 'B=/opt/kafka/bin\n$B/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group %s 2>/dev/null || true\n' "$1" | kx \
    | awk -v g="$1" '$1 == g && $6 ~ /^[0-9]+$/ {s += $6; n++} END {if (n) print s; else print "-"}'
}
dlt_end() {
  printf '/opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic %s --time -1 2>/dev/null || true\n' "$(envval PMART_SEARCH_DLT_TOPIC)" | kx \
    | awk -F: '{s += $3} END {print s + 0}'
}
# Logstash 영구 큐에 남은 사건 수(9600 은 밖에 열지 않았다 — 컨테이너 안에서). 읽지 못하면 '-'
queue() {
  quiet_ssh "$(host_of "$LPC")" "docker exec pmart-lan-logstash-1 curl -s -m 10 localhost:9600/_node/stats/pipelines/search-index" \
    | json "(lambda q: q.get('events_count', q.get('events', 0)))(d['pipelines']['search-index'].get('queue', {}))" 2>/dev/null || echo -
}
firing() { # 재색인 전에 풀려 있어야 하는 경보(이력 보관이 깨졌으면 전체 재생이 불완전하다)
  [ -n "$PPC" ] || return 0
  curl -sf -m 10 "http://$(ip_of "$PPC"):9090/api/v1/alerts" \
    | json "' '.join(sorted({a['labels']['alertname'] for a in d['data']['alerts'] if a['state'] == 'firing' and a['labels']['alertname'] in ('KafkaEventRetentionNotUnlimited', 'KafkaEventHistoryTruncated', 'ElasticsearchClusterRed', 'ElasticsearchUnreachable')}))" 2>/dev/null     || echo "(Prometheus 경보를 읽지 못함)"
}
load_state() { [ -f "$STATE" ] || die "진행 중인 재색인이 없다($STATE) — start 먼저"; . "$STATE"; }
show() {
  local tgt; tgt="$(alias_target)"
  echo "별칭 $ALIAS → ${tgt:-없음}"
  es "/_cat/indices/products-*?h=index,health,docs.count&s=index" | sed 's/^/  색인 /'
  echo "Logstash 대상 $(envval PMART_SEARCH_PRODUCTS_INDEX), 소비 그룹 $(envval PMART_SEARCH_GROUP_ID) 지연 $(lag "$(envval PMART_SEARCH_GROUP_ID)"), 영구 큐 $(queue), DLT 끝 오프셋 $(dlt_end)"
  [ -f "$STATE" ] && { echo "진행 중:"; sed 's/^/  /' "$STATE"; } || true
}
# 반영 완료: 새 그룹 지연 0, 영구 큐 0(오프셋 커밋이 ES 쓰기보다 먼저라 지연만으로는 부족), 건수 ≥ 이전 색인
# (재생 중 새로 생긴 상품은 새 색인에만 들어간다 — 이전 색인은 start 뒤 사건을 받지 않는다)
done_yet() {
  local l q n o
  l="$(lag "$NEW_GROUP")"; q="$(queue)"; n="$(count "$NEW_INDEX")"; o="$(count "$OLD_INDEX")"
  note "지연 $l · 큐 $q · 건수 $NEW_INDEX $n / $OLD_INDEX $o"
  [ "$l" = 0 ] && [ "$q" = 0 ] && [ "$n" -ge "$o" ] && [ "$n" -gt 0 ]
}
check_dlt() {
  local now; now="$(dlt_end)"
  [ "$now" = "$DLT_START" ] || die "DLT 가 늘었다($DLT_START → $now) — REINDEX.md §3 으로 원인부터. 별칭은 그대로다"
}
compare() { # 전 문서 대조(색인 시각 updatedAtMs 와 새 색인에만 있는 필드는 빼고). 1만 건까지
  "$PY" -I - "$ES" "$OLD_INDEX" "$NEW_INDEX" <<'EOF'
import json, sys, urllib.request
es, old, new = sys.argv[1:4]
sys.stdout.reconfigure(encoding="utf-8")
def docs(i):
    body = json.dumps({"size": 10000, "sort": [{"productId": "asc"}], "query": {"match_all": {}}}).encode()
    r = urllib.request.Request(f"{es}/{i}/_search", body, {"Content-Type": "application/json"})
    return {h["_id"]: h["_source"] for h in json.load(urllib.request.urlopen(r, timeout=60))["hits"]["hits"]}
a, b = docs(old), docs(new)
diff = {}
only_new = sorted(b.keys() - a.keys())  # 재생 중 새로 생긴 상품 — 이전 색인은 start 뒤 사건을 받지 않는다
for k in a.keys():
    x, y = a[k], b.get(k)
    if y is None:
        diff.setdefault("(새 색인에 없음)", []).append(k); continue
    for f in (x.keys() | y.keys()) - {"updatedAtMs"}:
        if f in x and x.get(f) != y.get(f):
            diff.setdefault(f, []).append(k)
if only_new:
    print(f"새 색인에만 {len(only_new)}건(재생 중 생긴 상품, 예 {only_new[0]})")
print(f"대조 {len(a)} / {len(b)}건, 다른 필드: " + (", ".join(f"{f} {len(v)}건(예 {v[0]})" for f, v in sorted(diff.items())) or "없음"))
sys.exit(1 if diff else 0)
EOF
}

cmd="${1:-}"; shift || true
case "$cmd" in
  status) show ;;

  check|start)
    NEW_INDEX="${1:?새 색인 이름(예 products-v4)}"
    [[ "$NEW_INDEX" =~ ^$ALIAS-v([0-9]+)$ ]] || die "색인 이름은 $ALIAS-v<번호>"
    NEW_GROUP="search-index-logstash-v${BASH_REMATCH[1]}"
    [ ! -f "$STATE" ] || die "진행 중인 재색인이 있다($STATE): $(tr '\n' ' ' < "$STATE")"
    [ -f "$DEFS/$NEW_INDEX.json" ] || die "정의 파일 없음: $DEFS/$NEW_INDEX.json"
    grep -q "\"$NEW_INDEX\"" services/search-service/src/main/java/com/pmart/search/infrastructure/SearchIndex.java \
      || die "SearchIndex.INDICES 가 $NEW_INDEX 가 아니다"
    OLD_INDEX="$(alias_target)"
    [ -n "$OLD_INDEX" ] && [[ "$OLD_INDEX" != *,* ]] || die "별칭 $ALIAS 이 색인 하나를 가리키지 않는다(${OLD_INDEX:-없음})"
    [ "$OLD_INDEX" != "$NEW_INDEX" ] || die "별칭이 이미 $NEW_INDEX 다"
    ! exists "$NEW_INDEX" || die "$NEW_INDEX 가 이미 있다 — 이전 시도의 남은 색인이면 확인 뒤 drop, 아니면 다음 번호"
    [ "$(envval PMART_SEARCH_PRODUCTS_INDEX)" = "$ALIAS" ] || die "search-index.env 가 평소 상태(별칭)가 아니다: $(envval PMART_SEARCH_PRODUCTS_INDEX)"
    OLD_GROUP="$(envval PMART_SEARCH_GROUP_ID)"
    [ "$OLD_GROUP" != "$NEW_GROUP" ] || die "소비 그룹이 이미 $NEW_GROUP 다"
    [ "$(lag "$NEW_GROUP")" = - ] || die "소비 그룹 $NEW_GROUP 이 이미 있다(처음부터 읽지 않는다) — 지우거나 다음 번호"

    note "사전 확인"
    [ "$(es /_cluster/health | json "d['status']")" = green ] || die "Elasticsearch 가 green 이 아니다"
    f="$(firing)"; [ -z "$f" ] || die "경보 울리는 중: $f"
    l="$(lag "$OLD_GROUP")"; q="$(queue)"
    [ "$l" = 0 ] && [ "$q" = 0 ] || die "지금 파이프라인이 밀려 있다(지연 $l, 큐 $q) — 따라잡은 뒤 다시"
    note "$OLD_INDEX $(count "$OLD_INDEX")건, 그룹 $OLD_GROUP 지연 0, 큐 0 → $NEW_INDEX · $NEW_GROUP 로 재색인할 수 있다"
    [ "$cmd" = start ] || exit 0

    note "Kafka 백업"
    bash $LAN/kafka/backup.sh "${PMART_KAFKA_BACKUP_DIR:-/d/pmart-backups/kafka}" "before-reindex-$NEW_INDEX"

    cat > "$STATE" <<EOF
OLD_INDEX=$OLD_INDEX
NEW_INDEX=$NEW_INDEX
OLD_GROUP=$OLD_GROUP
NEW_GROUP=$NEW_GROUP
DLT_START=$(dlt_end)
STARTED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
    # render 전에 env 를 바꾼다 — 새 Logstash 설정이 옛 색인(strict 매핑)에 쓰지 않게(REINDEX.md §2-2)
    setenv PMART_SEARCH_PRODUCTS_INDEX "$NEW_INDEX"
    setenv PMART_SEARCH_GROUP_ID "$NEW_GROUP"
    note "search-index.env → $NEW_INDEX · $NEW_GROUP, 배포(es-init 이 빈 $NEW_INDEX 를 만들고 별칭은 그대로)"
    if [ "$EPC" = "$LPC" ]; then
      bash $LAN/lan.sh deploy "$EPC" --force-recreate es-init logstash
    else
      bash $LAN/lan.sh deploy "$EPC" --force-recreate es-init
      bash $LAN/lan.sh deploy "$LPC" --force-recreate logstash
    fi
    exists "$NEW_INDEX" || die "$NEW_INDEX 가 만들어지지 않았다 — lan.sh logs $EPC es-init"
    [ "$(alias_target)" = "$OLD_INDEX" ] || die "별칭이 바뀌었다: $(alias_target)"
    note "재생 시작. 다음: $0 wait" ;;

  wait)
    load_state
    limit="${1:-900}"; t0=$SECONDS
    until done_yet; do
      [ $((SECONDS - t0)) -lt "$limit" ] || die "${limit}초 안에 끝나지 않았다 — status 로 확인 뒤 다시 wait"
      sleep 10
    done
    check_dlt
    compare || die "대조 결과가 다르다 — 위 필드를 확인(새 필드가 일부러 다른 값이면 그 근거를 적고 switch)"
    note "완료 조건 충족. 다음: $0 switch" ;;

  switch)
    load_state
    [ "$(alias_target)" = "$OLD_INDEX" ] || die "별칭이 $OLD_INDEX 가 아니다: $(alias_target)"
    done_yet || die "완료 조건이 아직 아니다 — wait 먼저"
    check_dlt
    es /_aliases -X POST -H 'Content-Type: application/json' \
      -d "{\"actions\":[{\"remove\":{\"index\":\"$OLD_INDEX\",\"alias\":\"$ALIAS\"}},{\"add\":{\"index\":\"$NEW_INDEX\",\"alias\":\"$ALIAS\"}}]}" >/dev/null
    [ "$(alias_target)" = "$NEW_INDEX" ] || die "별칭 교체 확인 실패: $(alias_target)"
    note "별칭 $ALIAS → $NEW_INDEX. search-service 새 이미지가 있으면 지금 배포. 다음: $0 finish" ;;

  finish)
    load_state
    [ "$(alias_target)" = "$NEW_INDEX" ] || die "별칭이 $NEW_INDEX 가 아니다 — switch 먼저"
    setenv PMART_SEARCH_PRODUCTS_INDEX "$ALIAS"
    note "search-index.env → 별칭 $ALIAS(그룹 $NEW_GROUP 유지), logstash 재배포"
    bash $LAN/lan.sh deploy "$LPC" logstash
    note "옛 소비 그룹 $OLD_GROUP 삭제"
    printf '/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --delete --group %s\n' "$OLD_GROUP" | kx || note "삭제 실패(이미 없거나 소비자가 붙어 있음) — 확인 필요"
    # lan.sh deploy 의 scp 는 더하기만 한다 — 남은 정의 파일이 있으면 es-init 이 옛 색인을 지운 뒤에도 다시 만든다
    if [ -f "$DEFS/$OLD_INDEX.json" ]; then
      note "주의: $DEFS/$OLD_INDEX.json 이 아직 저장소에 있다 — 지우지 않으면 다음 render 가 다시 복사한다"
    fi
    quiet_ssh "$(host_of "$EPC")" "powershell -NoProfile -Command \"Remove-Item -ErrorAction SilentlyContinue \$env:USERPROFILE\\pmart-lan\\elastic\\indices\\$OLD_INDEX.json\""
    rm -f "$STATE"
    note "끝. 커밋: $ENVF(그룹 $NEW_GROUP). 옛 색인 $OLD_INDEX 는 확인 뒤 $0 drop $OLD_INDEX" ;;

  rollback)
    load_state
    exists "$OLD_INDEX" || die "$OLD_INDEX 가 없다"
    es /_aliases -X POST -H 'Content-Type: application/json' \
      -d "{\"actions\":[{\"remove\":{\"index\":\"$NEW_INDEX\",\"alias\":\"$ALIAS\"}},{\"add\":{\"index\":\"$OLD_INDEX\",\"alias\":\"$ALIAS\"}}]}" >/dev/null
    [ "$(alias_target)" = "$OLD_INDEX" ] || die "되돌림 확인 실패: $(alias_target)"
    note "별칭 $ALIAS → $OLD_INDEX. $OLD_INDEX 는 start 뒤 사건을 받지 않았다 — 원인을 고친 뒤 새 번호로 다시 재색인(REINDEX.md §2-7)." \
      "search-index.env 는 지금 $(envval PMART_SEARCH_PRODUCTS_INDEX) · $(envval PMART_SEARCH_GROUP_ID) — 손으로 정리한다" ;;

  drop)
    idx="${1:?지울 색인}"
    [[ "$idx" =~ ^$ALIAS-v[0-9]+$ ]] || die "$ALIAS-v<번호> 만 지운다"
    [[ ",$(alias_target)," != *",$idx,"* ]] || die "별칭이 $idx 를 가리킨다"
    [ "$(envval PMART_SEARCH_PRODUCTS_INDEX)" != "$idx" ] || die "Logstash 가 $idx 에 쓰는 중이다"
    if [ -f "$STATE" ]; then . "$STATE"; [ "$idx" != "$NEW_INDEX" ] || die "진행 중인 재색인의 새 색인이다"; fi
    es "/$idx" -X DELETE >/dev/null && note "$idx 삭제" ;;

  *) sed -n '2,11p' "$0"; exit 2 ;;
esac
