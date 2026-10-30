# order-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `order-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/order-service.yaml` → `openApiGenerate` 가 `com.pmart.order.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.order.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:order-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:order-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8201.

## 주문 초안 (2026-10-31, BR-13·14·21)
- `POST /order-drafts`(고객만): 정본 재확인 → 거절(422): 구매 불가 계정·권역 밖/판정 불명·판매 불가 SKU·구매 상한·가격 미설정·10kg 넘는 단일 상품. `MFC_CHANGED`(409).
- 차단 사유(초안은 만든다): 최소주문 미달(부족액)·한집/알뜰 불가(심야·센터 운영 밖·설정·조회 실패)·예약 시간 미선택/불가. 순서는 `OrderRules.BLOCKER_ORDER`.
- 금액(`OrderRules`, Commerce 8.3 사례로 시험): 한집 4,000·알뜰/예약 3,000, 30,000 이상·첫 구매 무료, 봉투 100, 최소 15,000, 10분 동결, 10kg 단위 분할.
- 첫 구매는 customer `first-purchase-checks` 후보면 EXPECTED(자격 확보는 결제 진입 때). 같은 구매 의도의 유효 초안은 그대로 돌려준다.
- `GET /delivery-options`(회원·비회원): 방식별 가능 여부·사유·예상시간(NOT_SET — 사용자 결정)·배달팁(기본/적용/사유)·가장 빠른 예약 슬롯.
- 의존: customer·product·pricing·center-master 는 `/internal`(서비스 토큰), 예약 슬롯은 capacity `/slots`(사용자 토큰 전파).

## 결제 진입 (2026-10-31, 슬라이스 4)
- `POST /order-drafts/{id}/checkouts`: 견적 버전·동결·차단 사유(`CHECKOUT_BLOCKED`) 확인 → orderId(초안+요청 키로 결정적) → orchestration S1 시작. 진행 중이면 기존 주문 ID.
- 초안 조회가 사가 사본을 읽어 status(RESOURCE_PENDING·PAYMENT_PENDING·PAYMENT_UNKNOWN·DRAFT)·nextAction(WAIT·PAY·REVIEW)·resources·`payment`(결제창 정보)·`checkoutFailure` 를 채운다. 사가 조회 실패면 WAIT·UNKNOWN.

## 상태
주문 초안·배송 방식 안내·결제 진입 구현(2026-10-31). 주문 확정·주문 조회·내부 API 는 501(결제 확정 단계).
