# [pmart-backend → pmart-clients] 요청: 라이더 백오피스(별도 웹 앱) — 뼈대·직원 로그인 (2026-11-17)

- 보낸 영역: platform(PC 1)
- 대상: pmart-clients — **새 웹 앱**(라이더 백오피스, 앱 영역 `rider-backoffice` — 이 폴더). 기본 백오피스(`web/backoffice`)에 넣지 않는다.
- 근거: 사용자 2026-11-17 — "라이더 백오피스 새로 만들어야 할 것 같아서, 관제까지 넣을 거야. 로그인 기능 똑같이", "관제 화면 같은 건 … 앱 뼈대랑 로그인만", "라이더 백오피스는 PC5로".
- 담당: **뼈대·로그인 = PC 1 클라이언트 세션**(로그인·세션 코드는 PC 1 만 — `pc-split.md` §1-3, mfc-backoffice 와 같은 순서), **메뉴·업무 화면(관제 포함) = PC 5**(delivery 영역 — 화면과 배송·라이더 API 가 같은 PC). 웹 공통(`web/core`·`web/ui`)은 PC 2.
- **백엔드 상태(2026-11-17): 로그인·서빙 준비 끝, LAN 반영** — `https://192.168.0.40:8446` 열림, employee-auth 앱 `rider-backoffice` 로그인 시작 확인(backend 00b0a5ae, pmart-config 4e55a0e). 확인: 로그인 시작 302(192.168.0.40·d603-16), 남의 복귀 주소 400, silent 302, CORS 8446 허용.
- **이미 들어간 커밋 — 클라이언트가 이어받음(사용자 2026-11-17 "그거 이어서 받기로 했어")**: 백엔드 세션이 규칙을 어기고 pmart-clients main 에 뼈대를 직접 넣었다(**756db67** `feat(rider-backoffice): 라이더 백오피스 뼈대`, LAN 8446 에 그 빌드 `index-CW6GFx5O.js`). 되돌리지 않고 **PC 1 클라이언트 세션이 이 커밋을 검토·고쳐 자기 것으로 이어받는다** — 아래 §2 범위와 맞는지, 로그인·세션 부분(mfc-backoffice 복사)이 지금 web/core 와 맞는지 보고, 고칠 것은 클라이언트가 고친다. 백엔드 세션은 앞으로 pmart-clients 에 쓰지 않는다(handoff 만).

## 1. 앱과 로그인 (MFC 백오피스와 같은 방식)

| 항목 | 값 |
|---|---|
| 로그인 | employee-auth-service, 지금 백오피스들과 같은 흐름(`web/core` 직원 로그인 — 세션별 HttpOnly refresh 쿠키·Signed Double-Submit CSRF(ADR-0059), 탭이 다시 보일 때 세션 확인(ADR-0061), 처음 열 때 조용한 로그인 확인). **앱 이름 `app=rider-backoffice`** |
| SSO | 켬(다른 백오피스와 같게 — 같은 브라우저면 한 번 로그인으로 모두, 어느 쪽 로그아웃도 Keycloak 세션을 끝냄) |
| LAN 주소 | `https://192.168.0.40:8446`(PC 4 nginx — 사설 CA, ADR-0057). `http://…:8084` 는 HTTPS 로 넘기기만. 복귀 `…/auth/callback`, 로그아웃 뒤 `…/login`. 개발 `http://localhost:5176` |
| API | 게이트웨이 `https://192.168.0.40:8443/api/v1`(LAN 빌드) — CORS 출처 8446·d603-16:8446·localhost:5176 허용 |
| `X-Source-System` | `rider-backoffice-web` |
| 볼 수 있는 사람 | **`RIDER_OPS`**(라이더 운영 — 지원센터 겸함, 모든 센터), `OPERATOR_ADMIN`. 센터 고르기 없음(모든 센터 역할). 그 밖 역할은 막지 않고 안내만 — 판정은 서버(API 마다 403) |

**백엔드가 한 일(PC 1, 2026-11-17 끝)**: employee-auth 앱 `rider-backoffice`(복귀 주소·SSO·로그아웃 경로·LAN refresh 쿠키), Keycloak 로그아웃 허용 주소(파일 + 운영 중 관리 API), 게이트웨이 CORS, PC 4 서빙(새 nginx 8446, `lan.sh publish-rider-backoffice <dist>` — 다른 백오피스와 따로라 서로 덮지 않는다).

## 2. 뼈대 범위 (PC 1 클라이언트 세션)

- 새 npm workspace `web/rider-backoffice` — mfc-backoffice 처럼 로그인 화면·복귀(`/auth/callback`)·내 세션(로그아웃)·메뉴 자리. 목 모드(MSW) 목 계정: RIDER_OPS·OPERATOR_ADMIN·그 밖 역할 하나.
- 메뉴 자리(이름·묶음은 PC 5 가 바꿔도 된다, 모두 "준비 중"):

| 묶음 | 메뉴 | 비고 |
|---|---|---|
| 관제 | 라이더 관제 | 직원용 라이더 위치 목록 API 가 아직 없다(delivery-tracking — 백엔드 PC 5) |
| 라이더 | 라이더 찾기 | rider-service 직원 API 있음 |
| 배송·배차 | 배송 찾기 | delivery-execution·dispatch 직원 API 있음 |
| 지원센터 | 접수 건 | case-service |

- 공통 손볼 곳: ESLint project 목록, `.gitignore`(generated), 앱 문서·`pc-split.md`(영역 표 — mfc-backoffice 는 2026-11-17 PC 3, rider-backoffice PC 5).

## 3. 그 뒤 (PC 5)

- 메뉴·업무 화면과 관제. 계획·남은 사용자 결정(기본 백오피스의 라이더 운영 화면을 옮길지 등): `pmart-backend/docs/delivery/rider-backoffice-plan.md`.
- LAN 반영: 담당 PC 는 PR 만. **빌드·올리기는 PC 1**(병합한 main 에서 `npm run build:lan -w web/rider-backoffice` → `lan.sh publish-rider-backoffice`). PC 4 에 직접 복사하지 않는다.

## 회신

뼈대가 들어가면 이 폴더 회신(`2026-11-17-reply-…` 또는 그 뒤 날짜)으로: 커밋, 시험 결과, LAN 에 올릴 빌드가 있으면 그 커밋.
