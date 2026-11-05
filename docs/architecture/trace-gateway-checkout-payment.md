# 추적의 시작(api-gateway)과 결제 진입·결제 승인 trace

- 기준: 2026-11-05. 지금 LAN 에서 동작하는 모습을 설명하는 안내서다(결정 문서 아님).
- 관련 문서:
  - 추적·상관 ID 작업 계획과 결과(작업 A·B·C): `trace-correlation-plan.md`
  - 결제 사가 패턴 → 코드: `payment-saga-patterns.md`
  - 사가 흐름: `docs/business/flows/order-checkout-saga.md`
  - Zipkin·로그 운영: `docs/operations/lan-monitoring-and-backup.md`
- 용어:
  - **traceId**: 요청 하나의 흐름. 요청마다 바뀐다.
  - **correlationId**: 업무 하나(사가)의 묶음. 여러 요청·사건에 걸쳐 유지된다. 결제 사가에서는 sagaId 다.

## 1. trace 가 시작되는 곳 — api-gateway

고객앱·백오피스 요청은 모두 api-gateway(pc4 :8080, Spring Cloud Gateway WebFlux)로 들어온다.

| 항목 | 값 | 어디서 정하나 |
|---|---|---|
| 추적 라이브러리 | Micrometer Tracing + OpenTelemetry 브리지, Zipkin 전송 | `gradle/libs.versions.toml` 묶음 `service-observability`(모든 앱 공통) |
| 전파 형식 | W3C `traceparent` | `ObservabilityDefaultsPostProcessor`(common-observability) `management.tracing.propagation.type=W3C` |
| 샘플링 | 1.0(모든 요청) | 같은 곳 `management.tracing.sampling.probability=1.0`. 설정 저장소에 덮어쓰는 값 없음 |
| baggage | 원격 필드 `correlationId`·`X-Source-System`, MDC 연동 켬 | 같은 곳 `management.tracing.baggage.*` |
| span 전송처 | LAN `http://zipkin:9411/api/v2/spans`(pc4), local `http://localhost:9411/...` | 설정 저장소 `application-lan.yml`·`application-local.yml` |
| Reactor 문맥 | `spring.reactor.context-propagation: auto` | `api-gateway.yml` — WebFlux 는 요청이 여러 스레드를 오가므로 traceId 를 MDC·오류 응답에 보이게 하려고 켬(2026-11-03) |

- 위 기본값은 가장 낮은 우선순위로 들어간다. 서비스 설정·환경변수가 있으면 그쪽이 이긴다.
- **시작 규칙**:
  - 요청에 `traceparent` 가 없으면 게이트웨이가 새 trace 를 시작한다. 지금 고객앱은 보내지 않으므로 사실상 모든 trace 가 게이트웨이에서 시작한다.
  - 있으면 W3C 규칙대로 그 trace 를 이어받는다.
- 게이트웨이는 업스트림 호출에 `traceparent` 를 실어 보내고, 서비스는 그 trace 안에서 span 을 만든다.
- **게이트웨이가 하는 일과 하지 않는 일**:
  - `X-Request-Id`(`RequestIdWebFilter`): 클라이언트 값이 있으면 쓰고, 없으면 발급해 응답에도 돌려준다. traceId 와는 별개다.
  - correlationId 는 만들지 않는다. `CorrelationIdFilter` 는 서블릿 필터라 WebFlux 인 게이트웨이에서는 꺼져 있다. 각 서비스가 정한다(2절).
  - 클라이언트가 보낸 `X-Correlation-Id` 를 지우지 않는다. 기본 필터는 `Cookie` 만 지운다. 그래서 외부 요청이 넣은 값이 서비스까지 그대로 간다 → 운영 전 과제(5절).

## 2. 서비스 안에서 correlationId 가 정해지는 방식

| 경우 | correlationId | 코드 |
|---|---|---|
| HTTP 요청을 받을 때 | `X-Correlation-Id` 헤더 값(길이 128 이하, 제어문자 없음). 없으면 새 UUID. 응답 헤더로 돌려준다 | `CorrelationIdFilter`(common-observability) |
| 사가 진행 | sagaId(취소 사가 S3 는 claimId) | `CorrelationScope.open(...)` — `CheckoutSagaService.advance`, `CancellationSagaService` |
| 결제 시도 사건 발행 | 그 결제 시도에 저장된 sagaId(환불 결과는 요청 값 그대로) | `PaymentEvents.publish` |
| 다른 서비스 호출 | MDC 의 correlationId 를 `X-Correlation-Id` 로 | `ServiceCallHeadersCustomizer`(common-security) |
| Kafka 사건 | 봉투의 `correlationId` 필드. 소비하는 동안 MDC 를 그 값으로 | `OutboxWriter`·`EventConsumers`(common-messaging) |

- 별도 스레드(사가 실행기 등)로 넘어갈 때는 `ContextExecutorService.wrap` 으로 trace 와 MDC 를 함께 넘긴다(작업 A).

## 3. 결제 진입 trace

LAN 실측(2026-11-04 20:10, 사가 `SAGA-7feb7eac-…`, trace `b9f8bad6…`, 55 span)

```
고객앱  POST /api/v1/order-drafts/{draftId}/checkouts
  → api-gateway                                  ← trace 시작
  → order-service  CheckoutService.start          (correlationId = 새 UUID)
      주문서 잠금 · 견적/차단 사유/계정 상태 확인
  → order-orchestration  POST /internal/checkout-sagas  → 202
      사가 실행기(작업 A: 같은 trace 로 스레드 넘김)
      CorrelationScope(sagaId) 시작
  → mfc-inventory   재고 예약          ┐
  → mfc-capacity    작업 여력 확보     │ 참여자 호출마다 X-Correlation-Id = sagaId
  → delivery-capacity 슬롯 확보(예약 배송만) │
  → payment-service 결제 시작(토스 결제창 정보) ┘
  → WAITING_RESULT / AWAIT_PAYMENT_RESULT 에서 멈춤
```

- 응답 202 는 "사가 시작"이지 결제 성공이 아니다.
- correlationId 가 두 가지로 나온다:
  - 게이트웨이 → order → orchestration 시작까지는 order-service 가 만든 UUID다.
  - 사가 실행기부터는 sagaId 다.
  - 실측에서 sagaId 로 찍힌 것은 mfc-inventory·mfc-capacity 로그였다.
- 그 뒤 앱이 주문서를 조회하는 요청(`GET /order-drafts/{id}`, 결제창 정보 확인)은 요청마다 별도의 짧은 trace 다.

### 3-1. 응답 시점 — 사가가 끝나길 기다리지 않는다

1. order-service 는 orchestration 이 사가를 저장하고 202 를 주면 바로 앱에 202 를 돌려준다.
2. orchestration 은 응답한 뒤 사가 실행기(`CheckoutSagaService.kick` → `executor.execute(advance)`)에서 재고·여력·슬롯·결제 시작을 비동기로 진행한다.
   - 실행기에서 오류가 나도 주기 작업(sweep)이 사가를 이어서 진행한다.
3. 그래서 결제 진입 trace 의 span 대부분(참여자 호출)은 앱이 응답을 받은 **뒤에** 끝난다. Zipkin 에서 게이트웨이 span 이 사가 단계 span 보다 먼저 닫혀 보이는 것이 정상이다.
4. 앱은 계약대로 `GET /order-drafts/{draftId}` 로 진행을 조회한다.

| 사가 상태 | `status` / `nextAction` |
|---|---|
| 자원 확보 중 | `RESOURCE_PENDING` / `WAIT` |
| 결제창 정보가 준비됨 | `PAYMENT_PENDING` / `PAY` (응답에 `payment` 가 실림 → 앱이 토스 결제창을 연다) |
| 실패해서 보상까지 끝남 | `DRAFT` / `REVIEW`, 견적이 만료됐으면 `EXPIRED` / `RE_QUOTE` |

### 3-2. 응답에 실리는 추적 정보

| 응답 | 추적 관련 내용 |
|---|---|
| 성공 202 본문 | `{status: ACCEPTED, requestId, resultId(orderId), nextAction: QUERY_RESULT, occurredAt}` — `requestId` 는 앱이 보낸 `Idempotency-Key` 값이다. traceId 는 없다 |
| 성공 응답 헤더 | `X-Request-Id`(게이트웨이), `X-Correlation-Id`(order-service 가 이 요청에서 만든 UUID — sagaId 아님). `traceparent`·traceId 헤더는 없다 |
| 오류 4xx·5xx 본문 | `application/problem+json` 에 `traceId` 가 **필수**로 들어간다(계약 `_common.yaml` `ErrorResponse`). 서비스 오류는 `ProblemDetailWriter`, 게이트웨이 거절(401·403 등)은 `ProblemResponseWriter` 가 넣는다 |

- 고객이 오류를 겪으면 그 `traceId` 로 Zipkin·Kibana 에서 바로 그 요청을 찾는다.
- 성공 응답만으로는 클라이언트가 그 요청의 trace 를 알 수 없다. 서버에서 찾을 단서는 `X-Request-Id`·orderId 다. 성공 응답에도 traceId 를 줄지는 정하지 않았다.
- 게이트웨이 CORS 설정:
  - 노출 헤더는 `X-Request-Id`·`Location`·`Retry-After` 뿐이라, 웹 브라우저는 `X-Correlation-Id` 를 읽을 수 없다. 안드로이드 앱은 CORS 제한을 받지 않는다.
  - 요청 헤더 `traceparent` 는 허용한다. 앱이 보내면 그 trace 를 이어받는데, 지금 고객앱은 보내지 않는다.

## 4. 결제 승인 trace

LAN 실측(같은 결제, trace `d3510de7…`, 47 span)

```
고객앱  (토스 결제창에서 인증 성공 → 앱으로 복귀)
고객앱  POST /api/v1/payment-confirmations {orderId, paymentKey, amount}
  → api-gateway                                  ← 새 trace 시작
  → payment-service  CheckoutService.confirm
      결제 시도 조회(본인) · 키/금액/상태/만료 확인 · READY→CONFIRMING
      → 토스 승인 API
      → 결과 반영 · 사건(outbox, correlationId = sagaId, traceparent 저장)
  → Kafka order.payment.v1                         ← 작업 B: traceparent 헤더로 같은 trace
  → order-orchestration · claim-service 소비
      재고·여력 확정(CONFIRM_RESOURCES) → order-service 주문 확정(CONFIRM_ORDER)
  → Kafka order.order.v1                           ← 같은 trace
  → customer-service · cart-service 소비
```

- Kafka 를 두 번 건너도 trace 하나로 이어진다(작업 B). 경로는 다음과 같다.
  1. `OutboxWriter` 가 traceparent 를 outbox 열(`V0_0_4`)에 저장한다.
  2. Debezium 커넥터가 그 값을 Kafka 헤더로 옮긴다.
  3. Stream 바인더 observation 이 그 헤더를 부모로 잇는다.
- correlationId:
  - payment 진입 구간(게이트웨이·confirm 앞부분)은 새 UUID 다. 앱이 `X-Correlation-Id` 를 보내지 않는다.
  - 사건 발행부터는 sagaId 다.
  - 실측에서 sagaId 로 찍힌 것은 mfc-inventory·order·cart 로그였다.

## 5. 두 trace 가 나뉘는 이유와 묶는 방법

- **나뉘는 이유**: 사용자 요청이 두 번이다. 그 사이에 고객이 토스 결제창에서 결제한다(외부 시스템, 시간 미정, 포기할 수 있음). 설계상 맞는 동작이다.
  - 고객 행동을 사이에 둔 긴 trace 는 span 시간이 의미를 잃는다.
  - 결제를 포기하면 끝나지 않는 trace 가 남는다.
- **묶는 방법**: correlationId = sagaId.
  - Kibana 에서 `correlationId:SAGA-…` 하나로 두 trace 의 로그를 함께 찾는다.
  - 서버 안에서 이어지는 구간(사가 실행기 스레드, Kafka 두 구간)은 trace 하나로 이었다(작업 A·B).
- **남은 공백**: 결제 승인 요청이 들어오는 구간(게이트웨이·payment `confirm` 앞부분)에는 sagaId 가 붙지 않는다.
  - 해결 방향: 클라이언트에 요청하지 않고 서버에서 푼다. payment-service 가 결제 시도를 찾은 직후 그 시도의 sagaId 로 `CorrelationScope` 를 연다(미구현).
  - 앱이 sagaId 를 받은 적이 없다. 계약 어디에도 고객용으로 노출되지 않는다.
  - 토스 웹훅·대사 작업처럼 클라이언트가 없는 경로도 같은 규칙으로 묶어야 한다.
  - 클라이언트가 보낸 상관 ID 는 믿을 수 없다(로그 오염).
- **운영 전 과제**: 게이트웨이에서 외부 요청의 `X-Correlation-Id` 를 지운다(미구현). 지금은 아무 값이나 넣어 다른 사가의 로그에 섞을 수 있다.
- 클라이언트 추적(모바일 추적 SDK 의 `traceparent`)은 별개다. 요청 하나를 앱 화면까지 늘려 보는 기능이고, 두 요청을 사가로 묶어 주지 않는다. 운영 준비 때 정한다.

## 6. 결제 한 건 찾는 법

1. sagaId 를 얻는다: pc2 postgres `order_orchestration_service.checkout_saga` 의 최근 행(또는 주문의 orderId 로 찾기).
2. Kibana(pc4 :5601) 데이터 뷰 `pmart-logs-*` 에서 `correlationId:"SAGA-…"` 로 검색한다. 두 trace 의 로그가 함께 나온다.
3. 로그의 `traceId` 로 Zipkin(pc4 :9411)을 연다. 결제 진입·결제 승인 각각의 span 트리가 보인다.

- Zipkin 은 메모리 저장소다(span 상한 5만, 힙 256m). trace 가 오래 남지 않으므로 확인할 결제는 바로 찾아본다.
- 로그는 3일 보관한다.
