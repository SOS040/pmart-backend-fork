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
- 반영 회신(ADR-0039): 승인(APPROVED·PARTIALLY_APPROVED)을 반영한 같은 트랜잭션에서 `ApplicationReceiptRecorded`(receiverService `pricing-service`, receiptSeq 1, result APPLIED·PARTIAL·REJECTED, 행별 `lineKey`=ApprovalDecided 의 lineId·`reasonCode`·`domainVersion`)를 `platform.approval-receipt.v1` 로 outbox. 반려된 결정에는 회신하지 않는다.
- 소급 없음: 요청 효력 시각이 지났으면 반영 시각부터. 효력 시각이 된 버전은 그 트랜잭션에서, 예약 버전은 `PriceActivator` 주기 작업(`pmart.pricing.activation-interval`, 기본 10초)이 발효하고 `PriceVersionEffective` 를 토픽 `commerce.pricing.v1` 로 발행한다(집합 이름 기본 토픽이 아님).
- `/price-version-applications` 는 새 버전을 만들지 않는다. 요청이 받은 승인 범위와 같은지 대조하고 반영 결과를 requestId 로 묶는다. 승인을 아직 못 받았으면 `APPROVAL_REQUIRED`(같은 키로 재시도).
- 견적(`/internal/quotes`): 가격 없는 SKU 가 하나라도 있으면 `PRICE_NOT_SET` 전체 거절. 결과는 `price_quote` 에 보존.
- **할인(2026-11-21 ADR-0082, 계약 0.2.0)**: 기본가 → 기간 할인(promotion `/internal/price-discounts` — SKU 마다 가장 큰 1개, 개당 × 수량) → 장바구니 쿠폰(`couponIds[0]` + accountId, promotion `/internal/coupon-quotes` — 기간 할인 뒤 금액으로 판정, `PriceRules.allocate` 비례 안분·원 미만 버림·남은 원은 큰 품목). `itemAmount` = 기간 할인 뒤·쿠폰 전, `couponDiscount` 따로. 쓸 수 없는 쿠폰은 거절 없이 `coupon.applicable=false`. promotion 장애 → 503(정가로 계산하지 않음). 주소 `pmart.pricing.promotion-base-url`(비우면 할인 단계 없음 — 시험 기본). 클라이언트 `infrastructure.PromotionClient`, 시험 `QuoteDiscountIT`.

- **표시 할인(2026-11-21 ADR-0082 4단계, 계약 0.3.0)**: 공개 `/prices`·`/prices/{sku}` 에 `discount`(행사·할인액·salePrice·할인율·끝)와 `discountAvailable` — `DisplayPrices` 가 promotion `/internal/price-discounts` 를 묻는다(견적과 같은 계산). promotion 장애면 정가 + `discountAvailable=false`(표시용이라 거절 안 함 — 견적은 503). 내부 `/internal/prices` 는 할인 없음. 시험 `QuoteDiscountIT.displayPricesCarryTheCurrentDiscountAndFallBackToBasePrice`.

## 가격 일괄 등록 (2026-11-03, 사용자 결정)
- `/price-import-batches`: seed-v1 묶음(zip, 상품 일괄 등록과 같은 파일)의 `product.json` 에서 seedProductId·`product.internalName`(없으면 `content.name`)·`price.basePrice`·`source.system` 만 읽는다(`SeedPriceReader`). 원천 키 → SKU 는 product-service `POST /api/v1/internal/source-link-lookups`(`ProductLinks`, `pmart.pricing.product-base-url`). 조회는 트랜잭션 밖, 저장은 `RequestIdempotency` 안.
- 행 분류(`PriceImportRules`): SKU 없음 → `SKU_NOT_FOUND`, 뒤에 다시 나온 SKU → `DUPLICATE_SKU`, 가격 없음·0 이하 → `PRICE_REQUIRED`, 그 밖에는 현재 유효 가격과 비교해 NEW(가격 없던 SKU)·CHANGE(다름)·UNCHANGED(같음 → 제출 제외). 운영자 설정은 행 가격 고치기·빼기·공통 효력 시각(A안)만.
- 제출(`…/submissions`)은 다시 대조한 뒤 READY 행을 잠그고(배치 SUBMITTED, 이후 수정 409 `BATCH_LOCKED`) approval-service `POST /change-requests` 본문(type PRICE, targetRef `priceImportBatch:{batchId}`, 행 attributes `{sku, basePrice, effectiveFrom?, previousPrice, changeType, seedProductId, productName}`)을 돌려준다. 가격 버전은 여기서 만들지 않는다.
- `PriceApprovalApplier` 가 반영 뒤 targetRef 가 이 배치면 `PriceImportService.onApprovalApplied` 로 배치 상태(APPLIED / PARTIALLY_APPLIED / REJECTED)와 approvalId 를 연결한다. 행별 결과는 조회 때 `price_approval_line` 에서 읽는다.
- 업로드 상한 300MB(pmart-config `pricing-service.yml` multipart).

## 상태
가격 버전·승인 반영·발효·조회·견적 구현(2026-10-30). 기간 할인·장바구니 쿠폰 2026-11-21(ADR-0082). 수량 행사 2026-11-26(ADR-0090, 아래). 상품 쿠폰은 미구현.
- **수량 행사 1+1·2+1(2026-11-26 ADR-0090, 계약 0.4.0)**: 견적 품목 quantity = **유상 수량**. promotion `GET /internal/quantity-offers?skus=&at=`(`PromotionClient.offers`, 꺼져 있으면 빈 결과, 실패는 503 — 정가로 계산하지 않음)로 걸린 SKU 는 무료 수량 = `PriceRules.freeQuantity`(⌊유상 ÷ buy⌋ × free) → 줄 quantity = 총 수량, `paidQuantity`·`freeQuantity`·`offerPromotionId`·`offerName`·`offerBuyQuantity`·`offerFreeQuantity`, 줄 금액 = 기본가 × 총 수량, 무료분 = `promotionDiscount`. **그 SKU 는 기간 할인을 묻지 않는다**(A5 — price-discounts 요청에서 뺌). 쿠폰은 그 뒤 금액에 안분. 단계 `QUANTITY_OFFER`(기간 할인이 실제로 있을 때만 PROMOTION). 옛 견적 JSON 은 paidQuantity 0 → 응답에서 quantity 로. 시험 `QuoteDiscountIT.quantityOfferAddsFreeUnitsAndSkipsPeriodDiscount`(기본 stub 은 행사 없음).
