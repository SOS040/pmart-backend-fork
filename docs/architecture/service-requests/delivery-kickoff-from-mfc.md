# 배송 백엔드 시작 안내 — MFC 쪽에서 전달 (2026-11-10)

배송 서비스(delivery-dispatch·delivery-execution·rider·delivery-tracking·delivery-capacity·delivery-serviceability)를 만들기 시작할 때 **먼저 읽는 한 장**. MFC 쪽은 이미 만들어 LAN 에서 돌고 있고, 배송 쪽이 없어서 멈춰 있는 지점과 맞춰야 할 약속을 모았다. 항목별 상세는 아래 링크(받는 서비스 파일)가 정본이다.

## 1. 지금 상태
- 배송 6개 서비스: 스캐폴딩(2026-10-23) — 모든 엔드포인트 501. LAN 은 **PC 5**(192.168.0.101, 2026-11-10 PC 3 에서 옮김, DB 새로 — `docs/worklog/2026-11-10.md`). Outbox 커넥터 6개는 PC 5 DB 를 보고 RUNNING(발행만 하면 Kafka 로 나간다).
- MFC 쪽: 피킹 → 포장 → **배차 요청** → 출하 대기(READY) → **라이더 QR 인계** 까지 구현·LAN 배포. 실제 고객 주문 1건(ORD-0e67de38-7acf-3e71-8a28-7aeadd986bb0, 2026-11-10)이 **출고 READY 에서 배차를 기다리는 중** — 배차가 생기면 처음 이어서 시험할 대상(단, §6 시작 위치 참고).
- 인계 관련 사용자 결정 D-1~D-9 는 모두 결정됨(2026-11-08, `docs/business/flows/package-handover-decisions.md`).

## 2. 받는 서비스별 할 일 (정본 링크)
| 번호 | 서비스 | 할 일 | 순서 |
|---|---|---|---|
| [DD-1](delivery-dispatch-service.md) | delivery-dispatch | `DispatchRequested` 구독 → 배송 생성 → 라이더 배정 → 배정 사건 발행, 취소·조정 | **1순위** — 이게 없어 주문이 READY 에서 멈춤 |
| [DE-1](delivery-execution-service.md) | delivery-execution | 픽업 완료 = 포장별 MFC 인계 확정 호출, `PackageHandedOver` 가 모두 오면 PICKED_UP | 2 |
| [RS-1](rider-service.md) | rider | 라이더 단계 사건(신규배차 제한·일시 중지·계약 종료) → dispatch 가 배정 풀기 | 2 |
| [DD-2](delivery-dispatch-service.md) | delivery-dispatch | 확인된 오배송 회수 라이더 자동 배차(ADR-0052) | DD-1 뒤 |
| [CP-1](delivery-capacity-service.md) | delivery-capacity | `CapacityReduced` 구독 → 영향 주문 처리 | **사용자 결정 필요**(#15) |
| [TR-1](delivery-tracking-service.md) | delivery-tracking | `DeliveryEtaUpdated` | 사용자: 필요 없음(선택) |
| [O-1](order-service.md) | order(배송 아님) | `PackageHandedOver` → 인계 수량·주문 상태 | 인계 시험 전에 같이 |

## 3. MFC 가 이미 주는 것
### 사건 — 토픽 `mfc.fulfillment.v1` (스키마 `contracts/events/<이름>.v1.json`, 목록 `docs/architecture/events.md`)
| 사건 | 언제 | 배송이 쓰는 항목 |
|---|---|---|
| `DispatchRequested` | 점원이 포장 중 예상 시간과 함께 요청, 없으면 READY 때 자동. **출고당 한 번** | orderId·shipmentId·mfcId·trigger(MANUAL/AUTO_ON_READY)·estimatedPackMinutes(MANUAL)·estimatedReadyAt·requestedAt |
| `PackingFinished` | 포장 완료(인계 아님) | shipmentId·packages[{packageId, bagCount, weight, volume, alcohol}] — 결품으로 줄어든 내용도 여기 |
| `ShipmentReady` | 출하 대기 | orderId·shipmentId·mfcId·readyAt·packages[]·pickupLocation — `DispatchRequested` 보다 먼저 와도 같은 출고 하나로 |
| `PackageHandedOver` | 라이더 인계 확정 + 재고 차감 뒤 | orderId·shipmentId·deliveryId·packageId·riderId·handoverId·inventoryCommitId·lines[]·handedAt |
| `WorkStopResult` | 주문 중지 판정 | perPackage[{packageId, STOPPED/HANDOVER_IN_PROGRESS/HANDED_OVER}] — 출고 취소면 배차 취소 |

### API — `contracts/openapi/mfc-fulfillment-service.yaml`
- `POST /packages/{packageId}/handover-authorizations` — 라이더 QR 스캔(토큰 **RIDER**, 주체 = riderId). 포장 SEALED·출고 READY·배정 라이더여야 함. 실패 코드: `DELIVERY_MISMATCH`·`NOT_ASSIGNED`·`RIDER_MISMATCH`(403)·`FENCE_ACTIVE`·`PACKAGE_INVALIDATED`·`HANDOVER_RESULT_PENDING`.
- `POST /packages/{packageId}/handover-commits` — 인계 확정(같은 라이더). 결과 모름이면 MFC 가 같은 인계 ID 로 다시 보내고 결과는 `PackageHandedOver` 로 온다. `FENCE_EXPIRED` 면 다시 찍기(같은 라이더는 확인 없이 — D-1 안 C).
- 허가 유효시간: 센터 설정 `handover-fence-ttl`, 기본 3분(D-2).

## 4. MFC 가 배송에게서 받으려는 것 — 토픽 `delivery.delivery.v1` (이미 구독 중)
mfc-fulfillment 는 아래 4개로 "이 출고를 누가 가져갈 수 있나"(배정 투영)를 만들고 인계 허가에 쓴다(`HandoverService.onDeliveryEvent`). **이 항목 이름 그대로** 발행해야 한다(스키마 `contracts/events/Delivery*.v1.json`).

| 사건 | MFC 가 읽는 항목 | MFC 동작 |
|---|---|---|
| `DeliveryCreated` | deliveryId, **shipmentId**, orderId | 배송 ↔ 출고 연결. shipmentId 가 없으면 인계 허가가 `DELIVERY_MISMATCH` |
| `DeliveryAssigned` | deliveryId, orderId, riderId, assignmentVersion | 배정. 다른 라이더의 진행 중 허가는 취소 |
| `DeliveryAssignmentChanged` | deliveryId, **newRiderId**(null = 배정 해제), assignmentVersion | 재배정·해제 |
| `DeliveryCancelledBeforePickup` | deliveryId, orderId | 배정 취소 |

- 발행은 Outbox(`EventPublisher`) → Debezium. 같은 aggregate 의 낮은 version 이 늦게 오면 상태 전이는 버린다(`events.md` 순서 규칙).

## 5. 결정된 규칙(배송 쪽에 영향 있는 것)
- D-3: MFC 는 라이더 **가게 도착을 보지 않는다**. "도착 → QR → 픽업 완료" 순서는 라이더 앱·delivery-execution 몫.
- D-4: MFC 는 라이더 단계를 직접 보지 않는다 — dispatch 가 일시 중지·계약 종료 라이더의 배정을 풀면(`DeliveryAssignmentChanged`) 인계가 막힌다. 신규배차 제한 라이더는 이미 배정된 것 그대로.
- D-5: 배차 요청 시점 = 점원 요청(포장 중) + 포장 끝까지 없으면 READY 때 자동.
- D-6: PICKED_UP 은 그 배송의 **모든 포장** `PackageHandedOver` 뒤. 버튼만으로 바꾸지 않는다.
- D-7: 인계 뒤 취소 회수비 — 고객 부담 9,000원·고객센터 경유·회사 잘못 면제, 환불은 검수 뒤(claim 쪽).
- 취소: 배차 취소는 그 주문의 **모든 포장이 STOPPED** 일 때만(S3 취소 사가, `cancel-vs-handover.md`). 하나라도 인계됐으면 회수(미구현).
- 주문당 출고 1개(D6-2, 10kg 분할 없음) — 배송 1 ↔ 출고 1 로 시작해도 된다.

## 6. 시작할 때 정해야 할 것 / 주의
- **구독 시작 위치**: `mfc.fulfillment.v1` 에는 지난 E2E 시험 출고(`E2E-…`, 대부분 취소됨)의 `DispatchRequested` 가 남아 있다. 새 소비 그룹을 처음부터 읽으면 지난 시험 출고에 배송을 만든다. 처음부터 읽을지·지금부터 읽을지(그러면 READY 로 기다리는 실제 주문은 배차 요청을 다시 받아야 함 — MFC 에 재요청 API 는 없다)는 **구현 전에 사용자 결정**.
- **문서끼리 다른 값**(구현 전에 하나로): DeliveryType(사건 NORMAL/SPLIT… vs API FORWARD…), `DispatchFailed` 사유 enum(D-9), 취소 포장 상태 이름(VOIDED vs ADR-0046 INVALIDATED).
- 오배송 확인 사건을 누가 기록하는지(DD-2, ADR-0052 남은 것).
- 라이더 토큰(RIDER 역할)·라이더 앱은 아직 없다 — 인계 API 시험은 시험용 라이더 토큰이 필요(만드는 방식은 사용자 확인).

## 7. 시험
- MFC 쪽 끝까지: `deploy/lan/checks/mfc_e2e.py`(배차 요청·READY·인계 판정까지, 라이더 인계 없음).
- 실제 주문 진행 기록: `docs/worklog/2026-11-10.md`, 추적은 Zipkin `http://192.168.0.40:9411`(MFC → 배송 사건도 traceparent 로 이어진다).
- 근거 문서: `docs/business/flows/package-handover-contract.md`(§5 서비스별 할 일, §7 열린 질문), `package-handover-decisions.md`, ADR-0006·0047·0052, `docs/business/Delivery-business-logic.md`(업무 원문).
