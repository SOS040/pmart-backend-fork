# [pmart-backend → pmart-clients] 알림: 인사·권한 — "자기 자신은 안 됨" 규칙 추가 (2026-11-17)

- 보낸 영역: platform(PC 1)
- 받는 앱: hr-backoffice(`web/hr-backoffice`), backoffice 승인함(권한 승인)
- 근거: 권한 빈틈 정리 `docs/architecture/auth/permissions.md` §9·§10-1(사용자 2026-11-17 "응 해줘"). LAN 배포됨(hr-service·approval-service·employee-auth-service, 2026-11-17).

## 1. hr-service — 인사 관리자 본인 처리 금지
`HR_ADMIN` 이 **자기 자신**(`operatorId` = 로그인한 직원)에게 아래를 하면 **403 `FORBIDDEN`**("자기 자신은 …할 수 없습니다(다른 인사 담당자가 처리)."):
- 소속 변경 `POST /hr/employees/{id}/affiliation-changes`
- 정지 `…/suspensions`, 정지 해제 `…/reinstatements`, 퇴사 `…/departures`
- (예전부터) 권한 요청 `…/grant-requests`
- 화면: 직원 상세가 **본인일 때 이 버튼들을 숨기거나 끄고** "다른 인사 담당자가 처리해요" 안내. 판정은 서버.

## 2. approval-service — 자기 권한 요청 승인 금지 (backoffice 승인함)
권한 승인(type `GRANT`)에서 **권한을 받을 직원 = 승인하려는 사람**이면 `OPERATOR_ADMIN` 이어도 **403**("자기 권한 요청은 승인·반려할 수 없습니다."). 예전 규칙(요청한 사람 ≠ 승인자)과 별개.
- 화면: 승인함 GRANT 행의 대상 직원이 본인이면 승인·반려 버튼을 끄고 안내.

## 3. employee-auth-service — 관리 경로 역할 변경 (화면 영향 없음 — 참고)
`PUT /employee-auth/employees/{id}` 로 역할·센터를 바로 바꾸는 것은 설정 스위치가 켜진 환경(LAN 시험)에서만, `OPERATOR_ADMIN`·`HR_ADMIN` 은 어디서든 이 경로로 못 준다(권한 요청 → 승인으로만). 인사팀 앱은 이 경로를 쓰지 않으니 바꿀 것 없음.

## 회신
화면 반영 뒤 `docs/handoff/clients/hr-backoffice/`(승인함은 `backoffice/`) 회신 또는 PR 본문으로.
