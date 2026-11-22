# 회신 — BR-83 할인 목록 필터 건수·할인율순 정렬

보낸 영역: platform
받는 앱: customer-app
요청: pmart-clients `docs/handoff/customer-app/2026-11-22-backend-sale-list-filters-sort.md`(BR-83)
작성: pmart-backend, 2026-11-22 — 사용자 "일단 가로 진행해줘"(R1 + R2-(가))
계약: **search-service 0.9.0**(`contracts/openapi/search-service.yaml`)
상태: **LAN 반영 2026-11-22**(search 7d6d6b09, PC 2)

## R1 — 할인 목록의 필터 건수
- `GET /search/filters?onSale=true`(q·categoryId 와 함께 쓸 수 있음) — 건수를 **할인 중인 상품만으로** 센다. `onSale` 이 없거나 false 면 전처럼 전체.
- 할인 판정은 `GET /search/products?onSale=true` 와 같다(대표 SKU 할인 중·가격 버전 같음). 그래서 필터에 보이는 건수 = 그 필터를 걸었을 때 할인 목록 건수.
- LAN 확인: 원산지 줄 합계 — 전체 427, `onSale=true` 7.

## R2-(가) — 할인율순
- `sort=DISCOUNT_RATE`(SortKey 에 추가). **`onSale=true` 와 함께만** — 없이 부르면 400 VALIDATION_FAILED("할인율순은 할인 상품 목록(onSale=true)에서만 쓸 수 있습니다.").
- 순서: 할인율(`discountRatePercent`) 높은 것 → 같으면 할인가(`salePrice`) 낮은 것 → productId. `appliedSort` = `DISCOUNT_RATE`.
- 쪽 나누기는 다른 정렬과 같다(`size`·`page.nextCursor`). 할인 상품은 최대 1,000개까지 본다(`onSale` 과 같은 상한) — 지금 LAN 할인 상품 7개.
- 앱: 할인 목록 화면의 정렬 메뉴에만 "할인율순"을 넣고, 일반 검색·카테고리 목록에는 넣지 않는다.

## 시험
- search-service `check` 51 통과(단위 `DiscountSortTest`, 통합 `SearchFlowIT.onSaleKeepsOnlyDiscountedProducts` — 할인율순 쪽 나누기·400·`onSale` 필터 건수).
- LAN: `onSale=true&sort=DISCOUNT_RATE&size=5` 200(7건, 다음 쪽 커서 있음), `sort=DISCOUNT_RATE` 만 400.
