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
- REST 계약은 `contracts/openapi/`가 정본. springdoc으로 문서화.

## 비동기 통신 (Kafka)
- 확정된 도메인 사건만 이벤트로 발행.
- 업무 변경과 Outbox 저장은 같은 로컬 트랜잭션.
- 소비자는 Inbox 또는 동등한 멱등 처리 기록을 유지.
- 업무 반영 후 offset 커밋. 재전달을 안전하게 처리.
- 필수 메타데이터: `eventId`, `aggregateId`, `aggregateVersion`, `schemaVersion`, `occurredAt`, `correlationId`.
- 순서가 필요한 사건은 안정적인 파티션 키 사용 (보통 aggregateId).
- Retry topic은 처리 순서를 바꿀 수 있음을 고려.
- 재시도·DLT·재처리 시 원이벤트 식별자 유지.
- Kafka 보장을 외부 DB·PG까지의 exactly-once로 확대 해석하지 않는다.

## 이벤트 스키마
- `contracts/events/`의 Avro 스키마가 정본. Schema Registry로 호환성 관리.
- 하위 호환이 깨지는 변경(필드 삭제, 타입 변경, rename)은 새 버전으로.
- 계약 변경 시 영향받는 producer·consumer 서비스를 찾아 보고한다.
