# [pmart-backend → pmart-clients] 알림: 역할 목록 API(BR-57)·본사 부서 목록 API — LAN 배포 (2026-11-15)

- 보낸 영역: platform
- 원 문서: pmart-clients `docs/handoff/hr-backoffice/2026-11-15-backend-rider-ops-role-and-role-list.md` §2(BR-57), 백엔드 회신 `2026-11-15-reply-role-list-api.md`(결정 대기 → **만듦**, 사용자 2026-11-15)
- 백엔드 95e8c9ed(LAN 배포). 계약 `employee-auth-service.yaml` 0.6.0, `hr-service.yaml` 0.2.0.

## 1. 역할 목록 — `GET /api/v1/employee-auth/roles`
- 직원 토큰이면 역할과 상관없이 부를 수 있다. 응답 `{items:[{code, name, centerScoped, order}]}`(order 오름차순, 지금 13개 — 이름 예 `RIDER_OPS` → "라이더 운영").
- `centerScoped=true`(지금 `MFC_SUPERVISOR`·`MFC_WORKER`)면 권한 센터를 함께 고르게 안내(판정은 서버).
- 화면의 고정 목록 `GRANT_ROLES` 대신 이 응답을 쓴다. 못 불러오면 빈 목록이 아니라 오류로(요청서대로).

## 2. 본사 부서 목록 — `GET /api/v1/hr/departments`, `PUT /api/v1/hr/departments/{code}`
- 사용자 2026-11-15: "지금 입력한 값들을 목록 리스트로 추가해줘. 예) 1004". LAN 에는 지금 입력돼 있던 `1004`·`HQ`·`CUSTOMER_SUPPORT` 가 이름 없이 들어 있다.
- 목록 `{items:[{code, name?, employeeCount}]}`(코드 순, 퇴사 아닌 소속 직원 수). `HR_ADMIN` 만.
- 이름 붙이기 `PUT …/{code}` `{name?}`(60자, 비우면 지움, 없는 코드면 새로 만듦) — `Idempotency-Key`·`X-Source-System`.
- 직원 등록·소속 변경에서 처음 보는 부서 코드는 서버가 목록에 자동으로 더한다(거절하지 않음).
- 화면: 소속 "본사 부서"를 직접 입력 대신 이 목록에서 고르게(이름 있으면 "이름(코드)"), 새 부서는 입력도 허용. 부서 이름 관리 화면(작은 표)도 부탁.
