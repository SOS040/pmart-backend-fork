# LAN 운영 설정 — 감시·지표·경보·추적·로그·Kafka 백업

- 기준: 2026-11-06 전환 뒤(Kafka 3노드 클러스터 ADR-0037, 검색·로그 ES 분리 ADR-0038). LAN(PC 4대)에서 실제로 돌고 있는 운영 설정을 한곳에 모은 안내서다. 결정 문서가 아니다.
- 정본은 각 설정 파일이다. 값을 바꾸면 그 파일을 고치고, 이 문서도 같은 커밋에서 맞춘다.
- 관련 결정:
  - ADR-0030: 지표·로그·디스크
  - ADR-0031: Elastic Stack
  - ADR-0034: Kafka 이력 보관
  - ADR-0037: Kafka 3노드 KRaft 클러스터
  - ADR-0038: 검색 ES·로그 ES 분리

## 1. 한눈에

| 무엇 | 도구 | 어디서 | 정본 파일 |
|---|---|---|---|
| 살아 있는지 점검·자동 재시작 | `watch.sh` (5분마다) | PC1 작업 스케줄러 "PMart LAN Watch" | `deploy/lan/watch.sh` |
| 지표 수집 | Prometheus v3.5.0 | pc4 :9090 | `render.sh` (prometheus.yml 생성) |
| 경보 규칙 28개 | Prometheus → Alertmanager v0.28.1 | pc4 :9093 | `deploy/lan/monitoring/alert-rules.yml`, `alertmanager.yml` |
| 화면 | Grafana 12.0.2 | pc4 :3000 | `deploy/lan/monitoring/grafana/` (대시보드 `pmart-overview.json`, 상품 검색 `pmart-search.json`). 데이터 원본: Prometheus·Alertmanager·**로그(로그 ES `es-logs` `pmart-logs-*`)·Zipkin**(2026-11-05 — Explore 에서 로그 검색·추적 보기, 로그 traceId → 추적, 추적 → 같은 traceId 로그) |
| 요청 추적 | Zipkin 3 (저장: 로그 ES `zipkin-span-*`, 2026-11-06) | pc4 :9411 | `render.sh` zipkin 블록 |
| 앱 로그 | Filebeat → logstash-logs → es-logs → Kibana | 각 PC → pc4 | `deploy/lan/elastic/` |
| 상품 검색 색인 | Kafka → logstash-search → es-search | pc3 | `services/search-service/src/main/logstash/`, `deploy/lan/elastic/` |
| Kafka 클러스터 지표 | kafka-exporter v1.10.0 | pc4 :9308 | `render.sh` kafka-exporter 블록 |
| 디스크 여유 | node-exporter(host-disk) | 각 PC :9100 | `render.sh` host-disk 블록 |
| Kafka 이력 백업 | `backup.sh` (수동) | PC1 `D:\pmart-backups\kafka` | `deploy/lan/kafka/` |

PC 배치: `deploy/lan/placement.conf`
- pc2 (192.168.0.34): kafka-1, 상품·주문·결제 서비스
- pc3 (192.168.0.222): kafka-2, Connect, 검색 ES·Logstash(es-search·logstash-search), 센터·배송 서비스
- pc4 (192.168.0.40): kafka-3, 게이트웨이, 인증, 로그 ES·Logstash·Kibana(es-logs·logstash-logs), Prometheus·Grafana, kafka-exporter
- PC1: 빌드와 감시

## 2. 감시 — `watch.sh` (PC1, 5분마다)

- **점검 대상**: 각 구성요소를 다른 PC에서 접속하듯이 실제 프로토콜로 확인한다. PC 안의 healthcheck는 정상인데 LAN에서는 안 닿는 경우를 잡기 위해서다.

  | 대상 | 확인 방법 |
  |---|---|
  | 앱 | `/actuator/health` |
  | PostgreSQL | SSLRequest |
  | Kafka (kafka-1~3 각각) | ApiVersions |
  | Elasticsearch (es-search·es-logs) | `_cluster/health` green·yellow |
  | logstash-logs | 5044 TCP 연결 |
  | logstash-search | LAN 포트가 없어 SSH 로 그 PC 에 들어가 API(9600) `status: green` |
  | Redis | PING |
  | Zipkin | `/health` |
  | Connect | 커넥터·작업 RUNNING |

- **자동 재시작**:
  - 같은 구성요소가 2번 연속 실패하면 그 PC에서 재시작한다(`PMART_WATCH_FAIL_LIMIT`, 기본 2).
  - PC마다 10분 안에는 다시 재시작하지 않는다(`PMART_WATCH_COOLDOWN`, 기본 600초).
  - 한 PC의 구성요소가 전부 실패하면 Docker Desktop이 꺼진 것으로 보고, 예약 작업 "PMart Docker Desktop Start"를 먼저 실행한다.
- **IP 변경 감지**:
  - 컴퓨터 이름으로 실제 IP를 조회해 placement.conf와 비교한다.
  - IP가 바뀐 게 확정되면 재시작을 모두 보류하고 알리기만 한다. 복구는 README의 "IP 변경 시 복구" 절차로 사람이 한다.
- **지표 생성**(읽기만 한다):
  - Kafka 토픽 지표 → `kafka_topics.prom`
  - 검색 색인 파이프라인 지표 → `search_index.prom`
  - Kafka 토픽 지표는 kafka-1(pc2) 컨테이너에서 잰다(한 노드가 클러스터 전체를 본다). 토픽 크기는 리더 복제본 기준이다 — 복제 3이라 모든 복제본을 합하면 3배가 된다.
  - 검색 지표는 logstash-search(pc3)의 API 와 es-search 에서 읽는다.
  - 두 파일을 pc2의 `pmart-lan/metrics/`에 올리고, pc2의 host-disk(node-exporter textfile 수집기)가 Prometheus로 내보낸다.
  - 파일은 **UTF-8 + LF**여야 한다. Windows 기본값(cp949·CRLF)으로 쓰면 node-exporter가 파일 전체를 거부한다(2026-11-04).
- **상태·로그**: `~/.pmart-lan-watch/` (`watch.log`)
- **점검만 하고 복구는 안 할 때**: `deploy/lan/watch.sh --dry-run`

## 3. 지표 — Prometheus

- 수집 주기 15초, 규칙 평가도 15초.
- 보존은 **7일 또는 2 GB** 중 먼저 닿는 쪽까지(`--storage.tsdb.retention.time=7d`, `retention.size=2GB`). 메모리 상한 1 GiB.
- 수집 대상: 앱마다 `/actuator/prometheus`, host-disk 3대, es-exporter 2개(레이블 `es=search|logs`), kafka-exporter, Prometheus 자신.
  - **앱은 Eureka 등록부에서 찾는다**(2026-11-05 변경, LAN 반영 전 — 아래 참고). Prometheus 가 30초마다 Eureka(`http://<eureka PC>:8761/eureka`, 아이디 `pmart`, 비밀번호는 `build/pc4/monitoring/eureka-password` 파일 — Git 제외)에서 앱 목록을 받아, 등록된 IP + 메타데이터 `management.port` 를 수집한다. `job` = 앱 이름 소문자, `instance` = IP:포트, `pc` = IP 로 붙인 PC 이름, `discovery="eureka"`.
  - 고정 목록으로 남은 것: eureka-server(자기 자신을 등록하지 않음), host-disk·es-exporter·kafka-exporter(Eureka 클라이언트 아님), Prometheus 자신. Eureka 서버 주소는 render.sh 가 placement.conf 에서 eureka-server 위치를 읽어 적는다.
  - 서비스를 옮기거나 늘려도 Prometheus 재배포가 필요 없다(Eureka 에 다시 등록하면 30초 안에 반영). Eureka 를 못 읽으면 Prometheus 는 마지막으로 찾은 목록을 유지한다.
  - 검증(2026-11-05, 로컬 Prometheus 를 LAN Eureka 에 연결): 대상 39개 모두 UP, job·instance·pc 가 그때 LAN Prometheus(고정 목록)와 같음. 설정 `promtool check config` 통과.
  - 코드: `deploy/lan/render.sh` Prometheus 설정 생성 부분(`eureka_sd_configs`), compose 의 `eureka-password` 마운트.
  - 예외: config-server는 actuator가 health·info만 열려 있어 수집하지 않는다(열지 결정 대기).
- 규칙 파일 다시 읽기: 배포 뒤 `curl -X POST http://192.168.0.40:9090/-/reload` (`--web.enable-lifecycle`).
- 직접 만드는 지표:

  | 파일 | 만드는 곳 | 지표 |
  |---|---|---|
  | `kafka_topics.prom` | `deploy/lan/kafka/topic-metrics.sh` (kafka-1 컨테이너 안) | 토픽 크기(리더 기준)·시작/끝 오프셋·무제한 보관 여부·검색 색인 소비 지연 |
  | `search_index.prom` | `deploy/lan/elastic/search-index-metrics.py` | Logstash 살아 있음·영구 큐·DLQ·ES 거절 수·마지막 색인 반영 시각 |

## 4. 경보 규칙 (28개, `alert-rules.yml`, 시험 `alert-rules-test.yml`)

> **경보는 Slack 으로 간다**(2026-11-06 사용자 요청). Alertmanager(pc4 `http://192.168.0.40:9093`) → Slack Incoming Webhook(앱 `PMart Alerts`). Alertmanager 와 Grafana 화면에서도 볼 수 있다.
>
> - 웹훅 주소는 `deploy/lan/.secrets/slack-alert-webhook`(한 줄, Git 제외)에만 둔다. `render.sh` 가 이 파일을 pc4 `monitoring/alertmanager-secrets/` 로 넣고 Alertmanager 는 `api_url_file` 로 읽는다. 파일이 없으면 받는 곳을 `none` 으로 바꿔 렌더한다.
> - 보내는 것: critical·warning 모두, 해제 알림 포함, 멘션 없음. 묶음 `alertname`·`job`, 반복 4시간. 제목 `[CRITICAL|WARNING|해제] 경보 이름`, 본문은 경보마다 PC·instance·summary.
> - 주소를 바꾸려면 파일만 고치고 `lan.sh deploy pc4 alertmanager`. 주소가 새면 Slack 앱 설정에서 그 웹훅을 지우고 새로 만든다.
> - 시험: `POST :9093/api/v2/alerts` 로 `alertname=PmartSlackTest` 경보(endsAt 몇 분 뒤) → 채널에 경보·해제. 전송 수는 `alertmanager_notifications_total{integration="slack"}`, 실패는 `alertmanager_notifications_failed_total`.

| 묶음 | 경보 | 조건 | 심각도 |
|---|---|---|---|
| 앱 | AppDown | 2분 넘게 지표 수집 실패 | critical |
| | AppMissingFromDiscovery | 최근 1시간 안에 수집하던 앱이 Eureka 등록부에서 사라짐(죽은 앱은 30초 뒤 등록부에서 지워져 AppDown 이 못 잡는다). 1시간 뒤 저절로 풀림, 그 뒤는 watch.sh 가 본다 | critical |
| | Http5xxRateHigh | 5분간 5xx 비율 > 5% (요청이 있을 때) | warning |
| 메시징 | DeadLetterOpen | 서비스 DB에 처리 못 한 메시지(OPEN)가 있음 | critical |
| | ConsumerLagHigh | 소비 지연 > 500건이 10분 | warning |
| Elasticsearch (es 레이블로 search·logs 구분) | ElasticsearchUnreachable | 2분 응답 없음(둘 중 하나만 멈춰도) | critical |
| | ElasticsearchClusterRed | 5분 RED | critical |
| | ElasticsearchHeapHigh | 힙 > 90%가 10분 | warning |
| Kafka 클러스터 (kafka-exporter) | KafkaBrokerDown | 브로커 < 3이 2분(쓰기는 계속) | warning |
| | KafkaQuorumAtRisk | 브로커 < 2 또는 클러스터 조회 불가 1분(과반 상실 — 컨트롤러 지표가 없어 대신) | critical |
| | KafkaUnderReplicatedPartitions | 복제 부족 파티션 > 0이 5분 | warning |
| | KafkaPartitionBelowMinIsr | ISR < 2 파티션(그 파티션 쓰기 실패) 2분 | critical |
| | KafkaOfflinePartitions | 리더 없는 파티션 2분 | critical |
| Kafka 이력 | KafkaEventRetentionNotUnlimited | 상품·가격 토픽의 무제한 보관이 풀림 | critical |
| | KafkaEventHistoryTruncated | 앞 사건이 지워짐(시작 오프셋 > 0) | critical |
| | KafkaEventBurst | 1시간에 사건 > 5000건 | warning |
| | KafkaEventTopicGrowthFast | 하루 > 200 MB 증가 | warning |
| | KafkaTopicMetricsStale | 토픽 지표가 30분 넘게 갱신 안 됨 | warning |
| | KafkaTopicMetricsMissing | 토픽 지표가 2시간 없음 | warning |
| 검색 색인 | SearchIndexDeadLetter | 30분 안에 `search-index.DLT` 증가 | warning |
| | SearchIndexStalled | 지연 > 0인데 15분 넘게 반영 없음, 이 상태가 10분 | critical |
| | SearchIndexQueueBacklog | Logstash 영구 큐가 15분 비지 않음 | warning |
| | SearchIndexLogstashDown | Logstash 통계를 15분 못 읽음 | critical |
| | SearchIndexRejectedByElasticsearch | 30분 안에 ES가 문서를 거절함(DLQ 증가·실패 수 증가·DLQ 넘침) | warning |
| | SearchIndexMetricsStale | 검색 지표가 30분 넘게 갱신 안 됨 | warning |
| 디스크(C:) | HostDiskFillPredicted | 지금 속도면 7일 안에 10 GB 아래로 | warning |
| | HostDiskLow | 여유 < 15%가 10분 | warning |
| | HostDiskCritical | 여유 < 10 GB가 5분 | critical |

- 디스크는 컨테이너 안의 df(1 TB 가상 디스크)를 믿지 않고, Windows C: 실제 여유를 본다.
- Docker 가상 디스크(`docker_data.vhdx`)는 안에서 파일을 지워도 줄지 않는다. 줄이려면 Docker를 멈추고 압축해야 한다.

## 5. 요청 추적 — Zipkin

- **수집 비율 100%**: `common-observability`가 기본값 `management.tracing.sampling.probability=1.0`을 넣는다.
- 앱은 `http://zipkin:9411/api/v2/spans`로 보낸다(pmart-config `application-lan.yml`).
- 추적은 Kafka 경계를 넘어 이어진다. 결제 한 건이 진입 구간 하나와 승인 뒤 구간 하나, 모두 trace 2개로 남는다. correlationId는 사가 ID다(2026-11-04).
- **저장은 로그 ES**(`es-logs`, 2026-11-06 사용자 요청). 그 전에는 메모리 저장이라 span 5만 개(수십 분)만 남고 재시작하면 모두 사라졌다.
  - `STORAGE_TYPE=elasticsearch`, `ES_HOSTS=http://es-logs:9200`, 샤드 1·복제본 0. 색인은 하루 하나 `zipkin-span-YYYY-MM-DD`(UTC).
  - 로그 ES 의 `action.auto_create_index` 에 `+zipkin-*` 를 넣었다(없으면 색인을 못 만들어 span 을 모두 버린다 — `zipkin_collector_spans_dropped_total` 로 보인다).
  - 보존: `log-janitor` 가 5분마다 **3일**(색인 이름의 날짜, 로그 ILM 과 같은 기간) + **전체 2GB** 상한(오래된 날부터 지움).
  - es-logs 가 배치되지 않은 구성에서는 예전처럼 메모리 저장(`MEM_MAX_SPANS` 50,000)으로 렌더한다.
  - Grafana 의 Zipkin 데이터 원본·앱 전송 주소는 그대로다.
- **양**(2026-11-06 측정): 약 67 span/초 → 하루 약 580만 span. 처음 7,557 span 이 7.1MB 였다(병합 전). 이 비율이면 하루 수 GB 라 **2GB 상한이 3일보다 먼저 걸린다**(대략 반나절~하루치만 남을 수 있음). 늘리려면 상한을 올리거나 수집 비율을 낮춘다.
- 컨테이너: 힙 256m(`-XX:+ExitOnOutOfMemoryError`), 메모리 상한 512 MiB. 메모리 저장을 안 쓰니 2026-11-05 의 힙 부족 재시작(20시간 113번)은 원인이 사라졌다.
- 확인:
  - `curl http://192.168.0.40:9411/health` → `ElasticsearchStorage … UP`
  - `curl "http://192.168.0.40:9200/_cat/indices/zipkin*?v"`
  - 버린 span: `curl -s http://192.168.0.40:9411/prometheus | grep spans_dropped`

## 6. 앱 로그와 검색 색인 — 로그 ES(pc4)·검색 ES(pc3) 분리 (ADR-0038)

```
각 PC 컨테이너 로그 ─▶ log-shipper(Filebeat) ─5044─▶ logstash-logs(pc4) ─▶ es-logs pmart-logs-YYYY.MM.DD ─▶ Kibana(:5601)
Kafka 이력 토픽(kafka-1~3) ─▶ logstash-search(pc3) ─▶ es-search products·sku-prices·sku-handlings ─▶ search-service
```
- **Filebeat** (PC마다, `deploy/lan/elastic/filebeat.yml`):
  - 대상: Docker 컨테이너 로그 파일. `serviceName` 필드가 있는 앱 JSON 로그만 남긴다.
  - 초당 500건 상한, 24시간 지난 파일은 무시, 메모리 큐 4096건.
  - `pc` 필드를 붙인다. 메모리 상한 256 MiB.
- **Logstash** 두 개(각각 메모리 상한 1.5 GiB, 힙 512m, 영구 큐 256 MB, DLQ 64 MB):
  - `logstash-logs`(pc4, 5044): 파이프라인 `logs` — 로그를 es-logs `pmart-logs-YYYY.MM.dd`(UTC 날짜)로. 목록 `pipelines-logs.yml`
  - `logstash-search`(pc3, LAN 포트 없음): 파이프라인 `search-index` — 상품 검색 색인을 es-search 로. 목록 `pipelines-search.yml`. `docs/architecture/product-search.md` 참고.
- **Elasticsearch** 두 개(8.18.0, 각각 단일 노드, 힙 768m, 메모리 상한 1.5 GiB, 보안 꺼짐 — LAN 시험망):
  - `es-search`(pc3, cluster.name `pmart-search`): 상품 검색 색인만. nori 설치. 색인 자동 생성은 시스템 색인만(검색 색인은 es-init-search 가 매핑과 함께 만든다).
  - `es-logs`(pc4, cluster.name `pmart-logs`): 앱 로그만. 색인 자동 생성은 `pmart-logs-*`와 시스템 색인만.
  - 로그 ES 를 지우거나 재시작해도 검색은 영향이 없다. 반대로 검색 재색인이 로그 수집을 막지 않는다.
- **로그 보존**:
  - ILM `pmart-logs`가 3일 뒤 지운다.
  - `log-janitor`가 5분마다 확인해 색인 하나가 1 GB를 넘으면 지우고, 전체가 4 GB를 넘으면 오래된 것부터 지운다.
- **준비 작업**(한 번 실행, `es-init.sh` 의 `PMART_ES_INIT`):
  - `es-init-search`(pc3): 검색 색인·별칭
  - `es-init-logs`(pc4): ILM, 로그 색인 템플릿, Kibana 데이터 뷰 `pmart-logs-*`

## 7. Kafka 클러스터, 이벤트 이력 보관과 백업 (ADR-0034·0037)

- **클러스터**: KRaft 3노드 — kafka-1(pc2)·kafka-2(pc3)·kafka-3(pc4), 노드마다 브로커+컨트롤러. 9092(클라이언트), 9094(컨트롤러 투표, 9093 은 Alertmanager).
  - 모든 토픽 복제 3, `min.insync.replicas=2`. 한 노드가 꺼져도 쓰기가 계속되고, 두 노드가 꺼지면 멈춘다(2026-11-05 로컬 시험으로 확인).
  - 브로커 자동 토픽 생성은 꺼져 있다. 업무·heartbeat 토픽은 Debezium Connect(`topic.creation.default.*=-1`), DLT·Bus 토픽은 Stream 바인더, 특수 설정 토픽은 `topics-init.sh` 가 만든다.
  - 접속 주소는 세 노드 모두(`kafka-1:9092,kafka-2:9092,kafka-3:9092`). 데이터 볼륨 `kafkacluster`.
  - 배포: `lan.sh deploy` 는 대상 PC 의 Kafka 노드를 모두 먼저 띄우고 기다린다(한 노드만으로는 과반이 안 돼 healthy 가 되지 않는다).
- **보관**:
  - 이력 토픽 `commerce.product.v1`·`commerce.pricing.v1`·`mfc.sku-handling.v1` 만 무제한 보관한다(`retention.ms=-1`, `retention.bytes=-1`, `cleanup.policy=delete`).
  - 검색 색인을 다시 만드는 방법이 이 토픽들의 전체 재생이기 때문이다.
  - `lan.sh deploy`가 매번 `kafka/topics-init.sh`로 다시 적용한다(복제 3 확인 포함). 이미 적용돼 있으면 아무것도 바꾸지 않는다.
  - 나머지 토픽은 기본 보관(7일)이다.
- **백업** (수동, PC1 Git Bash):
  ```bash
  deploy/lan/kafka/backup.sh /d/pmart-backups/kafka <사유>
  ```
  - Kafka는 읽기만 한다. 소비 그룹도 만들지 않는다. 기본은 kafka-1 컨테이너(`pmart-lan-kafka-1-1`)에서 읽고, 그 노드가 꺼져 있으면 `PMART_KAFKA_NODE=kafka-2` 처럼 고른다.
  - 결과는 `<폴더>/<UTC시각>-<사유>/`에 `{토픽}.records`와 `MANIFEST.yml`(오프셋·건수·sha256)로 남는다.
  - 검사: 줄 수가 오프셋 범위와 같은지, 파티션마다 오프셋이 끊김 없이 이어지는지 등. 하나라도 어긋나면 실패로 끝난다.
  - 재색인 스크립트(`deploy/lan/elastic/reindex.sh start`)는 시작 전에 자동으로 백업한다(사유 `before-reindex-<색인>`).
- **지금 있는 백업** (`D:\pmart-backups\kafka`, 합계 약 3.1 MB)

  | 폴더 | 내용 |
  |---|---|
  | `20261103T135659Z-baseline` | 첫 기준 |
  | `20261103T141322Z-after-retention-change-INCOMPLETE` | 불완전(이름에 표시). 쓰지 않는다 |
  | `20261103T141445Z-after-retention-change` | 보관 설정 변경 뒤 |
  | `20261104T094614Z-after-bulk-load` | 상품 대량 등록 뒤(product 887·pricing 275) |
  | `20261104T134205Z-before-reindex-v3` | v3 재색인 전(887·275) |

- **복원**: `deploy/lan/kafka/README.md` "복원"
  - `restore-topic.sh`는 대상 토픽이 비어 있지 않거나 파티션 수가 다르면 멈춘다.
  - 먼저 격리된 임시 Kafka에서 시험한다. 2026-11-03에 시험했고, 다시 내보내 비교한 결과가 모두 일치했다.
- **한계와 결정 대기**:
  - PC1의 C:와 D:는 **같은 SSD**다. 원본은 이제 세 PC 에 복제돼 있어(ADR-0037) PC 한 대를 잃어도 남지만, 백업 자체는 PC1 디스크 하나에만 있다.
  - 외부 매체나 다른 저장소로 옮길지, 정기 백업을 자동화할지는 결정 대기다.

## 8. 컨테이너 메모리 상한

| 컨테이너 | 상한 | 비고 |
|---|---|---|
| kafka-1~3 | 2 GiB | 힙 1 GiB(기본), PC마다 하나 |
| es-search(pc3), es-logs(pc4) | 1.5 GiB | 힙 768m |
| logstash-search(pc3), logstash-logs(pc4) | 1.5 GiB | 힙 512m |
| kibana | 1 GiB | |
| prometheus | 1 GiB | |
| 업무 앱 | 768 MiB | 앱마다 |
| zipkin | 512 MiB | 힙 256m (2026-11-05) |
| log-shipper (Filebeat) | 256 MiB | PC마다 |
| config-relay, kafka-exporter | 128 MiB | |
| host-disk, es-exporter-search·logs | 64 MiB | |
| log-janitor | 32 MiB | |

- 2026-11-05 측정(전환 전): Docker 할당 세 대 모두 23.5 GiB, 컨테이너 사용량 pc2 8.9·pc3 7.9·pc4 10.3 GiB. Windows 여유 pc2 9.5·pc3 16.5·pc4 9.2 GB.
  - 전환 뒤 예상: pc2 약 9, pc3 약 12, pc4 약 12 GiB(Kafka 노드·검색 ES·Logstash 추가분). 전환 뒤 다시 잰다.
  - pmart 가 아닌 컨테이너: pc2 `vault-config-study`(약 1.1 GiB), pc4 `cardcapture`(약 1.1 GiB).
- grafana·alertmanager·redis·postgres에는 상한이 없다.

## 9. 디스크 (2026-11-05)

| PC | C: 남은 공간 / 전체 |
|---|---|
| PC1 | 111.0 / 299.7 GiB (37.0%) |
| pc2 | 142.5 / 299.7 GiB (47.5%) |
| pc3 | 85.5 / 299.7 GiB (28.5%) |
| pc4 | 85.4 / 299.7 GiB (28.5%) |

- 남은 공간이 줄어드는 요인:
  - Prometheus: 2 GB에서 멈춘다.
  - 로그: 4 GB에서 멈춘다.
  - Kafka 이력: 무제한이지만 하루 수 MB 수준이다. 복제 3이라 세 PC 에 같은 양이 쌓인다.
  - 옛 이미지와 vhdx: 가장 크다. 정리는 결정 대기다.
  - 전환 때 남긴 롤백용 옛 볼륨: pc2 `kafkadata`, pc4 `esdata`·`lsdata`. 전환 확인 뒤 별도 요청으로 지운다.

## 10. 결정 대기 (운영)

1. ~~경보를 받을 곳~~ → Slack(2026-11-06)
2. Kafka 백업 외부 보관과 자동화
3. config-server 지표 열기
4. 옛 이미지 정리와 vhdx 압축(Docker 정지 필요)
5. ~~Zipkin 저장소~~ → 로그 ES(2026-11-06). 남은 결정: 추적 보존 상한(2GB)·수집 비율
