# [pmart-backend → pmart-clients] 요청: 사건함 화면·처리 대기 버튼 (2026-11-16)

- 보낸 영역: platform
- 받는 앱: backoffice(2026-11-16 부터 PC 2)
- 결정: 사용자 2026-11-16 — 사건함 "유형별 팀이 공용 목록에서 집어 간다, 처리 기한은 표시만", BO-12 "OPERATOR_ADMIN 만, 재시도·처리 완료(사유 필수), 승인 없음". 지원센터 역할 = RIDER_OPS.
- 계약: `case-service.yaml` **0.4.0**, `backoffice-bff.yaml` **0.2.0**(+ 어제 요청한 처리 대기 메뉴 `all/2026-11-15-request-permission-and-pending-menus.md` §2 를 이것으로 바꿈). LAN 배포됨.

## 1. 사건함 — 메뉴 "지원센터 사건"(RIDER_OPS·OPERATOR_ADMIN, CS_AGENT 는 보기만)
- 목록 `GET /api/v1/cases?unassigned=&overdue=&kind=&state=&assigneeId=&size=&cursor=` — state 를 비우면 열린 것, 긴급(P1)·기한 이른 것 먼저.
  - 탭 제안: "안 집은 것"(`unassigned=true`), "내 것"(`assigneeId=<내 운영자 ID>`), "기한 넘김"(`overdue=true`), "닫힌 것"(`state=CLOSED`).
  - 항목: `kind`(RIDER_HELP 라이더 도움요청 / DELIVERY_INCIDENT 전달 실패 / RIDER_LOCATION_STALE 위치 끊김 / MISDELIVERY 오배송), `priority`, `summary`, `refs`(deliveryId·orderId·riderId — 눌러서 배송·주문·라이더 화면으로), `slaDueAt`·`overdue`(빨간 표시), `assigneeId`.
- 상세 `GET /api/v1/cases/{caseId}` — `history`(열림·집음·재발·닫힘), `closure`.
- 집기 `POST /api/v1/cases/{caseId}/assignments` `{assigneeId: <내 운영자 ID>}` → 200 사건. 이미 다른 직원이 집었으면 409. 관리자는 다른 사람을 넣어 배정할 수 있다.
- 닫기 `POST /api/v1/cases/{caseId}/closures` `{resolution: RESOLVED|NOT_REPRODUCIBLE|DUPLICATE|NO_ACTION_REQUIRED|ESCALATED_EXTERNAL, summary}` → 200. summary 에 고객 주소·연락처 원문을 적지 않게 안내.
- **오배송 확인**(상담원·RIDER_OPS): `POST /api/v1/cases` `{kind: MISDELIVERY, subjectType: DELIVERY, subjectRef: <deliveryId>, summary, evidence?: [{system, ref, kind: PHOTO}]}` → 201. 같은 배송의 열린 오배송 사건이 있으면 409 `CASE_ALREADY_OPEN`. 확인하면 배송 쪽 회수 배차로 넘어간다(배송 쪽 구현은 진행 중).
- 모든 명령은 `Idempotency-Key`·`X-Source-System` 헤더.

## 2. 처리 대기 — 메뉴(OPERATOR_ADMIN)
- 목록 `GET /api/v1/backoffice/views/pending-work` — `items[]`: `kind`(STUCK_SAGA 멈춘 주문 처리 / FULFILLMENT_ISSUE 이행 확인 필요), `refId`, `orderId`, `state`, `reasonCode`·`reasonMessage`, `since`, `allowedActions`. `unavailableSources` 가 있으면 "일부 원천을 읽지 못함" 띄우기.
- 버튼은 `allowedActions` 에 있는 것만:
  - `REQUERY_RESULT` "결제 결과 다시 확인" → `POST /api/v1/backoffice/commands/order-orchestration-service/saga-requery` `{targetRef: <refId>, reason, payload: {}}`
  - `MARK_RESOLVED` "처리 완료(밖에서 해결)" — 사유 입력 필수 → `POST …/commands/order-orchestration-service/saga-resolve` 같은 본문. 완료하면 목록에서 빠진다(주문·재고는 바뀌지 않음 — 실제 환불·재고 정정은 각 메뉴에서 먼저).
  - `ACKNOWLEDGE` "확인" → order-service `POST /api/v1/fulfillment-issues/{refId}/acknowledgements` `{note}`(어제 요청과 같음).
- 응답 202 `{status: APPLIED, …}`. 422 `NOT_REQUERYABLE`·`NOT_RESOLVABLE` 은 "이미 상태가 바뀜 — 새로고침" 안내.
- MFC 인계 거절·현장 중지 실패는 아직 목록에 없다.
