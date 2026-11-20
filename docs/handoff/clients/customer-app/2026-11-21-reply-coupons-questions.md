# 회신 — 쿠폰·할인가 반영 알림의 R1·Q1~Q3, 스위치, 5단계 추가분

- 보낸 영역: platform (PC 1)
- 답하는 것: pmart-clients `docs/handoff/customer-app/2026-11-21-backend-coupons-applied-and-open-questions.md`
- 작성: pmart-backend, 2026-11-21 — 사용자 "응"(회신 → BR-71 → 쿠폰 도착 알림 순서)

## 답
- **R1. BR-71 주문 줄 상품 사진 — 반영(order 0.19.0, LAN 배포 2026-11-21)**. 11-20 요청에 회신이 빠져 있었다(미안). `GET /orders` `lines[].imageUrl`·`GET /orders/{id}` `lines[].imageUrl` = 지금 상품의 대표 사진(product-content 공개 카드 `mainImage.url` — review `Eligibility.imageUrl` 과 같은 값). 게시 안 된 상품·조회 실패는 null(주문 조회는 그대로). 앱은 주문상세의 줄마다 상품 조회를 걷어도 된다. 같이 **주문상세 `quote.coupon`** = `{couponId, applicable: true, name}`(쿠폰을 썼을 때만, 안 썼으면 null) — "쿠폰 할인" 대신 쿠폰 이름을 쓸 수 있다.
- **Q1. 찜 한도 `details`** — 맞다. 422 `WISHLIST_LIMIT_EXCEEDED` 의 details 는 한 줄 `{field: "limit", reason: "200"}`(reason 은 문자열 숫자).
- **Q2. BR-64 기존 계정 안내 `details`** — 맞다. `field` = 이름, `reason` = 값. 409 `IDENTITY_ALREADY_REGISTERED` 는 `provider`·`joinedAt`(ISO 시각)·`emailMasked`(있는 것만), 409 `IDENTITY_REJOIN_BLOCKED` 는 `rejoinAvailableAt`(ISO 시각).
- **Q3. 탈퇴 대기 중 주문** — 따로 알리는 code·detail 은 없다. 지금처럼 `ACCOUNT_NOT_PURCHASABLE` 일반 문구를 쓰면 된다.
- **스위치(LAN)** — `require-identity`(BR-64) **켜짐**, `unlink-after-exchange`(BR-74) **켜짐**(2026-11-20 사용자). 둘 다 켜진 채로 실기기 확인하면 된다.

## 요청서 8번 추가(5단계, 2026-11-21 LAN 배포 a0d2c884 — 앱이 회신을 쓴 뒤에 붙음)
`../2026-11-21-request-coupons-discounts.md` 8번 그대로. 요약:
- 가입 축하 쿠폰·배차 불가 보상 쿠폰(2,000원·30일·최소 주문 없음)은 **쿠폰함에 그대로** 나온다 — 화면 변경 없음. 보상 행사는 LAN 에서 아직 승인 대기(승인되면 대기분까지 나감), 가입 행사는 LAN 에 아직 없다.
- **개인정보 처리방침 새 판 `2026-11-21`**(쿠폰 항목·가입 쿠폰 지급 기록 탈퇴 후 보관) — 모든 고객이 다음 로그인 때 한 번 다시 동의한다(지금 동의 흐름 그대로).
- **쿠폰 도착 알림(반영, notification 2026-11-21)**: 서버가 준 쿠폰마다 알림함·푸시 한 건 — 배차 불가 보상 "보상 쿠폰이 도착했어요"(분류 ORDER, 목적지 ORDER = 취소된 주문 ID), 가입 축하 "가입 축하 쿠폰이 도착했어요"·직원 지정 "쿠폰이 도착했어요"(분류 SYSTEM, 목적지 NONE — 계약에 쿠폰함 목적지가 없어서. 앱이 templateKey `COUPON_SIGNUP`·`COUPON_ISSUED`·`COUPON_COMPENSATION` 로 쿠폰함을 열고 싶으면 알려 달라). 본문 끝에 "M월 D일까지 쓸 수 있어요."
