-- 작업자 쪽 3단계(2026-11-08 사용자 결정 Q-09): 휴게 중에는 새 작업을 받지 않는다(들고 있던 작업은 그대로).
CREATE TABLE mfc_worker_break (
    break_id     varchar(40)  NOT NULL,
    mfc_id       varchar(32)  NOT NULL,
    worker_id    varchar(64)  NOT NULL,
    started_at   timestamptz  NOT NULL,
    started_by   varchar(64)  NOT NULL,
    ended_at     timestamptz,
    ended_by     varchar(64),
    CONSTRAINT pk_mfc_worker_break PRIMARY KEY (break_id),
    CONSTRAINT fk_mfc_worker_break FOREIGN KEY (mfc_id, worker_id) REFERENCES mfc_worker (mfc_id, worker_id)
);
CREATE UNIQUE INDEX uq_mfc_worker_break_open ON mfc_worker_break (mfc_id, worker_id) WHERE ended_at IS NULL;
