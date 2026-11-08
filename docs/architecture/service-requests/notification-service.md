# notification-service 에 대한 요청 (MFC·주문 이행 쪽에서)

notification-service 는 아직 스캐폴딩(모든 엔드포인트 501)이다. 구현을 시작할 때 아래를 함께 넣는다.

## N-1. 예약 보류·결품 처리 고객 안내 (2026-11-08, 사용자 결정 #5 — "요청사항에 남기고 넘기자")

- **배경**: 결제 뒤 MFC 재고가 품질 격리·실사 손실로 모자라면 예약 일부가 보류된다(`ReservationBlocked`). 피킹 시작 전에 재고가 돌아오면 자동으로 다시 잡고(`ReservationBlockCleared`), 피킹 시작 때도 남아 있으면 결품 사가가 무조건 그 품목만 부분 취소·환불한다(ADR-0049, 남은 상품이 하나도 없을 때만 전체 취소).
- **지금(임시)**: 고객에게 아무 안내도 없다. 부분 취소·환불이 되면 주문 상세의 수량·금액이 바뀌는 것뿐이다.
- **notification-service 에 필요한 것**
  1. 결품 부분 취소 안내: `OrderLineQuantityChanged`(reason `SHORTAGE`) + 환불 결과(`RefundResult`) → "○○ 상품 n개가 품절되어 취소·환불되었습니다(금액)". 환불 요청만으로 "환불 완료"라고 쓰지 않는다(OMS — 결과 확인 뒤).
  2. 결품 전체 취소 안내(남은 상품이 하나도 없을 때만): 결품 사가의 시스템 전체 취소(`OrderCancelled`, 사유 OUT_OF_STOCK) → 전액 환불 안내.
  3. (선택) 보류 단계 안내 — 보류(`ReservationBlocked`)는 피킹 전에 다시 잡힐 수 있어 **바로 알리면 헛안내가 될 수 있다**. 알릴지·언제 알릴지는 **사용자 결정 필요**(추천: 보류 단계에서는 알리지 않고, 실제 취소·환불이 확정될 때만).
  4. 채널(앱 푸시·문자)·문구·야간 발송 제한은 notification 정책과 함께.
- **고객 앱 표시(pmart-clients)**: 주문 상세에 "일부 상품 확인 중" 같은 상태를 보일지도 위 3 과 같은 결정에 묶는다. 보이기로 하면 order 가 고객용 응답에 확인 필요 여부(지금은 운영자 전용 `/fulfillment-issues`)를 싣는 계약을 추가하고 `docs/handoff/` 로 클라이언트에 알린다 — **지금은 하지 않는다**.

## MFC·주문 쪽 보충 목록 (notification-service 구현 때 같이)
| # | 서비스 | 바꿀 것 |
|---|---|---|
| N-1 | order | 고객 안내에 필요한 사건이 다 나가는지 확인(`OrderLineQuantityChanged` SHORTAGE·`OrderCancelled` 사유) — 없으면 추가 |
| N-1 | claim/payment | `RefundResult` 에 결품 환불 구분(사유 OUT_OF_STOCK)이 실리는지 확인 |
| N-1 | order(선택) | 고객 앱 "확인 중" 표시를 하기로 하면 고객 주문 상세 응답 필드 + clients handoff |
