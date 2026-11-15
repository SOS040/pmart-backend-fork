# [pmart-backend → pmart-clients] 알림: 직원 역할 `RIDER_OPS` 추가 — 요청 권한 역할 목록 (2026-11-15)

- 보낸 영역: platform
- 작성: pmart-backend(PC 1), 2026-11-15
- 대상: `web/hr-backoffice`(직원 등록 화면의 **요청 권한 역할 고르기 목록**)
- 근거: 사용자 2026-11-15 — 라이더 운영 전용 역할을 만든다("응"). employee-auth-service 역할 목록에 들어감(pmart-config 9ef88f0, LAN 반영). 기본 백오피스 라이더 운영 메뉴: `../backoffice/2026-11-15-clients-request-backoffice-cs-rider-menus.md`.

## 할 것
- 역할 고르기 목록(11-13 요청서 §3 — 지금 12개)에 **`RIDER_OPS`(라이더 운영)** 를 더해 주세요. 이제 13개: `OPERATOR_ADMIN, HR_ADMIN, CENTER_MASTER_APPROVER, CS_AGENT, DISPLAY_APPROVER, INVENTORY_APPROVER, MD, MFC_SUPERVISOR, MFC_WORKER, PRICE_APPROVER, PRODUCT_APPROVER, RIDER_OPS, SERVICEABILITY_APPROVER`.
- 센터를 고르지 않는 역할이다(본사 역할). 승인은 다른 역할처럼 승인자(`OPERATOR_ADMIN`)가 기본 백오피스 승인함에서.
- 목록에 없는 역할을 고르면 employee-auth-service 가 그 행만 거절한다(`UNKNOWN_ROLE`) — 백엔드 목록과 화면 목록을 같게 유지.
- LAN 확인: 2026-11-15 시험 계정 `test-cs-rider@pmart.com`(CS_AGENT·RIDER_OPS)을 이 흐름(인사 등록 → 승인)으로 만들어 토큰에 두 역할이 들어오는 것을 확인했다.
