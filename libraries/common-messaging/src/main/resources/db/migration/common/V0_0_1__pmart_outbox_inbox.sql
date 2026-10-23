-- PMart 공통: Outbox / Inbox (common-messaging). 서비스 Flyway 위치에 classpath:db/migration/common 이 자동 추가된다.
-- 버전 V0.0.x 는 공통 라이브러리 전용. 서비스 자체 마이그레이션은 V1 부터.
-- 이미 적용된 파일은 수정하지 않는다. 변경은 V0_0_2 이후로.

-- Outbox: 애플리케이션은 업무 트랜잭션 안에서 INSERT(그리고 기본 설정에서는 즉시 DELETE)만 한다.
-- Debezium PostgreSQL 커넥터가 WAL 에서 INSERT 를 읽어 Outbox Event Router SMT 로 Kafka 에 발행한다.
-- 컬럼명은 SMT 기본 필드명(id, aggregatetype, aggregateid, type, payload)을 따른다.
CREATE TABLE IF NOT EXISTS outbox_event (
    id              uuid         NOT NULL,                 -- = envelope.eventId (멱등 키)
    aggregatetype   varchar(64)  NOT NULL,                 -- envelope.aggregateType
    aggregateid     varchar(128) NOT NULL,                 -- envelope.aggregateId → Kafka 메시지 키(파티션)
    type            varchar(96)  NOT NULL,                 -- envelope.eventType → 헤더 eventType
    topic           varchar(128) NOT NULL,                 -- {domain}.{aggregate}.v{n} → 라우팅 대상
    payload         jsonb        NOT NULL,                 -- 봉투 전체(JSON)
    trace_id        varchar(64),
    correlation_id  varchar(128),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_outbox_event PRIMARY KEY (id)
);
-- REPLICA IDENTITY FULL 은 불필요(INSERT 만 사용). DELETE 는 SMT 가 무시한다.

-- Inbox: (sourceSystem, eventId) 로 중복 수신 차단. 처리와 기록은 같은 트랜잭션.
CREATE TABLE IF NOT EXISTS inbox_event (
    source_system   varchar(64)  NOT NULL,
    event_id        uuid         NOT NULL,
    event_type      varchar(96)  NOT NULL,
    aggregate_id    varchar(128),
    received_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_inbox_event PRIMARY KEY (source_system, event_id)
);
CREATE INDEX IF NOT EXISTS ix_inbox_event_received_at ON inbox_event (received_at);

-- Debezium publication: 테이블 소유자가 생성 가능. 이미 있으면 건너뜀.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'pmart_outbox') THEN
        EXECUTE 'CREATE PUBLICATION pmart_outbox FOR TABLE outbox_event';
    END IF;
EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'pmart_outbox publication 생성 권한 없음 — DBA 가 생성: CREATE PUBLICATION pmart_outbox FOR TABLE outbox_event';
END $$;
