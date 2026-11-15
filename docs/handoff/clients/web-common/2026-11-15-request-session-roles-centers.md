# [pmart-backend → pmart-clients] 요청: 메뉴·센터는 access token 대신 `sessions/current` 응답으로 (2026-11-15)

- 보낸 영역: platform
- 대상: `web/core`(web-common) + 세 웹 앱(backoffice·hr-backoffice·mfc-backoffice) — **로그인·세션 코드라 PC 1 만**(`../all/2026-11-15-notice-login-code-pc1-only.md`)
- 근거: 사용자 2026-11-15 — 일반적인 `/me` 방식으로("아까 말한 순서로 하자"). 계약 `employee-auth-service.yaml` **0.6.0**(백엔드 95e8c9ed, LAN 배포됨).

## 1. 바뀐 것 (백엔드)
- `GET /api/v1/employee-auth/sessions/current` 응답에 **`roles`·`centers`**(필수)를 더했다. 값 = 지금 기준(계정 역할 ∪ 지금 유효한 승인 권한 — 토큰을 만들 때와 같은 계산). 계정이 정지·퇴사면 401 `SESSION_EXPIRED`.
- 필드를 더하기만 했으니 지금 빌드는 그대로 동작한다.

## 2. 부탁
1. `web/core`: 앱을 띄울 때·새로고침·토큰 갱신 뒤·탭이 다시 보일 때(ADR-0061 확인 — 이미 이 API 를 부른다) `sessions/current` 를 부르고, **그 응답의 `roles`·`centers` 를 세션 상태로** 쓴다. `session.ts` 의 access token 풀기(`decodeSegment`)는 화면 판단에 쓰지 않는다(목 모드용 `mockOperatorToken` 만 남김).
2. 세 앱의 메뉴(`nav.ts`·`App.tsx`)와 MFC 백오피스 센터 고르기가 그 값을 읽게.
3. 응답과 토큰이 잠깐 다를 수 있다(권한 승인·회수 직후, 토큰은 갱신 전). 화면은 응답을 따르고, API 403 이 오면 갱신 뒤 다시 보면 같아진다.
- 이유: access token 은 API 서버가 읽는 값이라 화면이 내용에 기대면 토큰 모양이 바뀔 때 세 앱이 같이 깨진다. 권한 변경도 바로 보인다.
- 센터 이름은 아직 응답에 없다 — 지금처럼 `GET /centers` 로 붙인다.
