# [pmart-backend → pmart-clients] 안내: 고객 주문 취소(전체 취소) 동작 (2026-10-31)

- 계약: `contracts/openapi/claim-service.yaml`(`POST /claims`, `GET /claims/{claimId}`, `GET /claims`, `GET /claims/{claimId}/refunds`) — 경로·스키마는 바뀌지 않았다. `POST /claims` 설명에 구현 범위와 422 코드 두 개(`PARTIAL_CANCEL_NOT_ALLOWED`, `UNSUPPORTED_ACTION`)를 더했다.
- 흐름 정본: `docs/business/flows/cancel-vs-handover.md` §6
- LAN 반영: 이 문서를 쓸 때는 아직 LAN 에 배포하지 않았다. 배포 뒤 따로 알린다.

## 1. 무엇이 되나
- 지금까지 `POST /claims` 는 501 이었다. 이제 **고객 본인 주문의 전체 취소**를 받는다.
- 이행·배송 기능이 아직 없어서, 확정된 주문은 모두 "피킹 전"이다. 그래서 지금은 취소하면 바로 처리된다.
- 반품·교환(`type: RETURN·EXCHANGE`)은 아직 없다 → 422 `UNSUPPORTED_ACTION`.
- 철회(`POST /claims/{id}/withdrawals`)는 아직 501 이다. 철회 버튼은 숨기거나 501 을 "준비 중"으로 보여 달라.

## 2. 요청 (앱이 지금 보내는 형태 그대로)
```json
POST /api/v1/claims   (Idempotency-Key, X-Source-System, 고객 토큰)
{ "orderId": "...", "type": "CANCEL", "reason": "CHANGE_OF_MIND",
  "channel": "CUSTOMER_APP",
  "lines": [ { "orderLineId": "<orderId>-L1", "qty": <미이행 수량> }, ... ] }
```
- **모든 주문행 × 미이행 수량(`unfulfilledQty`)** 을 보내야 한다. 일부 행·일부 수량이면 422 `PARTIAL_CANCEL_NOT_ALLOWED`(고객 임의 부분 취소는 정책상 열지 않는다 — OMS 8.4).
- 성공은 **201** 과 `Claim` 본문. 같은 Idempotency-Key 재전송은 같은 클레임을 돌려준다.

## 3. 거절
| 상태 | code | 뜻 | 앱 처리 |
|---|---|---|---|
| 404 | ORDER_NOT_FOUND | 남의 주문·없는 주문 | 주문 목록 새로고침 |
| 422 | ORDER_NOT_CANCELLABLE | 확정(CONFIRMED) 상태가 아님(이미 취소 등) | 주문 상세 새로고침 |
| 422 | PARTIAL_CANCEL_NOT_ALLOWED | 전체가 아님 | 전체 취소만 제공 |
| 422 | QUANTITY_EXCEEDS_AVAILABLE | 미이행보다 많거나 없는 행 | 주문 상세 새로고침 |
| 422 | DUPLICATE_CLAIM | 이 주문에 진행 중인 취소가 있음 | 기존 클레임 조회로 이동 |
| 422 | UNSUPPORTED_ACTION | 반품·교환 | "준비 중" |

**501·422 는 재시도하지 말아 달라.** 오늘 실기기 로그에서 같은 주문의 `POST /claims` 가 501 로 5번 나갔다(사용자가 다시 눌렀는지 자동 재시도인지 로그로는 구분 안 됨). 업무 거절(4xx)은 같은 키로 다시 보내도 결과가 같다.

## 4. 진행 상태 (`GET /claims/{claimId}` 로 조회)
| state | 화면 문구 예 | 뜻 |
|---|---|---|
| STOP_PENDING | 취소 확인 중 | 접수됨. 매장 작업 중지·재고 해제·주문 취소 반영 중 |
| IN_PROGRESS | 취소 완료 · 환불 진행 중 | 주문 취소 확정(주문 `status=CANCELLED`). 환불 실행 중 |
| CLOSED | 환불 완료 | PG 환불 성공 확인 |
| REJECTED | 취소할 수 없음 | 이미 라이더에게 넘어간 상품이 있다(`lines[].rejectReason=HANDED_OVER`). 지금은 생기지 않는다 |

- 보통 몇 초 안에 IN_PROGRESS → CLOSED 로 간다. 결제 쪽 응답이 늦으면 IN_PROGRESS 가 길어질 수 있다(서버가 30초 간격으로 다시 확인).
- **"환불 완료"는 CLOSED(또는 환불 `state=COMPLETED`)일 때만** 보여 달라. IN_PROGRESS 는 아직 PG 성공 전이다.
- 클레임 `lines[].decision`: PENDING → APPROVED(취소 확정 수량 `approvedQty`).

## 5. 환불 (`Claim.refunds[]` 또는 `GET /claims/{claimId}/refunds`)
- 금액 `amount` = 결제 승인액 전액(상품액 + 봉투값 + 배달팁). `breakdown` 에 itemAmount·bagFee·deliveryTip.
- `state`: PENDING_EXECUTION(실행 대기) → PG_PENDING(PG 결과 확인 중) → COMPLETED / FAILED / UNKNOWN(확인 중). FAILED 는 고객센터 확인이 필요하다고 안내.
- 토스 테스트 키 환경이라 실제 돈은 움직이지 않는다.

## 6. 같이 바뀌는 것
- 주문 상세 `GET /orders/{id}`: 취소되면 `status=CANCELLED`, 행 `unfulfilledQty=0`·`cancelledBeforeShipQty=원수량`. (주문 상세의 `claims` 는 아직 빈 배열 — 클레임은 `GET /claims?orderId=` 로 조회)
- 첫 구매 무료배달을 쓴 주문을 전체 취소하면 첫 구매 자격이 돌아온다(다음 주문서에서 다시 무료).
- 내 클레임 목록 `GET /claims`: 최근 순, 아직 cursor 없음(`size` 건까지).

## 7. 앱에서 확인할 것
1. 취소 버튼이 모든 행 × 미이행 수량으로 보내는지.
2. 501·4xx 에서 자동 재시도하지 않는지.
3. STOP_PENDING·IN_PROGRESS 동안 클레임 조회로 상태가 바뀌는지, CLOSED 전에 "환불 완료"를 보이지 않는지.
4. 철회 버튼 처리(아직 501).
