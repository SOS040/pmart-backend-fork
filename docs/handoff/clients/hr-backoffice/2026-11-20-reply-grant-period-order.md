# 회신 — 권한·근무 기간 순서 검증 (hr 0.9.1, BR-75)

- 보낸 영역: platform (PC 1)
- 작성: pmart-backend, 2026-11-20 — 사용자 "해줘"
- 답하는 것: pmart-clients PR #331 `docs/handoff/hr-backoffice/2026-11-20-backend-grant-period-order.md` GP-1·GP-2 (PC 2 백엔드 백오피스가 전달). 번호 **BR-75** 로 매김.
- 상태: **LAN 배포 2026-11-20 21:30 KST** — hr-service(PC 5) a7456a04

## GP-1 — 됨
- 등록 `requestedGrants`·`POST /hr/employees/{id}/grant-requests` 의 `grants`: 끝(`validTo`) < 시작(`validFrom`) 이면 **400 VALIDATION_FAILED, details field `requestedGrants[i].validTo` / `grants[i].validTo`**. 비운 값은 서버가 채우는 값(시작 = 근무 시작일, 끝 = 계약 종료일)으로 판정 — 예: 시작만 2027-03-01 로 보내고 계약 종료일이 2026-12-31 이면 400.
- 같은 날(시작 = 끝)은 허용. 정규직(종료일 없음)에 끝을 비우면 통과.
- 근무 기간: 등록의 `endDate < startDate` 400(field `endDate`)은 전부터. **복귀(reinstate)** 도 새 계약 종료일 < 근무 시작일이면 400(field `endDate`) 추가.
- 계약 `contracts/openapi/hr-service.yaml` 0.9.1(설명만 — 모양 변경 없음).

## GP-2 — 확인함(읽기만)
- LAN hr DB `requested_grant`(서버가 채운 값 포함)·`employee`(endDate < startDate), employee-auth DB `employee_grant`(valid_until ≤ valid_from) 모두 **거꾸로 된 기간 0건**(2026-11-20 21:2x). 정리할 것 없음.
- 제보된 2027-03-01 ~ 2026-11-20 은 저장 행이 없다 — 화면 목·요청이 거절·되돌려졌거나 다른 환경일 수 있음. 다시 보이면 직원 ID 를 알려 주세요.
