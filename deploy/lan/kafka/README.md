# Kafka 이벤트 이력 — 보관·감시·백업·복원 (ADR-0034)

대상은 **`commerce.product.v1`·`commerce.pricing.v1` 두 토픽뿐**이다(검색 색인의 정본 이력). 다른 토픽은 브로커 기본(7일)이다.

| 파일 | 어디서 | 하는 일 |
|---|---|---|
| `topics-init.sh` | Kafka 컨테이너 안(`lan.sh deploy` 가 실행) | 두 토픽 무제한 보관(`retention.ms=-1`, `retention.bytes=-1`, `cleanup.policy=delete`). 없으면 파티션 1개로 생성. 멱등 |
| `topic-metrics.sh` | Kafka 컨테이너 안(`watch.sh` 가 5분마다) | 토픽 크기·시작/끝 오프셋·무제한 여부를 Prometheus textfile 형식으로 |
| `backup.sh` | PC 1 Git Bash | 두 토픽을 읽기 전용으로 내보내고 검사·MANIFEST(sha256) |
| `backup-topic.sh` | Kafka 컨테이너 안(`backup.sh` 가 넣음) | 토픽 한 개를 줄 단위로 출력(소비 그룹을 만들지 않는다) |
| `restore-topic.sh` | **복구용** Kafka 컨테이너 안 | 백업 줄을 빈 토픽에 다시 넣는다 |

## 보관 설정 확인

```bash
ssh pmart-pc2 "docker exec pmart-lan-kafka-1 /opt/kafka/bin/kafka-configs.sh --bootstrap-server localhost:9092 --describe --entity-type topics --entity-name commerce.product.v1"
# → cleanup.policy=delete, retention.bytes=-1, retention.ms=-1 (DYNAMIC_TOPIC_CONFIG)
```

- Kafka 를 새로 만들면(볼륨 손실) 다음 `lan.sh deploy pc2` 전까지 7일이 걸린다. `KafkaEventRetentionNotUnlimited` 경보가 알린다.
- 배포 없이 바로 고치려면 `ssh pmart-pc2 "docker exec -i pmart-lan-kafka-1 sh" < deploy/lan/kafka/topics-init.sh`(재시작 없음)을 실행한다.

## 감시

**역할**
- PC 2: 측정 대상이다.
- PC 1 `watch.sh`: 측정하고, 결과를 `pc2:%USERPROFILE%\pmart-lan\metrics\kafka_topics.prom` 에 쓴다.
- PC 2 `host-disk`: node-exporter textfile 수집기로 내보낸다.
- PC 4 Prometheus: 수집하고 경보를 평가한다.

| 지표 | 뜻 |
|---|---|
| `pmart_kafka_topic_size_bytes{topic}` | 토픽 로그 크기 |
| `pmart_kafka_topic_end_offset{topic}` / `_start_offset` | 쌓인 사건 수 / 0 이 아니면 앞 사건이 지워졌다 |
| `pmart_kafka_topic_retention_unlimited{topic}` | 1 = 무제한 보관 |
| `pmart_kafka_topic_metrics_timestamp_seconds` | 측정 시각(멈춤 감지) |

**경보** (`monitoring/alert-rules.yml`) — 고정 크기 경보는 없다. 무제한이라 커지는 것이 정상이다.
- `KafkaEventRetentionNotUnlimited`
- `KafkaEventHistoryTruncated`
- `KafkaEventBurst`: 1시간 5,000건 초과. 상품 400개 등록은 약 1,600건이다.
- `KafkaEventTopicGrowthFast`: 하루 200MB 초과.
- `KafkaTopicMetricsStale`: 30분 동안 갱신 없음.
- `KafkaTopicMetricsMissing`
- `HostDiskFillPredicted`: C: 가 7일 안에 10GB 아래로 내려갈 예측. 기존 `HostDiskLow`·`HostDiskCritical` 과 함께 본다.

**디스크**
- Kafka 데이터는 PC 2 C: 의 Docker 가상디스크(`%LOCALAPPDATA%\Docker\wsl\disk\docker_data.vhdx`) 안에 있다.
- vhdx 는 **안에서 지워도 자동으로 줄지 않는다**. 정리한 뒤에도 C: 여유가 늘지 않으면 vhdx 를 압축해야 한다. 압축하려면 Docker 를 멈춰야 하므로 서비스 중단이 필요하다.
- 2026-11-03 기준값: vhdx 14.6GB, C: 여유 147GB/300GB, 두 토픽 합 약 130KB.

## 백업

```bash
deploy/lan/kafka/backup.sh                      # 기본 /d/pmart-backups/kafka/<UTC>-manual
deploy/lan/kafka/backup.sh /d/pmart-backups/kafka after-bulk-load
```

- 운영 Kafka 는 **읽기만** 한다. 파티션을 직접 지정해 읽으므로 소비 그룹을 만들지 않는다.
- 결과: `<시각>-<사유>/{토픽}.records` + `MANIFEST.yml`(파티션별 오프셋 범위·건수·sha256).
- 검사: 줄 수가 오프셋 범위와 같은지, 파티션마다 끊김이 없는지, 필드가 6개인지, 헤더 형식이 맞는지. 하나라도 어긋나면 폴더 이름에 `-INCOMPLETE` 를 붙이고 실패로 끝난다. SSH 일시 실패는 3번까지 다시 한다.
- 줄 형식: `CreateTime:<ms>` US `Partition:<p>` US `Offset:<o>` US `<헤더들>` US `<키>` US `<값>`
  - US = 0x1F, 헤더 사이 = 0x1E. 헤더 이름과 값 사이는 `:` 다(Kafka 4.1 출력기가 고정 — 헤더 이름에 `:` 가 없어야 한다).
  - null = `__PMART_NULL__`(예: 헤더 `correlationId`).
- **위치는 Kafka PC(PC 2)와 다른 장애 영역에 둔다.** 지금은 PC 1 `D:\pmart-backups\kafka` 다. PC 1 의 C:·D: 는 같은 SSD 라 외부 매체·별도 저장소로 옮기는 것은 미결이다.
- **시점**: 큰 변경 전, 상품 대량 등록 뒤. 정기 자동화는 미결.
- **RPO** 는 마지막 백업 시점이다.

| 백업 | 사건 | 비고 |
|---|---|---|
| `20261103T135659Z-baseline` | 상품 87·가격 29 | ADR-0034 보관 변경 전 |
| `20261103T141445Z-after-retention-change` | 상품 87·가격 29 | 변경 후. sha256 이 baseline 과 같다 |
| `20261103T141322Z-after-retention-change-INCOMPLETE` | 상품만 | 재시도 도입 전 SSH 일시 실패로 중단. 쓰지 않는다 |

## 복원

원칙:
1. **운영 Kafka 의 기존 토픽에 다시 넣지 않는다.** 사건이 모든 구독자에게 한 번 더 간다. 사건 id(`id` 헤더)가 보존되므로 Inbox 를 쓰는 서비스는 걸러 내지만, 그것에 기대지 않는다.
2. Kafka 를 잃었을 때 **빈 복구 환경**에 넣는다. `restore-topic.sh` 는 대상 토픽이 비어 있지 않거나 파티션 수가 백업과 다르면 멈춘다.
3. 먼저 **격리된 임시 Kafka** 에서 복원 시험을 하고, 색인을 다시 만들어 원천과 대조한 뒤 실제 복구에 쓴다.

절차(새 Kafka 가 떠 있고, **소비자와 Debezium Connect 는 아직 띄우지 않은 상태**):

```bash
B=/d/pmart-backups/kafka/<백업 폴더>
ssh pmart-pc2 "docker exec -i pmart-lan-kafka-1 sh" < deploy/lan/kafka/topics-init.sh        # 토픽·보관 정책(파티션 수는 MANIFEST 와 같게)
scp deploy/lan/kafka/restore-topic.sh pmart-pc2:pmart-lan/kafka/
ssh pmart-pc2 "docker cp pmart-lan/kafka/restore-topic.sh pmart-lan-kafka-1:/tmp/restore-topic.sh"
for t in commerce.product.v1 commerce.pricing.v1; do
  ssh pmart-pc2 "docker exec -i -e T=$t -e PARTITIONS=1 pmart-lan-kafka-1 sh /tmp/restore-topic.sh" < $B/$t.records
done
deploy/lan/kafka/backup.sh /d/pmart-backups/kafka restore-check   # 다시 내보낸다
R=$(ls -d /d/pmart-backups/kafka/*-restore-check | tail -1)
for t in commerce.product.v1 commerce.pricing.v1; do                 # 첫 필드(CreateTime)만 빼고 비교 — 파티션·오프셋·헤더·키·값이 같아야 한다
  cmp <(cut -d $'\037' -f2- $B/$t.records) <(cut -d $'\037' -f2- $R/$t.records) && echo "$t 일치"
done
```

**소비 그룹 오프셋**: 새 Kafka 에는 그룹이 없다. 그대로 띄우면 각 소비자의 `auto.offset.reset` 을 따른다. **소비자를 띄우기 전에** 그룹마다 정한다.

| 그룹(2026-11-03 두 토픽 구독) | 권장 |
|---|---|
| `search-index-logstash` | 색인도 잃었으면 처음부터(`--to-earliest`): 버전 비교로 멱등이다. 색인이 멀쩡하면 끝(`--to-latest`) |
| `cdc-inventory-service`, `mfc-inventory-service`, `pricing-service`, `product-content-service`, `storefront-service` | 서비스 DB 가 남아 있으면 끝(`--to-latest`). 이미 반영한 사건을 다시 처리하지 않는다 |
| `search-service` | ADR-0031 이전 소비 그룹의 흔적이다(지금 search-service 는 소비하지 않는다). 다시 만들 필요 없다 |

```bash
ssh pmart-pc2 "docker exec pmart-lan-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group storefront-service --topic commerce.product.v1 --topic commerce.pricing.v1 --reset-offsets --to-latest --execute"
```

**알려진 한계**
- 레코드 시각(CreateTime)은 복원 시각으로 바뀐다. 색인·서비스는 봉투의 `occurredAt` 을 쓴다.
- Debezium Connect 의 오프셋도 Kafka(내부 토픽)에 있다. Kafka 를 잃으면 Connect 는 PostgreSQL 복제 슬롯의 확인 위치부터 다시 읽는다. 마지막 백업 뒤에 이미 확인된 사건은 다시 오지 않는다. 마지막 백업과 Kafka 손실 사이의 사건은 원천 DB 에는 있지만 이력에는 없다.

**2026-11-03 복원 시험** (PC 1 Docker, 격리 네트워크 — 운영과 무관)
1. baseline 백업을 빈 임시 Kafka 에 복원했다(87·29건). 다시 내보내 비교하니 파티션·오프셋·헤더·키·값이 **모두 일치**했다. 비어 있지 않은 토픽에 복원을 시도하면 거부됐다.
2. 임시 ES(nori) + Logstash(`search-index.conf`, 콘텐츠는 LAN product-content API)로 색인을 다시 만들었다. LAN 색인과 **상품 29·가격 29 문서가 전부 같았고**(updatedAtMs 제외), 원천 DB(상품 29, EFFECTIVE 가격 29)와도 맞았다.

## LAN 배포 기록
- 2026-11-03 23:25 KST `lan.sh deploy pc2 host-disk`: pc2 컨테이너 17개 중 `host-disk` 만 재생성. Kafka·Postgres 는 그대로(`topics-init` 은 멱등 재실행). `:9100` 에 `pmart_kafka_topic_*` 노출, `node_textfile_scrape_error 0`.
- 같은 날 `lan.sh deploy pc4 es-exporter`: pc4 에 `es-exporter` 만 추가(다른 28개 그대로). Prometheus 는 `POST /-/reload` 로 반영. 대상 39개 모두 UP, 경보 규칙 16개, 발생 중인 경보 없음.

## 배포할 때 (스크립트와 LAN 배포는 따로)
- `lan.sh deploy pc2` 를 하면 Kafka 를 `--no-recreate` 로 먼저 올리고 `topics-init.sh` 를 실행한다. 이번 변경으로 **pc2 `host-disk` 만 재생성**된다(textfile 인자·`./metrics` 마운트).
- pc3 은 바뀌지 않는다.
- pc4 는 `es-exporter` 가 추가되고 Prometheus 설정·경보 규칙이 바뀐다. 파일은 바인드라 Prometheus 를 재시작하지 않고 `curl -X POST http://192.168.0.40:9090/-/reload` 로 반영한다.
- `es-exporter` 는 다른 PC 의 `extra_hosts` 에 넣지 않는다(`render.sh` `NO_EXTRA_HOST`). 넣으면 pc2·pc3 의 Kafka·Postgres·Connect 까지 모두 재생성된다.
