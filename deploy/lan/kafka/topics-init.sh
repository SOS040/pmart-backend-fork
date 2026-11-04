# 이벤트 이력 토픽의 보관 정책(ADR-0034) — Kafka 컨테이너 안에서 sh 로 실행. 몇 번 돌려도 같은 결과(멱등).
#   lan.sh deploy 가 Kafka 가 있는 PC 에서 kafka 를 먼저 올린 뒤(재생성 없이) 이 파일을 복사해 실행한다.
# - 대상은 아래 두 토픽뿐이다. 다른 토픽 설정은 읽지도 바꾸지도 않는다.
# - 없으면 파티션 1개로 만든다(자동 생성보다 먼저 — 자동 생성이면 브로커 기본 7일이 된다). 있으면 파티션 수는 그대로 두고 설정만 보정한다.
# - 무제한 보관(retention.ms·retention.bytes = -1), 정리 방식은 delete 그대로(Log Compaction 금지 — 같은 키에 사건 종류가 여러 개).
set -eu
B=/opt/kafka/bin
S=localhost:9092
TOPICS="commerce.product.v1 commerce.pricing.v1"
CONFIGS="retention.ms=-1,retention.bytes=-1,cleanup.policy=delete"

for t in $TOPICS; do
  if ! $B/kafka-topics.sh --bootstrap-server $S --describe --topic "$t" >/dev/null 2>&1; then
    $B/kafka-topics.sh --bootstrap-server $S --create --if-not-exists --topic "$t" \
      --partitions 1 --replication-factor 1 \
      --config retention.ms=-1 --config retention.bytes=-1 --config cleanup.policy=delete >/dev/null
    echo "kafka-topics-init: $t 생성(파티션 1)"
  fi
  # 생성 직후 다른 곳이 먼저 자동 생성했을 수도 있으므로 늘 보정한다(같은 값이면 바뀌는 것 없음)
  $B/kafka-configs.sh --bootstrap-server $S --alter --entity-type topics --entity-name "$t" --add-config "$CONFIGS" >/dev/null
  cfg="$($B/kafka-configs.sh --bootstrap-server $S --describe --entity-type topics --entity-name "$t")"
  for want in retention.ms=-1 retention.bytes=-1 cleanup.policy=delete; do
    printf '%s\n' "$cfg" | grep -q "$want " || { echo "kafka-topics-init: $t 에 $want 가 없다" >&2; exit 1; }
  done
  parts="$($B/kafka-topics.sh --bootstrap-server $S --describe --topic "$t" | sed -n 's/.*PartitionCount: \([0-9]*\).*/\1/p' | head -1)"
  echo "kafka-topics-init: $t OK (partitions=$parts, $CONFIGS)"
done

# 검색 색인 DLT(search-index.conf — 콘텐츠 보강이 재시도 뒤에도 실패한 사건). 이력 토픽이 아니라 30일 보관(2026-11-04)
DLT="search-index.DLT"
if ! $B/kafka-topics.sh --bootstrap-server $S --describe --topic "$DLT" >/dev/null 2>&1; then
  $B/kafka-topics.sh --bootstrap-server $S --create --if-not-exists --topic "$DLT"     --partitions 1 --replication-factor 1 --config retention.ms=2592000000 >/dev/null
  echo "kafka-topics-init: $DLT 생성(30일 보관)"
fi
echo "kafka-topics-init: $DLT OK"

