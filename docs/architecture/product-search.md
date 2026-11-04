# 상품 검색 — 현재 구현 정리

- 기준: 2026-11-04(LAN 색인 `products-v3` 전환·확인 뒤). 지금 어떻게 동작하는지 한곳에 모은 안내서이고, 설계 결정 문서는 아니다.
- 정본(바뀌면 이 문서보다 우선):
  - 결정: ADR-0031(Elastic Stack, 색인은 Logstash 만), ADR-0034(이력 토픽 무제한 보관, 재색인 = Kafka 전체 재생), ADR-0021(DB 없는 소비자의 버전 비교 멱등)
  - 계약: `contracts/openapi/search-service.yaml`(0.1.1), `contracts/events/ProductVersionPublished.v1.json` 등
  - 서비스 규칙: `services/search-service/CLAUDE.md`
  - 재색인 절차: `deploy/lan/elastic/REINDEX.md`
  - 사용자 메모(구현 순서 7단계)와의 대조: `docs/architecture/search-comparison.md`

## 1. 한눈에

```
product-service ─┐  ProductVersionPublished(rowVersion·filterOptions) · ProductSaleStateChanged
product-content ─┼─▶ outbox ─▶ Debezium ─▶ Kafka commerce.product.v1 ─┐
pricing-service ─┘                         Kafka commerce.pricing.v1 ─┤ (무제한 보관)
                                                                      ▼
                                   Logstash search-index 파이프라인(소비 그룹 search-index-logstash-v3)
                                     ├─ Painless 버전 비교 → ES products(별칭) · sku-prices(별칭)
                                     ├─ 콘텐츠 카드 보강(product-content 공개 API, 실패 시 재시도)
                                     └─ 끝내 실패 → Kafka search-index.DLT + 경보
                                                                      ▼
고객 앱 ─▶ api-gateway ─▶ search-service(조회만) ─▶ ES 별칭 products ─▶ products-v3
                              ├─ 카테고리 이름 경로: product-service 공개 트리(5분 사본)
                              ├─ 필터 이름·순서: product-service /categories/{id}/filters
                              └─ 센터 취급 여부: mfc-inventory /internal/availability(mfcId 를 줄 때)
```

- **검색 서비스는 DB 가 없고 색인에 쓰지 않는다.** 색인은 Logstash 만 쓴다(ADR-0031).
- **업무 테이블 CDC 가 아니라 도메인 이벤트**를 받는다. 검색에 새 값(브랜드·판매량·센터 취급 등)을 넣으려면 그 값이 먼저 이벤트에 실려야 한다.

## 2. 인프라 (LAN)

| 항목 | 값 |
|---|---|
| Elasticsearch | 8.18.0, **단일 노드**(복제본 없음), 보안 끔 — LAN 시험망이라 의도. pc4 `192.168.0.40:9200` |
| 형태소 분석기 | analysis-nori. 컨테이너 시작 때 없으면 설치(새로 만들 때 인터넷 필요) |
| 색인·별칭 | `products` → **`products-v3`**, `sku-prices` → `sku-prices-v1`. search-service 는 별칭으로만 읽는다. 2026-11-04 v2 → v3 전환, v2 삭제 |
| Logstash | 8.18.0, pc4. 파이프라인 `search-index`(작업자 1·배치 1), 영구 큐 256MB, Logstash DLQ 64MB |
| 색인 대상·그룹 설정 | `deploy/lan/elastic/search-index.env` → `render.sh` 가 Logstash 환경변수로 넣는다 |
| 색인 정의 | `services/search-service/src/main/resources/elasticsearch/*.json`. es-init(pc4)과 search-service 가 같은 파일로 만든다. 정의에 별칭을 넣지 않고 **별칭이 없을 때만** 붙인다 |
| 로컬 | `deploy/local/docker-compose.yml`(ES·es-init). 2026-11-04 이전 `esdata` 볼륨이 있으면 별칭이 v2 에 남는다 — 볼륨을 지우고 새로 띄운다 |

## 3. 색인 문서 (`products-v3`, strict 매핑)

| 필드 | 뜻 | 채우는 사건 |
|---|---|---|
| `productId` | 문서 id | 모두 |
| `productVersion` | 상품 버전 — 옛 사건 무시 기준 | ProductVersionPublished |
| `primaryCategoryId`·`categoryIds`·`categoryScope` | 카테고리(조상 범위 포함 — 상위 카테고리로 찾으면 하위 상품도 나옴) | ProductVersionPublished |
| `representativeSku` | 대표 SKU 하나 | ProductVersionPublished |
| `filters` | 승인 속성 필터 `"그룹.옵션"`(keyword) | ProductVersionPublished `filterOptions` |
| `saleState`·`saleStateAtMs`·`saleStateRowVersion` | 판매 상태와 비교 기준(§4) | ProductVersionPublished·ProductSaleStateChanged |
| `name`·`imageUrl`·`contentVersion`·`contentPublishedAtMs` | 고객용 이름·이미지(게시본만) | ProductContentPublished → 카드 API 보강 |
| `basePrice`·`priceVersion` | 기본가 | PriceVersionEffective(`sku-prices` 갱신 뒤 `_update_by_query` 로 퍼뜨림) |
| `updatedAtMs` | Logstash 가 마지막으로 반영한 시각 | 모두 |

- 상품명 분석: `name`(nori 형태소) · `name.edge`(앞부분 일치) · `name.partial`(1~2글자 n-gram).
- **검색 대상**: 이름과 상품 버전이 모두 있고 판매중지(`SALE_STOPPED`)가 아닌 문서. 콘텐츠가 아직 게시되지 않은 상품은 이름이 없어 검색되지 않는다.
- 적용된 색인의 매핑은 고치지 않는다. 필드를 더하면 새 색인(`-v4`)을 만들고 재색인한다(§7).

## 4. 색인 규칙 (Logstash `search-index.conf`)

- **멱등**: 재전달·재생해도 결과가 같다. 문서마다 버전을 비교해 옛 사건은 무시한다(상품 `productVersion`, 가격 `priceVersion`, 콘텐츠 `contentVersion`).
- **판매 상태**(2026-11-04): 상품 행 버전으로 비교한다(행 버전 = product 행이 바뀔 때마다 오르는 카운터 — PVP `rowVersion` 과 PSSC `version` 이 같은 카운터).
  - 사건에 행 버전이 있으면 저장된 것보다 **클 때만** 반영.
  - 행 버전이 없는 옛 사건(전환 전 발행분)은 행 버전 기준 상태가 아직 없을 때만, 시각이 같거나 늦으면 반영(재생 호환).
  - storefront-service 의 진열 사본(`display_product.sale_state_row_version`)도 같은 규칙이다.
- **콘텐츠 보강 실패**(2026-11-04): 카드 API 를 http 자동 재시도 + 2초 간격 2번 더 시도한다. 그래도 실패하면 **색인하지 않고** `search-index.DLT` 에 남긴다.
  - DLT 레코드 키 = 상품 id, 본문 `pmartDeadLetter{op, id, tags, topic, partition, offset, at}` 이 원래 사건 위치를 담는다. 토픽 보관 30일.
  - 지표 `pmart_kafka_topic_end_offset{topic="search-index.DLT"}`(watch.sh 5분마다), 경보 `SearchIndexDeadLetter`(30분 안에 늘면).
- **작업자 1·배치 1**: 같은 상품의 상품·가격 사건이 섞여 반영되지 않게 한 건씩 처리한다. 처리량은 낮지만 지금 규모(상품 275개, 전체 재생 약 15초)에서는 문제없다. 상품이 만 건 단위로 늘면 상품 id 파티션 등 순서 보장 설계와 함께 다시 본다.
- **영구 큐**: Kafka 오프셋 커밋이 ES 쓰기보다 먼저 일어난다. 그래서 "소비 지연 0" 만으로 반영 완료를 판단하지 않는다(큐도 비어야 함).
- 파이프라인을 바꾸면 `SearchFlowIT`(실제 Kafka·ES·Logstash Testcontainers)로 확인하고, 문법은 `logstash -t -f search-index.conf` 로 먼저 검사한다.

## 5. 검색 API (`/api/v1/search/...`)

### `GET /search/products`
| 파라미터 | 동작 |
|---|---|
| `q` | 상품명 검색(최대 100자). 찾기: nori 형태소가 모두 맞거나("두부를" → 두부) n-gram 조각이 모두 있거나(붙임말 "나랑드사이다제로", "미역" → 미역국). 점수: 형태소(가중 3) > 앞부분(2) > 조각, BM25 |
| `categoryId` | 그 카테고리와 하위 전체 |
| `minPrice`·`maxPrice` | 기본가 범위(원). 최소 > 최대면 400. 범위를 주면 가격 없는 상품은 빠진다 |
| `filters` | 반복 파라미터 `그룹.옵션`(예 `seed-storage.냉장`). **같은 그룹 안 OR, 그룹끼리 AND**. 최대 30개, 형식이 틀린 값은 버린다. 응답 `appliedFilters` = 실제로 적용한 값 |
| `mfcId`·`includeNotCarried` | 센터 취급 여부 `carried` 표시. 기본(`includeNotCarried=false`)은 미취급이 확인된 상품을 빼고 **모자란 만큼 다음 문서로 채운다**(최대 5번, BR-47). 조회 실패는 품절로 단정하지 않고 `carried=null` |
| `sort` | RELEVANCE(기본, 검색어 있을 때 점수순)·NEWEST·PRICE_ASC·PRICE_DESC. POPULAR·RATING 은 원천이 없어 관련도로 대신하고 응답 `appliedSort` 로 알린다 |
| `size`·`cursor` | 커서 페이지. `total` 은 색인 일치 건수(미취급 포함). 미취급을 거를 때는 첫 페이지에서 끝까지 읽은 경우만 실제 건수 |

- 판매중지는 결과에서 뺀다. 일시품절은 남기고 `saleState` 로 표시한다.
- 결과의 `categoryPath` 는 대표 카테고리 이름 경로(product-service 공개 트리 5분 사본, 실패해도 검색은 성공 — 빈 목록).

### `GET /search/filters`
- 지금 조건(`q`·`categoryId`)에 걸리는 상품의 `filters` 를 ES terms 집계로 세어 `attributes[{key, label, multiSelect, values[{value, label, count}]}]` 로 돌려준다.
- `categoryId` 를 주면 product-service 필터 패널에서 이름(label)·선택 방식(TOGGLE 이면 단일 선택)·옵션 순서를 가져온다. **`categoryId` 없이 부르면 이름을 못 찾아 label 이 키 그대로**다(예 `seed-storage`).
- 건수가 0 인 옵션은 나오지 않는다(집계 기반).

### 그 밖
- `GET /search/suggestions?q=&limit=` 자동완성: 상품 검색과 같은 질의로 찾은 상품명을 중복 없이 관련도 순으로(앞부분 일치 덕분에 "두" → 두부). 전용 suggester·인기 검색어는 없다.
- `GET /internal/index-status` 색인 신선도: 원천 사건별 마지막 반영 시각·지연(사람이 조회 — 경보와 연결되지 않음).
- 최근 검색어·`/internal/reindex-requests` 는 **501**(재색인은 절차로 한다, §7).

## 6. 필터 데이터의 흐름
1. product-service: 카테고리 필터 패널(`filter_group`·`filter_option`·`category_filter`)과 상품별 옵션(`product_filter_option`). 상품 등록·변경 초안의 `filterOptions` 로 넣고 PRODUCT 승인으로 반영된다.
2. 승인 반영 때 ProductVersionPublished 에 `filterOptions[{filterGroupId, option}]` 를 싣는다.
3. Logstash 가 `"그룹.옵션"` 으로 바꿔 색인 `filters` 에 넣는다.
- 2026-11-04 LAN: 필터 그룹은 `seed-storage`(보관방법: 냉장·냉동·실온, 카테고리 t01·t02) 하나, 옵션이 붙은 상품은 시험으로 넣은 두부(SEED-001, 냉장) 하나뿐이다. **필터가 실제로 쓸모 있으려면 상품 등록 때 옵션을 채워야 한다**(일괄 등록 양식·백오피스 화면).
- 이전에 발행된 사건에는 `filterOptions` 가 없어서, 재생만으로는 채워지지 않는다. 상품 변경 승인이 새로 나와야 한다.

## 7. 재색인 (`deploy/lan/elastic/REINDEX.md`)
- 방법: **새 소비 그룹으로 두 이력 토픽을 처음부터 재생** → 새 색인 채움 → 완료 확인 → 별칭 원자 교체. 이벤트는 사라지지 않고(무제한 보관), 검색은 교체 순간까지 이전 색인을 읽는다.
- 완료 조건 4개: 소비 지연 0, Logstash 영구 큐 비어 있음, `search-index.DLT` 새 레코드 없음, 건수 일치(+ 표본 대조).
- 주의:
  - `search-index.env` 를 **render 전에** 새 색인·새 그룹으로 바꾼다(새 Logstash 설정이 옛 strict 색인에 쓰면 실패).
  - 전환 뒤 env 의 색인은 별칭으로 되돌리고, 그룹은 새 값으로 커밋한다.
  - `lan.sh deploy` 의 scp 는 파일을 지우지 않는다 — Elasticsearch PC 의 옛 정의 파일을 지워야 es-init 이 옛 색인을 다시 만들지 않는다.
  - 되돌림은 옛 색인 대신 원인을 고친 뒤 새 색인으로 다시 재생(약 15초)하는 것이 기본이다. 옛 색인은 전환 뒤 사건을 받지 않아 금방 낡는다.
- 일부만 실패(DLT 몇 건)면 §3 부분 재처리: 콘텐츠 재게시, 또는 Logstash 를 멈추고 오프셋을 DLT 의 원래 offset 으로 되돌림.
- 실행 기록: 2026-11-04 v2 → v3(사건 1162건 약 15초, 전 문서 대조, 같은 날 v2 삭제).

## 8. 감시
| 대상 | 있는 것 | 없는 것 |
|---|---|---|
| Elasticsearch | es-exporter 지표, 경보 `ElasticsearchUnreachable`·`ElasticsearchClusterRed`·`ElasticsearchHeapHigh` | Grafana 검색 대시보드 |
| 이력 토픽 | 보관 설정·시작 오프셋·증가량 지표와 경보, 측정 멈춤 경보 `KafkaTopicMetricsStale` | 외부 매체 백업 자동화(백업은 수동 `deploy/lan/kafka/backup.sh`) |
| 색인 파이프라인 | DLT 증가 경보 `SearchIndexDeadLetter`, 신선도 API | **Logstash 자체 지표**(소비 지연·영구 큐·Logstash DLQ)가 Prometheus 에 없다 — `ConsumerLagHigh` 는 Spring 앱만 |
| search-service | 앱 공통 지표, `AppDown`·`Http5xxRateHigh` | 검색 지연·0건 비율 같은 품질 지표 |
| 경보 수신 | Prometheus·Alertmanager 화면 | 수신처 없음(Slack 은 명시 요청 때만) |

- 2026-11-04 사고: 작업 사본 줄바꿈이 CRLF 가 되어 watch.sh 가 컨테이너로 보낸 `topic-metrics.sh` 가 실패, 토픽 지표가 약 1시간 멈춤(`KafkaTopicMetricsStale` 로 발견). 컨테이너로 보내는 스크립트는 LF 여야 한다(`.gitattributes` 가 저장소는 LF 로 지킨다).

## 9. 시험
| 시험 | 확인하는 것 |
|---|---|
| `SearchFlowIT`(search-service, 실제 Kafka·ES·Logstash) | 이벤트 → 색인 → 검색 전 구간, 형태소·붙임말·부분 검색, 가격 범위, 미취급 채우기, 필터 OR·AND·appliedFilters·`/search/filters`, 행 버전 판매 상태, 콘텐츠 보강 실패 → DLT |
| `HomeFlowIT`(storefront-service) | 진열 사본의 행 버전 판매 상태 규칙 |
| search-service 단위 시험 | 정렬·커서·필터 파싱(`SearchRules`) |
| LAN 확인(2026-11-04) | 생크림 일시품절 → 판매중(ES·검색·storefront, 행 버전 2·3), 두부 필터 옵션 승인 → `filters=seed-storage.냉장` 1건·`냉동` 0건·`/search/filters` 건수 |

## 10. 아직 없는 것
| 항목 | 막혀 있는 이유 · 필요한 것 |
|---|---|
| 브랜드 필터 | 상품 모델·계약·업무 원문에 브랜드가 없다. product-service 모델·PVP·일괄 등록 양식·백오피스 화면부터(정책 결정 필요) |
| 초성·동의어·오타 교정·구문 검색 | 색인 설정 변경(새 색인)으로 가능. 동의어 사전을 누가 관리할지 결정 필요. "무선 이어폰/무선이어폰" 같은 사례를 시험으로 고정 |
| 랭킹(인기·별점·전환율·광고) | 판매량 집계 없음(OrderConfirmed 구독으로 가능), 리뷰 서비스는 2단계, 노출·클릭 로그 없음, 광고 도메인 없음. 집계 기간·가중치는 승인 설정(Commerce 원문 1051·1082행) |
| 센터 취급을 색인에서 거르기 | mfc-inventory 가 취급 변경 이벤트(예 `SkuHandlingChanged`)를 내지 않는다 — 이벤트 계약·발행 → 색인 필드(예 `carriedMfcIds`) → 새 색인. 품절까지 넣을지는 결정 필요 |
| SKU 옵션 검색 | 색인에 대표 SKU 하나뿐. SKU 목록(nested)·바코드·규격 색인 필요 |
| `/search/filters` 카테고리 없는 이름 | 전체 필터 그룹 이름 조회를 추가하면 해결(작은 작업) |
| 부하 시험·검색 품질 평가 | 도구(k6 등)·정답 검색어 세트(_rank_eval) — 새 도구 도입이라 결정 필요 |
| Logstash 감시 | Logstash 모니터링 API(9600)를 지표로 내보내 소비 지연·큐 경보 |
