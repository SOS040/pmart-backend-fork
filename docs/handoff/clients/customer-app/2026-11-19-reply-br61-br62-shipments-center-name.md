# 회신 — BR-61 주문의 배송 ID(shipments)·BR-62 센터 이름

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 답하는 것: `pmart-clients docs/handoff/customer-app/2026-11-19-backend-delivery-status-screen.md`·`…-order-shipments-empty.md` (사용자 "응 바로 만들고 고객이 센터 이름 보여도 돼")
- 상태: 백엔드 main 반영(order-service 0.15.0), LAN 배포는 사용자 승인 뒤(배포되면 아래에 적는다)

## R1(BR-61) `GET /orders/{orderId}` 의 `shipments[]` — 채운다
- **언제부터:** 배차로 배송이 생긴 뒤(delivery-dispatch `DeliveryCreated` — 포장이 끝나 배차 요청이 나가면). 그 전(준비 중)에는 빈 배열 → 앱은 단계만.
- 한 줄 = `{shipmentId, deliveryId, state, lines[]}`. 고객에게 가는 배송만(일반·재발송 — 반품 회수 배송은 넣지 않음). 분할·재발송이면 배송마다 한 줄(생긴 순).
- `state`: 배송 생성·라이더 배정 `READY` → 픽업 `IN_DELIVERY` → 전달 `DELIVERED` / 실패 `FAILED` / 픽업 전 취소 `CANCELLED`(뒤로 가지 않음). 라이더 위치는 지금처럼 픽업 뒤(`IN_DELIVERY`)부터 tracking 이 준다.
- `lines`: 첫 출고(`<orderId>-S1`)는 주문 줄 전체(orderLineId·주문 수량), 재발송 출고는 빈 목록. `weightG`·`eta` 는 채우지 않는다(도착 예정은 tracking `/eta`).
- **주의:** 배포 전에 이미 배송이 생긴 주문(예 `ORD-ed68685a…`)은 짝이 없어 빈 배열로 남는다 — 배포 뒤 새 주문부터. 그동안은 "배송 출발" 알림을 누르면 배송 ID 로 배송 현황이 열린다(notification 0.5.1, 14:11 배포).

## R2(BR-62) 센터 이름 — `Order.mfcName`
- center-master 센터 이름 그대로(고객 공개 — 사용자 결정 2026-11-19). LAN 은 지금 `[시험] 부산진구 MFC`. 모르면 null(이름을 지어내지 않음 — 앱은 브랜드만).
- 주소·좌표는 넣지 않는다(매장 위치 공개는 별도 정책).
