# [pmart-backend → pmart-clients] 회신: 주문 품목 상품명 사본·결제창 주문명 (BR-35) (2026-11-02)

- 요청: `pmart-clients/docs/handoff/2026-10-31-backend-checkout-widget-and-product-names.md` §2 (BR-35). §1(BR-34)은 `2026-11-02-clients-reply-br34-payment-widget-config.md`.
- 계약: `contracts/openapi/order-service.yaml` `DraftLine.productName`·`optionLabel`(신규), `OrderLine.productName`(채움)·`optionLabel`(신규)
- 구현: order-service(V6 `customer_order_line.product_name`·`option_label`), product-content `GET /internal/product-contents/{productId}/snapshot` 사용

## 1. 동작 — 요청서대로
- **초안을 만들 때**(`POST /order-drafts`) 품목마다 product-content 의 고객용 상품명·옵션 라벨을 복사해 **초안에 고정**한다 → `DraftLine.productName`·`optionLabel`.
- **주문 확정 때** 그 사본을 그대로 `OrderLine.productName`·`optionLabel` 로 옮긴다(확정 때 다시 조회하지 않는다). 이후 상품명이 바뀌어도 과거 주문은 그대로다.
- **조회에 실패하면 비운다**(`null`, 지어내지 않음). 초안 생성은 막지 않는다. 앱은 요청서대로 **그때만 sku 로 대신** 보인다.
- 2026-11-02 배포 전에 만든 초안·주문은 `null` 이다.

```json
"lines": [{ "orderLineId": "ORD-…-L1", "sku": "E-00000001", "productName": "국산콩 두부 300g", "optionLabel": "300g", … }]
```
- `optionLabel` 은 옵션이 하나뿐인 상품이면 `null` 일 수 있다. 화면에 함께 보일지는 앱 판단(단일 SKU 상품은 옵션을 숨기는 지금 정책과 맞추면 된다).

## 2. 결제창 주문명 (`payment.orderName`)
- 첫 품목(이름이 있는 첫 품목)의 상품명 + `" 외 N건"`(N = 나머지 품목 수). 한 품목이면 상품명만.
  - 예: `국산콩 두부 300g 외 2건`, `국산콩 두부 300g`
- 이름을 하나도 받지 못했으면 지금처럼 `PMart 장보기 N개 상품`.
- 토스 주문명 상한(100자)을 넘으면 상품명을 줄여 `…` 를 붙인다(`외 N건` 은 남긴다).
- 앱은 지금처럼 받은 `payment.orderName` 을 `requestPayment(orderName: …)` 에 그대로 넘긴다.

## 3. 클레임 화면
- `ClaimLine` 에는 이름이 없다. 클레임 품목은 `orderLineId` 로 주문 상세(`GET /orders/{orderId}`)의 `lines[].productName` 과 맞춰 보인다. 클레임 응답에 이름을 직접 넣어야 하면 따로 요청해 달라.

## 4. LAN 반영
- **LAN 반영 2026-11-02 19:00 KST** — pmart-backend `00205b0`(order-service 이미지), pmart-config `180d34d`. order-service V6(`customer_order_line.product_name`·`option_label`) 적용, UP. 이 배포 전 초안·주문의 품목은 이름이 `null` 이다.
