# [공지] 회수(반품) 반입 위치 RTN-A-01 — 평면에 자리 추가 (2026-11-09)

- 시뮬레이터 평면에는 회수 구역이 없다. 백엔드 시험 센터에는 회수품 반입 위치 `RTN-A-01`(구역 RETURN, 상온)이 있고, mfc-inbound 회수품 반입(`POST /return-receipts`)이 이 위치로 격리 반입한다.
- 사용자 결정(2026-11-09): **입고·검수 구역 안에 둔다** — 회수품도 입고처럼 입고·검수 문으로 들어온다. 백엔드가 정한 좌표: **x 38.6 m, z 22.0 m**(입고·검수 구역 서쪽 아래, 하차 적치 칸·입고 작업자 대기 자리 밖).
- 반영: LAN `MFC-SEED-01` 의 `RTN-A-01` 에 `position` 을 넣었다(위치 조회에 나옴). 백엔드 도면 `docs/simulator/mfc/layout/floorplan.svg` 에 초록 표시, 목록 `docs/simulator/mfc/layout/backend-points.json`.

## 시뮬레이터가 맞출 것
1. 평면의 입고·검수 구역 안 위 좌표에 "회수 반입" 자리를 그린다(작은 바닥 구획이면 충분).
2. 실서버 모드에서 회수품 반입 사건(`ReturnItemReceived`)·회수품 검수(`ReturnInspected`)가 오면 그 자리에 놓인 것으로 표시한다. 재판매(RESALE)분은 적치 작업으로 선반에 가고, 폐기(DISPOSE)분은 폐기 요청으로 남는다.
