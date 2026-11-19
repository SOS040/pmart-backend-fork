# 상품 검색 — 현재 구현 정리

- 기준: 2026-11-05(LAN 색인 `products-v5` 전환·확인 뒤 — 띄어쓰기·초성·센터 취급·구문/초성 순위). 지금 어떻게 동작하는지 한곳에 모은 안내서이고, 설계 결정 문서는 아니다.
- 상품이 추가되면 퍼지는 구조·재색인이 필요한 때: `docs/architecture/product-add-and-reindex.md`.
- 2026-11-20 갱신: 색인 **products-v6**(ADR-0072 순위 신호 — 별점·리뷰 수·베이지안 점수·최근 30일 판매, storefront 가 셈), 정렬 POPULAR·RATING·REVIEW_COUNT 실제 동작(검색 계약 0.3.0).
- 2026-11-19 갱신: 계약 0.2.0(최근 검색어·인기 키워드, 2026-11-17), v3·v4 정리됨, 리뷰·평점 집계 현황, 성능 분석·알려진 문제 링크(§11).
- 정본(바뀌면 이 문서보다 우선):
  - 결정: ADR-0031(Elastic Stack, 색인은 Logstash 만), ADR-0034(이력 토픽 무제한 보관, 재색인 = Kafka 전체 재생), ADR-0021(DB 없는 소비자의 버전 비교 멱등)
  - 계약: `contracts/openapi/search-service.yaml`(0.2.0), `contracts/events/ProductVersionPublished.v1.json` 등
  - 서비스 규칙: `services/search-service/CLAUDE.md`
  - 재색인 절차: `deploy/lan/elastic/REINDEX.md`
  - 사용자 메모(구현 순서 7단계)와의 대조: `docs/architecture/search-comparison.md`

## 1. 한눈에

```
product-service ─┐  ProductVersionPublished(rowVersion·filterOptions) · ProductSaleStateChanged
product-content ─┼─▶ outbox ─▶ Debezium ─▶ Kafka commerce.product.v1 ─┐
pricing-service ─┘                         Kafka commerce.pricing.v1 ─┤ (무제한 보관)
mfc-inventory ── SkuHandlingChanged ─────▶ Kafka mfc.sku-handling.v1 ─┤ (무제한 보관, 2026-11-05)
                                                                      ▼
                                   Logstash search-index 파이프라인(소비 그룹 search-index-logstash-v5)
                                     ├─ Painless 버전 비교 → ES products · sku-prices · sku-handlings(모두 별칭)
                                     ├─ 콘텐츠 카드 보강(product-content 공개 API, 실패 시 재시도) + 상품명 초성
                                     └─ 끝내 실패 → Kafka search-index.DLT + 경보
                                                                      ▼
고객 앱 ─▶ api-gateway ─▶ search-service(조회만) ─▶ ES 별칭 products ─▶ products-v5
                              ├─ 카테고리 이름 경로: product-service 공개 트리(5분 사본)
                              └─ 필터 이름·순서: product-service /categories/{id}/filters
```

- **검색 서비스는 DB 가 없고 색인에 쓰지 않는다.** 색인은 Logstash 만 쓴다(ADR-0031).
- **업무 테이블 CDC 가 아니라 도메인 이벤트**를 받는다. 검색에 새 값(브랜드·판매량 등)을 넣으려면 그 값이 먼저 이벤트에 실려야 한다(센터 취급은 2026-11-05 `SkuHandlingChanged` 로 해결).

## 2. 인프라 (LAN)

| 항목 | 값 |
|---|---|
| Elasticsearch | `es-search` — 검색 전용(ADR-0038, 로그 ES 와 분리). 8.18.0, **단일 노드**(복제본 없음), 보안 끔 — LAN 시험망이라 의도. pc3 `192.168.0.222:9200` |
| 형태소 분석기 | analysis-nori. 컨테이너 시작 때 없으면 설치(새로 만들 때 인터넷 필요) |
| 색인·별칭 | `products` → **`products-v5`**, `sku-prices` → `sku-prices-v1`, `sku-handlings` → `sku-handlings-v1`. search-service 는 별칭으로만 읽는다. 2026-11-04 v2 → v3(v2 삭제), 2026-11-05 v3 → v4 → v5(초성 앞부분 필드, 같은 날) |
| Logstash | `logstash-search` — 8.18.0, pc3, LAN 포트 없음. 파이프라인 `search-index`(작업자 1·배치 1), 영구 큐 256MB, Logstash DLQ 64MB |
| 색인 대상·그룹 설정 | `deploy/lan/elastic/search-index.env` → `render.sh` 가 Logstash 환경변수로 넣는다 |
| 색인 정의 | `services/search-service/src/main/resources/elasticsearch/*.json`. es-init-search(pc3)과 search-service 가 같은 파일로 만든다. 정의에 별칭을 넣지 않고 **별칭이 없을 때만** 붙인다 |
| 로컬 | `deploy/local/docker-compose.yml`(ES·es-init). 이전 `esdata` 볼륨이 있으면 별칭이 옛 색인(v2~v4)에 남는다 — 볼륨을 지우고 새로 띄운다 |

## 3. 색인 문서 (`products-v5`, strict 매핑)

| 필드 | 뜻 | 채우는 사건 |
|---|---|---|
| `productId` | 문서 id | 모두 |
| `productVersion` | 상품 버전 — 옛 사건 무시 기준 | ProductVersionPublished |
| `primaryCategoryId`·`categoryIds`·`categoryScope` | 카테고리(조상 범위 포함 — 상위 카테고리로 찾으면 하위 상품도 나옴) | ProductVersionPublished |
| `representativeSku` | 대표 SKU 하나(사건의 SKU 목록 첫 번째 — 지금 LAN 상품은 모두 상품 하나에 SKU 하나) | ProductVersionPublished |
| `carriedMfcIds` | 대표 SKU 를 켠 센터 목록(2026-11-05) | SkuHandlingChanged(`sku-handlings` 갱신 뒤 `_update_by_query`), 상품 사건은 `sku-handlings` 를 찾아 붙임 |
| `filters` | 승인 속성 필터 `"그룹.옵션"`(keyword) | ProductVersionPublished `filterOptions` |
| `saleState`·`saleStateAtMs`·`saleStateRowVersion` | 판매 상태와 비교 기준(§4) | ProductVersionPublished·ProductSaleStateChanged |
| `name`·`imageUrl`·`contentVersion`·`contentPublishedAtMs` | 고객용 이름·이미지(게시본만) | ProductContentPublished → 카드 API 보강 |
| `nameChosung` | 상품명 초성(한글 음절 → ㄱ~ㅎ, 그 밖의 글자는 공백, 2026-11-05) | ProductContentPublished(Logstash 가 이름에서 계산) |
| `basePrice`·`priceVersion` | 기본가 | PriceVersionEffective(`sku-prices` 갱신 뒤 `_update_by_query` 로 퍼뜨림) |
| `updatedAtMs` | Logstash 가 마지막으로 반영한 시각 | 모두 |

- 상품명 분석: `name`(nori 형태소) · `name.edge`(앞부분 일치) · `name.partial`(1~2글자 n-gram) · `name.compact`(공백을 모두 뺀 뒤 1~2글자 n-gram). `nameChosung` 도 공백을 뺀 n-gram, `nameChosung.edge` 는 낱말 앞부분(순위 가산용, v5).
- `sku-handlings-v1`(SKU 별): `centers`(센터별 {켬, 행 버전} — 색인하지 않는 객체), `carriedMfcIds`, `updatedAtMs`. 센터별로 버전이 큰 사건만 반영한다.
- **검색 대상**: 이름과 상품 버전이 모두 있고 판매중지(`SALE_STOPPED`)가 아닌 문서. 콘텐츠가 아직 게시되지 않은 상품은 이름이 없어 검색되지 않는다.
- 적용된 색인의 매핑은 고치지 않는다. 필드를 더하면 새 색인(`-v6`)을 만들고 재색인한다(§7).

## 4. 색인 규칙 (Logstash `search-index.conf`)

- **멱등**: 재전달·재생해도 결과가 같다. 문서마다 버전을 비교해 옛 사건은 무시한다(상품 `productVersion`, 가격 `priceVersion`, 콘텐츠 `contentVersion`, 센터 취급은 센터별 행 버전).
- **센터 취급**(2026-11-05): mfc-inventory 가 취급이 바뀔 때만 `SkuHandlingChanged` 를 낸다(레코드 없음 = OFF). 사건 도입 전 취급은 `POST /internal/sku-handling-republications` 로 같은 버전을 다시 알려 연결했다(LAN 275건). 취급 사건과 상품 사건은 어느 쪽이 먼저 와도 결과가 같다.
- **판매 상태**(2026-11-04): 상품 행 버전으로 비교한다(행 버전 = product 행이 바뀔 때마다 오르는 카운터 — PVP `rowVersion` 과 PSSC `version` 이 같은 카운터).
  - 사건에 행 버전이 있으면 저장된 것보다 **클 때만** 반영.
  - 행 버전이 없는 옛 사건(전환 전 발행분)은 행 버전 기준 상태가 아직 없을 때만, 시각이 같거나 늦으면 반영(재생 호환).
  - storefront-service 의 진열 사본(`display_product.sale_state_row_version`)도 같은 규칙이다.
- **콘텐츠 보강 실패**(2026-11-04): 카드 API 를 http 자동 재시도 + 2초 간격 2번 더 시도한다. 그래도 실패하면 **색인하지 않고** `search-index.DLT` 에 남긴다.
  - DLT 레코드 키 = 상품 id, 본문 `pmartDeadLetter{op, id, tags, topic, partition, offset, at}` 이 원래 사건 위치를 담는다. 토픽 보관 30일.
  - 지표 `pmart_kafka_topic_end_offset{topic="search-index.DLT"}`(watch.sh 5분마다), 경보 `SearchIndexDeadLetter`(30분 안에 늘면).
- **작업자 1·배치 1**: 같은 상품의 상품·가격 사건이 섞여 반영되지 않게 한 건씩 처리한다. 처리량은 낮지만 지금 규모(상품 275개, 전체 재생 사건 1440건 약 40초)에서는 문제없다. 상품이 만 건 단위로 늘면 상품 id 파티션 등 순서 보장 설계와 함께 다시 본다.
- **영구 큐**: Kafka 오프셋 커밋이 ES 쓰기보다 먼저 일어난다. 그래서 "소비 지연 0" 만으로 반영 완료를 판단하지 않는다(큐도 비어야 함).
- 파이프라인을 바꾸면 `SearchFlowIT`(실제 Kafka·ES·Logstash Testcontainers)로 확인하고, 문법은 `logstash -t -f search-index.conf` 로 먼저 검사한다.

## 5. 검색 API (`/api/v1/search/...`)

### `GET /search/products`
| 파라미터 | 동작 |
|---|---|
| `q` | 상품명 검색(최대 100자). 찾기: nori 형태소가 모두 맞거나("두부를" → 두부) n-gram 조각이 모두 있거나(붙임말 "나랑드사이다제로", "미역" → 미역국) 공백을 뺀 조각이 모두 있거나("생크림" ↔ "생 크림"). 점수: 형태소(가중 3) > 앞부분(2) > 조각, BM25, 낱말이 검색어 순서로 붙어 있으면 가산(구문, slop 1). **한글 자음만 2자 이상이면 초성 검색**(`nameChosung`, 낱말 앞부분 초성 일치 가산 — 예 "ㄷㅂ" → 두부가 위) |
| `categoryId` | 그 카테고리와 하위 전체 |
| `minPrice`·`maxPrice` | 기본가 범위(원). 최소 > 최대면 400. 범위를 주면 가격 없는 상품은 빠진다 |
| `filters` | 반복 파라미터 `그룹.옵션`(예 `seed-storage.냉장`). **같은 그룹 안 OR, 그룹끼리 AND**. 최대 30개, 형식이 틀린 값은 버린다. 응답 `appliedFilters` = 실제로 적용한 값 |
| `mfcId`·`includeNotCarried` | 센터 취급 여부 `carried`(색인 `carriedMfcIds` 에 그 센터가 있는지, 레코드 없음 = false). 기본(`includeNotCarried=false`)은 **ES 필터로 거른다**(2026-11-05 — 실시간 가용 조회와 BR-47 "더 읽어 채우기"는 없앰). 품절은 색인하지 않는다 |
| `sort` | RELEVANCE(기본, 검색어 있을 때 점수순)·NEWEST·PRICE_ASC·PRICE_DESC. POPULAR·RATING 은 원천이 없어 관련도로 대신하고 응답 `appliedSort` 로 알린다 |
| `size`·`cursor` | 커서 페이지. `total` 은 조건(미취급 거르기 포함)에 맞는 정확한 건수 |

- 판매중지는 결과에서 뺀다. 일시품절은 남기고 `saleState` 로 표시한다.
- 결과의 `categoryPath` 는 대표 카테고리 이름 경로(product-service 공개 트리 5분 사본, 실패해도 검색은 성공 — 빈 목록).

### `GET /search/filters`
- 지금 조건(`q`·`categoryId`)에 걸리는 상품의 `filters` 를 ES terms 집계로 세어 `attributes[{key, label, multiSelect, values[{value, label, count}]}]` 로 돌려준다.
- 이름(label)·선택 방식(TOGGLE 이면 단일 선택)·옵션 순서는 product-service 필터 패널에서 가져온다. 패널은 노드에 직접 붙은 것만 내려오므로(상속 없음) **그 카테고리와 조상의 패널을 가까운 것부터 합치고, `categoryId` 가 없으면 최상위 노드들의 패널**을 합친다(패널마다 5분 사본). 하위 노드에만 붙은 그룹을 카테고리 없이 부르면 이름을 못 찾아 키 그대로 보인다(2026-11-04).
- 건수가 0 인 옵션은 나오지 않는다(집계 기반).

### 그 밖
- `GET /search/suggestions?q=&limit=` 자동완성: 상품 검색과 같은 질의로 찾은 상품명을 중복 없이 관련도 순으로(앞부분 일치 덕분에 "두" → 두부, 초성 "ㅅㅋㄹ" → 생크림…). 전용 suggester 는 없다. 응답의 인기 키워드(popular)는 아래 검색 기록으로(0.2.0).
- `GET /internal/index-status` 색인 신선도: 원천 사건별 마지막 반영 시각·지연(사람이 조회 — 이 API 는 경보와 연결되지 않고, 경보는 watch.sh 지표로 한다 §8). 센터 취급(`sku-handlings`) 원천은 아직 넣지 않았다.
- **최근 검색어·인기 키워드(0.2.0, 2026-11-17 사용자 결정 — Commerce §6.7·6.9)**: search-service 가 쓰는 유일한 색인 두 개.
  - 검색자는 `HMAC-SHA256(pmart.search.searcher-hash-key, 사용자 종류:주체 ID)` 해시만 남긴다(고객 ID 원문 없음). 키가 없으면 기록하지 않고 최근 검색어 조회는 CONFIG_REQUIRED.
  - 최근 검색어 `GET·DELETE /me/recent-searches`: 고객 토큰만(고객 검색 때 자동 기록). 색인 `search-recent`(→ `search-recent-v1`), 같은 검색어는 덮어써 맨 위로, `max-items`·`retention` 안에서.
  - 인기 키워드: 고객·비회원 검색을 `search-log`(→ `search-log-v1`, 검색 하나당 문서)에 남기고 `window` 안 결과 있는 검색을 검색어별로 세어 서로 다른 검색자 `min-searchers` 명 이상만, 숨김 목록 제외. 기록은 비동기(검색 응답을 기다리게 하지 않음).
  - 설정 pmart-config `search-service.yml` `pmart.search.recent.*`·`pmart.search.popular.*`(없으면 기록하지 않음 — 기본값으로 대신하지 않는다). 매일 04:17 `SearchHousekeeping` 이 오래된 문서를 지운다.
  - 자세한 규칙: `services/search-service/CLAUDE.md`.
- `/internal/reindex-requests` 는 **501**(재색인은 절차로 한다, §7).

## 6. 필터 데이터의 흐름
1. product-service: 카테고리 필터 패널(`filter_group`·`filter_option`·`category_filter`)과 상품별 옵션(`product_filter_option`). 상품 등록·변경 초안의 `filterOptions` 로 넣고 PRODUCT 승인으로 반영된다.
2. 승인 반영 때 ProductVersionPublished 에 `filterOptions[{filterGroupId, option}]` 를 싣는다.
3. Logstash 가 `"그룹.옵션"` 으로 바꿔 색인 `filters` 에 넣는다.
- 2026-11-04 LAN: 필터 그룹은 `seed-storage`(보관방법: 냉장·냉동·실온, 카테고리 t01·t02) 하나, 옵션이 붙은 상품은 시험으로 넣은 두부(SEED-001, 냉장) 하나뿐이다. **필터가 실제로 쓸모 있으려면 상품 등록 때 옵션을 채워야 한다**(일괄 등록 양식·백오피스 화면).
- 이전에 발행된 사건에는 `filterOptions` 가 없어서, 재생만으로는 채워지지 않는다. 상품 변경 승인이 새로 나와야 한다.

## 7. 재색인 (`deploy/lan/elastic/REINDEX.md`)
- 실행: `deploy/lan/elastic/reindex.sh`(check·start·wait·switch·finish·rollback·drop) — 단계마다 조건을 확인하고 멈춘다(2026-11-05).
- 방법: **새 소비 그룹으로 세 이력 토픽을 처음부터 재생** → 새 색인 채움 → 완료 확인 → 별칭 원자 교체. 이벤트는 사라지지 않고(무제한 보관), 검색은 교체 순간까지 이전 색인을 읽는다.
- 완료 조건(스크립트 `wait` 가 확인): 소비 지연 0, Logstash 영구 큐 비어 있음, `search-index.DLT` 새 레코드 없음, 건수 ≥ 이전 색인(재생 중 생긴 상품은 새 색인에만), 전 문서 대조(`updatedAtMs` 제외).
- 사전 확인(`check`): 정의 파일·`SearchIndex.INDICES`, 새 색인·그룹 없음, ES green, 보관·ES 경보 없음, Logstash 구독 토픽이 Kafka 에 모두 있음, 지연 0·큐 0. 시작 전에 Kafka 백업을 자동으로 남긴다.
- 주의:
  - `search-index.env` 를 **render 전에** 새 색인·새 그룹으로 바꾼다(새 Logstash 설정이 옛 strict 색인에 쓰면 실패) — `start` 가 한다.
  - 전환 뒤 env 의 색인은 별칭으로 되돌리고(`finish`), 그룹은 새 값으로 **사람이 커밋**한다.
  - `lan.sh deploy` 의 scp 는 파일을 지우지 않는다 — `finish` 가 Elasticsearch PC 의 옛 정의 파일을 지운다(남으면 es-init 이 옛 색인을 다시 만든다).
  - search-service 새 이미지는 `switch` 뒤에 배포한다(새 필드를 쓰는 쿼리가 옛 색인에서는 맞지 않는다 — 예 mfcId 거르기).
  - 되돌림은 `rollback`(별칭만 되돌림)보다, 원인을 고친 뒤 새 색인으로 다시 재생(약 40초)하는 것이 기본이다. 옛 색인은 전환 뒤 사건을 받지 않아 금방 낡는다.
- 일부만 실패(DLT 몇 건)면 §3 부분 재처리: 콘텐츠 재게시, 또는 Logstash 를 멈추고 오프셋을 DLT 의 원래 offset 으로 되돌림.
- 실행 기록:
  - 2026-11-04 v2 → v3(사건 1162건 약 15초, 전 문서 대조, 같은 날 v2 삭제)
  - 2026-11-05 v3 → v4(reindex.sh 첫 실행, 사건 1440건 약 40초, 전 문서 대조 차이 없음)
  - 2026-11-05 v4 → v5(초성 앞부분 필드, 같은 절차로 멈춤 없이). v3·v4 는 이제 없다 — 2026-11-19 검색 ES(pc3) 색인 목록은 `products-v5`·`sku-prices-v1`·`sku-handlings-v1`·`search-recent-v1`·`search-log-v1` 뿐(ADR-0038 검색 ES 분리 때 v5 만 옮겨진 것으로 보임)

## 8. 감시
| 대상 | 있는 것 | 없는 것 |
|---|---|---|
| Elasticsearch | es-exporter 지표, 경보 `ElasticsearchUnreachable`·`ElasticsearchClusterRed`·`ElasticsearchHeapHigh` | 색인별 지표(노드 합계만) |
| 이력 토픽 | 보관 설정·시작 오프셋·증가량 지표와 경보, 측정 멈춤 경보 `KafkaTopicMetricsStale` | 외부 매체 백업 자동화(백업은 수동 `deploy/lan/kafka/backup.sh`) |
| 색인 파이프라인 | 소비 지연·Logstash 영구 큐·Logstash DLQ·ES 거절·마지막 반영 시각 지표(watch.sh 5분마다, 2026-11-04). 경보 `SearchIndexStalled`(지연 > 0 인데 15분 넘게 반영 없음)·`SearchIndexQueueBacklog`·`SearchIndexLogstashDown`·`SearchIndexRejectedByElasticsearch`·`SearchIndexDeadLetter`·`SearchIndexMetricsStale`. 신선도 API | 5분 표본이라 짧은 멈춤은 못 본다 |
| search-service | 앱 공통 지표, `AppDown`·`Http5xxRateHigh` | 검색 지연·0건 비율 같은 품질 지표 |
| 경보 수신 | Prometheus·Alertmanager 화면 | 수신처 없음(Slack 은 명시 요청 때만) |

- 화면: Grafana **PMart 상품 검색**(uid `pmart-search`, `deploy/lan/monitoring/grafana/dashboards/pmart-search.json`, 2026-11-05) — 파이프라인 상태 6개, 토픽별 지연·처리량, 검색 API 요청·응답 시간·오류, ES 쿼리·힙, 이력 토픽 크기.
- 지표 경로: `deploy/lan/watch.sh`(PC 1, 5분마다) → 소비 지연은 `kafka/topic-metrics.sh`(그룹 = `search-index.env`), Logstash 통계·마지막 반영 시각은 `elastic/search-index-metrics.py` → Kafka PC(pc2) `pmart-lan/metrics/*.prom` → host-disk(node-exporter textfile). 그래서 지표의 `pc` 라벨은 pc2 다.
- 2026-11-04 사고: 작업 사본 줄바꿈이 CRLF 가 되어 watch.sh 가 컨테이너로 보낸 `topic-metrics.sh` 가 실패, 토픽 지표가 약 1시간 멈춤(`KafkaTopicMetricsStale` 로 발견). 컨테이너로 보내는 스크립트는 LF 여야 한다(`.gitattributes` 가 저장소는 LF 로 지킨다).

## 9. 시험
| 시험 | 확인하는 것 |
|---|---|
| `SearchFlowIT`(search-service, 실제 Kafka·ES·Logstash) | 이벤트 → 색인 → 검색 전 구간, 형태소·붙임말·부분 검색, 가격 범위, 센터 취급(사건 순서 뒤바뀜·옛 버전·재발행·모르는 센터·total), 필터 OR·AND·appliedFilters·`/search/filters`(조상·최상위 패널 이름), 행 버전 판매 상태, 콘텐츠 보강 실패 → DLT, 띄어쓰기 변형, 초성 |
| `SkuHandlingFlowIT`(mfc-inventory-service) | 취급 변경 → SkuHandlingChanged(바뀔 때만·버전), 재발행, 내부 경로 권한 |
| `HomeFlowIT`(storefront-service) | 진열 사본의 행 버전 판매 상태 규칙 |
| search-service 단위 시험 | 정렬·커서·필터 파싱·초성 검색어 판정(`SearchRules`) |
| LAN 확인(2026-11-04) | 생크림 일시품절 → 판매중(ES·검색·storefront, 행 버전 2·3), 두부 필터 옵션 승인 → `filters=seed-storage.냉장` 1건·`냉동` 0건·`/search/filters` 건수 |
| LAN 확인(2026-11-05) | "생크림" 5건·"생 크림" 30건(생크림 상품이 상위), 초성 "ㅅㅋㄹ" 21건, MFC-SEED-01 취급 275건 전부 carried=true·다음 커서, 모르는 센터 0건(total 0)·includeNotCarried=true 면 275건 carried=false, mfcId 없으면 carried=null |

## 10. 아직 없는 것
| 항목 | 막혀 있는 이유 · 필요한 것 |
|---|---|
| 브랜드 필터 | 상품 모델·계약·업무 원문에 브랜드가 없다. product-service 모델·PVP·일괄 등록 양식·백오피스 화면부터(정책 결정 필요) |
| 동의어·오타 교정 — **ADR-0073 으로 결정·구현(2026-11-20, LAN 배포 전)** | 단계 A(products-v7 동의어 검색 분석기·오타 사전·교정 0건/적음/자동완성·0건 검색어 API)·B(승인 `SEARCH_SYNONYM` — MD 요청·운영 관리자 승인 → search-service 가 버전 비교로 반영·회신) 코드 끝. 남은 것: LAN 배포(products-v7 재색인), 백오피스 화면(C), 고객 앱 문구(D), 처음 동의어 목록(E) |
| 랭킹(인기·별점·전환율·광고) — **인기순·별점순·리뷰 많은 순 정렬은 2026-11-20 됨(ADR-0072)**, 기본 순위에 섞기·전환율·광고는 아직 | 판매량 집계 없음(OrderConfirmed 구독으로 가능), 리뷰는 있음(review-service, 평점 집계 product-content `GET /product-contents/{id}/rating-summary` 0.2.1 — 2026-11-19) 하지만 검색 색인·순위에는 아직 별점·리뷰 수가 없다(색인하려면 새 색인 `-v6`), 노출·클릭 로그 없음, 광고 도메인 없음. 집계 기간·가중치는 승인 설정(Commerce 원문 1051·1082행) |
| 여러 SKU 상품의 취급 판정 | 지금은 대표 SKU 기준. 상품 하나에 SKU 가 여러 개 생기면 "하나라도 취급"으로 바꿀지 결정(`sku-handlings` 는 SKU 별이라 그대로 쓸 수 있다). cdc-inventory 취급도 사건이 없다 |
| SKU 옵션 검색 | 색인에 대표 SKU 하나뿐. SKU 목록(nested)·바코드·규격 색인 필요 |
| 부하 시험·검색 품질 평가 | 도구(k6 등)·정답 검색어 세트(_rank_eval) — 새 도구 도입이라 결정 필요 |

## 11. 성능·알려진 문제 (2026-11-19)
- 분석: `docs/operations/2026-11-19-search-performance-analysis.md` — 지금 규모(상품 약 500개)에서 서버 안 약 10ms, 사용자가 겪는 30~40ms 는 대부분 네트워크.
- 알려진 문제(1순위, `docs/operations/pc1-todo.md` "상품 검색 개선" — ①②③ 끝남):
  - ~~깊은 커서 500~~ — **2026-11-19 고침**: 1만 이상 위치 커서는 400, 창 너머 다음 커서 없음.
  - ~~카테고리 경로 캐시 동기 갱신·필터 패널 차례로 읽기~~ — **2026-11-19 고침(배포 19:45)**: 만료 사본을 그대로 쓰고 뒤에서 한 번만 다시 읽음, 사본 없는 실패 30초 기억, 없는 패널 병렬(`CategoryTreeClient`). 처음(재시작 직후)만 기다린다.
  - ~~`ensureIndices()` 가 요청 경로에서 색인 10개 확인~~ — **2026-11-19 고침**: 시작 때 뒤에서 한 번, 요청은 자기 색인만.
- ~~관측 빈틈: ES 호출 지표·응답 시간 히스토그램 없음~~ — **2026-11-19 밤 구현**(`pmart.search.es` 지표·span, 응답 시간 버킷 → p95). PC 3 ES·Logstash 메모리 97% 는 힙 밖·파일 캐시(강제 종료 없음) — 늘면 한도 올리기.
- "상품 상세가 느리다"는 검색과 별개다(설명 이미지 원본 디코딩) — `docs/handoff/clients/customer-app/2026-11-19-request-detail-image-performance.md`.
