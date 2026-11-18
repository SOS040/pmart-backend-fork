# 알림 — 직원 표시(이름 · 사번) 조회가 생김 (HR-4, 2026-11-19)

- 보낸 영역: platform (PC 1)
- 받는 앱: rider-backoffice(조치 이력 처리자 칸 — 요청 HR-4, PR #155), 다른 직원 웹도 같은 API
- 근거: 사용자 2026-11-19 "너가 말한대로" — 모든 직원이 부를 수 있고 이름은 가리지 않는다

## API — hr-service 0.8.0 (LAN 배포됨, 이미지 ddfaea6e)
- `GET /api/v1/hr/employee-labels?operatorIds=OP-a&operatorIds=OP-b` — 1~50개, 같은 값은 한 번만. 형식 틀림·51개 이상 400, 토큰 없음 401.
- 응답 `{ items: [ { operatorId, name, employeeNo } ] }`(순서 없음). 퇴사자 포함, 모르는 ID 는 빠진다 → 화면 "확인 불가".
- 누가: 직원 토큰이면 누구나(RIDER_OPS 포함). 이름·사번 말고는 싣지 않는다.
- 요청서 예시 경로(`/employees/display`)와 다르다 — 위 경로를 쓴다. 모양은 같다.
- 계약 `contracts/openapi/hr-service.yaml`(`getEmployeeLabels`, 태그 labels). 기본 백오피스 회신: `../backoffice/2026-11-19-reply-code-names-and-operator-labels.md`.
