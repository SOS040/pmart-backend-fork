# 회신 — "배차 불가로 취소" 주문 행: DC-1 반영(claim 0.11.0)

- 보낸 영역: platform (PC 1)
- 답하는 것: pmart-clients `docs/handoff/mfc-backoffice/2026-11-19-backend-dispatch-cancel-lines.md`
- 결정: 사용자 2026-11-21 "dc-1로 할게"
- 서버 상태: **claim 0.11.0 LAN 배포 2026-11-21**

## 바뀐 것
- `POST /claims` 직원 회사 사유 전체 취소(`reason` = `DISPATCH_UNAVAILABLE` 또는 `OPERATOR_DECISION`, `type` CANCEL)는 **`lines` 를 빼고 보내도 된다**. 서버가 order 원장에서 "모든 행 × 미이행 수량"을 채운다.
  - 본문: `{orderId, type: "CANCEL", reason: "DISPATCH_UNAVAILABLE", channel: "OPERATOR", reasonDetail}` (+ `Idempotency-Key`·`X-Source-System`).
  - 권한·범위는 그대로: 그 센터 `MFC_SUPERVISOR`(남의 센터 주문 404)·CS_AGENT·OPERATOR_ADMIN. 상품준비 뒤라도 인계 전이면 받는다. 이미 라이더에게 넘겼으면(미이행 0) 422 `ORDER_NOT_CANCELLABLE` — 반품으로.
  - 같은 `Idempotency-Key` 재전송은 처음 결과(201 클레임). 결과 불명(503·시간 초과)이면 같은 키로 다시.
  - 응답 201 클레임의 `lines` 에 서버가 채운 행(`orderLineId`·`requestedQty`)이 있다.
- `lines` 를 주면 지금처럼 검사한다. 그 밖의 접수(고객 취소·고객센터 취소·반품)는 `lines` 가 필요하다(없으면 400).
- DC-2(주문 조회를 센터 관리자에게 열기)는 하지 않았다.

## 쿠폰 문구
- 확인 창 "주문 전체 취소 · 전액 환불 · 고객에게 2,000원 쿠폰" 은 맞다(ADR-0082 5단계, 2026-11-21) — 취소가 확정되면 promotion 이 주문마다 2,000원 쿠폰(받은 날 포함 30일, 최소 주문 없음)을 주고 고객에게 "보상 쿠폰이 도착했어요" 알림이 간다. LAN 보상 행사는 승인 대기 중이라 승인 전 취소분은 승인되면 나간다.
