# [pmart-backend → pmart-clients] 요청: 네이버 로그인 프로필 사진 넘기기 (2026-11-20)

- 보낸 영역: platform(PC 1)
- 받는 앱: customer-app (로그인 코드 — PC 1 에서 고친다)
- 근거: 사용자 2026-11-20 "네이버 프로필 넣어줘야지"
- 계약: auth-service **0.8.0** — 로그인 본문 세 곳에 선택 `profileImageUrl`
- 먼저: 네이버 로그인 자체가 끝나야 한다 — `2026-11-20-request-naver-login-task-affinity.md`(MainActivity `taskAffinity=""` 빼기)

## 왜 앱이 해야 하나
네이버 ID 토큰(OIDC)에는 프로필 사진이 없다. 사진은 네이버 회원 프로필 API(access token 필요)로만 온다. 서버는 제공자 access token 을 받지 않는다(ADR-0071) — 그래서 **앱이 받은 access token 으로 프로필을 읽어 사진 주소만 서버에 넘긴다.**
(구글은 ID 토큰에 사진이 있어 지금처럼 된다. 카카오도 ID 토큰 `picture` — 카카오 콘솔 동의 항목에 "프로필 사진"이 켜져 있어야 온다.)

## 앱에 필요한 것
1. 네이버 로그인(flutter_appauth `authorizeAndExchangeCode`) 결과의 `accessToken` 으로
   `GET https://openapi.naver.com/v1/nid/me`, 헤더 `Authorization: Bearer <accessToken>` → `response.profile_image`
   - 실패·없음이면 그냥 넘기지 않는다(로그인은 계속). access token 은 저장·로그하지 않는다(이 호출에만 쓰고 버림).
2. 그 주소를 `profileImageUrl` 로 함께 보낸다 — 세 곳 모두(회원 정보가 넘어가는 시점이 셋이라):
   - `POST /api/v1/auth/social-logins` `{provider: NAVER, providerToken: <id_token>, profileImageUrl}`
   - `POST /api/v1/auth/social-logins/{id}/completions` (약관 단계)
   - `POST /api/v1/auth/social-logins/{id}/identity-verifications/{vid}/confirmations` (휴대폰 확인 단계)
3. 서버 규칙: NAVER 이고 ID 토큰에 사진이 없을 때만, `https://…pstatic.net/…`(1000자 이하)만 쓴다. 아니면 조용히 무시 — 로그인 실패가 되지 않는다.
4. 사진은 customer-service 가 받아 사본을 두고 `GET /me` 의 `profileImageUrl`(`/api/v1/me/profile-image?v=…`)로 준다 — 화면은 지금처럼 그 주소만 쓴다(네이버 주소를 직접 띄우지 않음).

## 네이버 콘솔
- 애플리케이션 → API 설정 → 제공 정보에 **프로필 사진** 체크(필수 또는 추가). 없으면 `profile_image` 가 오지 않는다.
- (참고) 연결 끊기 Callback URL 은 로그인 Callback 과 다르게 — 지금은 비워 두기(서버에 받는 기능 없음, 네이버 서버는 LAN 주소에 못 닿음).

## 상태
- 2026-11-20: 서버 구현(auth 0.8.0, customer 받을 호스트 pstatic.net·kakaocdn.net). LAN 배포는 사용자 승인 뒤 — 배포되면 여기 적는다.
