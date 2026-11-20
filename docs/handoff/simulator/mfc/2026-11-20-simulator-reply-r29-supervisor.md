# [pmart-backend → pmart-mfc-simulation] 회신: R-29 관리자 제어·지난 주문 조회 (2026-11-20)

- 보낸 영역: mfc(PC 3) — 사용자 허락("R-29 … 니가 바로 고쳐")으로 시뮬레이터 세션이 백엔드 작업 트리 `mfc/r29-supervisor-control` 에서 고침
- 상태: **로컬 커밋만(push·PR·LAN 배포 전)** — 배포 뒤 실서버에서 쓸 수 있다.

## 된 것
1. **주문번호 → 현장 상태**(요청 3): `GET /work-orders?orderNumber=12345678`(mfc-fulfillment 0.27.0). orderId 와 둘 중 하나만(아니면 400), 공백·하이픈 무시 8자리, 번호 사본이 없으면 404. 권한은 orderId 와 같음(그 센터 직원이면 됨 — `/orders/lookups` 같은 본사 역할 필요 없음).
2. **센터 출고 목록 거르기**(요청 3): `GET /shipments?mfcId=&orderNumber=&createdFrom=&createdTo=&sort=recent` — 날짜는 출고 만든 날(서울, 최대 31일), `sort` oldest(기본)/recent, 커서 그대로. 번호·날짜를 주고 `state` 를 안 주면 **인계 끝난·취소된 출고도** 나온다(지난 주문 찾기).
3. **피킹 시작 뒤 취소**(요청 2, 범위 줄임): claim 0.8.0 사유 `OPERATOR_DECISION`(ADR-0076) — `POST /claims` 직원 토큰, type CANCEL, 그 센터 `MFC_SUPERVISOR` 또는 `OPERATOR_ADMIN`(CS_AGENT 는 403), channel OPERATOR, **reasonDetail 필수**, 상품준비 뒤라도 인계 전이면 주문 전체 취소(포장분 복귀·전액 환불). 인계 뒤 422. 고객 취소는 그대로 상품준비 전만.

## 업무 규칙 결정 필요 (이번에 안 함)
- 작업(PICK·PACK) 단위 강제 중지·취소, BLOCKED 해제, 직접 부분 종료(CLOSED_PARTIAL), 끝난 작업 다시 열기 — 재고 수량·복귀·결품 사가와 얽혀 업무 규칙이 먼저.
- PACK 시작 이력·배정 변경·내놓기 이력(요청 4) — 지금은 작업의 현재 담당·시작 시각과 실적 목록(`GET /tasks/{id}/executions`)만.

## 2단계 (2026-11-20 사용자 결정 — 로컬 커밋만, push·배포 전)
1. **작업 중지** `POST /tasks/{taskId}/blocks`(fulfillment 0.28.0) — 그 센터 MFC_SUPERVISOR 또는 OPERATOR_ADMIN, `reason` 필수, X-Expected-Version·Idempotency-Key. READY·ASSIGNED·IN_PROGRESS → BLOCKED(`blockKind` SUPERVISOR). 담당·장바구니·재고·예약·배차 그대로. 결과 대기 실적이면 409 EXECUTION_IN_FLIGHT. 막힌 동안 작업자 시작·실적·밀봉 409 **TASK_BLOCKED**. 기록 `GET /tasks/{taskId}/controls`.
2. **막힘 해제** `POST /tasks/{taskId}/unblocks`(0.29.0) — `reason`·`cartChecked: true` 필수(빠지면 400, false 422 PHYSICAL_CHECK_REQUIRED). 담당 배정이 살아 있고 맡을 수 있으면 ASSIGNED(다시 시작), 아니면 배정 해제 → READY. 결품 막힘은 409 BLOCKED_BY_SHORTAGE.
3. **부분 종료** `POST /tasks/{taskId}/partial-closes`(0.30.0) — PICK 만, `reason` 필수, 집은 양 0 이면 422 NOTHING_EXECUTED(주문 취소 OPERATOR_DECISION 으로). BLOCKED(`blockKind` PARTIAL_CLOSE) → 결품 사가가 잔량 취소·예약 해제·부분 환불 → **CLOSED_PARTIAL**, 집은 양은 포장으로. 처리 중 409 PARTIAL_CLOSE_PENDING.
4. **배정 이력** `GET /assignments/history?mfcId=&taskId=`(capacity 0.31.0) — 그 센터 MFC_SUPERVISOR·OPERATOR_ADMIN. 버전 순으로 배정한 사람·시각·사유와 끝(endedAt·endedBy·endReason). 0.31.0 전에 끝난 배정은 끝 정보 없음.
5. **피킹 끝 PACK 바로 배정**(capacity 0.32.0, ADR-0078) — PICK 이 끝나면 그 출고 PACK 이 피커에게 SYSTEM 배정(맡을 수 없음·휴게·근무 밖·이미 배정이면 예전처럼 READY). **설정 저장소 바인딩이 들어가야 켜짐**(PC 1).
6. **다시 열기(후속 작업)** — 이번에 안 함(아래).

## 후속 작업(결품 뒤 찾은 물건 다시 집기) — 설계 메모, 사용자 결정 대기
- 필요한 것: ① 재고 재확보 — mfc-inventory 에 "그 주문 줄 qty 를 새 예약 조각으로 다시 잡기" 경로(지금 재확보는 결품 보고 안에서만, 예약이 RELEASED 된 뒤 같은 예약에 다시 잡는 API 없음), 실패면 거절. ② fulfillment 후속 PICK 작업(같은 출고에 PICK 은 하나 — `ux_task_shipment_type` 유일 제약이라 새 출고 또는 제약 변경·V 마이그레이션, 원래 작업 연결 칸), 포장이 끝났으면 추가 포장(출고 READY → 다시 PREPARING 인지, 배차·인계 fence 와 어떻게 맞추는지). ③ 고객 쪽.
- **막힌 결정**: 결품 사가는 이미 그 줄을 주문에서 줄이고(APPLY_ORDER_CHANGE) 부분 환불(REQUEST_REFUND)까지 끝낸다(보통 몇 초 안). 그 뒤 물건을 찾아 다시 보내면 **고객이 돈을 내지 않은 상품을 보내게 된다** — 재청구(새 결제·추가 결제)·새 주문·무상 발송 중 무엇인지, 고객 동의가 필요한지(WK-06 "무동의 변경 금지") 업무 원문에 규칙이 없다. 사가가 끝나기 전(환불 전)에만 허용하는 안도 있지만 사가는 자동이라 창이 거의 없다.
- 그래서 재고·작업·결제 세 서비스에 걸친 변경을 결정 없이 만들지 않았다. 결정되면: (a) 무상 발송이면 inventory 재확보 API + fulfillment 후속 출고(새 shipment)·추가 포장 + 배차 요청, (b) 재청구면 order/payment 쪽 추가 결제 사가가 먼저.
