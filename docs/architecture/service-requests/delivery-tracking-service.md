# delivery-tracking-service 에 대한 요청 (MFC·주문 쪽에서)

delivery-tracking-service 는 아직 스캐폴딩(모든 엔드포인트 501)이다.

## TR-1. 라이더 ETA (2026-11-09 기록)

- **요청한 곳**: mfc-fulfillment(점원 화면 `Shipment.riderEta`, 지금 비어 있음 — DD-1 에서 선택 사항), order(고객 조회 ETA, `events.md`).
- **필요한 것**: `DeliveryEtaUpdated` 발행. 받는 쪽 구독은 각 서비스가 붙인다.
- **사용자 결정**: 필요 없음(선택 기능).
