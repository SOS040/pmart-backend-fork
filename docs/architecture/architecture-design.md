# PMart 백엔드 마이크로서비스 아키텍처 설계

문서 버전: 0.1 · 작성일: 2026-10-23 · 상태: **설계안** (CLAUDE.md 목표 서비스 50개 기준, 코드 미작성)

> 근거: CLAUDE.md(2026-10-23 수정본), `docs/business/*-business-logic.md` 8개.
> 이 문서는 **구조**를 정한다. 서비스별 책임은 `service-map.md`, 이벤트는 `events.md`, 사가는 `docs/business/flows/`, 결정 근거는 `docs/adr/`에 둔다.
> 표기: `[CLAUDE.md 확정]` 목표 구성에서 주어진 것 / `[문서 확정]` 비즈니스 문서의 확정 정책 / `[제안]` 이번 설계의 선택 / `[결정 필요]` 팀 승인 대기.

---

## 1. 설계 목표와 제약

| 항목 | 내용 |
|---|---|
| 목표 구성 | 업무 서비스 42개 + 플랫폼·지원 8개 = **50개** `[CLAUDE.md 확정]`. 여기에 BackOffice 문서가 요구하는 원장 3개(`approval-service`, `case-service`, `center-master-service`)를 **추가 제안** (§4.8, ADR-0004). |
| 1차 범위 | 문서가 1차 제외한 영역(쿠폰·프로모션, 공급사·구매발주, CDC 외부 입고/ASN, MFC 간 이관, 예정재고 예약판매)은 서비스 경계만 확보하고 **기능은 비활성** (§8 단계 계획). |
| 기술 스택 | CLAUDE.md "Tech Stack" 절이 비어 있음 `[결정 필요]`. 이 설계는 이전 버전의 Java 21 / Spring Boot 3.x / Gradle Kotlin DSL / Spring Cloud Gateway / OpenFeign / Resilience4j / Kafka / PostgreSQL / Flyway / Micrometer+OTel / Docker Compose를 **가정**하고, Delivery·TMS 문서의 Python 최적화 서비스(OR-Tools, GreedRL/RouteFinder)는 별도 런타임으로 둔다. |
| 통신 규칙 | 동기 호출은 timeout/retry/circuit breaker 필수, 체인 최대 2 hop, 상태 전파는 이벤트, Outbox, consumer 멱등 `[CLAUDE.md 확정]`. |
| 트랜잭션 | 2PC 금지. Saga + 보상 `[CLAUDE.md 확정]`. 주문 사가는 orchestration으로 한다 (ADR-0003). |
| 데이터 | 서비스별 독립 DB. 다른 서비스 DB 직접 접근 금지 `[CLAUDE.md 확정]`. 재고 수량을 바꾸는 서비스는 센터 유형별 **하나** (`cdc-inventory`, `mfc-inventory`). |

---

## 2. 전체 구조 (컨텍스트 맵)

```
                 ┌────────────── 고객앱 / 라이더앱 / 점원앱(PDA) / 간선기사앱 / 관리자 웹 ──────────────┐
                 │                                                                                │
                 ▼                                                                                ▼
          ┌─────────────┐                                                              ┌─────────────────┐
          │ api-gateway │  인증 토큰 검증(auth) · 라우팅 · rate limit                     │ backoffice-bff   │
          └──────┬──────┘                                                              └────────┬────────┘
                 │                                                                                │
 ┌───────────────┴──────────────┐  ┌────────────────────────┐  ┌────────────────────────┐  ┌──────┴───────────────────────────┐
 │ 1. Commerce (9)              │  │ 2. Order & Payment (5) │  │ 7. Last-mile Delivery  │  │ 8. Platform & Support (8 + 3)    │
 │ product · product-content    │◄─┤ order · orchestration  ├─►│ serviceability·capacity│  │ auth · config · discovery        │
 │ customer · cart · review     │  │ fulfillment-routing    │  │ rider · dispatch       │  │ notification · audit · ai-assist │
 │ search · storefront          │  │ claim · payment ──► PG │  │ execution · tracking   │  │ [+] approval · case · center-mst │
 │ pricing · promotion(1차 X)   │  └──────────┬─────────────┘  └───────────┬────────────┘  └──────────────────────────────────┘
 └──────────────────────────────┘             │ 재고예약·작업요청·중지          │ ShipmentReady / QR 인계 / 회수
                                              ▼                             ▼
                                  ┌──────────────────────────────────────────────────────┐
                                  │ 5. MFC (6)                                            │
                                  │ mfc-inventory(단일 쓰기) · mfc-fulfillment · mfc-capacity │
                                  │ mfc-inbound · mfc-location · mfc-quality               │
                                  └───────────────▲──────────────────────────▲────────────┘
                                 TransferDeparted │  TIN → MfcReceiptPosted   │ 도착·인계 증빙
                                                  │                           │
 ┌──────────────────────┐  ApprovedTransfer  ┌────┴─────────────────────┐  ┌──┴───────────────────────────┐
 │ 3. SCM (4)           │──────────────────► │ 4. CDC WMS (5)           │  │ 6. Middle-mile TMS (5)        │
 │ replenishment        │◄───────────────── │ cdc-inventory(단일 쓰기)  │◄─┤ transport-planning · routing  │
 │ demand-planning      │  AllocationResult  │ cdc-outbound · cdc-inbound│─►│ fleet · transport-execution   │
 │ supplier·procurement │                    │ cdc-location · cdc-quality│  │ cold-chain                    │
 │ (1차 X)              │                    └──────────────────────────┘  └───────────────────────────────┘
 └──────────────────────┘
```

**읽는 법**
- 화살표는 **업무 의존 방향**이다. 동기/비동기는 §5 표로 정한다.
- MFC는 판매 흐름(OMS→MFC→Delivery)과 공급 흐름(SCM→CDC→TMS→MFC)이 만나는 지점이다. 두 흐름은 `mfc-inventory-service`의 H/E/R/A에서만 결합한다.
- BackOffice는 어떤 거래 정본도 소유하지 않는다. 승인·권한·사건 원장만 소유하고 도메인에 **명령을 요청**한다.

---

## 3. 핵심 설계 원칙 (문서에서 도출)

| # | 원칙 | 근거 | 설계 반영 |
|---|---|---|---|
| P1 | **센터 유형별 단일 재고 쓰기 주체** | WMS L774, MFC IF-06 L1816, CLAUDE.md L68·L83 | `cdc-inventory`, `mfc-inventory`만 수량 변경. inbound/outbound/quality/fulfillment는 "요청 → commit_id 수신". Transit은 `cdc-inventory` 소유. |
| P2 | **요청·수락·수행·원장반영·통지의 분리** | OMS 1.4, MFC IF-06, TMS 0.2-5 | 모든 명령 응답은 `ACCEPTED / APPLIED / REJECTED / UNKNOWN_RESULT`를 구분. ACK≠업무 성공. 통지 실패는 통지만 재시도. |
| P3 | **결과 불명은 실패가 아니다** | OMS A.4, SCM 20, TMS 18, Commerce 16.3 | 모든 상태 머신에 `UNKNOWN_RESULT`/`확인 중` 상태. 자동 복구는 원요청 조회 재시도(1분×5회→상신→15분)만. 시간 경과로 실패·재판매 확정 금지. |
| P4 | **멱등 2계층** | OMS A.1.1, SCM 20-1, TMS 18-1, MFC 1530 | 요청 멱등 `(sourceSystem, requestId)` + 업무 유일성(원주문·원TRF·departureId·handoverId). 새 키로 와도 같은 실물 사건은 1회 효과. |
| P5 | **미설정 ≠ 0/무제한** | 전 문서 공통 | 정책값 조회는 `VALUE / NOT_SET / UNAVAILABLE` 3값. NOT_SET이면 해당 신규 실행만 `CONFIG_REQUIRED`로 보류. |
| P6 | **보상은 이번 시도 성공분만** | OMS 5.4, TMS 8.2, SCM 20-4 | 사가 보상 로그에 "이번 시도에서 새로 확보한 자원 ID"만 기록해 해제. 기존 유효 점유·실출발·실인계는 되돌리지 않는다. |
| P7 | **스냅샷 불변** | OMS A.1.2, Commerce F.2 | 주문 당시 가격·주소·정책 버전을 주문행에 복사. 마스터 변경이 과거 주문·과거 운행 조건을 덮지 않는다. |
| P8 | **시간: 저장 UTC, 기한 판정 Asia/Seoul** | WMS L211·L692, OMS 11.1 | DB는 `Instant`. 소비기한·반품 7일/3개월·영업일 판정은 `Asia/Seoul` LocalDate 기준. 이벤트는 `occurredAt`(UTC)+`timezone` 필드. |
| P9 | **지정 MFC 단일 이행** | OMS 6 `[확정]` | 1차 `fulfillment-routing-service`는 "배송지→담당 MFC 1곳 조회+적격 검증"만. 타 MFC 재배정·합산 없음. |
| P10 | **BackOffice는 복제하지 않는다** | BackOffice 1.1~1.2 | 승인함은 원승인 ID 참조, 조회는 원천별 `asOf`/신선도 표시, 금지 기능(강제 성공·임의 ADJ·주문 상태 직접 변경) 없음. |

---

## 4. 서비스 그룹별 구조

각 서비스의 상세(소유·비소유·API·이벤트)는 `service-map.md`. 여기서는 **그룹 내부의 결합 구조**와 결정 사항만 적는다.

### 4.1 Commerce (9)

```
product-service ──(ProductVersionPublished)──► product-content · search · storefront · pricing · cdc-inventory · mfc-inventory · order
customer-service ──(탈퇴 가능 여부 조회)──► order-service
cart-service ──(가용 조회)──► mfc-inventory   ──(구매상한 검증)──► product-service
pricing-service ◄──(견적 조회)── order-service
review-service ◄──(DeliveryCompleted)── delivery-execution   ──(평점 집계)──► product-content
```

- **상품 정본 = `product-service`** (ADR-0001). SKU 발급(E-/B- 8자리), 기초상품, 구성 낱개 수, 카테고리(상위 30/하위 141), 바코드, 물류 규격(BOX/PLT 환산·Ti/Hi)을 소유. 승인 워크플로는 `approval-service`가 돌리고, 발급·버전 확정은 `product-service`가 한다. 센터별 취급 ON/OFF는 각 inventory 서비스.
- **`pricing-service`는 "가격 버전 정본 + 견적 계산"**, 최종 청구액 확정은 `order-service` 초안에서 한다 (Commerce 8.1 L1405). 배달팁·봉투값·최소주문·첫 구매 무료 규칙은 **`order-service`에 둔다** `[제안]` — 문서가 OMS 직접 구현으로 확정했고 1차에 쿠폰이 없어 `promotion-service`는 비활성.
- `customer-service`는 계정·동의·주소록·탈퇴 상태. 소셜 OAuth 검증·세션·토큰·`AUTH_RESTRICTED`(15분 5회)는 `auth-service`.
- `cart-service`는 재고를 예약하지 않는다. 담기 시 `mfc-inventory`의 A(가용) 조회와 `product-service` 구매상한 검증만. `OrderConfirmed` 구독으로 확정 포함분만 1회 정리.
- 고객센터·상담·첨부(Commerce 14장)는 9개에 없음 → 1차는 `case-service`(BackOffice) CS 접수 + `backoffice-bff` 화면으로 흡수 `[제안]`. 재입고 알림 구독은 `[결정 필요]`(notification 또는 cart).

### 4.2 Order & Payment (5)

```
Commerce ──draft/pay/cancel/claim──► order-service ◄──► order-orchestration-service ──► payment-service ──► 토스페이먼츠
                                          │                        │ 예약/점유/작업요청/중지
                                          │                        ▼
                                          │            mfc-inventory · mfc-capacity · delivery-capacity · mfc-fulfillment · delivery-dispatch
                                          ▼
                                      claim-service (취소·반품·교환 심사, 환불 의도)
```

- **`order-service`** = OMS A.1.1~A.1.5, A.1.8 (멱등 접수, 원장·스냅샷, 검증·금액 확정, 상태·생명주기, 이행 계획·10kg 분할). 주문 상태의 정본.
- **`order-orchestration-service`** = OMS A.1.4, A.1.6(협업 부분), A.1.7, A.1.9~A.1.11 조정, A.1.15 (사가 단계·타임아웃·재시도·보상·대사). **업무 진행 상태만 소유**하고 주문·결제·재고 성공을 대신 확정하지 않는다 `[CLAUDE.md 확정]`. 사가 4종: 결제 진입(자원 확보→PG→확정), 이행(작업 요청→결품→부분취소), 취소(중지↔인계 경합), 예약 시간 변경.
- **`payment-service`** = OMS A.1.6 + A.2.2. 토스페이먼츠 어댑터 + 결제 시도/PG 거래/취소·환불 거래 원장. "추가 환불 가능 한도 = 승인액 − 성공 누적 − 불명 점유"를 계산·강제. OMS 문서는 Payment를 외부 참여자로 보지만 CLAUDE.md가 내부 서비스로 확정했으므로 **거래 사실의 정본은 PG, 거래 원장·대사의 정본은 payment-service** (ADR-0005).
- **`claim-service`** = OMS A.1.11(취소 접수)·A.1.12·A.1.13 + BackOffice BO-12~14 심사 원장. 취소·반품·교환 자격(7일/24h/3개월·30일), 신청 가능량(전달량 − 진행/완료 점유), 환불 의도·금액 산정(원안분). PG 실행은 `payment-service`, 수량·재고는 MFC가 확정.
- **`fulfillment-routing-service`**: 1차는 `delivery-serviceability`(배송지→MFC)와 `mfc-*` 제한 조회를 묶어 "이행 가능 MFC 1곳 + 적격 사유"를 돌려주는 얇은 서비스. 다중 MFC 평가·재배정 계획은 2차 `[CLAUDE.md 목표]`.

### 4.3 SCM (4)

```
mfc-inventory · cdc-inventory ──InventorySnapshot──► replenishment-service ◄──CustomerCommitment── order-service
mfc-inbound ──SupplyRequest / ReceivingCapability──►         │           ◄──TransportFeasibility── transport-planning
demand-planning-service ──ForecastVersion──►                 │
                                            ApprovedTransfer ▼
                                                    cdc-outbound · transport-planning · mfc-inbound
```

- **`replenishment-service`** = SCM 3~18장 전체 (planRun 스냅샷, H/C/I/P/F/SS 수량 사전, 보호기간, 배수·상한, 배분, **사업승인 TRF 정본**, 미출발 잔량 종료, 추가 공급). SCM-D01~D12 처리기가 모듈.
- **`demand-planning-service`**: SCM 5장 예측 후보(이동평균 등)와 예측 버전·신뢰도. 1차는 승인 수동 수요 입력 + 단순 이동평균 `[문서: 예측 방법 확인 필요]`.
- **`supplier-service`, `procurement-service`**: 문서 1차 제외 `[문서 확정]`. CLAUDE.md 목표 구성이므로 경계·계약 이름만 예약하고 **2차 이후 활성** (§8).
- 사업승인 워크플로(승인자·대행·50% 추가승인)는 `approval-service`가 수행하되, **승인 결과의 정본은 `replenishment-service`** (BackOffice 1.1 L83 "승인 정본을 양쪽에서 별도 생성하지 않음").

### 4.4 CDC WMS (5)

```
replenishment ──ApprovedTransfer──► cdc-outbound ──Allocate/Release──► cdc-inventory ──AllocationResult──► replenishment
transport-execution ──TruckActuallyDeparted──► cdc-outbound(검증) ──PostDeparture──► cdc-inventory ──TOUT: TransferDeparted──► mfc-inbound · replenishment · transport
mfc-inventory ──MfcReceiptPosted(TIN)──► cdc-inventory ──TransitReconciled──► mfc · replenishment · 관제
cdc-inbound(적치/MOV/보충) ──CapacityReserve──► cdc-location ; ──MoveApply──► cdc-inventory
cdc-quality ──Hold/Release/Dispose──► cdc-inventory ; ◄──TemperatureExcursion── cold-chain
```

- **`cdc-inventory-service`**: 위치·LOT·품질상태별 On-hand, 이관 예약(R), 안전량/가용 `max(0,E−R−S)`, FEFO 후보, **TOUT 1회 전기**(현물−q·예약−q·Transit+q 한 거래), Transit 대사, 손실/폐기/ADJ 집행, 초기 보유(INITIAL_BALANCE), 일일 수불 대사. CLAUDE.md L62의 "LOT별 잔량"은 **"위치·LOT·품질상태별"로 확장** `[제안]`.
- **`cdc-inbound-service`**: 1차에 외부 입고·ASN이 없으므로 **"적치·이동·선반보충 작업 서비스"(CDC-F-010~013)로 범위 재정의** `[제안]` (WMS 1.1 L135). 2차에 ASN/실수령/검수를 추가. 적치 완료의 3자 원자성(수량+공간+작업 상태)은 "location 공간 선점 → inventory 수량 반영 → inbound 작업 완료" 순 사가로 처리하고 실패 시 이번 선점분만 해제.
- **`cdc-location-service`**: 7구간 LOC 마스터·상태·시설 적격·적치 후보·공간 용량 예약.
- **`cdc-outbound-service`**: TRF 인수(1요청1이관1출발), Wave/Task, 실피킹·부족, 분류/검품, 출발 준비 적격(TMS/MFC 조건 소비), 상차(LOADED≠출발), 출발 사실 검증, 미출발 취소·정리, 출발 매니페스트 발행. **LPN(BOX/PLT) 구성 정본은 `cdc-outbound`** `[제안·결정 필요]` — 피킹→봉인 시점에 생성되고 위치 상속은 location, 수량 귀속은 inventory가 참조.
- **`cdc-quality-service`**: 보류/격리/해제/리콜/폐기 **판단과 승인 기록**. 실제 상태 전환·수량 차감은 `cdc-inventory`에 요청. FEFO 예외 이중 승인. 운영 제한(CDC-F-031) 정본도 여기에 둔다 `[제안]`.

### 4.5 MFC (6)

```
order-orchestration ──ReserveInventory──► mfc-inventory ──InventoryHeld──► orchestration
order-orchestration ──HoldCapacity──► mfc-capacity
order-orchestration ──ConfirmOrder(작업 요청)──► mfc-fulfillment ──Task──► mfc-capacity(배정)
mfc-fulfillment ──ShipmentReady──► delivery-dispatch · order
rider QR ──► mfc-fulfillment: AuthorizePackageHandover(fence) ──► CommitPackageHandover ──► mfc-inventory(H−q, R−q) ──PackageHandedOver──► order · delivery-execution
cdc-inventory ──TransferDeparted──► mfc-inbound(InboundPromise) ──ConfirmReceipt──► mfc-inventory(TIN) ──MfcReceiptPosted──► cdc-inventory · replenishment
mfc-quality ──Quarantine/Release/Dispose──► mfc-inventory ──ReservationBlocked──► order-orchestration
mfc-inbound ──SupplyRequest / ReceivingCapability──► replenishment
```

- **`mfc-inventory-service`**: Balance(센터·위치·SKU·LOT·품질상태·업무상태·수량·버전), Ledger, ReservationPart(HELD/CONFIRMED/BLOCKED/CONSUMED/RELEASED/EXPIRED), MoveAllocation(물량 M + 공간 V — **공간 잔여량도 함께 소유** `[제안]`, IV-03/04 원자성 때문), TIN 전기, 인계 전기, 실사/ADJ. 모든 변경은 `commit_id` 반환.
- **`mfc-fulfillment-service`**: PICK/PACK/DISPATCH Task, Shipment(SHP)/Package(PKG)/HandoverFact(HOV), **인계 fence**(`AuthorizePackageHandover` → `HANDOVER_IN_PROGRESS`, `CommitPackageHandover`), 작업 중지 응답(취소 판단은 OMS). PDA→fulfillment→inventory = 2 hop 유지.
- **`mfc-inbound-service`**: RECEIVE/INSPECT/PUTAWAY Task, InboundPromise(P/T 투영), 도착·인계 증빙 연결, `ConfirmReceipt`(정상/격리/미판정 수량), 회수품 실물 반입, **로컬 공급 요청 WK-14**(DRAFT→SUBMITTED→ACKNOWLEDGED) `[제안]`.
- **`mfc-capacity-service`**: 작업자·장비·근무·자격, CapacityHold/Snapshot(HELD→CONFIRMED/RELEASED/EXPIRED), **작업 배정·계획 발행(WK-02/09/10, OR-Tools 호출)** `[제안]` — CLAUDE.md 설명에 없으나 가장 가까운 서비스. 수령창·수용량(ReceivingCapability)도 여기서 제공.
- **`mfc-location-service`**: Center/Area/Location, 온도구역, 격리·출하대기 구역. 공간 잔여량 계산 입력(치수·슬롯 수)을 제공하되 **예약 상태는 inventory**가 들고 있다.
- **`mfc-quality-service`**: 품질 판정·격리/해제 승인·회수품 검수(재판매/폐기)·폐기 승인. 수량 반영은 inventory 요청.
- 공통 Task 상태 엔진(WAITING→READY→ASSIGNED→IN_PROGRESS→COMPLETED/BLOCKED/FAILED/CANCELLED/CLOSED_PARTIAL)은 fulfillment·inbound가 **각자 구현**한다. libs에 비즈니스 로직을 두지 않기 위해 상태표만 `contracts/`에 공유 `[제안]`.
- 문제함/이관(WK-12), 관제(WK-11)는 `case-service` + `backoffice-bff`로.

### 4.6 Middle-mile TMS (5)

```
replenishment ──ApprovedTransfer──► transport-planning ──TransportFeasibility──► replenishment
transport-planning ──후보 계산──► transport-routing(도로행렬·OR-Tools) ; ──자원 확보──► fleet(차량·기사 점유) ; ──도크/하차면──► cdc-location · mfc-capacity
transport-planning ──TripAssigned──► cdc-outbound · mfc-inbound · transport-execution(지시서)
transport-execution ──TruckActuallyDeparted──► cdc-outbound ; ──Arrival/HandoverEvidence──► mfc-inbound ; ──TransportIncident/ETAUpdated──► replenishment · mfc · 관제
cold-chain ──TemperatureExcursion──► cdc-quality · mfc-quality · transport-execution
```

- **`transport-planning-service`**: 운송 요청(TRF당 1건), 직송/순회/회차 후보, 적재 계획(구획·중량·부피·하차 접근), 정차 시간표 계산식(§10.1), 배정 버전·지시서, 출발 전 변경(TMS-D06).
- **`transport-routing-service`**: 시간대·차종별 도로시간 행렬(외부 경로 API 어댑터), OR-Tools VRPTW 후보 계산 (Python 런타임 `[결정 필요]`). 해를 못 찾은 것과 불가 확정을 구분.
- **`fleet-service`**: 실차·간선기사 마스터, 적격, 가용시간, **점유 예약**(`[시작,종료)` 겹침 검사). 라이더와 별개 ID.
- **`transport-execution-service`**: 운행 실행(DRAFT→…→IN_TRANSIT→PHYSICAL_ENDED→CLOSED), 정차(PLANNED→ARRIVED→…→DEPARTED), `departureId`·이관별 출발 구성, 인계 증빙, 사건, 차량/기사 재가용 계산, 비용 근거(요율 미등록=산정 불가).
- **`cold-chain-service`**: 온도 데이터 수집·허용 범위·이탈 사건. 판정은 품질 서비스.

### 4.7 Last-mile Delivery (6)

```
customer/cart/order ──주소→MFC·배송 가능?──► delivery-serviceability
order-orchestration ──HoldSlot──► delivery-capacity
mfc-fulfillment ──ShipmentReady──► delivery-dispatch ──후보 탐색(1km→2km→3km, 1분 응답)──► rider-service(적격·상태) ──제안/수락──► 라이더앱
delivery-dispatch ──DeliveryAssigned──► delivery-execution ──가게도착→QR(mfc-fulfillment 인계)→픽업→고객도착→전달(사진)──► order · review · notification
delivery-execution ◄──위치 수신── delivery-tracking ──ETA──► order(고객 조회) · mfc-fulfillment(점원 화면)
claim ──ReturnPickupRequested──► delivery-dispatch → delivery-execution(회수) ──ReturnDelivered──► mfc-inbound
```

- **`delivery-serviceability-service`**: 권역 폴리곤·담당 MFC 매핑 **버전**(본사 BO-07 승인 → 여기서 발효), 주소 정규화·판정(담당 MFC/서비스 밖/중첩/불명), 배송 가능 조건(방식·수단·중량·보냉).
- **`delivery-capacity-service`**: 시간대 슬롯 정의와 정원, 슬롯 점유(주문당 1건, 분할배송도 1건). 정원은 `mfc-capacity` 작업 여력과 라이더 수급을 입력으로 한 승인값 `[결정 필요: 08/09시 개시·정원]` (ADR-0008).
- **`rider-service`**: 라이더 프로필·수단·면허 고유성·계좌·운행 상태(종료/운행중·신규배차 허용/중지)·계정 제한(정상/신규배차 제한/일시 중지/계약 종료)·배차 모드.
- **`delivery-dispatch-service`**: 배송(delivery) 생성(주문의 이행 계획을 받아 배송 단위 생성), 배차 시도/제안/응답 이력, 반경 단계 탐색, 동시 수락 1명 확정, 재시도(최대 2회·10분), 추가·분할 배차, 배차 취소(픽업 전/후), 회수·재배송 배차. AI 추천 배차는 `delivery-tracking` 거리행렬 + 외부 Python 모델 어댑터 `[2차]`.
- **`delivery-execution-service`**: 배송 상태(가게 도착→픽업 완료→고객 도착→전달 완료, 실패/중단/회수), 전달 사진·비대면 증빙, 도움요청 사건 연계, 수행료 근거(요율표 버전·보상·공제), 정산 상태(예상/확정 대기/지급 확정/…). 지급 실행은 외부 회계.
- **`delivery-tracking-service`**: 위치 수집(좌표·시각·정확도), 이동 이력, 고객용/점원용 ETA, WebSocket/SSE 푸시, 위치 단절 표시.
- QR 인계: 라이더앱 스캔 → `api-gateway` → `mfc-fulfillment`(fence·commit) → `mfc-inventory`. `delivery-execution`은 `PackageHandedOver` 이벤트로 픽업 완료를 반영한다 (동기 체인 2 hop 유지).

### 4.8 Platform & Support (8 + 추가 3)

| 서비스 | 역할 | 비고 |
|---|---|---|
| `api-gateway` | 라우팅, 토큰 검증(auth 공개키), rate limit, traceId 발급 | 비즈니스 로직 없음 |
| `auth-service` | 고객/라이더/운영자/서비스 신원, 토큰, **유효 RoleGrant·Delegation·긴급권한 정본**, 행위 시점 검증 API | BackOffice 3장. 권한 **부여 승인 흐름**은 approval-service |
| `config-service` | 환경별 애플리케이션 설정 | 업무 정책값 아님 |
| `discovery-service` | 서비스 등록·조회 | |
| `backoffice-bff` | 관리자 화면별 조회 조합(원천별 `asOf`·신선도 표시), 명령 전달(멱등키 부착) | **상태 없음** `[CLAUDE.md 확정]` |
| `notification-service` | 고객·라이더·운영 알림, 채널, 발송 이력·재시도, 읽음 | 알림 실패는 통지만 복구 |
| `audit-service` | 감사 사건 수집·조회·보존(추가만) | |
| `ai-assistant-service` | RAG·정책 설명·권한 있는 도메인 API 호출 | 비즈니스 문서에 요구 없음, 3차 |
| **`approval-service`** `[추가 제안]` | ChangeRequest·ApprovalDossier, 5축 상태(요청/심사/실행/전달/사건), 부분 집행 불변식, 통합 승인함(BO-10), 대량 변경(BO-26), 정책 참조 레지스트리(BO-09 PolicyReference) | 승인 **정본은 도메인**(replenishment, claim, quality…). 여기는 워크플로 원장 (ADR-0004) |
| **`case-service`** `[추가 제안]` | Case/SubCase/Obligation/ActionRequest/ShiftHandover/CloseSnapshot, CS 접수, 문제함(MFC WK-12), 도움요청(Delivery 9장) | 도메인 사고·클레임은 원시스템 유지 |
| **`center-master-service`** `[추가 제안]` | 센터 코드·유형·주소·좌표·영업 캘린더·취급 온도대·입고창 (BO-06, TMS 2.3~3.1) | 소비자: order, replenishment, transport, delivery, mfc, cdc. 현장 용량·LOC는 각 센터 서비스 |

---

## 5. 통신 패턴

### 5.1 동기 vs 비동기 결정 기준

| 사용 | 방식 | 요구 |
|---|---|---|
| 사가 단계 명령(예약, 점유, 결제, 작업 요청, 중지, 인계 승인) | **동기 REST(Feign)** | timeout·retry(멱등 보장 시만)·circuit breaker 필수. 응답에 `ACCEPTED/APPLIED/REJECTED/UNKNOWN_RESULT`. 불명 시 조회 API로 복구 |
| 원결과 조회(원요청·원거래·원예약·원수령) | 동기 REST | 모든 명령 API는 짝이 되는 `GET …/{requestId}` 조회를 가진다 (P3) |
| 마스터·참조 조회(상품, 가격, 권역, 센터, 권한) | 동기 REST + 로컬 캐시(버전 포함) | 캐시로 성공을 확정하지 않음. 조회 실패≠0 |
| 상태 전파·사실 통지(확정, 출발, 수령, 인계, 전달, 품질 차단) | **Kafka 이벤트 + Outbox** | consumer inbox 멱등, 순서는 aggregate 버전으로 판단 |
| 외부 연동(PG, 경로 API, 소셜 OAuth, 알림 채널) | 어댑터 서비스 내부 | 비밀·원문 로그 금지 |
| 실시간 푸시(라이더 위치, ETA) | WebSocket/SSE (`delivery-tracking`) | 서버 확정 상태만 표시 |

**2 hop 규칙 검증**: 고위험 체인을 미리 확인한다.
- 고객앱 → gateway → `order-service` → `pricing-service` (견적) ✔ 2 hop
- orchestration → `mfc-inventory` ✔ 1 hop (orchestration이 호출 주체)
- PDA → gateway → `mfc-fulfillment` → `mfc-inventory` ✔ 2 hop
- orchestration → `payment-service` → 토스페이먼츠 ✔ (외부 포함 2)
- `backoffice-bff` → `approval-service` → `replenishment-service` ✔ 2 hop. approval이 다시 WMS를 부르면 3 hop → **`ApprovedTransfer` 이벤트로 전환** (그래서 승인 발효는 이벤트)

### 5.2 이벤트 봉투 (공통)

```json
{
  "eventId": "uuid",
  "eventType": "OrderConfirmed",
  "schemaVersion": 1,
  "sourceSystem": "order-service",
  "aggregateType": "Order", "aggregateId": "…", "aggregateVersion": 7,
  "occurredAt": "2026-10-23T03:00:00Z", "timezone": "Asia/Seoul",
  "traceId": "…", "correlationId": "…", "causationId": "…",
  "actor": { "type": "SYSTEM|OPERATOR|CUSTOMER|RIDER", "id": "…" },
  "payload": { }
}
```

- `(sourceSystem, eventId)`가 멱등 키. 하위 호환은 필드 추가만, 삭제/rename은 `V2`.
- 토픽: `{domain}.{aggregate}.v{n}` (예: `order.order.v1`, `mfc.inventory.v1`). 파티션 키 = aggregateId.
- Outbox 테이블은 서비스마다 동일 스키마(`libs/common-outbox` — 기술 코드만). 릴레이는 polling publisher(1차) → Debezium(2차) `[결정 필요]`.
- Inbox 테이블 `(sourceSystem, eventId)` unique로 중복 차단. 역순 수신은 `aggregateVersion` 비교로 폐기하되 **실물 사실(출발·인계·수령)은 폐기하지 않고 정정 경로로 수용**.

### 5.3 명령 API 규약

- 경로 `/api/v1/{resources}` 복수형 kebab-case. 명령은 `POST /api/v1/orders/{orderId}/cancellations` 처럼 하위 리소스로.
- 헤더 `Idempotency-Key`(=requestId), `X-Source-System`, `X-Expected-Version`(낙관적 잠금), `traceparent`.
- 응답 본문 공통: `{ "status": "ACCEPTED|APPLIED|REJECTED|UNKNOWN_RESULT", "requestId", "resultId", "appliedQty"?, "version", "retryable", "nextAction"? }`. `resultId`는 commitId/reservationId/holdId 등 소유 서비스가 발급한 결과 식별자.
- 오류: `{ "code": "ORDER_NOT_FOUND", "message", "traceId", "requestId", "retryable" }`. 공통 코드: `VALIDATION_FAILED, FORBIDDEN, INVALID_STATE, VERSION_CONFLICT, IDEMPOTENCY_CONFLICT, CONFIG_REQUIRED, INSUFFICIENT_STOCK, CAPACITY_EXCEEDED, DEPENDENCY_UNAVAILABLE, UNKNOWN_RESULT, UNSUPPORTED_ACTION`.

---

## 6. 사가 목록

| 사가 | 조정자 | 참여자 | 보상 | 문서 |
|---|---|---|---|---|
| S1 결제 진입·주문 확정 | `order-orchestration` | mfc-inventory(예약), mfc-capacity(여력), delivery-capacity(슬롯), [promotion 3차], payment, order | 이번 시도 신규 점유만 해제. PG 승인 후 확정 실패 → 승인취소 보상 | `flows/order-checkout-saga.md` |
| S2 이행·결품·부분 취소 | `order-orchestration` | mfc-fulfillment, mfc-inventory, claim, payment | 사전 동의 범위 부분취소, 동의 없으면 보류·CS 이관 | `flows/order-checkout-saga.md` §후반 |
| S3 취소 vs 인계 경합 | `order-orchestration` (claim 접수) | mfc-fulfillment(중지/fence), mfc-inventory(해제), delivery-dispatch(배차 취소), payment(취소) | 인계 확정분은 취소 불가 → 회수 사가로 전환 | `flows/cancel-vs-handover.md` |
| S4 예약 시간 변경 | `order-orchestration` | delivery-capacity(새 슬롯 임시), mfc-capacity, mfc-inventory(LOT 기한 재검), mfc-fulfillment(준비 시작 경합) | 실패 시 새 슬롯만 해제, 기존 유지 | OMS 4, A.2.5 |
| S5 보충 TRF 실행 | `replenishment` (사업 결정) + 각 정본 | approval, cdc-inventory(확보), cdc-outbound, transport-planning, fleet, transport-execution, mfc-inbound, mfc-inventory | 미출발 잔량 종료는 수동 승인. 출발 후 취소 없음 | `flows/replenishment-transfer.md` |
| S6 반품·교환·회수·환불 | `claim-service` | delivery-dispatch/execution(회수), mfc-inbound/quality/inventory(검수·재입고), payment(부분취소), order(교환 재배송) | 회수 차이·검수 불합격은 환불 보류 | `flows/return-exchange-refund.md` |
| S7 적치/보충 MOV (센터 내부) | `cdc-inbound` / `mfc-inventory` | location(공간), inventory(수량) | 공간 선점만 해제 | WMS CDC-F-010~013, MFC IV-03/04 |
| S8 간선 자원 배정 | `transport-planning` | fleet(차량·기사), cdc-location(도크), mfc-capacity(하차면) | 이번 시도 신규 예약만 해제, `RESERVED/PENDING` 유지 | TMS-D03 |

Choreography로 처리하는 전파(사가 아님): `OrderConfirmed`→cart 정리·SCM 수요·알림, `DeliveryCompleted`→review 자격·알림·order 완료 집계, `ProductVersionPublished`→카탈로그·검색·센터 취급, `QualityBlocked`→order 영향 조사·replenishment 재계산.

---

## 7. 데이터 소유권 요약

| 데이터 | 정본 서비스 | 복제(읽기 투영) 허용 서비스 |
|---|---|---|
| 상품·SKU·카테고리·규격·바코드 | product-service | 전 서비스(버전 포함 캐시) |
| 가격 버전 | pricing-service | order(스냅샷), cart/storefront(표시) |
| 고객 계정·주소록·동의 | customer-service | order(주문 시 스냅샷) |
| 장바구니 | cart-service | — |
| 주문·주문행·금액 스냅샷·이행 계획 | order-service | commerce(조회), delivery-dispatch(배송 단위), replenishment(수요 집계) |
| 사가 진행·보상 로그 | order-orchestration | backoffice-bff(조회) |
| 결제 시도·PG 거래·환불 거래 | payment-service | order/claim(참조 ID) |
| 클레임·환불 의도 | claim-service | order, commerce |
| 보충 계획·TRF 사업승인·잔량 | replenishment-service | cdc-outbound, transport-planning, mfc-inbound, approval(참조) |
| CDC 재고·예약·Transit·LOT 잔량 | cdc-inventory-service | replenishment(스냅샷), outbound/inbound/quality(참조) |
| CDC LOC·공간 | cdc-location-service | inbound, outbound |
| Wave/Task/LPN/매니페스트 | cdc-outbound-service | transport, mfc-inbound |
| MFC 재고·예약·공간 잔여 | mfc-inventory-service | order-orchestration(예약 ID), cart(가용 조회), replenishment(스냅샷), commerce(표시) |
| MFC 작업·출하·포장·인계 | mfc-fulfillment-service | order, delivery |
| 작업 여력·CapacityHold | mfc-capacity-service | order-orchestration, delivery-capacity |
| 운송 요청·운행 계획·배정 | transport-planning-service | cdc-outbound, mfc-inbound, replenishment |
| 차량·기사·점유 | fleet-service | transport-planning |
| 실제 출발·인계 증빙·운행 실행 | transport-execution-service | cdc-inventory(TOUT 입력), mfc-inbound |
| 권역·MFC 매핑 버전 | delivery-serviceability-service | customer, order, cart |
| 배송 슬롯·정원·점유 | delivery-capacity-service | order |
| 라이더 | rider-service | dispatch, execution |
| 배송·배차 제안·배정 | delivery-dispatch-service | execution, order, mfc-fulfillment |
| 배송 수행·증빙·수행료 근거 | delivery-execution-service | order, claim, review |
| 위치·ETA | delivery-tracking-service | order(조회), mfc-fulfillment(점원) |
| 권한 grant·위임 | auth-service | 전 서비스(토큰 claim) |
| 승인 워크플로·정책 참조 | approval-service | backoffice-bff |
| 사건·의무 | case-service | backoffice-bff |
| 센터 기준정보·캘린더 | center-master-service | 전 물류·주문 서비스 |

---

## 8. 단계별 도입 계획 `[제안]`

50개를 한 번에 세우지 않는다. **경계는 처음부터 50개로 설계**(Gradle 모듈·DB 스키마·contracts 분리)하되, 배포·활성은 세 단계로 나눈다.

| 단계 | 목표 | 활성 서비스 | 비활성(경계만 예약) |
|---|---|---|---|
| **1단계 (핵심 판매 루프)** | 지정 MFC 재고로 주문→결제→피킹→QR 인계→전달 완료, 전체 취소, 부분 품절 PG 부분취소 | product, product-content, customer, cart, search(기본), storefront(기본), pricing / order, order-orchestration, fulfillment-routing(얇게), claim(취소·반품 접수), payment / mfc-inventory, mfc-fulfillment, mfc-capacity, mfc-location, mfc-quality(최소), mfc-inbound(초기 보유·회수 반입) / delivery-serviceability, delivery-capacity, rider, delivery-dispatch, delivery-execution, delivery-tracking / api-gateway, auth, config, discovery, notification, audit, backoffice-bff, approval(상품·가격·권역·권한), case(CS 접수), center-master | promotion, review, supplier, procurement, demand-planning(수동 입력만), cdc-*, transport-*, fleet, cold-chain, ai-assistant |
| **2단계 (공급 루프)** | CDC 초기 보유→SCM 보충 계산·사업승인→CDC 확보·피킹·상차→TMS 운행·실제 출발(TOUT)→MFC TIN→Transit 대사 | replenishment, demand-planning / cdc-inventory, cdc-location, cdc-outbound, cdc-inbound(적치·MOV), cdc-quality / transport-planning, transport-routing, fleet, transport-execution, cold-chain / mfc-inbound 완성 / review | supplier, procurement, ai-assistant |
| **3단계 (확장)** | 쿠폰·프로모션, 공급사·구매발주·ASN, 다중 MFC 라우팅, AI 배차, AI 어시스턴트, 26개 MFC | promotion, supplier, procurement, cdc-inbound(ASN), fulfillment-routing 완성, ai-assistant | — |

**초기 배포 단위에 대한 권고**: 1단계에서도 서비스 수가 30개를 넘는다. 팀 규모가 작다면 같은 그룹 내 서비스를 **하나의 배포 단위(모듈러 모놀리스)** 로 묶되, 모듈 간 호출은 반드시 공개 API/이벤트 인터페이스만 사용해 나중에 분리 가능하게 한다. 묶어도 되는 쌍(같은 트랜잭션 경계·같은 변경 주기): `mfc-fulfillment + mfc-capacity`, `delivery-dispatch + delivery-execution`, `product + product-content`, `cdc-outbound + cdc-inbound`. 묶으면 안 되는 쌍: `*-inventory`와 그 밖의 모든 것(단일 쓰기 경계 보호), `order`와 `order-orchestration`(상태 정본 vs 진행 상태), `payment`와 나머지(PG 비밀·감사 경계).

---

## 9. 저장소·빌드 구조 `[제안]`

```
/
├── services/
│   ├── commerce/   product-service, product-content-service, customer-service, cart-service, review-service, search-service, storefront-service, pricing-service, promotion-service
│   ├── order/      order-service, order-orchestration-service, fulfillment-routing-service, claim-service, payment-service
│   ├── scm/        supplier-service, procurement-service, demand-planning-service, replenishment-service
│   ├── cdc/        cdc-inventory-service, cdc-inbound-service, cdc-location-service, cdc-outbound-service, cdc-quality-service
│   ├── mfc/        mfc-inventory-service, mfc-inbound-service, mfc-location-service, mfc-fulfillment-service, mfc-capacity-service, mfc-quality-service
│   ├── tms/        transport-planning-service, transport-routing-service, fleet-service, transport-execution-service, cold-chain-service
│   ├── delivery/   delivery-serviceability-service, delivery-capacity-service, rider-service, delivery-dispatch-service, delivery-execution-service, delivery-tracking-service
│   └── platform/   api-gateway, auth-service, config-service, discovery-service, backoffice-bff, notification-service, audit-service, ai-assistant-service, approval-service, case-service, center-master-service
├── libs/           common-error, common-event(봉투·직렬화), common-outbox, common-inbox, common-idempotency, common-web(헤더·traceId), common-test   <- 기술 코드만
├── contracts/
│   ├── openapi/{service}.yaml
│   ├── events/{EventType}.v{n}.json  (JSON Schema)
│   └── states/{aggregate}-states.md  (상태표 공유, 코드 아님)
├── infra/          docker-compose(Kafka, PostgreSQL x N, Redis, OTel collector), k8s/
├── optimizers/     python: routing-solver(OR-Tools), dispatch-model(GreedRL/RouteFinder 어댑터)  <- 별도 런타임 [결정 필요]
└── docs/
```

각 서비스는 `services/{group}/{name}/CLAUDE.md`에 서비스 내부 규칙을 둔다(루트 CLAUDE.md 주석). Gradle 경로는 `:services:order:order-service` 형태 `[결정 필요: CLAUDE.md Commands 절 표기와 맞출 것]`.

---

## 10. 횡단 관심사

| 관심사 | 설계 |
|---|---|
| 인증·인가 | gateway가 JWT 검증, 서비스는 `auth-service`의 행위 시점 검증 API(역할·센터 범위·대행 기간·대상 소유권)를 **변경 명령마다** 호출(BackOffice 3.2). 서비스 간 호출은 서비스 신원 토큰. |
| 추적 | `traceId`는 gateway 발급, 모든 동기 헤더·이벤트 봉투·로그에 전파. `correlationId`=사가/사건 ID. |
| 로그 | 구조화 JSON. PG 비밀·주소 원문·출입 비밀번호·신분증 금지. |
| 개인정보 | 최소 노출(담당 주문 범위), 다운로드 시 현권한 재검사, 보존기간 `[결정 필요]`. |
| 정책값 | 각 도메인 서비스가 자기 정책 테이블(버전·효력일) 소유. `approval-service`는 참조만. 조회 결과 3값(VALUE/NOT_SET/UNAVAILABLE). |
| 관측 | 미편성·미확보·불명·장기 미수령·대사 불일치 등 "열린 의무" 지표를 각 서비스가 노출, `backoffice-bff`가 조합. |
| 시간 | P8. 이벤트·API 모두 ISO-8601 UTC + timezone 필드. 영업일·기한 계산은 Asia/Seoul. |
| 시뮬레이션 | 1차에 실물 센서·차량·설비가 없으므로 실제 출발·상차·온도 입력에 `source=SIMULATED` 표시를 의무화(TMS 12.1, WMS CWC-18). |

---

## 11. CLAUDE.md와 문서 사이에서 이 설계가 내린 결정

| # | 쟁점 | 결정 | ADR |
|---|---|---|---|
| 1 | 상품 정본(WMS vs product-service) | `product-service` | 0001 |
| 2 | 재고 원장 2개(cdc/mfc)와 Transit | 센터 유형별 단일 쓰기, Transit은 cdc | 0002 |
| 3 | Saga 방식(choreography 기본 vs orchestration 서비스) | 주문·취소·시간변경은 orchestration, 전파는 choreography | 0003 |
| 4 | BackOffice 원장(bff만으로 불가) | approval/case/center-master 추가 | 0004 |
| 5 | Payment 위치 | 내부 payment-service, PG가 거래 사실 정본 | 0005 |
| 6 | 취소/인계 공통 판정 주체(MFC Q-01) | fence=mfc-fulfillment, 취소 판정=order-orchestration | 0006 |
| 7 | TMS/Delivery 분리, 권역 정본 | Delivery, serviceability | 0007 |
| 8 | 슬롯 정원 정본 | delivery-capacity(고객 슬롯) + mfc-capacity(작업 여력) 이중 확보 | 0008 |
| 9 | Outbox·이벤트 봉투·멱등 | §5.2 | 0009 |
| 10 | 시간 저장·판정 | UTC 저장, Asia/Seoul 판정 | 0010 |

---

## 12. 팀 결정 대기 목록 (설계가 확정하지 않은 것)

| 항목 | 영향 | 출처 |
|---|---|---|
| Tech Stack 확정(CLAUDE.md 공란), Python 런타임 허용 여부 | 전체 | CLAUDE.md, Delivery 2.1, TMS 24 |
| 정식 서비스명(PMart/바로픽) | 로그·이벤트 sourceSystem | Commerce, BackOffice |
| 초기 배포 단위(50개 개별 vs 모듈러 묶음) | 인프라·팀 | §8 |
| IAM 실제 배치(auth-service 범위, 센터×업무×행위 교집합 모델) | auth, approval | BackOffice BO-Q01/02 |
| LPN(BOX/PLT) 정본 서비스 | cdc-outbound vs cdc-inventory | WMS F-007, SEP |
| cdc-inbound 1차 범위 재정의(적치·MOV) 수용 여부 | cdc | WMS 1.1 |
| mfc-capacity에 작업 배정·OR-Tools 포함 여부 | mfc | MFC WK-02/09/10 |
| 고객 슬롯 정원 산정식(08/09시 개시, 시간대별 정원) | delivery-capacity | OMS 6, MFC Q-18 |
| 첫 구매 자격 점유 위치(order vs promotion) | order | Commerce 8.8 |
| Outbox 릴레이 방식(polling vs CDC) | libs | §5.2 |
| 고객센터·재입고 알림·BI 원천 사건의 서비스 배치 | case/notification | Commerce 14, 6.13, 1.2 |
| 회계·지급 연계(라이더 수행료, 운송비) | 외부 어댑터 | Delivery 7.2, TMS 20, BO-21 |
| 문서 내 깨진 링크(`시스템 책임 경계.md`, `MFC-business-logic/02_*.md`, `문서_책임정리_20261020/` 등 10건)와 Delivery 이미지 | docs | grep 결과 |
