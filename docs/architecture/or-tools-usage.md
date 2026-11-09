# OR-Tools 활용 — 쓰는 곳과 후보

> 2026-11-09 기록. 라이브러리는 이미 있다(`ortools-java 9.15.6755`, `gradle/libs.versions.toml`) — 아래 기능은 모두 그 안에 들어 있다(CP-SAT `CpSolver`, 선형·정수계획 `MPSolver`, 배정·흐름 `LinearSumAssignment`·`MinCostFlow`, 경로 `RoutingModel`, 적재 `KnapsackSolver` — jar 안 클래스로 확인). **새 의존성 없이** 서비스 안에 계산 어댑터만 추가하면 된다.
> 방침: **ADR-0050**(2026-11-09 승인) — 라이더 배정·배송 경로·피킹 순서는 기존 OR-Tools 로, JGraphT 도입 안 함. 사용 서비스: mfc-capacity·transport-routing·delivery-dispatch·피킹 순서 담당(구현 때 정함).

## 쓰는 곳

| 기능 | 서비스 | 내용 |
|---|---|---|
| CP-SAT | mfc-capacity | 작업 배정 추천(WK-09/10, `CpSatPlanner`) |

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
