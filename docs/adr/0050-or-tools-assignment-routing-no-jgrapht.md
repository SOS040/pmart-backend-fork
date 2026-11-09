# ADR-0050 라이더 배정·배송 경로·피킹 순서는 기존 OR-Tools 로, JGraphT 는 도입하지 않는다

- 상태: 승인 (2026-11-09, 사용자 "Assignment(라이더 배정)와 Routing(배송 경로·피킹 순서)은 지금 있는 OR-Tools로 하고, JGraphT는 도입하지 않습니다 — 채택")
- 대체: `docs/architecture/tech-stack.md:49` 의 OR-Tools 사용 서비스 범위("`transport-routing-service`, `mfc-capacity-service`에만")를 넓힌다. 그 밖(Java 바인딩만, 별도 Python 런타임 없음)은 그대로.
- 관련: `docs/architecture/or-tools-usage.md`(후보 표), `docs/simulator/scope.md`(S-4 TMS, S-5 도면), baro-pick-docs 충돌 ③(피킹 동선 JGraphT — 보류였던 것)

## 결정
1. **배정(Assignment·최소비용흐름)** — 라이더 ↔ 배송 짝짓기(delivery-dispatch DD-1 의 라이더 후보 탐색·제안)에 OR-Tools 를 쓸 수 있다. 사용 서비스에 `delivery-dispatch-service` 를 더한다. 라이더 선택 규칙(무엇을 최소화하나)은 업무 문서(`Delivery-business-logic.md`)를 따르고, 없는 규칙은 구현 때 질문한다.
2. **경로(Routing)** — (가) TMS 운송 경로(`transport-routing-service`, 기존 범위), (나) MFC 피킹 방문 순서. (나)를 맡을 서비스는 구현 때 정한다(작업 상세의 칸 순서를 내는 쪽 — mfc-fulfillment 또는 배정 추천과 묶어 mfc-capacity). 정해지면 이 ADR 의 범위 표에 적는다.
3. **새 의존성 없음** — 지금 `ortools-java 9.15.6755` 에 배정(`LinearSumAssignment`·`MinCostFlow`)·경로(`RoutingModel`)가 다 들어 있다.
4. **JGraphT 는 도입하지 않는다.** OR-Tools 경로는 지점 사이 거리표를 받아 순서만 정하므로, MFC 안 "칸 사이 걷는 거리"는 도면 격자 위 길찾기를 직접 구현한다(시뮬레이터 `navgrid.ts` 0.25 m 격자 A* 와 같은 방식). 배송 거리는 외부 경로 API(service-map transport-routing). 통로 구조가 복잡해져 직접 구현이 버거우면 다시 본다.
5. **도면은 MFC 마다 다르다**(사용자 2026-11-09). 지금 도면(`docs/simulator/layout/`)은 임시로 만든 시험 MFC 한 곳의 것이다. 피킹 거리표는 센터별 도면·칸 좌표 데이터에서 만들고, 특정 센터 배치를 코드에 박지 않는다. 도면·좌표를 어느 서비스가 갖는지(mfc-location 이 위치의 정본)는 피킹 순서 구현 때 정한다.

## OR-Tools 사용 범위 (이 ADR 기준)
| 서비스 | 기능 | 상태 |
|---|---|---|
| mfc-capacity | CP-SAT 작업 배정 추천 | 구현 |
| transport-routing | 경로 — TMS 운송 | 서비스 미생성 |
| delivery-dispatch | 배정 — 라이더 ↔ 배송 | 서비스 뼈대(501) |
| (미정 — mfc-fulfillment 또는 mfc-capacity) | 경로 — 피킹 방문 순서 | 도면 좌표 필요 |

## 결과
- 실행 이미지·네이티브 라이브러리 주의는 mfc-capacity 와 같다(LAN 리눅스 정상, Windows 개발 PC 는 2026-11-09 애플리케이션 제어 정책이 막음, GraalVM 전환 때 검증 — ADR-0028).
- 계산은 서비스 안 `infrastructure` 어댑터로 두고(시간 제한·동시 실행 수 제한), 결과는 추천·후보로만 쓰고 확정은 각 서비스의 업무 규칙을 거친다(배정 추천과 같은 원칙).
