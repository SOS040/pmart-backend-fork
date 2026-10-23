# SCM 상태표 (replenishment-service)

근거: SCM v1.1 14.1 [명칭 제안], 14.2, 15, 20

## PlanRun (계획)

`INPUT_PENDING → CALCULATED → PROPOSED → SUPERSEDED`. 입력 판정 `CALCULABLE / REFERENCE_ONLY / INPUT_PENDING`. 조회 실패는 H0/SS0가 아니라 INPUT_PENDING.

## SupplyRequest / 사업승인

`RECEIVED → UNDER_REVIEW → APPROVED | PARTIALLY_APPROVED | REJECTED | WITHDRAWN`. MFC 로컬 전송 상태(DRAFT/SUBMITTED/ACKNOWLEDGED)와 별개. 승인 부족은 `APPROVAL_PENDING`(자동 승인 없음).

## Execution (실행 준비, 원WMS/TMS 결과를 추적)

`ALLOCATION_PENDING → PARTIALLY_ALLOCATED | ALLOCATED` ; `TRANSPORT_PENDING → ASSIGNED` ; `EXECUTION_HOLD` ; `READY`. 승인 ≠ 확보 ≠ 편성.

## Fulfillment (이행, 실제 사건의 정본 참조)

`NOT_DEPARTED → DEPARTED → PARTIALLY_RECEIVED → RECEIVED → USAGE_PENDING → (준비 완료)` / `RECONCILIATION_REQUIRED`. RECEIVED ≠ 판매 준비.

## Residual (행정 잔량)

`OPEN → RESIDUAL_CLOSE_PENDING → RESIDUAL_CLOSED`. 보존식 `A0 = D + Cpre + Uclosed + Uopen`. 잔량 종료 ≠ 부족 해소.

## Delivery (수신자별 전달)

`PENDING → ACKNOWLEDGED | UNKNOWN_RESULT | RECONCILIATION_REQUIRED`. 수신자(WMS/TMS/MFC/BI)마다 독립.

## 변경 가능 시점

| 시점 | 허용 |
|---|---|
| 미승인 | 새 버전·철회 |
| 승인·미확보 | 변경/취소 심사 |
| 확보·작업 중 | 중지·정리 확인 후 변경 |
| 상차·미출발 | WMS 하차/복귀 대사 후 |
| 실제 출발 후 | 변경 불가(목적지 전환·반송 없음) |
| 수령 후 | 원수령 취소 불가 |
