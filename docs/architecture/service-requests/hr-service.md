# hr-service 에 요청

## HR-1 직원 이름 내부 조회 (2026-11-17 구현)

- **요청한 곳**: employee-auth-service(platform, PC 1) — 인사팀 앱 권한 상세의 승인 칸이 이름 대신 `OP-…`(PC 2 확인 2026-11-16, `docs/worklog/backoffice/2026-11-16.md`). 사용자 2026-11-17 "백엔드에서 이름을 같이 준다".
- **필요한 것**: 운영자 ID 여러 개 → 이름. employee-auth-service 는 이름을 저장하지 않으므로(그 서비스 CLAUDE.md 데이터 규칙) 정본 hr-service 에서 읽는다.
- **만든 것**: `GET /api/v1/internal/employee-names?operatorIds=…`(계약 hr-service 0.5.0, 토큰 없는 내부 호출, 1~200개, 퇴사자 포함, 모르는 ID 는 빠짐) → `{items: [{operatorId, name}]}`. employee-auth-service 0.10.0 이 `grants[].approverName`·`revokedByName` 에 쓴다(실패면 null).
- **요청한 쪽에서 바꿀 것**: 같은 커밋에서 했다(`EmployeeNames`).
- **사용자 결정 필요**: 없음.

## HR-2 권한 요청 검사 — 중복 부여·센터 규칙 (2026-11-17 기록, platform PC 1 — 사용자 수락)
- **문제(2026-11-17 확인)**: `POST /hr/employees/{id}/grant-requests`(그리고 등록 때 `requestedGrants`)는 "결정 안 된 요청이 있으면 409 `GRANT_REQUEST_PENDING`"만 본다.
  - 이미 유효한 **같은 역할·센터가 겹치는·기간이 겹치는** 권한을 또 요청할 수 있다 → 승인되면 employee-auth `employee_grant` 에 줄이 하나 더 생김(회수 때 한 줄만 회수하면 권한이 남는 위험, 목록 중복, `GrantEnded` 가 줄마다).
  - 한 요청 안에 같은 역할을 두 줄 넣을 수 있다.
  - 센터 지정 역할(`role-info.centerScoped` — `MFC_SUPERVISOR`·`MFC_WORKER`)에 센터가 없거나, 센터가 필요 없는 역할에 센터가 있어도 받는 것으로 보인다(구현 때 확인).
  - 인사팀 앱 화면(`web/hr-backoffice` 권한 관리)도 막지 않는다 — 지금 권한을 보여 주기만.
- **사용자 결정(2026-11-17)**: 제안 수락 — "백엔드부터 일단 내쪽(PC 1)에서 작업하고 클라이언트한텐 그 요청서로 보내자".
- **할 것(PC 1)**:
  1. hr-service 권한 요청 검사: 같은 요청 안 중복 역할 400, 센터 규칙(`centerScoped` 역할은 센터 1개 이상·아닌 역할은 센터 없음) 400, 이미 유효한 같은 권한(역할 같고 센터·기간 겹침) 409 `GRANT_ALREADY_ACTIVE` — 지금 권한은 employee-auth 에 묻는다(내부 조회). 역할 목록·`centerScoped` 도 employee-auth 설정을 정본으로.
  2. employee-auth 반영 안전망(`GrantApprovalHandler`): 승인된 줄이 이미 유효한 같은 권한과 겹치면 새 줄을 만들지 않고 기록만.
  3. 그 뒤 인사팀 앱 요청서(PC 2, `docs/handoff/clients/hr-backoffice/`): 센터가 필요 없는 역할이면 센터 칸 숨김·끔, 센터 지정 역할이면 센터 필수, 이미 가진 권한 표시.
- **정할 것**: 이미 있는 권한의 **기간 연장**(종료일 늘리기)을 새 요청으로 받을지, "연장" 요청으로 구분할지.
- **상태**: 작업 예정(LOT 바코드 일 다음).

