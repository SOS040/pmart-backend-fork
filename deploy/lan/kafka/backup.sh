#!/usr/bin/env bash
# 이벤트 이력 토픽 백업(ADR-0034) — PC 1 Git Bash 에서. 운영 Kafka 는 읽기만 한다(소비 그룹도 만들지 않는다).
#   deploy/lan/kafka/backup.sh [저장 폴더] [사유]
#   저장 폴더 기본: $PMART_KAFKA_BACKUP_DIR 또는 /d/pmart-backups/kafka — Kafka PC(pc2)와 다른 장애 영역에 둔다.
#   결과: <폴더>/<UTC시각>-<사유>/{토픽}.records + MANIFEST.yml(오프셋·건수·sha256). 복원은 README.md.
# 검사(하나라도 어긋나면 실패로 끝난다): 줄 수 = 오프셋 범위, 파티션마다 오프셋이 끊김 없이 이어짐, 필드 6개, 헤더 이름에 ':' 없음.
set -euo pipefail
cd "$(dirname "$0")/../../.."
LAN=deploy/lan
OUT=$LAN/build
TOPICS="commerce.product.v1 commerce.pricing.v1 mfc.sku-handling.v1"
ROOT="${1:-${PMART_KAFKA_BACKUP_DIR:-/d/pmart-backups/kafka}}"
REASON="${2:-manual}"

kpc="$(grep -l '^  kafka:' "$OUT"/*/compose.yml 2>/dev/null | head -1 | xargs -r dirname | xargs -r basename)"
[ -n "$kpc" ] || { echo "Kafka PC 를 찾지 못했다 — lan.sh render 먼저" >&2; exit 1; }
host="$(awk '{print $3}' "$OUT/$kpc/target")"
kx() { ssh -o BatchMode=yes -o ConnectTimeout=10 "$host" "docker exec -i pmart-lan-kafka-1 sh" 2> >(grep -av -E "post-quantum|store now|pq.html" >&2); }

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
dest="$ROOT/$stamp-$REASON"
mkdir -p "$dest"
{
  echo "kind: pmart-kafka-topic-backup"
  echo "taken_at: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "source: $kpc ($host) pmart-lan-kafka-1"
  echo "reason: $REASON"
  echo "format: deploy/lan/kafka/backup-topic.sh (US=0x1F fields, RS=0x1E between headers, ':' header name/value, null=__PMART_NULL__)"
  echo "topics:"
} > "$dest/MANIFEST.yml"

# 일시적인 SSH 실패(watch.sh 와 같은 순간 접속 등)는 3번까지 다시 한다. 끝내 실패하면 폴더 이름에 -INCOMPLETE 를 붙이고 실패로 끝난다
fail() { echo "백업 실패: $*" >&2; mv "$dest" "$dest-INCOMPLETE" 2>/dev/null; exit 1; }
offsets_of() { printf 'B=/opt/kafka/bin\nfor w in -2 -1; do $B/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic %s --time $w; done\n' "$1" | kx; }
records_of() { { echo "T=$1"; cat "$LAN/kafka/backup-topic.sh"; } | kx > "$dest/$1.records"; }
retry() { local i; for i in 1 2 3; do "$@" && return 0; echo "  다시 시도($i): $*" >&2; sleep 5; done; return 1; }

for t in $TOPICS; do
  offs="$(retry offsets_of "$t")" && [ -n "$offs" ] || fail "$t 오프셋 조회"
  retry records_of "$t" || fail "$t 내보내기"
  # 오프셋 목록(earliest 줄들 다음 latest 줄들)과 파일을 대조
  printf '%s\n' "$offs" | python -I -c '
import sys, collections
path, topic = sys.argv[1], sys.argv[2]
rows = [l.strip().split(":") for l in sys.stdin if l.strip()]
half = len(rows) // 2
start = {int(r[1]): int(r[2]) for r in rows[:half]}
end = {int(r[1]): int(r[2]) for r in rows[half:]}
seen = collections.defaultdict(list)
for n, line in enumerate(open(path, encoding="utf-8", newline="\n"), 1):
    f = line.rstrip("\n").split("\x1f")
    if len(f) != 6:
        sys.exit(f"{topic}: {n}번째 줄 필드 {len(f)}개")
    if f[3] != "NO_HEADERS":
        for h in f[3].split("\x1e"):
            if ":" not in h:
                sys.exit(f"{topic}: {n}번째 줄 헤더 형식 이상")
    seen[int(f[1].split(":")[1])].append(int(f[2].split(":")[1]))
total = 0
for p in sorted(start):
    want = list(range(start[p], end[p]))
    if seen.get(p, []) != want:
        sys.exit(f"{topic}: 파티션 {p} 오프셋 {start[p]}~{end[p]} 와 백업이 다르다")
    total += len(want)
print(f"  {topic}: {{ partitions: {len(start)}, offsets: \"{ {p: [start[p], end[p] - 1] for p in start} }\", records: {total} }}")
' "$dest/$t.records" "$t" >> "$dest/MANIFEST.yml" || fail "$t 검사"
done
echo "sha256:" >> "$dest/MANIFEST.yml"
(cd "$dest" && sha256sum *.records | sed 's/^/  /') >> "$dest/MANIFEST.yml"
cat "$dest/MANIFEST.yml"
echo "backup: $dest"
