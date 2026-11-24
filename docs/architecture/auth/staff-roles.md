# 직원 역할(ROLE) 정리 (2026-11-16)

PMart 직원(운영자) 역할 13개가 무엇이고, 누가 받고, 어느 화면·API 를 열어 주는지 한곳에 모은 안내서다. 고객·라이더는 역할이 아니라 **토큰 종류**(`typ=CUSTOMER`·`RIDER`)로 나뉜다 — 이 문서는 직원 토큰(`typ=OPERATOR`)만 다룬다.

- **전체 권한(고객·라이더·서비스 포함, 서비스별, 빈틈 목록)은 `permissions.md`**(2026-11-17).
- **정본**: 역할 목록 = 설정 저장소 `employee-auth-service.yml` 의 `pmart.employee-auth.roles`(순서)·`role-info`(표시 이름·센터 지정). 이 밖의 역할은 저장되지 않는다(ADR-0056 결정 4·D3). 화면은 `GET /api/v1/employee-auth/roles`(직원이면 누구나)로 같은 목록을 받는다.
- **누가 무엇을 할 수 있는지의 정본은 각 서비스 코드**다(서비스마다 `Access`·`Actors`·`requireAny` 등). 이 문서는 그 요약이라, 서비스 규칙(`services/<서비스>/CLAUDE.md`)이 바뀌면 같이 고친다.
- 직원 한 명의 역할·권한 센터는 **employee-auth-service DB 의 grant(권한 부여)** 에 있다. Keycloak 에는 로그인 정보만 있고 역할은 없다(2026-11-13 Realm 역할 삭제). 토큰의 `roles`·`centers` 는 로그인·갱신 때 지금 유효한 grant 로 채운다.
- 관련: ADR-0056(직원 인증·인사 구조), `employee-auth-structure.md`, `keycloak-manage-users-and-grant-revocation.md`(권한 회수 구조), `docs/business/flows/employee-account-provisioning.md`(BO-01 권한 흐름), 업무 원문 `docs/business/BackOffice-business-logic.md` BO-01.

## 1. 역할 13개

| 코드 | 표시 이름 | 센터 지정 | 하는 일(요약) | 주로 쓰는 앱 |
|---|---|---|---|---|
| `OPERATOR_ADMIN` | 운영 관리자 | 아니오(모든 센터) | 본사 운영 전반 — 모든 센터 조회, 처리 대기(BO-12), 직원 권한 요청 **승인**, 관리자 권한 회수, 개인정보 열람 기록 조회, 사건함 다른 사람에게 배정 | 백오피스 |
| `HR_ADMIN` | 인사 관리자 | 아니오 | 직원 등록·인사 정보·정지/퇴사, 권한 **요청**(승인은 못 함)·지금 권한 보기·회수(관리자 권한 제외) | 인사팀 백오피스 |
| `CS_AGENT` | 고객센터 상담원 | 아니오(모든 센터) | 고객 문의 답변, 직원용 주문 검색·개인정보 원문 열람(사유 + 기록), 라이더 컴플레인 기록, 라이더·배송 조회, 전달 실패 확정, 오배송 확인 기록, 사건함 **보기** | 백오피스 |
| `RIDER_OPS` | 라이더 운영 | 아니오(모든 센터) | **지원센터를 겸한다**(사용자 2026-11-15). 라이더 찾기·상세·원문 열람, 계정 제한·제한 검토, 배차 조회·취소, 전달 실패 확정, 사건함 집기·닫기, 오배송 확인 기록 | 백오피스 |
| `MD` | MD(상품 담당) | 아니오 | 상품·SKU·카테고리·콘텐츠·가격 **입력**(승인은 각 승인자), 판매 상태 변경(MD 만) | 백오피스 |
| `MFC_SUPERVISOR` | 센터 관리자 | **예** | 자기 센터의 작업자·근무·작업 배정, 이동 지시, 실사 승인, 반품 검수, 운영 제한, 보충 정책, 로컬 공급 요청, 전달 실패 확정(자기 센터) | MFC 백오피스, mfc_app(PDA) |
| `MFC_WORKER` | 센터 작업자 | **예** | 자기 센터의 피킹·포장·인계 확인·배차 요청·입고·품질 판정·이동 실행·실사 계수 | mfc_app(PDA) |
| `CDC_SUPERVISOR` | CDC 센터 관리자 | **예** | 자기 CDC 센터(칠곡 DC `CDC01` 등)의 웨이브·작업 지시·결과 확인(2026-11-25 cdc R-16 — 지금 CDC 서비스는 역할을 보지 않고 직원 토큰·`centers` 만 본다) | CDC 시뮬레이터·(예정) CDC 백오피스 |
| `CDC_WORKER` | CDC 작업자 | **예** | 자기 CDC 센터의 FC 피킹·분배/라인·검품·상차·지게차 작업(2026-11-25 R-16) | CDC 시뮬레이터·(예정) PDA |
| `PRODUCT_APPROVER` | 상품 승인자 | 아니오 | 상품·SKU·바코드·카테고리 트리 변경 **승인**(BO-03) | 백오피스 승인함 |
| `DISPLAY_APPROVER` | 전시 승인자 | 아니오 | 고객용 상품명·설명·이미지 게시 승인(BO-05) | 백오피스 승인함 |
| `PRICE_APPROVER` | 가격 승인자 | 아니오 | 기본 판매가격 승인(BO-04) | 백오피스 승인함 |
| `SERVICEABILITY_APPROVER` | 배송 권역 승인자 | 아니오 | 고객 배송 권역·담당 MFC 매핑 승인(BO-07) | 백오피스 승인함 |
| `CENTER_MASTER_APPROVER` | 센터 기준정보 승인자 | 아니오 | 센터 기준정보·영업 캘린더 승인(BO-06) | 백오피스 승인함 |
| `INVENTORY_APPROVER` | 재고 승인자 | 아니오 | MFC 초기 보유 반입(오픈 때 초기 재고) 승인 | 백오피스 승인함 |

- **센터 지정** = 권한을 줄 때 센터(`MFC-…`)를 같이 골라야 하는 역할. 토큰의 `centers` 에 그 센터가 있어야 그 센터 일을 할 수 있다(지금 `MFC_SUPERVISOR`·`MFC_WORKER`·`CDC_SUPERVISOR`·`CDC_WORKER` — CDC 는 센터 `CDC01` 처럼). 센터 지정이 아닌 역할은 모든 센터에 걸친다.
- 한 직원이 여러 역할을 가질 수 있다(예: 시험 계정 `test-cs-rider@pmart.com` = `CS_AGENT`·`RIDER_OPS`).
- "그 센터 운영자" — MFC 서비스 문서에 자주 나오는 말로, 직원 토큰이면서 `centers` 에 그 센터가 있는 사람(역할과 상관없는 조회 권한). 일을 하는 권한은 위 역할로 따로 본다.

## 2. 승인 종류 → 승인할 수 있는 역할 (approval-service)

설정 `approval-service.yml` 의 `pmart.approval.approver-roles`. **입력한 사람과 승인하는 사람은 달라야 한다**(자기 승인 금지). 여기 없는 종류는 `CONFIG_REQUIRED`.

| 승인 종류 | 승인 역할 | 무엇 |
|---|---|---|
| `PRODUCT` | `PRODUCT_APPROVER` | 상품·SKU·바코드·카테고리 트리 |
| `DISPLAY` | `DISPLAY_APPROVER` | 고객용 상품명·설명·이미지 게시 |
| `PRICE` | `PRICE_APPROVER` | 기본 판매가격 |
| `SERVICEABILITY` | `SERVICEABILITY_APPROVER` | 배송 권역·담당 MFC |
| `CENTER_MASTER` | `CENTER_MASTER_APPROVER` | 센터 기준정보·영업 캘린더 |
| `INITIAL_STOCK` | `INVENTORY_APPROVER` | MFC 초기 재고 반입 |
| `GRANT` | `OPERATOR_ADMIN` | 직원 권한 추가·변경 요청 |

- MFC 현장의 승인(실사 차이·반품 검수·품질 등)은 approval-service 가 아니라 그 서비스 안에서 **그 센터 `MFC_SUPERVISOR`** 가 한다(요청·계수한 본인은 못 함 — `SELF_APPROVAL`).

## 3. 권한을 주고 빼는 흐름 (BO-01, 사용자 결정 2026-11-15)

1. **요청**: 인사팀(`HR_ADMIN`)이 인사팀 앱 "권한 관리"에서 역할·센터·기간을 골라 요청(`POST /hr/employees/{id}/grant-requests`). 자기 권한은 요청 못 함.
2. **승인**: 백오피스 승인함에서 `OPERATOR_ADMIN` 이 결정(승인 종류 `GRANT`).
   - **자동 승인**: 역할이 `MFC_WORKER` 이고, 소속이 센터이며, 권한 센터가 정확히 그 소속 센터 하나일 때만 바로 승인(규칙 `GRANT-MFC-WORKER-OWN-CENTER` v1 — 사용자 2026-11-12 "소속 센터 현장 역할만"). 그 밖은 모두 사람이 심사.
3. **반영**: 승인되면 employee-auth-service 가 grant 를 만들고 `GrantApplied` 를 낸다. 다음 로그인·토큰 갱신부터 토큰에 들어간다.
4. **기간**: 종료일을 안 주면 근무 기간까지.
5. **회수**: 즉시, 사유 필수(`POST /employee-auth/employees/{id}/grant-revocations`, `OPERATOR_ADMIN`·`HR_ADMIN`). 그 직원의 세션을 끊는다. **`OPERATOR_ADMIN`·`HR_ADMIN` 권한은 `OPERATOR_ADMIN` 만 회수**한다. 자기 권한은 회수 못 함.
6. **소속 변경**: 직원의 소속 센터가 바뀌면 이전 센터 권한은 **다음 날 00:00(Asia/Seoul)** 에 자동 종료(`EmployeeAffiliationChanged`).
- 인사상 상사라는 이유만으로 권한을 주지 않는다(BO-01) — 고용 정보(hr-service)와 접근 권한(employee-auth-service)은 따로다.

## 4. 앱·메뉴별로 누가 보나

### 백오피스(https://192.168.0.40) — 메뉴는 세션 응답의 역할로 보이고, 판정은 서버가 한다
| 메뉴 묶음 | 보는 역할 |
|---|---|
| 상품 관리(상품·초안·일괄 등록, 카테고리, 콘텐츠, 가격, 취급) | `MD`·`OPERATOR_ADMIN`(판매 상태 변경은 `MD` 만) |
| 승인(승인함) | 각 `*_APPROVER`·`OPERATOR_ADMIN` |
| 고객센터(주문·원문 열람, 고객 문의, 라이더 컴플레인, 클레임, 배송) | `CS_AGENT`·`OPERATOR_ADMIN` |
| 라이더 운영(라이더 찾기·상세·제한, 제한 검토, 배송, 컴플레인) | `RIDER_OPS`·`OPERATOR_ADMIN` |
| 지원센터(사건함, 오배송 확인) | `RIDER_OPS`·`OPERATOR_ADMIN`·`CS_AGENT`(상담원은 보기만) |
| 처리 대기(멈춘 주문, 이행 확인 필요) | `OPERATOR_ADMIN` |
| 개인정보 열람 기록 | `OPERATOR_ADMIN`(API 는 있음, 화면은 클라이언트가 만드는 중) |
| 업무 도우미(운영자 RAG) | 직원 모두 |

### 인사팀 백오피스(:8444) — `HR_ADMIN`
직원 등록·본사 부서·권한 관리(보기·요청·회수)·소속 변경·정지/퇴사. 본인 정보(`/hr/employees/me`)는 직원 누구나.

### MFC 백오피스(:8445) — `MFC_SUPERVISOR`(자기 센터)
작업자·배정·작업·입고·재고·품질·위치·운영 제한(메뉴 화면은 PC 2 가 만드는 중).

### mfc_app(PDA) — `MFC_WORKER`·`MFC_SUPERVISOR`(자기 센터)
피킹·포장·인계·입고·품질·이동·실사. 무활동 30분이면 다시 로그인.

## 5. 서비스별 주요 권한 (요약 — 정본은 각 서비스)

| 서비스 | 역할별로 열리는 것 |
|---|---|
| employee-auth | 직원 상세·지금 권한 보기 `OPERATOR_ADMIN`·`HR_ADMIN`, 회수(위 §3), 관리 경로 `OPERATOR_ADMIN` |
| hr | 모든 명령·목록 `HR_ADMIN`, 본인 조회 직원 누구나 |
| approval | 승인 결정 = 종류별 승인 역할(§2), 자기 승인 금지 |
| product | 판매 상태 변경 `MD` |
| order | 직원 주문 검색·상세·원문 열람(사유 + audit 기록) `OPERATOR_ADMIN`·`CS_AGENT`·`RIDER_OPS`, 이행 확인 필요 — 그 센터 운영자(관리자는 모든 센터) |
| order-orchestration / backoffice-bff | 처리 대기·다시 확인·처리 완료 `OPERATOR_ADMIN` |
| audit | 개인정보 열람 기록 조회 `OPERATOR_ADMIN` |
| case | 문의 답변 `CS_AGENT`, 라이더 컴플레인 기록 `CS_AGENT`·`OPERATOR_ADMIN`(조회는 `RIDER_OPS` 도), 사건함 보기 `OPERATOR_ADMIN`·`RIDER_OPS`·`CS_AGENT`, 집기·닫기 `RIDER_OPS`·`OPERATOR_ADMIN`(남의 사건 배정·닫기는 관리자) |
| rider | 라이더 찾기·상세·원문 열람 `OPERATOR_ADMIN`·`RIDER_OPS`·`CS_AGENT`, 계정 제한·제한 검토 `OPERATOR_ADMIN`·`RIDER_OPS` |
| delivery-dispatch | 배차 조회 그 센터 운영자·`OPERATOR_ADMIN`·`RIDER_OPS`·`CS_AGENT`, 배차 취소 그 센터 운영자·`OPERATOR_ADMIN`·`RIDER_OPS`(상담원은 403 — ADR-0060) |
| delivery-execution | 직원 배송 찾기 `OPERATOR_ADMIN`·`CS_AGENT`·`RIDER_OPS`(모든 센터), 전달 실패 확정 그 센터 `MFC_SUPERVISOR`·`OPERATOR_ADMIN`·`CS_AGENT`(작업자 403) |
| mfc-fulfillment·inventory·inbound·quality·capacity | 자기 센터 — 실행·판정·요청 `MFC_WORKER`·`MFC_SUPERVISOR`, 배정·지시·승인·정책 `MFC_SUPERVISOR` |

- 개인정보 원문을 보여 주는 기능은 역할이 있어도 **사유 입력 + audit 기록이 남은 뒤에만** 원문을 준다(ADR-0062).

## 6. LAN 시험 계정 (비밀번호 파일 `deploy/lan/.secrets/`, 토큰은 `deploy/lan/checks/staff_auth.py`) — 전체 목록(MFC 작업자 70명 포함)은 `docs/operations/lan-test-accounts.md`

| 계정 | 역할·센터 |
|---|---|
| `claude-dev` | `OPERATOR_ADMIN` |
| `test-cs-rider@pmart.com` | `CS_AGENT`·`RIDER_OPS` |
| `claude-cs` | `CS_AGENT` + `MD`(2026-11-22, 상품 속성 수정 초안 요청 — 승인자 claude-approver 와 다른 사람이어야 해서) |
| `claude-approver` | 승인 역할들 + `HR_ADMIN` + `MD`(2026-11-22, 동의어 요청) |
| `claude-mfc-sup` | `MFC_SUPERVISOR` · `MFC-SEED-01` |
| `claude-mfc-wrk`, `mfcw-<분야>-NN@pmart.com`(70명) | `MFC_WORKER` · `MFC-SEED-01` |

## 7. 역할을 더하거나 바꿀 때
1. 사용자 결정을 받는다(새 역할은 업무 원문·ADR 근거가 필요하다).
2. 설정 저장소 `employee-auth-service.yml` 의 `roles`·`role-info` 에 넣는다(센터 지정이면 `center-scoped: true`) → employee-auth-service 재시작. 인사팀 앱 고르기 목록은 자동으로 따라온다.
3. 그 역할을 받는 서비스의 권한 검사 코드·시험을 고치고, 승인 역할이면 `approval-service.yml` `approver-roles` 에 종류를 잇는다.
4. 백오피스 메뉴(`web/backoffice/src/app/nav.ts`)는 클라이언트에 handoff 로 요청한다.
5. 이 문서와 서비스 `CLAUDE.md` 를 같이 고친다.
- 사례: `RIDER_OPS`(2026-11-15, 라이더 운영 — 지원센터 겸함).
