# order-service 에 대한 요청 (MFC 쪽에서)

## O-1. `PackageHandedOver` 구독 → 인계 수량 반영 (2026-11-09 기록)

- **요청한 곳**: mfc-fulfillment(`docs/business/flows/package-handover-contract.md` §5).
- **지금**: order 의 `mfc.fulfillment.v1` 구독은 `PreparationStarted` 만 처리. 줄 수량 변경은 CANCEL_BEFORE_SHIP·SHORTAGE 외 사유를 422 로 막는다(`OrderService`).
- **order-service 가 할 것**
  1. `PackageHandedOver` 구독 → 줄마다 `handedOverQty +q`, `unfulfilledQty −q`(사유 `HANDOVER`, sourceRef = handoverId, 멱등).
  2. 주문 상태 PARTIALLY_HANDED_OVER / IN_DELIVERY.
  3. 배송 사실(`DeliveryCompleted`·`DeliveryFailed`)은 delivery-execution(DE-1) 이 생긴 뒤 같은 방식으로.
- **사용자 결정**: 필요 없음(계약 문서에 규칙 있음).
