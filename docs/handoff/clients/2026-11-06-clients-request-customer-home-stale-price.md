# [pmart-backend → pmart-clients] 요청: 고객 앱 홈의 가격이 앱을 다시 켤 때까지 바뀌지 않음 (2026-11-06)

- 성격: **수정 요청(고객 앱 `apps/customer_app`).** 백엔드는 클라이언트를 수정하지 않는다. 백엔드 변경은 없다.
- 기준: pmart-clients `8e35308` 의 코드를 읽고 쓴 내용이다.

## 1. 현상 (LAN, 2026-11-06)
- `E-00000239` 오뚜기 딸기잼 300g: 가격 승인으로 5190 → **5200원(v2)**, 12:13 KST 발효.
- 서버는 모두 5200원이다: pricing `/prices/E-00000239`, 게이트웨이 검색, storefront `/storefront/sections/S-ALL/items`, 검색 ES.
- 고객 앱 **홈**은 옛 가격(5190)을 계속 보였다. 앱을 완전히 종료하고 다시 켜니 5200원이 보였다(사용자 확인).
- 상품 상세·목록·장바구니는 열 때마다 서버에서 다시 읽어 새 가격이다. 그래서 한 앱 안에서 홈과 상세의 가격이 다르게 보인다.

## 2. 원인 (추정, 코드 기준)
- `lib/features/catalog/presentation/catalog_providers.dart` 19줄 `homeProvider` 가 `FutureProvider`(autoDispose 아님)다. 쇼핑 주소(`currentMfcIdProvider`)가 바뀔 때만 다시 조회한다. 그래서 앱 프로세스가 살아 있는 동안 처음 받은 홈 응답이 그대로 남는다.
- 다른 화면 provider(`productDetailProvider`·`productListProvider`·`homeTabProvider` 등)는 `autoDispose` 라 다시 열면 새로 읽는다.

## 3. 요청
아래 가운데 화면 정책에 맞는 것을 고른다. 여러 개를 함께 써도 된다.
1. 홈을 당겨서 새로 고침(`RefreshIndicator` → `ref.invalidate(homeProvider)`).
2. 앱이 다시 앞으로 올 때(`AppLifecycleState.resumed`) 홈을 다시 읽는다.
3. 일정 시간(예: 몇 분)이 지나면 다시 읽는다.

## 4. 참고
- 결제 금액은 틀리지 않는다. 주문할 때 order-orchestration 이 pricing 견적을 다시 받는다. 문제는 표시 가격이 서로 다른 것이다.
- 가격은 승인 후 발효 시각에 바뀐다. 예약 발효는 pricing 이 10초 주기로 처리한다. 서버 쪽에는 가격 캐시가 없다(storefront·검색은 `PriceVersionEffective` 를 받아 바로 갱신).
- 확인 방법: 앱을 켜 둔 채로 백오피스에서 한 SKU 가격을 바꿔 승인한다(적용 시작일은 비우거나 지금). 발효 뒤 홈에 새 가격이 보이는지 본다.
