---
paths:
  - "contracts/**"
  - "**/api/**"
  - "**/infrastructure/**"
  - "libraries/common-messaging/**"
---

# API·이벤트 규칙

## 동기 통신
- 외부 API는 HTTP/JSON. 서비스 간 호출은 Spring RestClient 기본.
- 타임아웃은 반드시 명시. 재시도는 제한적으로 (Resilience4j).
- 변경 요청은 멱등키와 처리 결과 조회 계약을 고려한다.
- 오류 응답은 Problem Details(RFC 9457) 형식.
- 시간 초과를 상대 서비스의 업무 실패로 단정하지 않는다 (결과 조회로 확인).
- **동기 호출 체인은 최대 2 hop.** 그 이상이 필요하면 이벤트 기반으로 설계한다 (ADR-0006, 승인 발효 이벤트화가 이 규칙의 결과).
- 경로는 `/api/v1/{resources}` 복수형 kebab-case. 명령은 하위 리소스 POST (`POST /api/v1/orders/{id}/cancellations`). 서비스 간 전용 경로는 `/internal/...`.
- 변경 요청 헤더: `Idempotency-Key`, `X-Source-System` 필수, 상태 변경은 `X-Expected-Version`. 응답 `status`는 ACCEPTED/APPLIED/REJECTED/UNKNOWN_RESULT (ACK ≠ 업무 성공).
- 공통 구현(슬라이스 0, 2026-10-28):
  - 변경 명령은 `RequestIdempotency.execute(...)`(common-messaging, 표 `request_record`) 안에서 처리한다. 같은 키·같은 본문은 저장 결과 재생, 다르면 `IDEMPOTENCY_CONFLICT`. 예외로 끝난 요청은 기록되지 않으므로, 재생해야 할 업무 거절은 결과(`CommandResponse.rejected`)로 돌려준다.
  - 서비스 간 동기 호출은 `Downstream.call(target, ...)`(common-web)로 감싼다. 미전달·503 → `DEPENDENCY_UNAVAILABLE`, 응답 시간 초과·그 밖의 5xx → `UNKNOWN_RESULT`(보상 금지, 원결과 조회), 4xx 는 그대로. 기본 타임아웃 연결 1초·응답 5초(`spring.http.clients.*`).
  - 낙관적 잠금: `RequestHeaders.checkExpectedVersion(current)` 선검사 + 엔티티 `@Version`. 저장 시 충돌은 자동으로 `VERSION_CONFLICT`.
  - 시험 토큰: common-test `TestJwt`(customer/rider/operator/service) + `@Import(TestJwtConfiguration.class)`.
- `traceId`(W3C `traceparent`)는 게이트웨이가 발급하고 모든 동기 호출·이벤트 봉투·로그에 전파한다. `correlationId`는 사가/사건 ID.
- 시각은 UTC `Instant`로 저장·전송한다. 기한·영업일·달력 판정만 Asia/Seoul (ADR-0010).
- REST 계약은 `contracts/openapi/`가 정본. springdoc으로 문서화.

## 비동기 통신 (Kafka)
- 확정된 도메인 사건만 이벤트로 발행.
- 업무 변경과 Outbox 저장은 같은 로컬 트랜잭션.
- 소비자는 Inbox 또는 동등한 멱등 처리 기록을 유지.
- 소비자는 Spring Cloud Stream 함수 바인딩(ADR-0035): `EventConsumers.of(...)` 로 만든 `PmartEventConsumer` 빈 + 설정 저장소 `<서비스>.yml` 의 `spring.cloud.function.definition`·`spring.cloud.stream.bindings.<fn>-in-0.destination`(그 뒤 `config-copies.sh sync`). 그룹·재시도·DLQ·커밋은 common-messaging 이 넣는다. `@KafkaListener` 와 운영 코드의 KafkaTemplate 은 쓰지 않는다(발행은 outbox → Debezium, 재처리는 StreamBridge).
- 업무 반영 후 offset 커밋. 재전달을 안전하게 처리.
- 필수 메타데이터: `eventId`, `aggregateId`, `aggregateVersion`, `schemaVersion`, `occurredAt`, `correlationId`.
- 순서가 필요한 사건은 안정적인 파티션 키 사용 (보통 aggregateId).
- Retry topic은 처리 순서를 바꿀 수 있음을 고려.
- 재시도·DLT·재처리 시 원이벤트 식별자 유지.
- 재시도를 다 쓴 메시지는 `{topic}.DLT` 와 그 서비스 DB `dead_letter` 에 남는다(common-messaging, ADR-0030). 원인을 고친 뒤 `POST /api/v1/internal/dead-letters/{id}/replays`(원래 토픽에 다시 발행) 또는 사유와 함께 `…/discards`. 재처리는 모든 구독자에게 다시 가므로 소비자는 반드시 멱등(inbox·버전 비교)이어야 한다.
- Kafka 보장을 외부 DB·PG까지의 exactly-once로 확대 해석하지 않는다.

## 이벤트 스키마
- `contracts/events/`의 JSON Schema(draft 2020-12)가 정본. Schema Registry는 두지 않는다(ADR-0009): 발행 측 `EventSchemaValidator`(ENFORCE)가 검증하고, 호환성은 필드 추가만 허용하는 규칙으로 지킨다. 봉투는 `_envelope.v1.json`.
- 하위 호환이 깨지는 변경(필드 삭제, 타입 변경, rename)은 새 버전으로.
- 계약 변경 시 영향받는 producer·consumer 서비스를 찾아 보고한다.
