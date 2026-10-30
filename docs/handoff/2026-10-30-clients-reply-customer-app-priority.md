# [pmart-backend → pmart-clients] 고객앱 우선 범위 회신 (2026-10-30)

- 원 요청: `pmart-clients/docs/handoff/2026-10-30-backend-customer-app-priority.md`
- 기준: pmart-backend `main` 308e773 이후

## 1. 결정 (사용자 승인, 2026-10-30)
| 항목 | 결정 | 근거 |
|---|---|---|
| 진행 순서 | **클라이언트 제안 그대로**: ① 주소·쇼핑 주소 → ② 비회원 둘러보기 공개 경로 + 시험 데이터 → ③ 홈 1섹션·상품 검색 → ④ 장바구니 → ⑤ 배송 조건·슬롯 → 주문서 → ⑥ 결제 | 요청 §4 |
| 비회원 둘러보기(BR-28) | **공개로 연다**: `GET /categories/tree`, `GET /categories/{id}/filters`, `GET /product-contents/{productId}`, `GET /product-contents`, `GET /content-images/{imageId}`, `GET /prices/{sku}`, `GET /prices`. 계약 `security: []` + 게이트웨이 public-paths 로 반영한다(②에서) | Commerce "비회원은 상품 조회와 장바구니를 사용할 수 있다" |
| 비회원 장바구니(2-2) | **(a) 게스트 토큰**: auth-service 가 비회원 세션 토큰(`typ=GUEST`, 기기별)을 발급하고, cart 는 그 토큰의 주체로 장바구니를 식별한다. 로그인하면 병합 흐름으로 옮긴다. 계약은 ④에서 정한다 | Commerce "비회원 장바구니 보존·로그인 병합" — (c) 는 정책과 맞지 않고, (b) 기기 ID 공개 장바구니는 소유자 확인이 약하다 |
| 시험 데이터(2-3) | 넣는다. 카테고리 트리 1개, 상품 몇 개, 가격, MFC 1곳 취급 ON — **승인 경로(API)로만** 적재하는 seed 스크립트로(ADR-0014, DB 직접 입력 없음) | 요청 §2-3 |

## 2. 결제(BR-01) — 해결됨
- 고객용 결제 계약을 추가하고 구현·LAN 배포했다(ADR-0020): `POST /payment-confirmations {orderId, paymentKey, amount}`, `POST /payment-authorization-failures`, `GET /payments/{orderId}`.
- 안내: `pmart-backend/docs/handoff/2026-10-30-clients-notice-customer-payment.md`.
- 결제 진입 정보(orderId·amount·orderName·clientKey)는 주문 초안 `nextAction=PAY` 로 내려간다(D4). 주문서(⑤)가 생겨야 앱에서 결제까지 이어진다.

## 3. 항목별 메모
- **2-1 주소**: customer-service 에 `CustomerAuthenticated` 구독 → 고객 프로필 생성, `/me/addresses`(목록·추가), `/me/shopping-address-selections`, `/me/shopping-context`, 비회원 `/guest-delivery-addresses` 를 만든다. 배송 가능 판정은 delivery-serviceability(권역)·center-master(MFC) 최소 구현과 같이 한다.
- **2-4 홈·검색**: storefront 홈 1섹션과 `GET /search/products?q=` 를 상품·콘텐츠·가격 사건 투영(`ProductVersionPublished`·`ProductContentPublished`·`PriceVersionEffective`)으로 채운다. 색인은 별도 검색엔진 없이 시작할지 ③ 착수 때 알린다.
- **2-5 주문서**: BR-13·14·21 은 ⑤ 착수 때 계약과 함께 회신한다.
- 각 단계가 LAN 에 배포되면 이 폴더에 짧게 알린다.
