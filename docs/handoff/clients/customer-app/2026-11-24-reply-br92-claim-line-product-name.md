# 회신 — BR-92 취소·반품 품목에 상품 이름·옵션·사진 사본(`ClaimLine`)

보낸 영역: platform
받는 앱: customer-app
원 문서: `pmart-clients docs/handoff/customer-app/2026-11-24-backend-claim-line-product-name.md`(R1·Q1)
작성: pmart-backend, 2026-11-24 — 사용자 "BR-92 해줘. 배포까지"
상태: 구현·시험 끝, LAN 배포는 아래 §3

## 1. R1 — claim 0.14.0 `ClaimLine` 상품 사본
- 새 칸(모두 nullable): `productName`·`optionLabel`·`imageUrl`. `GET /claims/{claimId}`·`GET /claims` 의 `lines[]` 에 들어 있다(같은 `ClaimLine`).
- 값: **접수할 때** order 내부 원장의 같은 주문 줄에서 복사해 클레임 품목에 저장한다.
  - `productName`·`optionLabel` — 주문 줄 사본 그대로(`OrderLine` 과 같은 값·규칙). 단일 옵션 상품이면 `optionLabel` 이 null 일 수 있다.
  - `imageUrl` — 접수 때의 대표 사진(BR-71 주문 줄 `imageUrl` 과 같은 값). 그 뒤 상품 사진이 바뀌어도 클레임 값은 그대로다.
- null 인 경우: 이름 사본이 없는 옛 주문(2026-11-02 이전), **이 판 배포 전에 접수된 클레임**(이미 있는 행은 채우지 않았다), 사진 조회 실패·게시 안 된 상품. 서버는 SKU 로 채우지 않는다.
- 앱은 요청서 그대로 `ClaimLine` 값을 먼저 쓰고, null 일 때만 주문 상세에서 붙이면 된다.
- 접수 종류: 고객 취소·상담원 취소·시스템 취소·결품 부분 환불·반품 모두 같다.

## 2. Q1 — 숨긴 주문도 `GET /orders/{orderId}` 가 열린다
- 숨김(`POST /orders/{id}/hides`)은 `GET /orders` 목록에서만 뺀다. 고객 상세(`GET /orders/{orderId}`)·내부·직원 조회는 그대로 열린다(order 0.12.0 부터 같은 규칙).
- 그래서 숨긴 주문의 클레임도 지금 방식(주문 상세에서 붙이기)으로 이름을 찾을 수 있고, R1 이 오면 주문을 읽지 않아도 된다.

## 3. 백엔드 변경
- order 0.21.0: 내부 `GET /internal/orders/{orderId}` `lines[]` 에 `optionLabel`, 선택 `includeImages=true` 일 때만 `imageUrl`(product-content 조회를 한 번 더 하므로 기본은 끈다 — claim 접수만 켠다). 고객 API 는 바뀌지 않음.
- claim 0.14.0: `ClaimLine` 세 칸, `claim_line` 열 세 개(V8).
- 시험: `:services:claim-service:check`·`:services:order-service:check`.
- 배포: order-service·claim-service(PC 2).
