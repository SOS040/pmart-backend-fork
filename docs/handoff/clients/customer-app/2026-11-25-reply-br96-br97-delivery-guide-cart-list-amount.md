# 회신 — BR-96 배달 안내 값 · BR-97 장바구니 할인 전 합계

보낸 영역: platform
받는 앱: customer-app
원 문서: `pmart-clients docs/handoff/customer-app/2026-11-24-backend-delivery-guide.md`(BR-96 R1~R3)·`2026-11-24-backend-cart-list-amount.md`(BR-97 R1)
작성: pmart-backend, 2026-11-25 — 사용자 "응"(BR-97·96 먼저)
상태: **LAN 배포 끝**(2026-11-25, order·cart 09c347ef) — LAN `GET /delivery-options?mfcId=MFC-BJG-01` 에 minOrderAmount VALUE 15000·tiers [30000→0, 0→4000]·serviceHours 08:00~24:00 확인

## BR-97 (cart 0.5.0)
- R1 `Cart.selectedListAmount`(정수 원, nullable) — 선택 품목 기간 할인 **전** 단가 합계. `selectedItemAmount` 가 null 이면 null. 주문 초안 `Quote.baseItemAmount` 와 같은 기준이고 늘 `selectedItemAmount + selectedDiscountAmount`. 변경 응답의 cart 에도 있다. 앱의 더하기를 이 값으로 바꾸면 된다.

## BR-96 (order 0.22.0, `GET /delivery-options`)
- R1 방식마다 `minOrderAmount` = PolicyValue `{state: VALUE, value: 15000}` — 주문 초안·장바구니와 같은 설정값. 설정이 없으면 `{state: NOT_SET}`(0 아님).
- R2 방식마다 `deliveryTip.tiers[]` = `{fromAmount, tip}` fromAmount 높은 순 — 지금 `[{30000, 0}, {0, 기본 배달팁}]`(한집 4,000 · 알뜰·예약 3,000). 상품액 ≥ fromAmount 인 첫 구간의 tip. 첫 구매 무료 같은 개인 사유는 없다(그건 지금처럼 `applied`·`reason`). "15,000원 이상 3,000원"은 최소 주문과 겹치므로 구간으로 따로 두지 않았다 — 최소 주문 아래는 주문이 안 된다.
- R3 `serviceHours` = `{start: "08:00", end: "24:00"}`(Asia/Seoul, 한집·알뜰 실제 배송 시간 — 그 밖은 지금처럼 NIGHT_TIME·예약만). 센터별 휴무는 여전히 방식의 `availability`·`unavailableReason` 으로.
- 모두 선택 필드 — 앱은 없으면 그 줄을 빼면 된다.
