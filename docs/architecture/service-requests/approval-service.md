# approval-service 에 대한 요청 (MFC 쪽에서)

## AP-1. MFC 승인 번호(approvalRef) 실제 검증 (2026-11-09 기록)

- **요청한 곳**: mfc-inventory(실사 조정·폐기 실행), mfc-quality(격리 해제·폐기), mfc-capacity(운영 제한 BO-08).
- **지금(임시)**: 세 서비스의 `ApprovalRefs` 가 설정 `pmart.approval-ref.mode: RECORD_ONLY` 일 때만 번호를 **확인 없이 기록**한다. 이 값은 LAN·local 설정에만 있다(`deploy/lan/.config-repo/application-lan.yml`, `application-local.yml`). 설정이 없으면 CONFIG_REQUIRED 로 막히므로 **승인 연동 전에는 운영에서 쓸 수 없다**.
- **approval-service 가 할 것**
  1. MFC 승인 유형 추가(BO-15 재고 조정·폐기, BO-08 운영 제한). 지금 `ApprovalRules.targetType` 은 PRODUCT·PRICE·DISPLAY·CENTER_MASTER·SERVICEABILITY·INITIAL_STOCK 만.
  2. 승인 번호 검증 수단 — 내부 API(계약 `approval-service.yaml` 에 없음, InternalController 501) 또는 사건 중 하나로 정한다.
- **요청한 쪽에서 바꿀 것**: `ApprovalRefs` 를 실제 검증으로 바꾸고 RECORD_ONLY 를 LAN 시험용으로만 남긴다.
- **사용자 결정**: Q-11 "위험 추가 승인(금액·수량 기준)" 은 기준이 정해지면(`docs/business/flows/pending-user-decisions.md`).
- 근거: `services/mfc-*-service/.../application/ApprovalRefs.java`, `docs/business/flows/mfc-defaults-status.md` #8.
- **결정(2026-11-17 사용자 "추천대로")**: Q-11 기준 = 한 건 손실 **10만 원 이상** 또는 한 SKU **20개 이상**이면 본사 재고 승인(`INVENTORY_APPROVER`), 그 아래는 센터 관리자. approval-service 는 MFC 승인 종류와 승인 번호 확인 내부 API 를 만든다(PC 1). 기준 판정·번호 확인 호출·`ApprovalRefs` 전환은 MFC 서비스(PC 3). LAN `RECORD_ONLY` 는 PC 3 전환 뒤 끈다.

## AP-2. 직원 권한 요청 유형(BO-01)·hr-service 자동 제출·자동 승인 규칙 (2026-11-12 기록, ADR-0056)

> **2026-11-13 구현(ADR-0056 5-4, LAN 배포 전)**: 유형은 계약에 이미 있던 `GRANT`(정본 `employee-auth-service`, 원객체 = 운영자 ID). 제출 경로는 내부 API 대신 **사건** `GrantRequested`(hr-service outbox → platform.employee.v1 → approval-service `employeeEvents`) — hr-service 재시도 장치 없이 등록과 같은 트랜잭션. 자동 승인 규칙 표 `pmart.approval.auto-approval-rules`(설정 저장소, 승인자 = `RULE:<id>@v<버전>`, 행위자 SYSTEM), 맞는 행과 나머지 행은 승인을 나눈다(부분 승인 없음). 수동 심사 역할 `approver-roles.GRANT = OPERATOR_ADMIN`. 반영은 employee-auth-service(`employee_grant`, `GrantApplied`, 회신 `ApplicationReceiptRecorded`), 결과 표시는 hr-service. 남은 것: 같은 직원에 심사 중인 권한 승인이 있으면 새 요청이 DUPLICATE_APPROVAL(지금은 등록 때 한 번만 와서 생기지 않음) — 권한 변경 요청 화면(BO-01 변경·회수)을 만들 때 정한다.

- **요청한 곳**: hr-service(새, 구현 전), employee-auth-service(새, 구현 전).
- **필요한 것**
  1. 권한 요청 승인 유형(BO-01: 역할·권한 센터·기간·근거 행 단위) — 지금 `ApprovalRules.targetType` 에 없다.
  2. hr-service 가 직원 등록 때 요청 권한을 **권한 요청으로 자동 제출**하는 내부 경로(요청자 = 등록한 인사팀 직원, 서비스 토큰).
  3. **자동 승인 규칙**: 요청 역할이 `MFC_WORKER` 이고 권한 센터 = 등록한 소속 센터일 때만 심사 없이 승인, 승인자 = 규칙(ID·버전)으로 기록. 그 밖은 승인자 심사. 규칙 표는 approval-service 설정(바꿀 때 기록).
  4. 승인 결과(`ApprovalDecided`)의 반영 대상을 auth-service → **employee-auth-service**(직원 권한 정본)로. 반영 확인 `GrantApplied` 도 employee-auth-service 가 발행.
- **지금(임시)**: 없음 — 직원 역할은 Keycloak Realm 역할을 관리 화면에서 직접 넣는다(ADR-0054 결정 L, 전환 때까지).
- **요청한 쪽에서 바꿀 것**: hr-service 등록 화면이 요청 권한을 받아 제출, employee-auth-service 가 `ApprovalDecided` 구독·grant 저장.
- **사용자 결정**: 정함(2026-11-12 — 등록 화면에서 함께 받기 "1번을 하자", 자동 승인 "소속 센터 현장 역할만").
- 근거: `docs/adr/0056-staff-login-employee-auth-service-and-hr-service.md` 결정 5·6, `docs/architecture/auth/employee-auth-structure.md` §8-1, 업무 원문 BO-01.
