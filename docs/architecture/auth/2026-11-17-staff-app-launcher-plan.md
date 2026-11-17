# 직원 웹 앱 바꾸기 메뉴(앱 런처) 계획 — 2026-11-17

보낸 영역: platform(PC 1). 상태: **계획**(요청서·구현 전, 사용자 확인 대기).

## 1. 무엇을 하려는가

구글 오른쪽 위 점 9개(⋮⋮⋮) 메뉴처럼(참고 화면 [`2026-11-17-google-app-launcher.png`](2026-11-17-google-app-launcher.png)), 한 번 로그인한 직원이
**비밀번호를 다시 넣지 않고** 직원 웹 앱 사이를 메뉴 하나로 오가게 한다.

대상 앱(모두 PC 4 nginx, https):

| 앱 | `app` 이름 | LAN 주소 | 개발 주소 | 주로 쓰는 역할 |
|---|---|---|---|---|
| 기본 백오피스 | `backoffice` | https://192.168.0.40 | :5173 | OPERATOR_ADMIN, CS_AGENT, 상품·승인 담당 |
| 인사팀 백오피스 | `hr-backoffice` | https://192.168.0.40:8444 | :5174 | HR_ADMIN 등 인사 역할 |
| MFC 백오피스 | `mfc-backoffice` | https://192.168.0.40:8445 | :5175 | MFC_SUPERVISOR |
| 라이더 백오피스(2026-11-17 뼈대 — 화면 PC 5) | `rider-backoffice` | https://192.168.0.40:8446 | :5176 | RIDER_OPS |

## 2. 지금 이미 되는 것 (확인 2026-11-17)

| 구글 | 우리 | 근거 |
|---|---|---|
| 한 번 로그인 → 모든 앱 | 됨 — Keycloak SSO | employee-auth-service `pmart.employee-auth.apps.*.sso: true`(세 앱 모두) |
| 다른 앱을 열면 로그인 화면 없이 들어감 | 됨 — 조용한 로그인 확인 | `web/core/src/employeeAuth.ts` `beginSilentEmployeeLogin` → `GET /employee-auth/logins?silent=true` → Keycloak `prompt=none`(`EmployeeLoginService`). 세션 없으면 `?error=LOGIN_REQUIRED` → 로그인 버튼 |
| 한 곳 로그아웃 → 전부 로그아웃 | 됨 | Keycloak 세션 종료(idpLogoutUri) + 다른 앱 세션 끊기(ADR-0061), 탭이 다시 보일 때 확인 |
| 앱 바꾸기 메뉴 | **없음** | 세 웹 앱 코드에 다른 앱으로 가는 링크 없음 — 주소를 직접 친다 |

→ **새로 할 일은 웹 화면의 메뉴뿐이고, 로그인·백엔드는 바꾸지 않는다.**

## 3. 구글과 다른 점 (설계상 받아들임)

1. **처음 열 때 한 번 화면이 넘어간다.** 앱마다 출처(포트)가 달라 토큰·쿠키를 나눠 쓰지 않는다(앱별 PMart 세션 — ADR-0056·0059). 처음 열면
   게이트웨이 → Keycloak → 앱 `/auth/callback` 으로 1초 안팎 다녀온다. 비밀번호는 묻지 않는다.
   - 같은 출처 하나(경로로 나눔 `/hr`, `/mfc` …)로 합치면 이것도 없어지지만, 쿠키·CSP·배포를 다 바꿔야 해서 **하지 않는다**.
2. **조용한 확인은 탭마다 한 번.** 실패하면(Keycloak 세션 없음·만료 12시간) 그 탭에선 로그인 버튼이 보인다 — 정상.
3. **메뉴는 편의일 뿐 권한이 아니다.** 메뉴에 안 보여도 주소를 치면 열리고, 열려도 API 는 서버가 역할로 판정한다.

## 4. 만들 것

### 4-1. 웹 공통 메뉴 (pmart-clients `web/ui`, PC 2 웹 세션)
- 머리줄 오른쪽에 ⋮⋮⋮ 버튼 → 앱 목록(아이콘·이름). 지금 앱은 표시만, 누르면 **같은 탭**에서 그 앱 첫 화면으로(새 탭은 휠클릭·Ctrl).
- **보일 앱 = 내 역할로 쓸 수 있는 앱**: 이미 받는 `GET /employee-auth/sessions/current` 의 역할로 판단(아래 표). 쓸 수 있는 앱이 지금 앱 하나뿐이면 버튼을 숨긴다.
- 앱 주소는 빌드 설정(`VITE_…`, 로컬·LAN 값)으로 받는다 — 코드에 IP 를 적지 않는다.
- **로그인·세션 코드(`employeeAuth.ts`·`session.ts` 등)는 건드리지 않는다**(로그인 코드는 PC 1 만 — `pc-split.md` §1-3). 메뉴는 링크와 역할 읽기만.
- 세 앱(+라이더) 빌드를 같이 확인한다(웹 공통 규칙).

역할 → 앱 표(초안, 사용자 확인 필요):

| 역할 | backoffice | hr-backoffice | mfc-backoffice | rider-backoffice |
|---|---|---|---|---|
| OPERATOR_ADMIN | ○ | ○ | ○ | ○ |
| HR_ADMIN(인사 역할) | | ○ | | |
| CS_AGENT | ○ | | | |
| RIDER_OPS | ○(사건함 등 — 라이더 앱으로 옮기기 전까지) | | | ○ |
| MFC_SUPERVISOR | ○(실시간 배송·배차 실패 자기 센터) | | ○ | |
| 상품·가격·승인 담당 | ○ | | | |

정확한 역할 목록은 `docs/architecture/auth/staff-roles.md` 기준으로 맞춘다. 표는 웹 공통 한 곳에 둔다(앱마다 따로 두지 않음).

### 4-2. 백엔드 (PC 1)
- **바꿀 것 없음.** 라이더 백오피스를 만들 때만: employee-auth-service `apps.rider-backoffice`(sso: true), Keycloak post-logout URI, 게이트웨이 CORS, PC 4 배포 —
  라이더 백오피스 계획의 1단계와 같은 일. `sso: true` 를 빠뜨리면 조용한 확인이 거절된다(`silent && !sso` — `EmployeeLoginService`).

## 5. 순서

1. 사용자 확인: §6 질문
2. 이 계획을 요청서로 — `docs/handoff/clients/all/2026-11-17-request-app-launcher.md`(웹 세션 PC 2)
3. 웹 세션 구현(목 모드 시험) → PR → PC 1 병합 → `lan.sh publish-backoffice`·`publish-hr-backoffice`·`publish-mfc-backoffice`
4. 확인(사용자 브라우저): 기본 백오피스 로그인 → 메뉴로 MFC·인사팀 이동(비밀번호 안 물음) → 한 곳 로그아웃 → 다른 탭 다시 보면 로그인 화면
5. 라이더 백오피스가 생기면 메뉴 표에 한 줄 추가

## 6. 정할 것

1. 역할 → 앱 표(§4-1)가 맞는가 — 특히 OPERATOR_ADMIN 은 모든 앱, RIDER_OPS 는 기본 백오피스도 보이게 둘지.
2. 메뉴 클릭 시 같은 탭(추천, 구글과 같음) / 새 탭.
3. 라이더 백오피스와 같이 할지(추천: 메뉴 먼저 세 앱으로, 라이더는 생길 때 한 줄 추가).
