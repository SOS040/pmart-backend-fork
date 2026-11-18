# [pmart-backend → pmart-mfc-simulation] 알림: R-21 실제 주문 넣기 — 개발용 고객 토큰·시험 결제 (2026-11-19)

- 보낸 영역: platform(PC 1)
- 앞 회신: `2026-11-19-simulator-reply-r20-r21.md`
- 계약: auth-service **0.3.0**, payment-service **0.2.0**. LAN: **배포 전**(배포하면 이 줄을 고친다)
- 둘 다 **LAN 설정을 켠 곳에만** 있다(pmart-config `auth-service-lan.yml`·`payment-service-lan.yml`). 운영 설정에는 없다.

## 1. 고객 토큰 — `POST http://192.168.0.40:8501/api/v1/internal/dev-customer-tokens`
- **게이트웨이가 아니라 auth-service 포트로 직접**(직원 `dev-token-exchange` 와 같은 방식). 헤더 `Idempotency-Key`(아무 UUID — 기록 안 함)·`X-Source-System`(예 `mfc-simulator`)·`X-Device-Id`, 본문 `{ "accountId": "CU-…" }`.
- 응답 `{accountId, accessToken, accessTokenExpiresAt, sessionId}` — `typ=CUSTOMER`, **15분**, refresh 없음(만료되면 다시 받는다). 이 토큰으로 게이트웨이(`https://192.168.0.40:8443/api/v1`)의 고객 API(장바구니·주문서·결제)를 부른다.
- **허용 목록의 기존 고객만** — 목록 밖 403 `DEV_CUSTOMER_NOT_ALLOWED`, auth 에 없는 계정 404, ACTIVE 아님 403 `ACCOUNT_NOT_AVAILABLE`.
  - 지금 목록: `CU-fdccbd6b-9817-4e95-b55a-2c4e71539116`(PC 1 지정 고객 — 11-18 LAN 시험 주문 계정, 배송지 1). 시뮬레이터 전용 "시험 고객"은 PC 1 이 정해 목록에 더한다(사용자 결정 뒤).
- 발급 기록: auth 세션 행(계정·기기·X-Source-System·시각). 토큰은 화면·저장소·로그에 남기지 않는다(직원 토큰과 같음).
- 약관 동의(CU-1)는 LAN 에서 켜져 있다 — 그 고객이 필수 동의를 마치지 않았으면 주문서·결제가 422 `ACCOUNT_NOT_PURCHASABLE`. 필요하면 이 토큰으로 `POST /me/consent-changes`(현행 버전은 `GET /consent-terms`).

## 2. 시험 결제 — `paymentKey = TEST_AUTO_<pgOrderId>`
- 주문 결제는 평소처럼 시작한다(결제 시작 응답의 `checkout.pgOrderId`·금액). 토스 결제창 대신 바로 `POST /api/v1/payment-confirmations`(고객 토큰):
  `{ "orderId": "<pgOrderId>", "paymentKey": "TEST_AUTO_<pgOrderId>", "amount": <결제 금액> }`.
- 서버가 토스를 부르지 않고 승인한다(method `TEST_AUTO`). 그 앞의 검사(본인 결제·금액 400 AMOUNT_MISMATCH·만료 422)와 그 뒤(PaymentApproved → 주문 확정 → MFC 작업 지시)는 실제와 같다. `stage=APPROVED` 를 받은 뒤 주문번호를 보여 주면 된다.
- 키의 orderId 가 다르면 승인하지 않는다(`TEST_AUTO_KEY_MISMATCH`). 그 결제의 취소·환불(결품·고객 취소)도 토스 없이 처리된다.
- 결제 수단 enum 은 그대로(`TOSS_PAYMENTS`) — 주문·오케스트레이터 경로를 바꾸지 않으려고 승인 키로 구분한다(회신에 적은 "결제 수단 TEST_AUTO" 대신).
