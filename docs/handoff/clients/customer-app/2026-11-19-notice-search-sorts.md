# 알림 — 검색 정렬 "인기순·별점순·리뷰 많은 순"이 실제로 동작 (search 0.3.0)

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 계기: 사용자 2026-11-19 "추천대로 5번 6번부터" — ADR-0072, 계획 `docs/operations/2026-11-19-search-next-plan.md`
- 상태: **LAN 배포됨 2026-11-20 00:06**(storefront 7e9d2ef1·search-service, 색인 products-v6). 게이트웨이 경유 확인: sort=RATING·REVIEW_COUNT·POPULAR 의 appliedSort 가 그 값, 리뷰 많은 순 1위 햇양파(480개·4.1), 결과 rating·reviewCount 채워짐

## 바뀐 것
- `GET /search/products?sort=` — `POPULAR`(인기순 = 최근 30일 판매), `RATING`(별점순 — 리뷰가 적은 높은 별점은 덜 믿는 점수), **`REVIEW_COUNT`(리뷰 많은 순, 새 값)**. 전에는 POPULAR·RATING 이 관련도로 대체됐다(appliedSort 가 RELEVANCE).
- 신호가 없는 상품(리뷰·판매 없음)은 그 정렬에서 맨 뒤. 기본(RELEVANCE)·가격·최신순은 그대로.
- 결과 항목 `rating`(평균 소수 첫째)·`reviewCount` 가 채워진다(리뷰 없으면 비어 있음). 홈 진열(storefront `rating`·`reviewCount`, BR-63)과 같은 값.

## 앱이 할 것
- 검색·카테고리 정렬 메뉴에 "인기순"·"별점순"·"리뷰 많은 순"(값 `POPULAR`·`RATING`·`REVIEW_COUNT`). `appliedSort` 로 실제 적용된 정렬을 확인.
- 카드에 별점·리뷰 수를 보이고 있다면 검색 결과 값으로(카드마다 따로 부르지 말 것).
