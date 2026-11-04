# 이벤트 이력 토픽 지표(ADR-0034) — Kafka 컨테이너 안에서 sh 로 실행, Prometheus textfile 형식을 표준출력으로.
#   watch.sh(PC 1, 5분마다)가 실행해 Kafka PC 의 pmart-lan/metrics/kafka_topics.prom 에 쓰고, 같은 PC 의 host-disk(node-exporter)가 내보낸다.
# 무제한 보관이라 크기 자체는 커지는 것이 정상 — 경보는 크기가 아니라 증가 속도·보관 설정 풀림·측정 멈춤에 건다(alert-rules.yml).
set -eu
B=/opt/kafka/bin
S=localhost:9092
TOPICS="commerce.product.v1 commerce.pricing.v1"
LIST="commerce.product.v1,commerce.pricing.v1"

echo "# HELP pmart_kafka_topic_size_bytes 토픽 로그 크기(모든 파티션 합, kafka-log-dirs)"
echo "# TYPE pmart_kafka_topic_size_bytes gauge"
$B/kafka-log-dirs.sh --bootstrap-server $S --describe --topic-list "$LIST" | grep '^{' | tr '{' '\n' \
  | sed -n 's/^"partition":"\(.*\)-[0-9]*","size":\([0-9]*\),.*/\1 \2/p' \
  | awk '{s[$1] += $2} END {for (t in s) printf "pmart_kafka_topic_size_bytes{topic=\"%s\"} %d\n", t, s[t]}'

echo "# HELP pmart_kafka_topic_end_offset 파티션 끝 오프셋 합(지금까지 쌓인 사건 수)"
echo "# TYPE pmart_kafka_topic_end_offset gauge"
echo "# HELP pmart_kafka_topic_start_offset 파티션 시작 오프셋 합(0 이 아니면 앞 사건이 지워졌다)"
echo "# TYPE pmart_kafka_topic_start_offset gauge"
echo "# HELP pmart_kafka_topic_retention_unlimited 토픽 동적 설정이 retention.ms=-1 이면 1, 아니면 0(보관 정책이 풀렸다)"
echo "# TYPE pmart_kafka_topic_retention_unlimited gauge"
for t in $TOPICS; do
  end=$($B/kafka-get-offsets.sh --bootstrap-server $S --topic "$t" --time -1 | awk -F: '{s += $3} END {print s + 0}')
  start=$($B/kafka-get-offsets.sh --bootstrap-server $S --topic "$t" --time -2 | awk -F: '{s += $3} END {print s + 0}')
  r=$($B/kafka-configs.sh --bootstrap-server $S --describe --entity-type topics --entity-name "$t" \
    | sed -n 's/.*retention\.ms=\(-\{0,1\}[0-9]*\) .*/\1/p' | head -1)
  echo "pmart_kafka_topic_end_offset{topic=\"$t\"} $end"
  echo "pmart_kafka_topic_start_offset{topic=\"$t\"} $start"
  [ "$r" = "-1" ] && u=1 || u=0
  echo "pmart_kafka_topic_retention_unlimited{topic=\"$t\"} $u"
done

# 검색 색인 DLT(search-index.DLT) 끝 오프셋 — 늘면 경보 SearchIndexDeadLetter. 이력 토픽 경보(보관·시작 오프셋)는 대상이 아니라 끝만
dlt=$($B/kafka-get-offsets.sh --bootstrap-server $S --topic search-index.DLT --time -1 2>/dev/null | awk -F: '{s += $3} END {print s + 0}')
echo "pmart_kafka_topic_end_offset{topic=\"search-index.DLT\"} ${dlt:-0}"

echo "# HELP pmart_kafka_topic_metrics_timestamp_seconds 이 파일을 만든 시각(멈춤 감지)"
echo "# TYPE pmart_kafka_topic_metrics_timestamp_seconds gauge"
echo "pmart_kafka_topic_metrics_timestamp_seconds $(date +%s)"
