# ADR-0022: 비회원 세션 토큰(typ=GUEST)과 guest-paths, 장바구니 병합 소유 증명(X-Guest-Token)

- 상태: 제안
- 날짜: 2026-10-30
- 관련: ADR-0011(서비스 신원), ADR-0018·0019(로그인), 클라이언트 회신 `docs/handoff/clients/2026-10-30-clients-reply-customer-app-priority.md` 2-2 (a)(사용자 승인)

## 배경
- Commerce: 비회원도 탐색·장바구니를 쓰고 주문서 진행 전 로그인한다. 로그인 후 고객이 확인한 항목만 한 번 이관한다(자동 병합 금지).
- cart 계약은 비회원을 `X-Device-Id` 헤더로 식별하게 돼 있었다. 기기 ID 는 클라이언트가 정하는 값이라 소유 증명이 약하다.
- 사용자 결정(2026-10-30): 비회원 장바구니는 auth 가 발급하는 **비회원 토큰**으로 식별한다.

## 결정
1. auth-service `POST /auth/guest-sessions`(공개, `X-Device-Id` 필수): 새 비회원 주체 `GST-…` 와 토큰 쌍(`typ=GUEST`, 세션은 그 기기에 묶임). 갱신은 `POST /auth/tokens`(같은 기기), 종료는 `/auth/logouts`. 수명: access 1시간, refresh·세션 30일(장바구니 보존과 같게). 개인정보 없음.
2. **GUEST 토큰은 기본 거절**: common-security `pmart.security.guest-paths`(기본 빈 목록) 밖의 모든 인증 경로에서 403. 게이트웨이도 `pmart.gateway.guest-paths` 로 같은 규칙(재배포 전 서비스까지 보호). 지금 연 곳: cart `/api/v1/carts/**`, auth 자기 세션 조회·종료.
3. cart 는 토큰 주체로 주인을 정한다: 고객 토큰 → `MEMBER(accountId)`, 비회원 토큰 → `GUEST(GST-…)`. `X-Device-Id` 는 기록용.
4. 병합(`POST /carts/{회원 cartId}/merges`)은 회원 토큰으로 부르고, 원본 비회원 장바구니 소유는 **`X-Guest-Token`(비회원 access token)** 을 같은 JWKS 로 검증해 증명한다(계약의 X-Device-Id 를 대체). 토큰 원문은 로그에 남기지 않는다.
5. cart → product 내부 API(SKU·구매 상한)는 서비스 신원 토큰(ADR-0011)으로 부른다. LAN 은 `init-secrets.sh` 가 `PMART_SERVICE_CLIENT_SECRET` 을 받는 서비스마다 secret·bcrypt 해시를 만들고, `render.sh` 가 그 서비스와 auth 등록부(`PMART_AUTH_SERVICECLIENTS_n_*`)에 넣는다.

## 대안
- 기기 ID 공개 장바구니(계약 원안): 토큰 없이 단순하지만 기기 ID 만 알면 남의 장바구니를 읽고 바꿀 수 있다.
- 비회원 장바구니를 앱에만 두기: Commerce 의 비회원 장바구니 보존·병합 재검증과 맞지 않는다.
- GUEST 를 각 서비스가 알아서 거절: 빠뜨린 서비스가 회원 API 를 비회원에게 열게 된다 → 공통 라이브러리에서 기본 거절로.

## 결과
- common-security 를 쓰는 모든 서비스(32개)가 새 규칙을 받는다. 비회원이 아닌 토큰에는 동작이 같다. 재배포 전 서비스는 게이트웨이 규칙이 막는다.
- 비회원이 토큰을 잃으면 새 세션 = 새 빈 장바구니(이전 비회원 장바구니는 보존 기간 뒤 만료).
- 병합은 같은 DB 한 트랜잭션이라 `MERGE_PENDING` 이 생기지 않는다(계약 상태는 유지).
