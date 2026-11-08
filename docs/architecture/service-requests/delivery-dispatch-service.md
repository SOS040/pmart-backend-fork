# delivery-dispatch-service 에 대한 요청 (MFC 쪽에서)

delivery-dispatch-service 는 아직 스캐폴딩(모든 엔드포인트 501)이다. 구현을 시작할 때 아래를 함께 넣는다.

## DD-1. 배차 요청 받기 (2026-11-08, 사용자 결정 D-5)

- **결정**: 배차는 점원이 포장을 시작할 때 예상 포장 시간(분)과 함께 요청한다(Delivery §3.1 — 라이더가 포장이 끝날 즈음 도착). 포장이 끝날 때까지 요청이 없으면 출하 대기(READY)가 되는 순간 자동으로 요청한다. 출고당 한 번.
- **MFC 가 주는 것(구현됨, mfc-fulfillment 0.4.5)**: `DispatchRequested`(mfc.fulfillment.v1, 집계 Shipment = shipmentId) — orderId·shipmentId·mfcId·trigger(MANUAL/AUTO_ON_READY)·estimatedPackMinutes(MANUAL)·estimatedReadyAt·requestedAt·requestedBy. 그 뒤 `PackingFinished`·`ShipmentReady`(포장 목록·무게·봉투)는 그대로 나간다.
- **delivery-dispatch 가 할 것**
  1. `DispatchRequested` 구독 → 배송 생성(DeliveryCreated, shipmentId 포함 — MFC 는 이것으로 배정을 투영한다) → `estimatedReadyAt` 에 맞춰 라이더 후보 탐색·제안(DispatchOfferSent).
  2. `ShipmentReady` 가 먼저 오는 경우(자동 요청과 같은 순간)도 같은 출고 하나로.
  3. **포장 중 결품·취소**: 출고가 취소되면(주문 취소 → mfc `WorkStopResult`, 결품으로 출고 수량이 줄면 `PackingFinished` 내용) 배차를 취소·조정한다. 배정된 라이더가 있으면 `DeliveryCancelledBeforePickup`(MFC 는 이것으로 배정을 푼다).
  4. 라이더 단계(D-4): 일시 중지·계약 종료 라이더의 배정을 풀고(`DeliveryAssignmentChanged`) 재배정. 신규배차 제한 라이더는 배정분 그대로.
- **MFC 쪽 보충(delivery-dispatch 가 생기면)**: 없음. 필요하면 점원 화면에 라이더 ETA(`Shipment.riderEta`, 지금 비어 있음)를 delivery 사건으로 채운다.
