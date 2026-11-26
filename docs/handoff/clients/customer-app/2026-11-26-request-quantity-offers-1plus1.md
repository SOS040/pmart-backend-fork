# 요청 — 1+1·2+1(수량 행사) 화면 (ADR-0090)

보낸 영역: platform
받는 앱: customer-app
작성: pmart-backend PC 1, 2026-11-26 — 사용자 "고객앱에 마감세일이랑 1+1 띄우게 백엔드에 기능 추가해" → 정할 것 A1~A6 "추천대로"
결정: `docs/adr/0090-quantity-offers-and-clearance-sale.md`(마감세일은 다음 — 백엔드 준비 뒤 따로 알림)
상태: 백엔드 **LAN 배포** — promotion 0.5.0·pricing 0.4.1·cart 0.6.0·order 0.26.1·claim 0.15.1. 실제 행사는 백오피스에서 만들고 승인해야 보인다(지금 LAN 행사 0개).

## 규칙 (앱이 알아야 할 것)
- 같은 상품 N개(유상)를 사면 M개를 더 준다(1+1 = 1·1, 2+1 = 2·1). 유상 수량의 N 배수마다 M개.
- **고객이 고르는 수량 = 유상 수량.** 무료 수량은 서버가 더한다. 무료분까지 재고가 없으면 주문서가 다른 품절과 같이 422 `INSUFFICIENT_STOCK`(무료분만 줄여 주지 않음).
- 1+1 이 걸린 상품은 기간 할인가가 없다(둘 중 1+1 만). 장바구니 쿠폰은 낸 금액에 적용.
- 반품은 묶음 단위만 — 아니면 422 `RETURN_BUNDLE_REQUIRED`(1+1 은 2개씩, 2+1 은 3개씩).

## API
| 화면 | API | 쓸 값 |
|---|---|---|
| 홈 할인 탭 "+1 하나더" | `GET /api/v1/promotions/quantity-offer-items?limit=`(회원·비회원 토큰) | `items[{sku, productId, promotionId, name, buyQuantity, freeQuantity, endsAt}]` — 끝 빠른 순. 카드(이름·사진·가격·센터 판매)는 productId 로 지금처럼 받는다. TOP30 순서는 화면이 정한다(예: 랭킹 순) |
| 상품 카드·상세 배지 "1+1"·"2+1" | 위 목록을 한 번 받아 SKU 로 표시하거나, `GET /api/v1/prices?skus=` 의 `quantityOffer{promotionId, name, buyQuantity, freeQuantity, endsAt}` | 배지 글자 = `buyQuantity + "+" + freeQuantity` |
| 장바구니 | `GET /carts/{id}` 품목 `display.quantityOffer`·`display.freeQuantity` | "1+1 · 무료 N개" 안내. `quantity` 는 유상, 합계(`selectedItemAmount`)는 유상만. 재고 표시(availability)는 총 수량 기준 |
| 주문서 | 주문 초안 줄 `quantity`(총)·`paidQuantity`·`freeQuantity`·`offerPromotionId`, 무료분은 `discountShare` 안 | 유상 N + 무료 M 으로 나눠 보이기 |
| 주문 내역·상세 | 주문 줄·목록 줄 `paidQuantity`·`freeQuantity`·`offerPromotionId` | 같음 |
| 반품 신청 | 반품 수량을 묶음 배수로만 고르게(1+1 → 2·4·6…) | 422 `RETURN_BUNDLE_REQUIRED` 안내 문구 |

## 확인 방법
- 백오피스(PC 2 화면 전)에서는 행사를 만들 화면이 아직 없다 — 백엔드가 LAN 시험 행사를 하나 만들어 줄 수 있다(요청하면). 
