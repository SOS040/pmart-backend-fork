# [pmart-backend → pmart-clients] 요청: 직원 웹 앱 바꾸기 메뉴(앱 런처 ⋮⋮⋮) — 직원용 백오피스 4개 (2026-11-17)

- 보낸 영역: platform(PC 1)
- 대상: pmart-clients 웹 — **`web/ui`(웹 공통, PC 2)** 가 메뉴를 만들고, 네 앱이 머리줄에 붙인다: `web/backoffice`·`web/hr-backoffice`(PC 2), `web/mfc-backoffice`(PC 3), `web/rider-backoffice`(PC 5).
- 근거: 사용자 2026-11-17 — 구글 오른쪽 위 점 9개 메뉴처럼(참고 화면 `docs/architecture/auth/2026-11-17-google-app-launcher.png`).
  "앱런처 들어갈 내용은 지금 직원용 백오피스들 4개", "같은 탭을 열거야", "다 보여지는 방식으로 해줘".
- 계획(배경·구글과 다른 점): `docs/architecture/auth/2026-11-17-staff-app-launcher-plan.md`
- **백엔드 상태: 바꿀 것 없음, 준비 끝.** 네 앱 모두 employee-auth-service `sso: true`, LAN return-uri 에 `192.168.0.40`·`d603-16` 둘 다 있음, 조용한 로그인(silent)은 이미 네 앱에서 동작(사용자 2026-11-17 8446 비밀번호 없이 확인).

## 1. 무엇을

머리줄 오른쪽(로그아웃 버튼 근처)에 ⋮⋮⋮ 버튼 → 누르면 앱 4개 목록 → 하나를 누르면 **같은 탭**에서 그 앱 첫 화면(`/`)으로 간다.
그 앱은 이 탭에서 처음 열리므로 이미 있는 조용한 로그인으로 들어간다(비밀번호 안 물음, 화면이 1초 안팎 한 번 넘어감 — 앱마다 출처가 달라 정상).

| 순서 | 앱 | 보일 이름(제안) | LAN 포트(https) | 개발·목 포트(http localhost) |
|---|---|---|---|---|
| 1 | `backoffice` | 백오피스 | 443(포트 없음) | 5173 |
| 2 | `hr-backoffice` | 인사팀 | 8444 | 5174 |
| 3 | `mfc-backoffice` | MFC 센터 | 8445 | 5175 |
| 4 | `rider-backoffice` | 라이더 운영 | 8446 | 5176 |

## 2. 정한 것 (사용자 2026-11-17)

1. **네 앱 모두, 모든 직원에게 똑같이 보인다** — 역할로 거르지 않는다. 역할 → 앱 표를 만들지 않는다.
   - 메뉴는 편의일 뿐 권한이 아니다. 역할이 없는 앱을 누르면 그 앱이 이미 띄우는 안내("… 권한이 없어요")가 나오고, API 는 서버가 403 으로 막는다.
   - 그래서 메뉴는 세션·역할을 읽지 않아도 된다(링크만).
2. **같은 탭**으로 연다. 보통 `<a href>` 로 두어 휠클릭·Ctrl+클릭이면 브라우저가 새 탭으로 여는 것은 그대로 둔다(`window.open` 강제 안 함).
3. 지금 앱은 목록에 표시(강조)만 하고 눌러도 아무것도 안 한다(또는 첫 화면으로).

## 3. 만들 것

### 3-1. PC 2 — `web/ui`(@pmart/web-ui) 공통 컴포넌트
- `AppLauncher`(이름 자유) — props 는 **지금 앱 이름 하나**(`current: 'backoffice' | 'hr-backoffice' | 'mfc-backoffice' | 'rider-backoffice'`).
- 앱 목록(§1 표)은 **이 컴포넌트 한 곳**에만 둔다(앱마다 따로 두지 않음).
- 주소 만들기: **지금 열린 주소의 프로토콜·호스트 이름 + 앱 포트**.
  - LAN(https): `https://<지금 hostname>[:포트]/` — `192.168.0.40` 으로 열었든 `d603-16` 으로 열었든 같은 호스트로 간다. 443 은 포트를 붙이지 않는다.
  - 개발·목(http, `localhost`): `http://localhost:<5173~5176>/`.
  - 구분은 지금 페이지의 `location.protocol`(또는 이미 있는 LAN 빌드 구분)으로. **코드에 IP 를 적지 않는다.**
- 접근성: 버튼 `aria-label="앱 바꾸기"`, 목록은 키보드로 열고 닫기(Esc), 바깥 클릭 닫기.
- PC 2 의 두 앱(`web/backoffice`·`web/hr-backoffice`) 머리줄에 붙인다.

### 3-2. PC 3 — `web/mfc-backoffice`
- PC 2 의 `web/ui` 변경이 main 에 들어간 뒤, 머리줄에 `<AppLauncher current="mfc-backoffice" />` 한 줄.

### 3-3. PC 5 — `web/rider-backoffice`
- 같은 방식으로 `<AppLauncher current="rider-backoffice" />` 한 줄.

> 한 PR 로 PC 2 가 네 앱에 다 붙이는 것은 하지 않는다 — 앱 화면 주인이 다르다(`pc-split.md` §2: mfc-backoffice PC 3, rider-backoffice PC 5). PC 3·5 는 한 줄이라 PC 2 PR 병합 뒤 바로 붙이면 된다.

## 4. 하지 않을 것

- **로그인·세션·토큰 코드는 건드리지 않는다** — `web/core` 의 `employeeAuth.ts`·`session.ts`·`sessionPersistence.ts`·`tokens.ts`, 각 앱 `src/features/session`·`SessionContext`(로그인 코드는 PC 1 만 — `pc-split.md` §1-3). 메뉴는 링크만 만들고 세션을 읽거나 바꾸지 않는다.
- 같은 출처로 합치기(경로 `/hr`·`/mfc` 등)는 하지 않는다.
- 새 탭 강제, 역할별 숨기기 하지 않는다(§2).

## 5. 시험·확인

- `web/ui` 단위 시험: 4개 다 보임, 지금 앱 표시, 주소 만들기(https `192.168.0.40` → `https://192.168.0.40:8445/`, `d603-16` → `https://d603-16:8446/`, 443 은 포트 없음, http localhost → 5173~5176).
- 웹 공통 규칙대로 **네 앱 빌드·시험을 모두** 돌린다(`web/ui` 를 바꾸면 네 앱이 영향).
- 병합 뒤 PC 1 이 LAN 웹 빌드(`lan.sh publish-backoffice`·`publish-hr-backoffice`·`publish-mfc-backoffice`·`publish-rider-backoffice`).
- 사용자 브라우저 확인: 기본 백오피스 로그인 → 메뉴로 인사팀·MFC·라이더로 이동(비밀번호 안 물음) → 역할 없는 앱은 안내 → 한 곳 로그아웃 → 다른 앱 탭을 다시 보면 로그인 화면.

## 6. 회신

PR 에 이 파일 경로를 적어 주면 된다. 막히는 것(특히 머리줄 위치·LAN 구분 방법)은 `docs/handoff/clients/all/` 에 회신.
