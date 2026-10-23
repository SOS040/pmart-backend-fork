# ADR-0009 Outbox + Kafka, 공통 이벤트 봉투, 2계층 멱등

- 상태: 제안 (2026-10-23)
- 관련: CLAUDE.md "통신"·"계약 변경", OMS A.1.1·A.4, SCM 19.2·20, TMS 17.2·18, MFC IF-04 L1720·L1976, Commerce E.1·E.5, BackOffice 6.2

## 결정
1. **발행**: 모든 서비스는 로컬 트랜잭션에 Outbox 행을 함께 쓰고 릴레이가 Kafka로 발행한다. 릴레이는 1차 polling publisher, 2차 Debezium `[결정 필요]`. `libraries/common-messaging`은 봉투·Outbox·Inbox 기술 코드만 포함(비즈니스 로직 금지).
2. **봉투**(architecture-design §5.2): `eventId, eventType, schemaVersion, sourceSystem, aggregateType/Id/Version, occurredAt(UTC), timezone, traceId, correlationId, causationId, actor, payload`. 스키마는 `contracts/events/{EventType}.v{n}.json`.
3. **토픽**: `{domain}.{aggregate}.v{n}`, 파티션 키 = aggregateId. 하위 호환은 필드 추가만, 그 외는 `V2` 새 토픽.
4. **소비**: inbox 테이블 `(sourceSystem, eventId)` unique. 처리와 inbox 기록은 같은 로컬 트랜잭션. 실패는 재시도 → DLQ + `case-service` 상신.
5. **멱등 2계층**:
   - 요청: `(sourceSystem, requestId)` 같은 본문=기존 결과, 다른 본문=`IDEMPOTENCY_CONFLICT`.
   - 업무 유일성: 원주문·원TRF·departureId·handoverId·receiptId 등 실물 사건 ID로 새 키가 와도 1회 효과. 수량·차량이 다르면 충돌 사건.
6. **순서**: 같은 aggregate의 낮은 version은 폐기. 단 실물 사실(출발·인계·수령·전달)은 폐기하지 않고 정정 경로로 수용(발생시각·수신시각 보존).
7. **타임아웃 ≠ 실패**: 모든 명령 API에 짝이 되는 원결과 조회 API. 시간 경과만으로 성공/실패 확정 금지.
8. **수신자별 전달 상태**: 한 사실을 여러 수신자가 받을 때 각 수신자의 ACK/Receipt를 분리 추적(SCM 19.1, TMS 18-5, BackOffice 4.2 전달 축). 한 수신자 실패가 다른 성공이나 실물 사실을 취소하지 않는다.

## 결과
- 이벤트 payload에 개인정보 원문·PG 비밀·주소 원문·출입 비밀번호 금지(참조 ID).
- 재시도 간격·총횟수·보존기간·장기 미결 상신 기한은 운영 승인값 `[결정 필요]`.
