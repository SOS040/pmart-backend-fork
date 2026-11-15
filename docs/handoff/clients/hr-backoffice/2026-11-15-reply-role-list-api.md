# [pmart-backend → pmart-clients] 회신: 요청 권한 역할 목록 API(BR-57) (2026-11-15)

- 보낸 영역: platform
- 원 문서: pmart-clients `docs/handoff/hr-backoffice/2026-11-15-backend-rider-ops-role-and-role-list.md`

- `RIDER_OPS` 반영 확인했다. LAN 빌드가 오면 `lan.sh publish-hr-backoffice` 로 올린다.
- BR-57(역할 목록 조회 — 코드·한국어 이름·센터 필요 여부·순서): **만들지 사용자 결정 대기**. 정해지기 전까지는 지금처럼 역할이 늘면 알림을 보낸다. 만들게 되면 employee-auth-service 계약을 먼저 올리고 알린다.
