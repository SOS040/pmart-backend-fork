# [pmart-backend → pmart-clients] 요청: "다 팔렸어요" 오표시 고치기 + 상품 목록에 품절 표시 (2026-11-20)

- 보낸 영역: platform(PC 1)
- 받는 앱: customer-app
- 근거: 사용자 2026-11-20 "지금 재고가 다 다팔렸다고 떠" → 확인 결과 재고는 정상(MFC-SEED-01 503개 모두 판매 가능), 앱이 센터 없이 불러 생긴 표시. 사용자 "그 개선제안에 추가로 상품목록에서도 품절 같은 상태가 떠야지"
- 서버 쪽 짝: 목록 API 에 재고 상태를 붙이는 일(아래 2) — PC 1 이 한다. 계약 버전은 정해지면 이 문서에 적는다

## 1. 배달 주소(센터)가 없을 때 "다 팔렸어요" 대신 안내
- 지금: 상품 상세 `GET /product-contents/{id}` 를 `mfcId` 없이 부르면 옵션 `availability` 가 비어 온다(서버는 센터를 모르면 재고를 붙이지 않는다 — 0·품절이 아님). 앱은 이것을 `Availability.unknown` → `canOfferAdd=false` 로 보고, 옵션이 전부 그러면 사진을 덮고 **"다 팔렸어요"**.
  - 2026-11-20 09:15 앱을 새로 깐 폰에서 모든 상품이 "다 팔렸어요"로 보였다(로그인·주소가 풀린 상태로 보임).
- 바꿔 주세요:
  - `availability` 가 **없음(센터 모름)** → "배달 주소를 정하면 살 수 있어요" + 주소 정하기 버튼. 품절 표시·사진 덮기 하지 않음
  - `availability.state == UNKNOWN`(재고 조회 실패, 상세 `sources` 의 mfc-inventory-service UNAVAILABLE) → "재고를 확인하지 못했어요. 잠시 뒤 다시" — 역시 품절이 아님
  - `carried == false` → "이 주소로는 배달하지 않는 상품이에요"
  - `state == SOLD_OUT` 일 때만 "품절"·"다 팔렸어요"
- 장바구니 담기 막기는 지금처럼 그대로(살 수 없는 건 같다) — 문구만 원인별로.

## 2. 상품 목록 카드에 품절 표시
- 지금: 홈(storefront)·검색(search)·카테고리 목록 카드는 `saleState`(판매중·일시품절·판매중지)와 `carried` 만 받는다. **재고가 0 이 돼도 목록에는 안 보인다** — 상세에 들어가야 안다. 앱 카드도 `saleState == TEMPORARILY_SOLD_OUT` 일 때만 "일시품절".
- 서버(PC 1): 목록 항목에 `availability`(상세 옵션과 같은 값 — `AVAILABLE`·`SOLD_OUT`·`UNKNOWN`, `mfcId` 를 줬을 때만)를 붙인다. 방식·계약 버전은 정해지면 여기 적는다.
- 앱(받은 뒤):
  - `availability == SOLD_OUT` → 카드 사진 위 "품절" 표시(일시품절과 같은 모양), 담기 버튼 숨김
  - `availability` 없음(센터 모름)·`UNKNOWN` → 표시 없음(1 과 같은 이유로 품절이라고 하지 않음)
  - `saleState == TEMPORARILY_SOLD_OUT` 는 지금처럼
- 정렬·거르기(품절 상품 뒤로·빼기)는 이번 범위 아님.

## 상태
- 2026-11-20: 요청. 서버 2 는 방식 결정 뒤 구현.
