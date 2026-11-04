# ADR-0003 주문 계열 사가는 orchestration, 사실 전파는 choreography

- 상태: 승인 (2026-11-04, 사용자 "유지" 결정 — 핵심 주문 사가는 오케스트레이션, 사가 밖 후속 연동은 코레오그래피. 패턴별 책임은 `docs/architecture/payment-saga-patterns.md`) · 제안 2026-10-23
- 관련: CLAUDE.md "분산 트랜잭션: Saga(choreography 기본)" 및 `order-orchestration-service`, OMS 3·5.4·7·8·A.1.4·A.1.15·A.4

## 배경
CLAUDE.md는 choreography를 기본으로 하면서 동시에 `order-orchestration-service`("주문 Saga·단계별 실행·시간 초과·보상 진행")를 목표 구성에 두었다. OMS 문서는 결제 진입 전 전품목 재고 예약 + MFC 여력 + 슬롯(+쿠폰·행사)을 **모두** 확보한 뒤에만 PG에 진입하고, 일부 성공 시 성공분만 보상하며, 결과 불명은 1분×5회 조회 후 상신하는 절차를 요구한다. 이런 다중 자원·타임아웃·보상·수동 개입이 있는 흐름을 choreography로 만들면 진행 상태가 어디에도 없어 "결과 불명" 복구가 불가능하다.

## 결정
- **Orchestration**: S1 결제 진입·확정, S2 이행·결품·부분취소, S3 취소 vs 인계, S4 예약 시간 변경 → `order-orchestration-service`가 조정. 사가 인스턴스·단계·타임아웃·보상 로그·불명 추적을 소유. 각 참여자의 성공은 참여자 정본이 확정하고 orchestration은 "진행 상태"만 가진다.
- **Orchestration(도메인 내)**: S5 보충 TRF는 `replenishment-service`가 사업 결정 조정자, S6 반품은 `claim-service`, S7 적치는 `cdc-inbound`/`mfc-inventory`, S8 간선 자원은 `transport-planning`. 별도 오케스트레이터 서비스를 늘리지 않는다.
- **Choreography**: 확정된 사실의 전파(`OrderConfirmed`→cart/알림/SCM, `DeliveryCompleted`→review/알림, `ProductVersionPublished`→카탈로그/센터, `QualityBlocked`→영향 조사 등).
- CLAUDE.md의 "choreography 기본" 문구는 "**사실 전파는 choreography, 다중 자원 확보·보상이 필요한 흐름은 orchestration**"으로 해석한다. CLAUDE.md 갱신 제안.

## 대안
- 전부 choreography — 결과 불명·부분 성공 복구와 운영자 수동 개입 지점이 없어 OMS A.1.15 요구를 충족하지 못함.
- 전부 orchestration — 단순 전파까지 조정자에 몰려 결합도 증가.

## 결과
- 조정자 명령은 동기(Spring RestClient + Resilience4j, 멱등키, 2 hop)이고 결과 수신은 동기 응답 + 이벤트 둘 다 허용(응답 유실 시 이벤트/조회로 복구).
- 사가 상태 테이블과 보상 로그는 `order-orchestration` DB. `backoffice-bff`는 조회만.
