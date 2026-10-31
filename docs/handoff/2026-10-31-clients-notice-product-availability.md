# [pmart-backend → pmart-clients] 안내: 상품·장바구니 재고(가용) 표시 연결 (2026-10-31)

- 계약: `contracts/openapi/product-content-service.yaml`(SaleOption.availability·label, 카드 조회 `mfcId`), `cart-service.yaml`(CartItemDisplay.availability·availableQty)
- LAN 배포됨: cart-service(b2c8f01), product-content-service(5b47b4d)

## 1. 무엇이 바뀌었나
- 지금까지 고객 앱의 재고 표시는 늘 "확인 중"이었다. 백엔드가 재고 서비스(mfc-inventory)와 연결돼 있지 않았기 때문이다.
- 이제 **현재 쇼핑 주소의 담당 MFC(mfcId)** 를 주면 그 MFC 의 재고로 상태를 채운다. 구매·예약 보장은 아니다. 재고는 결제 진입 때 다시 잡는다.

| 화면 | API | 값 |
|---|---|---|
| 상품 상세 | `GET /product-contents/{productId}?mfcId=` | `options[].availability` = `{carried, state}` |
| 상품 카드 일괄 | `GET /product-contents?productIds=…&mfcId=` (**mfcId 새로 추가**) | 같음 |
| 장바구니 | `POST /carts {mfcId}` 로 연 장바구니의 `GET /carts/{id}` | `items[].display.availability`·`availableQty` |

### 상품 상세·카드 `availability`
- `carried=false`: 그 MFC 가 취급하지 않는다(state UNKNOWN). 앱의 기존 처리(담기 비활성)와 맞다.
- `state=SOLD_OUT`: 가용 0개. `AVAILABLE`: 가용 1개 이상.
- `LOW`("N개 남음")는 아직 쓰지 않는다. 업무 문서상 노출 임계값이 운영 설정이라 정해지지 않았다.
- `availability` 가 없거나 `state=UNKNOWN`(carried 없음): mfcId 를 안 줬거나 재고 조회 실패. 품절로 보이지 말 것.

### 장바구니 `availability`
- AVAILABLE: 담은 수량 이상 있음. LOW: 0 < 가용 < 담은 수량, 이때만 `availableQty` = 살 수 있는 최대 수량.
- SOLD_OUT: 가용 0(`availableQty`=0). NOT_CARRIED: 취급 안 함. UNKNOWN: 장바구니에 mfcId 가 없거나 조회 실패.
- 담기는 재고로 막지 않는다(Commerce 7장).

## 2. 앱에서 맞춰 줄 것
1. **옵션 라벨이 없을 수 있다.** 콘텐츠에 옵션이 없고 SKU 가 1개인 상품은 그 SKU 하나를 옵션으로 준다(`label` 없음). LAN 상품 17개가 모두 이 경우다.
   - 지금 앱은 `o.label ?? o.sku!` 라서 SKU 코드(E-00000001)가 라벨로 보일 것이다. 라벨이 없으면 옵션 이름을 비우거나 상품명만 보여 달라.
   - 이전에는 이 상품들의 `options` 가 비어 있어서 상세에서 담기가 꺼져 있었을 것이다. 이제는 켜진다.
2. **카드 조회에 `mfcId` 를 넘겨 달라.** 상세는 이미 넘기고 있다. 카드(`listProductContents`)는 계약에 파라미터가 새로 생겼다. 계약 재생성이 필요하다.
3. 홈(storefront)·검색 결과 카드의 취급·가용은 아직 채우지 않는다(다음 작업).

## 3. 확인 조건
- 쇼핑 주소의 담당 MFC 가 있어야 한다. LAN 담당 MFC 는 `MFC-SEED-01`(부산진구)이다.
- 새 주소의 판정은 카카오맵 사용 설정이 켜져야 된다. 회신 `2026-10-31-clients-reply-address-geocoding.md` 5장 참고.
- LAN 확인(2026-10-31):
  - `GET /product-contents/PRD-b962c916…?mfcId=MFC-SEED-01` → `options[0]` = `{sku: E-00000001, availability: {carried: true, state: AVAILABLE}}`
  - 카드 2건도 같다.
  - mfcId 없이 조회하면 `availability` 는 null 이다.
