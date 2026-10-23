# ADR-0009 Outbox + Kafka, 공통 이벤트 봉투, 2계층 멱등

- 상태: 제안 (2026-10-23) · **2026-10-23 갱신: 릴레이는 1차부터 Debezium** (사용자 결정 "데비지움 바로 적용")
- 관련: CLAUDE.md "통신"·"계약 변경", OMS A.1.1·A.4, SCM 19.2·20, TMS 17.2·18, MFC IF-04 L1720·L1976, Commerce E.1·E.5, BackOffice 6.2

## 결정
1. **발행**: 모든 서비스는 로컬 트랜잭션에 `outbox_event` 행을 함께 쓴다(`OutboxWriter`, 활성 트랜잭션 밖 호출은 거부). 발행은 **Debezium PostgreSQL 커넥터(pgoutput, publication `pmart_outbox`) + Outbox Event Router SMT**가 WAL의 INSERT를 읽어 수행한다. 애플리케이션 폴링 릴레이는 없다. 기본 설정은 INSERT 직후 같은 트랜잭션에서 DELETE해 테이블을 비워 둔다(WAL에는 INSERT가 남고 SMT는 DELETE를 무시). 컬럼은 SMT 규약(`id`, `aggregatetype`, `aggregateid`, `type`, `payload`) + `topic`(라우팅), `trace_id`, `correlation_id`. 메시지 키=`aggregateid`, 값=봉투 JSON, 헤더=eventType/aggregateType/traceId/correlationId. 서비스(DB) 1개당 커넥터 1개·복제 슬롯 1개. 로컬은 `deploy/local/`. `libraries/common-messaging`은 봉투·Outbox·Inbox·검증 기술 코드만 포함(비즈니스 로직 금지).
1-1. **발행 전 계약 검증**: `EventSchemaValidator`가 `contracts/events/{EventType}.v{n}.json`(jar 사본)으로 봉투+payload를 검증한다. 발행 측 기본 ENFORCE(위반 시 저장 거부), 소비 측 권장 WARN. Schema Registry는 두지 않는다.
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
