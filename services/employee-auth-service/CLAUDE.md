# employee-auth-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `employee-auth-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.
결정: ADR-0056(Keycloak 은 직원 신원만, 직원 토큰은 이 서비스). 설계·구현에서 정한 것: `docs/architecture/auth/employee-auth-structure.md`(진행 절).

## 계약
- REST: `contracts/openapi/employee-auth-service.yaml` → `openApiGenerate` 가 `com.pmart.employeeauth.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 아직 없음(Kafka 를 쓰지 않는다 — Employee* 구독·SubjectBlocked·GrantApplied 발행은 ADR-0056 5단계). 시험 클래스패스에만 common-messaging 이 있어 `employee-auth-service-it-env.yml` 에 `pmart.messaging.domain`.

## 패키지
`com.pmart.employeeauth.{api, application, infrastructure}`. Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- Keycloak 토큰·비밀번호·이메일·이름은 저장하지 않는다 — Keycloak `sub` ↔ 운영자 ID 연결만. state·티켓·refresh token 은 SHA-256 만. Keycloak PKCE verifier 는 콜백에서 지운다.
- `employee_account` 가 직원 상태·역할·권한 센터의 정본(D1). 바꾸는 길은 지금 관리 경로(PUT, 변경 기록) 하나.

## 로그인 (Keycloak 기밀 클라이언트)
- Spring OAuth2 Client 를 쓰지 않는다 — 게이트웨이가 Cookie 헤더를 지우므로 세션 쿠키 없이 돌게 직접 구현(`EmployeeLoginService`, `infrastructure/KeycloakClient`).
- 브라우저 주소(authorization-uri)와 서버끼리 주소(token·jwks·logout — 같은 PC 5 Docker 네트워크)를 나눈다. ID 토큰 `iss` 는 `pmart.employee-auth.keycloak.issuer` 와 정확히 같아야 한다(Keycloak KC_HOSTNAME 기준).
- 콜백에서 코드 교환 직후 Keycloak 세션을 끝내고(`endSession`), 시작 URL 에는 `prompt=login`(공용 PDA).
- 티켓은 60초·1회, 시작한 기기 + 앱의 code_verifier(S256) 로만. 틀린 시도도 티켓을 태운다.
- 복귀 주소는 설정 허용 목록에서만(열린 리디렉션 금지). 오류는 `?error=코드` 로 앱에 돌려준다(state 를 모르면 400).
- Keycloak 클라이언트 시크릿은 pmart-config `{cipher}`(원본 `deploy/lan/.secrets/keycloak-employee-auth-client-secret`), Keycloak 쪽 값은 Realm 파일 자리표시자(render.sh). 로그에 남기지 않는다.

## 토큰 (자체 JWT)
- access: RS256, 예전 auth-service 운영자 토큰과 같은 모양 — `iss` = `pmart.employee-auth.issuer`(발급자 표 `staff-ea` 줄과 같게), `sub` = 운영자 ID, `typ=OPERATOR`, `aud=pmart-api`, `roles`, `centers`, `sid`, 15분.
- refresh: 한 번만(재사용이면 세션 폐기 `TOKEN_REUSED`), 로그인한 기기에서만. 세션 = min(갱신 + 앱 무활동 한도, 시작 + 12시간).
- 서명키 `pmart.employee-auth.signing.keys`(다른 발급자와 다른 키, LAN `{cipher}`, 원본 `.secrets/employee-auth-signing-key.pem`). local/test 만 `ephemeral=true`.
- 로그아웃·현재 세션은 이 서비스가 발급한 토큰만(Keycloak 토큰은 403). 관리 경로는 발급자 표가 인정하는 OPERATOR + `OPERATOR_ADMIN`.

## 시험
```bash
./gradlew :services:employee-auth-service:check   # 단위 + 통합(PostgreSQL·Keycloak 26.8.1 컨테이너) + 계약 검증
```
- `EmployeeLoginIT` 는 Keycloak 컨테이너(`src/integrationTest/resources/keycloak/pmart-staff-it-realm.json` → 컨테이너 안 이름 `pmart-staff-realm.json` — 26.8 은 파일 이름 = Realm 이름)를 띄우고 브라우저를 흉내 낸다. Keycloak 쿠키에 Secure 가 붙어 java.net.CookieManager 대신 직접 쿠키를 보관한다.
- 로컬 포트 8511. LAN PC 5. 로컬 프로필에는 Keycloak 이 없어 로그인 시작이 CONFIG_REQUIRED.
