# 요청 — 쿠폰·기간 할인 고객 화면 (ADR-0082)

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-21 — 사용자 "쿠폰 기능 만들고싶어. 그리고 프로모션으로 해당 상품들 전체적으로 적용하는 것까지", 2·3단계 "응"
- 서버 상태: **LAN 배포 2026-11-21** — promotion-service 0.2.0(80d1a952), pricing 0.2.0·order 0.18.0·claim(1a5b6d6f), 할인가 표시 pricing 0.3.0·cart 0.4.0·storefront 0.8.0·search 0.6.0(7e9a2851).
- 계약: `contracts/openapi/promotion-service.yaml`, `order-service.yaml` 0.18.0, 할인가 칸은 `pricing`·`cart`·`storefront`·`search-service.yaml`(7번). 결정: `docs/adr/0082-coupons-and-period-promotions.md`.

## 무엇이 바뀌었나
- 원문의 "쿠폰·할인 1차 제외"를 사용자가 바꿨다. 이번 범위 = **기간 할인(상품 할인)** + **장바구니 쿠폰**(주문 1건에 1장). 상품 쿠폰·1+1·배송비 쿠폰은 없다.
- 결제 금액에 실제로 반영된다(주문서·결제·취소 환불까지).

## 화면 요청
1. **쿠폰 받기**(로그인 고객): `GET /coupon-campaigns` — 지금 내려받을 수 있는 쿠폰(이름·할인·최소 주문금액·1인 한도·내가 받은 수·남은 수량·받기 끝·사용 기간). `POST /coupon-campaigns/{promotionId}/downloads`(Idempotency-Key) → 201 쿠폰. 422 `COUPON_LIMIT_REACHED`(이미 받음)·`COUPON_SOLD_OUT`(다 나감)·`NOT_ACTIVE`.
2. **내 쿠폰함**: `GET /me/coupons?status=AVAILABLE|USED|EXPIRED` — AVAILABLE 은 기한 가까운 순. 쿠폰: 이름·할인(RATE % 또는 AMOUNT 원, 정률이면 maxAmount)·최소 주문금액·validUntil·status.
3. **주문서 쿠폰 고르기**: 주문서 만들기 `POST /order-drafts` 에 `couponId`(AVAILABLE 쿠폰 1장). **쿠폰을 바꾸면 주문서를 다시 만든다**(배송 방식 바꿀 때와 같음 — 같은 purchaseIntentId 라도 쿠폰이 다르면 새 초안).
   - 견적 `quote`: `baseItemAmount`(정가)·`promotionDiscount`(기간 할인)·`itemAmount`(기간 할인 뒤·쿠폰 전)·`couponDiscount`·`coupon`{couponId, applicable, reason, name}·`totalAmount`(= itemAmount − couponDiscount + 배달팁 + 봉투값).
   - 최소 주문금액(15,000)·무료 배달(30,000) 판정은 **쿠폰 전 itemAmount** 기준(쿠폰으로 15,000 아래가 돼도 주문 가능).
   - 쓸 수 없는 쿠폰이면 차단 사유 **`COUPON_NOT_APPLICABLE`**(detail = NOT_FOUND·NOT_OWNER·EXPIRED·HELD·USED·MIN_ORDER_NOT_MET) — 결제 버튼 막고 "쿠폰 빼기" 안내 → 쿠폰 없이 주문서 다시.
   - 품목 `discountShare`(기간 할인 + 쿠폰 나눠 넣은 몫), `unitPrice`·`lineAmount` 는 정가.
4. **결제 진입**: 서버가 쿠폰을 점유한다. 422 `COUPON_NOT_APPLICABLE`(그 사이 쓸 수 없게 됨)·`RE_QUOTE_REQUIRED`(할인액 바뀜) → 주문서 다시.
   - 결제 실패·중단이면 쿠폰은 자동으로 풀린다(최대 약 30초 뒤 쿠폰함 AVAILABLE).
5. **주문 상세**: `quote.couponDiscount`·`promotionDiscount`, 줄 `discountShare`.
6. **취소·환불**: 주문 **전체 취소**면 쿠폰이 돌아온다(원래 기한 안일 때만 — 지났으면 만료로 보임). 부분 취소·결품·반품은 쿠폰 그대로, 환불은 할인 뺀 금액.
7. **상품 할인가 표시**(2026-11-21 4단계 LAN 배포 7e9a2851) — 정가 `basePrice` 옆에 할인가·할인율(값이 null 이면 정가만):
   - 검색 결과(search 0.6.0)·홈 진열(storefront 0.8.0) 카드: `salePrice`·`discountRatePercent`·`discountName`·`discountEndsAt`.
   - 상세(pricing 0.3.0 `GET /prices/{sku}`·`/prices?skus=`): `discount`{promotionId, name, unitDiscount, salePrice, ratePercent, endsAt}. `discountAvailable=false` 면 할인을 몰라 정가만(오류 아님).
   - 장바구니(cart 0.4.0): 품목 `display.salePrice`·`discountRatePercent`·`discountName`, `selectedItemAmount` 는 할인가 합계, `selectedDiscountAmount` = 할인 합계.
   - 카드·상세는 행사 시작·끝에서 최대 약 30초 늦을 수 있다. 실제 결제 금액은 주문서 견적. 가격순 정렬·가격 필터는 정가 기준, 100g당 가격도 정가 기준(할인가 기준으로 보이려면 같은 식을 앱이).

## 확인 필요 없음 / 참고
- 쿠폰 할인 계산: 정률은 원 미만 버림, 최대 할인액, 상품액을 넘지 않음. 품목 안분은 금액 비례(남은 원은 금액 큰 품목).
- 쿠폰 지급은 직원 지정 발급도 있다(보상) — 앱은 쿠폰함에서 똑같이 보인다.
