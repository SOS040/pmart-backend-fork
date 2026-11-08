# 고객 앱 결제(토스 결제위젯) 연동 — payment-service 고객 API (ADR-0020)

- 작성: pmart-backend, 2026-10-30
- 대상: pmart-clients 고객 앱(Flutter, Android)
- 계약 정본: `pmart-backend/contracts/openapi/payment-service.yaml` 의 `checkout` 태그
- 명세: `pmart-backend/docs/architecture/payment-system-spec.md` §3(ID 규칙)·§10(앱 구현) — 앱 쪽 규칙은 그 명세를 따른다

## 0. 지금 상태 (먼저 읽을 것)
- **결제 진입(결제 시작)은 앱이 직접 부르지 않는다.** 주문 초안 응답의 `nextAction=PAY` 에 결제 진입 정보(`orderId`, `amount`, `orderName`, `clientKey`)가 실려 오는 구조다(D4). 주문 쪽(order-service)이 아직 없어서 **지금은 실제 앱에서 결제 진입까지 갈 수 없다.** 아래 API 는 주문 쪽이 붙으면 그대로 쓴다.
- `customerKey` 는 아직 정하지 않았다(명세 §12 D6). 백엔드는 발급하지 않는다. 결제위젯 생성에 필요하면 정해질 때까지 앱 쪽 임시값으로 시험한다.
- 키: 클라이언트 키(gck)는 결제 진입 정보로 내려준다. **시크릿 키는 앱에 넣지 않는다.** LAN 은 토스 공식 문서의 테스트 키를 쓴다.

## 1. 흐름
```
① (주문 초안 nextAction=PAY) 결제 진입 정보 받기: orderId(= pay-…), amount, orderName, clientKey
② 앱이 orderId·amount 를 결제가 끝날 때까지 보관(R1)
③ 위젯 금액 = amount (updateAmount), requestPayment(orderId, orderName)
④ 토스 결과
   success → 결과의 orderId·amount 가 보관값과 같은지 확인(R2) → POST /payment-confirmations
   fail    → 보관한 orderId 로 POST /payment-authorization-failures (결과 orderId 는 빈 값일 수 있음)
⑤ 응답 stage=APPROVED 일 때만 "결제 완료" 표시. 응답을 놓치면 GET /payments/{orderId} 로 확인(승인 요청을 다시 보내지 않음)
```

## 2. API (게이트웨이 `/api/v1`, 고객 토큰)
| 할 일 | 요청 | 응답 |
|---|---|---|
| 승인 요청 | `POST /payment-confirmations` `{orderId, paymentKey, amount}` **이 세 값만**(R4) + `Idempotency-Key`, `X-Source-System` | `CheckoutPayment {orderId, state, stage, amount, method?, approvedAt?, failure?}` |
| 인증 실패·취소 보고 | `POST /payment-authorization-failures` `{orderId, errorCode, errorMessage?}` | 같은 형식. `PAY_PROCESS_CANCELED` 면 `CANCELED_BY_USER`, 그 밖은 `AUTH_FAILED` |
| 상태 조회 | `GET /payments/{orderId}` | 같은 형식 |

- 본인 결제만 보인다. 다른 고객의 orderId 는 404 다.
- orderId·paymentKey 는 받은 그대로 보낸다(대소문자·공백·인코딩 변경 금지, R6).

## 3. `stage` 와 화면
| stage | 뜻 | 화면 |
|---|---|---|
| `READY` | 결제창 진입 가능 | — |
| `CONFIRMING` | 승인 진행 중 | 진행 중(중복 클릭·뒤로가기 막기) |
| `APPROVED` | **결제 완료** | 완료 |
| `CONFIRM_UNKNOWN`, `MANUAL_REVIEW` | 결과 확인 중(서버가 토스에 재조회) | "결제 결과 확인 중" — 다시 결제하게 하지 않는다 |
| `MISMATCH_HOLD` | 금액·주문 불일치로 보류 | "확인 필요" 안내 |
| `CONFIRM_FAILED` | 승인 실패 | 실패(`failure.code/message`) |
| `AUTH_FAILED`, `CANCELED_BY_USER` | 결제창 실패·취소 | 실패·취소 |
| `EXPIRED` | 결제 가능 시간이 지남 | 주문서 다시 확인 |
| `PARTIAL_CANCELED`, `CANCELED` | 환불 반영 | 주문 상세에서 표시 |

## 4. 오류
| HTTP / code | 뜻 | 앱 처리 |
|---|---|---|
| 400 `AMOUNT_MISMATCH` | 승인 요청 금액 ≠ 결제 금액(토스 호출 안 함) | 결제 실패 안내. 금액을 앱이 바꾸지 않는다 |
| 409 `PAYMENT_KEY_MISMATCH` | 이미 다른 paymentKey 로 진행된 결제 | 상태 조회 후 안내 |
| 409 `INVALID_STATE` | 이미 끝난 결제(실패·만료) | 주문서에서 다시 결제 |
| 422 `RESERVATION_EXPIRED` | 재고 예약·슬롯 만료 — 승인하지 않음(결제되지 않음) | "결제 가능 시간이 지났어요" → 주문서 재확인 |
| 404 | orderId 없음·본인 것 아님 | 오류 안내 |

## 5. 참고
- 결과 불명은 서버가 1분 간격 5회, 이후 15분 간격으로 토스에 재조회해 결정한다. 앱은 `GET /payments/{orderId}` 로만 확인한다.
- 환불·부분 취소는 고객 앱이 부르지 않는다(클레임 → 결제 내부 명령).
- LAN 배포 여부는 따로 알린다.
