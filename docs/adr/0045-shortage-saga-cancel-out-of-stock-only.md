# ADR-0045 결품 사가(IV-09 2부) — 품절 상품만 취소는 자동(작업 취소·이행부족 해제·주문 수량·부분 환불), 전체 취소·전체 결품은 수동

- 상태: 승인 (2026-11-07, 사용자 "그래" — IV-09 2부 진행). **결정 2·4 의 사전 선택별 처리는 ADR-0049(2026-11-08, 무조건 부분 취소·환불)로 대체**
- 관련: ADR-0044(1부: 재확보 못 한 양만 결품 사건), 흐름 문서 order-checkout-saga §2.1(고객 사전 선택별 처리 — 확정 규칙), OMS 10·10.2(원가격 부분 취소, 봉투값은 전체 취소만), Commerce 1차 할인 제외

## 배경
1부 뒤에는 MFC 가 다시 잡지 못한 결품이 `LineShortageReported` 로 나오고 작업은 BLOCKED 로 남는다. 흐름 문서 §2.1 은 고객 사전 선택(주문서 필수값 `CANCEL_OOS_ONLY` / `CANCEL_ALL`)에 따라 처리를 정해 두었다. 결제는 부분 취소를 이미 지원한다.

## 결정
1. **결품 사가(order-orchestration, S2 의 하위 흐름)**: `LineShortageReported`(mfc.fulfillment.v1)마다 하나(사건 ID 로 중복 방지). 사건에 고객 선택·예약 ID 를 싣는다(fulfillment 가 작업 지시 사본에서).
2. **품절 상품만 취소(CANCEL_OOS_ONLY)**, 단계마다 하위 명령 키 `sagaId:단계`:
   - CHECK_ORDER: 주문 원장. 결품이 남은 미이행 수량 전부면 상신(아래 4).
   - RESOLVE_WORK: fulfillment `POST /internal/work-orders/{id}/shortage-resolutions` — PICK·PACK 작업 행에서 취소, BLOCKED 해제(남은 집품 0 이면 PICK 완료 → 포장 준비).
   - RELEASE_SHORTFALL: inventory `POST /internal/reservations/{id}/shortfall-releases` — BLOCKED(이행부족 D) 조각 RELEASED. 현물·R 변화 없음.
   - APPLY_ORDER_CHANGE: order 수량 변경 reason `SHORTAGE`(출고 전 취소와 같은 산식 — 미이행 −q, 출고 전 취소 +q, 주문 상태 유지).
   - REQUEST_REFUND: claim `POST /internal/claims/shortage-refunds` — 클레임(type CANCEL, reason OUT_OF_STOCK, channel SYSTEM)·환불 의도 PARTIAL = **원단가 × 수량**(1차 할인 안분 없음, 있으면 거절), **봉투값·배달팁 환불 없음**, 남은 주문이 15,000원 미만이어도 유지. 요청 수량 ≤ 출고 전 취소 누계 − 이미 결품 환불한 수량(두 번 환불 방지). payment 부분 취소는 claim 이 비동기로.
3. 단계 업무 거절은 상신(ESCALATED, 운영자 BO-12 미구현), 결과 모름·미전달은 같은 키로 sweep 재시도.
4. **수동(상신)으로 두는 것**:
   - `CANCEL_ALL`: 집품이 시작된 주문의 전체 취소는 집품분 판매 제외·복귀(IV-08)가 있어야 한다 — 아직 없음. 사유 `PICKED_CANCEL_NOT_SUPPORTED`.
   - 선택 없음: CS 이관(case-service 미구현). 사유 `NO_PREFERENCE`(주문서 필수값이라 드물다).
   - 결품이 주문의 남은 수량 전부: 품절 상품만 취소라도 결과가 전체 취소라 봉투값·배달팁 환불 규칙(OMS 10)이 달라진다. 사유 `WHOLE_ORDER_OUT_OF_STOCK`.
   이 경우 작업은 BLOCKED 로, 이행부족은 BLOCKED 조각으로 남는다(약속 보존).

## 대안
- 결품도 고객 취소 흐름(S3: 현장 중지 → 해제 → 주문 → OrderCancelled 로 환불)으로: 피킹이 시작된 주문이라 현장 중지가 PENDING(ADR-0043)이 되고, 행 단위가 아니다. 결품 전용 사가로.
- 환불을 주문 수량 변경 사건(OrderLineQuantityChanged)으로 claim 이 시작: 사가가 결과(클레임 ID)를 알 수 없다. 동기 호출로.

## 결과
- 계약: claim(`/internal/claims/shortage-refunds`, ClaimReason OUT_OF_STOCK), order(SHORTAGE 허용), inventory 0.6.0(`shortfall-releases`), fulfillment(`shortage-resolutions`, LineShortageReported 에 outOfStockPreference·reservationId·mfcId·shipmentId).
- orchestration V6 `shortage_saga`, 구독 `fulfillmentEvents-in-0`(mfc.fulfillment.v1), 시작 기준 시각은 S2 와 같은 `start-orders-confirmed-after`.
- 남은 것: CANCEL_ALL(IV-08 집품 뒤 취소), 전체 결품의 전체 환불, CS 이관(case-service), 운영자 수동 처리 화면(BO-12).
