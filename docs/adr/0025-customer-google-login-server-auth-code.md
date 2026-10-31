# ADR-0025 고객 Google 로그인은 하이브리드 — 앱은 서버 인가 코드를 받고, auth-service 가 OAuth 클라이언트로 교환한다

- 상태: 제안 (2026-10-31, 초안 — 사용자 검토 전)
- 대체: ADR-0019 결정 1(앱 ID 토큰 직접 전달)·8(백엔드는 client secret 을 쓰지 않음). ADR-0019 의 계정 연결·동의 생략·토큰·실패 제한·재전송·이벤트(결정 2~7)는 그대로 유지한다.
- 관련: ADR-0018(세션·토큰), ADR-0024(제공자 프로필 → customer-service), Commerce 4.9(인증 상태·실패 제한), `contracts/openapi/auth-service.yaml`(logins)

## 배경
- ADR-0019 는 고객 앱이 Google Sign-In 으로 받은 **OIDC ID Token** 을 `providerToken` 으로 보내고 auth-service 가 검증만 하게 했다. 고객 앱이 OAuth 클라이언트(공개 클라이언트)이고 auth-service 는 client secret 이 없다.
- 이 방식은 `nonce` 를 쓰지 않아, 탈취된 ID Token 을 만료(약 1시간) 전에 다른 기기에서 다시 보내면 막을 장치가 기기별 실패 제한뿐이다.
- 사용자 결정(2026-10-31): auth-service 도 OAuth 클라이언트로 동작해야 한다. 참고한 Eum authserver 는 `spring-boot-starter-oauth2-client` + `oauth2Login()`(브라우저 리다이렉트, 쿠키로 토큰 전달)인데, 이것은 웹 전용이라 고객 앱(네이티브 Google 로그인)에 그대로 맞지 않는다.
- Google 은 모바일 앱이 네이티브 로그인 화면을 유지한 채 **서버용 인가 코드(serverAuthCode)** 를 받아 백엔드에 넘기고, 백엔드가 client secret 으로 교환하는 "server-side access" 방식을 제공한다.

## 결정
1. **방식(하이브리드)**: 고객 앱은 네이티브 Google 로그인(`google_sign_in`)을 유지하되, ID Token 대신 서버 인가 코드를 받는다(`authorizeServer`, scope `openid email profile`, 대상 = 서버용 web 클라이언트 ID). 앱은 `POST /auth/social-logins {provider: GOOGLE, credentialType: AUTHORIZATION_CODE, providerToken: <serverAuthCode>}` 로 보낸다.
2. **auth-service 는 OAuth 클라이언트**: `spring-security-oauth2-client` 의 `ClientRegistration`(registrationId `google`, web 클라이언트 ID·secret, grant `authorization_code`)과 토큰 응답 클라이언트(`RestClientAuthorizationCodeTokenResponseClient`)로 Google 토큰 엔드포인트에서 코드를 교환한다. 코드가 브라우저 리다이렉트가 아니라 API 본문으로 오므로 **`oauth2Login()`·HTTP 세션은 쓰지 않는다** — auth-service 는 계속 stateless.
3. **ID Token 검증**: 교환 응답의 `id_token` 을 지금의 `GoogleIdTokenVerifier`(Google JWKS 서명, `iss`, `aud`, 만료)로 검증한다. 하이브리드 경로의 허용 `aud` 는 **서버 web 클라이언트 ID 하나**다. 그 뒤 계정 연결(`sub` → `CU-…`)·세션·`CustomerAuthenticated`(ADR-0024 프로필 포함)는 ADR-0019 와 같은 코드를 탄다.
4. **Google 토큰은 버린다**: 교환 응답의 Google access token·refresh token 은 저장하지 않고 로그도 남기지 않는다. 서버가 Google API 를 호출할 계획이 없으므로 오프라인 접근(refresh token)은 요청하지 않는다. 필요해지면 새 ADR 로 정한다.
5. **실패 분류**(Commerce 4.9 실패 제한과 연결):
   - `invalid_grant`(코드 만료·재사용·위조), ID Token 검증 실패 → `PROVIDER_REJECTED`, 실패 횟수에 넣는다.
   - Google 5xx·시간 초과·연결 실패 → `PROVIDER_UNAVAILABLE`(503), 세지 않는다.
   - `invalid_client`·`redirect_uri_mismatch` 등 우리 설정 오류 → 500 + 경보, 세지 않는다(고객 잘못이 아님).
   - 토큰 엔드포인트 호출 시간 제한은 설정값 `pmart.auth.customer.google.token-timeout`(기본 연결 2초·응답 5초).
6. **재전송**: 인가 코드는 한 번만 교환된다. 같은 Idempotency-Key 재전송은 **코드를 다시 교환하지 않고** 저장된 원인증 결과를 따른다(회원이면 새 세션 토큰 — ADR-0019 결정 6 그대로).
7. **비밀정보**: client secret 은 Secret Manager → env `PMART_AUTH_CUSTOMER_GOOGLE_CLIENTSECRET` 로만 받는다. Git·`application.yml`·이미지·로그에 두지 않는다. 없으면 하이브리드 경로를 끈 채 기동하고 경고를 남긴다(로컬은 시험용 토큰 엔드포인트). LAN 은 배포 PC 의 비밀 파일 → env(`deploy/lan/render.sh`). 인가 코드·Google 토큰·secret 은 로그 금지.
8. **계약**: `SocialLoginRequest` 에 `credentialType`(`AUTHORIZATION_CODE` | `ID_TOKEN`, 기본 `ID_TOKEN` — 기존 앱 호환)을 추가한다. 응답(`AuthResult`)은 바뀌지 않는다.
9. **전환 순서**:
   1. 백엔드가 두 방식을 모두 받는다(`pmart.auth.customer.google.accepted-credentials=ID_TOKEN,AUTHORIZATION_CODE`).
   2. 고객 앱이 하이브리드로 바꾼다(pmart-clients 요청).
   3. 앱 배포 확인 뒤 `ID_TOKEN` 을 설정에서 끈다 → 이후 ID Token 전달은 `PROVIDER_REJECTED` 가 아니라 `VALIDATION_FAILED`(400, 실패 횟수 제외).

## 대안
- **현행 유지 + `nonce`**: 재사용 공격은 막지만 auth-service 가 OAuth 클라이언트가 되지 않는다(사용자 요구와 다름).
- **서버 `oauth2Login()` + Custom Tab + 앱 딥링크(Eum 방식의 앱 판)**: 네이티브 로그인 화면을 잃고, 리다이렉트 사이 `state` 보관(세션)·딥링크 일회용 코드 교환 API 가 더 필요하다. 웹 고객 채널이 생기면 그 채널에만 이 방식을 따로 연다(같은 `sub` → `CU-…`).
- **Google refresh token 저장**: 지금은 쓸 곳이 없고 보관할 민감 정보만 는다.

## 결과
- 의존성: `spring-security-oauth2-client`(Spring Boot BOM 관리 버전)를 `gradle/libs.versions.toml` 에 등록하고 **auth-service 에만** 추가한다. `libraries/` 는 바꾸지 않는다(다른 서비스 영향 없음). 새 프레임워크가 아니라 이미 쓰는 Spring Security 의 모듈이다.
- 로그인 지연: 로그인마다 Google 토큰 엔드포인트 호출 1회가 는다. Google 장애 시 로그인 불가(실패 횟수 미가산, 503).
- 시험: `CustomerSocialLoginIT` 에 시험용 토큰 엔드포인트(시험 키로 서명한 `id_token` 반환)를 두고 성공·`invalid_grant`·5xx·시간 초과·재전송(재교환 안 함)을 확인한다.
- 문서: `services/auth-service/CLAUDE.md` 고객 소셜 로그인 절, `docs/adr/README.md` 의 ADR-0019 상태(일부 대체), 클라이언트 안내(handoff/collab).
- **확인 필요(구현 전)**:
  - Android `serverAuthCode` 교환 시 `redirect_uri` 값(빈 문자열로 알려져 있음 — Google 문서·실기기로 확인).
  - **추가 동의 화면 없음(필수 조건, 사용자 결정 2026-10-31)**: 계정 선택(`authenticate()`) 뒤 `authorizeServer` 단계에서 Google OAuth 동의 화면이 한 번 더 뜨면 안 된다. 그래서 scope 는 로그인 때 이미 공유되는 `openid email profile` 로만 한정하고 오프라인 접근(refresh token)은 요청하지 않는다(결정 4) — 이미 허용된 기본 scope 라 화면 없이 코드가 나올 것으로 예상하지만, 실기기(신규 계정·기존 계정)로 확인한다. 화면이 뜨면 이 ADR 은 승인하지 않고 방식을 다시 정한다.
  - `authorizeServer` 가 재로그인 때 null 을 주는지(`google_sign_in` 7.2.0 설명: 일부 플랫폼은 첫 로그인 때만 서버 인가 코드를 준다). null 이면 로그인할 수 없으므로 위 조건과 함께 확인한다.
- **실기기 확인 결과(2026-10-31, SM A536N · Android 16, 시험 플래그 디버그 빌드)**: Google 로그인 3회 모두 `serverAuthCode` 발급(길이 73), 예외 없음. 동의 화면은 계정 선택 흐름의 하나만 떴고 `authorizeServer` 단계의 추가 화면은 없었다(사용자 확인). 기존 ID 토큰 로그인도 3회 모두 200. 남은 확인: 실제 코드 교환(서버 구현·client secret 필요)과 그때의 `redirect_uri` 값.
- 대상 플랫폼은 Android 만이다(iOS 는 개발하지 않는다 — 사용자 결정 2026-10-31). iOS 클라이언트 등록·흐름은 두지 않는다.
