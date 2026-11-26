# 요청 — 검색창 빈칸에 "자주 산 상품" 칩 (+ 회신: 리뷰 R4 `repurchased` 는 백엔드가 같이 함)

보낸 영역: platform
받는 앱: customer-app
작성: pmart-backend, 2026-11-26 — 사용자 "고객이 자주구매하는것에 대한 기록이 있어?" → "1, 2로 할건데" → "리뷰 '재구매' 표시, 백오피스 재구매율까지 … 검색순위 반영은 안할게" → "그대로 해줘"
상태: 백엔드 order-service 0.24.0·review-service 0.9.0 **LAN 배포**(2026-11-26, 9f4504ac — pc2 order·review, pc4 api-gateway). 앱 작업 요청.

## 백엔드가 만든 것
- `GET /api/v1/orders/frequent-items?windowDays=180&minOrders=2&limit=20` — **회원(CUSTOMER) 토큰만**(비회원·직원 403). 계약 `contracts/openapi/order-service.yaml` 0.24.0 `listFrequentItems`.
  - 응답 `{from, items[{sku, productId, productName, optionLabel, orderCount, totalQuantity, lastPurchasedAt, imageUrl}]}`.
  - "산 것" = 확정 주문 중 취소·만료 아님, 출고 전 전부 취소된 줄 빼고, **서로 다른 주문 수**로 센다(한 주문에 2개 = 1번). 숨긴 주문(주문 내역 삭제)은 세지 않는다. 반품은 빼지 않는다.
  - 정렬: 산 횟수 많은 순 → 마지막 구매 최근 순. 이름·옵션은 가장 최근 주문의 주문 당시 사본(없으면 null), `imageUrl` 은 지금 대표 사진(못 가져오면 null).
  - 범위: windowDays 1~365, minOrders 1~10, limit 1~50 — 벗어나면 400.
- 검색 순위에는 넣지 않는다(사용자 결정 — 백엔드 `docs/architecture/product-search.md` §15). 검색 결과는 누구에게나 같다.

- LAN 확인(2026-11-26): 목포 시험 고객 `minOrders=1` 14개·기본값 0개(아직 같은 상품을 두 번 산 게 없음), 토큰 없이 401.

## 요청 (앱)
- F1 검색 화면에서 **검색어를 넣기 전**(빈칸) 영역에 "자주 산 상품" 칩/작은 카드 — 위 API 기본값(180일·2번 이상·20개). 최근 검색어·인기 키워드와 같은 화면, 순서는 앱이 정한다.
- F2 칩을 누르면 그 상품 상세(`productId`, `sku` 옵션 선택). `productId` 가 null 인 옛 줄은 칩에서 뺀다.
- F3 비회원이거나 빈 목록이면 영역을 숨긴다. 호출 실패도 숨김(검색은 그대로).
- 이름이 null 이면 SKU 를 보여 주지 말고 그 칩을 뺀다(BR-92 처럼 SKU 가 화면에 나오지 않게).

## 리뷰 R4 `repurchased` (2026-11-25 `backend-product-page` 요청)
- 백엔드가 같이 만든다(review-service 0.9.0) — 리뷰 목록 항목 `repurchased`: 작성자가 **그 상품을 서로 다른 주문 2번 이상 배송 완료로 받은** 회원이면 true. review-service 가 2026-11-16 부터 모은 배송 완료 기록으로 세서, 그 전 배송은 세지 않는다(사용자 받아들임). 상품 리뷰 목록 밖(단건·내 리뷰)은 null. **LAN 배포됨** — 예: 리뷰 50개 상품에서 true 1·false 49.
- R4 `authorImageUrl` 은 이번 범위 밖.
