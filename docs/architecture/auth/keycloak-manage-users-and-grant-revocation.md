# Keycloak `manage-users` 와 PMart 권한 회수 구조 (2026-11-15)

사용자 질문(2026-11-15): "키클록에 manage-users 그런 거랑 방금 말한 권한 관리(BO-01)가 상관이 있나?" — 짧은 답: **직접 상관없다.** `manage-users` 는 Keycloak 안의 **로그인 계정**을 다루는 권한이고, PMart 업무 권한(역할·센터)은 Keycloak 이 아니라 **employee-auth-service DB** 에 있다. 아래는 그 근거와, PMart 에서 권한을 주고·빼는 구조 전체다.

관련: ADR-0056(결정 4·16, U-11), `employee-auth-structure.md`, `issuer-table.md`, `docs/business/flows/employee-account-provisioning.md` §1(BO-01), BackOffice BO-01.

---

## 1. 두 가지 "권한"은 다른 것이다

| | Keycloak 권한 | PMart 업무 권한 |
|---|---|---|
| 무엇 | Keycloak 관리 기능을 쓸 수 있는가(Realm 관리 콘솔·Admin API) | PMart API 에서 무슨 업무를 할 수 있는가 — 역할(`CS_AGENT`·`MFC_SUPERVISOR`…)과 권한 센터 |
| 어디에 | Keycloak Realm `pmart-staff` 의 `realm-management` 클라이언트 역할 | employee-auth-service DB: `employee_account`(계정에 직접 둔 값)·`employee_grant`(승인된 권한) |
| 누가 가짐 | 서비스 계정 `hr-provisioner`(hr-service 가 씀) — 사람은 아님 | 직원(운영자 ID `OP-…`) |
| 토큰에 실리나 | 실리지 않는다(PMart 토큰은 Keycloak 이 만들지 않음) | 실린다 — employee-auth-service 가 만드는 직원 토큰의 `roles`·`centers` |

ADR-0056 결정: **Keycloak 은 직원 신원(아이디·비밀번호·로그인 화면)만** 맡는다. PMart API 가 받는 직원 토큰은 employee-auth-service 가 발급한다(발급자 표 `staff-ea` 줄). Keycloak 이 직접 만든 토큰은 **2026-11-13 부터 PMart API 에서 401**(발급자 표에서 Keycloak `staff` 줄을 뺌 — `issuer-table.md`, pmart-config `0af0756`).

## 2. `hr-provisioner` 와 `manage-users`

ADR-0056 결정 16 — hr-service 가 Keycloak 을 다루는 자격:

- 클라이언트 `hr-provisioner`(Realm `pmart-staff`, 기밀, Service accounts 만 ON — 사람이 이 클라이언트로 로그인하지 않음).
- 서비스 계정 사용자에 `realm-management` 역할 **`manage-users`·`view-users`·`query-users` 만**. `realm-admin`·`manage-realm`·`manage-clients` 는 주지 않는다.
  - LAN Realm 파일: `deploy/lan/keycloak/pmart-staff-realm.json`(`fullScopeAllowed: false` 라 `clientScopeMappings` 로도 같은 역할을 준다 — 없으면 토큰에 안 실려 Admin API 403).
- Secret 원본 `deploy/lan/.secrets/keycloak-hr-provisioner-client-secret`, 설정은 pmart-config `{cipher}`.
- Keycloak 내부 주소로만 부른다(`/admin` 은 게이트웨이가 막음 — `api-gateway/CLAUDE.md` `/iam` 절).

hr-service 가 이 자격으로 **실제로 하는 일**(`KeycloakAdminClient`, 사용자 경로만):

| 언제 | Keycloak 에 하는 일 |
|---|---|
| 입사 등록(계정 발급) | 사용자 만들기(username = `<메일ID>@pmart.com`, 속성 `pmart_operator_id`), 비밀번호 설정 메일 보내기 |
| 정지·퇴사·계약 종료 | 사용자 `enabled = false`, Keycloak 세션 로그아웃 |
| 복귀 | `enabled = true` |

**하지 않는 일**: 역할·그룹 매핑 API 를 부르지 않는다(hr-service `CLAUDE.md`, 시험이 확인).

### 2-1. U-11 — `manage-users` 로 역할을 붙일 수 있나?

- 확인(2026-11-13, Keycloak 26.8.1, `HrProvisioningIT.u11ManageUsersCanAlsoMapRealmRoles`): **된다(204)**. `manage-users` 만 가진 서비스 계정이 사용자에게 Realm 역할을 붙일 수 있다.
- 그래서 막은 것:
  - (A) **발급자 표에서 Keycloak 줄을 뺐다**(2026-11-13). Keycloak 역할을 붙여도 그 역할이 실린 Keycloak 토큰을 PMart 가 받지 않는다.
  - (B) hr-service 는 역할 매핑 API 를 부르지 않는다(코드 규칙 + 시험).
- 결론: **`hr-provisioner` Secret 이 새도 PMart 업무 권한은 줄 수 없다.** 할 수 있는 피해는 로그인 계정 조작(사용자 만들기·비활성·비밀번호 재설정 메일·세션 로그아웃)까지다. 그래서 Secret 은 `{cipher}` 로만 두고, 새면 교체한다(결정 16 교체 절차: 새 Secret → 설정 반영·재시작 → 옛 Secret 폐기). Realm 관리 이벤트 저장이 켜져 있어 누가 무엇을 바꿨는지 남는다.

## 3. PMart 업무 권한 구조

직원 토큰의 역할·센터 = **계정 값 ∪ 지금 유효한 grant**(`EmployeeAccess`, 로그인·갱신 때마다 다시 계산).

```
employee_account (operator_id, state, roles[], centers[])   ← 관리 경로 PUT(OPERATOR_ADMIN)·예전 이전 계정(13명)
employee_grant   (grant_id = <approvalId>:<lineId>, role, centers[],
                  valid_from, valid_until, approval_id, approver_id,
                  revoked_at, revoked_by, revoke_reason)       ← 승인된 권한 한 줄씩(V4), 회수 칸(V6)

토큰 roles·centers = account.roles·centers ∪ { grant | valid_from ≤ 지금 < valid_until(없으면 끝없음) }
```

- 유효 기간은 Asia/Seoul 날짜 기준(시작일 00:00 ~ 마지막 날 다음 날 00:00). 종료일을 비우면 근무 기간까지(사용자 2026-11-15).
- 직원 access token 수명 15분. 갱신할 때마다 위 계산을 다시 하므로, 기한이 지난 grant 는 다음 갱신부터 빠진다.

### 3-1. 권한 주기(추가·변경) — 승인을 거친다

```
인사팀 앱(HR_ADMIN)
  POST /hr/employees/{id}/grant-requests  {grants:[{role, centers, validFrom?, validTo?}], reason}
      │  hr-service: 재직 중·발급 끝난 직원만, 자기 권한 403, 결정 안 된 요청 있으면 409 GRANT_REQUEST_PENDING
      │  requested_grant 행(SUBMITTED) + 같은 트랜잭션 GrantRequested(요청자 = 이 인사팀 직원)
      ▼  platform.employee.v1
approval-service: 자동 승인 규칙(자기 센터 MFC_WORKER 등)에 맞으면 바로 승인, 나머지는 승인함(심사 중)
      │  승인자 ≠ 요청자 (자기 승인 금지)
      ▼  ApprovalDecided (platform.approval.v1, type GRANT)
employee-auth-service: 승인된 행 → employee_grant 한 줄 → GrantApplied
hr-service:            requested_grant → APPROVED·REJECTED (화면 표시용)
      ▼
다음 로그인·갱신(15분 안)부터 토큰에 새 역할·센터
```

등록 때 고른 권한도 같은 길(계정 발급이 끝날 때 hr-service 가 GrantRequested 를 낸다).

### 3-2. 권한 회수 — 승인 없이 즉시 (사용자 결정 2026-11-15)

```
인사팀 앱(HR_ADMIN) 또는 백오피스(OPERATOR_ADMIN)
  POST /employee-auth/employees/{id}/grant-revocations  {grantId, reason}
      ▼
employee-auth-service (EmployeeAdminService.revokeGrant, 한 트랜잭션)
  1. 검사: 자기 권한 403 / 그 직원 grant 아님 404 / OPERATOR_ADMIN·HR_ADMIN 역할 grant 를 HR_ADMIN 이 회수 403
           이미 회수됨 → 지금 상태 200(멱등) / 이미 기한 끝남 409
  2. employee_grant: valid_until = 지금, revoked_at·revoked_by·revoke_reason·revoke_source (행은 지우지 않음 — 감사 이력)
  3. 그 직원의 모든 세션 REVOKED(사유 GRANT_REVOKED) — refresh token 이 더는 갱신되지 않음
  4. SubjectBlocked(사유 SESSIONS_REVOKED, 주체 = 운영자 ID, blockedBefore = 지금) → outbox
      ▼  platform.subject-block.v1
api-gateway (SubjectBlockEvents → Redis pmart:gw:subject-block:<iss>|<sub>, 2시간 보관)
  토큰 iat ≤ blockedBefore 이면 401 SUBJECT_BLOCKED  — 이미 나간 access token(최대 15분)도 바로 막힘
      ▼
직원이 다시 로그인 → 새 토큰(iat > blockedBefore)은 통과, 역할·센터는 남은 권한으로 다시 계산
```

누가 무엇을(사용자 2026-11-15 "3번까지 넣어서"):

| | 인사팀 앱(HR_ADMIN) | 백오피스(OPERATOR_ADMIN) |
|---|---|---|
| 권한 보기 | ○ | ○ |
| 추가·변경 요청 | ○(입사·이동 등) | — |
| 승인 | — | ○(승인함, 요청자와 다른 사람) |
| 일반 권한 회수(즉시) | ○(이동·퇴사 등) | ○(보안상 긴급 회수) |
| `OPERATOR_ADMIN`·`HR_ADMIN` 권한 회수 | ✕(403) | ○ |
| 자기 권한 회수·요청 | ✕ | ✕ |

회수에 승인을 두지 않은 이유: 줄이는 방향이라 위험이 낮고, 퇴사·사고 때 기다리면 안 된다. 대신 사유를 필수로 받고 기록을 남기며, 관리자 권한만은 운영 관리자로 한정해 인사팀 계정 하나로 관리자를 잠그지 못하게 했다.

### 3-3. 그 밖에 권한이 끊기는 길

| 상황 | 경로 | 결과 |
|---|---|---|
| 정지·퇴사·계약 종료 | hr-service → `EmployeeSuspended`·`EmployeeLeft` → employee-auth-service | 계정 상태 바뀜, 모든 세션 종료, `SubjectBlocked`(ACCOUNT_DISABLED). hr-service 가 Keycloak 사용자도 비활성(위 §2) |
| 관리자 보정 | `PUT /employee-auth/employees/{id}`(OPERATOR_ADMIN) | 계정에 직접 둔 역할·센터·상태를 바꿈(변경 기록). 비활성이면 세션 종료·차단 |
| grant 기한 지남 | 없음(계산) | 다음 갱신(15분 안)부터 빠짐 — 즉시 차단은 하지 않음 |

### 3-4. 아직 없는 것
- 소속 변경(A센터 → B센터) 때 A센터 grant 자동 종료 — 지금은 인사팀이 회수 API 로.
- 회수를 hr-service 화면 표시(`requestedGrants`)에 알리는 사건 — 지금은 employee-auth 조회의 `grants[].revokedAt` 으로 본다.
- 계정에 직접 둔 역할(예전 이전 계정)은 회수 API 대상이 아니다 — 관리 경로 PUT.

## 4. 한 줄 정리
- Keycloak `manage-users` = **로그인 계정 관리**(hr-service 전용 서비스 계정). Keycloak 역할을 붙일 수는 있지만 PMart 토큰에 안 들어가므로 업무 권한이 되지 않는다.
- PMart 업무 권한 = **employee-auth-service 의 계정 값 + 승인된 grant**. 주기는 승인, 빼기는 즉시(세션 끊기 + 게이트웨이 차단). 관리자 권한 회수는 운영 관리자만.
