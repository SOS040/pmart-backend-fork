# Platform 상태표 (approval, case, notification, auth)

근거: BackOffice v2.0 4.2 [명칭 제안], 5장, BO-24, Commerce 4.8

## Approval — 5축 (approval-service)

| 축 | 상태 |
|---|---|
| 요청 | DRAFT → SUBMITTED → WITHDRAW_REQUESTED → WITHDRAWN |
| 심사 | REVIEW_PENDING → EVIDENCE_REQUIRED → APPROVED / PARTIALLY_APPROVED / REJECTED / EXPIRED / SUPERSEDED |
| 실행(정본 반영) | NOT_REQUESTED → VALIDATING → ACCEPTED → IN_PROGRESS → PARTIAL → APPLIED / REJECTED / UNKNOWN_RESULT |
| 전달(수신자별) | PENDING → DELIVERED → ACKNOWLEDGED / RETRY_PENDING / FAILED |
| 사건 | OPEN → ASSIGNED → ACTION_PENDING → MONITORING → RESOLUTION_REVIEW → CLOSED / REOPENED |

불변: `승인량 = 누적 유효 집행량 + 승인된 미집행 취소량 + 열린 집행 잔량`. 재심사는 새 버전 + 계보. 승인 정본은 도메인, 여기는 원승인 ID 참조.

## BatchChange (BO-26)

`UPLOADED(fileHash 고정) → VALIDATING → PARTIALLY_APPLIED / APPLIED / REJECTED`. 행별 결과 `OK / FAILED / SKIPPED` 분리.

## Case (case-service)

`OPEN → ASSIGNED → ACTION_PENDING → MONITORING → RESOLUTION_REVIEW → CLOSED` ; `REOPENED → ASSIGNED`. 시간 경과 자동 종결 없음. 이관은 `TRANSFERRED(상대 처리 중)` 플래그. 병합은 원ID 보존.

## Obligation

`OPEN → IN_PROGRESS → RESOLVED | WAIVED(승인 근거)`. 종류가 다른 수량/금액 합산 금지.

## Notification (notification-service)

`REQUESTED → SENT → DELIVERED | FAILED(재시도) → READ`(본인 읽음만). 읽음 ≠ 동의. 알림 실패는 통지만 복구(원장 재생성 없음).

## Auth (auth-service)

고객: `AUTH_GUEST → AUTH_PENDING → (AUTH_CONSENT) → AUTH_MEMBER` ; `AUTH_UNKNOWN`(원인증 조회) ; `AUTH_RESTRICTED`(15분 5회 → 10분) ; `AUTH_MEMBER → AUTH_GUEST`(로그아웃).
Grant: `REQUESTED(approval) → ACTIVE → REVOKED | EXPIRED`. Delegation: `SCHEDULED → ACTIVE → ENDED`(교집합, 만료 후 소급 금지). 긴급권한: `ISSUED → ACTIVE → EXPIRED → POST_REVIEWED`.

## Customer withdrawal (customer-service)

`ACTIVE → WITHDRAW_PENDING(신규 구매·마케팅 중단, OMS 미결 확인) → WITHDRAW_COMPLETED` ; `WITHDRAW_PENDING → ACTIVE`(본인 재인증 철회). 삭제 일부 실패 시 COMPLETED 금지.
