# Claim · Refund · Payment 상태표

근거: OMS 10·11, A.1.6·A.1.12·A.1.13, flows/return-exchange-refund.md, BackOffice BO-13/14

## Claim (claim-service)

| 상태 | 의미 | 다음 |
|---|---|---|
| OPENED | 접수(자격·수량·중복 검증 통과) | UNDER_REVIEW, WITHDRAWN |
| UNDER_REVIEW | 심사(증빙 보완 요청 가능 `EVIDENCE_REQUIRED` 플래그) | DECIDED |
| DECIDED | 품목별 APPROVED/REJECTED 확정(일부 승인 가능) | IN_PROGRESS, CLOSED(전부 거절) |
| IN_PROGRESS | 회수·검수·환불·교환 재배송 진행 | CLOSED |
| CLOSED | 모든 의무(회수·검수·환불/재배송·안내) 확인 | — |
| WITHDRAWN | 철회(진행된 수행·실물·PG는 대사 후 처리) | — |

품목(ClaimLine)별 축: `REQUESTED → APPROVED | REJECTED → PICKUP_PENDING → PICKED_UP → RECEIVED_AT_STORE → INSPECTED(RESALE|DISPOSE) → REFUND_PENDING → REFUNDED | EXCHANGE_SHIPPED`.

## ReturnPickup (delivery 수행, claim 추적)

`REQUESTED → DISPATCHED → PICKED_UP → DELIVERED_TO_STORE → RECEIVED(점원 수령 확인) → INSPECTED`. 승인량 > 실수령이면 `SHORT_RECEIVED` 플래그, 환불 선완료 금지.

## Refund (claim-service 의도 + payment-service 실행)

| 상태 | 소유 | 의미 |
|---|---|---|
| INTENT_CREATED | claim | 환불 의도·금액(원안분) 산정 |
| APPROVED | claim | 심사/승인 완료(개시점 정책 충족) |
| EXECUTION_PENDING | claim | PG 요청 대기 |
| PG_REQUESTED | payment | 취소 요청 전송 |
| COMPLETED | payment→claim | PG 취소 성공 확인 |
| FAILED | payment→claim | 명시적 실패 → 재심사 |
| UNKNOWN | payment | 응답 불명(1분×5회 → 상신 → 15분). 완료 안내 금지 |

한도: `remainingLimit = approvedAmount − succeededRefunds − unknownHeld`. 초과 요청은 payment가 거절.

## PaymentAttempt (payment-service)

| 상태 | 의미 |
|---|---|
| STARTED | PG 결제창 진입 정보 발급 |
| APPROVED | PG 승인 사실 확인(원거래 조회 또는 검증된 콜백) |
| FAILED | 승인 실패 확정 |
| UNKNOWN | 타임아웃·응답 유실 → 조회 루프 |
| MISMATCH | 승인액·통화·주문 참조 불일치 → 확정 차단·대사 |
| COMPENSATION_CANCELLED | 승인 후 주문 확정 실패로 승인취소 완료 |
| COMPENSATION_UNKNOWN | 승인취소 결과 불명 |

CancellationRequest(취소/환불 거래): `REQUESTED → SUCCEEDED | FAILED | UNKNOWN`. 같은 멱등키 재요청은 원결과.
