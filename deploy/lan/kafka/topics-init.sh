# 특수 설정 토픽 준비(ADR-0034·0037) — Kafka 노드 컨테이너 안에서 sh 로 실행. 몇 번 돌려도 같은 결과(멱등).
#   lan.sh deploy 가 세 노드를 먼저 올린 뒤(재생성 없이) 한 노드에 이 파일을 복사해 실행한다.
# - 대상은 아래 토픽뿐이다(보관 설정이 브로커 기본과 다른 토픽). 다른 토픽 설정은 읽지도 바꾸지도 않는다.
#   업무·heartbeat 토픽은 Debezium Connect, {topic}.DLT·Bus 토픽은 Stream 바인더가 만든다(ADR-0037 결정 4 — 브로커 자동 생성은 꺼져 있다).
# - 없으면 파티션 1개·복제 RF(기본 3)로 만든다. 있으면 파티션 수는 그대로 두고 설정만 보정한다.
#   복제 수가 RF 와 다르면 멈춘다 — 복제 수는 파티션 재할당으로만 바뀌므로 자동으로 고치지 않는다.
# - 이력 토픽: 무제한 보관(retention.ms·retention.bytes = -1), 정리 방식은 delete 그대로(Log Compaction 금지 — 같은 키에 사건 종류가 여러 개).
set -eu
B=/opt/kafka/bin
S=localhost:9092
RF="${RF:-3}"
TOPICS="commerce.product.v1 commerce.pricing.v1 mfc.sku-handling.v1"
CONFIGS="retention.ms=-1,retention.bytes=-1,cleanup.policy=delete"

check_rf() {
  have="$($B/kafka-topics.sh --bootstrap-server $S --describe --topic "$1" | sed -n 's/.*ReplicationFactor: \([0-9]*\).*/\1/p' | head -1)"
  [ "$have" = "$RF" ] || { echo "kafka-topics-init: $1 복제 수 $have ≠ $RF — 파티션 재할당이 필요하다(자동으로 고치지 않음)" >&2; exit 1; }
}

for t in $TOPICS; do
  if ! $B/kafka-topics.sh --bootstrap-server $S --describe --topic "$t" >/dev/null 2>&1; then
    $B/kafka-topics.sh --bootstrap-server $S --create --if-not-exists --topic "$t" \
      --partitions 1 --replication-factor "$RF" \
      --config retention.ms=-1 --config retention.bytes=-1 --config cleanup.policy=delete >/dev/null
    echo "kafka-topics-init: $t 생성(파티션 1, 복제 $RF)"
  fi
  # 늘 보정한다(같은 값이면 바뀌는 것 없음)
  $B/kafka-configs.sh --bootstrap-server $S --alter --entity-type topics --entity-name "$t" --add-config "$CONFIGS" >/dev/null
  cfg="$($B/kafka-configs.sh --bootstrap-server $S --describe --entity-type topics --entity-name "$t")"
  for want in retention.ms=-1 retention.bytes=-1 cleanup.policy=delete; do
    printf '%s\n' "$cfg" | grep -q "$want " || { echo "kafka-topics-init: $t 에 $want 가 없다" >&2; exit 1; }
  done
  check_rf "$t"
  parts="$($B/kafka-topics.sh --bootstrap-server $S --describe --topic "$t" | sed -n 's/.*PartitionCount: \([0-9]*\).*/\1/p' | head -1)"
  echo "kafka-topics-init: $t OK (partitions=$parts, rf=$RF, $CONFIGS)"
done

# 검색 색인 DLT(search-index.conf — 콘텐츠 보강이 재시도 뒤에도 실패한 사건). 이력 토픽이 아니라 30일 보관(2026-11-04).
# Logstash 가 발행하는 토픽이라 바인더·Connect 가 만들어 주지 않는다 — 여기서 만든다
DLT="search-index.DLT"
if ! $B/kafka-topics.sh --bootstrap-server $S --describe --topic "$DLT" >/dev/null 2>&1; then
  $B/kafka-topics.sh --bootstrap-server $S --create --if-not-exists --topic "$DLT" \
    --partitions 1 --replication-factor "$RF" --config retention.ms=2592000000 >/dev/null
  echo "kafka-topics-init: $DLT 생성(30일 보관, 복제 $RF)"
fi
check_rf "$DLT"
echo "kafka-topics-init: $DLT OK (rf=$RF)"
