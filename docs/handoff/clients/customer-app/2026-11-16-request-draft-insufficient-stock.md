# [pmart-backend → pmart-clients] 요청: 주문서 진입 전 재고 부족 422 처리 (2026-11-16)

- 보낸 영역: platform
- 받는 앱: customer_app (장바구니 → 주문서 진입)
- 근거: 계약 `order-service.yaml` 0.8.0(LAN 배포 40150988), 사용자 결정 2026-11-16 "재고가 부족하면 주문서 진입 자체를 막기(422)"

## 1. 바뀐 것
`POST /order-drafts` 가 담당 센터 재고를 확인한다. 담은 상품 중 하나라도 **가용 < 주문 수량** 이거나 **센터가 취급하지 않으면** 초안을 만들지 않고 422 를 준다.
```json
{ "code": "INSUFFICIENT_STOCK", "status": 422,
  "detail": "재고가 부족한 상품이 있습니다. 장바구니에서 수량을 줄이거나 빼 주세요.",
  "details": [ { "field": "E-00000496", "reason": "requestedQty=2;availableQty=1" },
               { "field": "E-00000501", "reason": "NOT_HANDLED" } ] }
```

## 2. 앱에서 할 것
- **주문서 화면으로 넘어가지 않는다.** 장바구니에 머물러 `details[].field`(SKU) 상품에 표시한다.
  - `requestedQty=<q>;availableQty=<a>` → "남은 수량 a개" (a = 0 이면 "품절"), 수량 줄이기·빼기 안내.
  - `NOT_HANDLED` → "지금 이 지역에서 살 수 없는 상품".
- 고친 뒤 다시 주문하기는 새 `Idempotency-Key` 로(같은 `purchaseIntentId` 그대로 써도 된다 — 초안이 안 만들어졌으므로).
- 재고 조회가 잠깐 불명이면 서버는 막지 않는다 → 주문서가 열리고, 결제 단계 재고 예약에서 `INSUFFICIENT_STOCK` 로 끝날 수 있다(지금처럼). 그 화면 처리는 그대로 둔다.

## 3. 알아 둘 것
- 소비기한이 임박하거나 지난 재고는 아직 가용 숫자에 들어 있다(mfc-inventory 요청 IV-1, PC 3). 그래서 장바구니 "남은 수량"과 결제 단계 결과가 다를 수 있다 — 고쳐지면 다시 알린다.
