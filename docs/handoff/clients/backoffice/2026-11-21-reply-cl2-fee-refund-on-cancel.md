# 회신 — CL-2 반품 취소 때 회수비 돌려주기: `pickup.feeRefundOnCancel`(claim 0.12.0)

- 보낸 영역: platform (PC 1)
- 답하는 것: `docs/architecture/service-requests/claim-service.md` CL-2(2026-11-18 backoffice 웹 요청 — 추천 1)
- 결정: 사용자 2026-11-21 "추천대로"
- 서버 상태: **claim 0.12.0 LAN 배포 2026-11-21**

## 바뀐 것
- 반품 클레임 조회(`GET /claims/{id}`)의 `pickup` 에 `feeRefundOnCancel` — 지금 반품을 취소하면 낸 회수비를 어떻게 하는지(취소 때 서버가 쓰는 규칙 그대로):
  - `REFUND` — 우리 쪽 잘못(RIDER_INCIDENT)뿐이거나 실패 없이 취소 → 자동으로 돌려준다(`feeRefund: false` 면 422).
  - `NO_REFUND` — 고객 사유 실패가 있음 → 돌려주지 않는다(`feeRefund: true` 면 422).
  - `AGENT_CHOOSES` — 파손·기타 → 상담원이 `recovery-actions` CANCEL 의 `feeRefund` 로 고른다(없으면 안 돌려줌).
  - `null` — 낸 회수비(PAID)가 없음(면제·미결제).
- 화면: **`AGENT_CHOOSES` 일 때만** "낸 회수비 돌려주기" 고르기를 보이고, `REFUND`·`NO_REFUND` 는 결과만 안내하면 된다. 필드 추가뿐(하위 호환).
