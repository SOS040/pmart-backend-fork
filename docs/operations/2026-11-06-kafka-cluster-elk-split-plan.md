# Kafka 3노드 KRaft 클러스터 + ELK 분리 — 작업·전환 계획

- 작성: 2026-11-05, 다음 세션에서 작업을 시작한다. LAN 전환은 2026-11-06 예정(시간대는 정하지 않음).
- 상태: **계획.** 결정은 끝났고 코드·설정은 아직 바꾸지 않았다.
- 전제: 개발 단계이고 운영 트래픽이 없다. 그래서 "새로 시작"하되 핵심 이력만 복원한다.

## 0. 작업 위치 (중요)

| 저장소 | 폴더 | 브랜치 |
|---|---|---|
| pmart-backend | `C:\development\pmart-project\pmart-backend-kafka` (git worktree) | `feature/kafka-cluster` (main `954b003` 에서 분기) |
| pmart-config | `C:\development\pmart-project\pmart-backend-kafka\deploy\lan\.config-repo` (worktree) | `feature/kafka-cluster` (main `6cbf609` 에서 분기) |

- `pmart-backend-kafka\deploy\lan\.secrets` 는 원래 폴더(`pmart-backend\deploy\lan\.secrets`)로 가는 junction 이다. 비밀은 복사하지 않았다.
- 두 브랜치 모두 **아직 push 하지 않았다.** push 는 요청할 때 한다.
- Kafka·ELK 코드·스크립트·설정과 **전환 이후 상태를 설명하는 문서**는 worktree 에서만 고친다.
  - 이유: LAN config-server 는 pmart-config `main` 을 읽는다. 새 Kafka 주소가 `main` 에 먼저 가면, 떠 있는 서비스가 재시작하거나 Bus 갱신을 받는 순간 없는 Kafka 를 찾는다.
- `main` 폴더(`pmart-backend`)에서는 지금 상태 문서, 작업 일지, ADR 초안만 다룬다.

## 1. 최종 결정

| 항목 | 결정 |
|---|---|
| Kafka 구성 | **KRaft 클러스터 1개, 노드 3개**(서로 독립된 단일 Kafka 3대가 아님). 각 노드가 브로커+컨트롤러(combined) |
| pc2 (192.168.0.34) | kafka-1 (node 1) |
| pc3 (192.168.0.222) | kafka-2 (node 2) + Debezium Connect + **검색용** Elasticsearch·Logstash |
| pc4 (192.168.0.40) | kafka-3 (node 3) + **로그용** Elasticsearch·Logstash + Kibana |
| 복제 | 모든 토픽 replication factor **3**, `min.insync.replicas=2` |
| 기존 Kafka 이벤트 | 테스트 이벤트라 삭제 허용 |
| 이력 토픽 3개 | `commerce.product.v1`·`commerce.pricing.v1`·`mfc.sku-handling.v1` **백업 후 새 클러스터에 복원**(검색 재색인 원천, ADR-0034) |
| 소비 위치(consumer offset) | 초기화 허용 |
| PostgreSQL | **업무 데이터 유지**(삭제하지 않음) |
| Debezium | Connect 초기화, **PostgreSQL 복제 슬롯(Debezium 슬롯) 29개 삭제 후 재생성**, 커넥터 29개 재등록 |
| Elasticsearch | 검색 색인은 새 검색 ES 에 재생성(Kafka 이력 재생), 기존 로그는 버림(3일 보관분) |
| 모니터링 | **kafka-exporter + Kafka 장애 경보 추가** |
| 롤백 | 기존 Kafka·Connect·ES 볼륨을 지우지 않고 임시 보존 |

- 한 노드가 죽으면 남은 두 노드로 컨트롤러 투표와 쓰기가 계속된다(서비스 무중단).
- 두 노드가 죽으면 멈춘다(컨트롤러 과반 없음, ISR 2 미달).
- 검토했다가 버린 안: 도메인별 독립 단일 KRaft 3대. 한 대가 죽으면 그 도메인 토픽이 멈추고, 서비스마다 다중 바인더가 필요하다.

## 2. 현재 상태 (2026-11-05 확인)

- Kafka: pc2 단일 노드 `apache/kafka:4.1.1`, `KAFKA_NODE_ID: 1`, `KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093`, 광고 주소 `PLAINTEXT://kafka:9092`, 내부 토픽 복제 1, 자동 토픽 생성 켜짐(`deploy/lan/render.sh` 159~181줄).
- 토픽 생성: `deploy/lan/kafka/topics-init.sh` 가 `--replication-factor 1`.
- 접속 주소 `kafka:9092` 를 쓰는 곳:
  - 설정 저장소 `application-lan.yml`(`spring.kafka.bootstrap-servers`)
  - render.sh: Connect `BOOTSTRAP_SERVERS`(203줄), Logstash `PMART_KAFKA_BOOTSTRAP`(407줄), `SPRING_KAFKA_BOOTSTRAP_SERVERS`(563줄)
  - 스크립트 `deploy/lan/kafka/*.sh`(`topic-metrics`·`backup`·`backup-topic`·`restore-topic`), `deploy/lan/elastic/reindex.sh`, `deploy/lan/watch.sh`
- Kafka 사용 서비스:
  - 업무 이벤트 발행 15개, 소비 13개(합집합 18)
  - Debezium 커넥터 29개(pc3 Connect)
  - Logstash 검색 파이프라인 1개
  - Config Bus(소비 그룹 `bus-<앱>-<PC>` 34개)
- PC 자원(Docker 메모리): 세 대 모두 23.5 GiB. 사용량은 pc2 9.0, pc3 7.9, pc4 10.2 GiB. pc4 에 pmart 가 아닌 `cardcapture-backend`(0.8 GiB)가 떠 있다.
- ES: pc4 하나(검색 색인 + 로그). Logstash 하나(파이프라인 `search-index`·`logs`). Kibana pc4.

## 3. 작업 목록 (다음 세션, worktree)

### 3-1. 결정 기록 (`main` 폴더)
- [x] ADR-0037 Kafka 3노드 KRaft 클러스터(복제 3, min ISR 2) — ADR-0034(이력 보관)·0035(Stream 소비)와 연결. 상태 "제안".
- [x] ADR-0038 검색 ES·로그 ES 분리 — ADR-0031 의 "같은 ES 를 두 용도에" 결정을 대체. 상태 "제안".
- [x] ADR README 표

### 3-2. Kafka 클러스터 (`deploy/lan/render.sh`, `placement.conf`)
- [x] 구성요소 `kafka` → `kafka-1`(pc2)·`kafka-2`(pc3)·`kafka-3`(pc4). 각 PC 의 `extra_hosts` 에 다른 노드 이름이 들어가게 한다.
- [x] 노드 설정:
  - `KAFKA_NODE_ID` 1/2/3
  - `KAFKA_PROCESS_ROLES: broker,controller`
  - `KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka-1:9094,2@kafka-2:9094,3@kafka-3:9094`
  - `KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9094`
  - `KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka-N:9092`
- [x] **포트 9094(컨트롤러)도 공개**(`ports: ["9092:9092","9094:9094"]`). 지금은 9092 만 열려 있다. 9093 은 pc4 Alertmanager 가 호스트 포트로 쓰므로 컨트롤러는 9094(ADR-0037 결정 1).
- [x] 클러스터 ID: `render.sh` 상수 `KAFKA_CLUSTER_ID`(비밀 아님, `.secrets` 대신 — ADR-0037 결정 2). 볼륨 이름 `kafkacluster`(옛 `kafkadata` 는 롤백용).
- [x] 내부 토픽:
  - `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 3`
  - `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 3`
  - `KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 2`
  - `KAFKA_MIN_INSYNC_REPLICAS: 2`
  - `KAFKA_DEFAULT_REPLICATION_FACTOR: 3`
- [x] `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"`. **변경**: 업무·heartbeat 토픽은 Connect(`topic.creation.default.*=-1`, 커넥터 템플릿), DLT·Bus 는 Stream 바인더, 특수 설정 토픽만 `topics-init.sh`(ADR-0037 결정 4).
- [x] 메모리 상한: `mem_limit: 2g`(기본 힙 1 GiB + 여유. 지금 사용 1.3~1.5 GiB), healthcheck 는 지금 방식 유지.
- [x] 접속 주소 `kafka:9092` → `kafka-1:9092,kafka-2:9092,kafka-3:9092`: Connect(203줄), Logstash(407줄), `SPRING_KAFKA_BOOTSTRAP_SERVERS`(563줄)
- [x] Debezium Connect 내부 토픽(config·offset·status) 복제 3

- [x] `lan.sh deploy`: 대상 PC 의 Kafka 노드를 모두 먼저 띄우고 → 기다리고 → 한 노드에서 `topics-init.sh` → config-server → 나머지(한 노드만으로는 과반이 안 돼 healthy 불가).
- [x] 토픽 지표 textfile·`metrics/` 는 kafka-1(pc2)에만, `topics-init.sh` 는 세 노드 PC 모두에 복사.

### 3-3. 스크립트 (`deploy/lan/kafka/`, `elastic/`, `watch.sh`)
- [x] `topics-init.sh`: 복제 `RF`(기본 3, 기존 토픽 복제 수가 다르면 멈춤), min ISR 은 브로커 기본 2, 이력 토픽 무제한 보관 유지. 대상은 특수 설정 토픽(이력 3 + `search-index.DLT`)만
- [x] `topic-metrics.sh`: 토픽 크기를 **리더 복제본 기준**으로(파티션마다 가장 큰 복제본, 3브로커 모의 입력으로 확인). 복제 3이면 `kafka-log-dirs` 합이 3배라 용량 경보가 잘못 울린다.
- [x] `backup.sh`·`backup-topic.sh`·`restore-topic.sh`: 컨테이너 `pmart-lan-kafka-1-1`(compose 서비스 kafka-1), `PMART_KAFKA_NODE` 로 다른 노드 선택. 컨테이너 안 localhost:9092 는 그대로(클러스터 조회). `kafka/README.md` 복원 명령도 새 컨테이너 이름
- [x] `reindex.sh`: Kafka 부분(kafka-1 노드·컨테이너). ES·Logstash 이름은 3-4 에서
- [x] `watch.sh`: Kafka 3노드 각각 ApiVersions 점검(placement 구성요소 kafka-1~3), 토픽 지표는 kafka-1
- [x] Debezium: 등록 스크립트는 Kafka 주소를 쓰지 않아 변경 없음(템플릿 topic.creation 은 3-2). 슬롯 정리 `deploy/lan/kafka/debezium-slots.sh list|drop [--execute]`(비활성만 삭제, 슬롯 DB 로 접속) — 2026-11-05 LAN `list` 로 29개 확인

### 3-4. ELK 분리
- [x] 구성요소:
  - `elasticsearch` → `es-search`(pc3)·`es-logs`(pc4)
  - `logstash` → `logstash-search`(pc3, 파이프라인 `search-index`)·`logstash-logs`(pc4, 파이프라인 `logs`, 5044)
  - `es-init` 둘(검색 색인·별칭 / 로그 템플릿·ILM·데이터 뷰)
  - `es-exporter` 둘
  - `log-janitor` → es-logs
  - Kibana → es-logs
- [x] 각 ES 의 `action.auto_create_index`:
  - 검색: 시스템 색인만
  - 로그: `pmart-logs-*`·시스템 색인
- [x] Filebeat(log-shipper) 대상 → `logstash-logs`
- [x] search-service: ES 주소 → `es-search`(설정 저장소 `search-service-lan.yml`) — 3-5 에서
- [x] `search-index.env`(Logstash 그룹·색인 이름), `search-index.conf` 의 ES·Kafka 주소
- [x] 메모리 상한: 지금 ES·Logstash 상한(각 1.5 GiB) 기준
- 구현 메모(2026-11-05):
  - 볼륨 새 이름 `essearch`·`eslogs`·`lssearch`·`lslogs`(옛 `esdata`·`lsdata` 는 pc4 에 롤백용으로 남음). ES 마다 `cluster.name` pmart-search·pmart-logs
  - nori 설치는 es-search 만. `es-init.sh` 는 `PMART_ES_INIT=search|logs|all`(기본 all — 로컬 단일 ES 그대로)
  - Logstash 파이프라인 목록 `pipelines-search.yml`·`pipelines-logs.yml`(옛 `pipelines.yml` 삭제). logstash-search 는 LAN 포트 없음
  - Prometheus `elasticsearch` 작업에 대상 2개, 레이블 `cluster: search|logs`. Grafana 로그 데이터 소스 → `es-logs`(3-6 항목 일부)
  - `watch.sh`: es-search·es-logs 클러스터 상태, logstash-logs 5044, logstash-search 는 SSH 로 API(9600) `status: green`. 검색 지표는 logstash-search·es-search
  - `reindex.sh`: es-search·logstash-search·es-init-search

### 3-5. 설정 저장소 (worktree `.config-repo`)
- [x] `application-lan.yml`: `spring.kafka.bootstrap-servers` 3노드
- [x] search-service ES 주소
- [x] 바꾼 뒤 `deploy/lan/config-copies.sh sync` → `check`
- 결과(2026-11-05): pmart-config `feature/kafka-cluster` `e54f630`(미push). `-lan.yml` 은 시험 사본 대상이 아니라 사본 변경 없음.
- 주의: worktree 에서 `config-copies.sh check` 를 돌리면 api-gateway 사본 2개가 "다름"으로 나온다 — 내용은 같고 줄바꿈만 다르다(pmart-config 의 `api-gateway.yml` 이 CRLF 로 커밋돼 있고, worktree 는 LF 로 체크아웃). main 폴더에서는 같게 나온다. **배포는 main 폴더에서** 한다(`lan.sh deploy` 가 이 검사를 한다).

### 3-6. 관측
- [ ] **kafka-exporter**(예: `danielqsj/kafka-exporter`, 정확한 버전 고정)를 한 곳에 배치하고 Prometheus 수집 대상에 추가
- [ ] 새 경보:
  - 브로커 수 < 3
  - 복제 부족 파티션(under-replicated) > 0
  - 리더 없는 파티션(offline) > 0
  - 컨트롤러 없음
  - 이름·임계값은 alert-rules.yml 규칙에 맞춘다
- [ ] ES 경보(ElasticsearchUnreachable·ClusterRed·HeapHigh)를 클러스터별(`es-search`·`es-logs`)로
- [ ] Grafana:
  - [x] 데이터 소스 `Logs (Elasticsearch)` → `http://es-logs:9200`(3-4 때 함께)
  - 검색 대시보드의 ES 패널 → es-search exporter
- [ ] `promtool check config` / `promtool test rules` 로 검사

### 3-7. 시험 (worktree)
- [ ] `config-copies.sh check`
- [ ] 영향 모듈 check, 전체 `./gradlew check --continue`(공통 코드 변경이 없으면 영향 모듈만)
- [ ] `render.sh` 실행 결과(compose 3개) 검토:
  - 노드 ID·투표자·포트
  - `extra_hosts` 에 kafka-1~3·es-search·es-logs·logstash-* 가 들어갔는지
- [ ] 로컬에서 3노드 KRaft compose 를 띄워 클러스터 형성·복제 3·한 노드 정지 시 쓰기 확인(가능하면)

### 3-8. 문서 (worktree, 전환 이후 상태)
- [ ] `docs/operations/lan-monitoring-and-backup.md`(Kafka·ES 구성, 경보 표, 메모리 상한)
- [ ] `docs/architecture/elk-in-pmart.md`(분리 구조)
- [ ] `deploy/lan/kafka/README.md`, `deploy/lan/elastic/REINDEX.md`
- [ ] 배치도 `docs/architecture/diagrams/lan-placement.mmd/png`, `resilience.md`, `config.md`, `platform-capabilities-code-map.md`

## 4. 배포 전 검증 (전환 당일 시작 전)

- [ ] 세 PC 사이 **9092·9094 연결**: Windows 방화벽 인바운드 규칙, Docker 포트 공개. 예: pc3 → pc2:9094, pc4 → pc3:9094 …
- [ ] 각 PC 디스크 여유(C:)와 Docker 메모리 여유
- [ ] 진행 중 사가 건수(남아 있으면 그 주문은 멈춘 채 남는다 — 확인만)
- [ ] 이미지 빌드·push 준비(Docker Hub 토큰 유효)

## 5. 전환 순서 (2026-11-06)

1. **이력 토픽 백업**: `deploy/lan/kafka/backup.sh` — 상품·가격·센터 취급 3개. 상품 검색 문서 수(`products`·`sku-prices`·`sku-handlings`)도 기록한다.
2. **브랜치 합치기**: pmart-backend·pmart-config 의 `feature/kafka-cluster` → `main`, push. 필요한 이미지 jib 빌드·push.
3. **앱과 Debezium Connect 정지**(config-server·eureka 포함 전체 앱).
4. **기존 Kafka·Connect·ES·Logstash 종료.** 볼륨은 지우지 않고 남긴다(롤백용).
5. **PostgreSQL 복제 슬롯(Debezium 슬롯) 삭제** — `deploy/lan/kafka/debezium-slots.sh drop` 으로 목록 확인 → `drop --execute` → `list` 가 0개: 서비스 DB 29개(pc2·pc3·pc4 Postgres)의 Debezium 슬롯을 `pg_drop_replication_slot` 으로 지운다. 업무 데이터와 outbox 테이블·publication 은 그대로 둔다. 슬롯 이름은 커넥터 설정을 따른다.
6. **KRaft 클러스터 기동**: pc2·pc3·pc4 에 kafka-1~3. 컨트롤러 3·브로커 3, 쿼럼 리더 확인(`kafka-metadata-quorum.sh describe --status`).
7. **토픽 생성·이력 복원**: `topics-init.sh`(복제 3, min ISR 2) → `restore-topic.sh` 로 이력 3개 복원 → 끝 오프셋을 백업과 대조.
8. **Debezium**: Connect 기동(새 내부 토픽, 복제 3) → 커넥터 29개 재등록(새 슬롯 자동 생성) → 모두 RUNNING 확인.
9. **ES 분리 구축**: es-search·es-logs 기동, 각 es-init → logstash-logs 기동.
10. **검색 재색인**: logstash-search(새 소비 그룹)가 이력 토픽을 처음부터 읽어 es-search 에 색인 → 문서 수를 1단계 기록과 대조(`reindex.sh` 절차).
11. **서비스 기동**: config-server → eureka → 나머지 앱. Prometheus 수집 대상 UP 확인.
12. **통합 확인**:
    - 주문·결제 1건 E2E(결제 진입 → 승인 → 주문 확정 → 장바구니 정리)
    - 상품 검색(초성·필터·센터 취급)
    - 승인 흐름 1건(승인 → 상품·가격 반영)
    - Config Bus 설정 갱신 1건
    - Kibana·Grafana 로그 수집
    - kafka-exporter 지표
13. **장애 시험**: 브로커 1대 정지 → 발행·소비·사가가 계속 도는지(쓰기 성공, 소비 지연 없음), 복제 부족 경보가 울리는지 → 다시 기동 → 복제가 따라잡고 경보가 풀리는지.
14. **정리**: 작업 일지·문서 갱신, 메모리 갱신. 옛 Kafka·ES 볼륨 삭제는 확인 뒤 별도 요청으로.

## 6. 롤백

- 조건: 6~11단계에서 복구가 어려운 문제가 생겼을 때.
- 방법:
  1. 두 저장소 `main` 을 합치기 전 커밋(pmart-backend `954b003` 이후 main, pmart-config `6cbf609`)으로 되돌린다.
  2. render → 옛 Kafka·Connect·ES·Logstash 를 **보존한 볼륨으로** 다시 올린다.
  3. 커넥터를 다시 등록한다. 복제 슬롯을 지웠으므로 옛 커넥터도 새 슬롯으로 다시 시작한다.
- 롤백하면 전환 중 생긴 이벤트는 잃는다(개발 환경이라 허용).

## 7. 남은 결정

- 전환 시간대(전체 중단 수십 분)
- ~~kafka-exporter 배치 PC~~ → **pc4 확정**(2026-11-05)
- Alertmanager 수신처(지금 `none`): Slack 웹훅 등, **2026-11-06 에 정한다**(사용자 2026-11-05). 붙이면 실제 발송되므로 사용자 확인 후, URL 은 `.secrets` 에 사용자가 직접 입력
