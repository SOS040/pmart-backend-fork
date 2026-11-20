# search-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `search-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/search-service.yaml` → `openApiGenerate` 가 `com.pmart.search.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. **DB 가 없어 InboxGuard 대신 색인 문서의 버전 비교로 멱등**(ADR-0021).

## 패키지
`com.pmart.search.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- DB 없음. 색인 정의(설정·매핑)는 `src/main/resources/elasticsearch/*.json`(LAN 은 es-init 이 같은 파일로 만든다). 매핑을 바꾸면 새 색인(`-v3`)을 만들고 별칭을 옮긴다(적용된 색인 매핑을 고치지 않는다) — `SearchIndex.INDICES` 도 같이.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:search-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:search-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8105.

## 검색 색인 (ADR-0031 — 2026-11-03 OpenSearch → Elasticsearch)
- **Elasticsearch 8.18.0 + analysis-nori**(주소 pmart-config `pmart.search.elasticsearch-url`). RestClient 로 REST 호출 — 공용 Builder(사용자 토큰 전파)를 쓰지 않는다. 상품·가격·취급 색인은 **조회만** 한다(쓰는 것은 최근 검색어·검색 기록 색인뿐 — 아래).
- **색인은 Logstash**(`src/main/logstash/search-index.conf`, 이 서비스 소유 — 배포가 Logstash 에 복사): commerce.product.v1(ProductVersionPublished·ProductContentPublished·ProductSaleStateChanged)·commerce.pricing.v1(PriceVersionEffective) → products·sku-prices. Painless 스크립트가 버전을 비교(재전달·옛 사건 무시). 콘텐츠는 product-content 공개 카드 API 로 이름·이미지 보강(게시본만, 실패는 재시도 뒤 DLT). 작업자 1·배치 1(가격·상품 경합 방지). 재처리 = 소비 그룹(`search-index.env` 의 `PMART_SEARCH_GROUP_ID`, 지금 `search-index-logstash-v3`) 오프셋 되돌림. 파이프라인을 바꾸면 `SearchFlowIT`(실제 Kafka·ES·Logstash)로 확인한다.
- 이름 = `name`(nori) · `name.edge`(앞부분) · `name.partial`(1~2글자 n-gram) · `name.compact`(공백을 모두 뺀 뒤 1~2글자 n-gram, products-v4). 찾기: nori 형태소가 모두 맞거나("두부를"→두부) n-gram 조각이 모두 있거나(붙임말 "나랑드사이다제로"·"미역"→미역국) 공백을 뺀 조각이 모두 있거나("생크림"↔"생 크림"). 순위: 형태소 > 앞부분 > 조각, 검색어 낱말이 그 순서로 붙어 있으면 가산(구문 `match_phrase` slop 1, 2026-11-05). 카테고리는 구조 id 조상 범위.
- 초성(2026-11-05): 검색어가 한글 자음만 2자 이상이면(`SearchRules.isChosungQuery`) `nameChosung`(Logstash 가 이름에서 계산, 공백 제거 n-gram)만 찾는다. 1자·섞인 검색어("ㄷ부")는 일반 검색. 순위: 낱말 앞부분 초성이 맞으면 가산(`nameChosung.edge`, products-v5 — "ㄷㅂ" → 두부가 "단백"보다 위). 자동완성도 같은 조건.
- 검색 대상: 이름·상품 버전이 모두 있고 판매중지가 아닌 상품.
- categoryPath: 대표 카테고리의 이름 경로 — product-service 공개 트리(`pmart.search.product-base-url`)를 5분 사본으로 둔다. 조회 실패면 이전 사본, 없으면 빈 목록(검색은 실패하지 않음). (2026-11-02)
- **동의어·오타 교정(2026-11-20 ADR-0073 단계 A, 계약 0.4.0, products-v7)**: 상품명 `name` 의 검색 분석기 `name_nori_search` = nori + 동의어(synonym_graph, 동의어 집합 `pmart-product-synonyms`, updateable) — 집합을 바꾸면(`SearchIndex.putSynonymRules`) 재색인 없이 다음 검색부터. 상품 색인 만들기 전 동의어 집합을 빈 것으로(`ensureSynonymSet`, LAN 은 es-init.sh). 오타: `TermDictionary` 가 10분마다 상품명 낱말 사전 `search-terms`(이름에 쓰인 낱말 그대로 + nori 낱말, 한글 2글자 이상, 자모·상품 수) — `QueryCorrector` 가 사전에 없는 낱말을 자모 거리(6자모 이하 1·넘으면 2, 앞 1자모 고정)로 가장 가깝고 상품이 많은 낱말로. `CorrectedSearch`: 첫 쪽·검색어·초성 아님·`noCorrection` 아님 — 0건이면 고쳐 다시 찾아 `correctedQuery`, `correction.few-results`(설정, 없으면 교정 꺼짐) 이하면 고친 것이 더 많이 찾을 때만 `suggestedQuery`, 자동완성도 후보 없으면 고친 검색어로, 실패는 교정 없이(WARN, 검색어 로그 안 남김). 검색 기록 결과 수는 원래 검색어 것. 운영 `GET /search/zero-result-keywords?days=`(MD·OPERATOR_ADMIN — `ZeroResultKeywords`, 숨김 목록 제외). **bulk 본문은 charset 을 꼭(application/x-ndjson 은 String 이 ISO-8859-1 로 나가 한글이 깨졌다 — 시험에서 잡음)**. 시험 `HangulTest`·`SearchFlowIT.synonymsAndTypoCorrection`·`SearchActivityIT.zeroResultKeywordsForOperators`. 승인으로 동의어 바꾸기(단계 B)는 아직.
- **동의어 승인 반영(2026-11-20 ADR-0073 단계 B, 계약 0.5.0)**: 규칙 정본 = 색인 `search-synonym-rules`(문서 `state` = 버전·규칙·마지막 반영 승인, `approval-<승인 ID>` = 반영 기록). 바꾸기는 approval-service `SEARCH_SYNONYM` 승인으로만 — `ApprovalDecided` 를 바인딩 `approvalEvents`(설정 저장소에 바인딩이 있을 때만 빈 생성, `@ConditionalOnProperty`)로 받아 `SynonymRegistry` 가 처리. DB 가 없어 common-messaging inbox(`EventConsumers`)를 못 쓴다 — 멱등은 ① 반영 기록이 있으면 같은 회신만 다시 ② state 의 마지막 승인이 이것이면 기록만 ③ 아니면 기준 버전(`targetRef.expectedVersion`) = 지금 버전일 때만 전부 반영(`domain/SynonymRules` — 하나라도 어긋나면 전부 안 함: STALE_VERSION·LINE_REJECTED). 순서 동의어 집합 → state → 기록 → 회신. 회신 `ApplicationReceiptRecorded` 는 outbox 없이 StreamBridge 로 바로(`ApprovalReceiptSender`, 키 = 승인 ID, 스키마 검사). 반려 결정은 회신 없음. 운영 조회 `GET /search/synonym-rules`·`/search/synonym-rule-applications`(MD·OPERATOR_ADMIN). 재시도를 다 쓰면 `platform.approval.v1.DLT`(dead_letter 기록·재처리 API 없음 — DB 없음, 다시 승인 요청). 소비는 한 인스턴스 전제(메서드 synchronized).
- ES 호출 관측(2026-11-19, 성능 분석 2순위 ④): `SearchIndex` 가 ES 호출마다 Micrometer 관측 `pmart.search.es`(태그 op = search·put·delete·delete_by_query·ensure, alias, error) — 지표 `pmart_search_es_seconds_*` 와 추적 자식 span "es <op>"(이 클라이언트는 공용 Builder 가 아니라 자동 관측이 없어서). 응답 시간 버킷은 pmart-config `search-service.yml` `management.metrics.distribution.slo`(http.server.requests·pmart.search.es) — p95 = `histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{application="search-service"}[5m])))`, ES 는 `…pmart_search_es_seconds_bucket… by (le, op)`. 검색어·문서 내용은 태그에 넣지 않는다. 시험 `SearchFlowIT`(prometheus 에 버킷·op·alias).
- 깊은 쪽 상한(2026-11-19, 성능 분석 1순위 ①): 커서 위치가 `SearchRules.MAX_WINDOW`(1만, ES `max_result_window`) 이상이면 잘못된 커서 400(전에는 ES 오류로 500), 쪽 크기는 창 끝까지만(`pageSize`), 창 너머로는 다음 커서를 주지 않는다(`hasNext`). 시험 `SearchRulesTest.cursorStaysInsideTheResultWindow`·`SearchFlowIT`(o:20000 → 400).
- 색인 확인(2026-11-19 1순위 ③): `SearchIndex.ensure(alias)` — 검색·쓰기는 **자기 색인만** 확인(별칭마다 한 번, 같은 별칭은 동시에 하나만, 실패하면 다음 요청이 다시). 서비스가 뜨면(ApplicationReadyEvent) 가상 스레드로 전부 한 번 확인(실패는 경고만). `ensureIndices()` 는 전부(시험이 Logstash 를 띄우기 전에). 검색 기록 색인 문제가 상품 검색을 막지 않는다.
- 카테고리·필터 패널 사본(2026-11-19, 성능 분석 1순위 ② — 사용자 "카테고리 들어갈 때도 느려"): 검색 요청은 다시 읽기를 기다리지 않는다 — 만료된 사본을 그대로 쓰고 뒤에서 한 번만 다시 읽음(`CategoryTreeClient.once`, 가상 스레드). 처음(사본이 아예 없을 때)만 기다리고 동시 요청은 같은 읽기 하나를 기다린다. 사본 없이 실패하면 빈 사본을 30초(결과마다 다시 부르지 않음). 없는 필터 패널은 병렬로. 시험 `CategoryTreeClientTest`.
- carried(2026-11-05, 사용자 결정): 색인 `carriedMfcIds` = 대표 SKU 를 켠 센터 목록. 원천은 mfc-inventory `SkuHandlingChanged`(`mfc.sku-handling.v1`) → Logstash 가 SKU 취급 색인 `sku-handlings`(센터별 버전 비교) → 그 SKU 를 대표로 둔 상품에 복사(상품 사건이 먼저 와도 SKU 취급 문서를 찾아 붙임 — 가격과 같은 방식). mfcId 를 주면 carried = 목록에 있는지(취급 레코드 없음 = false), includeNotCarried=false(기본)면 ES 필터로 거른다 → 페이지 크기·total 정확. 실시간 가용 조회·"더 읽어 채우기"(BR-47)는 없앴다. 품절은 색인하지 않는다(화면 표시 때 실시간). 여러 SKU 상품이 생기면 판정 기준(대표 SKU vs 하나라도)을 다시 정한다 — `sku-handlings` 는 SKU 별이라 그대로 쓸 수 있다.
- 승인 속성 필터(2026-11-04): PVP `filterOptions` → 색인 `filters`("그룹.옵션"), 검색 `filters` 같은 그룹 OR·그룹끼리 AND, `appliedFilters` 는 실제 적용 값. `/search/filters` = 색인 집계 건수 + product-service 필터 패널 이름·선택 방식·옵션 순서(패널은 상속이 없어 그 카테고리와 조상을 가까운 것부터 합침, categoryId 없으면 최상위 노드들 — 5분 사본).
- 최근 검색어·인기 키워드(2026-11-17 사용자 결정, Commerce §6.7·6.9):
  - 기록: `GET /search/products` 첫 페이지(cursor 없음)·q 있음일 때 `SearchActivity` 가 작은 작업 큐(작업자 2·대기 1000, 차면 버림)에서 비동기로 남긴다 — 검색은 기록 때문에 늦어지거나 실패하지 않는다. 실패 로그에는 검색어·검색자를 넣지 않는다. 검색어는 `SearchKeywords.normalize`(공백 정리, 100자, 초성도 그대로).
  - 검색자 = `HMAC-SHA256(비밀키 pmart.search.searcher-hash-key, 사용자 종류:주체 ID)` 해시만 색인에 둔다(고객 ID 원문 없음, 키 없이는 짐작한 ID 로 맞춰 볼 수 없음 — LAN 키는 {cipher}, 원본 .secrets/search-searcher-hash-key). 키가 없으면 기록하지 않고 최근 검색어 조회는 CONFIG_REQUIRED.
  - 최근 검색어: 고객(CUSTOMER) 토큰만. 색인 `search-recent`(→ `search-recent-v1`), 문서 id = SHA-256(검색자 해시 + 검색어) — 같은 검색어는 덮어써 맨 위로(외부 버전 = 검색 시각 ms, 뒤바뀐 쓰기 방지). 쓸 때 최신 `max-items` 밖을 지우고, 조회는 `retention` 안의 최신 `max-items` 개. 그 밖의 종류는 403(비회원은 보안 체인), 설정 없음은 422 CONFIG_REQUIRED. 삭제는 본디 멱등(Idempotency-Key 저장 안 함 — DB 없음).
  - 인기 키워드(`/search/suggestions` 의 popular): 고객·비회원 토큰 검색을 색인 `search-log`(→ `search-log-v1`, 검색 하나당 문서)에 남기고, `window` 안 결과 1건 이상 검색을 검색어별로 세어 서로 다른 검색자 `min-searchers` 명 이상만 검색 횟수 순 `size` 개, 숨김 목록 `hidden-keywords` 제외(`SearchKeywords.popular`). `refresh` 동안 메모리 사본, 실패면 이전 사본·빈 목록. 익명·운영자 검색은 세지 않는다.
  - 설정(pmart-config `search-service.yml`): `pmart.search.recent.{max-items,retention}`, `pmart.search.popular.{window,size,min-searchers,hidden-keywords,refresh}`. 하나라도 없으면 기록하지 않고 인기 키워드는 빈 목록(WARN 한 번) — 기본값으로 대신하지 않는다.
  - 정리: 매일 04:17(Asia/Seoul) `SearchHousekeeping` 이 retention 지난 최근 검색어·window+1일 지난 검색 기록을 조건 삭제.
  - 시험: `SearchActivityIT`(실제 ES, 상품 문서를 직접 넣음), `SearchKeywordsTest`·`PopularKeywordsTest`.
- **순위 신호 정렬(2026-11-19 ADR-0072, 계약 0.3.0, products-v6)**: Logstash 가 `ProductRankingSignalsChanged`(commerce.product-ranking.v1 — storefront 가 셈)를 상품 문서 ratingCount·ratingAverage·ratingScore·salesWindow 에 쓴다(signalsVersion 큰 것만). 정렬 POPULAR(판매 → 점수)·RATING(베이지안 점수 → 리뷰 수)·REVIEW_COUNT(새, 리뷰 수 → 점수), 신호 없으면 맨 뒤, RELEVANCE 는 그대로. 결과 `rating`·`reviewCount`. 재색인 뒤 storefront 다시 내기(REINDEX.md §2-7). 시험 `SearchFlowIT.rankingSignalsDriveSorts`.
- 아직 없음: 재색인 API(501 — 재색인은 `deploy/lan/elastic/REINDEX.md` 절차), 숨김 목록 운영 API(지금은 설정).
- 재색인(ADR-0034): Kafka 전체 재생 → 별칭 교체, 절차 `deploy/lan/elastic/REINDEX.md`, 실행 `deploy/lan/elastic/reindex.sh`(check·start·wait·switch·finish). 색인 정의(`products-v5`)에는 별칭을 넣지 않는다 — 별칭이 없을 때만 붙이고(새 환경) 전환은 절차가 한다. Logstash 대상 색인·소비 그룹은 `deploy/lan/elastic/search-index.env`.
- 판매 상태(2026-11-04): 상품 행 버전(PVP `rowVersion`·PSSC `version`)이 큰 것만. 행 버전 없는 옛 사건은 행 버전 기준 상태가 없을 때만 시각 비교. storefront 도 같은 규칙.
- 콘텐츠 보강 실패(2026-11-04): 2초 간격 2번 더 시도 → 그래도 실패면 색인하지 않고 `search-index.DLT`(원래 토픽·파티션·오프셋) + 경보 `SearchIndexDeadLetter`.
- 띄어쓰기(2026-11-05 products-v4): 띄어 쓴 ↔ 붙여 쓴 검색어·상품명을 양쪽으로 찾는다(nori 형태소 또는 `name.compact`). 낱말 순서가 다르면 형태소로만 찾는다. 동의어 없음(`SearchFlowIT.spacingVariants`).
- 파이프라인 감시(2026-11-04): watch.sh 가 소비 지연·Logstash 큐·DLQ·ES 거절·마지막 반영 시각을 지표로 → 경보 `SearchIndexStalled` 등(`deploy/lan/monitoring/alert-rules.yml`).

- **할인가 표시(2026-11-21 ADR-0082 4단계, 계약 0.6.0)**: Logstash 가 commerce.sku-display-discount.v1 `SkuDisplayDiscountChanged` → 색인 `sku-discounts`(→ `sku-discounts-v1`, 문서 = SKU, seq 큰 것만, env `PMART_SEARCH_DISCOUNTS_INDEX`). 상품 색인은 그대로(재색인 없음) — `ProductSearch.withDiscounts` 가 쪽의 대표 SKU 로 terms 조회해 priceVersion 같고·unitDiscount>0·끝 전일 때만 `salePrice`·`discountRatePercent`·`discountName`·`discountEndsAt`. 할인 색인을 못 읽으면 정가(검색은 성공). 가격순·가격 범위는 기본가. 토픽은 compact(LAN topics-init) — 재색인 재생에도 SKU 마다 마지막 상태가 남는다. **LAN: Logstash 가 처음 쓰기 전에 es-init(또는 검색 첫 요청)이 색인을 만들어야 한다**(없으면 ES 가 동적 매핑 색인을 만들어 별칭이 막힘). 시험 `SearchFlowIT.eventsBecomeSearchableAndStayConsistent`.

## 상태
상품 검색·자동완성·필터(빈 목록) 구현(2026-10-30). 최근 검색어·인기 키워드 구현(2026-11-17, 계약 0.2.0).
