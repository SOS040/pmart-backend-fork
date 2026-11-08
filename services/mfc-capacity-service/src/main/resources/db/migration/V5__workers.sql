-- 작업자·업무·근무(WK-02 입력, 2026-11-08). 사용자 결정: 감독자가 등록하고, 업무가 바뀌어도 로그인 정보는 그대로.
-- 그래서 계정(worker_id = 운영자 ID, 바꾸지 않는 열쇠)과 업무(작업 유형·온도대 자격)를 다른 표에 두고, 업무는 버전마다 한 행(이력)으로 남긴다.
CREATE TABLE mfc_worker (
    mfc_id              varchar(32)  NOT NULL,
    worker_id           varchar(64)  NOT NULL,
    display_name        varchar(60)  NOT NULL,
    state               varchar(10)  NOT NULL,
    duty_version        integer      NOT NULL,
    registered_by       varchar(64)  NOT NULL,
    registered_at       timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_mfc_worker PRIMARY KEY (mfc_id, worker_id),
    CONSTRAINT ck_mfc_worker_state CHECK (state IN ('ACTIVE', 'SUSPENDED', 'LEFT'))
);

CREATE TABLE mfc_worker_duty (
    mfc_id              varchar(32)  NOT NULL,
    worker_id           varchar(64)  NOT NULL,
    duty_version        integer      NOT NULL,
    task_types          text[]       NOT NULL,
    temperature_zones   text[]       NOT NULL,
    reason              varchar(200),
    changed_by          varchar(64)  NOT NULL,
    changed_at          timestamptz  NOT NULL,
    CONSTRAINT pk_mfc_worker_duty PRIMARY KEY (mfc_id, worker_id, duty_version),
    CONSTRAINT fk_mfc_worker_duty FOREIGN KEY (mfc_id, worker_id) REFERENCES mfc_worker (mfc_id, worker_id)
);

CREATE TABLE mfc_worker_state_change (
    change_id           varchar(40)  NOT NULL,
    mfc_id              varchar(32)  NOT NULL,
    worker_id           varchar(64)  NOT NULL,
    from_state          varchar(10),
    to_state            varchar(10)  NOT NULL,
    reason              varchar(200),
    changed_by          varchar(64)  NOT NULL,
    changed_at          timestamptz  NOT NULL,
    CONSTRAINT pk_mfc_worker_state_change PRIMARY KEY (change_id),
    CONSTRAINT fk_mfc_worker_state_change FOREIGN KEY (mfc_id, worker_id) REFERENCES mfc_worker (mfc_id, worker_id)
);
CREATE INDEX ix_mfc_worker_state_change ON mfc_worker_state_change (mfc_id, worker_id, changed_at);

CREATE TABLE mfc_worker_shift (
    shift_id            varchar(40)  NOT NULL,
    mfc_id              varchar(32)  NOT NULL,
    worker_id           varchar(64)  NOT NULL,
    start_at            timestamptz  NOT NULL,
    end_at              timestamptz  NOT NULL,
    state               varchar(10)  NOT NULL,
    note                varchar(200),
    created_by          varchar(64)  NOT NULL,
    created_at          timestamptz  NOT NULL,
    cancel_reason       varchar(200),
    cancelled_by        varchar(64),
    cancelled_at        timestamptz,
    CONSTRAINT pk_mfc_worker_shift PRIMARY KEY (shift_id),
    CONSTRAINT fk_mfc_worker_shift FOREIGN KEY (mfc_id, worker_id) REFERENCES mfc_worker (mfc_id, worker_id),
    CONSTRAINT ck_mfc_worker_shift_state CHECK (state IN ('PLANNED', 'CANCELLED')),
    CONSTRAINT ck_mfc_worker_shift_range CHECK (end_at > start_at)
);
CREATE INDEX ix_mfc_worker_shift ON mfc_worker_shift (mfc_id, worker_id, start_at) WHERE state = 'PLANNED';

-- 배정에 작업 구역 온도대(요청에 있을 때) — 업무가 바뀌면 맞지 않는 배정을 알리는 데 쓴다
ALTER TABLE task_assignment ADD COLUMN temperature_zone varchar(10);
