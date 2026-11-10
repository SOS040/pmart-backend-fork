# ADR-0054 인증을 셋으로 나눈다 — 직원은 Keycloak, 고객은 지금 auth-service, 라이더는 Google 로그인 라이더 auth 서비스(새로), 게이트웨이는 1개

- 상태: 승인 (2026-11-10, 사용자). 처음 기록(같은 날)은 사용자가 삭제한 첫 번째 추천 글(Keycloak Realm 2개·Google 신원 연결)을 따랐으나, 사용자가 기준은 **두 번째 글**이라고 바로잡았다:
  - "키클록 구성을 따를거야" / "auth 관련해서 두번째로 준 md 파일을 확인하란거였어"
  - "라이더 키클롭 안쓰는거야. 구글 소셜 로그인을 쓰는 라이더 auth 서비스를 하나 더만드는거야"
  - "지금 auth 서비스를 고객앱 쓰는 그대로 하면돼"
- 근거: `docs/architecture/auth/references/2026-11-10/PMart-API-Gateway-단일-구성과-URL별-JWT-인증-인가.md`, 확인 `docs/architecture/auth/2026-11-10-gateway-url-jwt-review.md`.
- 대체: ADR-0011 대안의 "Keycloak 은 도입하지 않는다"(서비스 신원 토큰 결정은 유지), ADR-0018 의 운영자 로그인 발급 주체(auth-service → Keycloak). ADR-0019·0025(고객 Google 로그인)는 그대로.

## 결정
1. **직원(백오피스·MFC 등 운영자) = Keycloak.** 서버 1개.
2. **고객 = 지금 auth-service 그대로**(Google 로그인 ADR-0019·0025, 고객 토큰). 비회원(`GUEST`)·서비스 신원(`SERVICE`, ADR-0011) 토큰도 지금처럼 auth-service.
3. **라이더 = 새 라이더 auth 서비스**(Google 소셜 로그인, 라이더 토큰 `typ=RIDER` 발급). Keycloak 을 쓰지 않는다. 라이더 업무 정보(승인·정지·운행 상태)는 지금처럼 rider-service 정본 — 라이더 auth 는 로그인·토큰만.
4. **게이트웨이 1개**, 발급자 3개(Keycloak, auth-service, 라이더 auth). 사용자 종류마다 허용 발급자·대상(`aud`)·권한을 검증하고, 맞지 않으면 거부(고객 토큰으로 라이더 API 불가). 서비스도 다시 검증하고, 업무 인가(배차 소유권·라이더 승인·센터 권한)는 서비스가 본다.
5. 서비스가 쓰는 권한 형태(`TYPE_x`·역할·센터)는 유지 — 발급자별 토큰을 이 형태로 바꾸는 일은 공통 변환기(`common-security`)가 맡는다.

## 정할 것(구현 전)
- **URL 구조**: 두 번째 글은 `/api/backoffice/**`·`/api/customer/**`·`/api/rider/**` 로 나눠 영역마다 검증한다. 지금 경로는 자원 기준(`/api/v1/deliveries/**` 등)이라 한 경로를 여러 사용자 종류가 쓴다 — 경로를 바꿀지, 지금 경로에서 발급자·종류 짝으로 막을지 사용자 결정.
- **직원 주체 ID 유지**: 운영자 ID 가 여러 서비스에 기록돼 있다(mfc-capacity `workerId = 운영자 ID`, 승인 이력 처리자 등). Keycloak 기본 `sub` 는 UUID — 기존 ID 를 클레임으로 싣는 방법·계정 이전 순서.
- **라이더 가입과 업무 원문**: `Delivery-business-logic.md` §1.1 은 전화번호 인증·아이디·비밀번호·"가입 완료 → 즉시 운행 승인". 로그인은 Google 로 정했으므로 전화번호·약관·배달 정보 입력·운행 승인 시점을 어떻게 맞출지.
- 라이더 auth 서비스 이름·포트·DB, Google OAuth 클라이언트(라이더 앱용), service-map 등록(새 서비스 경계).
- Keycloak 이미지 버전 고정·DB·배치 PC·관리자 비밀 보관, `iss` 호스트 이름, 토큰 수명, 즉시 차단(퇴사·라이더 정지) 방법.
- 백오피스·MFC 앱·라이더 앱 로그인 변경 요청(pmart-clients handoff).

## 대안
- 라이더도 Keycloak(첫 번째 글) — 사용자가 쓰지 않기로 함.
- 라이더를 지금 auth-service 에 넣기 — 사용자가 별도 서비스로 정함.
- 고객도 Keycloak — 바꿀 요구 없음.

## 결과
- 새 운영 구성요소 2개(Keycloak + DB, 라이더 auth 서비스 + DB). LAN 배치·백업·모니터링 대상이 는다.
- 발급자가 3개가 되어 게이트웨이와 전 서비스의 JWT 검증 설정이 바뀐다(`common-security` 변경 = 전 서비스 영향).
- 운영자 로그인 이전 동안 두 방식이 잠시 함께 있을 수 있다.
