# 회신 — BR-93 주문 줄 상품명 사본 채우기

보낸 영역: platform
받는 앱: customer-app
원 문서: `pmart-clients docs/handoff/customer-app/2026-11-24-backend-order-line-name-backfill.md`(R1·R2·Q1)
작성: pmart-backend, 2026-11-24 — 사용자 "R1으로 해줘"
상태: **R1 LAN 반영 끝**(주문 줄 18·클레임 품목 12). R2 는 하지 않음

## 1. 원인·Q1
- 본 주문 96249223 은 **2026-10-31 확정** — 상품명 사본(BR-35, 2026-11-02)보다 앞선 주문이라 이름이 비어 있었다. 초안 때 조회 실패가 아니다.
- LAN 에서 이름이 빈 주문 줄: 전체 215줄 중 18줄(주문 6개). 2026-11-02 이후는 1개 주문 2줄뿐(그날 BR-35 배포 무렵) — 이름 조회 실패로 빈 줄은 사실상 없다.

## 2. R1 — 한 번 채움(LAN)
- 18줄 모두 productId 가 있어, 주문할 때와 같은 product-content 내부 스냅샷(`/internal/product-contents/{productId}/snapshot?sku=`)에서 이름을 받아 `customer_order_line.product_name` 에 넣었다(18/18). 옵션 라벨은 단일 옵션이라 스냅샷도 비어 있어 그대로 null.
- 같은 주문 줄을 가리키는 클레임 품목 사본(BR-92 `claim_line.product_name`)이 비어 있던 12줄도 같은 이름으로(12/12).
- 이름은 **지금** 상품 이름이다(그 주문 때 이름 기록이 없어서). 지어내거나 SKU 로 채우지 않았다.
- 앱은 바꿀 것 없음 — `GET /orders/{id}`·`GET /claims/{id}` 가 이제 이름을 준다.

## 3. R2 — 하지 않음
- 사용자 결정(R1 만). 2026-11-02 이후 조회 실패로 빈 줄이 거의 없어서, 앞으로 생기면 다시 요청해 주면 된다.
