# 요청: 탭이 다시 보일 때 세션 확인 — 로그아웃 전파(web/core)

- 보낸 영역: platform
- 작성: pmart-backend, 2026-11-14
- 대상: web-common(`web/core` 직원 세션 코드) — backoffice·hr-backoffice 둘 다
- 근거: ADR-0061(`docs/adr/0061-staff-logout-propagation-same-browser-and-keycloak-backchannel.md`), 사용자 2026-11-14 "탭에 돌아올 때 확인"
- 계약: `contracts/openapi/employee-auth-service.yaml` **0.4.0** — 클라이언트가 부르는 API 모양은 바뀌지 않았다(로그아웃 설명만). `gen:api` 는 해도 되고 안 해도 된다

## 1. 서버에서 바뀐 것
- 같은 브라우저에서 백오피스·인사팀 백오피스를 함께 쓰다가 **한쪽에서 로그아웃하면 다른 쪽 세션도 서버가 끝낸다**(같은 Keycloak 로그인으로 묶인 세션만 — 다른 PC·PDA 는 그대로).
- 관리자가 Keycloak 에서 그 직원을 로그아웃시켜도 두 앱 세션이 끝난다.
- 끝난 세션의 access token 은 게이트웨이에서 바로 401(`SUBJECT_BLOCKED`), refresh 쿠키 갱신은 401 `TOKEN_INVALID`.

## 2. 요청 — 탭이 다시 보일 때 한 번 확인
두 앱은 출처(포트)가 달라 브라우저 안에서 서로 알릴 수 없다. 지금은 다른 탭이 **다음 API 를 부를 때** 401 을 받고 로그인 화면으로 간다(이미 그렇게 동작해야 한다 — 401 → 갱신 실패 → silent → `LOGIN_REQUIRED` → 버튼). 화면만 띄워 둔 탭이 바로 알게:

1. `document.visibilitychange` 로 탭이 **보이게 될 때**(`visibilityState === 'visible'`), 세션이 있으면 `GET /employee-auth/sessions/current`(Bearer, 쿠키 없음) 한 번.
2. 200 → 아무것도 하지 않는다.
3. 401 → 지금 401 처리와 같게(갱신 한 번 시도 → 실패면 이 탭 세션을 지우고 silent 로그인 → `LOGIN_REQUIRED` 면 로그인 버튼). 로그아웃된 세션이면 Keycloak 세션도 끝나 있으므로 silent 는 `LOGIN_REQUIRED` 가 된다.
4. 네트워크 오류·5xx → 아무것도 하지 않는다(다음 호출 때 다시).
5. 너무 자주 부르지 않게: 마지막 확인 뒤 **30초** 안이면 건너뛴다(탭을 빨리 오갈 때).

## 3. 확인 (LAN, 배포 뒤 같이)
- 같은 브라우저: 인사팀 백오피스 탭과 백오피스 탭 → 백오피스에서 로그아웃 → 인사팀 탭으로 돌아가면 로그인 화면.
- 다른 브라우저(또는 시크릿 창)의 같은 직원 세션은 그대로.
