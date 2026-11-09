# handoff — 백엔드 → MFC 시뮬레이터

- 상대: `../pmart-mfc-simulation`(MFC 현장을 화면으로 보여 주는 3D 시뮬레이터). 백엔드 작업에서는 그 저장소를 **읽기만** 한다.
- 시뮬레이터 → 백엔드 요청: 그 저장소 `docs/backend-requests.md`(R-01~). 회신은 여기에 R 번호를 그대로 써서 남긴다.
- 원칙(사용자 2026-11-09): 백엔드 계약이 정본, **시뮬레이터와 안 맞는 부분은 시뮬레이터가 맞춘다.** 백엔드가 시뮬레이터에 영향 주는 변경을 하면 여기에 공지한다.
- 연결 설계·결정(SIM-1~): `docs/simulator/mfc-simulator-integration.md`.
- 파일 이름 `YYYY-MM-DD-simulator-<reply|notice|request>-<주제>.md`.
