# auth-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `auth-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/auth-service.yaml` → `openApiGenerate` 가 `com.pmart.auth.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.auth.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:auth-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:auth-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8501.

## 서명키·JWKS (ADR-0011, 서비스 토큰은 ADR-0026 으로 폐지)
- `GET /api/v1/internal/jwks`(공개키, permit-path, 게이트웨이 비노출) — 모든 서비스·게이트웨이가 사용자 토큰을 이 공개키로 검증한다.
- 서명키 `pmart.auth.signing.keys` (Secret Manager, PKCS#8 PEM, 첫 키가 활성). 없으면 기동 실패. local/test 만 `ephemeral=true`.
- 서비스 토큰 발급(`/internal/service-tokens`)·서비스 클라이언트 등록부는 없다(서비스 간 호출은 인증하지 않음, ADR-0026). 토큰 로그 금지.
- 자기 토큰 검증은 `KeyConfig` 의 JwtDecoder(같은 키 묶음) — `jwk-set-uri` 없음.
- access token `aud` = `pmart.auth.audience`(기본 `pmart-api`, ADR-0055 결정 6) — 발급자 표 auth-service 줄 `audiences` 와 같아야 한다. 시험 토큰을 직접 만들 때도 넣는다.

## 운영자 로그인 — 없앰 (2026-11-13, ADR-0056 7단계)
- 운영자(직원) 로그인·계정 관리(`/auth/operator-logins`·`/auth/password-changes`·`/operator-accounts/**`)·첫 관리자·개발 계정(`claude-dev`)·Keycloak 직원 차단 중계를 없앴다. 직원 토큰은 employee-auth-service 만 낸다(발급자 표 auth-service 줄 user-types 에서 OPERATOR 뺌).
- 세션·refresh 저장은 `SessionStore`(전 `OperatorStore`, 고객·비회원만). DB 표 `operator_account`·`staff_admin_event_seen` 은 남아 있고 쓰지 않는다(지울 때는 행 수를 먼저 센다).
- 고객 세션은 마지막 사용 기준 연장(2026-11-02): 갱신마다 세션 만료 = 지금 + `pmart.auth.customer.session-max-age`(30일, `SessionStore.extendSession`).
- refresh token 은 SHA-256 만 저장, 한 번만 쓴다. 재사용이면 세션 폐기(`TOKEN_REUSED`).

## 고객 소셜 로그인 (ADR-0019·0055)
- **GOOGLE·KAKAO·NAVER(2026-11-19 ADR-0071, 계약 0.4.0 — 카카오·네이버도 같은 흐름)**: `IdTokenVerifiers.require(provider)`(목록 밖 APPLE 501, 그 제공자 client-ids 비면 422 CONFIG_REQUIRED) → `IdTokenVerifier`(전 `GoogleIdTokenVerifier` — 제공자 JWKS·iss·aud·만료). 카카오 `pmart.auth.customer.kakao.client-ids`(앱 키, iss `https://kauth.kakao.com`), 네이버 `…naver.client-ids`(Client ID, iss `https://nid.naver.com`) — 공개키·발급자는 기본값. 계정은 (제공자, sub)로 따로. 카카오 프로필 이름은 `nickname`, 네이버 ID 토큰에는 프로필 없음. 시험 `CustomerSocialLoginIT.kakaoAndNaverFollowTheGoogleFlow`·`IdTokenVerifiersTest`, 시험 빈은 `IdTokenVerifiers` 를 시험 키로 바꾼다.
- (구글) 앱이 보낸 ID 토큰을 `IdTokenVerifier`(Google JWKS, iss·aud·만료)로 검증 — client secret·인가 코드 교환·Spring OAuth2 Client 는 쓰지 않는다(ADR-0055, ADR-0025 기각). 허용 aud 는 `pmart.auth.customer.google.client-ids` = **고객용 웹 클라이언트 ID 하나**(고객 앱 serverClientId). Android 클라이언트 ID·라이더 웹 ID 는 넣지 않는다. 환경별 값은 pmart-config `auth-service-<프로필>.yml`(ADR-0029)(원본 클라이언트 파일은 백엔드에 두지 않음).
- **네이버 콜백 중계(2026-11-19, 계약 0.5.0 — 고객 앱 BR-66, 사용자 "라이더 쪽 콜백중계도 해줘")**: `GET /auth/oauth-callbacks/naver/customer`·`…/rider`(`OAuthCallbacksController`, 규칙 `domain/OAuthCallbackRules`) — 받은 쿼리 원문을 그대로 고정 앱 주소(`com.pmart.customer://oauth/naver`·`com.pmart.rider://oauth/naver`)로 302·`Cache-Control: no-store`, 4096자 넘으면 400. 코드를 읽거나 교환·저장·로그하지 않는다(교환은 앱이 PKCE). permit-paths·게이트웨이 public-paths. 네이버 콘솔 Callback URL = `http://192.168.0.40:8080/api/v1/auth/oauth-callbacks/naver/{customer|rider}`(LAN 와이파이에서만). 라이더 것도 여기(rider-auth-service 는 손대지 않음 — 고정 302 뿐이라). 시험 `CustomerSocialLoginIT.naverCallbackRelaysQueryToFixedAppAddress`.
- **네이버 인가 코드 서버 교환(2026-11-20 ADR-0078, 계약 0.10.0 — BR-69)**: `POST /auth/naver-token-exchanges`(`NaverCodeExchangeService`·`infrastructure/NaverTokenClient`) — 네이버는 secret 없는 교환을 거절(client_secret_post)해 서버가 `{token-base-url}/oauth2/token` 에 form(client_id = `naver.client-ids` **첫 값**, `naver.client-secret`, code·code_verifier·redirect_uri·state)으로 교환하고 **id_token 만** 준다(access/refresh 버림). 공용 RestClient.Builder 를 쓰지 않는 전용 클라이언트(연결 2초·응답 `naver.token-timeout` 기본 5초). `redirectUri` ≠ `naver.callback-url` 400, client-ids·secret·callback 없음 422 CONFIG_REQUIRED, 네이버 4xx·200 `error`·id_token 없음 401 PROVIDER_REJECTED(로그는 오류 코드 문자열만), 시간 초과·연결·5xx 503 PROVIDER_UNAVAILABLE(실패 횟수 미가산). `RequestIdempotency`(operation exchangeNaverCode, anonymous, 본문 해시 = code·verifier·redirectUri) — 같은 키 재전송은 저장된 id_token(네이버 다시 안 부름). permit-path·게이트웨이 public-path. secret 은 pmart-config `auth-service-lan.yml` `{cipher}`. 시험 `NaverCodeExchangeIT`(WireMock)·`NaverCodeExchangeServiceTest`.
- **네이버 코드 교환 + 프로필 사진(2026-11-20 BR-70, 계약 0.10.1 — 사용자 "추천대로")**: 교환 성공 뒤 토큰 응답의 access token 으로 `{naver.profile-base-url 기본 https://openapi.naver.com}/v1/nid/me` 를 **한 번** 불러(`NaverTokenClient.profileImage`, 응답 대기 min(token-timeout, 3초)) `response.profile_image` 를 `ProviderPictureRules.naver`(https·`*.pstatic.net`)로 걸러 선택 `profileImageUrl` 로 준다. 프로필 호출의 어떤 실패(시간 초과·4xx·`resultcode`≠00·필드 없음·다른 호스트)도 교환 실패가 아니다 — 빠질 뿐, 로그는 `reason=` 짧은 코드만. access/refresh token 은 저장·응답하지 않고, 멱등 기록(`Exchanged`)은 id_token·사진 주소만 — 재전송은 같은 사진. rider-auth-service 는 바꾸지 않음. 시험 `NaverCodeExchangeIT.profileFailureOrForeignHostOmitsPictureButExchangeSucceeds`·`NaverCodeExchangeServiceTest.profileImageOnlyPstaticHttpsAndNeverFails`.
- **네이버 이메일(2026-11-21 BR-77, 계약 변경 없음 — 사용자 "BR-77 지금 해줘")**: 네이버 ID 토큰에는 이메일이 없다. 교환 때 같은 프로필 API 응답의 `response.email`(`NaverTokenClient.profile`)을 id_token `sub` 에 10분 묶어 둔다(`NaverProfileEmailStore`, V7 `naver_profile_email` — 키 SHA-256("NAVER:"+sub), 응답·로그에 싣지 않음). 그 sub 의 **검증된** id_token 으로 로그인하면 `publishAuthenticated` 가 한 번 꺼내(`take` — 읽고 지움) CustomerAuthenticated `email` 로(네이버는 확인 여부를 주지 않아 `emailVerified` 없음). 앱이 보낸 이메일은 받지 않는다. 없거나 프로필 실패·만료면 이메일 없이 — customer-service 는 사건에 없는 값은 기존 값을 유지(지우지 않음). 만료 행은 `NaverProfileEmailPurger`(기본 PT10M). 네이버 콘솔 "제공 정보 — 이메일 주소"를 켜야 값이 온다. 시험 `NaverCodeExchangeIT.profileEmailIsHeldForTheIdTokenSubjectNotReturnedToApp`·`NaverCodeExchangeServiceTest.subjectOfReadsIdTokenSubWithoutTrustingAnythingElse`.
- 라이더 로그인은 이 서비스가 아니라 rider-auth-service(ADR-0054). 발급자 표의 auth-service 줄 RIDER 는 LAN 시험 라이더 토큰용으로 rider-auth-service 배치 전까지만 둔다.
- 제공자 `sub` → 내부 계정 `CU-…`(이메일 병합 없음).
- **필수 약관 동의 단계(2026-11-17, CU-1 — 계약 0.2.0, 스위치 `pmart.auth.customer.require-consent`)**: 켜져 있으면 로그인마다 customer-service `GET /internal/accounts/{id}/consents` 로 현행 버전 동의를 묻고(가입이면 만들 계정 ID — customer 에 행이 없어 모두 빠짐), 빠진 필수가 있으면 `AUTH_CONSENT` — 토큰·세션·`CustomerAuthenticated` 없이 `requiredConsents`·`optionalConsents`(`TYPE:version`)만. 판단은 트랜잭션 밖, customer 가 응답하지 않으면 503 DEPENDENCY_UNAVAILABLE(실패 횟수 미가산, 계정도 만들지 않음).
  - 완료 `POST /auth/social-logins/{id}/completions`(BR-20): 본문 `providerToken`(같은 Google 계정의 ID 토큰 — 다시 검증해 본인 확인, `sub` 의 연결 계정이 요청 계정과 같아야 함) + `consents[]`. 같은 기기만. customer `POST /internal/accounts/{id}/consent-changes`(channel = 가입 SIGNUP·재동의 LOGIN, 멱등 키 = 완료 요청의 키) → 필수가 다 찼으면 원인증 행 `AUTH_MEMBER`(completion_key)·세션·`CustomerAuthenticated`(다시 검증한 토큰의 프로필 — **동의 전에는 Google 프로필을 넘기지 않는다**). 빠지면 422 `REQUIRED_CONSENT_MISSING`(customer 의 `REQUIRED_CONSENT_CANNOT_BE_WITHDRAWN` 도 이것으로), `CONSENT_VERSION_OUTDATED` 는 그대로. 같은 키 재전송 = 새 토큰(customer 재호출·사건 없음), 다른 키로 이미 끝난 요청 409.
  - `customer_account.member_since`(V5, 기존 계정은 created_at 으로 채움): 동의 단계에서 나간 가입은 회원이 된 적이 없어 다음 로그인도 `newAccount=true`·SIGNUP. 원인증 행에 물은 항목(`required_consents`·`optional_consents`)을 남겨 같은 키 재전송·결과 조회가 같은 목록을 준다.
  - `require-consent=false` 면 예전처럼 바로 AUTH_MEMBER(시험 `CustomerSocialLoginIT`). 켠 경로 시험은 `CustomerConsentLoginIT`(customer = WireMock).
  - **LAN 켬(2026-11-19 사용자 "켜줘" — pmart-config a22982d, customer purchase-gate 와 함께, 재시작).** (예전 2026-11-17: 서버는 준비됐고 LAN 은 꺼 둠) — 고객 앱이 completions 새 본문을 반영하기 전에 켜면 AUTH_CONSENT 를 받은 로그인이 끝나지 않는다. 켤 때는 customer-service `consents.purchase-gate: true` 와 **함께** true. 시험은 두 경로 모두 속성으로 명시한다.
  - **로그인 전 기기 동의(2026-11-19 BR-59 — customer 0.6.0)**: 동의 판단 `check(accountId, deviceId)`(customer 가 그 기기의 아직 옮기지 않은 결정을 결정한 것으로 — 기기에서 고른 마케팅을 다시 묻지 않음). 회원 토큰을 준 뒤(로그인 MEMBER·동의 완료) 트랜잭션 밖에서 `CustomerConsentClient.migrateDevice`(`POST /internal/accounts/{id}/device-consent-migrations`) 한 번 — 실패는 경고만(로그인 그대로, 다음 로그인 때 다시). require-consent 와 상관없이 부른다. 시험 `CustomerConsentLoginIT`(deviceId 쿼리·옮기기 호출 확인).
  - 주소 `pmart.auth.customer.customer-service-base-url`(lan `http://customer-service:8103`, local `http://localhost:8103`).
- 기기별 15분 5회 `PROVIDER_REJECTED` → 10분 `AUTH_RESTRICTED`(423, `AuthRestrictedAdvice`). 제공자 장애(503)는 세지 않는다.
- 세션·토큰은 비회원과 공통(`SessionTokens`, `SessionService`). 고객 토큰 `typ=CUSTOMER`. 회원이면 `CustomerAuthenticated`(platform.auth.v1).
- Google 프로필(email·email_verified·name·picture)은 버리지 않고 `CustomerAuthenticated` 에 실어 customer-service 로 넘긴다(ADR-0024). auth 는 `sub` 만 저장한다. 개인정보 로그 금지.
- 시험은 `GoogleIdTokenVerifier` 빈을 시험 키 검증기로 바꾼다(`CustomerSocialLoginIT`).

## 고객 탈퇴 (2026-11-20 ADR-0077, 계약 0.9.0)
- 재인증 `POST /internal/customers/{accountId}/reauthentications`(`CustomerWithdrawalService.verifyReauthentication`): 로그인과 같은 `IdTokenVerifiers` 검증 + (제공자, sub) 연결이 이 accountId 인지. 200·401 PROVIDER_REJECTED·403 REAUTHENTICATION_MISMATCH·503. 상태 변경 없음, internal-paths 기본값이라 permit-path 불필요. 조회성 POST(계약에 Idempotency-Key 없음)라 `pmart.web.excluded-paths` 에 `/api/v1/internal/customers/*/reauthentications`.
- `CustomerWithdrawalCompleted`(commerce.customer.v1, 바인딩 `customerEvents`) 구독 — inbox 트랜잭션 하나에서 소셜 연결 해시(`WithdrawnIdentifierRules` SHA-256 "provider:subject")를 격리 표 `withdrawn_social_identifier`(V6, 계정 ID·FK 없음, 완료 + 5년)로 옮기고 `social_identity` 삭제(같은 소셜은 새 가입)·계정 WITHDRAWN·세션 REVOKED(WITHDRAWN)·SubjectBlocked(ACCOUNT_DISABLED). 이미 WITHDRAWN·없는 계정은 건너뜀. **격리 표는 첫 구매 혜택 중복 판정만 읽는다.** 만료 행은 `WithdrawnIdentifierPurger`(@Scheduled, 기본 P1D). 시험 `CustomerWithdrawalIT`·`WithdrawnIdentifierRulesTest`.

- **카카오 연결 끊기(2026-11-21 — 사용자 "권한 동의는 처음 가입할 때만")**: 카카오는 앱과 연결된 동안 동의를 기억한다. 탈퇴 반영(`onWithdrawalCompleted`)이 커밋된 뒤 그 계정의 KAKAO 연결마다 `KakaoUnlinkClient`(`POST {kakao.api-base-url 기본 https://kapi.kakao.com}/v1/user/unlink`, `Authorization: KakaoAK {admin-key}`, target_id_type=user_id·target_id=sub, 연결 2초·응답 3초)로 끊어 다시 가입할 때 동의 화면이 뜨게 한다. Admin 키 `pmart.auth.customer.kakao.admin-key`(pmart-config `{cipher}`) 없으면 부르지 않음. 실패는 로그(`reason=`)만, 탈퇴는 그대로. 로그인마다 끊지는 않는다. 시험 `KakaoUnlinkClientTest`.

## [LAN 시험 전용] 개발용 고객 토큰 (2026-11-19, 계약 0.3.0 — MFC 시뮬레이터 R-21)
- `POST /api/v1/internal/dev-customer-tokens`(`DevCustomerTokenController`·`DevCustomerTokenService`, `@ConditionalOnProperty pmart.auth.dev-customer-token.enabled=true` — 운영 설정에 없으면 빈·경로 없음 404). 게이트웨이에 없고 permit-path(공통 auth-service.yml).
- 허용 목록 `allowed-accounts` 의 기존 고객(customer_account ACTIVE)만 — 밖 403 `DEV_CUSTOMER_NOT_ALLOWED`, 없음 404, ACTIVE 아님 403. 새 계정을 만들지 않는다. 세션(ttl 기본 PT15M)을 열고 access token 만(refresh 없음) — 세션 행이 발급 기록. 토큰 로그 금지. 시험 `DevCustomerTokenIT`.

## 비회원 세션 (ADR-0022)
- `POST /api/v1/auth/guest-sessions`(permit-path, `X-Device-Id` 필수): 새 비회원 주체 `GST-…` + 토큰 쌍(`typ=GUEST`). 같은 키 재전송은 같은 주체·새 세션. 수명 `pmart.auth.guest.*`(access 1시간, refresh·세션 30일).
- 갱신은 `/auth/tokens`(세션 기기만), `SessionService` 의 GUEST 분기(정본 상태 없음). 자기 세션 조회·종료는 guest-paths.

## 상태
JWKS(2026-10-23, 서비스 토큰은 2026-10-31 폐지 — ADR-0026), 운영자 로그인·계정 관리·토큰 갱신·로그아웃·현재 세션(2026-10-30), 고객 Google 로그인(2026-10-30), 필수 약관 동의 단계·완료(completions, 2026-11-17 CU-1). 행위 검증·grant 는 501 UNSUPPORTED_ACTION.
- 개발 환경 전용 계정 `claude-dev`(2026-11-07)는 2026-11-13 운영자 로그인과 함께 없앴다 — LAN 시험은 employee-auth-service 의 `claude-dev`(`deploy/lan/checks/staff_auth.py`).
- **휴대폰 확인 단계(2026-11-20 ADR-0074, 계약 0.6.0 — 고객 BR-64, 스위치 `pmart.auth.customer.require-identity` 기본 false)**: 켜면 필수 동의 다음 `AUTH_IDENTITY`(nextAction VERIFY_IDENTITY) — 가입과 확인 번호가 없는 기존 회원(사용자 (가)). 판단은 트랜잭션 밖 `CustomerIdentityClient.verified`(customer `/internal/accounts/{id}/identity`). `POST /auth/social-logins/{id}/identity-verifications`(providerToken 다시 검증·같은 기기 → customer 내부 시작)·`…/{vid}/confirmations`(customer 내부 확인 → VERIFIED 면 `completeIdentity`·markMember·세션·CustomerAuthenticated, 아직이면 AUTH_IDENTITY·`identityVerification`, 다른 계정 번호 409 IDENTITY_ALREADY_REGISTERED details provider·joinedAt(member_since)·emailMasked, 탈퇴 막기 409 IDENTITY_REJOIN_BLOCKED rejoinAvailableAt — 둘 다 원인증 AUTH_GUEST·사유, 회원이 된 적 없는 계정·연결은 `discardIfNeverMember`). `/completions` 뒤 확인이 남으면 AUTH_CONSENT → AUTH_IDENTITY. 시험 `CustomerIdentityLoginIT`(customer WireMock, permit-paths 를 속성으로).
- **네이버 콜백 = 앱으로 돌아가기 페이지(2026-11-20, 계약 0.7.0 — 사용자 "그렇게 고쳐주고 배포해줘")**: 302 대신 200 HTML(`OAuthCallbackRules.page` — 링크 = 고정 앱 주소 + 받은 쿼리 원문, HTML 속성 이스케이프만, 열리면 `location.replace` 한 번, 버튼 "PMart 앱으로 돌아가기"). 까닭: 네이버가 동의 뒤 스크립트로 콜백에 보내고 그 이동에 이어진 302 → `com.pmart.customer://` 는 Chrome 이 사용자 동작이 없다며 앱을 열지 않았다(LAN 11-20 09:19:48 콜백 302, 폰 로그에 RedirectUriReceiverActivity 없음, 네이버 일반 오류 화면 — `docs/operations/2026-11-19-naver-oauth-lan-callback-diagnosis.md`). 헤더 no-store·Referrer-Policy no-referrer·CSP(`script-src 'sha256-…'` 고정 해시 — 스크립트를 바꾸면 `SCRIPT_SHA256` 도, `OAuthCallbackRulesTest` 가 확인)·nosniff. 시험 `OAuthCallbackRulesTest`·`CustomerSocialLoginIT.naverCallbackRelaysQueryToFixedAppAddress`.
- **네이버 프로필 사진(2026-11-20, 계약 0.8.0 — 사용자 "네이버 프로필 넣어줘야지", ADR-0071 덧붙임)**: 로그인 본문 세 곳(social-logins·completions·identity confirmations)의 선택 `profileImageUrl` — `ProviderPictureRules.pick`(토큰 `picture` 우선, 없고 NAVER 이면 https·`*.pstatic.net`·포트·사용자정보 없음·1000자 이하만, 아니면 무시)을 `publishAuthenticated` 가 CustomerAuthenticated `pictureUrl` 로. 서버는 네이버 access token 을 받지 않는다(앱이 프로필 API 로 읽음). 시험 `ProviderPictureRulesTest`·`CustomerSocialLoginIT.naverPictureFromAppOnlyForPstatic`.
