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
- 2026-11-07: 접수된 작업 지시가 있으면 중지 수량만큼 작업 행을 취소(`task_line.cancelled_qty`), 잔량 0 인 작업·출고는 CANCELLED. 6a 는 피킹 시작이 없어 판정은 여전히 전부 STOPPED. 포장별 판정(PENDING·fence)은 6b 이후.

## 작업 접수 (2026-11-07, 슬라이스 6a — order-checkout-saga.md §2.0)
- `POST /internal/work-orders`(ConfirmOrder, orchestration): `WorkOrderService`·규칙 `domain/WorkRules`·표 V3(`work_order` 주문당 하나, `work_order_version` 요청 버전마다, `shipment`·`shipment_line`(수량·상품명 사본은 여기 한 곳), `task`·`task_line`(실적만, 잔량은 계산)).
- 결정은 201 `WorkOrderDecision`(거절도 결과 — 같은 키 재생). 같은 orderId·workVersion 은 기존 결정, 접수된 주문에 다른 버전·마지막보다 작은 버전은 409 INVALID_STATE.
- 순서: 주문 advisory lock(중지와 한 줄) → 이미 중지된 수량 빼기(전부면 거절 ALREADY_STOPPED) → mfc-inventory `GET /internal/reservations/{id}`(트랜잭션 밖, 조회 불가면 503·기록 없음)로 원래 예약이 CONFIRMED·SKU별 확정 조각 합 ≥ 작업 수량인지(아니면 INVENTORY_MISMATCH, retryable) → 접수: 출고마다 `<shipmentId>-PICK`(READY) + `-PACK`(WAITING). 결정과 `WorkAccepted`/`WorkRejected`(mfc.fulfillment.v1, 집계 키 orderId) 한 트랜잭션.
- 예약 주문 PICK: 슬롯 시작 − `pmart.fulfillment.pick-lead-time."[<센터>]"`(LAN 90분)까지 WAITING. 센터 설정 없으면 WAITING·holdReason CONFIG_REQUIRED(0·즉시 대체 금지). `sweepReady`(`pmart.fulfillment.ready-sweep-interval`, 기본 30초)가 시각이 된 PICK 을 READY 로, 설정이 새로 들어온 대기에 시각을 넣는다(조건부 갱신).
- OPERATION_RESTRICTED·CAPACITY_EXCEEDED·LOT_INELIGIBLE 은 계약 값만 — 원천(운영 제한·LOT)이 없어 내지 않는다. 작업자 배정·피킹 시작(PreparationStarted)·DISPATCH 는 6b·6c.
- 조회 `GET /internal/work-orders/{fulfillmentId}`·`GET /internal/work-orders?orderId=`(마지막 버전 기준, 작업별 계획·완료·취소·잔량·readyAt·holdReason).

## 상태
작업 접수·조회·접수 뒤 중지(2026-11-07, 6a)와 주문 단위 작업 중지(2026-10-31). 점원 단말 Task API·Shipment·포장·인계 fence 는 501 UNSUPPORTED_ACTION.
