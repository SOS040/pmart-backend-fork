# [pmart-backend → pmart-clients] 알림: 로그인·세션·토큰 코드는 PC 1 만 고친다 (2026-11-15)

- 보낸 영역: platform
- 작성: pmart-backend(PC 1), 2026-11-15
- 대상: pmart-clients 전체(`all`) — 특히 mfc-app(PC 3)·rider-app(PC 5)·앞으로 backoffice(PC 2)·mfc-backoffice
- 근거: 사용자 2026-11-15 — "백오피스, mfc 로그인, 소셜 로그인같은 작업은 pc1에서 하는걸로 정해서 다른쪽에서 코드가 오염되는걸 막고싶어". 백엔드 정본 `pmart-backend/docs/operations/pc-split.md` §1-3.

## 규칙
- 아래 경로는 **PC 1 만 고친다**. 다른 PC 는 자기 앱을 맡아도 이 경로는 건드리지 않고, 필요하면 PC 1 에 요청한다(handoff 또는 사용자에게).
  - `web/core` 의 로그인·세션·토큰: `employeeAuth.ts`·`session.ts`·`sessionPersistence.ts`·`tokens.ts`, `pmartFetch.ts` 의 토큰 갱신 부분
  - 웹 앱의 `src/features/session`(backoffice·hr-backoffice, 새로 만들 mfc-backoffice)
  - `apps/customer_app/lib/features/auth`(Google 로그인)
  - `apps/mfc_app/lib/features/auth`(직원 로그인)
  - `apps/rider_app` 로그인(아직 없음 — 만들 때 PC 1)
  - `packages/pmart_core` 의 `auth_tokens.dart`·`interceptors.dart`
- 업무 화면이 토큰을 **쓰는** 코드(요청에 붙이기, 401·403 화면)는 각 앱 담당이 그대로 쓴다 — 바꾸는 것만 PC 1.
- 새 앱(예 MFC 백오피스)은 로그인·세션 부분을 PC 1 이 먼저 만들고, 메뉴·업무 화면은 담당 PC 가 만든다.

## 부탁
- 클라이언트 `docs/operations/pc-split.md` §1 에 같은 표를 적어 주세요(경로가 다르면 고쳐서 회신).
- 이미 다른 PC 에서 이 경로를 고치고 있으면 알려 주세요 — PC 1 이 이어받는다.
