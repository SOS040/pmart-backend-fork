# Kafka 토픽 한 개를 줄 단위 텍스트로 내보낸다(ADR-0034). Kafka 컨테이너 안에서 sh 로 실행 — 입력 변수 T(토픽).
# 파티션을 직접 지정해 읽는다(소비 그룹을 만들지 않는다 — 운영 소비자의 오프셋과 무관).
# 줄 = CreateTime:<ms> US Partition:<p> US Offset:<o> US <헤더들> US <키> US <값>
#   US = 0x1F(필드), RS = 0x1E(헤더 사이), 헤더 이름과 값 사이는 ':'(Kafka 4.1 출력기가 고정 — 바꿀 수 없다), 널 = __PMART_NULL__
#   헤더 이름에 ':' 가 있으면 복원 때 나뉘는 위치가 틀어지므로 멈춘다(지금 헤더: id·eventType·aggregateType·traceId·correlationId·__debezium.*)
# 복원은 앞 3필드를 떼고 kafka-console-producer(parse.headers·parse.key, 같은 구분자)로 넣는다 — restore-topic.sh
B=/opt/kafka/bin; S=localhost:9092
US=$(printf '\037'); RS=$(printf '\036')
$B/kafka-get-offsets.sh --bootstrap-server $S --topic "$T" --time -2 | sort -t: -k2,2n > /tmp/pmart-backup-start
$B/kafka-get-offsets.sh --bootstrap-server $S --topic "$T" --time -1 | sort -t: -k2,2n > /tmp/pmart-backup-end
join -t: -j 2 -o 1.2,1.3,2.3 /tmp/pmart-backup-start /tmp/pmart-backup-end | while IFS=: read -r p s e; do
  n=$((e - s))
  [ "$n" -gt 0 ] || continue
  $B/kafka-console-consumer.sh --bootstrap-server $S --topic "$T" --partition "$p" --offset "$s" --max-messages "$n" --timeout-ms 60000 \
    --consumer-property isolation.level=read_committed \
    --property print.timestamp=true --property print.partition=true --property print.offset=true \
    --property print.headers=true --property print.key=true --property print.value=true \
    --property key.separator="$US" --property headers.separator="$RS" \
    --property null.literal=__PMART_NULL__ 2>/dev/null
done
rm -f /tmp/pmart-backup-start /tmp/pmart-backup-end
