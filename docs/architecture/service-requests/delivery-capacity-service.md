# delivery-capacity-service 에 대한 요청 (MFC 쪽에서)

## CP-1. `CapacityReduced` 구독 (2026-11-09 기록)

- **요청한 곳**: mfc-capacity — 수용 축소 때 넘친 창의 영향 주문을 알린다(이미 발행).
- **지금**: 받는 곳이 없어 새 Hold 만 막힌다. 이미 잡힌 주문은 그대로.
- **할 것**: 구독 → 슬롯 반영, 영향 주문 처리. 주문·고객 안내는 order·notification(N-1 과 같은 채널)과 함께.
- **사용자 결정**: #15 는 임시 구현값 — 영향 주문을 어떻게 다룰지(옮김·취소·안내만)는 질문 필요(`mfc-defaults-status.md` #15).
