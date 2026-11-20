# ADR-0076 관리자 판단 취소(OPERATOR_DECISION) — 직원 회사 사유 취소만 넓힌다

- 상태: **승인** (2026-11-20)
- 사용자 결정(2026-11-20, MFC 시뮬레이터 세션 R-29 — "R-29 … 니가 바로 고쳐", 범위 "관리자 사유 취소만 넓힘"): 상품준비 뒤 **고객 요청 취소는 여전히 받지 않는다**(ADR-0060 그대로). 직원이 회사 사유로 하는 전체 취소에 사유 하나를 더한다.
- 근거: 시뮬레이터 요청 R-29 2번(피킹 시작 뒤 주문 취소 — 지금 claim 은 상품준비 전 전체 취소만, 뒤는 422 ORDER_NOT_CANCELLABLE), CL-5 배차 불가 취소(claim 0.7.0, `cancel-vs-handover.md` §7)
- 관련: ADR-0060(상품준비 뒤 고객센터 취소 없음 — 그대로), ADR-0046(피킹 뒤 취소 복귀), `docs/business/flows/cancel-vs-handover.md` §7

## 결정
1. claim 사유 **`OPERATOR_DECISION`**(관리자 판단)을 더한다. 배차 불가(`DISPATCH_UNAVAILABLE`)와 **같은 사람·같은 범위·같은 흐름**이다.
   - 누가: 그 센터 `MFC_SUPERVISOR`(남의 센터 주문은 404)·`CS_AGENT`·`OPERATOR_ADMIN` — 배차 불가와 같게(그대로 따름). 작업자·그 밖 직원 403.
   - 언제: 주문 전체, **상품준비 뒤라도 라이더 인계 전**이면(시스템 취소와 같은 범위 `checkFullCancel(…, system=true)`). 인계 뒤·미이행 0 은 422 — 반품으로.
   - 본인 확인 없음(회사 사유). **사유 메모 `reasonDetail` 필수**(비면 400) — 왜 멈췄는지 남긴다.
   - 고객 토큰은 이 사유를 쓸 수 없다(400).
2. 흐름은 §2 그대로: ClaimOpened·CancellationRequested(reason OPERATOR_DECISION) → S3 현장 중지(포장분 취소 복귀) → OrderCancelled(`cancelReason` OPERATOR_DECISION) → 원 승인액 전액 환불. 고객 알림은 일반 "주문이 취소되었어요" 문구(사유별 문구 없음).
3. 고객 취소(앱·고객센터 대리)는 바꾸지 않는다 — 상품준비 전(CONFIRMED)만.

## 적용
- claim-service 0.8.0: `ReturnRules.OPERATOR_DECISION`·`COMPANY_CANCEL_REASONS`, `ClaimService.createCompanyReasonCancel`(배차 불가와 한 경로), `ClaimEvents.cancellationRequested` 가 사유를 그대로. 마이그레이션 없음(reason varchar(32)).
- 사건 스키마는 설명만(`CancellationRequested.reason`·`OrderCancelled.cancelReason` 은 자유 문자열).
- claim-service 는 platform(PC 1) 영역 — 사용자 허락으로 MFC 시뮬레이터 세션이 고쳤다.

## 결과
- 센터 관리자가 현장 사정(설비 고장·오주문 확인 등)으로 준비 중 주문을 멈추고 전액 환불할 길이 생긴다. 사유는 클레임에 남는다.
- 남은 것(업무 규칙 결정 필요, 이번에 안 함): 작업 단위(PICK·PACK) 중지·취소, BLOCKED 해제, 직접 부분 종료, 끝난 작업 다시 열기, 배정 이력. 고객 쿠폰·사유별 알림 문구도 없다.
