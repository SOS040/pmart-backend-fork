# traceId 와 sagaId — 언제 만들어지고 왜 둘 다 쓰나

- 기준: 2026-11-05. 결제 흐름(결제 진입 사가 S1)을 예로 든다.
- 그림 번호는 [`order-full-flow-steps.md`](order-full-flow-steps.md)(그림 `diagrams/order-full-flow.png`)의 화살표 번호다.
- 자세한 추적 동작은 [`trace-gateway-checkout-payment.md`](trace-gateway-checkout-payment.md)에 있다.
- 한 줄 요약: traceId 는 **요청 하나**를 묶는다. 결제 한 건은 **요청 여러 개와 주기 작업·Kafka** 에 걸쳐 일어나므로, 업무 단위로는 sagaId(로그의 correlationId)로 묶는다.

## 1. traceId 는 언제 만들어지나

- 앱이 보낸 HTTP 요청이 api-gateway 에 들어올 때마다 추적 라이브러리(Micrometer Tracing + OpenTelemetry)가 새로 만든다.
  - 요청에 `traceparent` 가 있으면 이어받는다. 지금 고객앱은 보내지 않는다.
  - 우리 코드에는 traceId 를 만드는 곳이 없다(`RequestIdWebFilter.java:14` 주석).
- 그 요청으로 시작된 내부 호출, 사가 실행기 스레드, Kafka 소비는 같은 traceId 를 이어받는다(`traceparent` 전파, 작업 A·B).

결제 흐름에서

| 그림 번호 | 요청 | traceId |
|---|---|---|
| 15 | 결제 진입 `POST /order-drafts/{id}/checkouts` | **A** 생성 |
| 16~42 | order → orchestration → 재고·여력·슬롯·결제 준비 | A 를 이어받음 |
| 46 (반복) | 주문서 조회 `GET /order-drafts/{id}` | 요청마다 새로 생성 |
| 55 | 결제 승인 `POST /payment-confirmations` | **B** 생성 |
| 56~107 | payment → Kafka → 주문 확정 → 장바구니 정리 | B 를 이어받음 |

- 배송 옵션 조회(1), 주문서 만들기(4), 취소 신청(108)처럼 앱의 다른 요청도 각각 새 traceId 를 받는다.
- 서버가 스스로 시작하는 작업(주기 작업 sweep·대사 등)은 그 서비스 안에서 새 trace 가 생긴다.

## 2. sagaId 는 언제 만들어지나

- 결제 진입 요청(15)을 받은 order-service 가 orchestration 에 사가 시작을 요청하면(21), orchestration 이 사가를 저장하면서 만든다(22, `SAGA-…`).
- 사가 행(`checkout_saga`)에 저장된다. 결제 시도 행(`payment_attempt.saga_id`)에도 같은 값이 들어간다(38).
- 앱에는 가지 않는다. 앱이 받는 값은 orderId 다(26).
- 로그에는 **correlationId** 로 찍힌다.
  - 사가 진행 중: `CorrelationScope`
  - 참여자 호출: `X-Correlation-Id`
  - 사건: 봉투의 correlationId
  - 결제 승인 처리: 결제 시도를 찾은 직후부터(2026-11-05)

## 3. 왜 traceId 만으로는 부족한가

| 상황 | traceId 로는 | sagaId 로는 |
|---|---|---|
| 결제 진입(15)과 결제 승인(55) | A·B 로 **나뉜다**. 사이에 고객이 토스 결제창을 쓰는 시간(외부 시스템, 시간 미정, 포기 가능)이 있어 이을 수 없다 | 둘 다 같은 sagaId |
| 사가가 멈췄다가 sweep(10초 주기 작업)이 이어서 진행 | sweep 이 **새 trace** 로 시작 | 같은 sagaId(사가 행에 저장된 값) |
| 결제 결과가 늦어 payment 대사 작업이 처리 | 새 trace | 같은 sagaId(결제 시도 행에 저장된 값) |
| 며칠 뒤 고객 문의로 찾아봄 | Zipkin 은 메모리 저장소(span 상한 5만)라 이미 사라졌을 수 있다 | 로그(3일 보관)·DB 에 남아 있다 |

- 고객 행동을 사이에 둔 긴 trace 를 억지로 만들지 않는다. span 시간이 의미를 잃고, 결제를 포기하면 끝나지 않는 trace 가 남기 때문이다.

## 4. 둘의 역할

| | traceId | sagaId (correlationId) |
|---|---|---|
| 단위 | 기술 — 요청 하나 | 업무 — 결제 한 건(결제 진입 사가 한 번) |
| 만드는 곳 | api-gateway 의 추적 라이브러리(요청마다), 주기 작업은 그 서비스 | order-orchestration(사가 시작 때), DB 에 저장 |
| 답하는 질문 | 이 요청이 어느 서비스를 거쳤고 어디서 느렸나 | 이 결제에 무슨 일이 있었나 |
| 찾는 곳 | Zipkin(pc4 :9411) | Kibana(pc4 :5601) `correlationId:"SAGA-…"` |
| 남는 기간 | 짧음(메모리 저장소) | 로그 3일, DB 는 계속 |

- 로그에는 둘 다 남긴다. 찾는 순서:
  1. Kibana 에서 sagaId 로 결제 한 건의 로그를 모은다.
  2. 그 로그의 traceId 로 Zipkin 에서 요청 하나의 자세한 흐름을 본다.
- 설계 규칙: `architecture-design.md` "correlationId = 사가/사건 ID", `.claude/rules/api-and-events.md`.

## 5. 묶음 범위와 빈틈

- sagaId 는 **결제 진입 사가 한 번**을 묶는다. 주문 하나의 일생 전체가 아니다.

  | 주문에 일어나는 일 | correlationId |
  |---|---|
  | 결제 진입 → 승인 → 주문 확정 | 결제 진입 사가의 sagaId |
  | 결제 실패 뒤 다시 결제 | 새 sagaId(새 결제 진입 요청이라 orderId·사가가 새로 생김) |
  | 주문 취소 → 환불 | claimId(취소 사가는 클레임 기준) |

- sagaId 가 정해지기 전 구간의 로그:
  - 2026-11-05 수정 전(지금 LAN): correlationId 가 없다. traceId 로 찾는다. 요청 필터가 만든 UUID 가 추적 라이브러리 설정 때문에 로그에서 지워졌다.
  - 수정 뒤(코드·시험 완료, LAN 미배포): 그 요청의 값(헤더 값 또는 새 UUID)이 남는다. 사가가 시작되면 sagaId 로 바뀌고 끝나면 요청 값으로 돌아온다(`trace-gateway-checkout-payment.md` 2절).
  - 대상: 게이트웨이(필터가 없어 수정 뒤에도 없음), order-service 의 사가 시작 전, payment 의 결제 시도 조회 전
- 토스 웹훅·payment 대사 작업 경로의 로그는 아직 sagaId 로 묶지 않았다. 사건 발행은 이미 sagaId 다.
