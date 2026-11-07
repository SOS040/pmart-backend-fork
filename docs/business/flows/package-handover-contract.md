# 포장 인계 계약 (MFC ↔ 배송·주문·클레임) — 슬라이스 6c

버전 1.0 · 2026-11-08 · ADR-0047. **MFC 쪽은 구현·LAN 배포 완료**. 배차·라이더·배송 실행·주문 쪽은 이 문서를 보고 맞춘다.
근거: MFC WK-08·IV-08·IF 계약(1891~1902행)·공통 판정 절차(1985~1994행), Delivery §3.1·3.4·5.1·9.4, OMS 9·12, flows `order-checkout-saga.md` §3, `cancel-vs-handover.md`, ADR-0002·0006·0046.

## 0. 한 줄 요약

배차가 배송을 만들고 라이더를 배정하면(사건) → MFC 가 배정을 투영 → 라이더가 **포장마다** QR 을 찍어 **인계 허가(fence)** → 라이더가 **인계 확정** → MFC 가 재고에서 그 포장을 빼고(H−q·E−q·R−q) `PackageHandedOver` 를 낸다. 주문·배송 실행·클레임은 그 사건으로 움직인다.

```
delivery-dispatch ──DeliveryCreated / DeliveryAssigned / DeliveryAssignmentChanged / DeliveryCancelledBeforePickup──► mfc-fulfillment(배정 투영)
rider-app ──POST /packages/{id}/handover-authorizations──► mfc-fulfillment(fence)            [동기, 라이더 토큰]
rider-app ──POST /packages/{id}/handover-commits─────────► mfc-fulfillment ──► mfc-inventory POST /internal/package-handovers (2 hop)
mfc-fulfillment ──PackageHandedOver(mfc.fulfillment.v1)──► order · delivery-execution · claim · (dispatch)
mfc-inventory  ──PackageHandedOverPosted(mfc.inventory.v1)
```

## 1. MFC 가 받는 것 — 배송 배정 사건 (토픽 `delivery.delivery.v1`, 생산 delivery-dispatch)

MFC 바인딩 `deliveryEvents-in-0`. 배송 ID 한 행으로 투영하므로 사건 순서가 섞여도 된다(배정이 생성보다 먼저 와도 됨). **배정 버전은 더 큰 것만 반영**.

| 사건 | MFC 가 쓰는 필드 | 효과 |
|---|---|---|
| `DeliveryCreated` | `deliveryId`, **`shipmentId`**(필수 — 배송 ↔ MFC 출고 연결), `orderId` | 배송 행 생성(CREATED) |
| `DeliveryAssigned` | `deliveryId`, `riderId`, `assignmentVersion` | ASSIGNED, 라이더 지정. 다른 라이더의 확정 요청 전 fence 는 끝냄 |
| `DeliveryAssignmentChanged` | `deliveryId`, `newRiderId`(null = 배정 해제), `assignmentVersion` | 새 라이더만 인계 가능. 이전 라이더의 확정 요청 전 fence 는 끝냄(Delivery 9.4 "이전 기사 권한 해제") |
| `DeliveryCancelledBeforePickup` | `deliveryId` | CANCELLED — 그 배송으로는 인계 안 됨, 확정 요청 전 fence 끝냄. 재배차는 **새 배송 ID**로 `DeliveryCreated` 부터 |

배차 쪽이 지킬 것:
- `DeliveryCreated.shipmentId` 는 MFC `ShipmentReady.shipmentId` 그대로(지금은 주문당 출고 1개 `<orderId>-S1`, D6-2).
- `assignmentVersion` 은 배송마다 1부터 단조 증가. 재배정·해제마다 +1.
- 집계 키(파티션 키)는 `deliveryId`.
- 확정 요청이 이미 들어간 포장(결과 대기)은 배정을 바꿔도 MFC 가 fence 를 풀지 않는다 — 실물이 이미 넘어갔을 수 있다(대사 대상).

## 2. 라이더 앱이 부르는 것 — 인계 허가·확정 (mfc-fulfillment, 게이트웨이 경유)

토큰은 **RIDER** 이고 `sub` = `riderId` 여야 한다(아니면 403). 변경 요청은 `Idempotency-Key`·`X-Source-System` 필수.

### 2.1 인계 허가 `POST /api/v1/packages/{packageId}/handover-authorizations`

본문 `{riderId, deliveryId, scannedCode, scannedAt?}` — `scannedCode` 는 포장 라벨 QR 원문 `PKG:<packageId>:<packageVersion>`.

검사 순서와 거절 코드:

| 순서 | 조건 | 결과 |
|---|---|---|
| 0 | 센터 fence 유효시간 설정 없음 | 422 `CONFIG_REQUIRED` |
| 1 | 포장 이미 인계 | 409 `ALREADY_HANDED_OVER` |
| 2 | QR 이 지금 라벨과 다름·포장 무효/취소(INVALIDATED·VOIDED) | 409 `PACKAGE_INVALIDATED` |
| 3 | 포장 SEALED 아님·출고 READY/PARTIALLY_HANDED_OVER 아님·**주문 취소(중지) 진행·확정** | 409 `INVALID_STATE` |
| 4 | `deliveryId` 가 이 포장 출고의 배송이 아님(배정 사건 없음 포함) | 409 `DELIVERY_MISMATCH` |
| 5 | 배송이 ASSIGNED 아님(해제·취소) | 409 `NOT_ASSIGNED` |
| 6 | 배정 라이더 ≠ `riderId` | 403 `RIDER_MISMATCH` |
| 7 | 이 포장의 확정 요청 결과 대기 | 409 `HANDOVER_RESULT_PENDING` |
| 8 | 다른 라이더·배송의 살아 있는 fence | 409 `FENCE_ACTIVE` |
| — | 같은 라이더·배송이 살아 있는 fence 를 다시 찍음 | 201 **같은 허가**(새로 만들지 않음) |
| — | 통과 | 201 `{handoverAuthorizationId, fenceVersion(+1), fenceExpiresAt, …}` |

- fence 유효시간: 센터 설정 `pmart.fulfillment.handover-fence-ttl."[센터]"`. **기본값 없음**(사용자 결정 2026-11-08). LAN `MFC-SEED-01` 은 시험값 PT2M.
- 허가는 재고 수량을 바꾸지 않는다(MFC IF `AuthorizePackageHandover` "수량 변화 0").
- 지금 검사하지 않는 것(생산자가 없어서): 제한 라이더(`RiderRestricted` 투영), 가게 도착(`RiderArrivedAtStore`)을 QR 전제로 둘지 — §7.

### 2.2 인계 확정 `POST /api/v1/packages/{packageId}/handover-commits`

본문 `{handoverAuthorizationId, fenceVersion, riderId, handedOverAt?}`.

| 상황 | 응답 |
|---|---|
| fence 없음 | 409 `INVALID_STATE` |
| 허가 ID·fence 버전이 지금 것과 다름(재배정으로 끝남 등) | 409 `FENCE_VERSION_MISMATCH` |
| fence 라이더 ≠ `riderId` | 403 `RIDER_MISMATCH` |
| 확정 요청 전에 fence 만료 | 409 `FENCE_EXPIRED` — 다시 찍기 |
| 재고 반영 | **201** `status APPLIED`, `handoverId`, `inventoryCommitId`, `packageState HANDED_OVER`, `shipmentState` |
| 재고 결과 모름·미전달 | **202** `status UNKNOWN_RESULT`, `nextAction WAIT_RESULT` — MFC 가 같은 `handoverId` 로 다시 보낸다(15초 sweep). 같은 허가로 다시 불러도 된다 |
| 재고 거절 | **202** `status REJECTED`, `nextAction RECONCILE` — fence 유지, 재인계·취소 모두 막음(운영자 대사) |
| 같은 허가로 다시 | 같은 `handoverId`(재고 차감 1회) |

- **인계 사실(handoverId)은 포장당 하나.** 확정 요청이 들어간 순간부터 fence 는 만료 시각과 상관없이 살아 있다 — "만료 ≠ 취소 성공"(ADR-0006, MFC 1553·1992행).
- 픽업 완료 판단에 쓸 것: 포장마다 201 의 `handoverId`.

### 2.3 인계 판정 조회 `GET /api/v1/packages/{packageId}/handover-resolution[?stopRequestId=]`

`verdict`: `READY`(인계 전·중지 없음) / `STOPPED`(무효·취소 또는 주문 중지) / `HANDOVER_IN_PROGRESS`(살아 있는 fence·결과 대기) / `HANDED_OVER`. `inventoryApplyStatus`: NOT_REQUESTED·PROCESSING·APPLIED·REJECTED·UNKNOWN_RESULT. 조회는 아무것도 풀지 않는다.

## 3. MFC 가 내는 것

### `PackageHandedOver` (토픽 `mfc.fulfillment.v1`, 집계 `Package`/packageId)

`{orderId, fulfillmentId, shipmentId, deliveryId, packageId, mfcId, riderId, handoverId, inventoryCommitId, lines[{orderLineId, sku, lot, qty}], handedAt}`. **정확히 한 번 키 = `handoverId`**(재전달·재처리에 대비해 소비자가 중복을 막는다).

### `PackageHandedOverPosted` (토픽 `mfc.inventory.v1`, mfc-inventory)

`{commitId, packageId, handoverId, shipmentId, orderId, reservationId, mfcId, lines[{sku, lot, qty}], postedAt}` — 재고 원장 쪽 사실. `commitId` = `PackageHandedOver.inventoryCommitId`.

### `WorkStopResult` 의 인계 관련 값 (취소와의 경합)

| 그 주문의 포장 | 중지 판정 |
|---|---|
| 하나라도 인계됨 | **전체 중지 거절**: 상태 RESOLVED, `perPackage[]` 에 그 포장 `HANDED_OVER`(+ 진행 중이면 `HANDOVER_IN_PROGRESS`). 작업·출고·나머지 포장은 그대로(나머지는 계속 인계 가능). 인계분은 회수(S6·CS) |
| 살아 있는 fence·결과 대기 | 중지 **PENDING**, `packages[]` 에 `HANDOVER_IN_PROGRESS` — 확정되면 위 줄, fence 가 확정 요청 없이 만료되면 그 fence 를 닫고 중지 진행 |
| 없음 | 피킹 뒤 중지(ADR-0046) — 포장 INVALIDATED, 출고 CANCELLED, 취소 복귀 |

중지 판정과 인계 허가·확정은 **주문 단위 잠금**으로 한 줄로 선다(먼저 확정한 쪽 기준, MFC 1985행). 중지 기록이 생긴 주문은 새 허가를 받지 않는다.

## 4. 수량·상태

- 재고(IV-08): 포장 q 인계 = 포장 위치의 NORMAL·PACKED −q → **H−q, E−q, R−q, A 불변**. 예약 조각 `handed_qty` +q, 다 넘긴 조각 CONSUMED, 현물 점유 조각이 없으면 예약 CONSUMED. 같은 packageId 는 요청 ID 가 달라도 한 번(다른 fence 면 409 `FENCE_VERSION_MISMATCH`). 배송 완료·실패로 다시 차감하지 않는다.
- 포장: `SEALED → HANDED_OVER`(fence `COMMITTED`). 인계·무효 포장은 다시 인계 불가.
- 출고: 유효 포장(SEALED·HANDED_OVER)이 전부 인계 → `HANDED_OVER`, 일부 → `PARTIALLY_HANDED_OVER`.
- 수용 기준(MFC IV-08): H10/E10/R4/A6 에서 포장 4 인계 → H6/E6/R0/A6 — mfc-inventory 통합 시험으로 확인.

## 5. 다른 서비스가 할 일 (이 문서 기준, 아직 미구현)

| 서비스 | 할 일 |
|---|---|
| **delivery-dispatch** | `ShipmentReady`(mfc.fulfillment.v1) 구독 → 배송 생성 → **`DeliveryCreated`(shipmentId 포함)** → 후보·제안·수락 → **`DeliveryAssigned`(assignmentVersion)**. 재배정·해제 `DeliveryAssignmentChanged`, 픽업 전 취소 `DeliveryCancelledBeforePickup`(픽업 = 그 배송 포장 중 하나라도 `PackageHandedOver`). 발행은 Outbox → `delivery.delivery.v1` |
| **rider-app / rider-service** | 가게 도착 뒤 포장마다 QR → 허가 → 확정. 모든 포장 201 이면 픽업 완료 가능. `RiderRestricted` 를 내면 MFC 가 인계 차단에 쓸 수 있게 투영 추가 예정 |
| **delivery-execution** | `PackageHandedOver` 구독 → 그 배송의 모든 유효 포장이 인계되면 `PickupCompleted`(handoverIds). `POST /deliveries/{id}/pickups` 의 `handoverIds[]` 는 이 사건과 대조 |
| **order-service** | `PackageHandedOver` 구독 → 줄마다 `handedOverQty +q`, `unfulfilledQty −q`(line-quantity-changes reason `HANDOVER`, sourceRef = handoverId, 멱등). 주문 상태 PARTIALLY_HANDED_OVER/IN_DELIVERY. 불변식 `원수량 = 미이행 + 인계 누계 + 출고 전 취소 누계` |
| **claim-service** | 인계된 수량은 출고 전 취소 대상이 아니다(지금도 `WorkStopResult` HANDED_OVER → 클레임 REJECTED) |
| **order-orchestration** | S3: `HANDED_OVER` → ESCALATED(회수 S6·CS 미구현), `HANDOVER_IN_PROGRESS`·PENDING → 재조회 대기(구현됨). 배차 취소 단계(b)는 모든 포장 STOPPED 일 때만 — 배차 서비스가 생기면 |

## 6. 이번에 정한 것 (이렇게 두었고 이유는)

1. **fence 는 mfc-fulfillment 안**(ADR-0006 제안대로). 별도 공통 판정 서비스(Q-01)는 두지 않았다 — 주문 잠금으로 중지와 한 줄.
2. **만료 처리**: 확정 요청 전 fence 는 만료 시각이 지나면 끝(다시 찍기·중지 진행). 확정 요청 뒤에는 결과를 알 때까지 살아 있음. 문서끼리의 긴장(계약 "만료 후 재스캔" vs MFC "만료만으로 새 허가 금지")을 이렇게 나눴다 — 실물이 넘어갔을 수 있는 경우는 확정 요청이 있는 경우뿐이므로.
3. **부분 인계 뒤 전체 취소는 거절**(작업 유지). 피킹 뒤 부분 중지가 없고, 인계분은 회수가 필요하다(cancel-vs-handover §3 첫 줄).
4. **DISPATCH 작업은 만들지 않았다** — 언제·누구에게·어떤 줄로 만드는지 문서에 없다. 포장 → 출하대기 랙 이동은 지금 작업으로 추적하지 않는다.
5. **가게 도착을 QR 전제로 두지 않았다** — 생산자(delivery-execution)가 없다. 생기면 허가 검사에 더한다.
6. 재고 인계 전기 요청에 `handoverId`·`locationCode`(포장 위치)·줄별 `orderLineId`·`lotId` 를 더했다(inventory 계약 0.8.0) — 위치·LOT 단위 차감에 필요.
7. `PackageHandedOver` 집계는 계약 스키마대로 `Package`(키 packageId).

## 7. 열린 질문 (결정 필요 — 다른 서비스 구현 전에)

항목별 상세·선택지·권장은 [package-handover-decisions.md](package-handover-decisions.md)(D-1~D-9). D-1(만료 뒤 취소·재찍기)은 지금 구현이 MFC 1553행과 어긋나 결정이 가장 급하다.

| 항목 | 내용 | 근거 |
|---|---|---|
| Q-01 | 공통 판정 주체·토큰 유효시간·불명 복구 승인(지금은 MFC 안 + 센터 설정) | MFC 2205·1994행 |
| ADR-0006 | 상태 "제안" — 이번 구현이 그 안을 따름. 승인 여부 | adr/0006 |
| 가게 도착 전제 | QR 허가에 `RiderArrivedAtStore` 를 요구할지 | Delivery 336행 vs 계약 |
| 제한 라이더 | 어떤 제한 단계가 이미 배정된 배송의 인계를 막는지 | Delivery 603행, BackOffice 557행 |
| 배차 요청 시점 | 포장 시작 때(Delivery §3.1) vs `ShipmentReady` 뒤(saga §3) | Delivery 175~177행 |
| 픽업 완료 | 사건 기반(ADR-0006 결정 5) vs 버튼+검증(execution 계약) | — |
| 인계 후 회수비 | `[결정 필요]` | cancel-vs-handover 19행 |
| 재고 거절된 인계의 대사 | 운영자 화면(BO-12)·절차 | MFC 1551행 |
| 10kg 분할 | D6-2 로 지금은 주문당 출고 1개(분할 없음). 분할 도입 시 배송 여러 개 ↔ 출고 여러 개 | OMS 9, MFC Q-10 |
| 냉장·냉동 대기 한계 | 출하대기 온도·시간 | MFC 413·771행, Q-05 |
| 문서 불일치 | 취소 포장 상태(VOIDED vs ADR-0046 INVALIDATED), DeliveryType enum(이벤트 NORMAL/SPLIT/… vs API FORWARD/…), DispatchFailed 사유 enum | 조사 기록 |
