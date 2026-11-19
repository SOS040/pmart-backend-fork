# 회신 — 인사팀 대시보드 H-D1·H-D2

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-19
- 답하는 것: `pmart-clients docs/handoff/hr-backoffice/2026-11-19-backend-hr-dashboard.md` (사용자 "응 해줘" — 추천대로)
- 상태: 백엔드 main 반영(hr-service 0.9.0, 마이그레이션 없음), LAN 배포는 사용자 승인 뒤(배포되면 아래에 적는다). 둘 다 `HR_ADMIN` 만(아니면 403).

## H-D1 직원 목록 거르기 — `GET /hr/employees`
| 쿼리 | 뜻 |
|---|---|
| `provisioningStep` | 계정 발급 단계, 쉼표로 여럿(`FAILED,COMPENSATED`). 모르는 값 400 |
| `grantSubmission` | `PENDING`·`SUBMITTED`·`APPROVED`·`REJECTED` — 요청 권한 중 하나라도 그 상태인 직원 |
| `endDateFrom`·`endDateTo` | 계약 종료일(서울 날짜, 둘 다 포함). 종료일 없는 직원은 빠진다. from > to 400 |
| `createdFrom`·`createdTo` | 등록 시각(date-time, 둘 다 포함). from > to 400 |
| `sort` | `CREATED_DESC`(기본 — 지금과 같음, 사번 내림차순) · `END_DATE_ASC`(종료일 빠른 순, 종료일 없는 직원 제외) |
- 커서는 같은 `sort` 로만 이어 쓴다(`END_DATE_ASC` 커서는 `종료일|사번` 모양 — 그대로 돌려주면 된다).

## H-D2 인원 세기 — `GET /hr/employees/counts?endingWithinDays=7`
```json
{ "asOf": "...", "active": {"value": 12, "complete": true}, "suspended": {...}, "left": {...},
  "provisioningFailed": {...}, "grantPendingApproval": {...}, "endingSoon": {...},
  "endingWithinDays": 7, "endingFrom": "2026-11-19", "endingTo": "2026-11-25" }
```
- `provisioningFailed` = 계정 발급 FAILED·COMPENSATED, `grantPendingApproval` = SUBMITTED 요청 권한이 있는 직원(퇴사자 제외), `endingSoon` = **재직 중** 계약 종료일이 `endingFrom`~`endingTo`(오늘 포함 N일).
- DB 집계라 지금은 늘 `complete: true`. 원천을 못 읽으면 오류(5xx) — 0 으로 바꾸지 않는다.
- 누르면 이동할 목록: 발급 실패 `?provisioningStep=FAILED,COMPENSATED`, 승인 대기 `?grantSubmission=SUBMITTED`(퇴사자도 나오니 필요하면 `&status=ACTIVE`), 종료 임박 `?status=ACTIVE&endDateFrom={endingFrom}&endDateTo={endingTo}&sort=END_DATE_ASC`, 최근 등록 `?size=5`(기본 정렬).

## 질문 답
1. bff `work-counts` 가 아니라 **hr-service 에 따로**(`/hr/employees/counts`) — 직원 데이터의 주인이 hr-service 이고 HR_ADMIN 권한도 여기서 본다. bff 를 거치지 않는다.
2. 계약 종료 "임박" 기준은 업무 문서에 정한 값이 없다 — **기본 7일**, `endingWithinDays`(1~90)로 바꿀 수 있다.
