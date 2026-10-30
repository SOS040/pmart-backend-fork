# pricing-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `pricing-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/pricing-service.yaml` → `openApiGenerate` 가 `com.pmart.pricing.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.pricing.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:pricing-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:pricing-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8107.

## 가격 승인 → 가격 버전 (슬라이스 1, 2026-10-30)
- 가격 변경은 approval-service 변경 요청(type `PRICE`, targetService `pricing-service`)으로만 들어온다. 승인 행 형식:
  `lineKey`=SKU, `requestedAmount`=기본가, `attributes`=`{sku, basePrice, effectiveFrom?}`. 행 효력 시각이 없으면 승인의 `requestedEffectiveFrom`, 그것도 없으면 반영 시각.
- `ApprovalDecided` 를 받으면 행마다 독립 반영한다(`PriceApprovalApplier`). 거절 사유: `SKU_INVALID`·`SKU_NOT_FOUND`(product 사건 투영에 없음)·`PRICE_MISSING`·`ZERO_PRICE_UNDECIDED`(0원 허용 미정)·`PRICE_SCHEDULE_CONFLICT`(예약 버전 이전·같은 시각)·`APPROVAL_SCOPE_MISMATCH`(amount ≠ attributes.basePrice)·`DUPLICATE_SKU`. 결과는 `price_approval(_line)`.
- 소급 없음: 요청 효력 시각이 지났으면 반영 시각부터. 효력 시각이 된 버전은 그 트랜잭션에서, 예약 버전은 `PriceActivator` 주기 작업(`pmart.pricing.activation-interval`, 기본 10초)이 발효하고 `PriceVersionEffective` 를 토픽 `commerce.pricing.v1` 로 발행한다(집합 이름 기본 토픽이 아님).
- `/price-version-applications` 는 새 버전을 만들지 않는다. 요청이 받은 승인 범위와 같은지 대조하고 반영 결과를 requestId 로 묶는다. 승인을 아직 못 받았으면 `APPROVAL_REQUIRED`(같은 키로 재시도).
- 견적(`/internal/quotes`)은 1차 기본가만(`BASE`). 가격 없는 SKU 가 하나라도 있으면 `PRICE_NOT_SET` 전체 거절. 결과는 `price_quote` 에 보존.

## 상태
가격 버전·승인 반영·발효·조회·기본가 견적 구현(2026-10-30). 오퍼·프로모션·쿠폰 단계는 미구현(promotion 비활성).
