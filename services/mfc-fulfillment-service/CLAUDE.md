# mfc-fulfillment-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `mfc-fulfillment-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/mfc-fulfillment-service.yaml` → `openApiGenerate` 가 `com.pmart.mfc.fulfillment.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.mfc.fulfillment.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:mfc-fulfillment-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:mfc-fulfillment-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8302.

## 주문 단위 작업 중지 (2026-10-31, S3 a)
- `POST /internal/stop-requests`·`GET /internal/stop-requests/{id}`(내부 호출): 작업 지시가 아직 없어 요청 수량 전부 STOPPED(`notStartedLines`, packages 빈 배열)·`order_work_stop`(V2) 기록·`WorkStopResult`(mfc.fulfillment.v1) 한 트랜잭션. 같은 stopRequestId 는 기존 판정, 다른 주문·수량이면 409 IDEMPOTENCY_CONFLICT.
- **슬라이스 6 할 일**: 작업 요청(ConfirmOrder)이 이 중지 기록의 수량을 빼고 받기, 작업 지시가 있으면 포장별 판정(PENDING·fence).

## 상태
주문 단위 작업 중지만 구현(2026-10-31). 작업 지시·Task·Shipment·인계 fence 는 501 UNSUPPORTED_ACTION.
