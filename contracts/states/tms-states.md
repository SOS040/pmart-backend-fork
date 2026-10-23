# TMS 상태표 (transport-planning, transport-execution, fleet)

근거: TMS v1.1 14장 [설계안], 6.2, 8.1, 11, 16

## TransportRequest (planning)

`RECEIVED → DATA_PENDING | PLAN_READY → ASSIGNED → IN_PROGRESS → OBLIGATION_PENDING → CLOSED` / `CANCELLED_BEFORE_DEPARTURE`. 보류 사유: `ADDRESS_UNVERIFIED`(≠차량 부족), `TIME_INFEASIBLE`, `CARGO_INFO_PENDING`, `OUTBOUND_HOLD`, `RECEIVING_WINDOW_UNKNOWN`, `NO_ELIGIBLE_VEHICLE`, `MODEL_INPUT_PENDING`. CLOSED = 운송 의무·미결 인계 확인(재고·공급 전체 종료 아님).

## Assignment (planning)

`PROPOSED → RESERVED(일부 자원 확보, 지시서 발행 금지) → CONFIRMED(모든 필수 자원) → SUPERSEDED | CANCELLED`. assignmentVersion 증가. 구버전 제안의 늦은 수락 거절.

## Trip (execution)

`DRAFT → VALIDATING → CONFIRMED → LOADING → READY → IN_TRANSIT → PHYSICAL_ENDED → CLOSED`. 출발 후 DRAFT·미출발취소로 역행 금지. `IN_TRANSIT` 사실 삭제 금지. PHYSICAL_ENDED(승인 구간 종료) ≠ CLOSED(의무 대사 완료). 사고·지연은 별도 플래그/사건.

## Stop (execution)

`PLANNED → ARRIVED → WAITING → UNLOADING → HANDOVER_PENDING → HANDOVER_RECORDED → DEPARTED`. 인계 기록과 MFC 수령/검수 확인은 별도 필드. 도착(GPS/버튼) ≠ 수량 인수.

## Departure 사실

`departureId` 1회. 이관별 출발 구성은 하위 레코드. 원장 반영 상태(`TOUT_POSTED / UNKNOWN / RECONCILIATION_REQUIRED`)는 이관별로 분리.

## FleetReservation (fleet)

`REQUESTED → RESERVED → RELEASED | EXPIRED` ; `UNKNOWN`(응답 불명 → 원예약 조회). 차량·기사 점유는 `[start, end)` 겹침 검사, 분리 검증.

## Incident

`OPEN → ASSIGNED → ACTION_PENDING → RESOLVED → CLOSED`. 차량 종료가 사건을 닫지 않는다.

## Linkage (연계)

`PENDING → SENT → ACKNOWLEDGED | UNKNOWN_RESULT | RECONCILIATION_REQUIRED` — 수신자별.

## 변경 경계

| 시점 | 허용 |
|---|---|
| 후보/미배정 | 재편성·철회 |
| 배정/미상차 | 실차·기사·회차·순서 재계획(기존 예약 정리, 구지시 무효) |
| 상차/미출발 | WMS 실물 확인·하차/복귀 후 변경 |
| 실제 출발 후 | ETA·안전 정차·잔여 일정만. 목적지·기출발 구성·완료 정차 고정 |
| 인계 후 | 원사건 정정·차이 조사만 |
