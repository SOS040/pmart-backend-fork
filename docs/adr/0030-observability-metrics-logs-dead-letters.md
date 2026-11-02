# ADR-0030 관측: 지표는 Prometheus·Grafana·Alertmanager, 로그는 Fluent Bit → OpenSearch, 미처리 메시지는 서비스별 dead_letter 표

- 상태: 승인 (2026-11-02, 사용자 요청 "1단계부터 3단계까지 진행" — 1단계 = 모니터링·DLQ 운영·로그 수집)
- 관련: `docs/architecture/tech-stack.md`(모니터링 Prometheus + Grafana), ADR-0009(봉투·inbox), ADR-0021(OpenSearch), ADR-0026(내부 호출 무인증), `deploy/lan/README.md`

## 배경
- 서비스 34개가 LAN PC 3대에 흩어져 있다. 지표(`/actuator/prometheus`)는 모든 앱이 이미 내지만 모으는 곳이 없고, 로그는 각 PC 의 `docker logs` 로만 본다(JSON, traceId 포함).
- Kafka 소비 실패는 재시도 3회 뒤 `{topic}.DLT` 로 간다(common-messaging). 그러나 알림·조회·재처리 수단이 없어, 예를 들어 환불 결과(`RefundResult`)가 DLT 에 빠지면 아무도 모른다.
- 비교 대상 Eum 은 Filebeat(게이트웨이 파일 로그만) + 앱이 Logstash 로 TCP 직접 전송(나머지) + Elasticsearch·Kibana.

## 결정
1. **지표**: LAN 에 Prometheus(15초 수집) · Alertmanager · Grafana 를 둔다(pc4). 대상은 `placement.conf` 의 모든 Spring 앱 `<PC IP>:<포트>/actuator/prometheus` 를 render.sh 가 생성한다. 서비스 코드는 바꾸지 않는다.
   - **보존: 7일 또는 2GB 중 먼저 닿는 쪽**(`--storage.tsdb.retention.time=7d --storage.tsdb.retention.size=2GB`, 실행 중 `/api/v1/status/runtimeinfo` = "1w or 2GiB" 확인). 2GB 는 **이 Prometheus 저장소 전체**(블록 + WAL + 메모리 매핑 청크)이며 지우는 것은 영속 블록만이다. 블록 단위(가장 오래된 것부터 통째로)로 지우고 정리는 백그라운드라, 압축 중에는 잠깐 넘을 수 있다(공식 문서 storage — 2GB 는 디스크 여유의 80~85% 이하로 두라는 권고, pc4 C: 여유 약 100GB 라 충분).
   - **디스크 감시**: PC 마다 `host-disk`(node-exporter v1.9.1, filesystem 수집기만)가 Docker Desktop 이 마운트한 Windows C:(`/run/desktop/mnt/host/c`, 9p, 읽기 전용)의 실제 크기·여유를 낸다(PC1 에서 Windows 값과 일치 확인). render 가 prometheus 배치 시 모든 PC 에 자동으로 붙인다.
   - 알림 규칙(`deploy/lan/monitoring/alert-rules.yml`): 앱 응답 없음, 미처리 메시지(dead letter) 열림, 소비 지연(lag), HTTP 5xx 비율, **C: 여유 15% 미만(10분) · 10GB 미만(5분)**.
   - **알림 전송 채널은 아직 두지 않는다**(Slack 등은 사용자 요청 때). Alertmanager·Grafana 화면에서 본다.
2. **로그**: 앱은 지금처럼 stdout JSON 만 낸다(앱에서 로그 서버로 직접 보내지 않는다 — 수집 서버 장애가 앱에 번지지 않게). **PC 마다 Fluent Bit** 가 Docker 컨테이너 로그 파일을 읽어 **기존 OpenSearch**(ADR-0021 과 같은 노드)의 `pmart-logs-YYYY.MM.DD` 에 넣고, **OpenSearch Dashboards** 로 본다.
   - **디스크 상한**(2026-10-25 pc3 Debezium Connect 로그 142GB 사고 교훈, `docs/worklog/2026-10-27.md` — 장애 때 로그가 폭증하고, 상한 없는 곳은 언젠가 찬다):
     - 앱 JSON 로그(`serviceName` 있는 것)만 보낸다. kafka·connect·opensearch·수집기 자신의 로그는 `docker logs`(20MB×3)로만 → 인프라 로그 폭주가 들어오지 않고, OpenSearch 오류 로그가 OpenSearch 로 되돌아가는 순환이 없다.
     - 초당 평균 500건(5초 창)을 넘으면 버린다(원본은 `docker logs`).
     - 보내지 못한 로그의 디스크 버퍼는 500MB 상한(넘으면 오래된 것부터 버림), 재시도 10회.
     - 색인(날짜마다 하나)은 **3일이 지나거나 1GB 를 넘으면 삭제**(ISM, 사용자 결정 2026-11-02). 동시에 있는 색인은 오늘~3일 전 최대 4개 → **로그 전체 약 4GB 이하**(ISM 검사 주기 약 5분 동안은 조금 넘을 수 있다). 같은 날 1GB 를 넘겨 지워지면 그날 색인이 새로 생긴다(앞부분 로그는 잃는다). 로컬 OpenSearch 2.19.1 로 정책 생성·재실행 갱신·새 색인 자동 적용·크기 초과 삭제를 확인했다.
     - **OpenSearch 의 디스크 경고(85%·90%·95%, 95% 에서 색인 읽기 전용)는 여기서 보호 장치가 아니다.** OpenSearch 가 보는 디스크는 Docker Desktop 가상 디스크(`docker_data.vhdx`, 최대 약 1TB — pc4 에서 `df` 1007G·사용 2%)라, 실제 Windows C:(pc4 여유 약 102GB)가 먼저 찬다. C: 가 차면 Docker 전체가 멈춘다(2026-10-25 pc3). 그래서 위 상한들과 C: 감시(결정 1)가 실제 보호 장치다. 가상 디스크는 안에서 지워도 줄지 않는다.
     - 한 번 돌고 끝나는 `logs-init` 도 Docker 로그 상한(1MB).
   - Elasticsearch·Kibana·Logstash 는 들이지 않는다(엔진 하나 — 상품 검색과 같은 OpenSearch). Filebeat 대신 Fluent Bit: 최신 Filebeat 는 OpenSearch 출력을 지원하지 않는다.
   - 운영(AWS)에서는 같은 구조로 수집기만 CloudWatch Logs·관리형 OpenSearch 로 바꿀 수 있다 — 운영 실행 환경을 정할 때 다시 본다.
3. **미처리 메시지(dead letter)**: common-messaging 의 Kafka 오류 처리가 재시도를 다 쓰면 지금처럼 `{topic}.DLT` 에 보내고, **같은 서비스 DB 의 `dead_letter` 표에도 기록**한다(공통 마이그레이션 V0_0_3). 소유는 그 메시지를 처리하지 못한 서비스다(새 서비스를 두지 않는다).
   - 지표: `pmart_messaging_dead_letters_open`(열린 건수, DB 기준 — 재기동해도 유지) · `pmart_messaging_dead_letters_total`(기록 수).
   - API(서비스마다, `/api/v1/internal/dead-letters`): 목록·상세, **재처리**(원래 토픽에 같은 키·헤더·본문으로 다시 발행 — 다른 구독자는 inbox 가 같은 eventId 를 버린다), **폐기**(사유 필수). 상태 `OPEN → REPLAYED / DISCARDED`. 재처리해도 다시 실패하면 새 행이 생긴다(원 행 id 를 `replay_of` 로 잇는다).
   - 내부 경로라 게이트웨이를 거치지 않는다(ADR-0026). 백오피스 화면은 backoffice-bff 가 조합해 나중에 붙인다.
4. **단일 노드 OpenSearch 의 복제본**: 우리 색인(products·sku-prices·pmart-logs)은 복제본 0 으로 만든다. OpenSearch 2.19 에 기본으로 켜진 Query Insights 가 매일 `top_queries-날짜` 색인을 **복제본 1 로 직접 지정해** 만들어(색인 틀로는 덮을 수 없음 — 로컬 확인) 복제본이 UNASSIGNED, 클러스터 yellow 였다(2026-11-02 LAN: 3개). `logs-init` 이 `search.insights.top_queries.exporter.type=none` 으로 기록 색인을 만들지 않게 하고(상위 쿼리는 메모리에서 계속 조회 가능), 남은 `top_queries-*` 는 복제본 0 으로 바꾼다.
5. 구성 이미지(버전 고정): `prom/prometheus:v3.5.0`, `prom/alertmanager:v0.28.1`, `grafana/grafana:12.0.2`, `opensearchproject/opensearch-dashboards:2.19.1`, `fluent/fluent-bit:4.0.3`, `prom/node-exporter:v1.9.1`, `curlimages/curl:8.14.1`.

## 대안
- **Eum 방식(앱 → Logstash TCP + Filebeat 일부)**: 경로가 둘이고 앱이 로그 서버에 의존한다.
- **Loki + Grafana(로그)**: 화면이 하나로 합쳐지지만 저장소가 하나 더 생긴다. 이미 OpenSearch 가 있다.
- **미처리 메시지 전용 서비스(DLT 전 토픽 구독)**: 한 곳에서 보지만, 남의 메시지를 재처리하는 서비스가 생기고 서비스 경계가 하나 늘어난다. 지금은 Grafana 에서 서비스별 건수를 모아 본다.

## 결과
- Grafana 하나에서 전 서비스 상태·지연·미처리 메시지를, OpenSearch Dashboards 에서 traceId 로 전 서비스 로그를 본다.
- 대가: pc4 에 컨테이너 4개(메모리 약 1.5GB), PC 마다 Fluent Bit·node-exporter 1개씩. 디스크는 최대 약 Prometheus 2GB + 로그 색인 4GB + PC 마다 버퍼 500MB(pc4 C: 여유의 약 6%). 인프라 로그·폭주 구간 로그는 OpenSearch 에서 빠진다(그때는 `docker logs`). OpenSearch 가 검색·로그를 함께 지므로 로그가 많아지면 검색에 영향 — 그때 노드를 나눈다.
- 재처리는 원래 토픽 전체 구독자에게 다시 간다. 모든 구독자가 inbox(또는 버전 비교, ADR-0021)로 멱등이라는 전제(ADR-0009)에 기댄다.
- `dead_letter` 에 메시지 본문(봉투)이 남는다. 개인정보가 담긴 사건이 있을 수 있으므로 상세 API 는 내부망 전용이고 로그에 본문을 찍지 않는다. 보존 기간은 정하지 않았다(`[결정 필요]`).
