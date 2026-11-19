# ADR-0072 상품 순위 신호 — storefront 가 별점·판매를 세어 사건으로, 검색 색인이 정렬에 쓴다

- 상태: 승인 (2026-11-19, 사용자 "추천대로 5번 6번부터 해줘" — 계획 `docs/operations/2026-11-19-search-next-plan.md` 5·6)
- 관련: ADR-0031(색인은 Logstash 만), ADR-0034(재색인 = Kafka 재생), ADR-0063(리뷰), 클라이언트 BR-63(진열 평점, storefront `review_rating`), `deploy/lan/elastic/REINDEX.md`

## 배경
- 검색 계약에 `POPULAR`·`RATING` 정렬이 처음부터 있었지만 원천(판매 집계·리뷰)이 없어 관련도로 대체했다(appliedSort 로 알림).
- 리뷰 집계는 2026-11-19 생겼다(product-content·storefront 사본). 판매는 아무도 세지 않았다.
- 검색 색인은 Logstash 만 쓴다(ADR-0031) — 집계(리뷰 수정·삭제, 판매 창이 하루씩 밀림)는 Logstash 가 하기 어렵다(상태·주기 계산).

## 결정
1. **storefront-service 가 순위 신호의 정본** — 서비스 지도상 랭킹 탭 담당이고, 이미 진열 평점 사본(review_rating)을 가진다.
   - 판매 원장 `sku_sales_line`(V7): `OrderConfirmed`(order.order.v1) 줄마다 확정 시각·수량, `OrderLineQuantityChanged`(누적 값·주문 버전)로 **순 수량 = 원수량 − 출고 전 취소 − 반품**(주문 버전이 큰 것만). 취소는 줄 수량 변경 사건으로 온다(전체 취소도 줄마다).
   - 상품 = 대표 SKU(지금은 상품 하나에 SKU 하나 — 여러 SKU 가 생기면 합산으로 바꾼다).
2. **신호**: 상품마다 `ratingCount`·`ratingAverage`(소수 첫째)·`ratingScore`(베이지안 평균 = (사전 개수 × 사전 평균 + 별점 합) ÷ (사전 개수 + 리뷰 수) — 리뷰가 적은 높은 별점을 덜 믿음)·`salesWindow`(최근 N일 순 판매). 리뷰가 없으면 점수 없음(0점 아님).
   - 정책값 `pmart.storefront.ranking.{sales-window-days, rating-prior-count, rating-prior-mean}`(LAN 30 · **20** · 4.0 — 사용자 추천 채택 "최근 30일 주문 확정 수량, 취소·반품 뺌". 사전 개수는 처음 5 였다가 2026-11-20 20 — 5 에서는 리뷰 1개 5.0(4.17)이 480개 4.1(4.10)보다 위). 없으면 신호를 내지 않는다(기본값으로 대신하지 않음).
3. **사건** `ProductRankingSignalsChanged` v1(commerce.product-ranking.v1, aggregate ProductRanking = productId, 상품마다 version 1부터): 리뷰·주문 사건으로 값이 **바뀌었을 때만**, 그리고 매일 04:40(Asia/Seoul) 전부 다시 세어(판매 창이 밀림) 바뀐 것만. 내부 `POST /internal/storefront/ranking-signal-republications` 는 지금 값을 모두 다시 낸다(새 색인 채우기).
4. **검색 색인**: `products-v6`(v5 + ratingCount·ratingAverage·ratingScore·salesWindow·signalsVersion). Logstash 가 신호 사건을 받아 **version 이 큰 것만** 상품 문서에 쓴다(ADR-0031 그대로 — 검색 서비스는 조회만).
5. **정렬**(검색 계약 0.3.0): `POPULAR` = 판매 → 별점 점수, `RATING` = 별점 점수 → 리뷰 수, `REVIEW_COUNT`(새) = 리뷰 수 → 별점 점수. 신호가 없는 상품은 그 정렬에서 맨 뒤. **기본 순위(RELEVANCE)는 그대로**(섞는 것은 써 보고 정함 — 사용자 추천 채택). 결과 `rating`·`reviewCount` 를 채운다.

## 결과
- 재색인: `products-v6` 는 §2 절차(Kafka 재생) 뒤 **storefront 다시 내기**를 한 번 부른다 — 신호 토픽은 무제한 보관이 아니어서(재생으로는 다 오지 않을 수 있다) 지금 값으로 채운다.
- 고객 앱: 정렬 메뉴에 "인기순"·"별점순"·"리뷰 많은 순"(계약 값 그대로).
- 판매량 숫자 자체는 고객 화면에 보이지 않는다(정렬에만 — 노출은 따로 정할 일).
