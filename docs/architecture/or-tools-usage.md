# OR-Tools 활용 — 쓰는 곳과 후보

> 2026-11-09 기록. 라이브러리는 이미 있다(`ortools-java 9.15.6755`, `gradle/libs.versions.toml`) — 아래 기능은 모두 그 안에 들어 있다(CP-SAT `CpSolver`, 선형·정수계획 `MPSolver`, 배정·흐름 `LinearSumAssignment`·`MinCostFlow`, 경로 `RoutingModel`, 적재 `KnapsackSolver` — jar 안 클래스로 확인). **새 의존성 없이** 서비스 안에 계산 어댑터만 추가하면 된다.
> 방침: **ADR-0050**(2026-11-09 승인) — 라이더 배정·배송 경로·피킹 순서는 기존 OR-Tools 로, JGraphT 도입 안 함. 사용 서비스: mfc-capacity·transport-routing·delivery-dispatch·피킹 순서 담당(구현 때 정함).

## 쓰는 곳

| 기능 | 서비스 | 내용 |
|---|---|---|
| CP-SAT | mfc-capacity | 작업 배정 추천(WK-09/10, `CpSatPlanner`) |

## 보완 진행 (2026-11-09)

| # | 내용 | 상태 |
|---|---|---|
| A1 | 계산 상태 세분 — UNKNOWN = UNDETERMINED, MODEL_INVALID = INVALID, INFEASIBLE = NO_SOLUTION(원문 WK-09) | 완료 mfc-capacity 0.9.4 `4ef91e9` |
| A2 | 한계 `ZONE_NOT_CHECKED` 제거(MFC 는 온도대 자격 안 씀 — 설계) | 완료 `4ef91e9` |
| A3 | 진행 중 작업은 남은 수량 비율만큼만 담당을 막음(`IN_PROGRESS_ESTIMATED_BY_PROGRESS`) | 완료 `4ef91e9` |
| A5 | 규모 측정(리눅스 컨테이너, LAN 과 같은 이미지): 20~100건 FEASIBLE 이나 늘 5초 다 씀, **150건·120분부터 5초 안에 첫 해 없음(UNKNOWN)** → 규칙 기반 첫 해를 힌트로 주고, 시간 안에 해가 없으면 그 해를 추천(`RULE_BASED_FALLBACK`) — 150·200·300건 모두 배정 | 완료 `e1b5c60` |
| B1 | 칸 사이 걷는 거리표 — mfc-location `POST /internal/location-distances`(0.7.0), 1차 도면 좌표 직각 거리, 벽 미반영(`WALLS_NOT_CONSIDERED`) | 완료 `141caa3`, LAN 확인 |
| A4 | 계산 입력 저장(원문 WK-09 재현) — 계획마다 엔진에 넘긴 문제 그대로 JSON + SHA-256 지문(capacity 0.12.0, V13 `assignment_plan_input`), `GET /assignment-plans/{id}/input`, 결과에 `inputHash`. 사용자 결정 2026-11-09: 다시 계산(replay) API 는 지금 안 만듦, 기간으로 지우지 않음, **총량 상한 2GB**(설정 `plan.input-max-bytes`) — 넘으면 오래된 것부터(확정 안 된 계획 먼저) 지우고 계획 결과는 남김 | 완료 `eeca933`, LAN E2E 66/66 |
| A6 | 이 PC(Windows) 의 OR-Tools 시험 실패 | B3 뒤에 정함 — 그동안 OR-Tools 시험은 Docker(리눅스)로 따로 돌려 확인 |
| B2 | 피킹 방문 순서(Routing) — mfc-capacity `POST /internal/pick-routes`(0.10.0), 냉장 → 냉동 → 상온 C~H, 그룹 안 OR-Tools 열린 경로(그룹당 300 ms, 못 쓰면 가까운 칸부터), 출발 CRT-A-01. fulfillment 0.4.10 이 시작 때 받아 상세 순서·visitSeq | 완료 `64bb895`, LAN E2E 63/63(ROUTING). 리눅스 확인: 30칸 69.6 m vs 가까운 칸부터 75.6 m |
| B3 | 배정 추천 작업 시간 추정 + 작업자 속도 배율(capacity 0.11.0) — 사용자 결정 2026-11-09: PICK·PACK 은 문서 7·4분을 평균으로 두고 주문마다 (걷는 거리 ÷ 1.1 m/s + 줄 9초·개수 3초) 비율(0.5~2배), 이동·보충·복귀는 도면 거리로 직접, 작업 사이 이동은 미포함(피킹 출발 고정이라 작음). 속도 배율 0.5~2.0(센터 관리자) → 작업 시간 ÷ 배율. 처리 값은 시뮬레이터 값(임시) | 완료 `57dfd4e`, LAN E2E 64/64(TRAVEL_ESTIMATED), 리눅스 CP-SAT 확인 |
| 남은 관찰 | 작은 문제도 최적 증명을 못 해 늘 시간 제한(5초)까지 계산 — 상대 차이 1 % 멈춤을 넣었으나 효과 없음 | 응답 시간을 줄이려면 시간 제한 설정을 낮추는 것을 검토 |

## 후보

| 기능 | 자리 | 상태 | 조건 |
|---|---|---|---|
| **경로(Routing)** | TMS 운송 경로(`transport-routing`) | **채택**(ADR-0050, `docs/simulator/scope.md` S-4) | TMS 서비스가 아직 없음. 도로 거리는 외부 경로 API |
| **경로(Routing)** | MFC 피킹 방문 순서 | **채택**(ADR-0050) | 도면은 MFC 마다 다름 — 지금 도면은 임시 시험 MFC 의 것(scope S-5). 칸 좌표(scope L-1)가 정해져야 거리표를 만든다 |
| **배정(Assignment)·최소비용흐름** | 라이더 ↔ 배송 짝짓기(delivery-dispatch DD-1) | **채택**(ADR-0050) | 라이더 선택 규칙이 업무 문서(`Delivery-business-logic.md`)에 있는지 먼저 확인 |
| CP-SAT | 작업자 근무표 | 나중 | 시간대별 필요 인원(수요예측) 필요 |
| 상자 채우기 | 포장 봉투·박스 추천 / CDC→MFC 차량 적재 | 나중 | 상품 크기·무게 기준 정보(MFC 문서 9장 자료 공백), TMS |
| 선형·정수계획 | CDC 재고를 MFC 별로 나누는 양(SCM 보충) | 나중 | replenishment·수요예측 |

## 도입하지 않는 것

- **JGraphT**(그래프 길찾기): 피킹 동선의 "칸 사이 걷는 거리"는 격자 길찾기를 직접 구현하면 된다(시뮬레이터 `navgrid.ts` 0.25 m 격자 A* 와 같은 방식). 방문 순서는 OR-Tools 경로가 한다. 배송은 외부 경로 API 거리. → **도입 안 함**(ADR-0050). 통로 구조가 복잡해져 직접 구현이 버거우면 다시 본다.
- **Timefold Solver**: OR-Tools 와 역할이 겹친다. 네이티브 라이브러리 문제가 운영에서도 막힐 때만 대안으로.

## 알아 둘 것

- OR-Tools 경로는 거리를 스스로 계산하지 않는다 — 지점 사이 거리·시간표를 받아 방문 순서만 정한다.
- 네이티브 라이브러리: LAN(리눅스 컨테이너) 정상. 2026-11-09 개발 PC(Windows)는 "애플리케이션 제어 정책"이 OR-Tools 임시 파일을 막아 capacity 의 CP-SAT 시험 7개가 이 PC 에서 실패. GraalVM 네이티브 이미지 전환 때(ADR-0028) 따로 검증.
