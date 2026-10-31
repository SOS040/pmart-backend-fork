# claim-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `claim-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/claim-service.yaml` → `openApiGenerate` 가 `com.pmart.claim.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.claim.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:claim-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:claim-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8204.

## 고객 전체 취소 (2026-10-31, 슬라이스 8 첫 단계)
- 흐름 정본: `docs/business/flows/cancel-vs-handover.md` §6. 규칙 `domain/ClaimRules`, 접수·사건 `application/ClaimService`, 환불 `application/RefundService`, 표 `claim`·`claim_line`·`refund_intent`(V2).
- `POST /claims`: 고객 토큰·type CANCEL 만(운영자·CS 접수, RETURN·EXCHANGE 는 422 UNSUPPORTED_ACTION). order `GET /internal/orders/{id}`(내부 호출)로 본인(아니면 404)·CONFIRMED(아니면 ORDER_NOT_CANCELLABLE)·모든 행 × 미이행 수량(아니면 PARTIAL_CANCEL_NOT_ALLOWED, 초과 QUANTITY_EXCEEDS_AVAILABLE) 심사. 진행 중 취소 클레임이 있으면 DUPLICATE_CLAIM. 클레임 STOP_PENDING + stopRequestId, `ClaimOpened`·`CancellationRequested`(order.claim.v1). 결제 시도·승인액·금액 구성을 접수 때 스냅샷.
- 구독(inbox): `OrderCancelled`(order.order.v1, claimId) → 행 APPROVED·IN_PROGRESS·환불 의도(원 승인액 전액, 구성 합 ≠ 승인액이면 FAILED AMOUNT_MISMATCH)·`RefundIntentCreated` → 커밋 뒤 payment 취소(멱등키 `REFUND-<의도 ID>`). `RefundResult`(order.payment.v1) SUCCESS → 환불 COMPLETED·클레임 CLOSED·`ClaimClosed`. `WorkStopResult`(mfc.fulfillment.v1) HANDED_OVER → REJECTED.
- 환불 sweep(`pmart.claim.refund-sweep-interval`, 기본 15초): 실행 대기는 같은 키로 재실행, PG 결과 대기·불명은 `GET …/cancellations/{requestId}` 원거래 조회. 기준 `pmart.claim.refund-retry-after`(30초).
- 조회: `GET /claims/{id}`·`/claims/{id}/refunds`(고객 본인, 남의 것 404, 운영자·서비스 모두), `GET /claims`(고객은 본인 것, cursor 없음 — 최근 순 size 건).

## 상태
고객 전체 취소·환불 실행·조회 구현(2026-10-31). 심사·결정·철회·반품·교환·회수 수령은 501.
