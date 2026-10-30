# order-orchestration-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `order-orchestration-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/order-orchestration-service.yaml` → `openApiGenerate` 가 `com.pmart.order.orchestration.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.order.orchestration.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:order-orchestration-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:order-orchestration-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8202.

## S1 결제 진입 사가 (2026-10-31, 슬라이스 4 a~f)
- 흐름·보상·만료 규칙의 정본: `docs/business/flows/order-checkout-saga.md` §1.4. 규칙은 `domain/CheckoutRules`, 실행은 `application/CheckoutSagaService`.
- 시작 명령은 사가만 저장(202)하고 단계는 가상 스레드에서 진행. 단계 명령 키 `sagaId:단계` — 재개·재전송이 정본에 한 번만 반영된다. `sweep()` 이 멈춘 사가·자원 만료를 처리.
- 단계 실행 중 사가 행을 `FOR UPDATE` 로 잡는다(같은 사가를 두 실행자가 진행하지 않음). 하위 호출은 `Participants` 한 곳(서비스 토큰).
- 해제는 원예약 조회로 RELEASED·EXPIRED 를 본 뒤에만 RELEASED. 결제 불명·불일치면 자원을 풀지 않는다.
- 발행: `CheckoutSagaStateChanged`·`CompensationExecuted` (order.saga.v1).

## 상태
S1 a~f(결제 시작까지) 구현. 승인 대사·확정(g~j), S2~S4, 수동 개입(BO-12)은 501.
