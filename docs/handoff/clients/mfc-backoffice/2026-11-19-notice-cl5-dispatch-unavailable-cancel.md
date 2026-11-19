# 알림 — "배차 불가로 취소" 호출 모양(CL-5, claim 0.7.0)

보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 관련: PC 5 요청서 `service-requests/claim-service.md` CL-5, MFC 백오피스 배차 지연 목록(`mfc-backoffice-staff-apis.md` MB-배차지연, dispatch `GET /dispatch-delays`)
- 상태: 백엔드 main 반영, LAN 배포는 사용자 승인 뒤(배포되면 아래에 적는다). **쿠폰(2,000원)은 아직 없다** — promotion-service 가 생길 때(사용자 결정 2026-11-19).

## 호출
`POST /api/v1/claims`(직원 토큰 — 그 센터 `MFC_SUPERVISOR`, 또는 CS_AGENT·OPERATOR_ADMIN), 헤더 `Idempotency-Key`·`X-Source-System`
```json
{ "orderId": "ORD-…", "type": "CANCEL", "reason": "DISPATCH_UNAVAILABLE", "channel": "OPERATOR",
  "lines": [{ "orderLineId": "ORD-…-L1", "qty": 3 }], "reasonDetail": "배차 지연 74분" }
```
- `lines` 는 **모든 행 × 미이행 수량**(전체 취소만). `identityCheck` 필요 없음(회사 사유).
- 상품준비 뒤(포장 끝)라도 **인계 전이면** 받는다. 인계 뒤·미이행 0 → 422(반품으로). 다른 센터 주문 → 404, 권한 없는 역할 → 403.
- 201 = 클레임(STOP_PENDING) — 뒤는 자동: 현장 중지 → 주문 취소 → 원 승인액 전액 환불, 배송(픽업 전)은 dispatch 가 거둠, 고객에게 "배달 기사를 찾지 못해 주문을 취소했어요" 알림.
- 진행은 `GET /claims/{claimId}` 로.
