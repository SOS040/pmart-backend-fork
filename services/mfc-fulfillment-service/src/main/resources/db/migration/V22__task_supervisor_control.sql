-- 센터 관리자 작업 조작(2026-11-20, 계약 0.28.0 — MFC 시뮬레이터 R-29 2단계, ADR-0077).
-- BLOCKED 를 누가 걸었나: SUPERVISOR = 관리자 작업 중지, NULL = 결품·예약 보류(결품 사가가 푼다). 상태가 바뀌면 지운다.
ALTER TABLE task ADD COLUMN block_kind varchar(16);

-- 조작 기록(지우지 않음). action = BLOCK·UNBLOCK·PARTIAL_CLOSE — 뒤에 더할 수 있어 CHECK 를 두지 않는다.
CREATE TABLE task_control (
    control_id    varchar(48)  NOT NULL,
    task_id       varchar(96)  NOT NULL,
    action        varchar(16)  NOT NULL,
    from_state    varchar(16)  NOT NULL,
    to_state      varchar(16)  NOT NULL,
    task_version  integer      NOT NULL,
    reason        varchar(200) NOT NULL,
    actor_id      varchar(64)  NOT NULL,
    worker_id     varchar(64),
    request_id    varchar(128) NOT NULL,
    details       jsonb,
    created_at    timestamptz  NOT NULL,
    CONSTRAINT pk_task_control PRIMARY KEY (control_id),
    CONSTRAINT fk_task_control_task FOREIGN KEY (task_id) REFERENCES task (task_id),
    CONSTRAINT ux_task_control_request UNIQUE (task_id, request_id)
);
CREATE INDEX ix_task_control_task ON task_control (task_id, created_at DESC);
