# [pmart-backend → pmart-clients] 알림: 현재 세션 응답에 센터 이름 (2026-11-16)

- 보낸 영역: platform
- 받는 앱: web-common(`web/core` 세션 — 로그인·세션 코드는 PC 1 규칙이지만 표시 쪽은 각 앱), backoffice·hr-backoffice·mfc-backoffice
- 결정: 사용자 2026-11-16 "센터 이름 넣고 싶어". 계약 `employee-auth-service.yaml` **0.9.0**.

## 바뀐 것
- `GET /api/v1/employee-auth/sessions/current` 응답에 **`centerDetails`** 가 더해졌다 — `centers` 와 같은 순서로 `[{centerId, name}]`.
  - `name` = center-master-service 의 센터 이름(서버가 10분 캐시). 서버가 이름을 못 읽으면 `name: null` — 화면은 `centerId`(코드)를 그대로 보여 준다.
  - `centers`(코드 배열)는 그대로 — 권한 판단·API 호출에는 계속 코드를 쓴다. 이름은 표시용.
- 센터 선택 상자·헤더의 "내 센터" 표시를 코드 대신 이름으로 바꾸면 된다(MFC 백오피스·PDA 센터 고르기 등).
