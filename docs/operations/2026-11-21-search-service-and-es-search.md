# search-service 와 es-search — 무엇을 하나 (2026-11-21)

찾아보기용 정리(결정 문서 아님). 정본: `services/search-service/CLAUDE.md`, ADR-0031(검색 색인)·ADR-0038(검색·로그 ES 분리)·ADR-0073(동의어·오타).
`es-service` 라는 서비스는 없다 — 검색용 Elasticsearch 컨테이너는 `es-search` 다.

## 배치(LAN, `deploy/lan/placement.conf`)
- **search-service**(앱): PC 2
- **es-search**(Elasticsearch 8.18 + nori, 9200) + **logstash-search**: PC 3
- **es-logs** + Kibana(앱 로그용, 상품 검색과 별개): PC 4

엘라스틱서치는 search-service 안에 들어 있지 않다. search-service 는 HTTP(REST, 클라이언트 라이브러리 없음)로 붙어서 쓴다. search-service 를 다시 배포해도 ES 데이터는 그대로다.

## search-service (PC 2)
고객·운영자에게 검색 API 를 준다. ES 에서 읽어 화면 모양으로 돌려준다. 상품 정본은 갖지 않는다.

**고객용**
- **상품 검색** `GET /search/products`
  - 검색어: 형태소(nori), 부분 일치, 띄어쓰기 무시, 초성("ㄷㅂ" → 두부), 동의어.
  - 거르기: 카테고리(하위 포함), 가격 범위, 승인 필터 속성(예: 용량), 센터 취급(기본은 미취급 상품 숨김).
  - 정렬: 관련도·최신·가격순·인기순(최근 판매)·별점순·리뷰 많은 순.
  - 오타 교정: 결과 0건이면 고친 검색어로 다시 찾아 `correctedQuery`, 결과가 적으면 `suggestedQuery` 제안.
  - 결과마다 대표 SKU 가격·할인가(`sku-discounts`, 0.6.0)·판매 상태·평점·카테고리 이름 경로.
- **자동완성·인기 검색어** `GET /search/suggestions`
- **최근 검색어** 저장·조회·지우기(회원·비회원)
- **필터 패널** `GET /search/filters` — 지금 조건에서 고를 수 있는 필터 값과 건수

**운영용**
- 결과 0건 검색어(MD·OPERATOR_ADMIN)
- 동의어 규칙 조회 — 바꾸기는 승인(SEARCH_SYNONYM) 뒤 반영
- 색인 상태 `/internal/index-status` — 문서 수, 사건 종류별 마지막 반영 시각·지연

**search-service 가 직접 쓰는 색인**: 검색 기록(인기 검색어 집계), 최근 검색어, 동의어 규칙, 오타 사전(주기적으로 다시 만듦).

**코드 폴더 안에 있지만 다른 곳에서 도는 것**
- Logstash 파이프라인 `services/search-service/src/main/logstash/search-index.conf` → PC 3 logstash-search 가 실행.
- 색인 정의 `services/search-service/src/main/resources/elasticsearch/*.json` → LAN 은 PC 3 `es-init` 이 이 파일로 색인을 만든다.

## es-search (PC 3)
저장소이자 검색 엔진. 업무 로직 없음.

| 색인(별칭) | 내용 | 쓰는 쪽 |
|---|---|---|
| `products` (→ products-v7) | 상품 문서: 이름·사진·카테고리·대표 SKU 가격·판매 상태·취급 센터·승인 필터 속성·평점·최근 판매 수(salesWindow) | Logstash |
| `sku-prices` | SKU 별 현재 가격 | Logstash |
| `sku-handlings` | 센터별 취급 여부 | Logstash |
| `sku-discounts` | SKU 별 표시 할인(2026-11-21) | Logstash |
| `search-log`, `search-recent` | 검색 기록, 최근 검색어 | search-service |
| `search-synonym-rules`, `search-terms` | 동의어 규칙, 오타 사전 | search-service |

**색인하지 않는 것**: 상품 상세 설명·영양·원산지 정보(product-content DB 에만 — 검색어 매칭·필터에 안 쓰임), 주문 한 건 한 건(판매는 상품마다 숫자 salesWindow 만).

## logstash-search (PC 3, es-search 짝꿍)
Kafka 사건(상품·콘텐츠·판매 상태·가격·센터 취급·순위 신호·표시 할인)을 받아 위 색인에 쓴다. 콘텐츠 사건에는 이름·사진이 없어 product-content 공개 카드 API 로 보강한다. 사건 하나를 다 쓴 뒤 다음 사건(작업자 1).

## 흐름
정본 서비스가 사건 발행 → Kafka → logstash-search → es-search 색인 → search-service 가 읽어 응답. 반영은 몇 초 늦을 수 있다(검색 결과 ≠ 재고 보장).
