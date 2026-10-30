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
| [0011](0011-service-identity-tokens.md) | 서비스 신원 토큰: auth-service client_credentials, /internal 호출에만 | 제안 |
| [0012](0012-image-registry-docker-hub-public.md) | 이미지 저장소: Docker Hub 개인 계정 공개 저장소 `<계정>/pmart-<service>` | 제안 |
| [0013](0013-deployment-lan-compose-and-helm.md) | 배포 정의: LAN 은 배치 파일 + compose 생성, Kubernetes 는 공통 Helm 차트 | 제안 |
| [0014](0014-master-data-and-initial-stock-loading.md) | 기준정보·MFC 초기 재고는 정식 승인·반입 경로로만, 적재는 API 스크립트 | 제안 |
| [0015](0015-category-tree-follows-reference-data.md) | 카테고리 트리는 참고 자료 구조(4단계 + 노드별 필터)를 따른다 — Commerce 1·2단계 정책 대체 | 제안 |
| [0016](0016-object-storage-seaweedfs-s3.md) | 상품 이미지는 S3 호환 저장소, 로컬·LAN 은 SeaweedFS 4.48 + AWS SDK v2 2.55.11 (수집 저장소와 동일) | 제안 |
| [0017](0017-production-db-servers-five.md) | 운영 PostgreSQL 은 도메인별 DB 서버 5대, 서비스별 논리 DB·계정 분리 | 제안 |
| [0018](0018-operator-accounts-and-jwt-login.md) | 운영자는 auth-service 운영자 계정(아이디·비밀번호)으로 로그인, JWT·refresh 회전, 게이트웨이 웹 CORS | 제안 |

형식: 배경 → 결정 → 대안 → 결과. 상태는 `제안 → 승인 → 대체됨(by ADR-xxxx)`.
