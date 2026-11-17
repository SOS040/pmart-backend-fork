# [pmart-backend → pmart-clients] 요청: 인계 뒤 반품(회수) — 백오피스 접수·조치, 고객 앱 회수비 결제 (2026-11-17)

- 보낸 영역: platform(PC 1)
- 받는 앱: backoffice(고객센터 메뉴 — PC 2), customer_app(주문·클레임 상세의 결제 화면)
- 근거: D-7·ADR-0060 결정 2, 사용자 결정 2026-11-17 "추천대로"(1차 감액 없음·면제 사유 다섯·회수 실패 2번 뒤 상담원 재연락), 계약 `claim-service.yaml` 0.2.0, 흐름 `docs/business/flows/return-exchange-refund.md` §6. **백엔드는 커밋만 — LAN 배포 전**(배포되면 PC 1 이 알림).

## 1. 언제 생기나
라이더가 상품을 받아 간 뒤(픽업·배달 시작 뒤) 고객이 상품을 더는 원하지 않으면 **고객센터로만** 반품(회수)을 받는다. 고객 사유면 **회수비 9,000원**을 고객이 앱에서 따로 결제해야 회수를 시작하고, 회사 잘못(오배송·상품 불량·배송 중 파손·약속 시간 크게 지연·잘못된 대체)이면 회수비 없이 바로 회수한다. 회수품이 MFC 에 들어와 검수되면 **상품값 전액**(원래 단가 × 수량, 할인은 나눠 뺌)을 원래 결제로 환불한다 — 봉투값·배달팁은 돌려주지 않는다.

## 2. 백오피스(고객센터 — CS_AGENT·OPERATOR_ADMIN)
| 할 일 | 호출 |
|---|---|
| 반품 접수 | `POST /api/v1/claims`(Idempotency-Key·X-Source-System) `{orderId, type: "RETURN", lines: [{orderLineId, qty}], reason, reasonDetail?, channel: "CS", faultCode?, identityCheck: {method, note?}, caseRef?}` → 201 Claim |
| 반품 조회 | 기존 `GET /api/v1/claims?orderId=&type=RETURN`, `GET /api/v1/claims/{claimId}` |
| 회수 실패 뒤 조치 | `POST /api/v1/claims/{claimId}/recovery-actions` `{action: "RETRY_PICKUP" \| "CANCEL", note}`(X-Expected-Version 선택) → 200 Claim |

- 반품 가능 수량 = 주문 상세(`GET /orders/lookups/{orderId}`)의 인계 수량 − 이 주문의 진행·완료 반품. 화면에서 행마다 최대값을 보여 주면 좋다. 넘으면 422 `QUANTITY_EXCEEDS_AVAILABLE`, 인계 전이면 422 `NOT_HANDED_OVER`.
- `faultCode`(회사 잘못 — 고르면 회수비 면제): `MISDELIVERY` 오배송 · `DEFECTIVE` 상품 불량 · `DAMAGED_IN_DELIVERY` 배송 중 파손 · `SEVERE_DELAY` 약속 시간 크게 지연 · `WRONG_SUBSTITUTE` 잘못된 대체. 비우면 고객 사유(9,000원).
- `identityCheck.method` 필수 — CS 접수와 같은 값(`ORDER_DETAILS_MATCH`·`APP_SIGNED_IN_CHAT`·`OTHER` — OTHER 면 `note` 필수). 고객이 말한 번호·주소는 넣지 않는다.
- 다른 직원 역할 403, 직원이 `type: CANCEL` 이면 422 `UNSUPPORTED_ACTION`(상품준비 뒤 고객센터 취소 없음 — ADR-0060), 회수비 결제 기한 설정이 없으면 고객 사유 반품만 422 `CONFIG_REQUIRED`.
- 상태 보기: `state` FEE_PENDING(회수비 결제 대기) · IN_PROGRESS(회수·검수·환불 중) · RECOVERY_FAILED(회수 두 번 실패 — **고객에게 다시 연락**) · CANCELLED · CLOSED. `pickup.state`(REQUESTED → PICKED_UP → DELIVERED_TO_STORE → RECEIVED → INSPECTED, 실패면 FAILED)·`pickup.attempts`·`pickup.failedAttempts`, 줄별 `pickedUpQty`·`receivedQty`·`inspection{resaleQty, disposeQty}`, `refunds[]`, `feePayment`.
- 조치: RECOVERY_FAILED 에서 고객과 다시 연락한 뒤 `RETRY_PICKUP`(새 회수 요청 — 회수비 다시 안 받음) 또는 `CANCEL`(반품 취소 — MFC 가 받은 회수품이 없을 때만, FEE_PENDING 에서도 가능). 그 밖 상태 409. 조치 사유(note)는 기록에만 남는다.
- 회수 실패 사건함 표시는 case R-8(PC 2 — 구독 전).

## 3. 고객 앱(회수비 결제)
| 할 일 | 호출 |
|---|---|
| 결제할 반품 보기 | `GET /api/v1/claims?type=RETURN`(본인 것) → `GET /api/v1/claims/{claimId}` 의 `state == FEE_PENDING` 과 `feePayment`: `amount`(9000), `orderName`("반품 회수비"), `paymentAttemptId`, `state`(WAITING·PAID·UNPAID…), `deadlineAt` |
| 결제 | 토스 결제위젯을 **`orderId = feePayment.paymentAttemptId`**, `amount`, `orderName` 으로(clientKey·customerKey 는 기존 `GET /payment-widget-config`) → 성공 리다이렉트의 `paymentKey` 로 기존 `POST /api/v1/payment-confirmations` `{orderId: paymentAttemptId, paymentKey, amount}` |
| 결제 실패 뒤 다시 | `POST /api/v1/claims/{claimId}/fee-payment-attempts`(Idempotency-Key·X-Source-System, 본문 없음) → 200 Claim(새 `paymentAttemptId`). 기한이 지났거나 결제 대기가 아니면 409, 남의 것 404 |

- `paymentAttemptId` 가 비어 있으면 결제 시도를 준비 중 — 잠시 뒤 다시 읽는다.
- 결제가 승인되면 클레임이 IN_PROGRESS 로 바뀌고 회수가 시작된다. `deadlineAt` 까지 결제하지 않으면 반품이 취소된다(CANCELLED, `feePayment.state` UNPAID). 화면에 남은 시간과 "결제하시면 라이더가 상품을 가지러 갑니다 / 상품은 검수 뒤 환불돼요"를 권한다(문구는 앱에서 정함).
- 회사 잘못 반품은 `feePayment` 가 없고 `fees.returnPickupFee` 0 — 결제 화면 없이 진행 상태만.
- 고객 앱에서 반품을 직접 접수하지는 않는다(`POST /claims` type RETURN 은 422 — "고객센터로 문의").

## 4. 아직 없는 것
- 알림: 사건 `ReturnFeePaymentRequested`(결제 필요)·`ReturnRecoveryFailed`(회수 실패)·`ClaimClosed` 는 나가지만 알림함 연결은 아직이다(notification). 연결되면 알림에서 이 결제 화면으로 들어오게 해 주세요.
- 회수 배송 자체(라이더 배차·수거·반납)는 delivery DD-3(PC 5) 구현 뒤에 실제로 움직인다.
- 회수비 결제 기한 값은 LAN 설정에서 정한다(제안 24시간 — 사용자 확인 전).
