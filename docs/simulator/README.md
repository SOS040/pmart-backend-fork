# 시뮬레이터 관련 문서 (백엔드 쪽)

시뮬레이터와 관련해 백엔드에서 만든 결과물은 여기에 둔다(사용자 2026-11-09). **MFC 와 CDC 를 폴더로 나눈다**(사용자 2026-11-09). 백엔드 → 시뮬레이터 회신·공지는 여기가 아니라 `docs/handoff/simulator/`.

| 위치 | 내용 |
|---|---|
| [scope.md](scope.md) | 시뮬레이션 구현 범위(공통) — MFC 먼저, 그다음 CDC(출고·피킹·상차만, 입고 없음), TMS 는 구현(OR-Tools 경로), MFC 도면 = 시뮬레이터 평면 |
| [mfc/](mfc/README.md) | MFC 시뮬레이터(`../pmart-mfc-simulation`) — 실서버 연결 결정(SIM-1~), 평면 도면·칸 대응·MFC 문서와 다른 점 |
| [cdc/](cdc/README.md) | CDC 시뮬레이터 — 아직 없음(MFC 완성 뒤, 근거는 WMS 문서) |
