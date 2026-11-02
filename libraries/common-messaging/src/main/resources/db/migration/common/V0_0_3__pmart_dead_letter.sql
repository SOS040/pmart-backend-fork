-- PMart 공통: 미처리 메시지(dead letter) 기록 (ADR-0030, common-messaging).
-- Kafka 소비가 재시도를 다 쓰면 {topic}.DLT 로 보내고 여기에도 남긴다. 이 서비스가 처리하지 못한 메시지만 담는다.
-- 상태: OPEN → REPLAYED(원래 토픽에 다시 발행) / DISCARDED(사유와 함께 버림). 다시 실패하면 새 행(replay_of 로 연결).
CREATE TABLE IF NOT EXISTS dead_letter (
    id                 uuid          NOT NULL,
    topic              varchar(200)  NOT NULL,                 -- 원래 토픽(DLT 아님)
    record_partition   integer       NOT NULL,
    record_offset      bigint        NOT NULL,
    record_key         text,
    payload            text,                                   -- 원래 값(봉투 JSON). 로그에 찍지 않는다
    headers            jsonb         NOT NULL,                 -- {이름: base64} (DLT 진단 헤더 제외)
    event_id           varchar(96),                            -- 봉투에서 읽을 수 있으면
    event_type         varchar(96),
    source_system      varchar(96),
    exception_class    varchar(300)  NOT NULL,
    exception_message  varchar(2000),
    status             varchar(12)   NOT NULL,                 -- OPEN / REPLAYED / DISCARDED
    failed_at          timestamptz   NOT NULL,
    resolved_at        timestamptz,
    resolved_by        varchar(128),
    resolution_note    varchar(500),
    replay_of          uuid,                                   -- 재처리한 메시지가 다시 실패하면 원 행
    CONSTRAINT pk_dead_letter PRIMARY KEY (id),
    CONSTRAINT uq_dead_letter_record UNIQUE (topic, record_partition, record_offset)
);
CREATE INDEX IF NOT EXISTS ix_dead_letter_status ON dead_letter (status, failed_at);
