# 작업 계획: 사가 하나를 traceId·correlationId 로 끝까지 따라가기

- 상태: **계획(미착수)** — 2026-11-04 작성. 구현 전 사용자 확인.
- 현재 상태 그림: [`diagrams/trace-correlation-flow.png`](diagrams/trace-correlation-flow.png) (영문 `.en.png`)
- **목표 상태 그림(작업 A·B·C 후)**: [`diagrams/trace-correlation-target.svg`](diagrams/trace-correlation-target.svg) (png·영문 `.en.svg` 함께) · 결제 패턴: [`payment-saga-patterns.md`](payment-saga-patterns.md) 7장
- 관련: ADR-0009(봉투·Outbox), ADR-0030(관측), ADR-0035(Stream 소비), `docs/architecture/platform-capabilities-code-map.md`(개선 과제)

## 1. 목표와 지금 문제

**목표**: Zipkin 에서 trace 하나, 또는 Kibana 에서 `correlationId` 하나로 결제 사가(S1)·취소 사가(S3)를 처음부터 끝까지 본다.

| 끊기는 곳 | 원인(코드) | 결과 |
|---|---|---|
| ① 사가 단계 호출 | `OrchestrationConfig` 가 `Executors.newVirtualThreadPerTaskExecutor()` 를 그대로 씀 → trace·MDC 가 `advance()` 스레드로 안 넘어감 | 참여자 호출마다 새 trace, `X-Correlation-Id` 없음 → 참여자가 새 correlationId 발급 |
| ② Kafka 발행 | outbox 에 `trace_id` 만 있고 W3C `traceparent` 가 없음 → Debezium 헤더에 traceId·correlationId 만 | 소비 쪽이 부모 span 을 알 수 없음 |
| ③ Kafka 소비 | Stream 바인더 observation 꺼짐 + `EventConsumers` 가 봉투의 traceId·correlationId 를 MDC 로 되살리지 않음 | 소비마다 새 trace, MDC 비어 그 반응이 내는 사건의 correlationId 가 빈 값 |
| ④ correlationId 의미 | 요청마다 `CorrelationIdFilter` 가 새로 발급, 사가 ID 로 잇는 곳은 `OrderConfirmed`(= sagaId) 하나 | 같은 사가의 사건들이 서로 다른 correlationId |

## 2. 작업 (권장 순서)

### 작업 A — 사가 실행기 문맥 전달 (끊김 ①) · 작음
- `order-orchestration-service/.../application/OrchestrationConfig.java`: 실행기를 `ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(), () -> ContextSnapshotFactory.builder().build().captureAll())` 로. 같은 방식이 이미 `cart-service/.../CartView.java` 에 있다(재사용).
- 같은 문제: `claim-service/.../ClaimConfig.java`, `customer-service/.../ProfileImageService.java` 도 함께.
- 단, sweep(`@Scheduled`)·이벤트 `kick()` 으로 시작한 진행에는 넘길 문맥이 없다 → 작업 C 에서 사가가 자기 문맥(sagaId)을 직접 연다.
- 시험: 실행기에서 MDC·현재 span 이 보이는지 단위 시험, `CheckoutSagaIT` 에서 참여자 WireMock 이 받은 `traceparent`·`X-Correlation-Id` 확인.

### 작업 B — Kafka 를 건너는 trace 잇기 (끊김 ②③) · 중간
1. **발행**: 봉투를 만들 때 현재 span 의 W3C `traceparent` 도 담는다.
   - 공통 마이그레이션 `V0_0_4__outbox_traceparent.sql`: `outbox_event` 에 `traceparent varchar(64)` 추가(널 허용, 기존 행 영향 없음).
   - `EventEnvelopeCodec.create`·`OutboxWriter`: `traceparent` 열을 채운다(봉투 계약 `_envelope.v1.json` 은 바꾸지 않는다 — 헤더 전용).
   - Debezium 커넥터 `additional.placement` 에 `traceparent:header:traceparent` 추가 — `deploy/local/connectors/outbox-connector.template.json`(LAN 은 render 가 같은 원본을 쓴다).
2. **소비**: `StreamBindingDefaultsPostProcessor` 에 `spring.cloud.stream.kafka.binder.enable-observation=true`(5.0.4 메타데이터 확인) → 바인더가 `traceparent` 헤더로 부모 span 을 이어 소비 span 을 만든다.
3. **MDC 복원**: `EventConsumers.of` 가 처리기 실행 동안 봉투의 `correlationId` 를 MDC(`correlationId`)와 baggage 에 넣고 끝나면 지운다 → 그 반응이 내는 사건·HTTP 호출이 같은 correlationId 를 잇는다.
- 시험: `StreamConsumerDlqIT` 확장 — 발행 쪽 span 의 traceId 가 소비 처리기 안의 traceId 와 같다, 처리기 안 MDC correlationId = 봉투 값. `OutboxInboxPostgresIT` 에 traceparent 열 저장.
- 배포 주의: 커넥터 설정 변경 → LAN connector-registrar 가 29개 커넥터 설정을 다시 넣어야 한다(재등록·재시작 순서 확인). 마이그레이션은 모든 outbox 서비스(29개)에 적용.

### 작업 C — correlationId = sagaId (끊김 ④) · 작음~중간
- `CheckoutSagaService`·`CancellationSagaService`: 사가를 진행할 때(`advance()`·sweep·`kick()` 진입마다) `MDC correlationId = sagaId` + baggage 로 문맥을 연다 → `ServiceCallHeadersCustomizer` 가 참여자 호출에 `X-Correlation-Id: sagaId` 를 싣고, 참여자의 `CorrelationIdFilter` 가 그 값을 쓴다 → 참여자가 내는 사건(`PaymentApproved` 등)도 sagaId 를 갖는다.
- 사가 사건(`CheckoutSagaStateChanged`·`CompensationExecuted`·`ReconciliationRequired`)은 correlationId = sagaId 로 명시(지금 `OrderConfirmed` 처럼).
- 결제 승인은 앱에서 오는 새 요청이다 → payment-service 가 결제 시도에 저장된 sagaId 로 correlationId 를 정한다(`PaymentStartService` 가 시도에 sagaId 를 이미 받음 — 확인 후 `PaymentEvents` 에서 명시).
- S3: claim 이 낸 `CancellationRequested` 의 correlationId 를 취소 사가 ID 로 바꾸지 않고 **클레임 ID → 취소 사가 ID** 연결을 사가 행에 남긴다(결정 필요: S3 의 correlationId 를 claimId 로 할지 sagaId 로 할지).
- 시험: `CheckoutSagaIT` — 사가 하나의 모든 outbox 행 `correlation_id` = sagaId, 참여자 WireMock 이 받은 `X-Correlation-Id` = sagaId.

## 3. 범위 밖 / 정할 것
- **S3 correlationId 기준**: **claimId 로 결정**(2026-11-04, 사용자 "추천대로") — 취소 요청부터 환불 종결까지 claim 이 처음과 끝이다. 취소 사가 행에 claimId 를 연결한다.
- 앱이 `X-Correlation-Id` 를 보내게 할지(클라이언트 요청 필요). 지금 계획은 서버만으로 사가 단위가 이어지게 한다.
- Logstash 검색 색인 파이프라인(Java 아님)은 trace 대상에서 뺀다.
- 운영(k8s) 수집기·표본 비율은 이 계획 밖.

## 4. 끝났을 때 확인
1. LAN 에서 결제 1건 → Zipkin 에서 게이트웨이 → order → orchestration → 참여자 → (Kafka) → orchestration 소비 → 주문 확정 → (Kafka) → cart·customer 소비가 **trace 하나**(또는 링크된 span)로 보인다.
2. Kibana `correlationId:<sagaId>` 로 그 사가의 모든 서비스 로그가 나온다.
3. 모든 outbox 행(그 사가) `correlation_id` = sagaId, `traceparent` 채워짐.
4. `order-full-flow`·`trace-correlation-flow` 그림의 "끊김"을 갱신.

## 5. 예상 변경 범위
| 작업 | 바뀌는 곳 | 배포 |
|---|---|---|
| A | order-orchestration, claim, customer (실행기) | 3개 서비스 |
| B | common-messaging(코덱·OutboxWriter·EventConsumers·PP), 공통 마이그레이션 V0_0_4, Debezium 커넥터 설정 | outbox 쓰는 서비스 전부 + 커넥터 재등록 |
| C | order-orchestration(사가 문맥·사건), payment(사건 correlationId) | 2개 서비스 |
