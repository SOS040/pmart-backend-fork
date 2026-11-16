보낸 영역: platform(PC 1)

# [pmart-backend → pmart-clients] 회신: 권한 상세 승인 칸에 승인자 이름 (2026-11-17)

- 원 보고: PC 2 확인(2026-11-16, `docs/worklog/backoffice/2026-11-16.md`) — hr-backoffice 권한 상세 "승인된 권한"의 승인 칸이 이름 대신 운영자 ID(`OP-…`)로 나온다.
- 사용자 결정(2026-11-17): 백엔드가 이름을 함께 준다.

## 바뀐 것 — employee-auth-service 계약 0.10.0

`GET /api/v1/employee-auth/employees/{operatorId}`(그리고 같은 `Employee` 를 돌려주는 `PUT …/employees/{id}`·`POST …/grant-revocations`)의 `grants[]` 각 줄에 두 칸이 더해졌다(둘 다 선택, null 가능 — 기존 칸은 그대로).

| 새 칸 | 옆 칸 | 뜻 |
|---|---|---|
| `approverName` | `approverId` | 승인한 직원 이름(hr-service 인사 기록) |
| `revokedByName` | `revokedBy` | 회수한 직원 이름(회수된 줄만) |

## 화면에서

- 승인 칸: `approverName` 이 있으면 그것, 없으면 —
  - `approverId` 가 `RULE:` 로 시작하면 **"자동 승인"**(소속 센터 MFC_WORKER 자동 승인 규칙 — 사람이 아니라 이름이 늘 null),
  - 아니면 지금처럼 `approverId`(인사 기록이 없는 이전 계정이거나, 잠깐 hr-service 를 못 읽은 경우 — 조회 자체는 실패하지 않는다).
- 회수 표시: 같은 방식으로 `revokedByName` → 없으면 `revokedBy`.
- 이름은 화면 표시에만 쓰고 저장·로그에 남기지 않는다.

## 요청 이력(hr-service `requestedGrants`)

- 요청 이력에는 승인자·요청자 ID 칸이 없어(`approvalRef`·`submission` 만) 같은 문제는 없다. 바꿀 것 없음.

## 배포

- 아직 LAN 배포 전(PC 1 이 hr-service 0.5.0·employee-auth-service 0.10.0 함께 올린 뒤 알림). 배포 전에는 새 칸이 오지 않으므로 위 대체 표시로 그대로 동작한다.
