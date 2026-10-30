# ADR-0019 고객 소셜 로그인은 Google 먼저 — 앱이 받은 ID 토큰을 auth-service 가 검증한다

- 상태: 제안 (2026-10-30, 사용자 결정)
- 관련: `docs/architecture/implementation-plan.md` D6, ADR-0018(세션·토큰), Commerce 4.9(인증 상태·실패 제한), `contracts/openapi/auth-service.yaml`(logins)

## 배경
- 고객 소셜 로그인의 제공자·방식(D6)이 미정이었다. 사용자가 Google OAuth 클라이언트(앱용 installed·서버용 web)를 만들고 고객 앱부터 붙이기로 했다.
- 확정 정책(Commerce 4.9): 제공자 인증 성공과 PMart 회원 이용 가능은 다른 사실이다. 이메일만 같다고 계정을 합치지 않는다. 15분 내 5회 실패하면 10분 제한하고, 사용자 취소·제공자 장애는 세지 않는다.
- 신규 가입은 필수 약관 동의 확인 뒤 계정을 만든다는 정책이 있지만, 동의 정본(customer-service)이 아직 없고 필수 약관 목록도 정해지지 않았다.

## 결정
1. **방식**: 고객 앱이 Google Sign-In 으로 ID 토큰을 받아 `POST /auth/social-logins {provider: GOOGLE, providerToken: <ID 토큰>}` 으로 보낸다. auth-service 는 Google 공개키(JWKS)로 서명을 확인하고 `iss`(accounts.google.com), `aud`(허용 클라이언트 ID: 앱·웹), 만료를 본다. 인가 코드 교환을 하지 않으므로 **client secret 을 쓰지 않는다**.
2. **계정 연결**: 제공자 계정 식별자(`sub`)로 내부 고객 계정(`CU-…`)을 찾고, 없으면 만든다. 제공자 토큰·이메일은 저장하지 않는다.
3. **필수 동의 단계는 지금 생략한다**(사용자 결정, `pmart.auth.customer.require-consent=false`): 신규 가입도 바로 `AUTH_MEMBER`. 동의 기능(customer-service)이 생기면 켜서 `AUTH_CONSENT` → 동의 → 완료 흐름으로 돌린다. 확정 정책과 다른 임시 상태임을 명시한다.
4. **토큰**: ADR-0018 의 세션·refresh 회전을 그대로 쓴다. 고객 access token 은 `typ=CUSTOMER, sub=accountId, sid`, 30분. refresh·세션은 30일(보안 설정값).
5. **실패 제한**: 기기(`X-Device-Id`)별 15분 내 5회 명확 실패(`PROVIDER_REJECTED`) → 10분 `AUTH_RESTRICTED`(423, `retryAfterSeconds`·`restrictedUntil`). Google 공개키를 못 받는 등 제공자 장애(`PROVIDER_UNAVAILABLE`, 503)는 세지 않는다. 제한 중 시도는 기간을 늘리지 않는다.
6. **재전송·조회**: 같은 Idempotency-Key 재전송은 원인증 결과를 따른다(회원이면 새 세션 토큰, 실패면 같은 실패). `GET /auth/social-logins/{id}` 는 로그인을 시작한 기기만, 토큰은 주지 않는다.
7. **이벤트**: 회원이 되면 `CustomerAuthenticated`(토픽 `platform.auth.v1`).
8. **비밀정보**: Google 클라이언트 파일 원본은 `deploy/lan/.secrets/google-oauth/`(Git 제외). 배포에는 클라이언트 ID 만 넘긴다(`PMART_AUTH_CUSTOMER_GOOGLE_CLIENTIDS`).

## 대안
- **인가 코드 + 서버 교환(client secret 사용)**: 웹 로그인에는 맞지만 앱에서는 ID 토큰 검증으로 충분하고 비밀값을 서버에 둘 필요가 없다. 웹 고객 로그인이 생기면 다시 본다.
- **동의 단계 유지(AUTH_CONSENT 에서 멈춤)**: 정책에는 맞지만 고객 앱에서 신규 가입을 끝까지 시험할 수 없다. 사용자가 지금은 필요 없다고 했다.

## 결과
- 테이블 4개(`customer_account`, `social_identity`, `social_auth_request`, `social_auth_restriction`).
- `completions`(동의 완료)는 지금 `INVALID_STATE`(동의 단계가 없음).
- 카카오·네이버·애플은 같은 계약으로 제공자 검증기만 추가한다.
- customer-service 가 `CustomerAuthenticated` 를 구독해 고객 프로필을 만드는 일은 customer-service 구현 때 한다.
