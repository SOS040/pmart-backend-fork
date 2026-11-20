# ADR-0077 센터 관리자 작업 조작 — 작업 중지(BLOCKED)·해제·부분 종료

- 상태: **승인** (2026-11-20)
- 사용자 결정 2026-11-20 (시뮬레이터 세션 경유 — MFC 시뮬레이터 R-29 2단계 "관리 탭"). 계기: 주문 23631583 이 인계 뒤 현장과 기록이 어긋났는데 아무도 손댈 수 없었다.
- 근거(업무 원문 — 이 ADR 은 원문을 고치지 않고 적용 방법만 정한다): `docs/business/MFC-business-logic.md` WK-04(실패·보류·취소 — "고장만으로 주문 예약 해제 안 됨", 잔량만 cancelled_qty, 실행>0 이면 CLOSED_PARTIAL), WK-05(재배정·재개 — 완료량 유지, 재개 시작은 WK-03), §6 상태표(READY·ASSIGNED·IN_PROGRESS → BLOCKED, BLOCKED → READY 또는 인계 확정 ASSIGNED, 안전 정리 후 CANCELLED/CLOSED_PARTIAL), IV-07/08/09 수량 규칙(바꾸지 않음).
- 관련: ADR-0076(관리자 판단 취소 OPERATOR_DECISION — 같은 역할), ADR-0045/0049(결품 사가), ADR-0043(점원 단말 작업)

## 결정

### 1. 작업 중지(BLOCKED)
- 누가: 그 센터 `MFC_SUPERVISOR`, 또는 `OPERATOR_ADMIN`(모든 센터 — ADR-0076 과 같은 역할). 작업자는 이 기능을 쓰지 않는다 — 현장 문제는 지금처럼 PDA 문제 보고(결품)만.
- 언제: READY·ASSIGNED·IN_PROGRESS(§6 상태표). 사유 필수. 결과를 기다리는 실적·열린 포장이 있으면 거절(WK-04 사전 조건 "결과 불명 사건 먼저 조회").
- 그대로인 것: 배정(담당은 풀지 않는다)·시작 점유·완료량·장바구니에 집은 물건의 위치·재고 수량·예약·배차 요청. 재고 서비스를 부르지 않는다(WK-04 "고장만으로 주문 예약 해제 안 됨").
- 막힌 동안 작업자의 시작·실적·포장 밀봉은 409 `TASK_BLOCKED`.

### 2. 막힘 해제
- 같은 역할, 사유 + **실물 확인 완료 표시(`cartChecked: true`) 필수**(빠지면 400, false 면 422) — WK-05 "미확정 실물 확인" 을 사람이 확인했다는 기록.
- 원래 담당의 배정이 아직 살아 있고(capacity ACTIVE) 그 작업자가 지금 맡을 수 있으면(capacity `workerEligibility` 없음 — 정지·퇴사·업무 변경 아님) **ASSIGNED** 로 그 사람에게(시작 점유는 지운다 — 재개는 WK-03 시작으로). 아니면 capacity 배정을 풀고 **READY**(누구나 받을 수 있게). 완료량은 그대로. 관리자 중지(`blockKind` SUPERVISOR)만 푼다 — 결품으로 막힌 작업은 결품 사가가 푼다.
- 근무표(OFF_SHIFT)는 보지 않는다 — capacity 가 근무 밖을 경고로만 다루고 있어(Q-09 미결) 같은 기준을 따른다.

### 3. 부분 종료(잔량만 취소)
- 같은 역할, 사유 필수, **PICK 작업만**. 아직 집지 않은 잔량만 취소한다 — 집은 양은 그대로 포장으로 간다.
- 집은 양이 0 이면 거절(422 `NOTHING_EXECUTED`) — 주문 취소(관리자 판단 취소 OPERATOR_DECISION, ADR-0076)와 복귀 흐름을 쓴다. 집은 물건을 취소하는 길은 여기서 만들지 않는다.
- 고객 쪽은 **결품과 같은 흐름**: 줄마다 `LineShortageReported`(reason `PARTIAL_CLOSE`, 잔량) → 결품 사가(ADR-0049, CHECK_ORDER → RESOLVE_WORK → RELEASE_SHORTFALL → APPLY_ORDER_CHANGE → REQUEST_REFUND) — 그 상품만 부분 취소·부분 환불. 작업은 사가가 RESOLVE_WORK 로 잔량을 취소할 때까지 BLOCKED(`blockKind` PARTIAL_CLOSE), 잔량이 0 이 되면 **CLOSED_PARTIAL**(WK-04 "실행>0/잔량취소 후 CLOSED_PARTIAL")·포장 준비.
- 예약: 잔량은 아직 선반에 있는 확정 예약이다 — 사가 RELEASE_SHORTFALL 이 사유 `PARTIAL_CLOSE_CANCELLED` 로 그 줄의 **아직 집지 않은 예약을 해제**(IV-08 "피킹 전 취소 R−q/A+q", 현물 H·E 그대로). 결품(이행부족 BLOCKED 조각 해제)과 같은 단계·같은 경로.
- 결품률 지표에는 세지 않는다(재고 결품이 아님). 센터 결품 목록에는 사유 PARTIAL_CLOSE 로 남는다. 품질 사고는 열리지 않는다(DAMAGED·SPOILED 만).

### 4. 끝난 작업 다시 열기 — 하지 않는다
- §6 "취소된 작업을 되살리는 대신 새 업무 근거와 후속 연결" 그대로. 후속 작업(결품 뒤 찾은 물건을 다시 집기)은 이 ADR 에서 정하지 않는다 — 결품 사가가 이미 부분 환불·주문 수량 감소까지 끝낸 뒤라 다시 보내려면 고객 결제(추가 청구·새 주문)가 필요하고 원문에 규칙이 없다(사용자 결정 대기).

## 적용
- mfc-fulfillment 0.28.0(V22): `POST /tasks/{taskId}/blocks`, `GET /tasks/{taskId}/controls`, 작업 상세 `blockKind`, 시작·실적·밀봉 `TASK_BLOCKED`. `application/TaskControlService`, 기록 `task_control`(조작마다 한 행, 지우지 않음), `task.block_kind`. 사건 TaskProgressed 에 controlledBy·blockKind·reason.
- mfc-fulfillment 0.29.0: `POST /tasks/{taskId}/unblocks`(`cartChecked` 필수 — 빠지면 400, false 면 422 PHYSICAL_CHECK_REQUIRED, 결품 BLOCKED 409 BLOCKED_BY_SHORTAGE). capacity 활성 배정을 호출자 토큰으로 읽고(실패 503), 맡을 수 없으면 `/internal/assignments/{taskId}/releases` 뒤 READY. mfc-capacity 0.30.0: `GET /assignments` 를 OPERATOR_ADMIN 도 모든 센터로(본사 관리자의 해제가 배정을 읽게).
- mfc-fulfillment 0.30.0: `POST /tasks/{taskId}/partial-closes`, 사건 LineShortageReported reason `PARTIAL_CLOSE`, `resolveShortage` 가 PARTIAL_CLOSE 막힘을 CLOSED_PARTIAL 로. mfc-inventory 0.41.0: `shortfall-releases` 사유 `PARTIAL_CLOSE_CANCELLED`(집지 않은 예약 해제). order-orchestration 0.4.2: 결품 사가 RELEASE_SHORTFALL 사유를 결품 사유로 고름(`ShortageRules.releaseReason`). 사건 스키마 `LineShortageReported.reason` 에 PARTIAL_CLOSE. 흐름 `order-checkout-saga.md` §2.1.

## 결과
- 현장이 멈추면 관리자가 작업을 멈추고(재고·배정 그대로) 확인 뒤 이어서 하거나 잔량만 끝낼 수 있다. 조작은 누가·언제·왜로 남는다.
- 남은 것: 후속 작업(결정 4), PACK·RETURN 의 부분 종료(집은 물건 취소 = 주문 취소·복귀로).
