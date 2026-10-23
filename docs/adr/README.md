# 아키텍처 결정 기록 (ADR)

| 번호 | 제목 | 상태 |
|---|---|---|
| [0001](0001-product-master-in-product-service.md) | 상품 정본은 `product-service`가 소유한다 | 제안 |
| [0002](0002-inventory-single-writer-per-center-type.md) | 재고 원장은 센터 유형별 단일 쓰기 서비스(cdc/mfc inventory) | 제안 |
| [0003](0003-order-saga-orchestration.md) | 주문 계열 사가는 orchestration, 사실 전파는 choreography | 제안 |
| [0004](0004-backoffice-ledgers-approval-case-center-master.md) | approval/case/center-master 서비스 추가 | 제안 |
| [0005](0005-payment-service-and-pg-source-of-truth.md) | payment-service 내부화, 거래 사실 정본은 PG | 제안 |
| [0006](0006-handover-fence-and-cancel-arbitration.md) | 인계 fence는 mfc-fulfillment, 취소 판정은 order-orchestration | 제안 |
| [0007](0007-tms-delivery-split-and-serviceability.md) | TMS/Delivery 분리, 권역 정본은 delivery-serviceability | 제안 |
| [0008](0008-slot-capacity-ownership.md) | 슬롯은 delivery-capacity, 작업 여력은 mfc-capacity, 결제 전 둘 다 확보 | 제안 |
| [0009](0009-outbox-event-envelope-idempotency.md) | Outbox + Kafka, 공통 봉투, 2계층 멱등 | 제안 |
| [0010](0010-time-utc-storage-seoul-business-dates.md) | UTC 저장, Asia/Seoul 기한 판정 | 제안 |

형식: 배경 → 결정 → 대안 → 결과. 상태는 `제안 → 승인 → 대체됨(by ADR-xxxx)`.
