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

## 운영자 로그인 (ADR-0018)
- `POST /api/v1/auth/operator-logins`(permit-path, Idempotency-Key 제외 경로) → access(`typ=OPERATOR, roles, centers, sid`) + refresh. 비밀번호·토큰 로그 금지.
- 고객 세션은 마지막 사용 기준 연장(2026-11-02): 갱신마다 세션 만료 = 지금 + `pmart.auth.customer.session-max-age`(30일, `OperatorStore.extendSession`). 운영자는 절대 수명(12시간), 비회원은 변경 없음.
- refresh token 은 SHA-256 만 저장, 한 번만 쓴다. 재사용이면 세션 폐기(`TOKEN_REUSED`). 같은 Idempotency-Key 재전송도 재생하지 않는다(토큰을 저장하지 않으려고).
- 계정 관리 `/api/v1/operator-accounts/**` 는 `pmart.auth.operator.admin-role`(기본 `OPERATOR_ADMIN`)만. 수치(토큰 수명·잠금)는 `pmart.auth.operator.*`.
- 첫 관리자: `pmart.auth.operator.bootstrap.login-id`·`password-hash`(bcrypt, Secret Manager). 로컬 프로파일은 `admin` / `pmart-local`.

## 고객 소셜 로그인 (ADR-0019)
- 지금은 GOOGLE 만. 앱이 보낸 ID 토큰을 `GoogleIdTokenVerifier`(Google JWKS, iss·aud·만료)로 검증 — client secret 은 쓰지 않는다. 허용 aud 는 `pmart.auth.customer.google.client-ids`— 환경별 값은 pmart-config `auth-service-<프로필>.yml`(ADR-0029)(원본 클라이언트 파일은 백엔드에 두지 않음).
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
