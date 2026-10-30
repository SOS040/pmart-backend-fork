# ADR-0018 운영자는 auth-service 의 운영자 계정(아이디·비밀번호)으로 로그인하고 JWT 를 받는다

- 상태: 제안 (2026-10-30, 사용자 결정)
- 관련: ADR-0011(서비스 신원 토큰·서명키), `docs/architecture/implementation-plan.md` D8, `contracts/openapi/auth-service.yaml`(operators), BackOffice 3장·BO-01

## 배경
- 임시 백오피스(React)를 만들어야 하는데 운영자가 토큰을 받을 방법이 없었다. auth-service 계약에는 고객 소셜 로그인만 있었다.
- D8 은 "auth-service 는 자체 JWT 로그인과 OAuth 로그인을 둘 다 지원"하는 방향이었다. 사용자는 운영자 계정 + JWT 방식을 골랐다(2026-10-30).
- 각 서비스는 이미 JWT 클레임 `typ`·`roles`·`centers` 로 인가한다(common-security `PmartPrincipal`).

## 결정
1. **운영자 계정은 auth-service 가 소유한다**(`operator_account`). 아이디·표시 이름·bcrypt 비밀번호 해시·역할·담당 센터·상태(ACTIVE/DISABLED)·연속 실패·잠금 시각.
2. **로그인** `POST /auth/operator-logins` → access token(RS256, ADR-0011 의 같은 서명키) + refresh token.
   - access token 클레임: `sub`=operatorId, `typ=OPERATOR`, `roles`, `centers`, `sid`(세션). 기본 수명 15분.
   - refresh token 은 무작위 값이고 DB 에는 SHA-256 만 둔다. **한 번만 쓴다(회전)**. 쓴 토큰이 다시 오면 탈취로 보고 세션을 폐기한다(`TOKEN_REUSED`).
   - 세션은 절대 수명(기본 12시간)이 있어 갱신해도 늘지 않는다.
   - 역할·센터는 로그인과 토큰 갱신 때마다 계정에서 다시 읽는다. 바꾼 권한은 늦어도 access token 수명(15분) 안에 반영된다.
3. **실패·잠금**: 아이디 없음과 비밀번호 불일치는 같은 응답(`LOGIN_FAILED`)이고 응답 시간도 같게 한다. 연속 5회 실패하면 15분 잠근다(`ACCOUNT_LOCKED`, 423). 수치는 보안 설정값(`pmart.auth.operator.*`)이다.
4. **계정 관리**(`/operator-accounts`)는 `OPERATOR_ADMIN` 역할만 한다: 생성, 표시 이름·역할·센터·상태 수정, 비밀번호 초기화.
   - 비활성화·비밀번호 초기화는 열린 세션을 폐기한다.
   - 관리자는 자기 계정을 비활성화하거나 관리자 역할을 뺄 수 없다.
   - 생성·초기화한 계정은 "비밀번호 변경 필요" 표시가 켜진다(화면 안내용. 서버가 다른 API 를 막지는 않는다).
5. **첫 관리자**: 설정 `pmart.auth.operator.bootstrap.login-id` / `password-hash`(bcrypt)가 있고 그 아이디가 없을 때만 기동 시 만든다. 원문 비밀번호는 설정에 두지 않는다. 로컬 프로파일만 `admin` / `pmart-local` 을 둔다.
6. **게이트웨이**: 로그인은 공개 작업(계약 `security: []`), `/operator-accounts/**` 는 auth-service 로 라우팅한다. 웹(브라우저) 클라이언트용 CORS 를 `pmart.gateway.cors.allowed-origins` 출처에만 연다(쿠키를 쓰지 않아 자격 증명 허용은 끔). CSRF 토큰은 두지 않는다(쿠키 인증이 아니므로, 사용자 결정 2026-10-30).

## 대안
- **개발용 토큰 발급 API**: 빠르지만 운영에서 꺼야 하고 결국 로그인을 다시 만들어야 한다.
- **외부 IdP(Keycloak 등)**: 새 플랫폼 도입이라 요청 없이 하지 않는다(CLAUDE.md).
- **역할 부여를 approval BO-01 승인으로만**: BackOffice 원칙에는 맞지만 임시 백오피스 단계에서 권한 승인 흐름까지 만들면 늦어진다. 지금은 관리자가 직접 바꾸고, BO-01 연동은 auth grant 구현 때 한다.
- **쿠키 세션**: 브라우저에 맞지만 CSRF 대응이 필요하다. 사용자가 CSRF 는 지금 하지 않기로 했으므로 Bearer 토큰을 쓴다.

## 결과
- 테이블 3개(`operator_account`, `auth_session`, `refresh_token`). `/auth/tokens`·`/auth/logouts`·`/auth/sessions/current` 는 지금은 운영자 세션만 다룬다(고객 소셜 로그인은 D6 결정 후 같은 세션 표를 쓴다).
- refresh 는 토큰을 저장하지 않으려고 같은 Idempotency-Key 재전송을 재생하지 않는다. 응답을 잃으면 다시 로그인해야 할 수 있다.
- 로그아웃해도 이미 발급된 access token 은 만료(최대 15분)까지 유효하다(서비스들은 서명만 검증).
- 역할 이름은 서비스 설정과 맞아야 한다(approval `approver-roles`, product `sale-state-change-role` 등).
- LAN·운영 배포 때 할 일: 첫 관리자 아이디·해시(`PMART_AUTH_OPERATOR_BOOTSTRAP_LOGINID`, `PMART_AUTH_OPERATOR_BOOTSTRAP_PASSWORDHASH`)와 게이트웨이 허용 출처(`PMART_GATEWAY_CORS_ALLOWEDORIGINS`)를 `.secrets`·설정에 넣는다.
