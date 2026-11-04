# pmart 의 ELK — 상품 검색과 로그 모니터링

- 기준: 2026-11-05. 지금 LAN 에서 돌고 있는 모습을 설명하는 안내서다(결정 문서 아님). 결정은 ADR-0031(Elastic Stack)·ADR-0034(Kafka 이력·재색인).
- 자세한 문서:
  - 상품 검색 전체: `product-search.md`
  - 운영 설정(경보·보존·백업·메모리): `docs/operations/lan-monitoring-and-backup.md`
  - 재색인 절차: `deploy/lan/elastic/REINDEX.md`

## 1. 구성

- **ELK** 는 세 가지를 묶어 부르는 이름이다.
  - **E**lasticsearch: 저장·검색
  - **L**ogstash: 데이터 가공·운반
  - **K**ibana: 화면
- pmart 는 여기에 PC 마다 로그를 모으는 **Filebeat** 를 붙였다.
- pmart 는 **pc4 에 Elasticsearch 하나와 Logstash 하나**를 두고, **두 용도에 같이** 쓴다.
  1. **상품 검색**: 도메인 이벤트 → 검색 색인
  2. **로그 모니터링**: 앱 로그 → 로그 색인 → Kibana
- 지표 감시(Prometheus·Grafana)와 요청 추적(Zipkin)은 ELK 가 아니다.
- 구성요소

| 구성요소 | 버전·위치 | 상품 검색 | 로그 |
|---|---|---|---|
| Elasticsearch | 8.18.0 + analysis-nori, pc4 :9200, 단일 노드, 힙 768m, 상한 1.5 GiB | `products`·`sku-prices` 색인 | `pmart-logs-YYYY.MM.DD` 색인 |
| Logstash | 8.18.0, pc4, 힙 512m, 상한 1.5 GiB | 파이프라인 `search-index` | 파이프라인 `logs`(5044) |
| Kibana | 8.18.0, pc4 :5601 | 거의 안 씀(Dev Tools 로 색인 확인 정도) | 로그 찾기 화면 |
| Filebeat | 8.18.0, PC 마다(`log-shipper`) | — | 컨테이너 로그 수집 |
| es-init | 한 번 실행 | 색인·별칭 생성(없을 때만) | ILM·템플릿·데이터 뷰 |

## 2. 상품 검색

```
product·pricing·product-content·mfc-inventory 서비스 DB
  → Outbox → Debezium(pc3) → Kafka(pc2): commerce.product.v1 · commerce.pricing.v1 · mfc.sku-handling.v1   ← 무제한 보관
  → Logstash "search-index"(pc4)
  → Elasticsearch: products-v5(별칭 products) · sku-prices-v1 · sku-handlings-v1(SKU 별 센터 취급)
  → search-service(읽기만) → api-gateway → 고객 앱
```

| 단계 | 하는 일 |
|---|---|
| **Kafka** | 상품이 바뀐 사실을 사건으로 쌓는다: 버전 공개, 콘텐츠 공개, 판매 상태, 가격 발효, 센터 취급 변경(`SkuHandlingChanged`). 세 토픽은 지우지 않으므로 처음부터 다시 읽으면 색인을 똑같이 다시 만들 수 있다. |
| **Logstash search-index** (`services/search-service/src/main/logstash/search-index.conf`, search-service 소유) | 사건을 하나씩 읽어 색인 문서로 바꾼다. 콘텐츠 사건이면 product-content 공개 카드 API 로 이름·이미지를 채운다. 실패하면 2초 간격으로 2번 더 시도하고, 그래도 실패하면 색인하지 않고 `search-index.DLT` 에 남긴다. |
| | ES 에 쓸 때 Painless 스크립트로 버전을 비교해 더 새로운 것만 반영한다. 판매 상태는 상품 행 버전으로 비교한다. 같은 사건이 다시 오거나 옛 사건이 늦게 와도 결과가 같다. |
| | 작업자 1·배치 1: 가격 사건과 상품 사건이 서로 덮어쓰지 않게 한다. 영구 큐는 256 MB, ES 가 거절한 문서는 Logstash DLQ(64 MB)로 간다. |
| **Elasticsearch** | `products` 는 strict 매핑이다. 상품명은 아래처럼 여러 방식으로 나눠 저장한다. 그 밖에 카테고리 범위, 승인 속성 필터(`그룹.옵션`), 판매 상태, 기본가를 담는다. |
| **search-service** | ES 는 별칭으로만 읽는다(색인은 하지 않는다). 결과에 카테고리 이름 경로와 센터 취급 여부를 붙이고, 필터 이름·순서는 product-service 필터 패널에서 가져온다. |

상품명 필드

| 필드 | 나누는 방식 | 찾는 예 |
|---|---|---|
| `name` | nori 형태소 | "두부를" → 두부 |
| `name.edge` | 앞부분 | 자동완성 |
| `name.partial` | 1~2글자 조각 | 붙임말 "나랑드사이다제로", "미역" → 미역국 |
| `name.compact` | 공백을 모두 뺀 뒤 1~2글자 조각(products-v4~) | "생크림" → "생 크림" |
| `nameChosung`(`.edge`) | 상품명 초성, 공백 뺀 조각(+ 낱말 앞부분, v5) | "ㄷㅂ" → 두부 |

- 순위: 형태소 일치 > 앞부분 일치 > 조각 일치, 낱말 순서가 맞으면(구문) 가산.

- **재색인**:
  - 매핑이 바뀌면 새 색인(`products-vN`)을 빈 채로 만든다.
  - 새 소비 그룹으로 Kafka 를 처음부터 다시 읽어 채운 뒤, 별칭을 한 요청으로 옮긴다. 사건 1440건 기준 약 40초(2026-11-05, v3→v4·v4→v5).
  - 실행은 `deploy/lan/elastic/reindex.sh` 로 한다(status·check·start·wait·switch·finish·rollback·drop).
- **감시**: watch.sh 가 5분마다 지표를 만들고 경보 6개가 본다.
  - 지표: 소비 지연, Logstash 영구 큐, DLQ, ES 거절 수, 마지막 반영 시각
  - 경보: SearchIndexStalled·QueueBacklog·LogstashDown·RejectedByElasticsearch·MetricsStale·DeadLetter
  - 화면: Grafana "PMart 상품 검색"(uid `pmart-search`) — 파이프라인·검색 API·ES·이력 토픽. 경보 발송처(receiver)는 없어 Prometheus·Alertmanager 화면에서만 보인다.

## 3. 로그 모니터링

```
각 PC 컨테이너 로그 → Filebeat(PC 마다) → Logstash "logs"(:5044) → ES pmart-logs-YYYY.MM.DD → Kibana(:5601)
```

| 단계 | 하는 일 |
|---|---|
| **Filebeat** (`deploy/lan/elastic/filebeat.yml`) | Docker 로그 파일에서 앱 JSON 로그(`serviceName` 필드가 있는 것)만 고른다. 로그를 남긴 PC 를 `pc` 필드로 붙인다. 초당 500건 상한, 24시간 지난 파일은 무시, 메모리 큐 4096건. |
| **Logstash logs** (`deploy/lan/elastic/logstash/logs.conf`) | 필요 없는 필드(agent·ecs 등)를 지우고 UTC 날짜별 색인에 넣는다. 거르기와 상한은 Filebeat 에서 한다. |
| **Elasticsearch** | 색인 템플릿 `pmart-logs-*`: 복제본 0, 형식이 틀린 필드는 버리고 문서는 남김, 문자열은 keyword. |
| | 보존: ILM 3일. 여기에 `log-janitor` 가 5분마다 확인해 색인 하나 1 GB·전체 4 GB 를 넘지 않게 지운다. |
| **Kibana** | 데이터 뷰 `pmart-logs-*`(시간 필드 `@timestamp`). 서비스·PC·레벨·`traceId`·`correlationId`(사가 ID)로 거른다. |

- Zipkin 에서 본 traceId 로 그 요청이 거친 모든 서비스의 로그를 찾을 수 있다.
- 지표 감시는 ELK 가 아니다.
  - 숫자 지표: Prometheus(15초 수집) → Grafana·Alertmanager
  - 요청 흐름: Zipkin
- 로그는 "왜 그랬는지"를 보는 용도다.

## 4. 같은 Elasticsearch 를 쓰는 이유와 대가

- **이유**: PC 자원이 제한돼 ES 를 하나만 둔다(pc4 메모리 상한 1.5 GiB).
- **대가**: 로그가 몰리면 검색도 느려질 수 있다. 그래서 다음 장치를 둔다.
  - Filebeat 초당 500건 상한
  - 로그 보존 3일·색인 1 GB·전체 4 GB
  - 색인 자동 생성은 `pmart-logs-*` 와 시스템 색인만 허용한다(`action.auto_create_index`). 매핑 없는 `products` 가 실수로 생기지 않게 하려는 것이다.
  - Logstash 파이프라인을 나눴다: 검색은 작업자 1, 로그는 따로.
- **보안**: 꺼져 있다(LAN 시험망). 운영으로 가면 인증·TLS 와 검색·로그 분리를 다시 정한다.

## 5. 아직 없는 것

- 검색:
  - 동의어·오타 교정, 브랜드, 인기·평점 정렬(정책 결정 필요)
  - SKU 옵션 검색(대표 SKU 만), 부하 시험·품질 평가, 색인별 ES 지표
  - (됨 2026-11-05: 초성 `nameChosung`, Grafana 검색 대시보드 `pmart-search`)
- 로그:
  - Kibana 저장 검색·대시보드
  - 로그 기반 경보(지금 경보는 모두 Prometheus 지표 기준)
- 공통:
  - ES 스냅숏 백업. 검색 색인은 Kafka 재생으로 다시 만들 수 있어 필요 없고, 로그는 3일만 보관한다.
