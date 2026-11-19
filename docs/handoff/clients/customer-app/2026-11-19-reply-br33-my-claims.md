# 회신 — BR-33 내 취소·반품·환불 목록

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 답하는 것: pmart-clients `docs/backend-requests.md` BR-33(요청 `docs/handoff/archive/2026-10-31-backend-customer-profile-and-refunds.md` §3)
- 계약: claim-service **0.6.0**. LAN: **배포됨**(2026-11-19 09:22, 이미지 64e844c2 — PC 2)

## 새 경로를 만들지 않고 있던 `GET /api/v1/claims` 를 고쳤다
- 고객 토큰이면 **본인 클레임만**, 최근 접수 순. `type`(CANCEL·RETURN·EXCHANGE)·`state` 거르기, `size`(기본 20, 최대 100).
- **커서가 이제 동작한다** — 전에는 `cursor` 를 받기만 하고 늘 첫 쪽이었다. 응답 `page.nextCursor` 를 다음 요청 `cursor` 로, 없으면(null) 끝. 틀린 커서 400.
- 줄(`ClaimSummary`)에 새 칸: `reason`, `channel`(CUSTOMER_APP·CS·OPERATOR·SYSTEM — 결품 자동 환불은 SYSTEM), `closedAt`, **`refund`**:
  - `state`: `NONE` 환불 없음 · `IN_PROGRESS` 진행 중(**PG 결과 전 — "완료"로 보이지 않는다**) · `COMPLETED` 모두 PG 성공 · `FAILED` 실패가 섞임(고객센터 안내)
  - `requestedAmount`(환불하기로 한 금액 합)·`refundedAmount`(PG 성공 합)·`completedAt`(마지막 성공 시각)
  - 반품 회수비 돌려주기는 여기에 넣지 않았다 — 상세 `GET /claims/{id}/refunds`(kind RETURN_FEE).
- 마이 "환불 관리" 목록 → 줄을 누르면 `GET /claims/{id}`·`/claims/{id}/refunds`.
