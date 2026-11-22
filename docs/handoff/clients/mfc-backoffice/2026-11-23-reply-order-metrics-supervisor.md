# 회신 — 센터 관리자 주문 수·매출 지표(O-11)

보낸 영역: platform
받는 앱: mfc-backoffice
요청: pmart-clients `docs/handoff/mfc-backoffice/2026-11-23-backend-order-metrics-supervisor.md`(앞 요청 `2026-11-21-backend-center-order-metrics.md`), 요청서 `service-requests/order-service.md` O-11
작성: pmart-backend, 2026-11-23 — 사용자 결정 "센터 관리자에게 토큰 센터만"
계약: **order-service 0.20.0**(`contracts/openapi/order-service.yaml`)

## 권한 (summary·top-products·series 모두)
- `OPERATOR_ADMIN`: 모든 센터, `mfcId` 선택(지금과 같음).
- `MFC_SUPERVISOR`: **`mfcId` 필수**(없으면 400 VALIDATION_FAILED), 토큰 센터가 아니면 403. 그 밖 403.
- 매출 = 승인 금액(환불 빼지 않음), 기준 시각 = 주문 확정 시각의 한국 시각 — O-8 그대로. 개인정보 없음.

## 1·4 추이 `GET /orders/metrics/series`
- `from`·`to`(한국 날짜, 포함), `mfcId`, **`bucket`**(필수): `HOUR`(정시 칸) · `DAY` · `WEEKDAY_HOUR`(요일 1=월…7=일 × 시 0~23).
- 기간: HOUR·DAY 93일, WEEKDAY_HOUR 366일 이하(1년 열지도를 한 번에).
- 줄: `start`(HOUR·DAY 칸 시작, +09:00) 또는 `weekday`·`hour`·`days`(그 요일이 기간에 든 날 수), `orderCount`, `approvedAmount`, `qty`, `lineAmount`, WEEKDAY_HOUR 는 `avgOrderCount`·`avgLineAmount`(= 합 ÷ days, 소수 둘째 자리).
- **0건 칸은 오지 않는다** — 화면이 0 으로 채운다. 칸 오름차순.
- 거르기 `sku` 또는 `categoryId`(하위 포함): 그 상품 줄이 든 주문만 세고, 금액은 그 줄 금액(`lineAmount`)·수량(`qty`)만. 이때 `approvedAmount` 는 **null**(주문 전체 금액이라 섞지 않음).

## 3 카테고리
- 주문 줄에는 카테고리 사본이 없다 → **상품의 지금 대표 카테고리**(product 값, 서버 5분 사본)로 붙인다. 상품의 카테고리를 옮기면 지난 주문도 새 카테고리로 센다.
- `GET /orders/metrics/top-products` 줄에 `categoryId`·`categoryName`(이름 경로, 예 "라면·면 > 봉지라면"), 모르면 null. `categoryId` 거르기(하위 포함)도 받는다.
- 카테고리 목록: product 공개 `GET /api/v1/categories/tree`(nodes 의 categoryId·parentId·name·path)를 화면이 직접 부른다.

## 확인
- order-service `check` 94 통과(`StaffOrderLookupIT.centerSupervisorSeesOwnCenterMetricsAndSeries` — 400·403·자기 센터, HOUR·DAY·WEEKDAY_HOUR, sku·카테고리 거르기, top-products 카테고리).
- LAN 반영은 아래 "상태" 줄.

상태: LAN 배포 2026-11-23(아래 일지 참고)
