# Resilience — 장애를 다루는 방식과 코드 위치

- 기준: 2026-11-05, main `f33c5ff` 이후 작업 트리. 경로는 저장소 루트 기준이다.
- 요약판은 [`platform-capabilities-code-map.md`](platform-capabilities-code-map.md) §4. 이 문서는 그것을 펼쳐 **무엇을 어디서 어떻게** 하는지 코드 위치와 함께 적는다.
- 관련 결정:
  - ADR-0003 주문 사가 오케스트레이션
  - ADR-0005 결제 정본
  - ADR-0009 outbox·봉투·멱등
  - ADR-0026 서비스 간 인증 없음
  - ADR-0030·0035 소비 실패·DLT
- 규칙 원문: `.claude/rules/api-and-events.md`("시간 초과를 상대 서비스의 업무 실패로 단정하지 않는다").

## 0. 한눈에

pmart 는 **회로 차단기 라이브러리(Resilience4j) 없이**, 아래 장치들을 조합해 장애를 다룬다.

| # | 장치 | 막는 문제 | 핵심 코드 |
|---|---|---|---|
| 1 | 시간 제한 + 호출 결과 해석(`Downstream`) | 무한 대기, "실패"와 "모름"의 혼동 | `libraries/common-web/.../Downstream.java` |
| 2 | 멱등 키(`Idempotency-Key`, `request_record`) | 재시도·중복 요청이 두 번 반영 | `libraries/common-messaging/.../RequestIdempotency.java` |
| 3 | 낙관적 잠금(`X-Expected-Version`, `@Version`) | 동시 수정 덮어쓰기 | `RequestHeadersFilter.java`, `OptimisticLockAdvice.java` |
| 4 | 사가 + 보상 + 주기 재개(sweep) | 여러 서비스에 걸친 처리의 부분 실패 | `services/order-orchestration-service/.../CheckoutSagaService.java` |
| 5 | 원결과 조회·대사(결제) | 외부 PG 응답 불명 | `services/payment-service/.../ReconciliationService.java` |
| 6 | Outbox → Debezium(전달 보장) | DB 는 바뀌었는데 이벤트 유실 | `OutboxWriter.java` + Debezium 커넥터 |
| 7 | Inbox(중복 소비 제거) | 같은 이벤트 두 번 처리 | `InboxGuard.java` |
| 8 | 소비 재시도 → DLT·`dead_letter` → 재처리 | 처리 실패한 메시지가 파티션을 막음·유실 | `StreamBindingDefaultsPostProcessor.java`, `PmartDlqDestinationResolver.java`, `DeadLetters.java` |
| 9 | 점유 TTL + 만료 청소 | 실패한 사가가 재고·정원을 계속 잡음 | 각 `HoldExpirySweeper` |
| 10 | 게이트웨이 처리율 제한(fail-open) | 과도한 요청 | `RateLimitGlobalFilter.java` |
| 11 | 컨테이너 재시작·메모리 상한·fail-fast | 프로세스 죽음·메모리 고갈 | `deploy/lan/render.sh` |

**Resilience4j 는 쓰지 않는다**:
- 버전만 선언돼 있다: `gradle/libs.versions.toml:13`(`resilience4j = "2.4.0"`), `:90`.
- `@CircuitBreaker`·`@Retry`·`@Bulkhead`·`RetryTemplate` 은 코드에 없다(검색 확인).
- 재시도는 "같은 키 재전송"을 사가·sweep 이 하고, 일반 동기 호출은 자동 재시도하지 않는다(6장).

## 1. 동기 호출 — 시간 제한과 결과 해석

### 1-1. 시간 제한

| 대상 | 연결 / 응답 | 위치 |
|---|---|---|
| 모든 앱의 RestClient(서비스 간) | 1s / 5s | `libraries/common-web/src/main/java/com/pmart/common/web/HttpClientDefaultsPostProcessor.java:24-25`. 우선순위 최저 기본값이라 서비스 설정이 이긴다 |
| api-gateway → 서비스 | 1000ms / 10s | 설정 저장소 `api-gateway.yml:22-23`(`spring.cloud.gateway.server.webflux.httpclient`) |
| payment → 토스 | 3s / 15s | `services/payment-service/.../infrastructure/toss/TossProperties.java:30-31`, 설정 `payment-service.yml:17-18` |
| delivery-serviceability → 외부 주소 검색 | 1s / 3s | 설정 `delivery-serviceability-service.yml:9-10` |
| config-server → GitHub | 10s | `platform-services/config-server/src/main/resources/application.yml:50` |

- 등록: common-web 의 `META-INF/spring.factories` 가 `HttpClientDefaultsPostProcessor` 를 등록한다.
  - 이 파일만 키 이름(`org.springframework.boot.EnvironmentPostProcessor`)과 형식(`=\n` 이 한 줄에 문자로 들어 있음)이 다른 두 라이브러리와 다르다.
  - Spring 이 값을 trim 하므로 로딩은 될 것으로 보이지만, **등록 자체를 확인하는 시험은 없다**. 시험(`HttpClientDefaultsPostProcessorTest`)은 클래스를 직접 호출할 뿐이다. → 점검 과제(9장).

### 1-2. 결과 해석 — `Downstream`

`libraries/common-web/src/main/java/com/pmart/common/web/Downstream.java`

| 상황 | 결과 | 호출자가 할 일 |
|---|---|---|
| 연결 거부·연결 시간 초과·호스트 없음·경로 없음 (`notDelivered`, `:59`) | `DEPENDENCY_UNAVAILABLE`(503, 재시도 가능) | 요청이 가지 않았다. 다시 보내도 된다 |
| 상대가 503 | `DEPENDENCY_UNAVAILABLE` | 같음 |
| 응답 시간 초과·응답 중 끊김·그 밖의 5xx (`unknown`, `:78`) | `UNKNOWN_RESULT`(503, 재시도 불가 표시) | 요청이 **갔을 수 있다**. 실패로 보고 보상하면 안 된다. 같은 키로 다시 보내거나 원결과를 조회한다 |
| 4xx | `HttpClientErrorException` 그대로 | 상대의 명시적 판정. Problem 코드를 해석한다 |

- 진입점: `Downstream.call(target, supplier)`(`:32`), `run`.
- 오류 코드 정의: `libraries/common-web/src/main/java/com/pmart/common/web/ErrorCode.java:15-21`
  - `VERSION_CONFLICT` 409
  - `IDEMPOTENCY_CONFLICT` 409
  - `DEPENDENCY_UNAVAILABLE` 503 재시도 가능
  - `UNKNOWN_RESULT` 503 재시도 불가 — `:42` 설명: "재시도가 아니라 원결과 조회"
- **쓰는 곳**(14개 파일):

| 서비스 | 파일 | 호출 대상 |
|---|---|---|
| order | `order/infrastructure/Upstreams.java`, `OrchestrationClient.java` | customer·product·pricing 등, 사가 시작·조회 |
| order-orchestration | `order/orchestration/infrastructure/Participants.java:334,360` | 사가 참여자(재고·여력·슬롯·결제·주문) |
| cart | `cart/infrastructure/CatalogClient.java` | 상품·가격·콘텐츠 |
| claim | `claim/infrastructure/Upstreams.java` | order·payment |
| search | `search/infrastructure/SearchIndex.java`, `CategoryTreeClient.java` | Elasticsearch, product |
| customer | `customer/infrastructure/ServiceabilityClient.java` | 배송 가능 판정 |
| delivery-capacity | `delivery/capacity/infrastructure/CenterCalendarClient.java` | 센터 달력 |
| pricing | `pricing/infrastructure/ProductLinks.java` | product |
| product-content | `product/content/infrastructure/InventoryClient.java` | 재고 |
| storefront | `storefront/infrastructure/ContentCardClient.java` | 콘텐츠 카드 |
| mfc-inbound | `mfc/inbound/infrastructure/InventoryReceiptClient.java` | 재고 입고 |
| backoffice-bff | `backoffice/bff/infrastructure/ApprovalClient.java` | 승인 |

(경로 앞부분 `services/<서비스>/src/main/java/com/pmart/` 생략)

## 2. 멱등 — 같은 요청은 한 번만

### 2-1. 요청 헤더 강제
- `libraries/common-web/src/main/java/com/pmart/common/web/RequestHeadersFilter.java:20`: 변경 요청(POST·PUT·PATCH·DELETE)에 `Idempotency-Key`·`X-Source-System` 이 없으면 400(`:39`, `:54`).
- `X-Expected-Version` 이 오면 `RequestHeaders.checkExpectedVersion` 으로 비교 → `VERSION_CONFLICT`.

### 2-2. 요청 기록 `request_record`
- 코드: `libraries/common-messaging/src/main/java/com/pmart/common/messaging/RequestIdempotency.java`
  - `execute(key, request, type, action)`(`:76`)가 `tx.execute`(`:80`) 안에서 `request_record` 에 INSERT(`:52-53`)를 시도한다.
  - 같은 키·같은 본문·같은 행위자면 저장된 결과를 재생한다.
  - 하나라도 다르면 `IDEMPOTENCY_CONFLICT`(409, `:26`).
  - 업무 변경과 **한 트랜잭션**이다.
- 스키마: `libraries/common-messaging/src/main/resources/db/migration/common/V0_0_2__pmart_request_record.sql`
- 쓰는 예: order `CheckoutService.start`(결제 진입), 각 서비스의 `/internal` 명령.
- **주의**: 액션 전체가 한 트랜잭션이다. 액션 안에서 원격 호출을 하면 그동안 DB 연결과 잠금을 쥔다.
  - 예: order 결제 진입은 주문서 행 `FOR UPDATE` 를 잡은 채 customer·orchestration 을 호출한다.
  - 그래서 ai-assistant 는 모델 호출 경로에 이것을 쓰지 않고, 따로 선점(lease) 방식을 만들었다(ADR-0036).

### 2-3. 외부 PG 멱등
- `services/payment-service/.../infrastructure/toss/TossClient.java:101,131`: 승인·취소에 결제 시도별 고정 `Idempotency-Key`.
- 토스가 `409 IDEMPOTENT_REQUEST_PROCESSING` 을 주면 결과 불명으로 본다(`:156`).

### 2-4. 사가 단계 멱등
- 단계 명령 키 = `sagaId:STEP`(취소 사가도 같은 방식). 몇 번 다시 보내도 참여자 정본에 한 번만 반영된다.
- `Participants.java:340`: `.header("Idempotency-Key", requestId)`

## 3. 동시 수정 — 낙관적 잠금
- `libraries/common-web/src/main/java/com/pmart/common/web/OptimisticLockAdvice.java:28`: JPA `OptimisticLockingFailureException` → `VERSION_CONFLICT`(409, 재시도 가능).
- JdbcTemplate 서비스는 `UPDATE … WHERE version = ?` 조건부 갱신으로 같은 효과를 낸다.
- 예: payment `store.transition(id, READY, EXPIRED)`, ai-assistant 선점 조건부 갱신.

## 4. 사가 — 부분 실패와 보상

### 4-1. 결제 진입 사가 S1
`services/order-orchestration-service/src/main/java/com/pmart/order/orchestration/application/CheckoutSagaService.java`

| 장치 | 위치 | 동작 |
|---|---|---|
| 응답 뒤 비동기 진행 | `kick()` `:205` | 시작 요청에는 사가 저장 후 202 만 주고, 실행기 스레드에서 `advance()` |
| 실행기 문맥 전달 | `OrchestrationConfig.java:28-29` | `ContextExecutorService.wrap(가상 스레드 실행기)` — trace·MDC 를 넘긴다 |
| 단계 직렬화 | `advanceLocked` `:241-244`, `SagaStore.java:163` | 사가 행 `SELECT … FOR UPDATE` — 같은 사가를 두 스레드가 동시에 진행하지 않는다 |
| 결과 불명 | `:355`, `:521`, `:568` | 참여자 `UNKNOWN_RESULT`·`DEPENDENCY_UNAVAILABLE` → 단계 `UNKNOWN`(실패 아님) |
| 주기 재개 | `sweep()` `:217-220` | `pmart.orchestration.sweep-interval`(기본 PT10S)마다 `stuck-after` 이상 멈춘 사가와 만료가 지난 WAITING_RESULT 를 다시 진행 — 같은 키 재전송이라 안전 |
| 업무 거절 | `fail()` `:641` → `compensate()` `:661` | `COMPENSATING`: 이번 시도에서 **새로 확보한 자원만** 역순 해제하고, 원예약 조회로 RELEASED 를 확인 |
| 결제 결과 | Kafka `order.payment.v1` 소비 → `kick` | 이벤트는 신호일 뿐이다. 결제 시도를 직접 조회해 판단한다(`:422` UNKNOWN_TRACKING) |
| 승인 후 확정 실패 | 보상 | 결제 보상 취소 → 취소 확인 뒤 자원 해제 |
| 금액 불일치 | `RECONCILIATION_REQUIRED` | 확정·해제·취소를 모두 멈추고 사람이 본다 |

- 설정: `OrchestrationProperties.java:15,25`(`@RefreshScope`, `stuck-after` 는 재시작 없이 변경 가능)
- 상태·단계 이름: `domain/CheckoutRules.java:39`

### 4-2. 취소 사가 S3
`CancellationSagaService.java`
- 같은 sweep(`:213`)과 단계 키를 쓴다.
- 업무 거절은 자동으로 되돌리지 않고 `ESCALATED` 로 멈춘다. 운영자가 처리한다(BO-12).

### 4-3. 다른 주기 작업 (재시도·청소)

| 서비스 | 위치 | 주기 | 하는 일 |
|---|---|---|---|
| payment | `application/ReconciliationService.java:86` | PT15S | 결과 불명 재조회, READY 만료 |
| claim | `application/RefundService.java:171` | PT15S | 오래된 환불(기준 `refund-retry-after` 30초): 실행 대기는 같은 키로 재실행, PG 결과 대기·불명은 원거래 조회 |
| mfc-capacity | `application/HoldExpirySweeper.java:35` | PT30S | TTL 지난 여력 점유 → EXPIRED |
| delivery-capacity | `application/HoldExpirySweeper.java:33` | PT30S | TTL 지난 슬롯 점유 → EXPIRED |
| mfc-inventory | `application/HoldExpiry.java:24` | PT5S(`pmart.inventory.hold-expiry-interval`) | 만료된 재고 예약 → EXPIRED, `InventoryHoldExpired` |
| pricing | `application/PriceActivator.java:80` | PT10S | 예약된 가격 발효 |
| ai-assistant | `application/KnowledgeJobs.java:106` | PT1H | 답변 보존 정리(기간 미설정이면 아무것도 지우지 않음) |

- 재고·여력·슬롯 청소는 `FOR UPDATE SKIP LOCKED` 로 인스턴스끼리 나눠 잡는다.
  - `mfc-capacity .../infrastructure/HoldStore.java:70`
  - `delivery-capacity .../infrastructure/SlotHoldStore.java:182`
- 점유는 모두 TTL(재고·여력 10분, 슬롯 600초)이 있다. 그래서 사가가 죽거나 보상이 늦어도 자원은 결국 풀린다. 결과 불명(UNKNOWN_TRACKING) 때 해제하지 않고 TTL 에 맡기는 것도 이 때문이다.

## 5. 외부 결제(토스) — 불명 결과와 대사

| 단계 | 위치 | 동작 |
|---|---|---|
| 결과 분류 | `TossClient.java:30,64,147,156-158` | 시간 초과·연결 오류·5xx·409 처리 중 → `Result.Unknown` |
| 상태 반영 | `application/PaymentLedger.java` | `CONFIRM_UNKNOWN` + `PaymentResultUnknown`. 같은 주문의 새 결제 시도 차단 |
| 재조회 | `ReconciliationService.java:86` | 토스 조회로 DONE·ABORTED·EXPIRED·불일치를 판정 |
| 재조회 간격 | `domain/PaymentRules.java:16,27-28,67` | 직후 1회 → 1분 × 5회 → `MANUAL_REVIEW` 상신, 이후 15분. **시간 경과만으로 실패 처리하지 않는다** |
| 웹훅 | `application/WebhookService.java:48-49,74-76` | `Toss-Signature` HMAC-SHA256 검증(키 없으면 전부 401). 상태를 직접 바꾸지 않고 재조회만 앞당긴다 |
| 트랜잭션 경계 | `application/CheckoutService.java` `confirm()` | 검증·`READY→CONFIRMING` 짧은 TX → 토스 호출(TX 밖) → 결과 반영 짧은 TX |
| 승인 직전 자원 확인 | 같은 곳 | `resourceExpiresAt` 이 지났으면 토스를 부르지 않고 `EXPIRED` + 422 |

(경로 앞부분 `services/payment-service/src/main/java/com/pmart/payment/` 생략)

## 6. 메시징 — 전달 보장·중복 제거·실패 격리

| 장치 | 위치 | 동작 |
|---|---|---|
| Outbox | `libraries/common-messaging/.../OutboxWriter.java:20,50,55` | 업무 변경과 같은 TX 에 `outbox_event` INSERT(trace_id·correlation_id·traceparent 포함, `:26`). 앱이 Kafka 에 직접 보내지 않는다 |
| Debezium | `deploy/local/connectors/outbox-connector.template.json:33` (LAN 은 render 산출물) | WAL 로 읽어 Kafka 로. 앱이 죽어도 커밋된 이벤트는 나간다 |
| Kafka 프로듀서 기본값 | `MessagingDefaultsPostProcessor.java:34-35` | `acks=all`, `enable.idempotence=true` |
| Kafka 복제(LAN) | `deploy/lan/render.sh` kafka-N 블록, ADR-0037 | 3노드 KRaft 클러스터, 모든 토픽 복제 3·`min.insync.replicas` 2 — `acks=all` 과 함께 노드 1대가 죽어도 쓰기가 계속되고, 확인된 쓰기는 두 노드 이상에 있다. 2대가 죽으면 쓰기가 `NOT_ENOUGH_REPLICAS` 로 거절된다(2026-11-05 로컬 시험) |
| 소비자 기본값 | `MessagingDefaultsPostProcessor.java:43-54` | 자동 커밋 끔, `earliest`, `read_committed`, `ack-mode RECORD` |
| Inbox | `InboxGuard.java:21,39` | `inbox_event` INSERT(`:24-25`)와 처리기를 한 TX 로. 이미 있으면 건너뛴다. 처리기가 실패하면 inbox 도 롤백돼 다시 처리할 수 있다 |
| 소비 바인딩 기본값 | `StreamBindingDefaultsPostProcessor.java:73-84` | 그룹 = 앱 이름, `max-attempts = 1 + max-retries`(3), 백오프 1s×2 최대 10s, `enable-dlq`, `ack-mode RECORD` |
| 재시도 설정 | `MessagingProperties.java:44-49` | `pmart.messaging.consumer.max-retries`·`initial-backoff-ms`·`max-backoff-ms`. 스키마 위반·`IllegalArgumentException` 은 재시도 없이 DLQ |
| DLQ 목적지 | `PmartDlqDestinationResolver.java:9-13` | `{원래 토픽}.DLT`. 보내기 전에 `dead_letter` 에 OPEN 기록(기록이 실패해도 DLT 사본은 간다) |
| 미처리 기록·재처리 | `DeadLetters.java:42,128`, `DeadLetterController.java`, `StreamBridgeRepublisher.java:18,27-34` | 목록·상세·재처리(StreamBridge 로 원 토픽에 같은 key·본문·헤더)·폐기. 계약 `contracts/openapi/_internal-dead-letters.yaml` |
| 설정 누락 방지 | `ConsumerBindingVerifier.java` | 소비 바인딩 설정이 빠지면 기동 실패 |
| 감시 | 경보 `DeadLetterOpen`(`pmart_messaging_dead_letters_open`) | `docs/operations/lan-monitoring-and-backup.md` |

(앞부분 `libraries/common-messaging/src/main/java/com/pmart/common/messaging/` 생략)

- 이벤트가 늦거나 DLT 로 빠져도 사가는 sweep 이 결제를 직접 조회해 진행한다. 이벤트 유실이 업무 정지로 이어지지 않는다.
- 상품 검색 색인(Logstash)은 별도 경로다.
  - 콘텐츠 보강 실패는 2초 간격 2번 더 시도한 뒤 `search-index.DLT` 로 보낸다.
  - ES 거절은 Logstash DLQ 로 간다.
  - 위치: `services/search-service/src/main/logstash/search-index.conf`, 문서 `elk-in-pmart.md`

## 7. 입구 보호 — 게이트웨이

`platform-services/api-gateway/src/main/java/com/pmart/gateway/filter/RateLimitGlobalFilter.java`
- Redis 토큰 버킷(`RedisRateLimiter`, `:11,42`).
- 초과하면 429 `RATE_LIMITED` + `Retry-After`(`:30`).
- 단위와 값(설정 저장소 `api-gateway.yml:278-287`):

| 구분 | 단위 | 초당 보충 | 최대 버스트 |
|---|---|---|---|
| 공개 작업 | IP | 10 | 20 |
| 인증 요청 | JWT subject | 20 | 40 |

- 토스 웹훅 경로(`/api/v1/webhooks/**`)는 제외한다.
- **fail-open**(`:32`): Redis 가 죽으면 허용한다. 처리율 제한 때문에 전체 트래픽을 막지 않는다. Redis 헬스도 꺼 둔다(`api-gateway.yml` `management.health.redis.enabled: false`).
- `/api/v1/internal/**` 은 게이트웨이가 라우팅하지 않는다(내부 전용).

## 8. 실행 환경 — 프로세스·메모리·기동

| 장치 | 위치 | 동작 |
|---|---|---|
| 재시작 | `deploy/lan/render.sh:121` | 모든 컨테이너 `restart: unless-stopped` |
| 헬스체크 | `render.sh:147` 등 | 인프라 컨테이너 healthcheck, 앱은 actuator liveness·readiness(`ObservabilityDefaultsPostProcessor`) |
| 메모리 상한 | `render.sh` 각 블록 | Kafka 노드 2 GiB, ES·Logstash 각 1.5 GiB(검색·로그 따로, ADR-0038), Zipkin 512m(+ `-XX:+ExitOnOutOfMemoryError`) 등 — 한 구성요소가 PC 메모리를 다 쓰지 않게 |
| DB 연결 상한 | 설정 `application-lan.yml` | Hikari `maximum-pool-size: 5`(PC 하나의 Postgres 를 여러 서비스가 나눔) |
| 설정 fail-fast | 각 서비스 `application.yml` `spring.cloud.config.fail-fast: true` | 설정을 못 받으면 기동하지 않는다 → compose 재시작 |
| 기동 순서 | `deploy/lan/lan.sh` | config-server 를 먼저 올리고 health UP 을 기다린다 |
| 서비스 조회 | Eureka(ADR-0032) | 인스턴스는 서비스당 하나다. 다중 인스턴스 분산·장애 조치는 아직 없다 |

## 9. 없는 것·알려진 약점 (2026-11-05)

| 항목 | 상태 | 영향 |
|---|---|---|
| 회로 차단기·벌크헤드 | 없음(Resilience4j 미사용) | 상대가 느리면 호출마다 응답 제한 시간(5s)까지 기다린다. 스레드·연결이 묶일 수 있다 |
| 일반 동기 호출 자동 재시도 | 없음 | 사가 외 호출(예: 장바구니가 상품 조회)은 실패를 그대로 사용자에게 돌려준다. 재시도는 앱·사용자 몫이다 |
| 공통 멱등 안의 원격 호출 | order 결제 진입 등 | 원격 호출 동안 DB 연결·행 잠금을 쥔다(2-2) |
| 다중 인스턴스 | 서비스당 1개 | 인스턴스 장애 = 그 서비스 중단(compose 재시작까지) |
| 주기 작업 중복 실행 방지 | 청소 작업은 SKIP LOCKED, 사가는 행 잠금 | 분산 잠금(ShedLock 등)은 없다. 다중 인스턴스로 갈 때 작업별로 다시 본다 |
| common-web `spring.factories` 형식 | 다른 라이브러리와 키·형식이 다름(1-1) | 등록을 확인하는 시험이 없다. 로딩 여부를 실제 기동으로 확인할 것 |
| 게이트웨이 외부 추적·상관 헤더(`X-Correlation-Id`·`baggage`·`traceparent`) | 외부 값을 그대로 전달 | 운영 전 과제 — `baggage` 오염 여부를 시험으로 먼저 확인한 뒤 게이트웨이 필터(`trace-gateway-checkout-payment.md` 5절) |
