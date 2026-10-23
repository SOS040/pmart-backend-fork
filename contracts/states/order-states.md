# Order 계열 상태표

근거: OMS 3·4·7·8.4, A.1.5, flows/order-checkout-saga.md, flows/cancel-vs-handover.md

## OrderDraft / Order (order-service)

| 상태 | 의미 | 진입 조건 | 다음 |
|---|---|---|---|
| DRAFT | 초안·가격 동결 중(10분) | createOrderDraft 검증 통과 | RESOURCE_PENDING, EXPIRED |
| RESOURCE_PENDING | 재고·여력·슬롯 확보 진행(S1 b~d) | checkout 요청 | PAYMENT_PENDING, DRAFT(재견적), EXPIRED |
| PAYMENT_PENDING | PG 진입 | 전품목 예약+Hold+Slot 성공 | CONFIRMED, PAYMENT_UNKNOWN, EXPIRED |
| PAYMENT_UNKNOWN | 승인 결과 불명(원거래 조회 중) | 타임아웃·응답 유실 | CONFIRMED, EXPIRED(미승인 확정), 수동 상신 |
| CONFIRMED | 주문 확정 | 승인·금액·통화 대사 일치 + 자원 CONFIRMED | PREPARING, CANCELLED |
| PREPARING | 상품준비(현장 준비 시작 확정 반영). 예약 시간 변경 차단 | PreparationStarted | PARTIALLY_HANDED_OVER, IN_DELIVERY, CANCELLED(미인계분) |
| PARTIALLY_HANDED_OVER | 일부 포장 인계 | PackageHandedOver(일부) | IN_DELIVERY |
| IN_DELIVERY | 모든 유효 포장 인계·배송 중 | 전 포장 HandedOver | PARTIALLY_DELIVERED, COMPLETED |
| PARTIALLY_DELIVERED | 분할배송 일부 전달 | DeliveryCompleted(일부) | COMPLETED |
| COMPLETED | 모든 유효 배송 전달 완료 + 미전달분 환불/취소로 해소 | — | (클레임은 별도 축) |
| CANCELLED | 전체 취소 확정(환불 완료와 별개) | S3 취소 수량 = 유효 미인계 전량 | — |
| EXPIRED | 초안 만료·미승인 확정 | 10분 만료 + PG 미승인/실패 확정 | — |

금지 전이: PAYMENT_UNKNOWN→EXPIRED(원거래 미확인), COMPLETED→과거 상태, 시간 경과→COMPLETED.

## CancellationRequest (claim-service / orchestration S3) — 주문 상태와 별개 축

| 상태 | 의미 |
|---|---|
| RECEIVED | 접수 |
| STOP_CONFIRMING | 현장 출고 중지 확인 중 |
| HANDOVER_CONFIRMING | 인계 fence 진행 중 → 결과 대기 |
| CANCELLING | 중지 확정분 취소 처리(예약 해제·PG 취소) |
| CANCELLED | 취소 완료(PG 취소 성공 확인) |
| NOT_CANCELLABLE | 전량 인계 확정 → 회수/반품 안내 |
| PARTIALLY_CANCELLED | 미인계분만 취소, 인계분은 회수 심사 |
| UNKNOWN | 현장/PG 응답 불명(원요청 조회) |

## ReservationTimeChange (S4)

`PENDING → CONFIRMED | REJECTED(PREPARATION_STARTED / SLOT_UNAVAILABLE / LOT_INELIGIBLE / CONFIG_REQUIRED) | UNKNOWN`. 실패 시 새 슬롯만 해제.

## 자원 확보 하위 축 (orchestration 보관)

| 자원 | 상태 |
|---|---|
| inventoryReservation | NOT_REQUESTED → HELD → CONFIRMED → CONSUMED / RELEASED / EXPIRED / BLOCKED ; HELD→PAYMENT_PENDING_HOLD(PG 불명 시 만료 대신) ; UNKNOWN |
| capacityHold | NOT_REQUESTED → HELD → CONFIRMED / RELEASED / EXPIRED ; UNKNOWN |
| slotHold | NOT_REQUIRED / NOT_REQUESTED → HELD → CONFIRMED / RELEASED / EXPIRED ; UNKNOWN |
| payment | NOT_STARTED → STARTED → APPROVED / FAILED / UNKNOWN / MISMATCH ; APPROVED→COMPENSATION_CANCELLED |

## Saga (order-orchestration)

`STARTED → STEP_{n}_PENDING → STEP_{n}_DONE … → COMPLETED | COMPENSATING → COMPENSATED | MANUAL_REQUIRED(자동 복구 한도 초과, case 상신) | UNKNOWN_WAITING(원결과 조회 중)`.
보상 로그 항목: `resourceType, resourceId, action, result(SUCCESS/FAILED/UNKNOWN), at`. 이번 시도 신규 자원만 기록.
