# MFC 상태표

근거: MFC v2.0 2장(1163~1179), 3장(1244, 1532, 1574~1580), WK-10/12/14, IF-04(1764)

## Task (mfc-fulfillment: PICK/PACK/DISPATCH · mfc-inbound: RECEIVE/INSPECT/PUTAWAY · REPLENISH)

| 상태 | 다음 | 비고 |
|---|---|---|
| WAITING | READY, CANCELLED | 선행 의존 대기 |
| READY | ASSIGNED, CANCELLED | |
| ASSIGNED | IN_PROGRESS, READY(배정 해제), CANCELLED | 활성 담당 1명, assignment_version |
| IN_PROGRESS | COMPLETED, BLOCKED, FAILED, CLOSED_PARTIAL | |
| BLOCKED | IN_PROGRESS, CANCELLED | 품질 차단·의존 불가 |
| FAILED | ASSIGNED(복구 후) | 스캔/통신 오류는 FAILED 아님 |
| COMPLETED | — | 계획 = 완료 + 취소 + 잔량 |
| CANCELLED | — | 실행 0 상태에서만. 부활 금지 |
| CLOSED_PARTIAL | — | 일부 실행 + 잔량 취소 |

## Shipment (SHP)

`PREPARING → READY → PARTIALLY_HANDED_OVER → HANDED_OVER` / `CANCELLED` / `CLOSED_PARTIAL`. `hold_reason` 별도 축(OOS_CONSENT_PENDING, QUALITY_BLOCK, RESTRICTION). 취소 포장을 제외했다는 이유로 HANDED_OVER 판정 금지.

## Package (PKG)

`OPEN → SEALED → HANDED_OVER`; `SEALED → INVALIDATED`(재포장, 새 PKG 발급); `OPEN|SEALED → VOIDED`(취소). 인계 후 재포장 금지.

## Handover fence

`NONE → HANDOVER_IN_PROGRESS(handover_authorization_id, fence_version, expiresAt) → HANDED_OVER(inventoryCommitId)` / `→ NONE(만료·해제)`. 만료 = 취소 성공 아님. fence 중 StopWork 응답은 `HANDOVER_IN_PROGRESS`.

## Balance 품질 축 (mfc-inventory)

`PENDING_INSPECTION → NORMAL | QUARANTINED` ; `NORMAL → QUARANTINED` ; `QUARANTINED → NORMAL`(승인) ; `QUARANTINED → DISPOSAL_PENDING → (폐기 실행 H−q)`.

## Balance 업무 축

`UNSTOWED → STORED → PICKED → PACKED` ; `PICKED|PACKED → CANCEL_RETURN_PENDING → STORED`(복귀·적격 확인 후). `RESERVED`라는 업무 상태는 없다(예약은 R 수량).

## ReservationPart (HAND / FUTURE)

`HELD → CONFIRMED | RELEASED | EXPIRED | BLOCKED` ; `CONFIRMED → CONSUMED | RELEASED | BLOCKED` ; FUTURE(Rf) → HAND 전환(3차). 불변: `원량 = HELD + CONFIRMED + BLOCKED + CONSUMED + RELEASED + EXPIRED`. PG 불명 중 HELD는 만료 대신 `PAYMENT_PENDING_HOLD` 플래그.

## inventory_apply_status (요청자가 추적)

`CAPTURED → SUBMITTED → PROCESSING → APPLIED | REJECTED | UNKNOWN_RESULT`. 완료 근거는 commit_id뿐.

## CapacityHold (mfc-capacity)

`HELD → CONFIRMED | RELEASED | EXPIRED`; `CONFIRMED → RELEASED`는 등록 근거 필수. 시간 경과만으로 CONFIRMED를 EXPIRED 처리 금지.

## AssignmentPlan (WK-09/10)

`PROPOSED → COMMITTED` / `NO_SOLUTION` / `INVALID` / `UNDETERMINED` / `STALE` ; 부분 성공 `RECOVERY_REQUIRED`(신규 점유만 보상).

## SupplyRequest (local, WK-14)

로컬 `DRAFT → SUBMITTED → ACKNOWLEDGED` ; SCM 측 `APPROVED | PARTIALLY_APPROVED | REJECTED | CANCELLED`는 별도 필드로 투영.

## Adjustment / Disposal

`REQUESTED → APPROVED | REJECTED → (부분 실행) → COMPLETED`. 요청자 ≠ 승인자.

## 문제함 (case-service로 이관)

`OPEN → TRIAGED → IN_PROGRESS | WAITING_EXTERNAL → RESOLVED → CLOSED` ; `REOPENED → TRIAGED`.

## sync_status (수신자별)

`SYNC_PENDING → SENT → ACKNOWLEDGED | RECONCILIATION_REQUIRED`. 투영 신선도 `CURRENT / GAP / STALE / UNAVAILABLE`.
