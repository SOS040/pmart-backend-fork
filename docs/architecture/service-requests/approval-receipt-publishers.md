# 승인 반영 회신 발행 요청 (approval-service 쪽에서 — 여러 서비스 대상)

받는 서비스: product-service, product-content-service, center-master-service, delivery-serviceability-service, mfc-inbound-service(초기 재고).

## RC-1. `ApplicationReceiptRecorded` 발행 (ADR-0039)

- **요청한 곳**: approval-service — 승인의 실행 축(반영 완료)이 이 회신으로 갱신된다.
- **지금**: pricing-service 만 발행한다(`PriceApprovalApplier`). 나머지 유형 승인은 반영돼도 `VALIDATING` 에 머문다(BR-45 "반영 확인 30초" 가 이것 때문).
- **각 서비스가 할 것**: `ApprovalDecided` 를 반영하는 **같은 트랜잭션**에서 `ApplicationReceiptRecorded` 를 outbox 에 쓴다(토픽 `platform.approval-receipt.v1`). pricing 구현을 본보기로.
- **사용자 결정**: 필요 없음. 과거 승인 정리 방식만 미정(ADR-0039).
- 근거: `docs/adr/0039-approval-application-receipt-event.md`, `docs/worklog/2026-11-04.md`.
- **delivery-serviceability 구현(PC 5, 2026-11-17 — 브랜치 `delivery/rc-1-serviceability-receipt`)**: 권역 매핑 승인(SERVICEABILITY, APPROVED·PARTIALLY_APPROVED)을 반영한 같은 inbox 트랜잭션에서 발행. 발효 = APPLIED(domainRef `mapping-version:<v>`), 발효하지 않음(초안 아님 IGNORED·승인 범위 불일치·시험 주소 불일치) = REJECTED(reason·행 reasonCode = 그 결과). 행 lineKey = 승인 행 lineId, 순번 1.
- **product·product-content·center-master 구현(PC 1, 2026-11-19 — 사용자 "응")**: 각 서비스 `application/ApprovalReceipts`(같은 모양, 서비스마다 사본)로 ApprovalDecided(APPROVED·PARTIALLY_APPROVED)를 반영한 같은 inbox 트랜잭션에서 발행. 반려 결정에는 회신 없음, 순번 1, domainRef = 승인 targetId.
  - product(PRODUCT): 초안·배치는 초안마다 행 결과(반영 못 한 초안 = REJECTED, reasonCode = NOT_SUBMITTED·REJECTED·SCOPE_MISMATCH·반영 문제 코드), 카테고리 트리·판매 중지는 승인 전체 한 결과(APPLIED·GRANTED → APPLIED, 아니면 REJECTED·그 결과 이름), 모르는 대상 REJECTED UNKNOWN_TARGET.
  - product-content(DISPLAY): 초안마다 — 같은 규칙.
  - center-master(CENTER_MASTER): 행마다(속성이 틀린 행 INVALID_ATTRIBUTES). **일부 승인(PARTIALLY_APPROVED)은 지금처럼 반영하지 않으므로 모든 행 REJECTED(PARTIAL_APPROVAL_NOT_APPLIED)**.
  - 시험: `RegistrationFlowIT.barcodeOfIssuedSkuBlocksOtherDraftsAndRejectionKeepsNothing`(APPLIED, 반려는 회신 없음), `ContentRegistrationFlowIT.singleDraftImageRulesScopeMismatchAndRejection`(REJECTED·SCOPE_MISMATCH), `CenterFlowIT.approvedCenterAndCalendar`(APPLIED·INVALID_ATTRIBUTES).
