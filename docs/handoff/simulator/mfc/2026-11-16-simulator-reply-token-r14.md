# [pmart-backend → pmart-mfc-simulation] 회신: R-14 시뮬레이터 토큰 발급 경로 (2026-11-16)

- 보낸 영역: platform(PC 1 — 로그인·토큰은 PC 1 담당)
- 원 문서: `pmart-mfc-simulation/docs/backend-requests.md` R-14(백엔드 `main` `00845b9f` 기준 작성)
- 근거: ADR-0056 D5(LAN 시험 전용 토큰 교환), `services/employee-auth-service/.../api/DevTokenController.java`, 설정 `pmart-config/employee-auth-service-lan.yml` `pmart.employee-auth.dev-token-exchange.enabled: true`, 전례 `deploy/lan/checks/staff_auth.py`

## 답: 계속 써도 된다 — LAN 에서 꺼지거나 바뀔 계획 없음
- 흐름은 지금 쓰는 그대로: Vite 개발 서버가 Keycloak(`pmart-staff`, 개발 클라이언트 `pmart-dev-cli`)에 시험 계정 비밀번호로 로그인 → employee-auth-service `POST /api/v1/internal/dev-token-exchange` → PMart 직원 토큰(15분). 교환 때 일반 로그인과 같은 직원 상태 확인을 거치므로 **토큰의 역할·센터는 그 계정의 실제 값**이다(시뮬레이터가 정하지 않는다).
- **SIM-11 "공개 API 만"의 예외로 인정한다** — 토큰 발급 한 곳만. 이 경로는 게이트웨이에 없으니(내부 경로) 개발 서버가 employee-auth-service 로 직접 부르는 지금 방식을 유지한다. 업무 API 는 계속 게이트웨이의 공개 경로로.
- **LAN 전용**: 이 경로는 설정이 켜진 LAN 에만 있고 운영(prod)에는 없다(설정이 없으면 경로 자체가 404). 운영 연결은 생각하지 않는다. LAN 에서 끄거나 바꾸게 되면 이 폴더(`docs/handoff/simulator/mfc/`)에 먼저 공지한다.

## 지킬 것 (지금 요청서에 적힌 그대로 — 확인)
1. 비밀번호는 Vite 서버만 `deploy/lan/.secrets/keycloak-<계정>-password` 에서 **읽기만**. 브라우저에는 토큰만, 토큰은 메모리에만(localStorage·sessionStorage 금지). 토큰·비밀번호를 로그·화면에 남기지 않는다.
2. **비밀번호를 새로 정하지 않는다** — `staff_auth.py` 의 "파일이 없으면 Keycloak 관리 API 로 새로 정함"은 쓰지 않는다(MFC 작업자 70명 공용 비밀번호가 깨진다). 파일이 없으면 실서버 모드를 멈추고 알린다.
3. 계정: 관찰(읽기) `claude-mfc-wrk`, 가상 작업자 `mfcw-pp-01`~`10`(사용자 2026-11-16). 나머지 `mfcw-*` 는 실제 PDA 용이라 쓰지 않는다. 역할·센터를 바꾸는 관리 경로(`staff_auth.py` 의 roles·centers 맞춤)도 쓰지 않는다.
4. 15분이 지나면 같은 흐름으로 새로 받는다. 직원 정지·로그아웃 전파(ADR-0061)로 401 이 나면 그 계정은 멈춘다(다시 받아도 막히면 운영자 확인).
5. `X-Source-System`·`X-Device-Id` 는 `mfc-simulator` — 로그 구분용으로 좋다.
