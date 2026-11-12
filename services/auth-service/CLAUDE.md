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

## 운영자 로그인 (ADR-0018)
- `POST /api/v1/auth/operator-logins`(permit-path, Idempotency-Key 제외 경로) → access(`typ=OPERATOR, roles, centers, sid`) + refresh. 비밀번호·토큰 로그 금지.
- 고객 세션은 마지막 사용 기준 연장(2026-11-02): 갱신마다 세션 만료 = 지금 + `pmart.auth.customer.session-max-age`(30일, `OperatorStore.extendSession`). 운영자는 절대 수명(12시간), 비회원은 변경 없음.
- refresh token 은 SHA-256 만 저장, 한 번만 쓴다. 재사용이면 세션 폐기(`TOKEN_REUSED`). 같은 Idempotency-Key 재전송도 재생하지 않는다(토큰을 저장하지 않으려고).
- 계정 관리 `/api/v1/operator-accounts/**` 는 `pmart.auth.operator.admin-role`(기본 `OPERATOR_ADMIN`)만. 수치(토큰 수명·잠금)는 `pmart.auth.operator.*`.
- 첫 관리자: `pmart.auth.operator.bootstrap.login-id`·`password-hash`(bcrypt, Secret Manager). 로컬 프로파일은 `admin` / `pmart-local`.
- **주체 차단 사건**(ADR-0054 ⑤·결정 K): 계정 비활성화(ACTIVE→DISABLED, 이미 DISABLED 면 내지 않음)·비밀번호 초기화 때 세션 폐기와 같은 트랜잭션에서 `SubjectBlocks.block(...)` → `SubjectBlocked`(토픽 `platform.subject-block.v1`, `issuer` = `pmart.auth.issuer`). 게이트웨이가 이미 나간 access token 을 만료 전에 막는다. 고객 계정을 정지하는 기능을 만들면 같은 사건을 낸다(사유 `ACCOUNT_RESTRICTED`).
- **Keycloak 직원 차단 중계**(ADR-0054 결정 M, `application/StaffBlockRelay`·`infrastructure/KeycloakAdminClient`, `pmart.auth.staff-relay.*` — LAN 만 켬): 10초마다 Keycloak 관리 기록(사용자)을 읽어 비활성화 → `ACCOUNT_DISABLED`, 세션 로그아웃 → `SESSIONS_REVOKED`, 비밀번호 재설정 → `PASSWORD_RESET` 으로 `SubjectBlocks.blockForIssuer(staff iss, pmart_operator_id, …)`. 같은 기록은 표 `staff_admin_event_seen`(Outbox 와 같은 트랜잭션)로 한 번만. 중계 클라이언트 `pmart-auth-relay` 는 읽기 권한만, 비밀값은 {cipher}. Keycloak 이 안 되면 다음 주기에 다시(되돌아 읽는 기간 1일). 사용자 삭제는 운영자 ID 를 몰라 못 막는다 — 직원은 지우지 않고 비활성화. **2026-11-13 LAN 에서 껐다**(ADR-0056 7단계 — Keycloak 토큰을 PMart 가 믿지 않고 직원 차단은 employee-auth-service 가 낸다, Keycloak 클라이언트 `pmart-auth-relay` 삭제). 코드는 남아 있고 `enabled: false`.

## 고객 소셜 로그인 (ADR-0019·0055)
- 지금은 GOOGLE 만. 앱이 보낸 ID 토큰을 `GoogleIdTokenVerifier`(Google JWKS, iss·aud·만료)로 검증 — client secret·인가 코드 교환·Spring OAuth2 Client 는 쓰지 않는다(ADR-0055, ADR-0025 기각). 허용 aud 는 `pmart.auth.customer.google.client-ids` = **고객용 웹 클라이언트 ID 하나**(고객 앱 serverClientId). Android 클라이언트 ID·라이더 웹 ID 는 넣지 않는다. 환경별 값은 pmart-config `auth-service-<프로필>.yml`(ADR-0029)(원본 클라이언트 파일은 백엔드에 두지 않음).
- 라이더 로그인은 이 서비스가 아니라 rider-auth-service(ADR-0054). 발급자 표의 auth-service 줄 RIDER 는 LAN 시험 라이더 토큰용으로 rider-auth-service 배치 전까지만 둔다.
- 제공자 `sub` → 내부 계정 `CU-…`(이메일 병합 없음). 필수 동의 단계는 `require-consent=false`(사용자 결정) — 신규도 바로 AUTH_MEMBER.
- 기기별 15분 5회 `PROVIDER_REJECTED` → 10분 `AUTH_RESTRICTED`(423, `AuthRestrictedAdvice`). 제공자 장애(503)는 세지 않는다.
- 세션·토큰은 운영자와 공통(`SessionTokens`, `SessionService`). 고객 토큰 `typ=CUSTOMER`. 회원이면 `CustomerAuthenticated`(platform.auth.v1).
- Google 프로필(email·email_verified·name·picture)은 버리지 않고 `CustomerAuthenticated` 에 실어 customer-service 로 넘긴다(ADR-0024). auth 는 `sub` 만 저장한다. 개인정보 로그 금지.
- 시험은 `GoogleIdTokenVerifier` 빈을 시험 키 검증기로 바꾼다(`CustomerSocialLoginIT`).

## 비회원 세션 (ADR-0022)
- `POST /api/v1/auth/guest-sessions`(permit-path, `X-Device-Id` 필수): 새 비회원 주체 `GST-…` + 토큰 쌍(`typ=GUEST`). 같은 키 재전송은 같은 주체·새 세션. 수명 `pmart.auth.guest.*`(access 1시간, refresh·세션 30일).
- 갱신은 `/auth/tokens`(세션 기기만), `SessionService` 의 GUEST 분기(정본 상태 없음). 자기 세션 조회·종료는 guest-paths.

## 상태
JWKS(2026-10-23, 서비스 토큰은 2026-10-31 폐지 — ADR-0026), 운영자 로그인·계정 관리·토큰 갱신·로그아웃·현재 세션(2026-10-30), 고객 Google 로그인(2026-10-30). 동의 완료(completions)는 INVALID_STATE, 행위 검증·grant 는 501 UNSUPPORTED_ACTION.
- **개발 환경 전용 계정(2026-11-07, 사용자 요청)**: `pmart.auth.operator.dev-accounts[]`(login-id·password-hash bcrypt·display-name·roles·centers) — 기동 때 없을 때만 만들고 비밀번호 변경 요구 없음. **LAN 프로필에만** 둔다(`claude-dev`, OPERATOR_ADMIN — Claude 가 LAN 에서 혼자 시험하려고). 원문 비밀번호는 `deploy/lan/.secrets/claude-dev-password`(git 제외). 운영 설정에 넣지 않는다. 없애려면 설정 항목 삭제 + 계정 비활성화.
