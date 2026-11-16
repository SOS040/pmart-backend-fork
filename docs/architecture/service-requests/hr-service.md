# hr-service 에 요청

## HR-1 직원 이름 내부 조회 (2026-11-17 구현)

- **요청한 곳**: employee-auth-service(platform, PC 1) — 인사팀 앱 권한 상세의 승인 칸이 이름 대신 `OP-…`(PC 2 확인 2026-11-16, `docs/worklog/backoffice/2026-11-16.md`). 사용자 2026-11-17 "백엔드에서 이름을 같이 준다".
- **필요한 것**: 운영자 ID 여러 개 → 이름. employee-auth-service 는 이름을 저장하지 않으므로(그 서비스 CLAUDE.md 데이터 규칙) 정본 hr-service 에서 읽는다.
- **만든 것**: `GET /api/v1/internal/employee-names?operatorIds=…`(계약 hr-service 0.5.0, 토큰 없는 내부 호출, 1~200개, 퇴사자 포함, 모르는 ID 는 빠짐) → `{items: [{operatorId, name}]}`. employee-auth-service 0.10.0 이 `grants[].approverName`·`revokedByName` 에 쓴다(실패면 null).
- **요청한 쪽에서 바꿀 것**: 같은 커밋에서 했다(`EmployeeNames`).
- **사용자 결정 필요**: 없음.
