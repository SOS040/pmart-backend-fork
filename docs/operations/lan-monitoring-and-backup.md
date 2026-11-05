# LAN 운영 설정 — 감시·지표·경보·추적·로그·Kafka 백업

- 기준: 2026-11-05. LAN(PC 4대)에서 지금 실제로 돌고 있는 운영 설정을 한곳에 모은 안내서다. 결정 문서가 아니다.
- 정본은 각 설정 파일이다. 값을 바꾸면 그 파일을 고치고, 이 문서도 같은 커밋에서 맞춘다.
- 관련 결정:
  - ADR-0030: 지표·로그·디스크
  - ADR-0031: Elastic Stack
  - ADR-0034: Kafka 이력 보관

## 1. 한눈에

| 무엇 | 도구 | 어디서 | 정본 파일 |
|---|---|---|---|
| 살아 있는지 점검·자동 재시작 | `watch.sh` (5분마다) | PC1 작업 스케줄러 "PMart LAN Watch" | `deploy/lan/watch.sh` |
| 지표 수집 | Prometheus v3.5.0 | pc4 :9090 | `render.sh` (prometheus.yml 생성) |
| 경보 규칙 23개 | Prometheus → Alertmanager v0.28.1 | pc4 :9093 | `deploy/lan/monitoring/alert-rules.yml`, `alertmanager.yml` |
| 화면 | Grafana 12.0.2 | pc4 :3000 | `deploy/lan/monitoring/grafana/` (대시보드 `pmart-overview.json`, 상품 검색 `pmart-search.json`). 데이터 원본: Prometheus·Alertmanager·**로그(Elasticsearch `pmart-logs-*`)·Zipkin**(2026-11-05 — Explore 에서 로그 검색·추적 보기, 로그 traceId → 추적, 추적 → 같은 traceId 로그) |
| 요청 추적 | Zipkin 3 (메모리 저장) | pc4 :9411 | `render.sh` zipkin 블록 |
| 앱 로그 | Filebeat → Logstash → Elasticsearch → Kibana | 각 PC → pc4 | `deploy/lan/elastic/` |
| 디스크 여유 | node-exporter(host-disk) | 각 PC :9100 | `render.sh` host-disk 블록 |
| Kafka 이력 백업 | `backup.sh` (수동) | PC1 `D:\pmart-backups\kafka` | `deploy/lan/kafka/` |

PC 배치: `deploy/lan/placement.conf`
- pc2 (192.168.0.34): Kafka, 상품·주문·결제 서비스
- pc3 (192.168.0.222): Connect, 센터·배송 서비스
- pc4 (192.168.0.40): 게이트웨이, 인증, ES·Logstash·Prometheus·Grafana
- PC1: 빌드와 감시

## 2. 감시 — `watch.sh` (PC1, 5분마다)

- **점검 대상**: 각 구성요소를 다른 PC에서 접속하듯이 실제 프로토콜로 확인한다. PC 안의 healthcheck는 정상인데 LAN에서는 안 닿는 경우를 잡기 위해서다.

  | 대상 | 확인 방법 |
  |---|---|
  | 앱 | `/actuator/health` |
  | PostgreSQL | SSLRequest |
  | Kafka | ApiVersions |
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
  - 두 파일을 pc2의 `pmart-lan/metrics/`에 올리고, pc2의 host-disk(node-exporter textfile 수집기)가 Prometheus로 내보낸다.
  - 파일은 **UTF-8 + LF**여야 한다. Windows 기본값(cp949·CRLF)으로 쓰면 node-exporter가 파일 전체를 거부한다(2026-11-04).
- **상태·로그**: `~/.pmart-lan-watch/` (`watch.log`)
- **점검만 하고 복구는 안 할 때**: `deploy/lan/watch.sh --dry-run`

## 3. 지표 — Prometheus

- 수집 주기 15초, 규칙 평가도 15초.
- 보존은 **7일 또는 2 GB** 중 먼저 닿는 쪽까지(`--storage.tsdb.retention.time=7d`, `retention.size=2GB`). 메모리 상한 1 GiB.
- 수집 대상 39개: 앱마다 `/actuator/prometheus`, host-disk 3대, es-exporter, Prometheus 자신.
  - **앱은 Eureka 등록부에서 찾는다**(2026-11-05 변경, LAN 반영 전 — 아래 참고). Prometheus 가 30초마다 Eureka(`http://<eureka PC>:8761/eureka`, 아이디 `pmart`, 비밀번호는 `build/pc4/monitoring/eureka-password` 파일 — Git 제외)에서 앱 목록을 받아, 등록된 IP + 메타데이터 `management.port` 를 수집한다. `job` = 앱 이름 소문자, `instance` = IP:포트, `pc` = IP 로 붙인 PC 이름, `discovery="eureka"`.
  - 고정 목록으로 남은 것: eureka-server(자기 자신을 등록하지 않음), host-disk·es-exporter(Eureka 클라이언트 아님), Prometheus 자신. Eureka 서버 주소는 render.sh 가 placement.conf 에서 eureka-server 위치를 읽어 적는다.
  - 서비스를 옮기거나 늘려도 Prometheus 재배포가 필요 없다(Eureka 에 다시 등록하면 30초 안에 반영). Eureka 를 못 읽으면 Prometheus 는 마지막으로 찾은 목록을 유지한다.
  - 검증(2026-11-05, 로컬 Prometheus 를 LAN Eureka 에 연결): 대상 39개 모두 UP, job·instance·pc 가 그때 LAN Prometheus(고정 목록)와 같음. 설정 `promtool check config` 통과.
  - 코드: `deploy/lan/render.sh` Prometheus 설정 생성 부분(`eureka_sd_configs`), compose 의 `eureka-password` 마운트.
  - 예외: config-server는 actuator가 health·info만 열려 있어 수집하지 않는다(열지 결정 대기).
- 규칙 파일 다시 읽기: 배포 뒤 `curl -X POST http://192.168.0.40:9090/-/reload` (`--web.enable-lifecycle`).
- 직접 만드는 지표:

  | 파일 | 만드는 곳 | 지표 |
  |---|---|---|
  | `kafka_topics.prom` | `deploy/lan/kafka/topic-metrics.sh` (Kafka 컨테이너 안) | 토픽 크기·시작/끝 오프셋·무제한 보관 여부·검색 색인 소비 지연 |
  | `search_index.prom` | `deploy/lan/elastic/search-index-metrics.py` | Logstash 살아 있음·영구 큐·DLQ·ES 거절 수·마지막 색인 반영 시각 |

## 4. 경보 규칙 (23개, `alert-rules.yml`)

> **경보는 아직 아무에게도 전달되지 않는다.** `alertmanager.yml`의 받는 곳이 `none`이다. 지금은 Alertmanager(`http://192.168.0.40:9093`)와 Grafana 화면에서만 볼 수 있다.
>
> 채널을 붙이려면 Slack(또는 Discord) Incoming Webhook 주소를 `deploy/lan/.secrets/` 에 파일로 두고 Alertmanager가 그 파일을 읽게 한다. 주소는 Git·설정 파일·이미지에 넣지 않는다. 결정 대기.

| 묶음 | 경보 | 조건 | 심각도 |
|---|---|---|---|
| 앱 | AppDown | 2분 넘게 지표 수집 실패 | critical |
| | AppMissingFromDiscovery | 최근 1시간 안에 수집하던 앱이 Eureka 등록부에서 사라짐(죽은 앱은 30초 뒤 등록부에서 지워져 AppDown 이 못 잡는다). 1시간 뒤 저절로 풀림, 그 뒤는 watch.sh 가 본다 | critical |
| | Http5xxRateHigh | 5분간 5xx 비율 > 5% (요청이 있을 때) | warning |
| 메시징 | DeadLetterOpen | 서비스 DB에 처리 못 한 메시지(OPEN)가 있음 | critical |
| | ConsumerLagHigh | 소비 지연 > 500건이 10분 | warning |
| Elasticsearch | ElasticsearchUnreachable | 2분 응답 없음 | critical |
| | ElasticsearchClusterRed | 5분 RED | critical |
| | ElasticsearchHeapHigh | 힙 > 90%가 10분 | warning |
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
- **저장은 메모리**다. 재시작하면 쌓인 추적이 모두 사라진다.
- 설정 (`render.sh` zipkin 블록, 2026-11-05 커밋 9f3bfd8)

  | 항목 | 값 | 이전 |
  |---|---|---|
  | 힙 (`JAVA_OPTS`) | `-Xms256m -Xmx256m -XX:+ExitOnOutOfMemoryError` | 160m (이미지 기본) |
  | 보관 span 수 (`MEM_MAX_SPANS`) | 50,000 | 500,000 (기본) |
  | 컨테이너 메모리 상한 | 512 MiB | 없음 |

- **2026-11-05 사고**:
  - 약 20시간 동안 113번 재시작했다(`OutOfMemoryError: Java heap space`).
  - 메모리 저장소가 160 MB 힙을 채워 죽고, `unless-stopped`로 다시 뜨기를 반복했다.
  - 컨테이너가 바로 다시 떠서 AppDown·watch.sh에는 잡히지 않았다.
- **지켜볼 것**:
  - 재배포 24분 뒤 사용량이 426 / 512 MiB였다.
  - 힙은 256 MB로 막혀 있다. 그래도 힙 밖 메모리(메타스페이스·스레드·버퍼)까지 512 MiB를 넘으면 Docker가 강제로 종료한다. 이때는 OOM 로그가 남지 않는다.
  - 확인 명령: `ssh pmart-pc4 "docker inspect pmart-lan-zipkin-1 --format '{{.RestartCount}} {{.State.OOMKilled}}'"`
  - 재시작 횟수가 다시 늘면 다음 중 하나를 고른다: 상한을 768 MiB로 올림, span 수를 더 줄임, 수집 비율을 낮춤.

## 6. 앱 로그 — Filebeat → Logstash → Elasticsearch → Kibana

```
각 PC 컨테이너 로그 ─▶ log-shipper(Filebeat) ─5044─▶ Logstash(logs 파이프라인) ─▶ ES pmart-logs-YYYY.MM.DD ─▶ Kibana(:5601)
```
- **Filebeat** (PC마다, `deploy/lan/elastic/filebeat.yml`):
  - 대상: Docker 컨테이너 로그 파일. `serviceName` 필드가 있는 앱 JSON 로그만 남긴다.
  - 초당 500건 상한, 24시간 지난 파일은 무시, 메모리 큐 4096건.
  - `pc` 필드를 붙인다. 메모리 상한 256 MiB.
- **Logstash** (pc4, 메모리 상한 1.5 GiB, 힙 512m): 파이프라인 두 개
  - `logs`: 로그를 `pmart-logs-YYYY.MM.dd`(UTC 날짜)로 보낸다.
  - `search-index`: 상품 검색 색인. `docs/architecture/product-search.md` 참고.
  - 영구 큐 256 MB, DLQ 64 MB.
- **Elasticsearch** (pc4, 8.18.0, 단일 노드):
  - 힙 768m, 메모리 상한 1.5 GiB. 보안은 꺼져 있다(LAN 시험망).
  - 색인 자동 생성은 `pmart-logs-*`와 시스템 색인만 허용한다(매핑 없는 products가 생기지 않게).
- **로그 보존**:
  - ILM `pmart-logs`가 3일 뒤 지운다.
  - `log-janitor`가 5분마다 확인해 색인 하나가 1 GB를 넘으면 지우고, 전체가 4 GB를 넘으면 오래된 것부터 지운다.
- **준비 작업**: `es-init`(한 번 실행)이 검색 색인, ILM, 로그 색인 템플릿, Kibana 데이터 뷰 `pmart-logs-*`를 만든다.

## 7. Kafka 이벤트 이력 보관과 백업 (ADR-0034)

- **보관**:
  - `commerce.product.v1`·`commerce.pricing.v1` 두 토픽만 무제한 보관한다(`retention.ms=-1`, `retention.bytes=-1`, `cleanup.policy=delete`).
  - 검색 색인을 다시 만드는 방법이 이 두 토픽의 전체 재생이기 때문이다.
  - `lan.sh deploy <Kafka PC>`가 매번 `kafka/topics-init.sh`로 다시 적용한다. 이미 적용돼 있으면 아무것도 바꾸지 않는다.
  - 나머지 토픽은 기본 보관(7일)이다.
- **백업** (수동, PC1 Git Bash):
  ```bash
  deploy/lan/kafka/backup.sh /d/pmart-backups/kafka <사유>
  ```
  - Kafka는 읽기만 한다. 소비 그룹도 만들지 않는다.
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
  - PC1의 C:와 D:는 **같은 SSD**다. 그 디스크가 고장 나면 원본(pc2 Kafka)과 백업 중 백업만 잃지만, pc2까지 함께 잃으면 복구할 수 없다.
  - 외부 매체나 다른 저장소로 옮길지, 정기 백업을 자동화할지는 결정 대기다.

## 8. 컨테이너 메모리 상한 (pc4 중심)

| 컨테이너 | 상한 | 비고 |
|---|---|---|
| elasticsearch | 1.5 GiB | 힙 768m |
| logstash | 1.5 GiB | 힙 512m |
| kibana | 1 GiB | |
| prometheus | 1 GiB | |
| 업무 앱 | 768 MiB | 앱마다 |
| zipkin | 512 MiB | 힙 256m (2026-11-05) |
| log-shipper (Filebeat) | 256 MiB | PC마다 |
| config-relay | 128 MiB | |
| host-disk, es-exporter | 64 MiB | |
| log-janitor | 32 MiB | |

- 2026-11-05 측정, pc4:
  - PC 전체 메모리 31.9 GB, 그중 비어 있는 것 10.0 GB
  - Docker에 할당된 메모리 23.5 GiB, 컨테이너 사용량 합 약 10.1 GiB
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
  - Kafka 이력: 무제한이지만 하루 수 MB 수준이다.
  - 옛 이미지와 vhdx: 가장 크다. 정리는 결정 대기다.

## 10. 결정 대기 (운영)

1. 경보를 받을 곳: Slack·Discord·이메일 또는 지금처럼 화면만
2. Kafka 백업 외부 보관과 자동화
3. config-server 지표 열기
4. 옛 이미지 정리와 vhdx 압축(Docker 정지 필요)
5. Zipkin을 지금처럼 메모리 저장으로 둘지, ES 같은 저장소로 옮길지(재시작하면 추적이 사라짐)
