# ADR-0061 직원 로그아웃 전파 — 같은 브라우저의 SSO 앱 세션을 함께 끝내고, Keycloak 백채널 로그아웃을 받는다

- 상태: 승인 (2026-11-14, 사용자 — "로그아웃 전파할거고"(11-14) → 설계안 → 범위 "같은 브라우저만", 백채널 "같이 넣기", 탭 반영 "탭에 돌아올 때 확인"(모두 추천안))
- 관련: ADR-0056(employee-auth-service, 결정 14 SSO), ADR-0059(웹 refresh 쿠키), ADR-0054 ⑤(게이트웨이 차단 목록 `SubjectBlocked`), `docs/architecture/auth/employee-auth-structure.md`, 계약 `contracts/openapi/employee-auth-service.yaml` 0.4.0

## 배경
- 백오피스·인사팀 백오피스는 SSO 앱이다. 같은 브라우저에서 둘 다 쓰면 **Keycloak 세션 하나**에 **PMart 세션이 앱마다 하나씩** 달린다.
- 한 앱에서 로그아웃하면 그 앱의 PMart 세션과(게이트웨이 차단 목록으로 access token 도 바로 401) Keycloak 세션은 끝나지만, **이미 열린 다른 앱의 PMart 세션은 12시간 한도까지 남았다**(2026-11-14 LAN 확인 — 인사팀 탭이 백오피스 로그아웃 뒤에도 갱신 200).
- Keycloak 쪽에서 세션이 끝나는 경우(관리자가 Keycloak 에서 로그아웃, 비밀번호 변경 때 다른 세션 로그아웃)도 PMart 세션에 전해지지 않았다.

## 결정
1. **범위 = 같은 브라우저(같은 Keycloak 세션)**: SSO 앱 세션에 Keycloak 세션 ID(ID 토큰 `sid`)를 둔다(V5 `keycloak_sid`). 로그아웃(`POST /employee-auth/logouts`, 이 세션만)하면 같은 `keycloak_sid`·같은 직원의 다른 살아 있는 세션도 끝내고(`revoke_reason` LOGOUT_PROPAGATED) 세션마다 `SubjectBlocked`(reason LOGOUT, sessionId)를 내 게이트웨이가 그 토큰을 바로 막는다. **다른 PC 의 세션·PDA(SSO 아님 — Keycloak 세션을 로그인 직후 끝냄)는 그대로.** "모든 기기 로그아웃"(`allDevices`)은 지금처럼 따로 있다.
2. **Keycloak 백채널 로그아웃(OIDC Back-Channel Logout 1.0)**: Keycloak 클라이언트 `employee-auth-service` 에 `backchannel.logout.url` = `http://employee-auth-service:8511/api/v1/internal/keycloak-logouts`(같은 PC Docker 네트워크, 게이트웨이에 열지 않음), `backchannel.logout.session.required` = true, `frontchannelLogout` = false. 받는 쪽은 logout_token 의 서명(Keycloak JWKS)·만료·iss·aud(이 클라이언트)·`events` 의 back-channel logout·nonce 없음·sid 또는 sub 를 확인한다. sid 면 그 Keycloak 세션의 PMart SSO 세션을(sub 도 있으면 그 직원 것만), sid 없이 sub 만이면 그 직원의 SSO 세션 전부를 끝내고(`revoke_reason` IDP_LOGOUT) 막는다. 무효 토큰은 400 `{"error":"invalid_request"}`, 공개키를 못 받으면 503. 이미 끝난 세션이면 그대로 200(멱등 — 브라우저 로그아웃 뒤 Keycloak 이 다시 알려 와도 무해). 이 경로는 OIDC 규약 요청이라 `Idempotency-Key`·`X-Source-System` 검사에서 뺀다(`pmart.web.excluded-paths`). 믿음의 근거는 Keycloak 서명이다(ADR-0026 내부 경로, 사용자 토큰 받지 않음).
3. **탭 반영(클라이언트)**: 두 백오피스는 출처(포트)가 달라 브라우저 안에서 서로 알릴 수 없다. 서버가 세션을 끝낸 뒤 다른 탭은 다음 API 호출 때 401 을 받는다. 더해 **탭이 다시 보일 때(visibilitychange) `GET /employee-auth/sessions/current` 로 확인**해 끝났으면 로그인 화면으로 간다(web/core 요청 `docs/handoff/clients/web-common/2026-11-14-web-common-request-logout-propagation.md`).

## 하지 않는 것·한계
- Keycloak 세션이 **시간으로 만료**될 때는 Keycloak 이 백채널을 보내지 않는다 — PMart 세션은 자기 한도(무활동·12시간)로 끝난다.
- 브라우저가 로그아웃 응답의 `idpLogoutUri` 로 가지 못하면 Keycloak 세션이 남아 새 탭의 silent 로그인이 다시 될 수 있다(이미 열린 다른 앱 세션은 결정 1 로 끝난다).
- `sid` 는 자격 증명이 아니어서(그것만으로 로그인할 수 없음) 세션이 끝난 뒤에도 기록으로 남긴다. ID 토큰은 지금처럼 세션이 끝나면 지운다.

## 결과
- employee-auth-service 계약 0.4.0(`/internal/keycloak-logouts`, 로그아웃 설명), V5, 시험 `EmployeeLoginIT` 2개(같은 브라우저 전파 — 다른 PC·PDA 유지, Keycloak 관리자 로그아웃 → 백채널 → 두 세션 종료·차단 사건, 무효 토큰 400).
- LAN: Keycloak 클라이언트 설정은 관리 API 로 바꾸고 Realm 파일(`deploy/lan/keycloak/pmart-staff-realm.json`)도 같게 둔다(Realm 은 처음 한 번만 가져온다).
