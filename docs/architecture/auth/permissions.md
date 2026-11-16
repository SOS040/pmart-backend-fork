# PMart 권한 전체 정리 (2026-11-17)

누가(고객·비회원·라이더·직원·서비스) 무엇을 할 수 있는지 **지금 코드 기준**으로 한곳에 모은 문서다.
2026-11-17 PC 1 이 계약(`contracts/openapi/*.yaml` 의 `x-pmart-user-types`)·서비스 코드·LAN 설정(`deploy/lan/.config-repo/`)을 대조해 썼다.

- 직원 역할 13개의 뜻·받는 법·메뉴는 `staff-roles.md`(이 문서는 그 위의 전체판). 발급자·게이트웨이 구조는 `issuer-type-pairing-and-access-lists.md`, ADR-0054·0056·0059·0062.
- **정본은 각 서비스 코드**다. 서비스의 권한 검사를 바꾸면 이 문서와 그 서비스 `CLAUDE.md` 를 같이 고친다.
- §9 "계약과 코드가 다른 곳·빈틈" 은 고칠지 결정이 필요한 목록이다(아직 고치지 않음).

## 목차
1. 권한이 정해지는 4단계
2. 사용자 종류(토큰 종류)
3. 직원 역할 13개
4. 승인(만든 사람 ≠ 승인한 사람)
5. 직원 권한을 주고 빼는 흐름
6. 서비스별 권한 — 고객·상품·주문
7. 서비스별 권한 — 배송·라이더·지원센터
8. 서비스별 권한 — MFC
9. 계약과 코드가 다른 곳·빈틈 (결정 필요)
10. 빈틈을 영역별로 — 누가 고치나

---

## 1. 권한이 정해지는 4단계

```
요청 + 토큰
 ① 게이트웨이: 토큰 발급자(iss) ↔ 사용자 종류(typ) 짝 확인, 차단 목록, /internal 거부
 ② 서비스 공통 필터: 공개 경로·비회원 경로·내부 경로 구분
 ③ 서비스 코드: 사용자 종류 · 직원 역할 · 센터 · 본인 소유 확인
 ④ 민감한 일: 사유 + 감사 기록(개인정보 원문), 승인(만든 사람 ≠ 승인한 사람)
```

### ① 게이트웨이 (`platform-services/api-gateway`)
- **발급자 짝**(ADR-0054): 발급자 표에 없는 토큰은 401. 발급자마다 낼 수 있는 사용자 종류가 정해져 있다(`application-lan.yml`).

  | 발급자 | 사용자 종류 |
  |---|---|
  | auth-service(고객 로그인) | CUSTOMER, GUEST (표에는 SERVICE 도 남아 있지만 서비스 토큰은 더 발급하지 않음 — ADR-0026) |
  | employee-auth-service(직원) | OPERATOR |
  | rider-auth-service(라이더) | RIDER |

  Keycloak 토큰은 직접 받지 않는다(직원은 employee-auth-service 가 다시 발급한 토큰만).
- **`/internal/**` 은 게이트웨이에서 모두 거부**(밖에서 내부 API 를 부를 수 없음).
- **공개 경로**(로그인 없이): 상품·가격·검색·전시·리뷰 보기, 배송 가능 확인, 비회원 주소, 토스 결제 웹훅, 고객·라이더·직원 로그인과 토큰 갱신 — 34개 + 직원 로그인 화면(`/iam`) 6개.
- **비회원(GUEST) 경로**: 장바구니, 배송 시간대(`/slots`), 배송 조건, 주소 좌표 변환, 배송 옵션, 로그아웃·현재 세션.
- **차단 목록**: 정지·퇴사·로그아웃·권한 회수가 일어나면 그 사람(또는 그 세션)의 이전 토큰을 401 `SUBJECT_BLOCKED`. **게이트웨이만** 이 목록을 본다. Redis 장애 때는 막지 않고 통과(fail-open).
- **경로별 사용자 종류 허용 목록**: 계약의 `x-pmart-user-types` 로 만든 487줄(직원 348·고객 77·라이더 54·공개 36·비회원 13, 한 줄에 여러 종류 가능). ⚠️ **지금 `mode: RECORD` — 어긋나도 막지 않고 기록만 한다.** 그래서 실제 판정은 ③ 서비스 코드뿐이다(§9-1).

### ② 서비스 공통 필터 (`libraries/common-security`)
- 공개 경로(permit-paths) → 로그인 없이.
- 비회원 경로(guest-paths) → 비회원 토큰도 가능.
- 내부 경로(`/internal/**`) → 서비스 호출만. 사용자 토큰을 붙이면 403. 서비스끼리는 토큰 없이 부르고 `X-Source-System` 헤더로 이름만 밝힌다(ADR-0026) — **어느 서비스가 부르는지는 확인하지 않는다**(네트워크가 경계).
- 그 밖 → 로그인한 비회원이 아닌 토큰이면 통과, 나머지는 ③.

### ③ 서비스 코드
- 토큰의 `typ`(사용자 종류), `roles`(직원 역할), `centers`(권한 센터)를 본다.
- "그 센터 운영자" = 직원 토큰이고 `centers` 에 그 센터가 있음. `centers` 가 비면 어느 센터도 아니다(전체가 아님).
- 본인 소유: 고객·라이더는 자기 것만. 남의 것은 대부분 **404**(있는지도 알려 주지 않음).

### ④ 민감한 일
- **개인정보 원문 보기**(ADR-0062): 역할이 있어도 사유(2~200자)를 쓰고 audit-service 에 기록이 남은 뒤에만 원문을 준다. 기록이 실패하면 원문 없음(503). 기록 조회는 `OPERATOR_ADMIN`.
- **승인**: §4.
- **승인 번호(approvalRef)**: MFC 재고 정정·폐기·실사 조정·운영 제한 등은 승인 번호를 받는다. ⚠️ LAN 은 `pmart.approval-ref.mode: RECORD_ONLY` — **번호를 확인하지 않고 기록만** 한다(prod 에는 넣지 않음, 없으면 CONFIG_REQUIRED).

---

## 2. 사용자 종류(토큰 종류)

| 종류 | 누구 | 받는 곳 | 할 수 있는 것(요약) |
|---|---|---|---|
| PUBLIC | 로그인 안 함 | — | 상품·가격·검색·홈·리뷰 보기, 배송 가능 확인, 로그인 |
| GUEST | 비회원(앱 첫 실행) | auth-service, 1시간(갱신 30일) | 장바구니, 배송 시간대·옵션 보기, 비회원 주소(기기 ID 로 본인 확인) |
| CUSTOMER | 회원 고객(Google 로그인) | auth-service, 30분(갱신 30일) | 자기 정보·주소·주문·결제·취소·리뷰·문의·알림·고객 RAG |
| RIDER | 라이더(Google 로그인) | rider-auth-service | 자기 가입·정보·배차 제안·배송 진행·위치·정산·알림. 역할·센터는 항상 비어 있음 |
| OPERATOR | 직원 | employee-auth-service(Keycloak 로그인 뒤) | §3 역할·센터에 따라 |
| SERVICE | 서비스끼리 | 토큰 없음(헤더로 이름만) | `/internal/**` 만 |

라이더 로그인은 계정이 ACTIVE 이고 제한이 없어야 한다. 정지·해지되면 진행 중 배송이 없을 때 세션을 끊고 다시 로그인하면 403 `ACCOUNT_NOT_AVAILABLE`.

---

## 3. 직원 역할 13개

목록의 정본: `employee-auth-service.yml` 의 `pmart.employee-auth.roles`. 목록 밖 역할은 저장되지 않는다. 자세한 설명은 `staff-roles.md` §1.

| 역할 | 이름 | 센터 지정 | 핵심 권한 |
|---|---|---|---|
| `OPERATOR_ADMIN` | 운영 관리자 | 아니오 | 거의 모든 본사 조회·사건함 전체·처리 대기 명령·직원 권한 승인(GRANT)·관리자 권한 회수·개인정보 열람 기록 조회. **MFC 현장 일에는 권한 없음**(MFC 서비스에 관리자 예외 없음) |
| `HR_ADMIN` | 인사 관리자 | 아니오 | 직원 등록·소속·정지·퇴사, 권한 **요청**·회수(관리자 권한 제외) |
| `CS_AGENT` | 고객센터 상담원 | 아니오 | 직원 주문 검색·고객 주소 원문(사유+기록), 고객 문의 **답변**, CS 접수, 라이더 컴플레인 기록, 사건함 보기·메모, 전달 실패 확정. **배차 취소 못 함**(ADR-0060) |
| `RIDER_OPS` | 라이더 운영(지원센터 겸함) | 아니오 | 라이더 찾기·원문·계정 제한·제한 검토, 배차 조회·취소, 전달 실패 확정, 사건함 집기·닫기·넘기기 |
| `MD` | 상품 담당 | 아니오 | **판매 상태 변경**, **상품 정보 수정 시작**(수정할 상품 정하기 — 2026-11-17). 그 밖 상품·콘텐츠·가격 입력은 직원 누구나 |
| `MFC_SUPERVISOR` | 센터 관리자 | **예** | 자기 센터 작업자·근무·배정·이동 지시·실사/품질/폐기/반품 승인·운영 제한·보충 정책·로컬 공급 요청, 자기 센터 배차 실패 사건·실시간 배송 |
| `MFC_WORKER` | 센터 작업자 | **예** | 자기 센터 피킹·포장·인계 확인·배차 요청·입고·품질 판정 요청·이동 실행·실사 계수 |
| `PRODUCT_APPROVER` | 상품 승인자 | 아니오 | PRODUCT 승인 |
| `DISPLAY_APPROVER` | 전시 승인자 | 아니오 | DISPLAY 승인 |
| `PRICE_APPROVER` | 가격 승인자 | 아니오 | PRICE 승인 |
| `SERVICEABILITY_APPROVER` | 배송 권역 승인자 | 아니오 | SERVICEABILITY 승인 |
| `CENTER_MASTER_APPROVER` | 센터 기준정보 승인자 | 아니오 | CENTER_MASTER 승인 |
| `INVENTORY_APPROVER` | 재고 승인자 | 아니오 | INITIAL_STOCK(초기 재고) 승인 |

설정으로 정하는 역할: `product-service.yml` `sale-state-change-role: MD`·`edit-role: MD`, `approval-service.yml` `approver-roles`. 그 밖은 코드에 고정.

---

## 4. 승인 (approval-service)

- 승인 요청 만들기·제출·조회: 직원 누구나(제출은 요청한 본인만).
- **결정: 그 종류의 승인 역할만, 요청한 본인은 못 함**(자기 승인 금지). 역할이 설정에 없으면 CONFIG_REQUIRED.

| 승인 종류 | 승인 역할 | 무엇 | 요청하는 곳 |
|---|---|---|---|
| PRODUCT | PRODUCT_APPROVER | 상품·SKU·바코드·카테고리 트리, 판매중지 | product-service 초안 제출 |
| DISPLAY | DISPLAY_APPROVER | 고객용 상품명·설명·이미지 | product-content-service |
| PRICE | PRICE_APPROVER | 기본 판매가격 | pricing-service |
| SERVICEABILITY | SERVICEABILITY_APPROVER | 배송 권역·담당 MFC | delivery-serviceability-service |
| CENTER_MASTER | CENTER_MASTER_APPROVER | 센터 기준정보·영업 캘린더 | center-master-service |
| INITIAL_STOCK | INVENTORY_APPROVER | MFC 초기 재고 반입 | mfc-inbound-service |
| GRANT | OPERATOR_ADMIN | 직원 권한 | hr-service 권한 요청 |

- 승인되면 각 서비스가 승인 사건을 받아 반영한다(직접 고치는 API 없음 — 예: 센터 기준정보).
- **MFC 현장 승인**(실사 차이·품질 해제·폐기·반품 검수)은 approval-service 가 아니라 그 서비스 안에서 **그 센터 `MFC_SUPERVISOR`** 가 한다. 요청·계수·판정한 본인은 못 함(`SELF_APPROVAL`, §8).

---

## 5. 직원 권한을 주고 빼는 흐름 (BO-01)

1. **요청** — `HR_ADMIN` 이 인사팀 앱에서(`POST /hr/employees/{id}/grant-requests`). 자기 권한은 요청 못 함. 대상은 ACTIVE·계정 준비됨, 열린 요청이 없어야 함.
2. **승인** — `OPERATOR_ADMIN`(승인 종류 GRANT, 요청자와 달라야 함).
   - 자동 승인: 역할 `MFC_WORKER` + 소속이 센터 + 권한 센터가 그 소속 센터 하나일 때만(규칙 `GRANT-MFC-WORKER-OWN-CENTER`, 승인자 기록 `RULE:…@v1`).
3. **반영** — employee-auth-service 가 권한(grant)을 만들고 `GrantApplied`. 다음 로그인·토큰 갱신(최대 15분)부터 토큰에 들어감. MFC 작업자 명단은 이 사건으로 생긴다(인사 등록만으로는 안 생김).
4. **회수** — 즉시, 사유 필수(`OPERATOR_ADMIN`·`HR_ADMIN`). 그 직원의 세션을 모두 끊고 차단 목록에 올린다. **`OPERATOR_ADMIN`·`HR_ADMIN` 권한은 `OPERATOR_ADMIN` 만** 회수. 자기 권한은 회수 못 함.
5. **소속 변경** — 이전 센터 권한은 다음 날 00:00(서울)에 자동 종료.
6. 직원 상세·지금 권한 보기: `OPERATOR_ADMIN`·`HR_ADMIN`(승인자·회수자 이름 표시 — 2026-11-17). 역할 목록 API: 직원 누구나.

직원 로그인·세션: 계정이 ACTIVE 이고 시작일이 지나야 로그인. 웹 백오피스 3개는 HttpOnly 갱신 쿠키 + CSRF 확인(ADR-0059), 로그아웃은 다른 앱에도 전파(ADR-0061).

---

## 6. 서비스별 권한 — 고객·상품·주문

표기: **공개** = 로그인 없이, **직원** = 역할 상관없이 직원 누구나, **센터 운영자** = 그 센터가 토큰 centers 에 있는 직원, **내부** = 서비스끼리.

### 상품·콘텐츠·가격·검색·전시
| 서비스 | 누가 무엇을 |
|---|---|
| product-service | 공개: 카테고리 트리·필터. **MD**: 판매 상태 변경(판매중지는 PRODUCT 승인 필요), 상품 정보 수정 시작, 수정할 상품 정하기(예전 길도 — 2026-11-17). 직원: 초안·일괄 등록·카테고리 트리 작성·제출(→ PRODUCT 승인), 초안·배치 조회. 상품·SKU·바코드 조회는 계약상 직원(§9) |
| product-content-service | 공개: 상품 콘텐츠·이미지(게시된 것만, 직원·서비스는 초안 이미지도). 직원: 콘텐츠 초안·일괄 등록·제출(→ DISPLAY 승인) |
| pricing-service | 공개: 가격. 직원: 가격 일괄 등록·제출(→ PRICE 승인). 가격 반영 명령은 직원·서비스 + PRICE 승인 번호 |
| search-service | 공개: 상품 검색·추천어·필터. 최근 검색어(고객)는 미구현 |
| storefront-service | 공개: 홈·탭·배너. 화면 배치 게시(직원)는 미구현 |

### 고객·장바구니·주문·결제·취소·리뷰·알림
| 서비스 | 누가 무엇을 |
|---|---|
| customer-service | 고객: 내 정보·프로필 사진·주소·쇼핑 주소(남의 주소는 404). 공개: 비회원 배송 주소(기기 ID 가 다르면 403) |
| cart-service | 고객·비회원: 자기 장바구니만(주인 다르면 403). 비회원 장바구니 합치기는 고객 + 비회원 토큰으로 본인 확인 |
| order-service | 고객: 주문서·결제 시작·내 주문·예약 시간 변경(남의 주문은 404). **`OPERATOR_ADMIN`·`CS_AGENT`·`RIDER_OPS`**: 직원 주문 검색·상세(주소 가림), 주소 원문(사유+감사 기록), 재배달 주소 기록. 이행 확인 필요 목록·확인: 센터 운영자(센터 없이 전체는 `OPERATOR_ADMIN`) |
| order-orchestration-service | 고객: 재배달 결제(자기 것). 멈춘 주문 처리 명령은 내부 경로 — 화면에서는 backoffice-bff 가 `OPERATOR_ADMIN` 만 통과시킴 |
| payment-service | 고객: 결제 확정·실패·결제 조회(자기 것만, 404), 결제창 설정. 공개: 토스 웹훅(서명 확인) |
| claim-service | 고객: 주문 취소(자기 주문만). 취소·환불 조회: 고객은 자기 것, **직원은 역할·센터 제한 없이 전체**(§9). 클레임 결정·철회는 미구현 |
| review-service | 공개: 리뷰·사진 보기(게시된 것). 고객: 리뷰 작성·수정(3일 안)·삭제, 작성자 본인만. 직원 숨김·삭제 없음(2026-11-16 결정) |
| notification-service | 고객·라이더: 내 알림함·알림 설정(자기 것만) |
| customer-assistant-service | 고객만: 고객 RAG 질문·답변(자기 것만), 최근 주문은 고객 토큰으로 order 를 불러 본인 것만 |
| ai-assistant-service | 직원 누구나: 운영자 RAG(답변·기록은 물은 사람만) |
| audit-service | **`OPERATOR_ADMIN`**: 개인정보 열람 기록 조회. 기록 남기기는 내부 |
| center-master-service | 센터·캘린더 조회 직원, 픽업 지점 라이더·직원(2026-11-17 서비스 안 검사). 바꾸기는 CENTER_MASTER 승인으로만 |
| cdc-inventory-service | CDC 상품 취급 조회·변경: 그 CDC 가 centers 에 있는 직원(역할 없음). 나머지 내부 |
| fulfillment-routing-service | 내부만 |
| hr-service | **`HR_ADMIN`**: 직원 등록·목록·소속 변경·정지·복귀·퇴사·초대 재발송·메일 비밀번호 재설정·부서·권한 요청(권한 요청·소속 변경·정지·복귀·퇴사는 자기 자신 제외). 직원 누구나: 내 인사 정보(`/hr/employees/me`) |
| employee-auth-service | §5. 추가로 `OPERATOR_ADMIN` 만 직원 계정의 상태·역할·센터를 직접 바꾸는 관리 경로(`PUT /employees/{id}`, 자기 계정 제외 — §9) |
| auth-service | 공개: 고객 Google 로그인·토큰 갱신·비회원 세션. 고객·비회원: 로그아웃·현재 세션 |

---

## 7. 서비스별 권한 — 배송·라이더·지원센터

| 서비스 | 누가 무엇을 |
|---|---|
| rider-service | 라이더: 가입·내 정보·운행 상태·설정(자기 것). 면허 번호 중복 확인은 라이더 토큰이면 가입 전도 가능(쓰였는지만 알려 줌, 원문 저장 안 함). **`OPERATOR_ADMIN`·`RIDER_OPS`·`CS_AGENT`**: 라이더 찾기(ID·전화 정확히)·상세(가림)·원문(사유+감사 기록). **`OPERATOR_ADMIN`·`RIDER_OPS`**: 계정 제한·제한 검토. 정지·해지 라이더는 운행 시작 못 함, 배차 제한은 새 배차만 꺼짐(받은 배송은 끝낼 수 있음) |
| delivery-dispatch-service | 라이더: 자기 배차 제안·수락(제한 중이면 빈 목록·수락 불가). 제안에는 1 km 단위 대략 위치 + 주문 요약만. 배송 배차 조회: 센터 운영자·`OPERATOR_ADMIN`·`RIDER_OPS`·`CS_AGENT`, 라이더는 배정·제안받은 것만. **배차 취소**: 배정된 라이더 본인, 센터 운영자·`OPERATOR_ADMIN`·`RIDER_OPS`(**`CS_AGENT` 못 함** — ADR-0060). 픽업 뒤에는 취소 불가(사건으로) |
| delivery-execution-service | 라이더(배정된 본인): 도착·픽업·고객 도착·사진·완료·실패·반품. **고객 실제 주소·전화(drop-info)는 배정 라이더가 픽업 뒤에만**(저장하지 않고 그때 order 에서 읽음). 배송 조회: 배정 라이더·볼 수 있는 직원(`OPERATOR_ADMIN`·`CS_AGENT`·`RIDER_OPS` 전체, 그 밖은 센터 운영자). **전달 실패 확정·도움요청 해결**: `OPERATOR_ADMIN`·`CS_AGENT`·`RIDER_OPS` 또는 그 센터 `MFC_SUPERVISOR`(작업자 못 함). 라이더 내 배송·정산: 자기 것 |
| delivery-tracking-service | 라이더: 내 위치 보내기·최근 위치. 고객·직원 ETA·실시간 위치는 미구현 |
| delivery-capacity-service | 배송 시간대 보기(고객·비회원). 시간대 잡기는 내부 |
| delivery-serviceability-service | 공개: 배송 가능 확인(시험 주소 확인은 직원). 배송 권역 버전 작성: 직원 누구나(→ SERVICEABILITY 승인) |
| case-service | 아래 표 |
| backoffice-bff | 통합 조회: `OPERATOR_ADMIN`·`CS_AGENT`·`RIDER_OPS`. 실시간 배송: 같은 셋(전체) 또는 `MFC_SUPERVISOR`(자기 센터). **처리 대기·명령**(사가 다시 확인·처리 완료, MFC 중지·인계 재시도): `OPERATOR_ADMIN`. 사건함·승인함: 직원(판정은 case·approval 이) |

### case-service (지원센터·고객센터)
| 무엇 | 누가 |
|---|---|
| 사건함 보기 | `OPERATOR_ADMIN` 전부. `RIDER_OPS`·`CS_AGENT` 배송 사건(도움요청·배송 사고·위치 끊김·오배송·배차 실패). `MFC_SUPERVISOR` 자기 센터 배차 실패만. 주문 사건(정산 불일치·결제 미확정)은 `OPERATOR_ADMIN` 만 |
| 오배송 기록(사건 만들기) | `CS_AGENT`·`RIDER_OPS`·`OPERATOR_ADMIN` |
| 집기·닫기·다시 열기·넘기기 | `OPERATOR_ADMIN`, 배송 사건은 `RIDER_OPS`. 남에게 배정은 `OPERATOR_ADMIN` 만 |
| 메모 | 보기: 사건을 볼 수 있는 사람. 쓰기: `OPERATOR_ADMIN`·`RIDER_OPS`·`CS_AGENT`. 가리기(삭제): `OPERATOR_ADMIN` + 사유 |
| 후속 처리(의무) | 만들기: 사건을 다룰 수 있는 사람. 완료·시도: `OPERATOR_ADMIN` 또는 담당 팀 역할. 면제·실패: `OPERATOR_ADMIN` |
| 교대 인수인계 | 작성: 본인(부재 시 `OPERATOR_ADMIN`). 수락: 후임 본인만. 취소: 전임·`OPERATOR_ADMIN` |
| 고객 문의 | 고객: 작성·자기 문의 보기(상담원이 남긴 문의는 안 보임). 직원 보기: `CS_AGENT`·`OPERATOR_ADMIN`. **답변: `CS_AGENT` 만** |
| CS 접수(전화·채팅·메일 기록) | `CS_AGENT`·`OPERATOR_ADMIN`. 기록만 — 취소·환불 안 함 |
| 라이더 컴플레인 | 기록: `CS_AGENT`·`OPERATOR_ADMIN`. 보기: + `RIDER_OPS` |
| 행위 요청·라이더 도움요청 접수(`/help-requests`) | 미구현(도움요청은 delivery-execution 이 받음) |

---

## 8. 서비스별 권한 — MFC

모든 MFC 일은 **그 센터가 토큰 centers 에 있어야** 한다. `OPERATOR_ADMIN` 이라도 센터가 없으면 못 한다.

| 서비스 | 작업자(`MFC_WORKER`) | 관리자(`MFC_SUPERVISOR`) | 역할 없이 센터 운영자면 |
|---|---|---|---|
| mfc-fulfillment | 작업 시작·실행(본인 이름으로만), 포장 밀봉·재포장, 배차 요청, 인계 확인 목록·확인 | 작업 놓기(남의 것도) | 작업·출고·포장 조회, 본인 작업 놓기 |
| mfc-inventory | 실사 만들기·계수 | 실사 만들기·계수, **실사 승인**(시작·계수한 본인 제외), 보충 정책 변경 | 재고·보충 정책·출고 불가 LOT·상품 취급 조회, **재고 정정**·상품 취급 변경(§9) |
| mfc-inbound | 이동 지시 집기·내려놓기(배정·집은 본인) | 이동 지시 만들기·취소·집기·내려놓기, 로컬 공급 요청 | 입고 작업 조회·검수·적치(배정된 사람만), 초기 재고 수령, 반품 접수 |
| mfc-quality | 격리·해제 판정 요청, 폐기 요청·실행 | 위 + **품질 판정 승인**(판정한 본인 제외)·**폐기 승인**(요청한 본인 제외)·**반품 검수**(받은 본인 제외) | 조회 |
| mfc-capacity | 다음 작업 받기(본인), 출근 등록·휴식(본인) | 수동 배정·배정 계획·운영 제한 걸기/풀기(승인 번호)·작업자 등록·업무·상태·속도·자격 변경·근무 만들기/취소, 남의 출근·휴식 | 배정·계획·제한·근무 조회, **작업자 상세**(업무·상태 이력 포함 — 2026-11-16 결정 대기) |
| mfc-location | — | — | 위치 만들기·고치기·없애기·상태 변경(§9), 조회 |

- 라이더: 포장 인계 승인·확정은 **배정된 라이더 본인만**(배송 배정과 대조).
- 품질 사고·입고 예정(inbound-promises)은 미구현.

---

## 9. 계약과 코드가 다른 곳·빈틈 (결정 필요)

조사일 2026-11-17. 위험이 큰 순서. **✅ = 2026-11-17 고침**(PC 1 영역, 사용자 "응 해줘"), 나머지는 아직.

### 큰 것
1. **게이트웨이 사용자 종류 허용 목록이 RECORD(기록만)** — 계약에 "직원만"인데 서비스 코드에 검사가 없는 경로는 지금 **고객·라이더 토큰으로도 열린다.** ENFORCE 로 바꾸면 한 번에 막히지만, 그 전에 RECORD 로그에서 정상 요청이 걸리는지 확인해야 한다.
   - 서비스에 검사가 없던 "직원 전용" 조회: ✅ product `GET /products`·`/products/{id}`·`/skus/{sku}`·`/barcodes/{b}`·판매 상태/SKU 발급 결과, ✅ pricing 가격 버전·반영 결과, ✅ approval `GET /change-requests/{id}`, ✅ center-master `GET /centers*`(직원)·`/mfc-pickup-points`(라이더·직원) — 서비스 안에서 403. 남은 것: serviceability 권역 버전 조회(PC 5), mfc-fulfillment 인계 결과(`handover-resolution` — 고객 토큰도 통과, PC 3).
   - 사용자 종류 검사가 없는 곳: capacity `/slots`, serviceability 배송 조건·주소 변환(라이더·직원도 통과 — 영향 작음).
2. **직원 토큰에서 역할과 센터가 따로 합쳐진다**(사용자 2026-11-17: PC 1 만으로 — 권한 승인 때 센터마다 다른 MFC 역할이 섞이는 요청 거절, 그날 저녁 작업. LAN 실제 데이터: 센터 관리자 2명 모두 자기 센터 안, 여러 센터 직원 0명) — 토큰은 "모든 역할" + "모든 센터"라서, A 센터 `MFC_SUPERVISOR` + B 센터 `MFC_WORKER` 인 사람은 **B 센터에서도 관리자**가 된다(`EmployeeAccess`). 지금 시험 계정에는 이런 조합이 없다.
3. **`OPERATOR_ADMIN` 이 관리 경로로 역할을 바로 줄 수 있다** — `PUT /employee-auth/employees/{id}` 는 승인(GRANT) 없이 어떤 역할이든(관리자 역할 포함) 계정에 넣는다. ✅ 자기 대상 권한 요청 승인은 막음(GRANT 대상 = 승인자면 403 — `ApprovalRules.requireNotOwnGrant`). ✅ 관리 경로 역할·센터 변경은 스위치 `pmart.employee-auth.direct-role-edit`(기본 꺼짐, LAN 만 켬 — 시험 도구용), `OPERATOR_ADMIN`·`HR_ADMIN` 은 켜져 있어도 관리 경로로 새로 못 줌(사용자 "(가)").
4. **MFC 재고 정정에 역할이 없다** — `POST /mfcs/{id}/stock-corrections`(재고 증감)는 그 센터 직원이면 누구나, 승인 번호는 LAN 에서 확인 안 함(RECORD_ONLY). 위치 만들기·없애기, 상품 취급 변경, 입고 검수·적치도 역할 없음.
5. **내부 경로는 부르는 서비스를 확인하지 않는다** — 네트워크만 믿는다(ADR-0026). 민감한 것: 멈춘 주문 처리 명령(`/internal/sagas/{id}/manual-actions` — 처리자 ID 를 요청 본문에서 받음), 고객 배송 주소(`/internal/orders/{id}/delivery-destination`). LAN 안의 아무 PC 에서나 부를 수 있다.

### 중간
6. 취소·환불 조회(claim)는 직원이면 역할·센터 상관없이 전부 본다.
7. ✅ hr-service: `HR_ADMIN` 이 자기 자신을 정지·정지 해제·퇴사·소속 변경하는 것을 막음(403, 권한 요청과 같은 원칙).
8. mfc-inbound `GET /initial-stock-loads` 는 센터 확인이 없다(직원 누구나). `GET /supply-requests`·`/move-orders` 는 사용자 종류를 보지 않는다(센터만).
9. 차단 목록은 게이트웨이만 본다 — 서비스를 직접 부르면(LAN 안) 정지된 토큰도 만료 전까지 통한다.
10. 교대 인수인계·운영자 RAG·배송 권역 작성은 직원 누구나(역할 결정 없음).

### 계약 표시만 틀린 것 (코드가 맞음 — 계약 고치기)
- 코드가 역할을 요구하는데 계약은 "OPERATOR"만: audit 열람 기록(`OPERATOR_ADMIN`), order 직원 조회(3개 역할), 이행 확인 필요(센터), product(MD).
- 코드가 서비스 호출도 받는데 계약에 없음: pricing 가격 반영, claim 조회, dispatch·execution 배송 조회·취소·도움요청.
- auth-service 로그아웃·세션 계약에 OPERATOR 가 남아 있음(직원 토큰은 더 이상 auth-service 발급이 아님).
- 미구현인데 계약에 있음: 콘텐츠 별점 요약·게시, 전시 배치, 최근 검색어, 고객 동의·탈퇴, 클레임 결정·철회, 감사 기록 조회, 승인 철회·재심사·일괄, 배차 요청, 라이더 탈퇴, ETA, case 도움요청·행위 요청, execution 상태 정정(내부 경로라 직원 토큰을 받을 수 없음 — 설계 확인), 입고 예정, 품질 사고. 알림함·알림 설정은 구현돼 있는데 계약 설명이 "구현 없음".
- 문서: delivery-execution `CLAUDE.md` 30줄의 실패 확정 역할에 `RIDER_OPS` 가 빠짐(코드는 포함).

---

## 10. 빈틈을 영역별로 — 누가 고치나 (2026-11-17)

영역 기준은 `docs/operations/pc-split.md`. PC 1(platform) = auth·employee-auth·hr·approval·audit, 게이트웨이, `libraries/`, 설정, 커머스 서비스 전부(상품·가격·주문·주문 조정·결제·클레임·고객·장바구니·리뷰·알림·센터 기준정보·CDC·검색·전시). 그 밖은 MFC(PC 3)·배송·라이더(PC 5)·case·backoffice-bff(PC 2)·RAG(PC 4).

### 10-1. PC 1 영역 안에서 고칠 수 있는 것
| # | 빈틈 | 어디를 고치나 | 상태 |
|---|---|---|---|
| 1 | 고객 토큰으로 직원 전용 조회가 열림(상품 목록·상세·SKU·바코드·판매 상태·SKU 발급 결과, 가격 버전·반영 결과, 승인 요청 조회, 센터·캘린더·픽업 지점) | product·pricing·approval·center-master 서비스 안 직원 확인 | ✅ 2026-11-17 고침·LAN 배포(df932a12) |
| 2 | 운영 관리자가 승인 없이 역할 부여(`PUT /employee-auth/employees/{id}`) | employee-auth-service | ✅ 스위치 `direct-role-edit`(기본 꺼짐, LAN 켬 — 시험 도구), 관리자 역할은 항상 승인으로만(9b44d0a2, LAN 배포) |
| 3 | 운영 관리자가 자기 권한 요청을 스스로 승인 | approval-service — GRANT 대상 ≠ 승인자 | ✅ 고침·LAN 배포(df932a12) |
| 4 | 멈춘 주문 처리 명령이 내부 경로에서 처리자 ID 를 본문으로 받음 | order-orchestration-service — 처리자 확인을 서비스 안으로 | 아직 |
| 5 | 고객 배송 주소를 주는 내부 API(`/internal/orders/{id}/delivery-destination`)가 부르는 서비스를 확인 안 함 | order-service, `libraries/common-security` — 부를 수 있는 서비스 목록 설정(이름은 헤더로 스스로 밝히는 값이라 효과 제한) | 아직 |
| 6 | 취소·환불 조회를 직원이면 역할 상관없이 전부 봄 | claim-service — 볼 수 있는 역할 정하기(예: 운영 관리자·상담원) | 아직(역할 결정 필요) |
| 7 | 인사 관리자가 자기 자신을 정지·퇴사 처리 | hr-service — 본인 금지 | ✅ 정지·정지 해제·퇴사·소속 변경 모두 본인 금지, LAN 배포(df932a12) |
| 8 | 차단 목록(정지·퇴사·로그아웃)을 게이트웨이만 확인 | `libraries/common-security` | 아직 — 바꾸면 모든 서비스 다시 배포 |
| 9 | 리뷰 공개 경로 설정이 수정·삭제도 로그인 없이 보안 필터 통과(코드가 다시 막아 실제 피해 없음) | review-service 설정 | 아직(작은 정리) |
| 10 | 계약 표시가 코드와 다름(실제 권한은 맞음) | 계약 yaml: audit·order·product·pricing·claim·auth-service | 아직(문서만) |
| — | (발견 2026-11-17) 라이더 픽업 지점 `GET /mfc-pickup-points` 가 게이트웨이 경로에 없어 404 | pmart-config `api-gateway.yml` 경로 | 아직(사용자 결정 대기) |

### 10-2. PC 1 이 고치지만 다른 영역에 영향이 가는 것 (함께 정해야 함)
| 빈틈 | 이유 | 상태 |
|---|---|---|
| 게이트웨이 허용 목록 RECORD(기록만) → ENFORCE(막음) | 게이트웨이는 PC 1 이지만 켜면 모든 영역 경로가 계약 표시대로 막힌다. 먼저 RECORD 로그로 정상 요청이 걸리는지 확인하고, 틀린 표시는 각 영역이 고친다 | 아직 |
| 직원 토큰에서 역할·센터가 따로 합쳐짐(A 센터 관리자 + B 센터 작업자 ⇒ B 에서도 관리자) | (가) PC 1 만: 권한 승인 때 "센터마다 다른 MFC 역할"이 섞이는 요청 거절. (나) 토큰을 "역할@센터" 짝으로 — MFC 서비스 6개(PC 3)도 고쳐야 함 | 사용자 2026-11-17: (가)로, 그날 저녁 작업. LAN 데이터: 센터 관리자 2명 모두 자기 센터 안, 여러 센터 직원 0명 |
| 승인 번호(approvalRef) 확인 | 설정 `pmart.approval-ref.mode: RECORD_ONLY` 는 PC 1 이지만 확인 코드는 MFC 서비스(PC 3)에 있다 | 아직(운영 전 필요) |

### 10-3. PC 1 영역 밖 — 요청만 할 수 있는 것
| 받는 영역 | 빈틈 |
|---|---|
| **PC 3 (MFC)** | 재고 정정(`stock-corrections`)·위치 만들기/고치기/없애기·상품 취급 변경·입고 검수/적치에 역할 검사 없음(그 센터 직원 누구나). 초기 재고 조회(`GET /initial-stock-loads`)에 센터 확인 없음. 공급 요청·이동 지시 조회가 사용자 종류를 안 봄. 포장 인계 결과(`handover-resolution`)는 고객 토큰도 통과 |
| **PC 5 (배송)** | 배송 권역 버전 조회·작성에 직원·역할 확인 없음. 배송 시간대(`/slots`)·배송 조건·주소 변환이 사용자 종류를 안 봄(라이더·직원도 통과 — 영향 작음). execution `CLAUDE.md` 30줄 실패 확정 역할에 `RIDER_OPS` 빠짐(코드는 포함) |
| **PC 2 (case)** | 교대 인수인계를 직원이면 누구나 씀(역할 결정 없음) |
| **PC 4 (RAG)** | 운영자 RAG 를 직원이면 누구나 씀(역할 결정 없음) |

요청은 아직 보내지 않았다 — 보낼 때는 `docs/architecture/service-requests/<받는 서비스>.md` + 받는 PC 의 `docs/operations/other-pcs-todo.md` 한 줄.
