# [pmart-backend → pmart-clients] 요청: DC 백오피스(별도 직원 웹) + 앱 바꾸기 메뉴에 "DC 백오피스" (2026-11-25)

- 보낸 영역: platform(PC 1 — cdc 영역도 PC 1)
- 대상: pmart-clients 웹 — **새 앱 `web/cdc-backoffice`**(제안 이름) + **`web/ui` `AppLauncher`(PC 2)** 한 줄
- 근거: 사용자 2026-11-25 "Dc 백오피스 만들어줘. Mfc나 라이더처럼 별도의 페이지를 갖고 있지만 통합 백오피스에서 sso로 클릭해서 이동할 수 있게 지금 이동 버튼 안에 dc 백오피스 이동 버튼 만들어줘" + 메뉴 17묶음
- 계획(메뉴 ↔ 백엔드 API 표·단계): **`docs/cdc/cdc-backoffice-plan.md`** — 먼저 읽어 주세요
- 본보기: `web/rider-backoffice` 뼈대(2026-11-17 — 로그인·조용한 확인·세션·메뉴 자리)

## 1. 백엔드 상태
- **서빙·로그인 준비(PC 1)**: PC 4 `cdc-backoffice-web` — **https://192.168.0.40:8447**(http 8085 → 8447), 빌드 올리기 `deploy/lan/lan.sh publish-cdc-backoffice <dist>`, 빌드 전엔 안내 페이지. employee-auth 앱 이름 **`cdc-backoffice`**(SSO 켬·무활동 12시간·LAN refresh 쿠키), 돌아오는 주소 `http://localhost:5177/auth/callback`·`https://192.168.0.40:8447/auth/callback`·`https://d603-16:8447/auth/callback`, Keycloak 로그아웃 주소 `…:8447/login`, 게이트웨이 CORS 8447.
  - 상태: **LAN 배포 끝**(2026-11-25 — pmart-config d90b951, main 600efb98). https://192.168.0.40:8447 은 빌드 전 안내 페이지, 로그인 시작 302 확인.
- 쓰는 사람: `CDC_SUPERVISOR`(토큰 centers 의 DC 만 — LAN `claude-cdc-sup`·`CDC01`), `OPERATOR_ADMIN`(모든 DC). `CDC_WORKER` 는 PDA 라 대상 아님 — 역할이 없으면 다른 직원 웹처럼 "권한이 없어요" 안내.

## 2. 만들 것

### 2-1. `web/cdc-backoffice` 뼈대(0단계)
- `web/rider-backoffice` 와 같은 구조: 로그인(employee-auth 앱 `cdc-backoffice`)·조용한 확인·세션·`build:lan`·개발 포트 **5177**·`version.txt`.
- 머리줄: DC 고르기(토큰 centers 중 DC — 지금 `CDC01` "칠곡 DC" 하나, OPERATOR_ADMIN 은 center-master 의 DC 목록), 앱 바꾸기 메뉴(`<AppLauncher current="cdc-backoffice" />`), 로그아웃.
- 왼쪽 메뉴 자리(계획서 §2 순서): 대시보드·관제 / 기준정보 / 입고 / 재고 / 보충 / TRF·Wave / 통합 작업관리 / 피킹·분배(FC·DPS·AutoStore·분배·DAS·Wheel Sorter·자키 **탭** — 작업 화면만) / 출고 / 출하·운송 / 반품·회수 / 작업자·인력 / 리포트·KPI / 시스템관리. API 가 없는 메뉴는 "준비 중"(지어낸 값·목 숫자 보이지 않게).
  - **빼는 것(2026-11-25 사용자 "설비센서자산 같은 건 제외")**: 시설·설비(설비 관제·장애·유지보수), 환경·품질(온도·습도·HACCP), 물류 자산(R/T·팔레트·지게차) — 메뉴 자리도 두지 않는다. DPS·AutoStore·Wheel Sorter 탭에 설비 상태·표시등·로봇 칸을 두지 않는다. 대시보드의 "설비 가동 현황"·"공간 점유율"·"차량 입출차" 칸도 뺀다.
- 스타일·용어는 다른 직원 웹과 같게(`web/ui` 콘솔 테마).

### 2-2. 1단계 화면(지금 있는 API — 계획서 §2 "있음")
- **통합 작업관리·TRF·Wave**: cdc-outbound `GET /waves`·`/waves/{id}`·`POST /waves`·`…/releases`, `GET /cdcs/{cdcId}/tasks`·`…/{taskId}`, `GET /cdcs/{cdcId}/regions`, 미출발 취소 `POST /cdcs/{cdcId}/transfers/{trfId}/pre-departure-cancellations`.
- **기준정보**: cdc-inventory `GET /cdcs/{cdcId}/sku-handlings`, cdc-location `GET /cdcs/{cdcId}/locations`·`…/{locationCode}`·상태 변경 `…/state-changes`·`GET /cdcs/{cdcId}/aisle-pick-methods`.
- **피킹·분배 FC 탭·출고 실적**: 위 Task + `GET /work-results/{id}`.
- **새 조회(2026-11-25 PC 1 만듦)**: TRF 관리 cdc-outbound 0.11.0 `GET /cdcs/{cdcId}/transfers?state=&mfcId=&page=&size=`(기한 순, 줄 합 수량)·`GET /cdcs/{cdcId}/transfers/{trfId}`(줄별 승인·할당·피킹·부족), 대시보드 `GET /cdcs/{cdcId}/outbound-summary?date=`(그날 기한 TRF 상태별 수·수량 합·진행률·Wave 상태별·작업 종류:상태별 — 입고는 서비스가 없어 없음), 재고 현황 cdc-inventory 0.6.0 `GET /cdcs/{cdcId}/stock-snapshot?sku=&withPlaces=`(SKU별 현물·가용·예약, withPlaces=true 면 LOT·위치별). 화면 순서(사용자 추천대로): 작업관리·Wave → 기준정보 → 재고 → 대시보드.
- 모든 호출은 게이트웨이 https://…:8443/api/v1 + 직원 토큰(지금 MFC·라이더 백오피스와 같음). 화면에 필요한데 없는 조회(TRF 목록·재고 현황·대시보드 집계 등)는 회신으로 알려 주면 PC 1 이 API 를 만든다.

### 2-3. PC 2 — `web/ui` `AppLauncher`
- 목록에 한 줄: `{ app: 'cdc-backoffice', name: 'DC 백오피스', lanPort: 8447, devPort: 5177 }`(아이콘은 창고·팔레트 모양 자유). `StaffApp` 타입·`appNames` 메시지·시험(https `d603-16` → `https://d603-16:8447/`, dev → `http://localhost:5177/`)에도.
- 2026-11-17 결정 그대로: 모든 직원에게 보임(역할로 숨기지 않음), 같은 탭, 링크만. 통합 백오피스에서 누르면 SSO 로 비밀번호 없이 들어간다.
- `web/ui` 를 바꾸면 직원 웹 앱 모두 빌드·시험(웹 공통 규칙).

## 3. 하지 않을 것
- 로그인·세션·토큰 공통 코드(`web/core` employeeAuth·session·tokens) 바꾸기 — 앱 이름 `cdc-backoffice` 만 넘긴다.
- 지어낸 숫자·목 데이터를 실서버 화면에 보이기(대시보드·KPI·설비·센서는 API 가 생길 때까지 "준비 중").

## 4. 병합 뒤
- PC 1 이 LAN 웹 빌드·`lan.sh publish-cdc-backoffice`, 직원 웹 전부 다시(`AppLauncher` 변경). `lan.sh web-sync` 대상에 `cdc-backoffice` 를 더한다(PC 1).
- 회신은 이 문서 옆 `…-reply-…` 로.
