# DC 백오피스 계획 (2026-11-25)

- 작성: PC 1(platform·cdc), 2026-11-25
- 계기: 사용자 2026-11-25 "Dc 백오피스 만들어줘. Mfc나 라이더처럼 별도의 페이지를 갖고 있지만 통합 백오피스에서 sso로 클릭해서 이동할 수 있게 지금 이동 버튼 안에 dc 백오피스 이동 버튼 만들어줘" + 메뉴 17묶음(대시보드·관제 ~ 시스템관리, 원문은 대화 — 아래 표가 요약).
- 본보기: 라이더 백오피스(2026-11-17, `docs/delivery/rider-backoffice-plan.md`·커밋 00b0a5ae), MFC 백오피스(2026-11-15).

## 1. 모양 (MFC·라이더 백오피스와 같게)
| 항목 | 값 |
|---|---|
| 웹 앱 | pmart-clients `web/cdc-backoffice`(제안 이름), `npm run build:lan` → `deploy/lan/lan.sh publish-cdc-backoffice <dist>` |
| LAN 주소 | PC 4 **https://192.168.0.40:8447** (http 8085 → 8447 넘김). 개발 `http://localhost:5177` |
| 로그인 | employee-auth 앱 `cdc-backoffice`(SSO 켬·무활동 12시간·LAN refresh 쿠키 — 다른 직원 웹과 같음). 통합 백오피스에서 이미 로그인했으면 조용한 로그인으로 바로 들어간다 |
| 이동 버튼 | 직원 웹 공통 앱 바꾸기 메뉴(⋮⋮⋮, `web/ui` `AppLauncher`)에 **"DC 백오피스"** 한 줄 — 보이는 역할 `CDC_SUPERVISOR`·`OPERATOR_ADMIN`, 같은 탭으로 8447 |
| 쓰는 사람 | `CDC_SUPERVISOR`(센터 관리자 — 토큰 centers 의 DC 만, LAN `claude-cdc-sup`·CDC01), `OPERATOR_ADMIN`(모든 DC). 현장 작업자 `CDC_WORKER` 는 PDA(시뮬레이터) — 백오피스 대상 아님 |
| 센터 고르기 | 머리줄에서 DC 고르기(지금 칠곡 `CDC01` 하나) — 모든 화면 경로가 `/cdcs/{cdcId}/…` |

## 2. 메뉴 17묶음 ↔ 지금 백엔드
상태: **있음**(LAN 동작 API) · **계약만**(계약은 있고 서비스 없음) · **없음**(계약도 없음 — 설계·결정 필요).

| # | 메뉴 | 지금 백엔드 | 상태 |
|---|---|---|---|
| 1 | 대시보드·실시간 관제 | 입출고 진행 집계·층/구역 현황·병목·KPI 집계 API 없음. 재료: cdc-outbound Wave·Task, cdc-location 위치 | 없음(집계 API 필요) |
| 2 | 기준정보 — 상품·로케이션 | cdc-inventory `GET /cdcs/{id}/sku-handlings`(SKU 취급·온도대), cdc-location `GET /cdcs/{id}/locations`·`…/{code}`·상태 변경·`aisle-pick-methods` | **있음**(보기·위치 상태) |
| 2 | 기준정보 — 거래처·차량·기사·노선·설비·정책 | fleet·transport-routing 계약만, 거래처(공급업체)는 SCM 범위 밖, 설비 없음 | 계약만/없음 |
| 3 | 입고관리(ASN·검수·적치) | cdc-inbound 계약(`/asn`·`/putaway-tasks`·`/replenishment-moves`) | 계약만 |
| 4 | 재고관리(현황·원장·이동·실사·품질) | cdc-inventory 는 내부 경로만(`/internal/availability`·`snapshots`·`adjustments`…) — 직원 조회 API 없음. 품질은 cdc-quality 계약(`/holds`·`/recalls`·`/disposal-approvals`) | 일부(직원 조회 API 필요) |
| 5 | 보충관리 | cdc-inbound `replenishment-moves` 계약 | 계약만 |
| 6 | TRF·Wave | cdc-outbound `GET/POST /waves`·`…/releases`, `/cdcs/{id}/tasks`, `/cdcs/{id}/regions`, 미출발 취소 | **있음**(TRF 목록 직원 조회는 내부 경로만) |
| 7 | 통합 작업관리 | cdc-outbound Task 목록·상세·착수·피킹 실적·다음 작업 | **있음**(재배정·보류·우선순위 변경 없음) |
| 8 | 피킹·분배(FC·DPS·AutoStore·분배·DAS·Wheel Sorter·자키) — 한 메뉴 탭 | FC 피킹 Task·묶음 피킹(지역)만. 분류·검품·상차 실적 경로 있음. 설비(DPS·AutoStore·소터) 연동 없음 | FC **있음**, 나머지 없음 |
| 9 | 출고관리(검품·LPN·출고 대기) | cdc-outbound `inspection-results`·`loading-results`·`sorting-results`, 출발 준비 | 일부 |
| 10 | 출하·운송 | transport-planning·execution·fleet 계약, 출발 검증은 cdc-outbound 내부 | 계약만 |
| 11 | 반품·회수 | 없음(cdc-quality 폐기 승인 계약만) | 없음 |
| 12 | 물류 자산(R/T·팔레트) | 없음 | 없음 |
| 13 | 작업자·인력 | 직원 계정·권한은 employee-auth·hr. 근무조·배치·생산성 없음 | 일부(계정만) |
| 14 | 시설·설비 | 없음(WCS 연동 없음) | 없음 |
| 15 | 환경·품질(온도·HACCP) | 없음(센서 연동 없음) | 없음 |
| 16 | 리포트·KPI | 없음 | 없음 |
| 17 | 시스템관리 | 권한 = 기존 백오피스 HR·권한 승인, 감사 로그 = audit. 바코드·PDA·연동 상태 없음 | 일부(기존 화면으로) |

## 3. 단계 (추천)
1. **0단계 — 뼈대·로그인·이동 버튼(바로)**: PC 1 서버 몫(employee-auth 앱·Keycloak 로그아웃 주소·게이트웨이 CORS 8447·PC 4 서빙·publish 명령) + 클라이언트 뼈대(로그인·조용한 확인·DC 고르기·17묶음 메뉴 자리 — 준비 중 표시) + 앱 바꾸기 메뉴 한 줄.
2. **1단계 — 지금 있는 API 화면**: 기준정보(상품·로케이션), TRF·Wave, 통합 작업관리, 피킹·분배 FC 탭, 출고 실적. 이 단계에 필요한 서버 추가: 직원용 TRF 목록 조회(cdc-outbound), 재고 현황·원장 직원 조회(cdc-inventory), 대시보드 입출고 진행 집계 1개.
3. **2단계 — 계약만 있는 서비스 구현 뒤**: 입고·보충(cdc-inbound), 재고 품질(cdc-quality), 운송(transport-*·fleet).
4. **3단계 — 새 설계가 필요한 것**: 설비·WCS(DPS·AutoStore·소터), 환경 센서, 물류 자산, 반품·회수, 인력 생산성, KPI 리포트 — 업무 원문(`docs/business/WMS-business-logic.md`)에 없는 규칙은 사용자 결정.

## 4. 결정이 필요한 것 (사용자)
- 1단계 화면 순서(추천: 작업관리·Wave → 기준정보 → 재고 → 대시보드).
- 3단계 항목을 이번 범위에 넣을지(설비·센서·자산은 실제 장비가 없어 시뮬레이터 값으로만 보일 수 있다).
