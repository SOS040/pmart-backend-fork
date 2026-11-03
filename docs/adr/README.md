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
| [0011](0011-service-identity-tokens.md) | 서비스 신원 토큰: auth-service client_credentials, /internal 호출에만 | 일부 대체(ADR-0026) |
| [0012](0012-image-registry-docker-hub-public.md) | 이미지 저장소: Docker Hub 개인 계정 공개 저장소 `<계정>/pmart-<service>` | 제안 |
| [0013](0013-deployment-lan-compose-and-helm.md) | 배포 정의: LAN 은 배치 파일 + compose 생성, Kubernetes 는 공통 Helm 차트 | 제안 |
| [0014](0014-master-data-and-initial-stock-loading.md) | 기준정보·MFC 초기 재고는 정식 승인·반입 경로로만, 적재는 API 스크립트 | 제안 |
| [0015](0015-category-tree-follows-reference-data.md) | 카테고리 트리는 참고 자료 구조(4단계 + 노드별 필터)를 따른다 — Commerce 1·2단계 정책 대체 | 제안 |
| [0016](0016-object-storage-seaweedfs-s3.md) | 상품 이미지는 S3 호환 저장소, 로컬·LAN 은 SeaweedFS 4.48 + AWS SDK v2 2.55.11 (수집 저장소와 동일) | 제안 |
| [0017](0017-production-db-servers-five.md) | 운영 PostgreSQL 은 도메인별 DB 서버 5대, 서비스별 논리 DB·계정 분리 | 제안 |
| [0018](0018-operator-accounts-and-jwt-login.md) | 운영자는 auth-service 운영자 계정(아이디·비밀번호)으로 로그인, JWT·refresh 회전, 게이트웨이 웹 CORS | 제안 |
| [0019](0019-customer-google-login-id-token.md) | 고객 소셜 로그인은 Google 먼저 — 앱 ID 토큰을 auth-service 가 검증, 필수 동의 단계 임시 생략 | 제안 |
| [0020](0020-payment-spec-mapping-toss-widget.md) | 결제 명세를 payment-service 에 반영 — 결제 시도 ID = 토스 orderId, 고객 승인 요청 API, 웹훅은 대사 신호 | 제안 |
| [0021](0021-search-index-opensearch-projection.md) | 상품 검색 색인은 OpenSearch 사건 투영 — REST·n-gram, 버전 비교 멱등(inbox 없음), 콘텐츠 카드 보강 | 제안 (결정 1~4 는 ADR-0031 로 대체) |
| [0022](0022-guest-session-token-and-guest-paths.md) | 비회원 세션 토큰(typ=GUEST)은 guest-paths 에서만, 장바구니 병합은 X-Guest-Token 으로 소유 증명 | 제안 |
| [0023](0023-address-geocoding-kakao-local.md) | 주소 ↔ 좌표는 카카오 로컬(서버 호출, 고객·비회원), 판정은 도로명주소 좌표 — 지도 핀은 배달 위치 참고 | 제안 |
| [0024](0024-provider-profile-to-customer-service.md) | 로그인 제공자(Google) 프로필(이름·이메일·사진)은 auth 에 저장하지 않고 CustomerAuthenticated 로 customer-service 에 넘긴다 | 제안 |
| [0025](0025-customer-google-login-server-auth-code.md) | 고객 Google 로그인 하이브리드 — 앱은 서버 인가 코드, auth-service 가 OAuth 클라이언트로 교환(ADR-0019 결정 1·8 대체) | 제안 |
| [0026](0026-no-service-to-service-authentication.md) | 서비스 간 호출은 인증하지 않는다 — 신뢰 경계는 private subnet 과 API Gateway(ADR-0011 서비스 토큰 대체) | 승인 |
| [0027](0027-config-server-github-cipher.md) | LAN 설정은 Spring Cloud Config Server(GitHub 저장소, {cipher})로 부팅 때 받는다 — 클라이언트 기본 꺼짐, 운영 Kubernetes 는 그대로 | 승인 (결정 2·3·5 는 ADR-0029 로 대체) |
| [0028](0028-dev-base-image-temurin-jdk.md) | 개발 단계 컨테이너 베이스는 eclipse-temurin:21-jdk(Exec 탭 셸·JVM 진단, 비루트 ubuntu) — 운영은 GraalVM Native 로 따로 정한다 | 승인 |
| [0029](0029-config-repo-as-source-of-truth.md) | 설정의 정본은 설정 저장소(pmart-config) — 서비스 yml 은 부트스트랩만, 환경은 프로필(local·lan·prod). ADR-0027 결정 2·3·5 대체 | 승인 |
| [0030](0030-observability-metrics-logs-dead-letters.md) | 관측 — 지표 Prometheus·Grafana·Alertmanager, 로그 Fluent Bit → OpenSearch(Dashboards), 미처리 메시지는 서비스별 dead_letter 표·재처리 API | 승인 (결정 2·4 는 ADR-0031 로 대체) |
| [0031](0031-elastic-stack-search-and-logs.md) | 검색·로그를 Elastic Stack 으로 — Elasticsearch 8.18(nori+n-gram), 검색 색인은 Logstash 가 도메인 이벤트로, 로그는 Filebeat → Logstash → ES → Kibana(상한 유지) | 승인 |
| [0032](0032-eureka-service-discovery-local-lan.md) | local·LAN 서비스 등록·조회는 Eureka(Basic 인증, PC IP+포트로 등록, 게이트웨이 lb://·RestClient 로드밸런서) — 인스턴스는 하나씩, 운영 Kubernetes 는 Service DNS | 승인 |
| [0034](0034-kafka-event-history-retention-and-reindex.md) | 상품·가격 이벤트 이력(`commerce.product.v1`·`commerce.pricing.v1`)만 무제한 보관(compaction 없음, 배포가 멱등 보정), 감시·백업·복원 절차, 검색 재색인은 Kafka 전체 재생(스냅샷 API 없음, 선행 버그 2건 수정 전 운영 실행 금지). ADR-0031 결정 3·8 보완 | 승인 |
| [0033](0033-config-refresh-webhook-bus.md) | LAN 설정 실행 중 갱신 — GitHub 웹훅(smee.io 중계) → config-server /monitor(서명 검증) → Spring Cloud Bus(Kafka springCloudBus) → 바뀐 파일의 앱이 다시 받음. ADR-0027 결정 6 대체 | 승인 |

형식: 배경 → 결정 → 대안 → 결과. 상태는 `제안 → 승인 → 대체됨(by ADR-xxxx)`.
