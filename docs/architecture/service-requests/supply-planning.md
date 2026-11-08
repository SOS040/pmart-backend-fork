# 공급 계획(replenishment / demand-planning) 에 대한 요청 (MFC 쪽에서)

두 서비스는 아직 `services/` 에 없다. 사용자 결정으로 **보류**(SCM 구현 때).

## SC-1. 판매 예상량 기반 공급 요청 (`docs/business/flows/forecast-based-supply-request.md`)

- **요청한 곳**: mfc-inbound, mfc-inventory, mfc-capacity.
- **필요한 것**
  1. `SupplyRequestReceived` 발행 → mfc-inbound 가 ACK, 추천 수량 조회 → `recommendationRef` 연결.
  2. `InventorySnapshotPublished` 구독자(지금은 LAN 확인용으로만 발행). 변화 때 발행(trigger EVENT)은 구독자가 생기면.
  3. mfc-capacity `ReceivingCapabilityChanged` — 구독자가 생기면 발행.
- **관련**: 입고 예정 재고 예약(IV-06)은 ASN/TRF 정보 필요, mfc-inbound InboundPromises 501, `MfcReceiptPosted` 미발행.
- **사용자 결정 필요**: Q-15 승인 주체·부분 승인·TRF(`pending-user-decisions.md`).
