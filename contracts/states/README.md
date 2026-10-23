# 상태표 (states)

서비스가 각자 구현하되 **이름·전이·완료 의미**를 공유하는 상태 축. 코드가 아니며 `libs/`에 두지 않는다.
원칙: 한 aggregate의 여러 축(업무/품질/연계/사건)을 하나의 "완료" 필드로 합치지 않는다. 모든 축에 `UNKNOWN_RESULT`/`확인 중`에 해당하는 상태가 있어야 하며 시간 경과만으로 성공/실패를 확정하지 않는다.

| 파일 | aggregate | 소유 서비스 |
|---|---|---|
| order-states.md | Order, OrderDraft, CancellationRequest, ReservationTimeChange | order-service, order-orchestration |
| claim-refund-states.md | Claim, Refund, ReturnPickup, PaymentAttempt | claim-service, payment-service |
| mfc-states.md | Task, Shipment, Package, Handover, Reservation, Balance(품질/업무), CapacityHold, SupplyRequest(local), Plan | mfc-* |
| cdc-states.md | PutawayTask, Wave, Task, TRF(outbound), Departure, Transit, LPN, LOC, Adjustment | cdc-* |
| scm-states.md | PlanRun, SupplyRequest, TransferApproval, Execution, Fulfillment, Residual, Delivery(수신자별) | replenishment-service |
| tms-states.md | TransportRequest, Trip, Stop, Assignment, Incident, Linkage | transport-* , fleet |
| delivery-states.md | Delivery, DispatchAttempt/Offer, RiderOperatingState, RiderRestriction, Settlement, SlotHold | delivery-*, rider |
| platform-states.md | Approval(5축), Case, Obligation, Notification, Auth | approval, case, notification, auth |
