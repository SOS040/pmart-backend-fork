# 주문 전체 흐름 — 그림 번호별 설명

- 그림: [`diagrams/order-full-flow.png`](diagrams/order-full-flow.png) · 원본 [`diagrams/order-full-flow.mmd`](diagrams/order-full-flow.mmd). 번호는 그림의 화살표 번호(1~151)다.
- 기준: 2026-11-05 그림(추적 반영). 단계·상태의 자세한 해설은 [`order-full-flow-analysis.md`](order-full-flow-analysis.md), 추적은 [`trace-gateway-checkout-payment.md`](trace-gateway-checkout-payment.md).
- 이 문서는 "번호마다 **누가 누구에게, 어떤 방식으로** 보내는지"만 따라간다.
- traceId 가 어느 번호에서 생기고 왜 sagaId 로도 묶는지: [`trace-id-and-saga-id.md`](trace-id-and-saga-id.md).

## 읽는 법

| 표기 | 뜻 |
|---|---|
| **HTTP 요청 / HTTP 응답** | 동기 호출. 응답이 올 때까지 호출한 쪽이 기다린다 |
| **내부 HTTP** | 서비스 간 `/api/v1/internal/**` 호출(게이트웨이를 거치지 않음, 토큰 없음 — ADR-0026) |
| **자기 처리** | 화살표가 자기에게 돌아오는 것. 서비스 안의 검사·저장(외부 호출 없음) |
| **outbox** | 업무 변경과 같은 트랜잭션에 `outbox_event` 행을 쓴다. 바로 Kafka 로 가지 않는다 |
| **Kafka** | Debezium 이 outbox 를 읽어 Kafka 로 보내고, 구독자가 소비한다(비동기) |
| 🟦 trace A / 🟩 trace B | 결제 진입 trace / 결제 승인 trace. 그 밖의 앱 요청은 요청마다 별도 trace |

- **응답 경로**: 그림은 응답을 서비스 → 앱으로 바로 그렸지만(예: 3, 14, 26), 실제로는 **모두 api-gateway 를 거쳐** 앱에 돌아간다. 앱 → 서비스 요청도 마찬가지다(46·101 처럼 GW 를 생략한 화살표 포함).
- **trace**: 앱의 HTTP 요청 하나마다 게이트웨이가 trace 하나를 시작하고, 그 요청으로 시작된 내부 호출·비동기 진행·Kafka 소비는 같은 trace 로 이어진다.

## Phase 1 — 주문서 만들기 (1~14)

| # | 누가 → 누구 | 방식 | 내용 |
|---|---|---|---|
| 1 | 앱 → gateway | HTTP 요청 | 배송 방식·예약 시간대 조회(`GET /delivery-options`, `GET /slots`) |
| 2 | gateway → order | HTTP 전달 | JWT 를 그대로 붙여 넘긴다 |
| 3 | order → 앱 | HTTP 응답 | 배송 방식·시간대 (1~3 이 요청 하나, trace 하나) |
| 4 | 앱 → gateway | HTTP 요청 | 주문서 만들기 `POST /order-drafts` (품목·배송지·배송 방식·결품 시 처리) |
| 5 | gateway → order | HTTP 전달 | |
| 6 | order → customer | 내부 HTTP | 계정 상태, 배송 가능 여부·담당 MFC |
| 7 | order → product | 내부 HTTP | SKU 정보, 구매 제한 확인 |
| 8 | order → pricing | 내부 HTTP | 견적(이 가격이 결제까지 고정) |
| 9 | order → customer·center-master | 내부 HTTP | 첫 구매 여부, 센터 영업 달력 |
| 10 | order → delivery-capacity | HTTP | 예약 슬롯(사용자 토큰으로) |
| 11 | order → product-content | 내부 HTTP | 상품명·이미지 사본 |
| 12 | 위 서비스들 → order | HTTP 응답 | 6~11 의 응답(묶어서 그림) |
| 13 | order | 자기 처리 | 주문서 저장 `DRAFT`, 견적 버전, 가격 동결 10분 |
| 14 | order → 앱 | HTTP 응답 | `201` 주문서(막는 사유·다음 행동). 4~14 가 요청 하나, trace 하나 |

- 14 에서 `nextAction=WAIT` 이면 앱이 주문서를 다시 조회한다(2초 × 10회, 요청마다 별도 trace).

## Phase 2 — 결제 진입 (15~26) 🟦 trace A 시작

| # | 누가 → 누구 | 방식 | 내용 |
|---|---|---|---|
| 15 | 앱 → gateway | HTTP 요청 | `POST /order-drafts/{draftId}/checkouts` (Idempotency-Key, 고객이 본 견적 버전) — **게이트웨이가 trace A 시작** |
| 16 | gateway → order | HTTP 전달 | |
| 17 | order | 자기 처리 | 주문서 행 잠금, 본인·버전 확인. 이미 확정됐거나 사가가 진행 중이면 기존 orderId 를 돌려주고 끝 |
| 18 | order | 자기 처리 | 견적 만료·버전 불일치 → `422 RE_QUOTE_REQUIRED`, 막는 사유 → `422 CHECKOUT_BLOCKED` |
| 19 | order → customer | 내부 HTTP | 계정 상태 재확인(정지·탈퇴 진행 시 `422`) |
| 20 | order | 자기 처리 | orderId 를 결정적으로 만든다(같은 요청 재시도 = 같은 ID) |
| 21 | order → orchestration | 내부 HTTP 요청 | 사가 시작 `POST /internal/checkout-sagas` (품목·금액·배송·수요) |
| 22 | orchestration | 자기 처리 | 견적 만료 재확인, 주문서당 열린 사가 하나(`409`), 사가 행 저장 `RUNNING` |
| 23 | orchestration → outbox | outbox | `CheckoutSagaStateChanged` |
| 24 | orchestration → order | 내부 HTTP 응답 | `202 { sagaId }` — **order-service 에게** 주는 응답. 사가 진행은 아직 안 함 |
| 25 | order | 자기 처리 | 주문서에 orderId·sagaId 기록 |
| 26 | order → 앱 | HTTP 응답 | `202 { ACCEPTED, resultId: orderId, nextAction: QUERY_RESULT }` — **앱이 받는 중간 응답**. sagaId 는 앱에 가지 않는다 |

- 26 으로 앱과의 HTTP 는 끝난다. 하지만 trace A 는 27~42 에서 계속된다.

## S1 a~f — 자원 확보 → 결제 준비 (27~42) 🟦 trace A 계속, 앱은 응답을 이미 받음

orchestration 이 응답(24) 뒤 별도 스레드(가상 스레드 실행기)에서 단계마다 진행한다. 실행기가 trace A 와 MDC 를 넘기고, 이 구간의 correlationId 는 sagaId 다.

| # | 누가 → 누구 | 방식 | 내용 |
|---|---|---|---|
| 27 | orchestration | 자기 처리 | a. 견적 확인 → 성공 |
| 28 | orchestration → mfc-inventory | 내부 HTTP 요청 | b. 재고 예약(10분) |
| 29 | mfc-inventory | 자기 처리 | SKU 별 합산, 잔고 잠금, 전량이 아니면 전부 거절, 예약 `HELD` |
| 30 | mfc-inventory → orchestration | 내부 HTTP 응답 | `APPLIED { reservationId }` |
| 31 | orchestration → mfc-capacity | 내부 HTTP 요청 | c. 작업 여력 확보(10분) |
| 32 | mfc-capacity → orchestration | 내부 HTTP 응답 | `HELD { holdId }` |
| 33 | orchestration → delivery-capacity | 내부 HTTP 요청 | d. 슬롯 확보 — 예약 배송일 때만 |
| 34 | delivery-capacity → orchestration | 내부 HTTP 응답 | `HELD { holdId }` |
| 35 | orchestration | 자기 처리 | 확보한 자원을 보상 기록에 남김, 가장 이른 만료 시각 |
| 36 | orchestration | 자기 처리 | (참여자가 거절하면) 실패 → 보상(93~100) |
| 37 | orchestration | 자기 처리 | (결과 불명: 시간 초과·5xx) 같은 키로 다시 보냄 — 실패로 보지 않음 |
| 38 | orchestration → payment | 내부 HTTP 요청 | f. 결제 시작(금액·주문명·자원 만료 시각) |
| 39 | payment | 자기 처리 | 결제 시도 `READY` |
| 40 | payment → orchestration | 내부 HTTP 응답 | `201` 결제 시도 ID, 결제창 정보(pgOrderId·clientKey) |
| 41 | orchestration | 자기 처리 | 사가 `WAITING_RESULT` / `AWAIT_PAYMENT_RESULT` — 여기서 결제 결과를 기다림 |
| 42 | orchestration → outbox | outbox | `CheckoutSagaStateChanged` |

- e. 쿠폰은 미구현이다.

## Phase 3 — 토스 결제창 (43~54) 요청마다 별도 trace

| # | 누가 → 누구 | 방식 | 내용 |
|---|---|---|---|
| 43 | 앱 → gateway | HTTP 요청 | 결제 위젯 설정 `GET /payment-widget-config` |
| 44 | gateway → payment | HTTP 전달 | |
| 45 | payment → 앱 | HTTP 응답 | clientKey, customerKey |
| 46 | 앱 → order | HTTP 요청 | 주문서 조회 `GET /order-drafts/{id}` — 1초 × 30회 반복(게이트웨이 경유, 그림은 생략) |
| 47 | order → 앱 | HTTP 응답 | `RESOURCE_PENDING/WAIT` … → 41 이 끝나면 `PAYMENT_PENDING/PAY` + 결제창 정보 |
| 48 | 앱 → 토스 | 토스 SDK | 결제창 열기(pgOrderId·금액·주문명) — 서버를 거치지 않음 |
| 49 | 토스 → 앱 | 토스 SDK | (인증 실패·사용자 취소) 실패 코드 |
| 50 | 앱 → gateway | HTTP 요청 | 실패 보고 `POST /payment-authorization-failures` |
| 51 | gateway → payment | HTTP 전달 | |
| 52 | payment | 자기 처리 | `READY → AUTH_FAILED / CANCELED_BY_USER` |
| 53 | payment → outbox | outbox | `PaymentFailed` → 사가가 받아 보상(92) |
| 54 | 토스 → 앱 | 토스 SDK | (인증 성공) paymentKey·orderId·금액 → Phase 4 로 |

- 48~54 사이는 고객이 토스 결제창을 쓰는 시간이다. 서버 trace 가 없다. 그래서 trace A 와 trace B 가 이어지지 않는다.

## Phase 4 — 결제 승인 (55~71) 🟩 trace B 시작

| # | 누가 → 누구 | 방식 | 내용 |
|---|---|---|---|
| 55 | 앱 → gateway | HTTP 요청 | `POST /payment-confirmations { orderId: pgOrderId, paymentKey, amount }` — **게이트웨이가 trace B 시작** |
| 56 | gateway → payment | HTTP 전달 | |
| 57 | payment | 자기 처리 | 본인·paymentKey(`409`)·금액(`400`) 확인 — 토스 호출 전 |
| 58 | payment | 자기 처리 | 자원 만료 지남 → `EXPIRED` + `422 RESERVATION_EXPIRED` |
| 59 | payment | 자기 처리 | `READY → CONFIRMING`, paymentKey 저장(짧은 트랜잭션) |
| 60 | payment → 토스 | 외부 HTTP 요청 | 승인 API(시도별 고정 Idempotency-Key) — 트랜잭션 밖 |
| 61 | 토스 → payment | 외부 HTTP 응답 | `200 DONE` |
| 62 | payment | 자기 처리 | `CONFIRMING → APPROVED` |
| 63 | payment → outbox | outbox | `PaymentApproved` (correlationId = sagaId) |
| 64~66 | 토스 → payment, payment | 응답·자기 처리·outbox | (거절) `CONFIRM_FAILED` + `PaymentFailed` |
| 67~68 | payment | 자기 처리·outbox | (시간 초과·5xx) `CONFIRM_UNKNOWN` + `PaymentResultUnknown` |
| 69~70 | payment | 자기 처리·outbox | (응답 필드 불일치) `MISMATCH_HOLD` + `PaymentMismatch` |
| 71 | payment → 앱 | HTTP 응답 | 결제 상태 — **앱이 받는 두 번째 응답**. 앱은 이것을 "주문 완료"로 보지 않는다 |

- 71 로 앱과의 HTTP 는 끝난다. trace B 는 72~92 에서 계속된다.
- 57~71 의 payment 로그는 결제 시도를 찾은 직후부터 correlationId = sagaId 다(2026-11-05, `CheckoutService.correlate`, LAN 미배포). 그 전(55~56, 게이트웨이)은 correlationId 가 없어 traceId B 로 찾는다.
- 결과가 불명이거나 READY 가 만료되면 payment 대사 작업(15초)이 정리한다. 토스 웹훅은 재조회를 앞당길 뿐 상태를 직접 바꾸지 않는다.

## S1 g~j — 결제 결과 → 주문 확정 (72~102) 🟩 trace B 계속

| # | 누가 → 누구 | 방식 | 내용 |
|---|---|---|---|
| 72 | outbox → Debezium | DB 변경 감지 | 63 의 outbox 행을 WAL 로 읽음 |
| 73 | Debezium → Kafka | Kafka 발행 | 토픽 `order.payment.v1`, 헤더 eventType·traceId·correlationId·**traceparent**(→ 소비자가 trace B 를 이어감) |
| 74 | Kafka → orchestration | Kafka 소비 | 중복 제거(InboxGuard) 후 사가를 깨움 — 이벤트는 신호일 뿐 |
| 75 | orchestration → payment | 내부 HTTP 요청 | g. 결제 상태를 직접 조회 |
| 76 | payment → orchestration | 내부 HTTP 응답 | 상태·승인 금액·PG 거래 ID |
| 77 | orchestration | 자기 처리 | (승인) 결제 승인을 보상 기록에 추가 |
| 78 | orchestration | 자기 처리 | h1. 승인 금액 = 사가 금액, KRW 확인 |
| 79~80 | orchestration | 자기 처리·outbox | (금액 불일치) `RECONCILIATION_REQUIRED` — 확정·해제·취소 모두 멈춤 |
| 81 | orchestration → mfc-inventory | 내부 HTTP | i. 재고 예약 확정 |
| 82 | orchestration → mfc-capacity | 내부 HTTP | 작업 여력 확정 |
| 83 | orchestration → delivery-capacity | 내부 HTTP | 슬롯 확정(예약 배송만) |
| 84 | orchestration → order | 내부 HTTP 요청 | h2. 주문 확정(승인·자원 근거) |
| 85 | order | 자기 처리 | 한 트랜잭션: 주문 `CONFIRMED`, 주문행·배송지 사본, 주문서 `CONFIRMED` |
| 86 | order → outbox | outbox | `OrderConfirmed` (correlationId = sagaId) |
| 87 | order → orchestration | 내부 HTTP 응답 | `APPLIED` |
| 88~89 | orchestration | 자기 처리·outbox | 사가 `COMPLETED` |
| 90 | orchestration | 자기 처리 | (결제 불명) `UNKNOWN_TRACKING` — 해제하지 않고 TTL 만료에 맡김 |
| 91 | orchestration | 자기 처리 | (결제 대기 중) 자원 만료까지 기다림, 지나면 실패 |
| 92 | orchestration | 자기 처리 | (결제 실패) 보상으로 |
| 93 | orchestration → payment | 내부 HTTP | j. (승인 뒤 실패) 보상 취소 |
| 94 | payment → 토스 | 외부 HTTP | 전액 취소 |
| 95 | payment → outbox | outbox | `CompensationCancelResult` |
| 96 | orchestration → payment | 내부 HTTP | 취소 확인(안 됐으면 다음 sweep 에서) |
| 97~99 | orchestration → 재고·여력·슬롯 | 내부 HTTP | 이번 시도에서 확보한 것만 해제 |
| 100 | orchestration | 자기 처리 | 해제 확인 → `COMPENSATED` |
| 101 | 앱 → order | HTTP 요청 | 주문서 조회 반복(1초 × 30회, 게이트웨이 경유) — 요청마다 별도 trace |
| 102 | order → 앱 | HTTP 응답 | `CONFIRMED/DONE` 또는 `DRAFT/REVIEW` + 실패 사유 |

- 74 가 늦거나 DLT 로 빠져도, orchestration 의 백업 sweep(10초)이 결제를 직접 조회해 진행한다.

## Phase 6 — 확정 후 전파 (103~107) 🟩 trace B 계속

| # | 누가 → 누구 | 방식 | 내용 |
|---|---|---|---|
| 103 | Debezium → Kafka | Kafka 발행 | 86 의 `OrderConfirmed` → `order.order.v1` |
| 104 | Kafka → cart | Kafka 소비 | |
| 105 | cart | 자기 처리 | 주문한 품목을 장바구니에서 삭제·수량 감소 |
| 106 | cart → outbox | outbox | `CartItemChangeApplied` |
| 107 | Kafka → customer | Kafka 소비 | 첫 구매면 사용 기록 |

- 이행 사가 S2(피킹·패킹), 배차, 알림은 미구현이다.

## Phase 7 — 주문 취소 S3 (108~143) 새 trace (취소 요청)

| # | 누가 → 누구 | 방식 | 내용 |
|---|---|---|---|
| 108 | 앱 → gateway | HTTP 요청 | 취소 신청 `POST /claims { CANCEL, 전 품목 }` — 새 trace |
| 109 | gateway → claim | HTTP 전달 | |
| 110~111 | claim ↔ order | 내부 HTTP | 주문 상태·품목 조회 |
| 112 | claim | 자기 처리 | 본인·확정 상태·전 품목·진행 중 클레임 없음 확인, `STOP_PENDING` |
| 113 | claim → outbox | outbox | `ClaimOpened` + `CancellationRequested` |
| 114 | claim → 앱 | HTTP 응답 | `201` 클레임 — 앱과의 HTTP 끝, 이후는 비동기 |
| 115 | Debezium → Kafka | Kafka 발행 | `CancellationRequested` → `order.claim.v1` |
| 116 | Kafka → orchestration | Kafka 소비 | |
| 117 | orchestration | 자기 처리 | 취소 사가 시작 (correlationId = claimId) |
| 118 | orchestration → mfc-fulfillment | 내부 HTTP 요청 | 작업 중지 요청 |
| 119 | mfc-fulfillment | 자기 처리 | 아직 작업 지시 없음 → 전부 중지됨 |
| 120 | mfc-fulfillment → outbox | outbox | `WorkStopResult` |
| 121 | mfc-fulfillment → orchestration | 내부 HTTP 응답 | `RESOLVED` |
| 122~124 | orchestration → 슬롯·여력·재고 | 내부 HTTP | S1 의 보상 기록으로 해제(슬롯 → 여력 → 재고) |
| 125 | orchestration → order | 내부 HTTP | 수량 변경(출고 전 취소) |
| 126 | order | 자기 처리 | 미이행 0 → 주문 `CANCELLED` |
| 127 | order → outbox | outbox | `OrderLineQuantityChanged` + `OrderCancelled` |
| 128 | orchestration | 자기 처리 | 취소 사가 `COMPLETED` |
| 129 | orchestration | 자기 처리 | (중지 판정 대기) `WAITING_RESULT` — 다시 조회 |
| 130 | orchestration | 자기 처리 | (이미 인계됨·거절) `ESCALATED` — 운영자 처리 |
| 131~132 | Debezium → Kafka → claim | Kafka | `OrderCancelled` 소비 |
| 133 | claim | 자기 처리 | 클레임 진행, 환불 의도(승인 금액 전액) |
| 134 | claim → outbox | outbox | `RefundIntentCreated` |
| 135 | claim → payment | 내부 HTTP | 환불 요청(커밋 뒤, 멱등 키) |
| 136~137 | payment ↔ 토스 | 외부 HTTP | 결제 취소 → `CANCELED` |
| 138 | payment → outbox | outbox | `RefundResult` |
| 139~140 | Debezium → Kafka → claim | Kafka | `RefundResult` 소비 |
| 141 | claim | 자기 처리 | 환불 완료, 클레임 종료 |
| 142 | claim → outbox | outbox | `ClaimClosed` |
| 143 | Kafka → customer | Kafka 소비 | 전체 취소면 첫 구매 기록 삭제 |

## Phase 8 — 소비 실패와 재처리 (144~151)

예: claim-service 가 `RefundResult` 를 처리하다 실패한 경우(모든 구독자 공통).

| # | 누가 → 누구 | 방식 | 내용 |
|---|---|---|---|
| 144 | Kafka → claim | Kafka 소비 | `RefundResult` |
| 145 | claim | 자기 처리(실패) | 처리 중 예외 → inbox 기록도 롤백 |
| 146 | Kafka → claim | 재전달 | 바인더 재시도 1초 → 2초 → 4초(3회). `IllegalArgumentException` 은 바로 DLQ |
| 147 | claim → dead_letter | DB | `dead_letter` 행 `OPEN`(토픽·파티션·오프셋·예외) |
| 148 | claim → DLT | Kafka | `order.payment.v1.DLT` 로 원본 그대로 |
| 149 | claim → Kafka | 오프셋 커밋 | 다음 레코드로 — 파티션이 막히지 않는다. 경보 `DeadLetterOpen` |
| 150 | 운영자 → Kafka | 재처리 API | 원인을 고친 뒤 `…/dead-letters/{id}/replays` → 원 토픽에 같은 key·본문·헤더로 다시 넣음 |
| 151 | Kafka → claim | Kafka 소비 | 이번엔 성공. 이미 처리한 다른 구독자는 같은 eventId 라 건너뜀 |

## 요약 — 앱이 받는 HTTP 응답과 trace

| 앱 요청 (번호) | 앱이 받는 응답 (번호) | 응답 뒤에도 계속되는 서버 작업 | trace |
|---|---|---|---|
| 배송 옵션 (1) | 3 | 없음 | 별도 |
| 주문서 만들기 (4) | 14 | 없음 | 별도 |
| **결제 진입 (15)** | **26 — 202 중간 응답** | 27~42 자원 확보·결제 준비 | **A** |
| 위젯 설정 (43), 주문서 조회 (46) | 45, 47 | 없음 | 요청마다 별도 |
| 인증 실패 보고 (50) | (그림 생략) | 53 → 사가 보상 | 별도 |
| **결제 승인 (55)** | **71 — 결제 상태** | 72~107 주문 확정·장바구니 정리 | **B** |
| 주문서 조회 (101) | 102 | 없음 | 요청마다 별도 |
| 취소 신청 (108) | 114 | 115~143 취소 사가·환불 | 별도(correlationId = claimId) |

- 결제 흐름은 앱 기준 **두 번의 HTTP 요청·응답**(15→26, 55→71)이고, 사이에 상태 조회(46~47)와 토스 결제창(48~54)이 있다.
- trace A·B 는 sagaId 로 묶는다(Kibana `correlationId:SAGA-…`).
