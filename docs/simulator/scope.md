# 시뮬레이션 구현 범위 (사용자 2026-11-09)

> 결정이 바뀌면 이 문서에 날짜와 함께 고친다. 연결 결정(SIM-1~)은 `mfc-simulator-integration.md`.

## 정한 것

| # | 내용 |
|---|---|
| S-1 | **MFC 시뮬레이션을 먼저 완성**한다(`pmart-mfc-simulation`). |
| S-2 | 그것을 토대로 **CDC 시뮬레이션도 만든다** — 둘 다 있게 된다. |
| S-3 | CDC 시뮬레이션은 **입고를 다루지 않는다.** 수요예측 등 MFC 와 달리 기능적으로 빠지는 부분이 많다. |
| S-4 | **TMS(CDC → MFC 운송)는 구현한다** — MFC 쪽 수요예측을 바탕으로 보충 물량을 실어 나르는 단계. 배송 단계에서 **OR-Tools 경로(Routing)** 를 쓴다(`tech-stack.md:49` 의 `transport-routing-service` 와 같은 방향). |
| S-5 | **MFC 도면 = 시뮬레이터 평면**(`layout/floorplan.svg`, `buildLayout()` 실행 결과) — **임시로 만든 시험 MFC 한 곳의 도면**이다. **도면은 MFC 마다 다르다**(사용자 2026-11-09) → 도면·칸 좌표는 센터별 데이터로 다루고 특정 배치를 코드에 박지 않는다(ADR-0050). |

## 여기서 생기는 것 (정리, 결정 아님)

- **피킹 동선이 가능해진다**: 지도(S-5)가 생겼으니 칸 사이 걷는 거리를 격자 길찾기로 구해(시뮬레이터 `navgrid.ts` 방식) OR-Tools 경로로 방문 순서를 정할 수 있다. 배정 추천의 `NO_TRAVEL_TIME` 한계도 같은 거리표로 풀 수 있다. → `docs/architecture/or-tools-usage.md`
- **TMS 쪽 서비스가 아직 없다**: `transport-planning`·`transport-routing`·`transport-execution`(service-map §TMS)은 `services/` 에 없다. CDC 쪽도 `cdc-inventory` 만 있다(`cdc-outbound`·`cdc-location` 등은 없음). 도로 거리는 외부 경로 API 로 받는 설계(service-map transport-routing).
- **수요예측 → 보충 → 운송 흐름**: 예측(`demand-planning`, 딥러닝 — `docs/architecture/demand-forecast-deep-learning.md`) → 보충량·이동 승인(`replenishment`) → 운송 계획·경로(TMS) → MFC 수령(mfc-capacity 수령창·mfc-inbound). CDC 입고를 안 하므로 CDC 재고는 시작 재고(시드)로 둔다 — 확인 필요.

## 확인할 것

| # | 질문 | 왜 |
|---|---|---|
| L-1 | 지도가 시뮬레이터 평면(임시 시험 MFC)이면, **백엔드 시험 센터의 위치(칸) 목록도 그 평면대로 다시 등록할지** — 지금 `MFC-SEED-01` 은 `A-S9-01-1-01` 형식 칸이 따로 있고, 시뮬레이터는 `A01-01-101` 형식 칸 6,762개. SIM-6("백엔드 기준, 시뮬레이터가 맞춤")과 함께 다시 정해야 한다 | 거리표(피킹 동선)는 칸마다 좌표가 있어야 만들 수 있다 |
| L-2 | CDC 시뮬레이션의 범위(출고·피킹·상차만인지, 재고 조정·품질은?) | S-3 |
| L-3 | TMS 의 범위(차량·기사 마스터, 배차, 실제 출발·도착 기록까지인지) | S-4 |
