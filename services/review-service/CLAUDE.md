# review-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `review-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다. 결정은 ADR-0063.

## 계약
- REST: `contracts/openapi/review-service.yaml`(0.3.0) → `openApiGenerate` 가 `com.pmart.review.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 구독 `DeliveryCompleted`(delivery.delivery.v1, 바인딩 `deliveryEvents-in-0`), 발행 `ReviewPublished`·`ReviewDeleted`(commerce.review.v1). 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `EventConsumers`(inbox).

## 패키지
`com.pmart.review.{api, application, domain, infrastructure}`. 규칙 `domain/ReviewRules`, 실행 `application/ReviewService`, 표 `infrastructure/ReviewStore`(V2 — `review_eligibility`·`review`·`review_photo`), 내부 조회 `infrastructure/Upstreams`(order·customer).

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.
- 리뷰 본문·별명은 로그 금지.

## 실행
```bash
./gradlew :services:review-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:review-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8108. LAN PC 2.

## 리뷰 1차 (2026-11-16, ADR-0063)
- 자격: `DeliveryCompleted` 품목마다 `review_eligibility`(첫 수령 시각·받은 수량 누계, 계정·SKU·상품·상품명은 order 내부 주문 사본). order 에 없는 주문은 건너뜀, order 장애는 예외(재시도·DLT).
- 작성 `POST /reviews`: 본인 품목·상품 있음(아니면 422 NOT_ELIGIBLE) → order 로 반품 확인(받은 수량 전부 반품이면 NOT_ELIGIBLE, order 장애 503) → 작성자 표시(customer, 실패면 "PMart 고객") → 멱등 처리 안에서 자격 행 잠금·중복(ALREADY_REVIEWED / 삭제됨 REWRITE_AFTER_DELETE_FORBIDDEN)·기한(ELIGIBILITY_EXPIRED)·사진 붙이기(PHOTO_NOT_FOUND) → `ReviewPublished`.
- 수정 `PATCH`: 본인(남의 것 404)·삭제됨/3일 지남 409 INVALID_STATE·X-Expected-Version. photoIds 를 주면 그 목록으로 바꾸고 빠진 사진은 떼어 정리 대상. 다시 `ReviewPublished`.
- 삭제 `DELETE`: 멱등(이미 삭제면 204), 행·사진은 남김(공개만 닫힘), `ReviewDeleted`.
- 사진: `POST /review-photos`(10MB·JPEG/PNG/WEBP, 키 `review-photos/<accountId>/<photoId>`), `GET /review-photos/{id}`(공개 리뷰에 붙은 것만). `OrphanPhotoSweeper` 1시간마다 24시간 지난 미부착·분리 사진 삭제. 버킷 미설정이면 사진만 503.
- 공개 경로(`permit-paths`)는 경로 단위라 `/reviews/*` 의 PATCH·DELETE 도 토큰 없이 들어온다 — 서비스가 고객 토큰을 다시 확인한다(403).
- 설정 `pmart.review.order-base-url`·`customer-base-url`, `pmart.storage.s3.*`.
- **주문 리뷰 작성 여부 내부 조회(2026-11-18, 계약 0.3.0 — 사용자 결정 "A해줘", 리뷰 요청 알림)**: `GET /internal/orders/{orderId}/review-status?accountId=`(토큰 없음) → 그 계정의 자격 품목 수·리뷰 쓴 품목 수(삭제 포함)·아직 쓸 수 있는 품목 수(리뷰 없음·수령 + 14일 안)·reviewed. 자격이 없거나 남의 주문이면 모두 0(404 아님), 반품은 보지 않는다(작성 때 order 로 확인). 부르는 쪽 notification-service(배송 완료 1시간 뒤 리뷰 요청). `ReviewStore.orderStatus`, 시험 `ReviewsIT.orderReviewStatusForNotification`.

## 상태
리뷰 자격·작성·수정·삭제·공개 조회·사진·내부 조회 구현(2026-11-16). 운영자 숨김·신고 없음(사용자 결정). 평점 집계 투영은 product-content(아직 구독 안 함).
