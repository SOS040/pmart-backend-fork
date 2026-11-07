-- 작업 배정(WK-02·WK-05, 슬라이스 6b 2026-11-07). Q-08 결정 전에는 권한자 수동 지정만 — 계획(OR-Tools)은 아직 없다.
-- 작업마다 활성 담당 1명, assignment_version 은 작업마다 1부터 1씩 는다. 작업 정본은 mfc-fulfillment/inbound(여기는 참조만).
CREATE TABLE task_assignment (
    assignment_id       varchar(48)  NOT NULL,
    mfc_id              varchar(32)  NOT NULL,
    task_id             varchar(96)  NOT NULL,
    task_type           varchar(16)  NOT NULL,
    worker_id           varchar(64)  NOT NULL,
    assignment_version  integer      NOT NULL,
    state               varchar(16)  NOT NULL,   -- ACTIVE / SUPERSEDED / RELEASED
    plan_id             varchar(48),             -- 수동 배정은 NULL
    assigned_by         varchar(64),
    reason              varchar(200),
    assigned_at         timestamptz  NOT NULL,
    ended_at            timestamptz,
    CONSTRAINT pk_task_assignment PRIMARY KEY (assignment_id),
    CONSTRAINT ux_task_assignment_version UNIQUE (task_id, assignment_version),
    CONSTRAINT ck_task_assignment_state CHECK (state IN ('ACTIVE', 'SUPERSEDED', 'RELEASED')),
    CONSTRAINT ck_task_assignment_version CHECK (assignment_version >= 1)
);
-- 활성 담당 1명
CREATE UNIQUE INDEX ux_task_assignment_active ON task_assignment (task_id) WHERE state = 'ACTIVE';
CREATE INDEX ix_task_assignment_worker ON task_assignment (mfc_id, worker_id) WHERE state = 'ACTIVE';
