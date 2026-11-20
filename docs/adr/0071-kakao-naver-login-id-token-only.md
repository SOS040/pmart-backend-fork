# ADR-0071 카카오·네이버 소셜 로그인도 ID 토큰 검증만 — 구글과 같은 흐름

- 상태: 승인 (2026-11-19, 사용자 "고객 구글 로그인 흐름처럼 카카오랑 네이버도 따라갔으면 좋겠어")
- 관련: ADR-0055(Google 은 ID 토큰 검증만 — 결정 2·3 을 카카오·네이버로 넓힌다), ADR-0019(고객 Google 로그인), ADR-0054(고객 auth-service·라이더 rider-auth-service), 계약 `auth-service.yaml` 0.4.0

## 배경
- 계약 `Provider` 에는 처음부터 KAKAO·NAVER·APPLE·GOOGLE 이 있었지만 코드는 GOOGLE 만 받았다(그 밖 501).
- 두 제공자 모두 OpenID Connect ID 토큰을 준다 — 2026-11-19 각 제공자의 `.well-known/openid-configuration` 을 직접 받아 확인:
  - 카카오: issuer `https://kauth.kakao.com`, JWKS `https://kauth.kakao.com/.well-known/jwks.json`, RS256. 콘솔에서 OpenID Connect 를 켜야 ID 토큰이 나온다. `aud` = 앱 키(카카오 SDK 를 초기화한 키), `sub` = 앱별 회원번호.
  - 네이버: issuer `https://nid.naver.com`, JWKS `https://nid.naver.com/oauth2/jwks`, RS256, scope `openid`, 인가 코드 + PKCE(S256), 토큰 엔드포인트 인증 `none`(공개 클라이언트 — 앱이 client secret 없이 ID 토큰을 받는다), `sub` pairwise(앱별). `aud` = 애플리케이션 Client ID.

## 결정
1. **흐름은 구글과 같다**: 앱이 제공자 SDK·OIDC 로 로그인 → ID 토큰 → `POST /auth/social-logins {provider: KAKAO|NAVER, providerToken: <ID 토큰>}` → auth-service 가 제공자 공개키로 서명·`iss`·`aud`·`exp` 검증 → `(provider, sub)` 로 계정 → 필수 약관 → PMart 자체 토큰.
2. **쓰지 않는 것(ADR-0055 결정 3 그대로)**: 제공자 access token 으로 사용자 정보 조회, 인가 코드 교환, client secret(백엔드·설정·비밀 폴더 어디에도 두지 않는다), Spring OAuth2 Client. 네이버의 옛 방식(access token + `openapi.naver.com/v1/nid/me`)은 쓰지 않는다.
3. **허용 aud**: `pmart.auth.customer.kakao.client-ids`(앱 키)·`pmart.auth.customer.naver.client-ids`(Client ID). 비면 그 제공자만 422 CONFIG_REQUIRED(구글은 그대로). 발급자·공개키 주소는 제공자 고정값을 기본으로(설정으로 바꿀 수 있음).
4. **계정**: `(제공자, 제공자 sub)` 로 따로. 같은 이메일·같은 사람이어도 합치지 않는다(지금 규칙) — 합치기는 별도 "계정 연결" 기능으로 정할 일.
5. **nonce 는 쓰지 않는다**(ADR-0055 와 같음 — 재전송 위험은 기기별 실패 제한·짧은 수명에 기댄다).
6. **APPLE** 은 그대로 501.
7. **라이더**(rider-auth-service)는 같은 방식으로 이어서 — 같은 검증기 구조를 쓴다.

## 결과
- auth-service 0.4.0: `GoogleIdTokenVerifier` → `IdTokenVerifier`(제공자 공통), `IdTokenVerifiers`(제공자별 고르기). 시험 `CustomerSocialLoginIT.kakaoAndNaverFollowTheGoogleFlow`·`IdTokenVerifiersTest`.
- 고객 앱: 카카오 SDK 로그인 결과의 `idToken`(콘솔 OIDC 켬), 네이버는 OIDC(인가 코드 + PKCE)로 받은 `id_token` 을 보낸다 — 네이버 SDK 가 OIDC ID 토큰을 주지 않으면 AppAuth 같은 OIDC 라이브러리(앱 쪽 결정).
- 사용자 할 일: 카카오 콘솔 카카오 로그인·OpenID Connect 켜기·Android 키 해시·앱 키 알려 주기, 네이버 애플리케이션 등록·Client ID 알려 주기(secret 불필요).

## 덧붙임 (2026-11-20) — 네이버 프로필 사진
- 사용자 "네이버 프로필 넣어줘야지". 네이버 ID 토큰에는 프로필 클레임(사진)이 없다. 결정 2(서버는 access token 을 쓰지 않는다)는 그대로 두고, **앱이 자기 access token 으로 네이버 회원 프로필 API(`openapi.naver.com/v1/nid/me`)를 읽어 사진 주소만** 로그인 본문 `profileImageUrl` 로 넘긴다(auth 0.8.0 — social-logins·completions·identity confirmations).
- 서버는 NAVER 이고 ID 토큰에 사진이 없을 때만, https·`*.pstatic.net`·1000자 이하일 때만 CustomerAuthenticated `pictureUrl` 로 싣는다(`ProviderPictureRules`) — 아니면 무시(로그인은 그대로). 앱이 넘긴 값이라 검증된 신원 정보가 아니다 — 본인 프로필 사진 표시에만 쓰고, customer-service 가 같은 호스트 목록(pstatic.net·kakaocdn.net 추가)·리다이렉트 안 따름·1MB 로 받아 사본을 둔다.
- 카카오는 ID 토큰 `picture`(동의 때) 그대로 — 받을 호스트에 kakaocdn.net 만 더함.
