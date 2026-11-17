# [pmart-backend → pmart-clients] 알림: 고객앱 갭 — 새로 생긴 백엔드 8가지 (2026-11-18)

- 보낸 영역: platform(PC 1)
- 받는 앱: customer_app
- 근거: 사용자 결정 2026-11-18 "A해줘" — `2026-11-18-notice-reference-gap-update.md` §2(백엔드 없음) 중 바로 만들 수 있는 것. 나머지(교환·현금영수증·단위 가격·라이더 통화·실시간 상담·매장 위치)는 "나중에"(`docs/business/flows/pending-user-decisions.md` L-8~L-13).
- 바로잡기: 갭 갱신 문서 §2 의 "배달 지연 제보 — 고객용 API 없음"은 틀렸다. 11-17 부터 문의 `deliveryIssue: DELAYED` 로 있었다(아래 7).
- 상태: main 에 들어감, **LAN 배포 전**(Docker Hub 로그인 대기 — 배포하면 PC 1 일지에 적는다). 계약을 보고 먼저 만들어도 된다.

## 1. 리뷰 요청 알림 — notification 0.4.2·review 0.3.0
- 알림함 새 종류 **`REVIEW_REQUESTED`**(category ORDER, 대상 = 주문 `ORDER`/orderId): 배송 완료 1시간 뒤, 그 주문에 리뷰를 안 썼을 때만, 주문당 한 번. 제목 "받은 상품은 어떠셨나요?".
- **알림함만**(휴대폰 푸시 7종에는 없음). 반품 회수 배송·취소 주문은 보내지 않음.
- 앱: 이 종류를 누르면 그 주문의 리뷰 쓰기 화면으로.

## 2. 주문 내역 카드 — order 0.12.0
- `GET /orders` 항목(`OrderSummary`)에 `deliveryMode`(SINGLE·BUNDLED·SCHEDULED)와 `representativeItem {productId, sku, productName, optionLabel, quantity}`(첫 주문 행). "<productName> 외 N개" = `itemCount` 로. 썸네일은 productId 로 상품 카드에서.
- `productName` 은 주문 때 사본 — 2026-11-02 전 주문은 null(그때는 "상품 N개"로).

## 3. 주문 내역 기간·검색 — order 0.12.0
- `GET /orders?from=&to=&q=&status=` — `from`·`to` 는 날짜(Asia/Seoul, 둘 다 포함, 확정 시각 기준), `q` 는 상품명 부분 일치(대소문자 무시, 100자).
- **둘 다 없으면 최근 3개월**(지금까지 전체를 받던 것과 달라짐 — "전체 기간"이 필요하면 `from` 을 오래전 날짜로). `from > to` 400.
- 다음 쪽은 **같은 필터 + cursor** 로 다시 보낸다.

## 4. 주문 내역 삭제(숨기기) — order 0.12.0
- `POST /orders/{orderId}/hides`(Idempotency-Key·X-Source-System, 고객 토큰) — 끝난 주문(COMPLETED·CANCELLED·EXPIRED)만, 아니면 409 `ORDER_NOT_FINISHED`, 남의 주문 404. 응답 APPLIED. 이미 숨긴 주문도 APPLIED.
- 숨긴 주문은 목록에서 빠짐, `GET /orders/{id}` 는 그대로(처리 내역 링크용). **되돌리기 없음** — 화면에 확인 창.

## 5. 장바구니 최소 주문 금액 진행 바 — cart 0.2.0
- 장바구니 응답(`Cart` — 바꾸기 응답 안의 Cart 포함)에 `minOrderAmount`(15,000)·`minOrderShortfall` = max(0, 15,000 − `selectedItemAmount`). 가격 모르는 줄이 있으면 null(0 아님 — 바를 숨긴다).
- 안내용 — 최종 판정은 주문서 견적(`Quote.minOrderShortfall`). 표시 가격과 견적 가격이 다르면 숫자가 다를 수 있다.

## 6. 품절 처리 "다음에도 적용" — customer 0.4.0
- `GET /me/order-preferences` → `{outOfStockPreference: CANCEL_OOS_ONLY|CANCEL_ALL|null, version, updatedAt}`(저장 안 했으면 null·version 0).
- `PUT /me/order-preferences {outOfStockPreference}`(Idempotency-Key·X-Source-System, 선택 X-Expected-Version) — 주문서 "다음에도 적용" 체크 때 저장, 다음 주문서의 기본값으로 읽는다. order 는 이 값을 읽지 않는다(주문서 요청에 그대로 보낸다).

## 7. 배달 지연 제보 — case 0.23.0
- 이미 있던 고객 문의로: `POST /inquiries {category: DELIVERY, orderId, deliveryIssue: DELAYED, deliveryId?, …}`.
- 새로: 지연 제보는 주문이 확정 뒤 ~ 배송 완료 전(CONFIRMED·PREPARING·PARTIALLY_HANDED_OVER·IN_DELIVERY·PARTIALLY_DELIVERED)만 — 그 밖 422 `ORDER_NOT_IN_PROGRESS`(완료 뒤 못 받음은 `NOT_RECEIVED`). 앱: 배송 현황·진행 중 주문 화면에 "늦어요" 버튼으로 연결하면 좋다.

## 8. FAQ 목록·검색 — customer-assistant 0.2.0
- `GET /customer-assistant/faqs?q=&category=` → `{items:[{faqId, category, categoryTitle, question, answer}], categories:[{category, title, count}]}`, `GET /customer-assistant/faqs/{faqId}`. **회원 고객 토큰만**(비회원 403).
- 내용 = 고객 AI 상담이 쓰는 FAQ 원문(13개 문서, 같은 색인) — AI 답과 FAQ 화면이 어긋나지 않는다.
- **`answer` 는 마크다운**(목록·굵게·표·소제목) — 앱이 마크다운으로 그리거나 기호를 걷어 낸다.
- LAN 은 배포 뒤 FAQ 색인을 다시 해야 채워진다 — 그 전에는 422 `CONFIG_REQUIRED`.

## 회신
막히는 응답·틀린 줄은 `docs/handoff/clients/customer-app/` 회신 파일 또는 PR 본문으로.
