# backup-topic.sh 가 만든 줄을 토픽 한 개에 다시 넣는다(ADR-0034). 복구용 Kafka 컨테이너 안에서 sh 로 실행 — 표준입력 = 백업 파일.
#   입력 변수 T(토픽). 운영 Kafka 의 기존 토픽에 다시 넣지 않는다 — 사건이 모든 구독자에게 한 번 더 간다(README 복원 원칙).
# 보존: 키·값·헤더(순서 포함)·파티션 안 순서. 파티션은 토픽의 파티션 수가 백업 때와 같을 때 키 해시로 같은 파티션에 간다
#   (원래 생산자 Debezium 도 기본 파티셔너) — 아니면 멈춘다. 레코드 시각(CreateTime)은 복원 시각으로 바뀐다(색인은 occurredAt 을 쓴다).
# 대상 토픽은 비어 있어야 한다(오프셋이 백업과 같게 0 부터 이어지도록).
set -eu
B=/opt/kafka/bin
S="${S:-localhost:9092}"
US=$(printf '\037'); RS=$(printf '\036')  # 헤더 이름·값 사이는 ':'(backup-topic.sh 참고)
: "${T:?토픽(T) 이 필요하다}"
: "${PARTITIONS:?백업 MANIFEST 의 파티션 수(PARTITIONS)가 필요하다}"

have=$($B/kafka-topics.sh --bootstrap-server $S --describe --topic "$T" | sed -n 's/.*PartitionCount: \([0-9]*\).*/\1/p' | head -1)
[ "$have" = "$PARTITIONS" ] || { echo "restore: $T 파티션 $have ≠ 백업 $PARTITIONS — 같은 수로 만든 뒤 다시" >&2; exit 1; }
end=$($B/kafka-get-offsets.sh --bootstrap-server $S --topic "$T" --time -1 | awk -F: '{s += $3} END {print s + 0}')
[ "$end" = 0 ] || { echo "restore: $T 가 비어 있지 않다(끝 오프셋 합 $end) — 빈 토픽에만 복원한다" >&2; exit 1; }

# 앞 3필드(CreateTime·Partition·Offset)를 떼면 producer 의 parse.headers 형식(헤더 US 키 US 값)이 된다
cut -d "$US" -f4- | $B/kafka-console-producer.sh --bootstrap-server $S --topic "$T" \
  --property parse.key=true --property parse.headers=true \
  --property key.separator="$US" --property headers.delimiter="$US" \
  --property headers.separator="$RS" --property headers.key.separator=: \
  --property null.marker=__PMART_NULL__ \
  --producer-property acks=all --producer-property enable.idempotence=true \
  --producer-property max.in.flight.requests.per.connection=1

echo "restore: $T 끝 오프셋 합 $($B/kafka-get-offsets.sh --bootstrap-server $S --topic "$T" --time -1 | awk -F: '{s += $3} END {print s + 0}')"
