# 다른 PC(PC 3 MFC·PC 5 배송)에서 개발할 때 — 직원 인증 바뀐 뒤(2026-11-13)

PC 1 이 아닌 PC 에서 개발하는 사람·Claude 가 먼저 읽는다. 영역(platform·mfc·delivery)·일지·handoff 규칙은 `docs/operations/pc-split.md`. 2026-11-13 직원 로그인 구조(ADR-0056)가 끝까지 바뀌어서 예전 방법이 안 되는 것들이 있다. 배경: `docs/adr/0056-staff-login-employee-auth-service-and-hr-service.md`, `docs/architecture/auth/employee-auth-structure.md`.

## 0. 먼저

- **저장소 위치는 어디든 된다**(드라이브·경로 무관 — 코드는 절대 경로를 쓰지 않는다). 다만 `pmart-backend`·`pmart-clients`·`pmart-mfc-simulation` 을 **같은 부모 폴더의 형제**로 둔다(클라이언트 `gen:api`·시뮬레이터 문서가 `../pmart-…` 를 가정). 예: `D:\pmart-project\pmart-backend`.
- GitHub: `winget install --id GitHub.cli -e` 뒤 **새 터미널**에서 `gh auth login`(두 줄을 따로). 그 PC 의 GitHub 계정(PC 마다 다른 계정)은 저장소 권한이 `triage` 라 **원본에 push 할 수 없다** — **포크에서 PR 을 보낸다**(2026-11-13 조직·저장소 포크 허용: pmart-backend·pmart-clients·pmart-mfc-simulation. pmart-config 는 포크 안 됨 — PC 1 전용). PC 1(admin)이 검토한 뒤 병합한다. 무료 플랜 비공개 저장소라 GitHub main 보호 규칙은 없다 — triage 권한이 사실상 보호 역할.
  ```bash
  gh repo fork code-and-pick/pmart-backend --clone=false          # 처음 한 번 — 내 계정에 비공개 포크
  git remote add fork https://github.com/<내 계정>/pmart-backend.git # 원본(origin)에서 받은 저장소에 포크를 더한다
  git switch -c mfc/<주제>   # 작업 → 커밋(일지 포함)
  git fetch origin && git rebase origin/main
  git push fork mfc/<주제>
  gh pr create --repo code-and-pick/pmart-backend --base main --head <내 계정>:mfc/<주제> --title "..." --body "바꾼 것·실행한 시험·영향 영역·공용 표 갱신 필요"
  ```
  받기는 계속 `git pull origin main`(원본). PR 이 병합되면 원본 main 에 들어온다.
  - PC 1 의 개발은 원본 main 에 바로 들어간다 → 작업 시작·PR 올리기 전에 `git pull origin main` 하고 작업 브랜치는 `git rebase origin/main`. 포크의 main 은 맞출 필요 없다(늘 원본 main 위에 브랜치).
  - PR 이 충돌(같은 파일 — 주로 공용 파일)이면 `git rebase origin/main` 으로 풀고 `git push --force-with-lease fork mfc/<주제>` 로 PR 을 갱신한다.
  - 병합 뒤: `git switch main && git pull origin main`, 다 쓴 브랜치 `git branch -d mfc/<주제>`·`git push fork --delete mfc/<주제>`. PC 1 은 squash 병합(커밋 하나, 작성자 유지).

- **`git pull`(작업 브랜치는 main 위로 rebase)**. 2026-11-13 에 계약·서비스가 크게 바뀌었다:
  - auth-service 운영자 로그인·계정 관리 **삭제** — `/api/v1/auth/operator-logins`·`/auth/password-changes`·`/operator-accounts/**`, 계약 `OperatorAccount` 등. 이걸 부르는 코드·스크립트는 깨진다.
  - 새 서비스 hr-service(8512)·mail-provisioner, 새 사건 `GrantRequested`, `SubjectBlocked` 에 `sessionId`(로그아웃 즉시 차단).
- 배포는 지금처럼 **PC 1 만**(`lan.sh`·Jib push·`.secrets`). 다른 PC 는 PR.

## 1. 직원(운영자) 토큰 — 어디서 받나

직원 토큰(`typ=OPERATOR`, `roles`, `centers`)을 내는 곳은 **employee-auth-service 하나**다. auth-service 는 고객·비회원만.

| 상황 | 방법 |
|---|---|
| 단위·통합 시험 | 그대로 — `common-test` `TestJwt.operator(...)` 시험 키(바뀐 것 없음) |
| **로컬 실행**(local 프로필, `bootRun`) | Keycloak 이 없다. employee-auth-service 를 local 로 띄우고 **`POST http://localhost:8511/api/v1/internal/dev-tokens`** 본문 `{"operatorId":"OP-…","roles":["MFC_WORKER"],"centers":["MFC-…"]}`, 헤더 `Idempotency-Key`·`X-Source-System`·`X-Device-Id` → 직원 토큰(계정이 없으면 만든다). 파이썬: `deploy/lan/checks/staff_auth.py` 의 `local_staff_token(op, roles, centers)`. local 프로필만 켜져 있고 LAN·운영에는 경로가 없다(404) |
| **LAN**(실제 배포본에 붙어 시험) | `deploy/lan/checks/staff_auth.py` 의 `staff_token(username, roles=None, centers=None)` — Keycloak(pmart-dev-cli) 비밀번호 로그인 → employee-auth-service 시험용 교환(`http://192.168.0.101:8511/api/v1/internal/dev-token-exchange`). 비밀번호 파일이 필요하다(§2) |

토큰은 15분. 갱신이 필요 없으면 다시 받는다. 토큰·비밀번호는 출력·커밋하지 않는다.

## 2. LAN 시험 계정과 비밀번호 파일

`.secrets` 는 git 에 없다(PC 1 `deploy/lan/.secrets/`). 다른 PC 에서 LAN 시험을 하려면 **필요한 파일만 같은 경로(`deploy/lan/.secrets/`)** 로 받아 둔다(사용자가 복사). 저장소 루트 `.secrets/` 에 두지 않는다(2026-11-13 PC 3 에서 그렇게 복사돼 커밋될 뻔함 — 이제 `.gitignore` 가 루트 `/.secrets/` 도 막는다).

| 파일(`deploy/lan/.secrets/`) | 계정(Keycloak 아이디) | 역할·센터(employee-auth-service) | 쓰는 곳 |
|---|---|---|---|
| `keycloak-claude-dev-password` | `claude-dev` | `OPERATOR_ADMIN` | 관리자 시험, 다른 시험 계정의 역할 맞춤(`staff_token(..., roles=...)`) |
| `keycloak-claude-mfc-sup-password` | `claude-mfc-sup` | `MFC_SUPERVISOR` · `MFC-SEED-01` | MFC 센터 관리자(`mfc_e2e.py`·`seed/mfc_layout.py`) |
| `keycloak-claude-mfc-wrk-password` | `claude-mfc-wrk` | `MFC_WORKER` · `MFC-SEED-01` | MFC 작업자(시뮬레이터 작업자 토큰, `mfc_e2e.py`) |
| `keycloak-claude-approver-password` | `claude-approver` | 승인 역할들 + `HR_ADMIN` | 인사팀 시험(직원 등록) |

- **`keycloak-admin-password`(Keycloak 전체 관리자)는 다른 PC 로 옮기지 않는다.** 이 파일이 없고 계정 비밀번호 파일도 없으면 `staff_token` 이 멈춘다(그 계정은 PC 1 에서만).
- 작업자 ID(MFC `workerId` 등) = 운영자 ID(`OP-…`) = 토큰 `sub`. `claude-mfc-wrk` = `OP-d696814f-4cd7-4263-86a6-032b591e5b42`.
- 배송 쪽 직원 시험 계정은 따로 없다 — 필요하면 PC 1 에 요청(인사팀 등록으로 만든다).

## 3. 새 시험 직원 만들기

- **인사팀 백오피스**(`http://192.168.0.40:8081`, HTTPS `https://192.168.0.40:8444`) 또는 hr-service API(`POST /api/v1/hr/employees`, `HR_ADMIN`)로 등록한다. 사서함·Keycloak 계정·비밀번호 설정 메일이 자동으로 생긴다.
- 요청 권한: 소속 센터의 `MFC_WORKER` 는 자동 승인, 그 밖은 승인함(`OPERATOR_ADMIN`)에서 승인.
- 예전 `/operator-accounts` 로 만드는 방법은 없다.

## 4. 그 밖에 바뀐 것

- **로그아웃하면 그 세션의 access token 이 바로 401 `SUBJECT_BLOCKED`**(게이트웨이 Redis). 정지·퇴사도 즉시 401.
- Keycloak 토큰은 PMart API 에서 401(발급자 표에 없음) — 반드시 employee-auth-service 토큰을 쓴다. Keycloak 역할·`centers` 속성은 지웠다(권한 정본 = employee-auth-service).
- HTTPS: 게이트웨이 `https://192.168.0.40:8443`, 백오피스 `https://192.168.0.40`, 인사팀 `https://192.168.0.40:8444`. 루트 CA `deploy/lan/tls/pmart-lan-root-ca.crt`(PC 2~5 Windows 에는 설치돼 있음). 스크립트는 `cafile` 로 검증, Windows curl 은 `--ssl-revoke-best-effort`.
- MFC 시뮬레이터: 작업자 토큰은 §1·§2(`claude-mfc-wrk`)로 받아 붙여 넣는다(15분).
- PDA 실기기: LAN 루트 CA 를 기기에 설치해야 직원 로그인(Keycloak 화면)이 열린다.
- 라이더 로그인(rider-auth-service)·고객 로그인(auth-service)은 바뀐 것 없음(로그아웃 즉시 차단만 추가).
