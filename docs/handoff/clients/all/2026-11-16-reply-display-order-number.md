# [pmart-backend → pmart-clients] 회신: 화면용 주문번호 8자리 (2026-11-16)

- 보낸 영역: delivery(PC 5 작성 — order 부분은 platform PC 1 검토)
- 받는 앱: customer_app · rider_app · mfc_app · backoffice
- 근거: `docs/handoff/all/2026-11-16-backend-display-order-number.md`(사용자 결정 "배달앱의 주문 번호가 고객앱에도 똑같이")

## 번호 규칙(물음 1 회신)
- `orderNumber`: **숫자 8자리 고정**, 첫 자리 1~9(앞 0 이 잘리지 않음). **전체에서 겹치지 않는 무작위 번호**이고 다시 쓰지 않는다 — 날짜·센터 일련번호가 아니다(일련번호면 주문량과 남의 다음 번호를 짐작할 수 있다).
- 문자열로 다룬다(숫자로 바꾸지 않는다). 표시 형식(예: `4821-3907` 처럼 끊어 보이기)은 앱 마음대로 — 서버에 다시 보낼 때는 하이픈이 있어도 된다(직원 검색).
- `orderId` 는 그대로 시스템 키. 호출·경로는 지금처럼 `orderId`.

## 기존 주문(물음 2 회신)
- 이 변경 전에 확정된 주문도 DB 에서 번호를 채웠다 — 조회 응답에는 모두 있다. 다만 배송·MFC 쪽 사본 중 이 변경 전에 만들어진 것은 없을 수 있다 → 없으면 `orderId` 를 보이면 된다(요청서에 적은 대로).

## 어디에 있나
| 앱 | 값 | 서비스 |
|---|---|---|
| 고객앱 | `GET /orders/{id}`·`GET /orders`(목록)의 `orderNumber` | order 0.11.0 |
| 라이더앱 | 제안 `summary.order.orderNumber`(수락 전), `GET /deliveries/{id}` 의 `orderSummary.orderNumber` | dispatch 0.4.7, execution 0.7.2 |
| 백오피스 | 직원 주문 목록·상세 `orderNumber`, 검색 `GET /orders/lookups?orderNumber=12345678`(공백·하이픈 무시, 기간 없이, 8자리 아니면 400) | order 0.11.0 |
| MFC앱 | 포장 라벨·인계 화면 — **아직**(mfc-fulfillment, PC 3 에 요청 F-5) | mfc-fulfillment |

## 아직
- MFC 라벨·인계(PC 3), 백오피스 배송·사건 화면의 번호(배송 조회 쪽은 orderId 로 order 를 다시 읽으면 보임 — 따로 필요하면 요청).
