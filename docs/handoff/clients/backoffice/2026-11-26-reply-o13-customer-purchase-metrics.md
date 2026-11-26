# 회신 — O-13 고객 구매 지표(재구매·한 번만·누적 구매액·평균 구매 횟수) (order-service 0.24.0)

보낸 영역: platform
받는 앱: backoffice (backoffice-bff — PC 2)
요청: 요청서 `docs/architecture/service-requests/order-service.md` O-13(PC 2 #365, 2026-11-23)
작성: pmart-backend PC 1, 2026-11-26 — 사용자 결정 "그대로 해줘"(첫 구매 기준)
계약: **order-service 0.24.0** `GET /api/v1/orders/metrics/customers`(`contracts/openapi/order-service.yaml` `getCustomerPurchaseMetrics`)
상태: **LAN 배포**(2026-11-26, 9f4504ac — pc2 order-service, pc4 api-gateway 허용 목록)

## 기준 (사용자 결정 2026-11-26)
- **첫 구매 기준**: 첫 "산" 주문의 확정 시각(한국 날짜)이 from~to(둘 다 포함) 안인 고객만 센다. 그 고객의 주문은 첫 구매부터 **기간 끝(to)까지** 센다. 가입일 기준은 하지 않는다(가입일은 customer 에 있고 order 가 모름).
- "산 것" = 확정 주문 중 CANCELLED·EXPIRED 아님, 출고 전 전부 취소된 줄 빼고, 횟수는 **서로 다른 주문 수**. 반품·환불은 빼지 않는다. 고객앱 "자주 산 상품"·리뷰 "재구매" 표시와 같은 정의(리뷰는 배송 완료만 세서 조금 다를 수 있음).
- 이탈 = 한 번만 산 고객(2026-11-23 사용자 "한 번 사고 다시는 안 산 고객 비율").

## 요청·응답
- `GET /api/v1/orders/metrics/customers?from=YYYY-MM-DD&to=YYYY-MM-DD[&mfcId=]` — 직원 토큰. 권한은 다른 `/orders/metrics/*` 와 같다: OPERATOR_ADMIN 모든 센터(mfcId 선택), MFC_SUPERVISOR 는 mfcId 필수(없으면 400)·자기 센터만(아니면 403). 기간 366일(포함) 이하, from > to 400.
- mfcId 를 주면 **그 센터 주문만으로** 첫 구매·횟수·금액을 센다(다른 센터에서 먼저 산 고객도 이 센터 첫 구매가 기간 안이면 들어감).
- 응답: `{from, to, mfcId, customerCount, repeatCustomerCount, oneTimeCustomerCount, orderCount, purchaseAmount}`
  - 재구매율 = repeatCustomerCount ÷ customerCount, 이탈률 = oneTimeCustomerCount ÷ customerCount, 평균 구매 횟수 = orderCount ÷ customerCount — **화면(또는 bff)이 계산**(BO-28, 분모 0 이면 "-").
  - purchaseAmount = 줄마다 (줄 금액 − 할인 분담) × 남은 수량 ÷ 주문 수량 합(top-products 와 같은 산식, 배달팁·봉투값 빼고, 정수 원).
- LAN 확인(2026-11-26): 11-01~26 전 센터 고객 8·재구매 4·한 번 4·주문 41, MFC-BJG-01 고객 6·재구매 3.

## 이번에 안 한 것
- 요청서의 **고객 한 명 칸**(누적 구매액·구매 횟수·첫·마지막 구매일)은 O-12 `customer-summaries` 에 넣었다 — `2026-11-26-reply-o12-customer-purchase-summaries.md`.
