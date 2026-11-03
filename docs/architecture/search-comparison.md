# 상품 검색 — 구현 메모 대비 현재 상태

- 작성: 2026-11-03. 사용자 메모(검색 구현 방식·구현 순서 7단계)와 현재 구현을 비교한 문서다. **설계 결정 문서가 아니다** — 정본은 ADR-0031(검색·로그 Elastic Stack), `services/search-service/CLAUDE.md`, 계약 `contracts/openapi/search-service.yaml`.
- 확인한 근거: 색인 설정 `services/search-service/src/main/resources/elasticsearch/products-v2.json`, 질의 `ProductSearch.java`, 색인 파이프라인 `src/main/logstash/search-index.conf`, 계약.

## 1. 개념 메모 대조

| 메모 | 현재 프로젝트 |
|---|---|
| Lucene = 검색 엔진(문서·색인·id), 쓰기 편하게 만든 게 Elastic | 같다. **Elasticsearch 8.18**(내부 Lucene). 상품 하나 = 문서 하나, id = `productId`. |
| Kibana 는 사용자가 보는 것 | **운영자·개발자 도구**다. 고객 검색 화면이 아니며, 우리는 로그 조회(`pmart-logs-*`)에 쓴다. 고객 검색은 앱 → `search-service` → ES. |
| Query DSL | `ProductSearch` 가 Query DSL(JSON)을 직접 만든다 — `bool` 의 `must`(검색어)·`should`(점수 가산)·`filter`(카테고리·판매중지 제외). |
| Logstash — 상품정보 테이블 넣기 시작하면 | **테이블이 아니라 이벤트**를 넣는다. Logstash 가 Kafka 도메인 이벤트를 읽어 ES 에 반영한다(§3). |
| 어떤 테이블 데이터가 갱신될 때 가져올지 처음에 지정 | **어떤 이벤트를 받을지**를 지정한다: `commerce.product.v1`(ProductVersionPublished·ProductContentPublished·ProductSaleStateChanged), `commerce.pricing.v1`(PriceVersionEffective). |
| ㅇㅋ(초성만) → 나이키 | **없다.** 초성 색인·질의 없음. |
| 무선 이어폰 / 무선이어폰 | **보장되지 않는다.** nori(형태소)와 n-gram(1~2글자 조각, 모든 조각 일치)으로 붙임말은 대체로 찾지만, 띄어쓰기 정규화·동의어가 없고 이 사례를 지키는 시험도 없다. |
| MFC 지정으로 보여야 함 | **일부.** 요청의 `mfcId` 로 mfc-inventory 에 취급 여부를 조회해 표시·제외한다(§2-6). |

## 2. 구현 순서 7단계 대비

● 됨 · ◐ 일부 · ✕ 없음

| 단계 | 메모 | 현재 | 상태 |
|---|---|---|---|
| 1. 검색 인프라 | 클러스터, Nori, Alias | ES **단일 노드**(LAN, 보안 끔). nori 는 시작할 때 설치. 별칭 `products` → `products-v2`, `sku-prices` → `sku-prices-v1`. 매핑 변경은 새 색인 + 별칭 이동(적용된 매핑은 고치지 않음) | ◐ 클러스터·복제본 없음 |
| 2. 기본 검색 | 형태소, BM25, 브랜드·카테고리·가격 필터 | 상품명 3중 분석 — `name`(nori, decompound mixed) · `name.edge`(앞부분, edge n-gram 1~10) · `name.partial`(1~2글자 n-gram, 모든 조각 일치). 순위 형태소 > 앞부분 > 조각. 점수 ES 기본 **BM25**. **카테고리 필터**(구조 id 조상 범위), 판매중지 제외 | ◐ **브랜드·가격 필터 없음**(브랜드 데이터도 없음). 승인 속성 필터는 계약만, 빈 목록 |
| 3. 고급 검색 | 초성, 자동완성, 동의어, 오타 교정, 구문 | **자동완성** `GET /search/suggestions`(앞부분 일치) | ◐ 초성·동의어·오타 교정·구문 검색 없음 |
| 4. 검색 랭킹 | 판매량·평점·전환율·광고 | 정렬 키는 계약에 있음(RELEVANCE·POPULAR·NEWEST·PRICE_ASC·PRICE_DESC·RATING). 인기·평점은 관련도로 대신하고 `appliedSort` 로 알림 | ✕ 판매량·평점·전환율 원천 없음, 광고 없음 |
| 5. 색인 연동 | Debezium + Connect + Consumer + Bulk | Debezium(서비스 **outbox 표만**) → Kafka **도메인 이벤트** → Logstash(Kafka 입력, 소비 그룹 `search-index-logstash`) → ES(bulk). Painless 로 버전·시각 비교(재전달·옛 이벤트 무시). 이름·이미지는 콘텐츠 공개 API 로 보강(게시본만) | ● (테이블 CDC 가 아니라 이벤트 — §3) |
| 6. MFC별 노출 | 배송 권역, 판매 가능, SKU 옵션 | 판매중지 제외(색인). 요청 `mfcId` 로 **검색할 때마다** mfc-inventory `/internal/availability` 의 취급 여부 → `carried`, 기본(includeNotCarried=false)이면 그 페이지에서 제외(total 은 빼기 전). 권역 → MFC 결정은 customer·serviceability 가 하고 검색은 받은 `mfcId` 만 쓴다 | ◐ 재고(품절) 기준 노출 없음, SKU 옵션 검색 없음(대표 SKU 만 색인), 취급 여부가 색인에 없어 페이지 크기가 들쭉날쭉 |
| 7. 품질·운영 | 부하 시험, 품질 평가, 무중단 재색인, 모니터링 | 재처리 = 소비 그룹 오프셋 되돌림. 새 색인 + 별칭 전환 규칙. ES 상태는 watch.sh 감시 | ✕ 재색인·색인 신선도 API 501, 부하 시험·품질 평가 없음, ES·Logstash 지표가 Prometheus 에 없음 |

## 3. 가장 큰 설계 차이 — 테이블 CDC 가 아니라 이벤트
- 메모의 5단계(Debezium 으로 **업무 테이블 변경**을 가져오기)는 Eum 방식이다. ADR-0031 에서 일부러 택하지 않았다.
  - 다른 서비스 DB 직접 접근 금지(절대 규칙), 상품 테이블 구조가 바뀌면 검색이 깨진다.
- 각 서비스가 **확정 사실을 이벤트로** 내고(이 경로에도 Debezium 이 쓰이지만 outbox 표만 읽는다), 검색은 그 이벤트만 받는다.
- 그래서 검색에 새 값(브랜드·판매량·MFC 취급 등)을 넣으려면 **그 값이 이벤트에 실려야** 한다.

## 4. 다음에 할 만한 것 (우선순위 제안)

| 순위 | 항목 | 내용 | 우리 구조와 맞는가 · 필요한 것 |
|---|---|---|---|
| 1 | MFC 노출 정확도 | 센터별 취급 여부(필요하면 품절 여부)를 색인에 넣어 ES 에서 바로 거른다 → 페이지 크기 문제 해결 | 맞는다. 다만 **mfc-inventory 가 취급 변경 이벤트를 아직 내지 않는다**(이벤트 카탈로그에 없음) → 새 이벤트(예 `SkuHandlingChanged`) 계약(`contracts/events/`)·발행을 먼저 만들고, Logstash 파이프라인이 구독. 색인 매핑이 바뀌므로 `products-v3` + 별칭 이동. **품절까지 넣을지는 결정 필요**(재고 변화가 잦아 이벤트량이 많다) |
| 2 | 가격 필터 | `minPrice`·`maxPrice` 필터, 가격순 정렬 확인 | 맞는다. 가격은 이미 색인에 있다(`basePrice`). 계약에 파라미터 추가만 |
| 3 | 띄어쓰기·동의어·초성 | 공백 제거 필드, 동의어 사전, 초성 필드. "무선 이어폰/무선이어폰" 같은 사례를 시험으로 고정 | 맞는다(색인 설정 변경 → v3). **동의어 사전을 누가 관리할지 결정 필요**(파일·운영자 화면·승인 여부) |
| 4 | 운영 | 재색인 API·색인 신선도 API(지금 501), ES·Logstash 지표·경보 | 맞는다. 계약이 이미 있다 |
| 5 | 랭킹 | 판매량(OrderConfirmed 집계), 평점(리뷰 서비스 이후) | 판매량은 이벤트 구독으로 가능. **가중치·노출 정책은 업무 결정 필요**, 광고는 업무 문서에 규칙이 없어 정하기 전에는 넣지 않는다 |
