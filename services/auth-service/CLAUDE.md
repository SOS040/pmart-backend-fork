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
- 지금은 GOOGLE 만. 앱이 보낸 ID 토큰을 `GoogleIdTokenVerifier`(Google JWKS, iss·aud·만료)로 검증 — client secret·인가 코드 교환·Spring OAuth2 Client 는 쓰지 않는다(ADR-0055, ADR-0025 기각). 허용 aud 는 `pmart.auth.customer.google.client-ids` = **고객용 웹 클라이언트 ID 하나**(고객 앱 serverClientId). Android 클라이언트 ID·라이더 웹 ID 는 넣지 않는다. 환경별 값은 pmart-config `auth-service-<프로필>.yml`(ADR-0029)(원본 클라이언트 파일은 백엔드에 두지 않음).
- 라이더 로그인은 이 서비스가 아니라 rider-auth-service(ADR-0054). 발급자 표의 auth-service 줄 RIDER 는 LAN 시험 라이더 토큰용으로 rider-auth-service 배치 전까지만 둔다.
- 제공자 `sub` → 내부 계정 `CU-…`(이메일 병합 없음).
- **필수 약관 동의 단계(2026-11-17, CU-1 — 계약 0.2.0, 스위치 `pmart.auth.customer.require-consent`)**: 켜져 있으면 로그인마다 customer-service `GET /internal/accounts/{id}/consents` 로 현행 버전 동의를 묻고(가입이면 만들 계정 ID — customer 에 행이 없어 모두 빠짐), 빠진 필수가 있으면 `AUTH_CONSENT` — 토큰·세션·`CustomerAuthenticated` 없이 `requiredConsents`·`optionalConsents`(`TYPE:version`)만. 판단은 트랜잭션 밖, customer 가 응답하지 않으면 503 DEPENDENCY_UNAVAILABLE(실패 횟수 미가산, 계정도 만들지 않음).
  - 완료 `POST /auth/social-logins/{id}/completions`(BR-20): 본문 `providerToken`(같은 Google 계정의 ID 토큰 — 다시 검증해 본인 확인, `sub` 의 연결 계정이 요청 계정과 같아야 함) + `consents[]`. 같은 기기만. customer `POST /internal/accounts/{id}/consent-changes`(channel = 가입 SIGNUP·재동의 LOGIN, 멱등 키 = 완료 요청의 키) → 필수가 다 찼으면 원인증 행 `AUTH_MEMBER`(completion_key)·세션·`CustomerAuthenticated`(다시 검증한 토큰의 프로필 — **동의 전에는 Google 프로필을 넘기지 않는다**). 빠지면 422 `REQUIRED_CONSENT_MISSING`(customer 의 `REQUIRED_CONSENT_CANNOT_BE_WITHDRAWN` 도 이것으로), `CONSENT_VERSION_OUTDATED` 는 그대로. 같은 키 재전송 = 새 토큰(customer 재호출·사건 없음), 다른 키로 이미 끝난 요청 409.
  - `customer_account.member_since`(V5, 기존 계정은 created_at 으로 채움): 동의 단계에서 나간 가입은 회원이 된 적이 없어 다음 로그인도 `newAccount=true`·SIGNUP. 원인증 행에 물은 항목(`required_consents`·`optional_consents`)을 남겨 같은 키 재전송·결과 조회가 같은 목록을 준다.
  - `require-consent=false` 면 예전처럼 바로 AUTH_MEMBER(시험 `CustomerSocialLoginIT`). 켠 경로 시험은 `CustomerConsentLoginIT`(customer = WireMock).
  - **서버는 준비됐고 LAN 은 꺼 둔다(2026-11-17, pmart-config `auth-service.yml` `require-consent: false`)** — 고객 앱이 completions 새 본문을 반영하기 전에 켜면 AUTH_CONSENT 를 받은 로그인이 끝나지 않는다. 켤 때는 customer-service `consents.purchase-gate: true` 와 **함께** true. 시험은 두 경로 모두 속성으로 명시한다.
  - 주소 `pmart.auth.customer.customer-service-base-url`(lan `http://customer-service:8103`, local `http://localhost:8103`).
- 기기별 15분 5회 `PROVIDER_REJECTED` → 10분 `AUTH_RESTRICTED`(423, `AuthRestrictedAdvice`). 제공자 장애(503)는 세지 않는다.
- 세션·토큰은 비회원과 공통(`SessionTokens`, `SessionService`). 고객 토큰 `typ=CUSTOMER`. 회원이면 `CustomerAuthenticated`(platform.auth.v1).
- Google 프로필(email·email_verified·name·picture)은 버리지 않고 `CustomerAuthenticated` 에 실어 customer-service 로 넘긴다(ADR-0024). auth 는 `sub` 만 저장한다. 개인정보 로그 금지.
- 시험은 `GoogleIdTokenVerifier` 빈을 시험 키 검증기로 바꾼다(`CustomerSocialLoginIT`).

## 비회원 세션 (ADR-0022)
- `POST /api/v1/auth/guest-sessions`(permit-path, `X-Device-Id` 필수): 새 비회원 주체 `GST-…` + 토큰 쌍(`typ=GUEST`). 같은 키 재전송은 같은 주체·새 세션. 수명 `pmart.auth.guest.*`(access 1시간, refresh·세션 30일).
- 갱신은 `/auth/tokens`(세션 기기만), `SessionService` 의 GUEST 분기(정본 상태 없음). 자기 세션 조회·종료는 guest-paths.

## 상태
JWKS(2026-10-23, 서비스 토큰은 2026-10-31 폐지 — ADR-0026), 운영자 로그인·계정 관리·토큰 갱신·로그아웃·현재 세션(2026-10-30), 고객 Google 로그인(2026-10-30), 필수 약관 동의 단계·완료(completions, 2026-11-17 CU-1). 행위 검증·grant 는 501 UNSUPPORTED_ACTION.
- 개발 환경 전용 계정 `claude-dev`(2026-11-07)는 2026-11-13 운영자 로그인과 함께 없앴다 — LAN 시험은 employee-auth-service 의 `claude-dev`(`deploy/lan/checks/staff_auth.py`).
