# 회신 — O-12 고객별 구매 요약·마지막 구매일로 고객 찾기 (order-service 0.25.0)

보낸 영역: platform
받는 앱: backoffice (backoffice-bff — PC 2)
요청: 요청서 `docs/architecture/service-requests/order-service.md` O-12(PC 2 #365, 2026-11-23)
작성: pmart-backend PC 1, 2026-11-26 — 사용자 "고객별 남은거 해줘"
계약: **order-service 0.25.0**(`contracts/openapi/order-service.yaml` `getCustomerPurchaseSummaries`·`findCustomersByLastOrder`)
상태: 구현·시험 통과. **LAN 배포 전**(배포되면 이 줄을 고친다)
함께 볼 것: O-13 회신 `docs/handoff/clients/backoffice/2026-11-26-reply-o13-customer-purchase-metrics.md`(같은 정의)

## 권한
- 직원 토큰의 **CS_AGENT·OPERATOR_ADMIN**(customer 직원 고객 조회와 같은 역할). 센터 범위 없음. 그 밖 403.

## 정의 — 요청서와 다른 점
- 요청서는 "구매 = 확정 주문, 금액 = 승인 금액(O-8 정의)" 이었지만, **O-13·고객앱 자주 산 상품과 같은 "산 것"** 으로 했다:
  - CANCELLED·EXPIRED 주문은 세지 않는다(O-8 승인 금액은 취소된 주문도 세서, 다 취소한 고객이 "우수"가 될 수 있음).
  - 출고 전 전부 취소된 줄은 빼고, 횟수는 서로 다른 주문 수.
  - 금액 = 줄마다 (줄 금액 − 할인 분담) × 남은 수량 ÷ 주문 수량 합 — **배달팁·봉투값은 빼고**, 쿠폰 할인은 할인 분담에 들어 있다.
- **환불은 빼지 않는다**(요청서 질문의 답). 환불은 claim-service 가 따로 준다.
- 고객이 숨긴 주문(고객 화면의 주문 내역 삭제)도 센다.

## API
1. `GET /api/v1/orders/metrics/customer-summaries?customerIds=CU-a,CU-b,…`(1~50개, 쉼표, 중복은 한 번)
   - 응답 `{amountSince, items[{customerId, orderCount, totalAmount, amount3m, firstOrderAt, lastOrderAt}]}` — **요청 순서대로 모든 ID 한 줄**. 산 것이 없는 고객은 orderCount 0·금액 0·날짜 null.
   - amount3m = 확정 시각이 `amountSince`(오늘 Asia/Seoul 3개월 전 날짜 0시) 이후인 산 주문 금액 — "우수 = 3개월 100만원" 판정용.
   - totalAmount·orderCount·firstOrderAt·lastOrderAt = O-13 이 미룬 "고객 한 명 칸".
2. `GET /api/v1/orders/metrics/customer-ids?lastOrderFrom=YYYY-MM-DD&lastOrderTo=YYYY-MM-DD&size=&cursor=`
   - 마지막 산 주문의 확정 시각(한국 날짜, 둘 다 포함)이 그 안인 고객. 하나만 줘도 되고 둘 다 없으면 400, from > to 400.
   - `{items[{customerId, lastOrderAt}], page{nextCursor, size}}` — 마지막 구매 최근 순, size 1~200(기본 50), 다음 쪽은 같은 조건 + cursor(잘못된 cursor 400).
   - **한 번도 산 적 없는 고객은 나오지 않는다** — 휴면(1년 구매 없음) 목록에서 "가입 1년 넘고 주문 없음" 고객은 bff 가 가입 목록(customer)과 summaries orderCount 0 으로 채운다.

## bff 쪽 분류 계산 예(사용자 결정 2026-11-23 — bff 가 이름을 붙임)
- 신규 = 가입 1달 이내(customer 가입일) · 우수 = amount3m ≥ 1,000,000 · 휴면 = lastOrderAt 이 1년보다 전이거나 없음 · 위험 = lastOrderAt 이 6개월~1년 전(겹치면 휴면) · 그 밖 일반.
- "휴면 고객만" 목록: `customer-ids?lastOrderTo=<오늘−1년−1일>` + 주문 없는 고객.
