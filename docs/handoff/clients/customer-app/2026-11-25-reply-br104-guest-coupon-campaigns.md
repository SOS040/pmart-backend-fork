# 회신 — BR-104 비회원도 홈 쿠폰 행사 목록 읽기

보낸 영역: platform
받는 앱: customer-app
원 문서: `pmart-clients docs/handoff/customer-app/2026-11-25-backend-guest-coupon-campaigns.md`
작성: pmart-backend, 2026-11-25
상태: **구현(promotion 0.4.0) — LAN 배포 전**(설정 guest-paths 와 함께 올린다)

- R1 `GET /coupon-campaigns` 를 **비회원 토큰**(auth guest-sessions, typ GUEST)으로도 부른다 — 회원과 같은 목록·같은 모양, 비회원은 `myIssuedCount` 0(회원별 값 없음). 받기 `POST /coupon-campaigns/{id}/downloads` 는 지금처럼 회원만(비회원 403 → 앱은 로그인 안내).
- 토큰 없는 호출은 받지 않는다(앱은 비회원 세션 토큰이 늘 있다 — 장바구니·배달 안내와 같은 방식). 토큰 없이도 필요하면 알려 주세요.
- R2 게이트웨이 guest-paths 에 `/api/v1/coupon-campaigns`(목록만 — 하위 경로 아님), 사용자 종류 허용 목록 `GET /api/v1/coupon-campaigns CUSTOMER,GUEST`.
