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
