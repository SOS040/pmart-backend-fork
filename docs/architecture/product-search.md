# 상품 검색 — 현재 구현 정리 (Elasticsearch 중심)

- 기준: **2026-11-23**(LAN 색인 `products-v10`, 계약 0.13.0 배포 뒤 — §13 AI 상담(RAG)이 검색을 쓸 때 추가). 지금 어떻게 동작하는지 한곳에 모은 안내서이고, 설계 결정 문서는 아니다.
- 바뀐 이력(요약): 11-05 v5(띄어쓰기·초성·센터 취급) → 11-17 최근 검색어·인기 키워드(0.2.0) → 11-19 성능 개선·ES 관측 → 11-20 v6 순위 신호(ADR-0072, 0.3.0) → 11-20 v7 동의어·오타 교정(ADR-0073 A, 0.4.0) → 동의어 승인 반영(B, 0.5.0) → 교정 다음 쪽(0.5.1) → 11-21 할인가(0.6.0, ADR-0082) → 11-22 v8 원산지·설명(0.7.0)·필터 이름 묶기·할인 상품만(0.8.0)·할인율순(0.9.0) → 11-23 목록 판매 가능 여부(0.10.0)·v9 영양·알레르기(0.11.0)·0건 비슷한 상품(0.12.0)·카테고리 대표 사진(0.13.0)·v10 사용자 사전(그릭요구르트·스파클링).
- 정본(바뀌면 이 문서보다 우선):
  - 결정: ADR-0031(Elastic Stack, 색인은 Logstash 만), ADR-0034(이력 토픽 무제한 보관, 재색인 = Kafka 전체 재생), ADR-0038(검색 ES·로그 ES 분리), ADR-0021(DB 없는 소비자의 버전 비교 멱등), **ADR-0072**(순위 신호), **ADR-0073**(동의어·오타 교정), **ADR-0082**(쿠폰·기간 할인), **ADR-0083**(RAG 상품 질문은 검색 결과를 정해진 문장으로)
  - 계약: `contracts/openapi/search-service.yaml`(**0.13.0**), 사건 `contracts/events/*.v1.json`
  - 서비스 규칙(가장 자세함): `services/search-service/CLAUDE.md`
  - 재색인 절차: `deploy/lan/elastic/REINDEX.md`, 상품 추가 때 퍼지는 구조: `docs/architecture/product-add-and-reindex.md`
  - 사용자 메모(구현 순서 7단계)와의 대조: `docs/architecture/search-comparison.md`

## 1. 한눈에

```
product-service ─┐ ProductVersionPublished(rowVersion·filterOptions)·ProductSaleStateChanged
product-content ─┼─▶ outbox ─▶ Debezium ─▶ commerce.product.v1 ──────────┐
pricing-service ─┘                         commerce.pricing.v1 ──────────┤ 이력 토픽
mfc-inventory ── SkuHandlingChanged ─────▶ mfc.sku-handling.v1 ──────────┤ (무제한 보관 —
storefront ──── ProductRankingSignalsChanged ▶ commerce.product-ranking.v1┘  재색인 = 처음부터 재생)
                                                                          ▼
                    Logstash search-index(소비 그룹 search-index-logstash-v7, 작업자 1·배치 1)
                      ├─ Painless 버전 비교 → ES products · sku-prices · sku-handlings
                      ├─ 콘텐츠 카드 보강(product-content 공개 API, 실패 시 재시도) + 상품명 초성
                      └─ 끝내 실패 → Kafka search-index.DLT + 경보
                                                                          ▼
고객 앱 ─▶ api-gateway ─▶ search-service ──읽기──▶ products(→ products-v10)·sku-discounts
AI 상담(RAG) ─(토큰 없이, ADR-0083)─▶ search-service
                           │                       sku-handlings·sku-prices(Logstash 만 씀)
                           ├──쓰기(자기 색인)──▶ search-recent·search-log(검색 기록)
                           │                     search-terms(오타 사전, 10분마다 다시 만듦)
                           │                     search-synonym-rules + 동의어 집합 pmart-product-synonyms
                           ├─ 카테고리 이름 경로·필터 패널: product-service(5분 사본)
                           └─ ApprovalDecided(SEARCH_SYNONYM) 구독 → 동의어 반영 → 회신(StreamBridge)
```

- **상품 색인은 Logstash 만 쓴다**(ADR-0031). search-service 가 쓰는 것은 자기 색인(검색 기록·오타 사전·동의어 규칙)뿐이고 **DB 가 없다**.
- **업무 테이블 CDC 가 아니라 도메인 이벤트**를 받는다. 검색에 새 값(브랜드·재고 수량 등)을 넣으려면 그 값이 먼저 이벤트에 실려야 한다.

## 2. 인프라 (LAN)

| 항목 | 값 |
|---|---|
| Elasticsearch | `es-search` — 검색 전용(ADR-0038). 8.18.0, **단일 노드**(복제본 없음), 보안 끔 — LAN 시험망이라 의도. pc3 `192.168.0.222:9200` |
| 형태소 분석기 | analysis-nori. 컨테이너 시작 때 없으면 설치(새로 만들 때 인터넷 필요) |
| Logstash | `logstash-search` — 8.18.0, pc3, LAN 포트 없음. 파이프라인 `search-index`(작업자 1·배치 1), 영구 큐 256MB, Logstash DLQ 64MB |
| 색인 대상·그룹 | `deploy/lan/elastic/search-index.env`(별칭 `products`·`sku-prices`·`sku-handlings`·`sku-discounts`, 그룹 `search-index-logstash-v10`) → `render.sh` 가 Logstash 환경변수로 |
| 색인 정의 | `services/search-service/src/main/resources/elasticsearch/*.json` — es-init-search(pc3)과 search-service 가 같은 파일로 만든다. 정의에 별칭을 넣지 않고 **별칭이 없을 때만** 붙인다 |
| 로컬 | `deploy/local/docker-compose.yml`(ES·es-init). 옛 `esdata` 볼륨이 있으면 별칭이 옛 색인에 남는다 — 볼륨을 지우고 새로 띄운다 |

### LAN 색인 목록 (2026-11-23 확인)
| 별칭 | 색인 | 문서 | 쓰는 쪽 | 내용 |
|---|---|---|---|---|
| `products` | **products-v10** | 504 | Logstash | 상품 검색 문서(§3). v5~v9 는 지웠다 |
| `sku-discounts` | sku-discounts-v1 | — | Logstash | SKU 지금 할인(ADR-0082, 토픽 compact) — 결과의 할인가·`onSale` |
| `sku-prices` | sku-prices-v1 | 503 | Logstash | SKU 기본가(가격 사건 → 상품 문서로 퍼뜨림) |
| `sku-handlings` | sku-handlings-v1 | 503 | Logstash | SKU 별 센터 취급(센터별 버전) |
| `search-recent` | search-recent-v1 | — | search-service | 고객 최근 검색어 |
| `search-log` | search-log-v1 | — | search-service | 검색 기록(인기 키워드·0건 검색어) |
| `search-terms` | search-terms-v1 | 약 1300 | search-service | 오타 교정 사전(상품명 낱말·자모·상품 수) |
| `search-synonym-rules` | search-synonym-rules-v1 | 0 | search-service | 동의어 규칙 정본(state·반영 기록) — 아직 규칙 없음 |
| — | ES 동의어 집합 `pmart-product-synonyms` | 규칙 0 | search-service | `name` 검색 분석기가 읽음(updateable) |

## 3. 상품 색인 문서 (`products-v10`, strict 매핑)

| 필드 | 뜻 | 채우는 사건 |
|---|---|---|
| `productId` | 문서 id | 모두 |
| `productVersion` | 상품 버전 — 옛 사건 무시 기준 | ProductVersionPublished |
| `primaryCategoryId`·`categoryIds`·`categoryScope` | 카테고리(조상 범위 포함 — 상위로 찾으면 하위 상품도) | ProductVersionPublished |
| `representativeSku` | 대표 SKU 하나(지금 LAN 상품은 모두 상품 하나에 SKU 하나) | ProductVersionPublished |
| `carriedMfcIds` | 대표 SKU 를 켠 센터 목록 | SkuHandlingChanged(`sku-handlings` 갱신 뒤 `_update_by_query`) |
| `filters` | 승인 속성 필터 `"그룹.옵션"` | ProductVersionPublished `filterOptions` |
| `saleState`·`saleStateAtMs`·`saleStateRowVersion` | 판매 상태와 비교 기준(§4) | ProductVersionPublished·ProductSaleStateChanged |
| `name`·`imageUrl`·`contentVersion`·`contentPublishedAtMs` | 고객용 이름·이미지(게시본만) | ProductContentPublished → 카드 API 보강 |
| `nameChosung` | 상품명 초성 | Logstash 가 이름에서 계산 |
| `basePrice`·`priceVersion` | 기본가 | PriceVersionEffective |
| `ratingCount`·`ratingAverage`·`ratingScore`·`salesWindow`·`signalsVersion` | **순위 신호(v6, ADR-0072)** — 리뷰 수·평균·베이지안 점수·최근 30일 판매 | ProductRankingSignalsChanged(storefront 가 셈, 바뀔 때만·매일 04:40) |
| `originType`·`originText`·`description` | **(v8)** 원산지(DOMESTIC·IMPORTED·null)·원산지 글·상품 설명 — 설명은 낮은 비중 검색 | 카드 API `include=search` 보강 |
| `nutrition` | **(v9)** 표시사항 영양 — 기준 `basisUnit` G(100g)·ML(100mL), `energyKcal`·`sodiumMg`·`sugarsG`·`proteinG`·`fatG`, 모르는 칸 null. LAN 504 중 239 | 카드 보강(product-content 0.6.0) |
| `allergens` | **(v9)** `contains`(함유)·`mayContain`(같은 시설 제조)·`text`(표시 원문) — 법정 이름. LAN 504 중 300. **목록에 없음 ≠ 들어 있지 않음**(§13) | 카드 보강 |
| `updatedAtMs` | Logstash 가 마지막으로 반영한 시각 | 모두 |

- **상품명 분석**:
  - 색인: `name`(nori 형태소) · `name.edge`(앞부분) · `name.partial`(1~2글자 n-gram) · `name.compact`(공백 뺀 n-gram) · `nameChosung`(+`.edge`)
  - **검색 분석기(v7)**: `name` 을 찾을 때만 `name_nori_search` = nori + **동의어**(`synonym_graph`, 집합 `pmart-product-synonyms`, updateable). 색인 분석기는 그대로라 동의어를 바꿔도 재색인이 필요 없다(다음 검색부터).
- **검색 대상**: 이름과 상품 버전이 모두 있고 판매중지(`SALE_STOPPED`)가 아닌 문서. 콘텐츠가 아직 게시되지 않은 상품은 이름이 없어 검색되지 않는다.
- **재고 수량(판매 가능량)은 색인에 없다** — 목록의 판매 가능 여부는 검색할 때 실시간으로 붙인다(§5 `availability`).
- 적용된 색인의 매핑은 고치지 않는다. 필드를 더하면 새 색인(`-v11`)을 만들고 재색인한다(§8).
- 사용자 사전(v8·v10): nori 가 동의어 낱말을 쪼개지 않게(어묵·오뎅·햇반·요거트·그릭요구르트·스파클링 등) — 바꾸면 새 색인.

## 4. 색인 규칙 (Logstash `search-index.conf`)

- **멱등**: 문서마다 버전을 비교해 옛 사건은 무시한다(상품 `productVersion`, 가격 `priceVersion`, 콘텐츠 `contentVersion`, 센터 취급은 센터별 행 버전, 순위 신호 `signalsVersion`). 재전달·재생해도 결과가 같다.
- **센터 취급**: mfc-inventory 가 취급이 바뀔 때만 `SkuHandlingChanged`(레코드 없음 = OFF). 취급 사건과 상품 사건은 어느 쪽이 먼저 와도 결과가 같다.
- **판매 상태**: 상품 행 버전(PVP `rowVersion`·PSSC `version`)이 저장된 것보다 클 때만. 행 버전 없는 옛 사건은 행 버전 기준 상태가 없을 때만 시각 비교. storefront 진열 사본도 같은 규칙.
- **콘텐츠 보강 실패**: 2초 간격 2번 더 시도 → 그래도 실패면 **색인하지 않고** `search-index.DLT`(원래 토픽·파티션·오프셋) + 경보 `SearchIndexDeadLetter`.
- **작업자 1·배치 1**: 같은 상품의 상품·가격 사건이 섞이지 않게 한 건씩. 지금 규모(상품 약 500개)에서 전체 재생 1분 안. 만 건 단위로 늘면 순서 보장 설계와 함께 다시 본다.
- **영구 큐**: Kafka 오프셋 커밋이 ES 쓰기보다 먼저다 — "소비 지연 0" 만으로 반영 완료를 판단하지 않는다(큐도 비어야 함).
- 파이프라인을 바꾸면 `SearchFlowIT`(실제 Kafka·ES·Logstash)로 확인하고, 문법은 `logstash -t -f search-index.conf` 로 먼저.

## 5. 검색 API (`/api/v1/search/...`, 계약 0.13.0)

### `GET /search/products`
| 파라미터 | 동작 |
|---|---|
| `q` | 상품명 검색(최대 100자). 찾기: nori 형태소(동의어 포함)가 모두 맞거나 · n-gram 조각이 모두 있거나 · 공백 뺀 조각이 모두 있거나. 점수: 형태소(가중 3) > 앞부분(2) > 조각, 낱말이 검색어 순서로 붙어 있으면 가산(구문, slop 1). **한글 자음만 2자 이상이면 초성 검색** |
| `categoryId` | 그 카테고리와 하위 전체 |
| `minPrice`·`maxPrice` | 기본가 범위. 범위를 주면 가격 없는 상품은 빠진다 |
| `filters` | `그룹.옵션` 반복. 같은 그룹 OR, 그룹끼리 AND, 최대 30개. 응답 `appliedFilters` |
| `mfcId`·`includeNotCarried` | `carried` = 색인 `carriedMfcIds` 에 그 센터가 있는지. 기본은 미취급을 ES 필터로 거른다(total 정확) |
| `sort` | RELEVANCE(기본)·NEWEST·PRICE_ASC·PRICE_DESC·**POPULAR**(판매 → 점수)·**RATING**(베이지안 점수 → 리뷰 수)·**REVIEW_COUNT**(리뷰 수 → 점수). 신호 없는 상품은 맨 뒤 |
| `size`·`cursor` | 커서 페이지. 커서 위치 1만 이상은 400(ES 창 밖), 창 너머 다음 커서 없음 |
| `noCorrection` | true 면 오타 교정을 하지 않는다("원래 검색어로 찾기") |
| `onSale` | **(0.8.0)** true 면 대표 SKU 에 지금 기간 할인이 있는 상품만(할인 색인을 못 읽으면 503). `sort=DISCOUNT_RATE`(0.9.0)는 이것과 함께만 |
| `filters=origin.국내산·수입산` | **(0.7.0)** 원산지 |
| `filters=nutrition.저당·저나트륨·고단백` | **(0.11.0)** 영양 기준(같은 키 AND) — 저당 = 당류 100g 당 5g·100mL 당 2.5g 미만, 저나트륨 = 나트륨 120mg 미만, 고단백 = 단백질 100g 당 11g·100mL 당 5.5g 이상(pmart-config `pmart.search.nutrition.*`, 없으면 422 CONFIG_REQUIRED). 영양 값이 null 인 상품은 걸리지 않는다 |

- 결과 항목: `productId`·`name`·`imageUrl`·`basePrice`·`saleState`·`carried`·`categoryPath`·`rating`·`reviewCount` 등. 판매중지는 빠지고 일시품절은 남아 `saleState` 로 표시.
  - **(0.6.0) 할인**: `salePrice`·`discountRatePercent`·`discountName`·`discountEndsAt` — 지금 기간 할인이 있을 때만(할인 색인을 못 읽으면 정가로, 검색은 성공). 가격순·가격 범위는 기본가.
  - **(0.10.0) `availability`**: mfcId 를 줄 때만 AVAILABLE·SOLD_OUT·UNKNOWN(그 센터에서 취급 옵션이 모두 품절이면 SOLD_OUT) — 검색 때 product-content 카드로 실시간. mfcId 가 없으면 null. 거르기·정렬에는 안 씀.
  - **(0.11.0) `nutrition`·`allergens`**: §3 그대로. 알레르기는 거르기가 없다 — 검색어가 묻는 꼴("땅콩 함유"·"계란 알레르기")일 때만 `contains` 상품을 낮은 비중으로 더한다(같은 시설은 더하지 않음).
  - **(0.12.0) `similarItems`**: 첫 쪽이 0건(교정 뒤에도)이면 비슷한 이름 상품(형태소 하나라도·조각 70%) → 없으면 인기 상품, 최대 20. **찾은 상품이 아니다**(§13). 결과가 있으면 null.
- **오타 교정(ADR-0073)** — 첫 쪽·검색어 있음·초성 아님·`noCorrection` 아님일 때:
  - **결과 0건** → 사전(`search-terms`)에 없는 낱말을 자모 거리(6자모 이하 1·넘으면 2, 첫 자모 고정)로 가장 가깝고 상품이 많은 낱말로 바꿔 다시 찾고 `correctedQuery`(결과는 고친 검색어 것, `query` 는 원래 검색어). 앱: "'두부' 검색 결과예요 · '두뷰'로 찾기"
  - **결과 적음**(설정 `pmart.search.correction.few-results`, LAN 3) → 결과는 그대로, 고친 검색어가 더 많이 찾을 때만 `suggestedQuery`. 앱: "혹시 '두부'를 찾으셨나요?"
  - **다음 쪽(0.5.1)**: 교정된 검색의 다음 쪽은 같은 원래 `q` + `cursor` 로 부르면 서버가 고친 검색어로 이어 준다.
  - 고친 낱말은 **실제 상품명에 있는 낱말만**. 실패해도 검색은 교정 없이 성공(WARN, 검색어는 로그에 안 남김).
  - (0.12.0) 원래 검색어가 상품명에 형태소로 그대로 맞으면 `suggestedQuery` 를 주지 않는다("서울우유"가 "우유" 제안을 받지 않게).
  - 사전은 10분마다 상품 색인에서 다시 만든다(`TermDictionary` — 이름 낱말 그대로 + nori 낱말, 한글 2글자 이상). 새 상품 낱말도 자동으로 들어온다.

### `GET /search/suggestions`
- 자동완성: 상품 검색과 같은 질의로 찾은 상품명(중복 없이 관련도 순). **후보가 없으면 고친 검색어로**. 응답의 인기 키워드(popular)는 검색 기록으로.

### `GET /search/filters`
- 지금 조건의 `filters` 를 terms 집계로 세고, 이름·선택 방식·순서는 product-service 필터 패널(그 카테고리와 조상을 가까운 것부터, 없으면 최상위 노드들 — 5분 사본). 건수 0 인 옵션은 안 나온다.

### `GET /search/category-images` (0.13.0)
- 카테고리마다 대표 사진 하나(그 카테고리와 하위의 판매 가능 상품 중 최근 판매 → 별점 → 최근 게시 1위), mfcId 면 그 센터 취급 상품만. 5분 사본. 공개.

### 최근 검색어·인기 키워드 (0.2.0)
- 검색자는 `HMAC-SHA256(searcher-hash-key, 사용자 종류:주체 ID)` 해시만 남긴다(고객 ID 원문 없음).
- `GET·DELETE /me/recent-searches`(고객만, `search-recent`), 인기 키워드 = `search-log` 의 `window` 안 결과 있는 검색을 서로 다른 검색자 `min-searchers` 명 이상만, 숨김 목록 제외.
- 기록은 첫 쪽·q 있음·**고객·비회원 토큰이 있을 때만** 비동기(작업 큐 — 검색을 늦추지 않음). 토큰 없는 호출(AI 상담 — ADR-0083)은 기록하지 않는다. 매일 04:17 오래된 문서 정리.

### 운영 API (MD·OPERATOR_ADMIN)
| API | 버전 | 내용 |
|---|---|---|
| `GET /search/zero-result-keywords?days=` | 0.4.0 | 최근 N일 결과 0건 검색어(많이 나온 순, 숨김 목록 제외) — 동의어·상품명 개선 근거. 백오피스 "결과 없는 검색어" 화면 |
| `GET /search/synonym-rules` | 0.5.0 | 지금 동의어 규칙과 버전(처음 0) |
| `GET /search/synonym-rule-applications` | 0.5.0 | 승인 반영 기록(APPLIED·REJECTED, 까닭) |

- 내부: `GET /internal/index-status`(원천 사건별 마지막 반영 시각), `/internal/reindex-requests` 는 501(재색인은 절차로).

## 6. 동의어 — 승인으로만 바뀐다 (ADR-0073 B)

```
백오피스(MD) ─ GET /search/synonym-rules(version) ─▶ approval-service POST /change-requests
   type SEARCH_SYNONYM · targetRef pmart-product-synonyms · targetExpectedVersion · 행 = 규칙 UPSERT/DELETE
운영 관리자 승인 ─▶ ApprovalDecided(platform.approval.v1) ─▶ search-service approvalEvents
   기준 버전 = 지금 버전이고 모든 행이 맞을 때만 전부 반영(아니면 STALE_VERSION·LINE_REJECTED, 아무것도 안 바꿈)
   → ES 동의어 집합 갱신(다음 검색부터) → search-synonym-rules state·반영 기록 → ApplicationReceiptRecorded 회신
```
- 규칙 모양: **같은 뜻 묶음만**(예 `계란, 달걀`) — 낱말 2~20개, 규칙 ID 영문·숫자·`_.-`. 한 방향 규칙(`콜라 => 코카콜라`)은 승인 형식 검사가 막는다(필요하면 새 결정).
- 요청 MD·OPERATOR_ADMIN, 승인 OPERATOR_ADMIN, 자기 승인 금지. 대상이 하나라 심사 중인 동의어 승인은 한 번에 하나.
- DB 가 없어 inbox·outbox 대신 **승인 ID·버전으로 멱등**, 회신은 StreamBridge 로 바로(실패하면 사건을 다시 받아 같은 회신). 재시도를 다 쓴 사건은 `platform.approval.v1.DLT` 로만(재처리 API 없음 — 다시 승인 요청). 소비는 한 인스턴스 전제.
- 남은 것: 백오피스 화면(요청서 `docs/handoff/clients/backoffice/2026-11-20-request-search-synonym-screen.md`, 웹 PR #282 목 모드), 처음 동의어 목록(단계 E).

## 7. 필터 데이터의 흐름
1. product-service: 카테고리 필터 패널과 상품별 옵션. 상품 등록·변경 초안의 `filterOptions` 로 넣고 PRODUCT 승인으로 반영.
2. 승인 반영 때 ProductVersionPublished 에 `filterOptions` 를 싣는다 → Logstash 가 `"그룹.옵션"` 으로 색인.
- LAN 은 필터 그룹이 `seed-storage`(보관방법) 하나, 옵션 붙은 상품이 거의 없다. **필터가 쓸모 있으려면 상품 등록 때 옵션을 채워야 한다.** 옛 사건에는 `filterOptions` 가 없어 재생만으로는 안 채워진다.

## 8. 재색인 (`deploy/lan/elastic/REINDEX.md`)
- 실행: `deploy/lan/elastic/reindex.sh`(check·start·wait·switch·finish·rollback·drop) — 단계마다 조건을 확인하고 멈춘다.
- 방법: **새 소비 그룹으로 이력 토픽을 처음부터 재생** → 새 색인 채움 → 완료 확인 → 별칭 원자 교체. 검색은 교체 순간까지 이전 색인을 읽는다(무중단).
- 완료 조건: 소비 지연 0, Logstash 영구 큐 비어 있음, DLT 새 레코드 없음, 건수 ≥ 이전 색인, 전 문서 대조.
- 순위 신호는 storefront 가 낸 사건이라, 재색인 뒤 storefront `POST /internal/storefront/ranking-signal-republications` 로 다시 낸다(REINDEX.md §2-7).
- 주의: `search-index.env` 를 render 전에 새 색인·그룹으로(`start` 가 함), 전환 뒤 별칭으로 되돌리고 그룹은 사람이 커밋, search-service 새 이미지는 `switch` 뒤, 되돌림은 `rollback` 보다 고쳐서 다시 재생이 기본.
- 실행 기록: 11-04 v2→v3, 11-05 v3→v4→v5, **11-20 v5→v6(순위 신호)**, **11-20 v6→v7(동의어 검색 분석기, PR 세션 cc99fe73)**, **11-22 v7→v8(원산지·설명·사용자 사전)**, **11-23 v8→v9(영양·알레르기)**, **11-23 v9→v10(사용자 사전 2낱말, af35f552)**. 옛 색인은 확인 뒤 drop(지금 v10 만).

## 9. 감시
| 대상 | 있는 것 | 없는 것 |
|---|---|---|
| Elasticsearch | es-exporter 지표, 경보 Unreachable·ClusterRed·HeapHigh | 색인별 지표 |
| 이력 토픽 | 보관·오프셋·증가량 지표와 경보, `KafkaTopicMetricsStale` | 외부 매체 백업 자동화 |
| 색인 파이프라인 | 소비 지연·영구 큐·DLQ·ES 거절·마지막 반영 시각(watch.sh 5분마다), 경보 `SearchIndexStalled` 등 | 5분 표본이라 짧은 멈춤은 못 봄 |
| search-service | 앱 공통 지표, **ES 호출 관측 `pmart.search.es`(op 별 지표·span), 응답 시간 버킷 → p95**(11-19) | 0건 비율·클릭 같은 검색 품질 지표(0건 검색어 API 는 있음) |
- 화면: Grafana **PMart 상품 검색**(uid `pmart-search`).

## 10. 아직 없는 것
| 항목 | 막혀 있는 이유 · 필요한 것 |
|---|---|
| 품절 거르기·정렬 | 목록 표시는 됨(0.10.0 `availability`, 실시간). 색인에 판매 가능량이 없어 "품절 빼고 보기"·품절 뒤로 정렬은 없다(2026-11-05 결정 — 색인하지 않음) |
| 알레르기 거르기("땅콩 없는 것만") | 표시사항 판독이 없는 상품(`allergens` null)을 "없음"으로 볼 수 없어 만들지 않는다(§13) |
| 브랜드 필터 | 상품 모델·계약·업무 원문에 브랜드가 없다(정책 결정 필요) |
| 기본 순위에 인기·별점 섞기·전환율·광고 | 정렬은 됨(ADR-0072). 기본 관련도에 섞는 가중치·노출/클릭 로그·광고 도메인은 없음 |
| 여러 SKU 상품의 취급 판정 | 지금은 대표 SKU 기준 |
| SKU 옵션·바코드 검색 | 색인에 대표 SKU 하나뿐 |
| 동의어 한 방향 규칙 | 지금은 같은 뜻 묶음만(§6) |
| 부하 시험·검색 품질 평가 | 도구·정답 검색어 세트 — 새 도구 도입이라 결정 필요 |

## 11. 시험
| 시험 | 확인하는 것 |
|---|---|
| `SearchFlowIT`(실제 Kafka·ES·Logstash) | 이벤트 → 색인 → 검색 전 구간, 형태소·붙임말·띄어쓰기·초성, 가격·센터 취급·필터, 행 버전 판매 상태, DLT, **순위 신호 정렬**(`rankingSignalsDriveSorts`), **동의어·오타 교정**(`synonymsAndTypoCorrection`), 깊은 커서 400, 할인·`onSale`, 원산지(`originFilterAndDescriptionSearch`), **영양 필터·알레르기 검색**(`nutritionFilterAndAllergenSearch`), **0건 비슷한 상품**(`zeroResultsShowSimilarItems`), 판매 가능 여부 |
| `SearchActivityIT`(실제 ES) | 최근 검색어·인기 키워드, **0건 검색어 API** |
| `SynonymApprovalIT` | ApprovalDecided → 동의어 반영·재전달·옛 버전·반려 무회신·행 오류·삭제, 회신 |
| 단위 | `SearchRules`(정렬·커서·창), `HangulTest`(자모·초성), `SynonymRules`, `CategoryTreeClientTest`, `SearchKeywordsTest`·`PopularKeywordsTest` |
| LAN 확인(11-20) | v7 504/504, 자동완성 "두뷰"→두부·"샹크림"→생크림, `/search/synonym-rules` version 0, `/search/zero-result-keywords` 200 |

## 12. 성능·알려진 문제
- 분석: `docs/operations/2026-11-19-search-performance-analysis.md` — 상품 약 500개 규모에서 서버 안 약 10ms, 사용자가 겪는 시간은 대부분 네트워크.
- 고친 것(11-19): 깊은 커서 500 → 400, 카테고리 경로·필터 패널 사본을 기다리지 않음, 색인 확인을 시작 때 한 번.
- 고객 앱 "느림"(11-20)은 검색이 아니라 게이트웨이 처리율 제한(사진 429)과 원본 이미지였다 — 공개 사진 경로 제한 제외·축소본으로 고침. 정리: `docs/operations/2026-11-20-search-detail-review-summary.md`, 이미지: `docs/architecture/product-image-variants.md`.

## 13. AI 상담(RAG)이 검색을 쓸 때 — CA-1·CA-2·AI-2 를 고려 (2026-11-23)

고객 AI 상담(customer-assistant, PC 4)과 운영자 AI(ai-assistant)는 상품 질문에 이 검색 API 를 쓴다(ADR-0083). 영양·알레르기 질문(CA-1·AI-2)과 채팅 금칙어(CA-2)를 넣을 때 검색 쪽에서 지켜야 할 것. 요청서: `docs/architecture/service-requests/customer-assistant-service.md` CA-1·CA-2, `ai-assistant-service.md` AI-2.

### 13-1. 부르는 법
- `GET /api/v1/search/products` 만(ES 직접 접근 금지 — ADR-0083 결정 1). **토큰 없이** 부른다 — 고객 최근 검색어·인기 키워드에 AI 질문이 섞이지 않는다(§5 기록은 토큰 있을 때만).
- 상품 질문: `q` = 질문에서 뽑은 **상품 이름**(성분·영양 낱말은 빼고), `size` = 답에 쓸 수(설정 `max-items`, 3). 영양 필터 질문("저당 음료 뭐 있어?"): `filters=nutrition.저당`(+ 이름 낱말이 있으면 `q`).
- 고객은 센터를 주지 않는다(질문에 주소·센터 없음 — ADR-0083) → 결과의 `availability`·`carried` 는 센터 기준이 아니다. **"지금 주문할 수 있어요/품절이에요"를 단정하지 않는다** — 판매 상태(`saleState`)만, "주소에 따라 받아 볼 수 없는 상품도 있어요" 한 줄 그대로.

### 13-2. 결과를 문장으로 옮길 때 (모델이 숫자·성분을 만들지 않는다)
| 칸 | 지킬 것 |
|---|---|
| `correctedQuery` 가 있으면 | 결과는 **고친 검색어** 것 — 답에 "'{고친 검색어}'로 찾은 결과예요"를 밝힌다(물은 상품이 있다고 말하지 않음) |
| `similarItems`(0건) | **찾은 상품이 아니다** — 상품 답을 하지 않고 지금처럼 문서·상담 연결(ADR-0083 결정 4). "비슷한 상품"으로 권하려면 따로 결정 |
| `nutrition` | 있는 칸만, 기준을 꼭 붙임("100g 당"·"100mL 당" — `basisUnit`). 전부 null 이면 "표시사항 영양정보가 아직 없어요" — 0 으로 말하지 않는다. 판독 값이라 "상품 표시사항이 기준이에요" 한 줄 |
| `allergens.contains` | "{성분}이(가) 들어 있어요" |
| `allergens.mayContain` | "{성분}을(를) 쓰는 제품과 같은 시설에서 만들어 섞일 수 있어요" — 함유와 섞어 말하지 않는다 |
| `allergens` null 이거나 물은 성분이 목록에 없음 | **"없다"고 답하지 않는다** — "표시사항에서 확인하지 못했어요. 알레르기가 있으면 상품 표시사항을 꼭 확인해 주세요." 목록은 표시사항 판독 결과라 빠진 것이 있을 수 있다 |
| 알레르기로 찾기("땅콩 들어간 거?") | 검색은 `contains` 상품을 **낮은 비중으로 더할 뿐** 거르지 않는다 — 결과마다 `allergens.contains` 를 다시 보고 든 것만 말한다. "땅콩 없는 것"은 거를 수 없다(§10) → 표시사항 확인 안내 |
| 영양 필터 결과 | 상위 N개 + 기준 한 줄(§5 `filters=nutrition`). 결과가 전부가 아니다("예를 들면") |
| `salePrice`·`discountEndsAt` | 할인이 있으면 할인가와 끝 시각(KST)을 같이 — 끝난 뒤 답이 틀리지 않게 "~까지". 쿠폰은 검색 결과에 없다(사람마다 다름) |
| 건강·복용량 | 의료 조언 금지(CA-1 3) — 문서·상담 연결 |

- 숫자는 결과 값을 그대로(반올림 규칙을 정하면 문장 코드 한 곳에서). 색인 반영이 늦으면 답도 늦은 값이다 — 가격·할인은 "지금 기준" 표현.

### 13-3. 금칙어(CA-2)와 검색
- **질문 글의 금칙어 검사는 검색을 부르기 전에** 한다. 막기 단어(욕설)가 있으면 검색·문서 검색·모델 호출 모두 하지 않는다 — 금칙어가 든 글이 검색어(ES 질의·느린 질의 로그)로 나가지 않게.
- 정지 단어는 사건 `CustomerProfanityDetected`(source AI_CHAT — customer 가 CU-7 로 detectedAt + 7일 정지·"7일 계정 정지" 알림, 2026-11-23 배포). 그 뒤 답을 이어 줄지는 CA-2 대로(PC 4).
- 검색 서비스는 검색어를 거르지 않는다(상품명 검색이라 욕설 목록을 두지 않음). 검색어는 로그에 남기지 않고(WARN 에도), 토큰 없는 호출은 기록도 하지 않는다.

### 13-4. 실패·느림
- 검색 실패(연결·5xx·시간 초과)면 상품 답 없이 문서로(ADR-0083 결정 4). `filters=nutrition` 이 422 CONFIG_REQUIRED(기준값 설정 없음)면 필터 질문만 "지금은 영양 기준으로 찾을 수 없어요".
- `onSale=true` 는 할인 색인을 못 읽으면 503 — 할인 질문만 실패로 다룬다.
- 서버 안 처리 약 10ms(§12), mfcId 를 주면 카드 조회가 더해진다(AI 는 주지 않음).

### 13-5. 계약이 바뀌면
- 결과 칸이 늘면(검색 계약 minor) AI 쪽 `ProductSearchClient` 는 모르는 칸을 무시하므로 깨지지 않는다. 칸의 **뜻**이 바뀌면(예: `allergens` 판독 기준) 이 절과 CA-1 문장을 같이 고친다.
- 새 색인(v11)으로 바뀌어도 API 가 같으면 AI 쪽은 할 일 없다.
