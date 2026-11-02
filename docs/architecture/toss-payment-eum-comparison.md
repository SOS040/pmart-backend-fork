# 토스 결제 처리 비교 — Eum-main vs pmart (시퀀스 다이어그램)

- 작성: 2026-11-02. 참고용 비교(설계 결정 아님). pmart 결정은 ADR-0020, 외부 명세는 `payment-system-spec.md`.
- Eum 출처(읽기 전용 `../Eum-main`): `eum_frontend/src/pages/CheckoutPage.jsx`, `eum_backend/paymentserver/.../customer/service/PaymentService.java`, `client/TossPaymentsClient.java`, `TossApprovalStatusPoller`.
- 두 시스템 모두 **토스 결제위젯 + 서버 승인 API** 방식이다. 차이는 금액·ID 를 누가 정하는지, 결과 불명을 어떻게 다루는지, 키를 어떻게 다루는지에 있다.
- 다이어그램의 ⚠ 는 Eum 의 결함, ✔ 는 pmart 의 대응이다.

## 1. 정상 결제 — Eum

```mermaid
sequenceDiagram
    autonumber
    actor U as 고객
    participant FE as Eum 웹 (React)
    participant ORD as Eum order
    participant PAY as Eum paymentserver
    participant T as 토스

    Note over FE: clientKey = 빌드 환경변수 VITE_TOSS_CLIENT_KEY
    FE->>T: loadTossPayments(clientKey)
    FE->>T: widgets(customerKey = String(user.userId))
    Note right of FE: ⚠ customerKey = DB 자동 증가 순번 ID

    U->>FE: 결제하기
    FE->>ORD: 주문 생성
    ORD-->>PAY: Kafka PaymentRequested (orderId, amount)
    PAY->>PAY: Payment READY (주문 1 : 결제 행 1)
    FE->>PAY: POST prepare (orderId, amount, 이름, 이메일)
    PAY->>PAY: refreshPrepareContext(request.amount)
    Note right of PAY: ⚠ 클라이언트가 보낸 금액으로 서버 금액을 덮어씀

    FE->>T: requestPayment(orderId = "order-"+orderId, customerName, customerEmail)
    Note right of FE: ⚠ 재시도해도 같은 토스 orderId, 개인정보를 PG 에 전달
    T-->>FE: successUrl (paymentKey, orderId, amount)

    FE->>PAY: POST confirm (orderId, paymentKey, amount)
    PAY->>PAY: amount == 저장 금액 확인 (= 클라이언트가 준 값이라 의미 없음)
    PAY->>T: POST /v1/payments/confirm (Basic secretKey, Idempotency-Key)
    Note right of PAY: ⚠ INFO 로그에 secretKey·Authorization 헤더 출력
    T-->>PAY: 승인 응답
    PAY->>PAY: approve(paymentKey 저장), outbox PaymentCompleted
    Note right of PAY: 응답을 받은 뒤에야 paymentKey 저장, 상태 검증 없이 성공 처리
    PAY-->>FE: SSE 결제 완료 push
```

## 2. 정상 결제 — pmart

```mermaid
sequenceDiagram
    autonumber
    actor U as 고객
    participant APP as 고객앱 (Flutter)
    participant ORD as order-service
    participant ORC as order-orchestration
    participant RES as inventory·capacity
    participant PAY as payment-service
    participant T as 토스

    U->>APP: 주문서에서 결제하기
    APP->>ORD: POST /order-drafts/{id}/checkouts (acceptedQuoteVersion, Idempotency-Key)
    ORD->>ORD: 견적 버전·10분 동결·차단 사유 확인
    ORD->>ORC: S1 사가 시작 (amount = 동결 quote.totalAmount)
    ORD-->>APP: 202 (orderId)
    ORC->>RES: 재고 예약·슬롯 점유
    RES-->>ORC: 확보 (resourceExpiresAt)
    ORC->>PAY: POST /internal/payments (amount, resourceExpiresAt)
    PAY->>PAY: 결제 시도 READY, 토스 orderId = "pay-"+무작위 32자
    Note right of PAY: ✔ 금액은 서버 견적, 시도마다 새 orderId (주문 1 : 시도 N)
    PAY-->>ORC: checkout (pgOrderId, clientKey, expiresAt)

    loop 1초마다
        APP->>ORD: GET /order-drafts/{id}
    end
    ORD-->>APP: PAYMENT_PENDING, nextAction=PAY, payment(pgOrderId, amount, orderName, clientKey)
    Note right of APP: clientKey 는 서버 응답으로 받음 (BR-34 이후 주문서 진입 전 /payment-widget-config)

    APP->>T: PaymentWidget(clientKey, customerKey = ANONYMOUS → 서버 발급 예정)
    APP->>T: requestPayment(orderId = pgOrderId, orderName)
    Note right of APP: ✔ 이름·이메일·전화는 PG 에 주지 않음
    T-->>APP: 인증 성공 (paymentKey, orderId, amount)

    APP->>PAY: POST /payment-confirmations (orderId, paymentKey, amount 만)
    PAY->>PAY: 본인 시도인지 · amount 일치 · 예약 만료 · 다른 paymentKey 검사
    Note right of PAY: ✔ 하나라도 어긋나면 토스를 부르지 않고 400/404/409/422
    PAY->>PAY: paymentKey 먼저 저장, READY → CONFIRMING
    PAY->>T: POST /v1/payments/confirm (secretKey, 시도별 고정 Idempotency-Key)
    Note right of PAY: ✔ secretKey·Authorization 은 로그 금지
    T-->>PAY: status=DONE
    PAY->>PAY: paymentKey·orderId·금액·통화 모두 같으면 APPROVED (아니면 MISMATCH_HOLD)
    PAY-->>ORC: Kafka PaymentApproved (order.payment.v1)
    ORC->>ORD: POST /internal/orders/{id}/confirmation
    ORD->>ORD: 승인 금액 == 동결 totalAmount 확인, 주문 확정, OrderConfirmed

    APP->>ORD: GET /order-drafts/{id} (폴링)
    ORD-->>APP: CONFIRMED, nextAction=DONE
```

## 3. 승인 결과 불명 (타임아웃·5xx·연결 오류)

### 3-1. Eum

```mermaid
sequenceDiagram
    autonumber
    participant FE as Eum 웹
    participant PAY as Eum paymentserver
    participant POL as TossApprovalStatusPoller
    participant T as 토스

    FE->>PAY: POST confirm
    PAY->>T: POST /v1/payments/confirm
    alt 타임아웃
        T--xPAY: 응답 없음
        PAY->>PAY: APPROVAL_REQUESTED 유지
        PAY-->>FE: 오류 ("잠시 후 상태 확인")
        loop 2분마다 (@Scheduled fixedDelay 120s)
            POL->>T: orderId 로 결제 조회
            T-->>POL: 상태
            POL->>POL: syncOne → approve / fail
        end
        Note right of POL: ⚠ syncOne 의 @Transactional 은 자기 호출이라 적용 안 됨
    else 5xx 등 HTTP 오류
        T-->>PAY: 500
        PAY->>PAY: fail() → FAILED, PaymentFailed 발행
        Note right of PAY: ⚠ 토스가 실제로 승인했을 수 있는데 실패로 확정 (돈은 빠졌는데 주문 실패)
    else 그 밖의 예외
        PAY-->>FE: 오류
        Note right of PAY: ⚠ 상태·시도 기록 없음
    end
    Note over PAY,T: ⚠ 웹훅 컨트롤러는 빈 클래스, 대사(reconciliation) 없음
```

### 3-2. pmart

```mermaid
sequenceDiagram
    autonumber
    participant APP as 고객앱
    participant PAY as payment-service
    participant JOB as 재조회 작업
    participant T as 토스
    participant OPS as 운영(사건)

    APP->>PAY: POST /payment-confirmations
    Note right of PAY: paymentKey 는 호출 전에 이미 저장됨
    PAY->>T: POST /v1/payments/confirm (Idempotency-Key = 시도별 고정)
    T--xPAY: 타임아웃 / 5xx / 연결 오류 / 409 IDEMPOTENT_REQUEST_PROCESSING
    PAY->>PAY: CONFIRM_UNKNOWN (state UNKNOWN), PaymentResultUnknown 발행
    PAY-->>APP: 200 (UNKNOWN) — 앱은 같은 orderId 로 조회만
    Note right of PAY: ✔ 시간 경과만으로 실패 처리하지 않음

    JOB->>T: GET /v1/payments/{paymentKey} (직후 1회 → 1분 × 5)
    alt DONE 이고 값 일치
        JOB->>PAY: APPROVED, PaymentApproved
    else 실패 확정
        JOB->>PAY: FAILED, PaymentFailed
    else IN_PROGRESS 이고 예약 유효
        JOB->>T: 같은 Idempotency-Key 로 승인 재요청
    else 그래도 모름
        JOB->>OPS: MANUAL_REVIEW 상신 (이후 15분 간격 조회)
    end

    T-)PAY: 웹훅 /webhooks/toss
    PAY->>PAY: 서명 검증 → 본문 해시 중복 제거
    PAY->>JOB: 해당 결제 재조회를 앞당김
    Note right of PAY: ✔ 웹훅 내용만으로 승인·취소를 확정하지 않음
```

## 4. 취소·환불

### 4-1. Eum

```mermaid
sequenceDiagram
    autonumber
    participant SRC as 고객 / OrderCancelled 사건 / 관리자
    participant PAY as Eum paymentserver
    participant T as 토스

    SRC->>PAY: cancel (cancelAmount)
    Note right of PAY: ⚠ 취소 한도(승인액 − 누적 취소) 검사 없음
    PAY->>T: POST /v1/payments/{paymentKey}/cancel (Idempotency-Key = 승인 때 키)
    Note right of PAY: ⚠ 승인 멱등키를 취소에 재사용 (부분 취소 두 번째부터 충돌 위험)
    alt 성공
        T-->>PAY: 취소 응답
        PAY->>PAY: CANCELED, PaymentCancelled 발행
    else 타임아웃
        T--xPAY: 응답 없음
        PAY->>PAY: cancelFailed("TIMEOUT")
        Note right of PAY: ⚠ 실제로는 취소됐을 수 있는데 실패로 기록, 재조회 없음
    end
```

### 4-2. pmart

```mermaid
sequenceDiagram
    autonumber
    participant CLM as claim / orchestration (S3)
    participant PAY as payment-service
    participant T as 토스

    CLM->>PAY: 취소 요청 (금액, Idempotency-Key = 요청 ID)
    PAY->>PAY: 한도 = 승인액 − 성공 누적 − 진행 중·불명 점유
    alt 한도 초과 / 부분 취소 불가
        PAY-->>CLM: 422 (토스 호출 안 함)
    else 한도 안
        PAY->>T: POST /v1/payments/{paymentKey}/cancel (Idempotency-Key = 요청 ID)
        alt 성공
            T-->>PAY: cancels[] (transactionKey)
            PAY->>PAY: transactionKey 저장, RefundResult 발행
        else 결과 불명
            T--xPAY: 타임아웃 / 5xx
            PAY->>T: GET /v1/payments/{paymentKey}
            alt cancels[] 에 있음
                PAY->>PAY: 성공으로 확정
            else 없음
                PAY->>T: 같은 Idempotency-Key·같은 금액으로 재요청
                Note right of PAY: ✔ 새 키·새 금액 금지 (이중 환불 방지)
            end
        end
    end
```

## 5. 요약

| 항목 | Eum | pmart |
|---|---|---|
| 결제 금액 | 클라이언트 값으로 덮어씀 | 서버 동결 견적, 다르면 토스 미호출 |
| 토스 orderId | `order-`+주문 ID, 재시도에도 재사용 | 시도마다 `pay-`+무작위 32자 |
| paymentKey 저장 | 승인 응답 뒤 | 승인 호출 전 |
| 승인 성공 판정 | 응답만 받으면 | `DONE` + 4개 값 일치 |
| 5xx | 실패 확정 | 결과 불명 → 원거래 조회 |
| 타임아웃 | 2분 폴러(트랜잭션 미적용) | 1회 → 1분×5 → MANUAL_REVIEW |
| 취소 멱등키 | 승인 키 재사용 | 취소 요청마다 |
| 취소 한도 | 없음 | 진행 중·불명 점유까지 차감 |
| 웹훅·대사 | 없음 | 서명 검증 + 재조회 촉진 |
| 키 로그 | secretKey·Authorization INFO 출력 | 로그 금지, `live_` 기동 거부 |
| clientKey | 프런트 빌드 환경변수 | 서버 응답 |
| customerKey | 회원 순번 ID | `ANONYMOUS` → 서버 발급 예정(BR-34) |
| PG 에 개인정보 | 이름·이메일 | 주지 않음 |
| 결과 전달 | SSE push | 1초 폴링 (가져올 만한 점) |
