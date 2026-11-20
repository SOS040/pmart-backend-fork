# promotion-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `promotion-service` 절, 결정은 ADR-0082(쿠폰·기간 할인, 2026-11-20). 여기에는 이 서비스 안에서만 적용되는 규칙을 둔다.

## 계약
- REST: `contracts/openapi/promotion-service.yaml` → `com.pmart.promotion.api.generated`. `api/*Controller` 가 구현(PromotionsApi 직원·CouponsApi 고객·InternalApi 서비스).
- 구독: `commerce.pricing.v1` PriceVersionEffective(기본가 사본 — 표시 할인, 아래), `commerce.product.v1` ProductVersionPublished(SKU → 카테고리 사본, 상위 포함 — `PromotionRules.withAncestors` 가 id 접두 규칙 `p09 → p09-c01 → p09-c01-l01` 을 쓴다), `platform.approval.v1` ApprovalDecided(PROMOTION·promotion-service 만).
- 발행: `ApplicationReceiptRecorded`(platform.approval-receipt.v1 — 승인 반영 회신, ADR-0039), `SkuDisplayDiscountChanged`(commerce.sku-display-discount.v1 — 표시 할인).

## 규칙 (ADR-0082)
- 행사 두 종류: PRICE_DISCOUNT(기간 할인 — SKU·카테고리 대상, 정률·정액(개당)), COUPON(장바구니 쿠폰 — 정액·정률+최대, 최소 주문금액, 총 수량·1인 한도, 사용 기간 = 받은 날 포함 N일 또는 고정 기한, 발급 방식 DOWNLOAD·ADMIN·SIGNUP).
- 상태 DRAFT → SUBMITTED(해시 잠금) → APPROVED(시작~끝 사이만 적용, `phase`) / REJECTED / ENDED(조기 종료 승인). 승인 행 attributes.action = ACTIVATE·END, draftHash 로 대조.
- 역할: 만들기·고치기·제출 MARKETING·OPERATOR_ADMIN, 보기 + CS_AGENT, 지정 발급 MARKETING·CS_AGENT·OPERATOR_ADMIN. 승인자는 approval-service `approver-roles.PROMOTION`(MARKETING,OPERATOR_ADMIN — 자기 승인 금지).
- 기간 할인 계산: 같은 SKU 에 걸린 것 중 할인액이 가장 큰 1개(같으면 먼저 끝나는 것). 정률 원 미만 버림, 할인 ≤ 단가.
- 쿠폰: 발급은 행사 행 잠금(마지막 1장 한 명), 점유는 쿠폰 행 잠금 + 유일 인덱스(살아 있는 점유 하나). 점유 때 할인액을 정해 그대로. 확정 → USED, 해제 → AVAILABLE, 전체 취소 복원 → AVAILABLE(원래 기한이 지났으면 만료로 보임, `couponUsable=false`).
- 상태 어긋남은 409 INVALID_STATE(공통 ErrorCode). 조회성 POST(`/internal/price-discounts`·`/internal/coupon-quotes`)는 Idempotency-Key 없음(`pmart.web.excluded-paths`).

## 할인가 표시 (2026-11-21 ADR-0082 4단계)
- 구독 `pricingEvents-in-0` ← commerce.pricing.v1 PriceVersionEffective → `sku_price`(버전 큰 것만, `PriceProjection`). 기본가가 있어야 정률·정액 중 가장 큰 1개를 고른다.
- `DisplayDiscountSync`(`pmart.promotion.display-sync-interval`, 기본 30초): 진행 중 기간 할인이 걸린 SKU + 지금 할인으로 보이는 SKU 를 견적과 같은 `PriceDiscounts` 로 다시 계산 → `sku_display_discount` 와 다르면 seq+1 저장 + `SkuDisplayDiscountChanged`(토픽 `commerce.sku-display-discount.v1`, 키 SKU, compact — LAN topics-init). 할인 없어짐 = unitDiscount 0. 500개씩 트랜잭션, advisory lock 으로 인스턴스 하나만. 가격 없는 SKU 는 알리지 않음. 시험 `PromotionsIT.displayDiscountFollowsPriceStartEndAndCategoryProducts`.

## 아직 없는 것 (ADR-0082 나누어 만들기 5~6)
가입 쿠폰 자동 발급(SIGNUP — 발급 방식만 있음), CL-5 보상 쿠폰 호출. (2026-11-21: pricing 견적·order 점유/확정/해제/복원·claim 환불은 연결됨 — 각 서비스 CLAUDE.md)

## 실행
```bash
./gradlew :services:promotion-service:check
```
로컬 포트 8109. LAN PC 2.
