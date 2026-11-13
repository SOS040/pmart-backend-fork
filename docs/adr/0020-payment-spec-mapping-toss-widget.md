# ADR-0020 결제 구현 명세를 payment-service 에 반영 — 결제 시도 ID = 토스 orderId, 고객 승인 요청, 웹훅은 대사 신호

- 상태: 제안 (2026-10-30, 사용자 결정)
- 관련: ADR-0005(payment-service 독립, PG 가 거래 사실의 정본), `docs/architecture/payment-system-spec.md`(외부 입력 명세), 구현 계획 D4·D5, `contracts/openapi/payment-service.yaml`

## 배경
- 토스페이먼츠 결제위젯 연동 명세를 받았다. 명세는 결제를 "OMS 내부 모듈"로, 웹훅을 범위 밖으로 둔다.
- 사용자 결정(2026-10-30): **결제는 독립 마이크로서비스(payment-service)** 로 하고, **웹훅은 기존 계약대로** 두며, 명세의 미결정 사항(§12 D1~D8)은 구현하지 않는다.

## 결정
1. **ID**: 결제 시도 ID 를 토스 `orderId` 로 그대로 쓴다(`pay-` + 무작위 32자, 명세 R5). 계약의 `paymentAttemptId` = `checkout.pgOrderId` = 토스 `orderId`. OMS 주문/초안은 `orderId`(계약 StartPaymentRequest) = `order_ref` 로 참조만 한다(주문 1 : 결제 시도 N). `paymentKey` 는 받은 그대로 저장(유일), 승인 API 호출 전에 저장(R8).
2. **결제 시작**은 내부 명령(orchestration → payment)이다. 금액·예약·가격 동결 판정은 호출자가 끝낸 뒤 확정 청구액과 `resourceExpiresAt`(예약·슬롯 중 가장 이른 만료, 계약에 필수로 추가)을 보낸다. 같은 주문에 진행 중·불명·불일치 시도가 있으면 새 orderId 를 발급하지 않는다(R7).
3. **고객 앱 API 추가(D4 반영)**: `POST /payment-confirmations {orderId, paymentKey, amount}`(이 세 값만, R4), `POST /payment-authorization-failures`, `GET /payments/{orderId}`. 본인 결제 시도만(다른 고객 것은 404). 승인 요청은 명세 §6.3 순서로 검사하고, 금액 불일치·만료·다른 paymentKey 면 **토스를 부르지 않는다**.
4. **상태**: 명세 §5 세부 단계를 `stage` 로 두고, 계약 `state` 는 그 요약이다(READY→CREATED, CONFIRMING→PENDING, CONFIRM_UNKNOWN·MANUAL_REVIEW→UNKNOWN …).
5. **승인 성공**은 토스 응답 `status=DONE` 이고 paymentKey·orderId·금액·통화가 결제 시도와 같을 때만(F6, R3). 아니면 `MISMATCH_HOLD`.
6. **결과 불명**: 5xx·타임아웃·연결 오류·`409 IDEMPOTENT_REQUEST_PROCESSING` → `CONFIRM_UNKNOWN`. paymentKey 로 원거래를 조회한다(직후 1회 → 1분×5 → `MANUAL_REVIEW` 상신 → 15분). 토스가 `IN_PROGRESS` 이고 예약이 유효하면 **같은 멱등키**로 승인을 재요청한다. 시간 경과만으로 실패 처리하지 않는다. 승인 호출 없이 예약이 만료된 READY 는 주기 작업이 `EXPIRED` 로 정리한다.
7. **취소**: 요청 ID(Idempotency-Key)가 토스 취소 멱등키다. 한도 = 승인액 − 성공 누적 − 진행 중·불명 점유, 넘으면 토스를 부르지 않고 422. 결과 불명은 조회해서 `cancels[]` 에 없을 때만 같은 멱등키로 재요청한다(새 키·새 금액 금지). 성공한 취소의 `transactionKey` 를 저장한다.
8. **웹훅**(기존 계약 `/webhooks/toss`): 서명 검증 → 본문 해시로 중복 제거 → 해당 결제의 원거래 조회를 앞당긴다. **웹훅 내용만으로 승인·취소를 확정하지 않는다.** 서명은 `Base64(HMAC-SHA256(웹훅 키, 원문 본문))` 로 가정했다 — 실제 토스 웹훅 서명 방식은 웹훅을 켤 때 확인해 맞춘다. 웹훅 키가 없으면 모든 웹훅을 401 로 거부한다(대사는 재조회 작업이 맡는다).
9. **키**: 결제위젯 키(gck/gsk)만 받는다. `allow-live-keys=false` 환경에서 `live_` 키면 기동을 거부한다. 시크릿 키·Authorization 헤더·카드 정보는 로그·이벤트·응답에 넣지 않는다.
10. **사건**: `PaymentApproved`·`PaymentFailed`·`PaymentResultUnknown`·`PaymentMismatch`·`RefundResult`·`CompensationCancelResult`, 토픽 `order.payment.v1`(계약).

## 구현하지 않은 것(명세 §12 미결정, 안전한 기본 동작만)
- D1 비동기 결제수단(가상계좌 등): `DONE` 이 아닌 승인 응답은 `MISMATCH_HOLD`. 앱 `pending` 처리는 앱 몫.
- D2 부분 취소 불가 결제의 부분 취소: 토스를 부르지 않고 `422 PARTIAL_CANCEL_NOT_ALLOWED`(결정 필요).
- D3 주류 성인인증: 결제 인증을 성인인증으로 기록·표시하지 않는다(`adultVerified` 를 채우지 않음).
- D4 인증 성공 시 예약 연장: 하지 않는다. D5 승인 기한: 예약 만료 기준만 본다. D6 `customerKey`: 2026-11-02 결정 — 회원 accountId 를 그대로 쓴다(별도 발급·보관 없음, 앱 안내 `docs/handoff/clients/archive/2026-11-02-clients-notice-toss-customer-key.md`). D7 orderId 재사용: 하지 않는다. D8 `variantKey`: 쓰지 않는다.

## 결과
- 테이블: `payment_attempt`, `payment_cancel`, `payment_event_log`, `payment_inquiry`, `toss_webhook_event`.
- 주문 쪽(order-service·order-orchestration)이 아직 없어 결제 시작은 서비스 토큰으로만 시험했다. 실제 앱 결제 흐름은 주문 초안 `nextAction=PAY`(D4)가 생겨야 이어진다.
- 승인·실패·불명 사건을 받아 재고·슬롯 점유를 해제·확정하는 일은 orchestration 구현 때 한다(결제 모듈은 직접 해제하지 않는다).
