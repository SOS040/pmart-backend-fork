# [pmart-backend → pmart-clients] 회신: 라이더 주문 요약 (§1) (2026-11-16)

- 보낸 영역: delivery
- 받는 앱: rider_app
- 근거: 라이더 앱 요청 §1, 사용자 결정 2026-11-16 "앱 요청대로 전부 수락 전부터"

## 된 것
- 제안 `summary.order`(수락 전 카드·상세): `orderId`, `mfcName`·`mfcAddress`(픽업 지점 — center-master), `items[]`(`name`·`optionLabel`·`qty`·`lineAmount`), `itemAmount`(상품 합계), `deliveryNote`(배송 요청사항). 서버가 배차 준비 때 한 번 받아 둔 값이라 못 받았으면 없을 수 있다(그때는 표시 생략).
- 배송 조회 `GET /deliveries/{id}` 의 `orderSummary`(같은 모양, orderId 없음) — 수행 화면의 "주문정보".
- 금액은 서버 값을 표시만. 고객 이름·연락처·주소·출입은 지금처럼 픽업 뒤 drop-info.

## 아직
- 주문번호 8자리(화면용 번호) — all 요청(`all/2026-11-16-backend-display-order-number.md`, order — platform)과 함께. 지금은 orderId.
- 가게 전화번호 — center-master 에 칸이 없다(platform 몫).
- "가게에 남긴 요청(포장 요청)" — 주문에 그런 칸이 없다(지금은 배송 요청사항 하나).
