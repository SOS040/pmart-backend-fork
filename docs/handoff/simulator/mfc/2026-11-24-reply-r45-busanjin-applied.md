# 회신 — 1호점 부산진구 MFC-SEED-01 다시 설계 반영 (2026-11-24, R-45)

보낸 영역: platform (PC 1 — PC 3 가 꺼져 있어 사용자 "너가 맡아"). 요청: `2026-11-24-request-busanjin-redesign.md`, 시뮬레이터 R-45 f2d145c(사용자 "확정").
상태: **LAN 반영 끝·시뮬레이터 대조 끝(어긋남 0)** — 2026-11-24 mfc 시뮬4: 새 파일 9,185칸 빠짐 0·xM·zM 차이 0, 파일 밖 CLOSED 아닌 칸은 RTN-A-01 하나, 재고 1,334줄 9,671개·CLOSED 칸 재고 0.

## 1. 사본·도구
- `docs/simulator/mfc/layout-busanjin-r45/`: `location-positions.json` 9,185(SHELF 8,696·RESERVE_RACK 420·SUPPORT 59·BULK_PALLET 10), `reserve-rack-cells.json` 420, `bulk-pallet-cells.json` 10.
  - 요청서 표(9,191)와 다른 점은 사용자 지시 둘: 상온 D 14 → 13랙(D03-02 뺌), 상온 보충재고 15 → 14랙.
- `deploy/lan/seed/mfc_r45.py`: `plan`(기본, 쓰기 없음)·`move`·`close`·`positions`, `--apply` 로만 쓴다.
- 옛 1호점 위치 파일 `docs/simulator/mfc/layout/location-positions.json`(R-38, 15,528)은 그대로 둔다. 옛 − 새 = 없어지는 칸.

## 2. 계획(사용자 "응" 앞에 보인 숫자)
- 새 파일 칸은 모두 서버에 있음(새 코드 0). 없어질 칸 6,343(F-A 1,230·C-B 1,715·A-C 540·A-D 660·A-E 432·A-F 600·A-G 486·A-H 200·A-R 330·C-R 75·F-R 75) — 모두 ACTIVE 였음.
- 없어질 칸 재고: 171칸·446줄·3,060개·171 SKU(칸마다 SKU 하나), 모두 STORED·3단. 예약·배정·보류 0, 보충 기준 0, 넓은 칸 영향 0.
- 받을 칸: 같은 온도대·구역 글자의 빈 ACTIVE 칸(보충재고 칸 아님) — 같은 고회전 여부 → 같은 단 → 폭이 원래 이상인 가장 좁은 칸. 171쌍 모두 같은 단·같은 고회전.

## 3. 실행(2026-11-24, 시뮬레이터 개발 서버를 끈 뒤 시작)
| 단계 | 결과 |
|---|---|
| move(위치 정정 `stock-corrections`, approvalRef `layout-r45`) | 171/171칸, 실패 0 |
| close(`state-changes` CLOSED, 지우지 않음) | 6,343, 실패 0 |
| positions(남는 칸 xM·zM — mfc_layout r34 와 같은 일) | 9,185 바꿈, 실패 0 |

- 끝난 뒤 다시 읽음: ACTIVE 9,086(새 파일 9,085 + RTN-A-01)·CLOSED 7,767, 재고 1,334줄 9,671개·503 SKU(전과 같음), 없어진 칸 재고 0, 위치 차이 0/9,185.
- 시뮬레이터는 닫기 도중 사용자가 다시 켰다. 끝난 뒤 읽었을 때 없어진 칸에 재고는 없었다.

## 4. 남은 것
- (끝) 시뮬레이터 대조 — 어긋남 0.
- 2~5호점은 이미 만든 센터라 바뀌지 않는다. 1호점 칸을 본뜨는 스크립트(`mfc_clone_center.py` 등)는 이제 새 1호점을 읽는다 — 원본 고정본은 `deploy/lan/seed/seed-template-2026-11-24.json`(#410, 다시 설계 전).
