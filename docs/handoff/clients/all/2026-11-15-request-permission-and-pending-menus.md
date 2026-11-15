# [pmart-backend → pmart-clients] 요청: 메뉴 먼저 — 인사팀 "권한 관리", 백오피스 "처리 대기" (2026-11-15)

- 보낸 영역: platform
- 받는 앱: **hr-backoffice**(권한 관리), **backoffice**(처리 대기)
- 결정: 사용자 2026-11-15 — 화면 메뉴를 먼저 만들고, 지금 있는 API 로 되는 데까지 동작하게 한다. 아직 없는 기능의 버튼은 "준비 중"으로 둔다.
  - 권한 관리 메뉴는 인사팀 앱에 둔다(사용자 2026-11-15, 추천안).
  - 처리 대기는 본사 관리자(OPERATOR_ADMIN)가 모든 센터를 본다(사용자 2026-11-15 "관리자는 모든 센터").
- 업무 원문: BackOffice BO-01(운영자 권한 부여·회수), BO-12(운영자 수동 개입).
- 백엔드 계약: `employee-auth-service.yaml` **0.7.0**, `order-service.yaml` **0.6.0**. LAN 배포는 이 문서와 같은 날 — 배포 확인 뒤 회신에 적는다.
- 참고: 2026-11-16 부터 웹 앱(backoffice·hr-backoffice·웹 공통)은 PC 2(backoffice 영역)가 맡는다. 로그인·세션·토큰 코드는 계속 PC 1 만(`all/2026-11-15-notice-login-code-pc1-only.md`).

## 1. 인사팀 앱 — "권한 관리" 메뉴 (HR_ADMIN)

### 1-1. 지금 동작하게 할 것
1. **직원 검색**: `GET /api/v1/hr/employees?q=&status=&affiliationCode=&size=` (HR_ADMIN, 이미 있는 API — 인사팀 앱 직원 목록과 같음). `q` = 이름·메일 ID·사번 앞부분.
2. **직원을 고르면 지금 권한 보기**: `GET /api/v1/employee-auth/employees/{operatorId}` — **0.7.0 부터 HR_ADMIN 도 읽는다**(전에는 OPERATOR_ADMIN 만).
   | 필드 | 화면 |
   |---|---|
   | `state` | 로그인 상태(ACTIVE·DISABLED 등) |
   | `effectiveRoles`·`effectiveCenters` | **지금 실제로 쓰는 역할·센터** — 이것을 보여 준다(토큰과 같은 계산) |
   | `grants[]` | 승인된 권한 목록 — `role`·`centers`·`validFrom`·`validUntil`(null = 근무 기간까지)·`approvalId`·`approverId`(`RULE:…` 이면 자동 승인)·`appliedAt`. `validUntil` 이 지났으면 "끝남"으로 표시 |
   | `roles`·`centers` | 계정에 직접 둔 값(예전 이전 계정·관리자 보정) — 따로 보여 줄 필요는 없다(effective 에 이미 들어 있음) |
   | `lastLoginAt` | 마지막 로그인 |
   - 역할 이름·센터 지정 여부는 `GET /api/v1/employee-auth/roles`(이미 쓰는 것)로 바꿔 보여 준다.
   - 404 `EMPLOYEE_NOT_FOUND` = 아직 계정 발급 전(입사 등록 직후) — "계정 발급 중"으로.
3. **진행 중인 권한 요청**: 인사팀 앱 직원 상세의 `requestedGrants`(hr-service, 이미 있음)를 같이 보여 주면 좋다.

### 1-2. "준비 중"으로 둘 것
- **권한 추가·변경 요청** 버튼, **권한 회수** 버튼 — 백엔드(BO-01)를 PC 1 이 만들고 있다. 정해진 규칙(사용자 2026-11-15):
  - 추가·변경은 지금처럼 **승인을 거친다**(approval-service 승인함).
  - **회수는 승인 없이 바로** 적용하고 그 직원의 세션도 끊는다(기록은 남김).
  - 종료일을 비우면 **근무 기간(계약 종료일)까지**.
  - API 가 생기면 따로 알린다.

## 2. 백오피스 — "처리 대기" 메뉴 (OPERATOR_ADMIN)

### 2-1. 지금 동작하게 할 것 — "이행 확인 필요"
- 목록: `GET /api/v1/fulfillment-issues?state=OPEN` — **0.6.0 부터 OPERATOR_ADMIN 은 `mfcId` 를 비우면 모든 센터**(센터를 고르면 그 센터만). 상태 탭 OPEN·ACKNOWLEDGED·RESOLVED. 새 것부터 최대 500.
  - 항목: `issueId`·`orderId`·`mfcId`·`sku`·`lotId`·`blockedQty`·`reason`(QUALITY_HOLD 등)·`state`·`blockedAt`·`note`·`acknowledgedBy`·`acknowledgedAt`·`resolvedAt`.
  - 주문 번호를 누르면 이미 있는 직원 주문 상세(`GET /orders/lookups/{orderId}`)로.
- 확인 처리: `POST /api/v1/fulfillment-issues/{issueId}/acknowledgements` `{note}`(1~500자, 헤더 `Idempotency-Key`·`X-Source-System`) → ACKNOWLEDGED. 확인은 표시만 한다 — 주문 상태·재고는 바뀌지 않는다(자동 취소·환불 없음). 메모에 고객 개인정보를 적지 않게 안내 문구.
- 센터 관리자(MFC_SUPERVISOR)는 `mfcId` 를 꼭 넣어야 한다(없으면 403) — 이 메뉴는 본사 백오피스용이라 OPERATOR_ADMIN 만 보여 주면 된다.

### 2-2. "준비 중"으로 둘 것
- **멈춘 주문 조치**(사가가 ESCALATED 등으로 멈춘 주문 — 재시도·포기), **인계 재시도**, **현장 중지 재시도** — 백엔드(backoffice-bff BO-12)가 아직 501. 화면 범위·절차는 사용자 결정 뒤 backoffice 영역(PC 2)이 만든다.

## 3. 회신
- 만든 메뉴·빌드 해시, "준비 중" 자리, 필요한 필드가 더 있으면 pmart-clients `docs/handoff/` 회신으로.
