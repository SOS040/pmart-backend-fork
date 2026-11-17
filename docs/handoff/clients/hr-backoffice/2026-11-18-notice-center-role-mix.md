# [pmart-backend → pmart-clients] 알림: 인사팀 앱 권한 요청 — 새 거절 코드 CENTER_ROLE_MIX (2026-11-18)

- 보낸 영역: platform(PC 1)
- 받는 앱: hr-backoffice(`web/hr-backoffice`) — 담당 PC 2
- 근거: `docs/architecture/auth/permissions.md` §9 큰 것 2(직원 토큰에서 역할·센터가 따로 합쳐짐). 사용자 결정 2026-11-17 "(가) PC 1 만으로", 구현 2026-11-18.
- 계약: hr-service **0.7.0**(info.description 0.7.0, `requestEmployeeGrants` 409 설명), employee-auth-service 0.11.1.
- 이어지는 문서: `2026-11-17-request-grant-request-validation.md`(HR-2 — 센터 칸·이미 가진 권한·`GRANT_ALREADY_ACTIVE`). 이 알림은 거절 코드 하나를 더할 뿐이다.

## 무엇이 바뀌나
직원 토큰은 "모든 역할"과 "모든 센터"를 따로 싣는다. 그래서 A 센터 관리자 + B 센터 작업자인 직원은 B 센터에서도 관리자가 된다. 서버가 이제 이런 조합을 만드는 권한 요청을 거절한다.

- 규칙: 같은 때 유효한 권한 안에서 **MFC 역할(센터 관리자·센터 작업자) 하나하나가 가진 센터 = 그 직원의 권한 센터 전체**.
  - 받음: 관리자@A + 관리자@B, 관리자@A + 작업자@A, MFC 역할 + 센터 없는 역할(MD·고객센터 등).
  - 거절: 관리자@A + 작업자@B, 관리자@{A,B} + 작업자@A(B 에서 작업자가 됨).
  - 기간이 겹치지 않으면 괜찮다(관리자@A 가 끝난 다음 날부터 작업자@B).
- 권한 추가·변경(`POST /hr/employees/{id}/grant-requests`): 지금 권한과 섞이면 **409 `CENTER_ROLE_MIX`**. `details[0].field` = `grants[i]`, `reason` = `existingGrantId=<id>;role=<역할>;centers=<A,B>`. `detail` 에 한국어 설명(어느 권한과 섞이는지, "다른 센터 권한을 먼저 회수하거나 같은 센터로 요청").
- 같은 요청의 행끼리 섞이면(등록 `requestedGrants` 포함) **400 `VALIDATION_FAILED`**, field `grants[i]`·`requestedGrants[i]`.

## 화면에 부탁
1. 409 `CENTER_ROLE_MIX` 를 받으면 서버 `detail` 문구를 그대로 보이고, `details[0].reason` 의 `existingGrantId` 로 권한 목록의 그 줄을 강조하면 좋다(`GRANT_ALREADY_ACTIVE` 와 같은 방식).
2. (선택) 미리 막기: 이미 가진 MFC 역할의 센터와 다른 센터로 다른 MFC 역할을 고르면 "센터마다 다른 MFC 역할은 줄 수 없습니다" 안내. 서버가 최종 판정이므로 꼭 필요하지는 않다.

## 상태
- 백엔드 구현·시험 끝. LAN 배포는 PC 1 일지(`docs/worklog/platform/2026-11-18.md`)에 적는다.
- LAN 데이터 확인(2026-11-18): 재직 87명 중 이미 섞인 직원 0명.
