# mfc-inbound-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `mfc-inbound-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/mfc-inbound-service.yaml` → `openApiGenerate` 가 `com.pmart.mfc.inbound.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.mfc.inbound.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:mfc-inbound-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:mfc-inbound-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8306.

## 상태
- 3단계 스캐폴딩(2026-10-23).
- 초기 보유 반입(슬라이스 4, 결정 D2, 2026-10-30): `platform.approval.v1` 의 ApprovalDecided 중 `type=INITIAL_STOCK`·`targetService=mfc-inbound-service`·APPROVED 만 InboxGuard 안에서 센터별 `initial_stock_load`(PENDING) 로 만든다(행 attributes `{mfcId, sku, qty}`, 한 행이라도 틀리면 승인 전체 REJECTED `INVALID: …`). `InitialStockPoster` 가 트랜잭션 밖에서 mfc-inventory `POST /internal/receipts`(서비스 토큰, Idempotency-Key = receiptId, trfId `INITIAL-<approvalId>`, departureId `INITIAL`)로 전기하고, 미전달·응답 불명은 같은 receiptId 로 5초부터 두 배(최대 5분) 재시도, 4xx 는 REJECTED. 조회: `/internal/initial-stock-loads/{receiptId}`(서비스), `/initial-stock-loads?approvalId=`(운영자). `POST /internal/initial-stock-loads` 와 그 밖의 엔드포인트는 501.
