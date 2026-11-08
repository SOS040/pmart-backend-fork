# mfc-fulfillment-service 에 대한 요청 (mfc-capacity 쪽에서)

## F-1. 운영 제한 사건 구독 (2026-11-09 기록)

- **요청한 곳**: mfc-capacity — `OperationRestrictionApplied`/`Lifted` 를 2026-11-08 부터 발행한다(BO-08).
- **지금**: fulfillment 는 `AssignmentPublished` 만 구독. 작업 거절 사유 `OPERATION_RESTRICTED` 는 계약에만 있고 내지 않는다(`WorkOrderService` 주석 "원천이 아직 없어" — 이제 원천 있음).
- **할 것**: 구독 → 제한 중 센터의 작업 접수 거절(`OPERATION_RESTRICTED`). 이미 진행 중인 작업을 어떻게 할지는 BO-08 규칙대로.
- **사용자 결정**: 진행 중 작업 처리가 문서에 없으면 질문.
