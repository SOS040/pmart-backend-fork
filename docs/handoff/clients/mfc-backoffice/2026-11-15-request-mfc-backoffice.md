# [pmart-backend → pmart-clients] 요청: MFC 백오피스(별도 웹 앱) — 센터 관리자 화면 (2026-11-15)

- 보낸 영역: platform
- 작성: pmart-backend(PC 1), 2026-11-15
- 대상: pmart-clients — **새 웹 앱**(MFC 백오피스, 앱 영역 `mfc-backoffice` — 이 폴더). 기본 백오피스(`web/backoffice`)에 넣지 않는다.
- 근거: 사용자 2026-11-15 — "백오피스 mfc는 떼어내고 고객센터랑 라이더를 기존백오피스에 붙일게". 그래서 11-09 요청서(`../backoffice/2026-11-09-clients-request-backoffice-role-menus.md`)의 **"MFC 메뉴도 같은 백오피스 안에"(§3)는 이 문서로 바뀐다**(같은 날 `../backoffice/2026-11-15-clients-request-backoffice-cs-rider-menus.md` §0 에서 예고).
- 메뉴·API 정본: `pmart-backend/docs/architecture/backoffice-role-menus.md` §3(MFC 메뉴), 각 mfc 서비스 계약 `contracts/openapi/mfc-*.yaml`(생성기로 타입을 만든다).
- **백엔드 상태**: 화면이 쓰는 mfc API 는 대부분 구현·LAN 배포됨. 이 앱의 로그인·서빙 준비(아래 §1 "백엔드가 할 일")는 **아직** — 화면은 계약과 목 서버로 먼저 만들 수 있고, 준비가 끝나면 알림을 보낸다.

## 1. 앱과 로그인 (인사팀 백오피스와 같은 방식)

| 항목 | 값 |
|---|---|
| 로그인 | employee-auth-service, 지금 두 백오피스와 같은 흐름(`web/core` 직원 로그인 — 세션별 HttpOnly refresh 쿠키·Signed Double-Submit CSRF(ADR-0059), 탭이 다시 보일 때 세션 확인(ADR-0061)). **앱 이름 `app=mfc-backoffice`** |
| SSO | 켬(인사팀 앱과 같게 — 같은 브라우저면 한 번 로그인으로 세 백오피스 모두, 어느 쪽 로그아웃도 Keycloak 세션을 끝냄) |
| LAN 주소 | `https://192.168.0.40:8445`(PC 4, 새 nginx — 사설 CA, ADR-0057). `http://…:8082` 는 HTTPS 로 넘기기만. 복귀 `…/auth/callback`, 로그아웃 뒤 `…/login`. 개발 `http://localhost:5175` |
| `X-Source-System` | `mfc-backoffice-web` |
| 볼 수 있는 사람 | 역할 **`MFC_SUPERVISOR`**(센터 관리자) — 자기 `centers` 의 센터만. `centers` 가 둘 이상이면 화면 위에 센터 선택(고른 `mfcId` 로 부른다), 하나면 고르지 않는다. 그 밖 역할(`OPERATOR_ADMIN` 등)이 어디까지 되는지는 **서버가 API 마다 판정**(403) — 화면은 403 을 "이 센터 권한이 없습니다"로 |
| `MFC_WORKER` | 이 앱을 쓰지 않는다(현장은 PDA 앱 `mfc_app`). 로그인하면 "센터 관리자만 쓰는 화면입니다" |

**백엔드가 할 일(PC 1, 앱이 LAN 에 올라가기 전)**: employee-auth-service 앱 `mfc-backoffice`(복귀 주소·SSO·로그아웃 경로·refresh 쿠키), Keycloak 로그아웃 허용 주소, 게이트웨이 CORS 출처(`https://192.168.0.40:8445`·`https://d603-16:8445`), PC 4 서빙(새 nginx 8445, `lan.sh publish-mfc-backoffice <dist>` — 다른 두 백오피스와 따로라 서로 덮지 않는다).

## 2. 메뉴 (상위-하위 묶음 — 기본 백오피스와 같은 모양으로)

| 묶음 | 하위 메뉴 | 내용 | API |
|---|---|---|---|
| 인력 | 작업자 | 등록, 고용 형태·자격·업무, 상태(정지·퇴사), 근무 등록·취소, 휴게, 일일크루 그날 등록 | mfc-capacity `/mfcs/{id}/workers*` |
|  | 배정 | 작업 배정·재배정, 배정 추천(OR-Tools) 계산·확정, 배정 목록 | mfc-capacity `/assignments`, `/assignment-plans*` |
| 작업 | 작업 현황 | 센터 작업 목록·상세, 확인 필요 포장 목록·확인 | mfc-fulfillment `/tasks?mfcId=&state=`, `/packages?mfcId=&checkRequired=true`, `/packages/{id}/handover-checks` |
|  | 이동·보충 | 이동 지시 만들기·취소, 목록 | mfc-inbound `/move-orders*` |
|  | 입고 | 입고 예정 수령, 품질검사(LOT 분리), 적치, 회수품 반입, 공급 요청 | mfc-inbound `/initial-stock-loads*`, `/inbound-tasks*`, `/return-receipts`, 공급 요청 |
| 재고·품질 | 재고 | 센터 재고 조회, 위치·LOT 정정, 순환 실사, 보충 기준, 출하 부적격 LOT, 상품 취급 | mfc-inventory `/mfcs/{id}/stock*`, `/stock-corrections`, `/count-sessions*`, `/replenishment-policies`, `/unshippable-lots`, `/sku-handlings*` |
|  | 품질 | 격리·해제(다른 관리자 승인), 폐기, 회수품 검수 | mfc-quality |
| 센터 | 위치(칸) | 칸 등록·수정·닫기·폐지, 도면 좌표·치수 | mfc-location `/locations*` |
|  | 운영 제한 | 접수 중지·수용 축소 등록·해제 | mfc-capacity `/operation-restrictions*` |

- 묶음 이름·순서는 클라이언트가 정해도 된다(바꾸면 회신 — 정본 표를 맞춘다).
- 이미 있는 화면 요청서는 그대로 쓴다: 입고 B1~B4(`../archive/2026-11-07-clients-request-mfc-inbound-screens.md`), 피킹·포장(`../archive/2026-11-07-clients-request-mfc-picking-packing-screens.md`), 근무 기본값·배정 계획(`../backoffice/2026-11-09-clients-notice-*`).
- 기본 백오피스의 "센터 취급"(`/sku-handlings`, MD)은 그대로 두고, MFC 백오피스 "재고 > 상품 취급"은 센터 관리자용으로 같은 API 를 쓴다.
- 변경 요청 헤더: `Idempotency-Key`(요청마다 새 UUID, 재시도 때 같은 값)·`X-Source-System`, 상태 변경은 `X-Expected-Version`. 오류는 Problem Details(`code`).

## 3. 시험 계정

- 센터 관리자 시험 계정은 PC 1 에 요청(인사 등록 → 권한 승인으로 만든다 — 비밀번호는 파일로 전달, 채팅·로그에 붙이지 않는다). 센터는 LAN 의 MFC 하나(`MFC-…` — 요청 때 알려 줌).

## 4. 정할 것·회신해 줄 것

- **이 앱을 어느 클라이언트 PC 가 만들지**(앱 영역 `mfc-backoffice` 의 담당) — 클라이언트 쪽 `pc-split.md` §1 에 적고 알려 주세요. 후보: PC 3(mfc_app·백엔드 mfc 와 같은 PC) 또는 PC 2(백오피스 영역).
- 앱 폴더 이름(예 `web/mfc-backoffice`)과 개발 포트가 위와 다르면.
- 11-09 요청서로 기본 백오피스에 MFC 화면을 이미 만든 것이 있으면 어디까지인지(지금 `web/backoffice` 에는 없는 것으로 보임 — 2026-11-15 PC 1 확인).
- 화면에 필요한데 API 에 없는 칸·조건 목록.
