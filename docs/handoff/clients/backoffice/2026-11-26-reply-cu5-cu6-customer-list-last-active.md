# 회신 — CU-5 직원 고객 목록·이름 검색, CU-6 최근 활동일 (customer 0.22.0 · auth 0.12.0)

보낸 영역: platform
받는 앱: backoffice (backoffice-bff — PC 2)
요청: 요청서 `docs/architecture/service-requests/customer-service.md` CU-5·CU-6(PC 2 #365, 2026-11-23)
작성: pmart-backend PC 1, 2026-11-26 — 결정 2026-11-23 사용자 "추천대로", 2026-11-26 "다음 남은것처리해줘"
상태: **LAN 배포**(2026-11-26, 1d94ebb7 — pc2 customer-service V22, pc4 auth-service V11). LAN 확인: 목록 3개씩 두 쪽 겹침 0, 가입 11-20~26 38명, 이름 검색 200·audit SEARCH 기록 남음(검색어 없음), 1글자 400, auth activities 200(없는 계정 null)
함께 볼 것: O-12 회신 `2026-11-26-reply-o12-customer-purchase-summaries.md`(최근 구매일·분류용 금액)

## CU-5 — `GET /api/v1/customers` 목록 모드 (customer-service 0.22.0)
- 권한 그대로 CS_AGENT·OPERATOR_ADMIN. 기존 찾기(`customerNumber`·`phone`·`customerIds` 중 하나)는 그대로이고, **셋 다 없으면 목록**이다. 찾기와 목록 거르기를 같이 주면 400.
- 목록: 가입 최근 순(joinedAt 내림차순, 같으면 customerId), `size` 1~200(기본 50), `cursor`(응답 `page.nextCursor` — 다음 쪽에 같은 거르기와 함께, 잘못되면 400).
- 거르기
  - `name` — 이름(제공자 실명 또는 고객이 넣은 실명) **2글자 이상 앞부분 일치**(앞뒤 공백 제거, 1글자·51자 이상 400). 별명은 찾지 않는다. `%`·`_` 는 글자 그대로.
  - `joinedFrom`·`joinedTo` — 가입일(Asia/Seoul 날짜, 둘 다 포함, from > to 400).
  - `status` — ACTIVE·WITHDRAW_PENDING·WITHDRAW_COMPLETED.
- 응답: 기존 `StaffCustomerSummary`(가린 이름·가린 전화·고객번호·상태·이용제한) + **`joinedAt`**, `page{nextCursor, size}`(찾기 모드에선 null).
- **이름으로 찾으면 검색 기록이 남는다**: audit-service 열람 기록(accessType SEARCH, fields NAME, subjectRef `CUSTOMER_LIST`, recordCount = 결과 수, 검색어는 넣지 않음)이 된 뒤에만 결과를 준다 — 기록이 안 되면 **503**(결과 없음). 화면은 "잠시 뒤 다시" 안내. 이름 없이 가입일·상태만 보는 목록은 기록하지 않는다.
- 화면 "최근 주문일" 거르기는 order `GET /orders/metrics/customer-ids?lastOrderFrom=&lastOrderTo=`(O-12)로 고객 ID 를 찾은 뒤 `GET /customers?customerIds=`(50개씩)로 표시.

## CU-6 — 최근 활동일 `lastActiveAt` (auth-service 0.12.0 + order 0.25.0, bff 가 합침)
- 정의(2026-11-23 사용자): **lastActiveAt = max(마지막 로그인, 마지막 토큰 갱신, 마지막 주문)**.
- 로그인·토큰 갱신은 auth-service, 주문은 order-service 가 정본이라 customer 응답에는 넣지 않았다. bff 가 둘을 불러 가장 늦은 값을 쓴다:
  1. auth 내부 `GET /api/v1/internal/customers/activities?accountIds=CU-a,CU-b,…`(1~200, 게이트웨이 밖 — bff 에서 `http://auth-service:8501`) → `{items[{accountId, lastLoginAt, lastTokenRefreshAt}]}`(요청 순서, auth 에 없는 계정은 null).
  2. order `GET /api/v1/orders/metrics/customer-summaries?customerIds=`(1~50) 의 `lastOrderAt`.
  3. 셋 중 null 아닌 가장 늦은 값. 셋 다 null 이면 "-".
- 앱을 열었지만 토큰을 갱신하지 않은 사용(access 토큰 수명 안)은 세지 않는다. 비회원 세션은 세지 않는다.
