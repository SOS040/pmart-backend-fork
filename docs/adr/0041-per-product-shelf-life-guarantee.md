# ADR-0041 상품별 소비기한 보장 — 상품 고시의 고객 약속을 SKU 필드로, 재고 배정은 상품 값 우선(보관 유형 기본값은 대체값), LOT 제조일

- 상태: 승인 (2026-11-07, 사용자 사전 허락 "응 허락할 거니까 기억해놔" — 다음 세션 1번 작업 "상품별 소비기한 보장")
- 대체: ADR-0040 결정 5 의 "소비기한 관리 상품 판단"(수령 LOT 에 기한이 있으면 검사)과 "최소 잔여일 = 보관 유형 값만". 줄당 LOT 수 제한 없음·배송 약속일 기준·보관 유형 기본값(냉장 2·냉동 30·상온 30)은 그대로다.
- 정본: product-service SKU `shelfLifeGuarantee`(계약 `ShelfLifeGuarantee`, 이벤트 `ProductVersionPublished.skus[].shelfLifeGuarantee`)

## 배경
- 상품 고시 "소비기한 또는 품질유지기한" 이 고객에게 약속하는 문구다. LAN 상품 503개를 공개 상세 API 로 모아 보니(2026-11-07):
  - `주문일 기준 N일 이상 남은 상품` 362개(N 예: 91)
  - `주문일 기준 N일 이내 제조된 상품` 99개(빵·두부 등)
  - 문구 없음 42개
- ADR-0040 은 보관 유형별 최소 잔여일 하나로만 판정했다. 상온 과자가 "91일 이상 남은" 을 약속했는데 30일만 확인하면 약속을 어긴다. "N일 이내 제조" 는 LOT 에 제조일이 없어 확인할 수 없었다.
- 고시 문구는 product-content(고객 표시 텍스트)에 있지만, 재고 배정이 문자열을 해석하면 표시 문구가 업무 규칙이 된다. 규칙 값은 상품 정본의 승인 버전에 두어야 한다.

## 결정

### 1. product-service SKU 필드 `shelfLifeGuarantee`
- `minRemainingDays`(주문일 기준 소비기한 최소 잔여일), `maxDaysSinceManufacture`(주문일 기준 제조 후 최대 경과일). 둘 다 선택, 0~3650, 둘 다 있을 수 있다. 없으면 그 보장 문구가 없는 상품이다(0 으로 채우지 않는다).
- 상품 초안(`ProductDraftInput.skus[].shelfLifeGuarantee`)으로 들어와 PRODUCT 승인으로 반영된다. 재적재 승인(BR-39)은 승인 초안 값으로 바꾼다(초안에 없으면 보장 없음). 범위를 벗어나면 초안 문제 `SHELF_LIFE_INVALID`.
- `SkuDetail`·`ProductVersionPublished.skus[]` 에 같은 모양으로 나간다(필드 추가 — 기존 소비자 호환).
- 고시 문구 → 값 변환은 등록하는 쪽(운영자·일괄 등록 도구)이 한다. product-service 는 문자열을 해석하지 않는다.

### 2. mfc-inventory 배정 규칙 (ADR-0040 결정 5 수정)
- SKU 투영에 두 값을 받는다(`sku_projection.shelf_min_remaining_days`, `shelf_max_days_since_mfg`).
- 기준일은 **배송 약속일**(ADR-0040 그대로). 고시는 "주문일 기준" 이고 약속일 ≥ 주문일이라, 약속일로 판정하면 고시 약속을 넘겨 지킨다.
- 최소 잔여일 = **상품 보장값이 있으면 그 값, 없으면 보관 유형 기본값**. 상품 값이 기본값보다 짧아도 상품 값을 쓴다(고시가 고객 약속의 정본).
- 상품이 잔여일을 보장하는데 LOT 에 소비기한이 없으면(UNTRACKED 이관분 등) 확인할 수 없으므로 배정하지 않는다. 보장이 없는 상품의 기한 없는 LOT 은 지금처럼 통과(소비기한 관리 대상 아님).
- 제조 후 경과일 보장이 있으면 `약속일 − 제조일 ≤ N` 인 LOT 만. **제조일 모르는 LOT 은 배정하지 않는다.**
- 기한 있는 LOT 인데 상품 값도 보관 유형 값도 없으면 `CONFIG_REQUIRED`(그대로).
- 적격 부족은 `NO_ELIGIBLE_LOT`(그대로).

### 3. LOT 제조일
- `stock_lot.manufacture_date`(없으면 NULL, 추정하지 않음). LOT 정체성 = SKU·제조 LOT 표기·소비기한·**제조일**(유일 인덱스 교체). 기존 LOT 은 제조일 NULL 이라 정체성이 바뀌지 않는다.
- 입력: 검수 `INSPECT` 줄 `manufactureDate`(inbound 품질검사 결과 줄 → inventory), 정정 `CORRECTION` 줄 `toManufactureDate`. inbound 는 오늘(Asia/Seoul) 이후·소비기한 이후 제조일을 400 으로 막는다.
- 조회: 센터 재고 상세 `lots[].manufactureDate`.
- CDC 이관 수령(`ConfirmReceipt` 줄)의 제조일은 아직 없다 — CDC 출고 계약이 생길 때 추가.

## 대안
- **보관 유형 기본값과 상품 값 중 큰 값**: 고시보다 엄격해 약속은 지키지만, 고시가 짧게 약속한 상품(예: 냉동 7일)의 정상 재고를 필요 이상으로 막는다. 고시를 정본으로 두는 쪽을 택했다.
- **inventory 가 product-content 고시 문구를 읽어 해석**: 표시 텍스트가 업무 규칙이 되고, 서비스 간 동기 조회가 예약 경로에 들어온다. 택하지 않음.
- **제조일 모르는 LOT 을 통과**: 고시 약속을 확인 없이 내보낸다. 택하지 않음.

## 결과
- product-service V4(SKU 열 2개), mfc-inventory V6(투영 열·LOT 제조일·정체성 인덱스), mfc-inbound 계약 0.4.0(검수 제조일), mfc-inventory 계약 0.4.0.
- 영향 소비자: `ProductVersionPublished` 구독자(mfc-inventory 가 새 필드 사용, search·cart·pricing 등은 무시 — 필드 추가만), mfc-inbound → mfc-inventory INSPECT 호출.
- 기존 상품 503개는 재적재 승인으로 값을 넣는다(고시 문구 → 값, 문구 없는 42개는 보장 없음). 시드 LOT 의 소비기한·제조일은 재고 정정(CORRECTION, 총량 불변)으로 고시를 만족하게 고친다. 값이 들어가기 전까지는 ADR-0040 동작과 같다.
