# 회신 — 쿠폰·할인가 반영 알림의 R1·Q1~Q3, 스위치, 5단계 추가분

- 보낸 영역: platform (PC 1)
- 답하는 것: pmart-clients `docs/handoff/customer-app/2026-11-21-backend-coupons-applied-and-open-questions.md`
- 작성: pmart-backend, 2026-11-21 — 사용자 "응"(회신 → BR-71 → 쿠폰 도착 알림 순서)

## 답
- **R1. BR-71 주문 줄 상품 사진** — 11-20 요청에 회신이 빠져 있었다(미안). 이 회신 바로 다음에 만든다: `OrderSummary.lines[]`·`OrderLine` 에 `imageUrl`(review `Eligibility.imageUrl` 과 같은 값·규칙 — 지금 상품의 대표 사진, 게시 안 된 상품 null). 같이 **주문상세 견적에 쿠폰 이름**(`quote.coupon.name`)도 넣는다 — 지금 "쿠폰 할인"으로 보이는 줄. 배포되면 이 파일에 적는다.
- **Q1. 찜 한도 `details`** — 맞다. 422 `WISHLIST_LIMIT_EXCEEDED` 의 details 는 한 줄 `{field: "limit", reason: "200"}`(reason 은 문자열 숫자).
- **Q2. BR-64 기존 계정 안내 `details`** — 맞다. `field` = 이름, `reason` = 값. 409 `IDENTITY_ALREADY_REGISTERED` 는 `provider`·`joinedAt`(ISO 시각)·`emailMasked`(있는 것만), 409 `IDENTITY_REJOIN_BLOCKED` 는 `rejoinAvailableAt`(ISO 시각).
- **Q3. 탈퇴 대기 중 주문** — 따로 알리는 code·detail 은 없다. 지금처럼 `ACCOUNT_NOT_PURCHASABLE` 일반 문구를 쓰면 된다.
- **스위치(LAN)** — `require-identity`(BR-64) **켜짐**, `unlink-after-exchange`(BR-74) **켜짐**(2026-11-20 사용자). 둘 다 켜진 채로 실기기 확인하면 된다.

## 요청서 8번 추가(5단계, 2026-11-21 LAN 배포 a0d2c884 — 앱이 회신을 쓴 뒤에 붙음)
`../2026-11-21-request-coupons-discounts.md` 8번 그대로. 요약:
- 가입 축하 쿠폰·배차 불가 보상 쿠폰(2,000원·30일·최소 주문 없음)은 **쿠폰함에 그대로** 나온다 — 화면 변경 없음. 보상 행사는 LAN 에서 아직 승인 대기(승인되면 대기분까지 나감), 가입 행사는 LAN 에 아직 없다.
- **개인정보 처리방침 새 판 `2026-11-21`**(쿠폰 항목·가입 쿠폰 지급 기록 탈퇴 후 보관) — 모든 고객이 다음 로그인 때 한 번 다시 동의한다(지금 동의 흐름 그대로).
- 쿠폰이 들어오면 알림("쿠폰이 도착했어요")도 곧 보낸다 — 만들면 이 파일에 적는다.
