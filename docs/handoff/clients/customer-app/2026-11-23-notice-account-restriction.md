# 알림 — 고객 계정 이용제한·정지(CU-7)

보낸 영역: platform
받는 앱: customer-app
작성: pmart-backend, 2026-11-23 — CU-7 사용자 결정(이용제한 = 문의·리뷰 작성, 정지 = 주문·로그인까지, 7일 자동 해제, 영구 없음)
상태: 코드·시험 끝, LAN 배포는 사용자 "배포" 뒤. 앱이 바꿔야 하는 것은 아래 안내 문구 처리뿐(새 화면 요청 없음)

- 로그인·토큰 갱신: 정지 중이면 403 `ACCOUNT_SUSPENDED`(details `until` — UTC 시각, message 에 "M월 d일 HH:mm까지"). 정지가 걸리면 그 전에 받은 토큰은 바로 막힌다(401) — 로그인 화면으로 보내고 이 오류를 보여 주면 된다.
- 주문: 정지면 기존처럼 422 `ACCOUNT_NOT_PURCHASABLE`.
- 문의 작성 `POST /inquiries`·리뷰 작성 `POST /reviews`: 이용제한·정지 중이면 403 `ACCOUNT_RESTRICTED`(details until).
- `GET /me` 에 `restriction`(NONE·RESTRICTED·SUSPENDED)·`restrictedUntil` — 이용제한이면 "~까지 문의·리뷰를 쓸 수 없어요" 안내에 쓸 수 있다.
- 알림: 알림함 + 푸시 `CUSTOMER_SUSPENDED` "7일 계정 정지", `CUSTOMER_RESTRICTED`, 풀리면 `CUSTOMER_RESTRICTION_LIFTED`(분류 SYSTEM, 목적지 NONE). 기한이 지나 저절로 풀릴 때는 알림 없음.
