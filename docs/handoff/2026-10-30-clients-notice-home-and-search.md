# [pmart-backend → pmart-clients] ③ 홈·상품 검색 안내 (2026-10-30)

- 관련: `2026-10-30-clients-reply-customer-app-priority.md` 의 ③(요청 2-4: 홈 1섹션 + `GET /search/products?q=`, 색인 원천·순서)
- 계약 정본: `contracts/openapi/storefront-service.yaml`, `contracts/openapi/search-service.yaml`. 모두 **토큰 없이** 부른다.
- LAN 배포됨(이미지 018c84d). 게이트웨이 `http://192.168.0.40:8080` 에서 시험 상품 6개로 확인.

## 1. 홈 (storefront)
| 작업 | API | 메모 |
|---|---|---|
| 홈 | `GET /api/v1/storefront/home` | `layoutVersion: 0`, `tabs: [HOME]`, `banners: []`, `sections: [S-ALL]` |
| 섹션 다음 묶음 | `GET /api/v1/storefront/sections/S-ALL/items?layoutVersion=0&cursor=...` | 홈 섹션의 `nextCursor` 를 넘긴다. 마지막이면 `page.nextCursor` 가 null |

- 지금 홈은 **기본 진열** 하나다: 섹션 `S-ALL` "전체 상품"(최근 등록 순, 첫 묶음 10개). 진열 편성 승인(테마·랭킹·신상품·배너)이 생기면 `layoutVersion` 이 올라간다. 섹션 조회에 옛 `layoutVersion` 을 보내면 409 `LAYOUT_VERSION_CHANGED` → 홈을 다시 부른다.
- `RANKING`·`NEW` 탭은 아직 없다(404 `TAB_NOT_CONFIGURED`). 탭 목록은 응답의 `tabs` 를 그대로 그린다.
- 상품이 하나도 없어도 홈은 200(섹션 `state: OK`, `items: []`).

## 2. 상품 검색 (search)
| 작업 | API | 메모 |
|---|---|---|
| 검색 | `GET /api/v1/search/products?q=&categoryId=&sort=&size=&cursor=` | `total`, `page.nextCursor`, `appliedSort` |
| 자동완성 | `GET /api/v1/search/suggestions?q=` | `keywords`(상품명), `popular: []` |
| 필터 | `GET /api/v1/search/filters` | 지금은 `attributes: []`(승인 속성 원천 없음) |

- 검색어: 글자 조각 일치라 "미역" 으로 "소고기 미역국" 이 나온다. 띄어 쓴 단어는 모두 들어 있어야 한다("두부 300g").
- 카테고리: 상위 id(예 `t02`)를 주면 하위(`t02-c01`, `t02-c02`) 상품까지 나온다.
- 정렬: `RELEVANCE`(기본, 검색어 없으면 최근 등록 순)·`NEWEST`·`PRICE_ASC`·`PRICE_DESC`. `POPULAR`·`RATING` 은 원천이 없어 관련도로 처리하고 `appliedSort: RELEVANCE` 로 알린다.
- 판매중지 상품은 홈·검색에서 빠진다. 일시품절은 `saleState: TEMPORARILY_SOLD_OUT` 으로 보인다.

## 3. 공통 필드 (DisplayItem·SearchHit)
- `imageUrl`: `/api/v1/content-images/...` 상대 경로 — 게이트웨이 주소를 앞에 붙여 `<img src>` 로 쓴다(토큰 불필요, ②).
- `basePrice`·`priceVersion`: 가격 사본. 없으면 null → "가격 미정"(0원 아님). 확정 금액은 주문서에서.
- `carried`: **늘 null(불명)** — 센터 취급 사건이 아직 없다. 취급 안 함(false)으로 표시하지 않는다.
- `categoryPath`: 지금은 빈 배열(카테고리 이름 투영 전). 카테고리 이름은 `GET /categories/tree` 로.
- `rating`·`reviewCount`: 리뷰 서비스 전이라 없음.

## 4. 색인 원천·반영 순서 (요청 2-4 질문)
- 원천 사건: `ProductVersionPublished`(상품·카테고리·대표 SKU·판매 상태), `ProductContentPublished`(+ product-content 공개 카드로 이름·대표 이미지 보강), `ProductSaleStateChanged`, `PriceVersionEffective`.
- 상품이 홈·검색에 나오려면 **상품 승인 + 콘텐츠 게시** 둘 다 필요하다. 가격은 늦게 와도 나중에 붙는다(그때까지 `basePrice: null`).
- 반영은 비동기라 승인 직후 수 초 늦을 수 있다. 사건 순서가 바뀌거나 다시 와도 결과는 같다(버전 비교).
- 검색엔진은 OpenSearch(ADR-0021), 홈은 storefront 자체 DB.
