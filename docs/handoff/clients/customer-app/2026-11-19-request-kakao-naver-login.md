# 요청 — 카카오·네이버 로그인 (구글과 같은 흐름, auth-service 0.4.0)

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 계기: 사용자 2026-11-19 "고객 구글 로그인 흐름처럼 카카오랑 네이버도 따라갔으면 좋겠어" — ADR-0071
- 상태: **LAN 배포됨(auth-service 0.4.0) + 키 설정 2026-11-19 22:3x**(pmart-config 0752d9f — 카카오 네이티브 앱 키·네이버 Client ID 를 고객 앱 `.secrets/customer.env` 에서 옮김, auth-service 재시작). 확인: 가짜 토큰이면 KAKAO·NAVER 401 PROVIDER_REJECTED(이제 실제 검증), 구글 그대로. 실기기 로그인 확인은 아직

## 서버 쪽(바뀐 것)
- `POST /auth/social-logins` 가 `provider: KAKAO`·`NAVER` 도 받는다. 본문·응답·동의 단계(completions)·토큰은 **구글과 똑같다** — `providerToken` 에 **제공자 OpenID Connect ID 토큰**(JWT)을 넣는다.
- access token·인가 코드는 받지 않는다. 서버에 client secret 도 없다.
- 그 제공자 설정(허용 앱 키·Client ID)이 아직 없으면 422 CONFIG_REQUIRED — 앱은 그 버튼을 "준비 중"으로.
- 계정은 제공자마다 따로 생긴다(같은 사람이 구글·카카오로 각각 가입하면 계정 2개 — 이메일로 합치지 않음).

## 앱이 할 것
- **카카오**: 카카오 SDK(콘솔에서 OpenID Connect 켬)로 로그인 → 결과 토큰의 `idToken` 을 보낸다(access token 아님). SDK 초기화 앱 키 = 서버 허용 aud(사용자가 백엔드에도 알려 줌).
- **네이버**: OpenID Connect(인가 코드 + PKCE, scope `openid`, client secret 없이 — 토큰 엔드포인트 인증 none)로 받은 `id_token` 을 보낸다. 네이버 SDK 가 ID 토큰을 주지 않으면 AppAuth 같은 OIDC 라이브러리(앱 결정). issuer `https://nid.naver.com`.
- 오류 처리는 구글과 같다(401 PROVIDER_REJECTED, 423 AUTH_RESTRICTED, 503 PROVIDER_UNAVAILABLE).
