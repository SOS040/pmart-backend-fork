# 회신 — BR-36 상품 상세 `SaleOption.price` 가 늘 null

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 답하는 것: pmart-clients `docs/backend-requests.md` BR-36(요청 `docs/handoff/archive/2026-11-02-backend-product-detail-price.md`)
- 계약: product-content-service **0.1.2**(설명만 — 모양 그대로). LAN: **배포 전**(배포하면 이 줄을 고친다)

## 바뀐 것
- 상품 상세(`GET /product-contents/{id}`)와 카드(`GET /product-contents?productIds=`)의 옵션마다 **`price{basePrice, priceVersion}` 를 채운다** — 읽을 때 pricing `GET /prices` 사본(전에는 늘 null).
- 가격이 정해지지 않은 SKU 는 `price` 가 비어 있다(0원 아님).
- pricing 을 못 읽으면 가격을 비우고, 상세 `sources` 에 `pricing-service` `freshness: UNAVAILABLE` 이 붙는다 — "가격 확인 중"으로 보이면 된다.
- 견적(실제 낼 금액)은 지금처럼 주문 초안이 정한다.

## 앱에서
- 지금 따로 부르던 `GET /prices?skus=` 는 이 값이 오면 없애도 된다(옵션 `price` 가 비었을 때만 따로 부르는 식으로 두어도 됨).
