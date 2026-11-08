-- 배정 추천(WK-09)·확정(WK-10), 2026-11-08. 추천은 배정을 바꾸지 않는다 — 확정 때 항목마다 task_assignment(plan_id) 를 만든다.
-- 항목은 계산 때의 작업 version·작업자 업무 버전을 적어 두고, 확정 때 그대로인지 다시 본다(다르면 STALE).
CREATE TABLE assignment_plan (
    plan_id             varchar(40)  NOT NULL,
    mfc_id              varchar(32)  NOT NULL,
    state               varchar(12)  NOT NULL,
    solver_status       varchar(16)  NOT NULL,
    horizon_start       timestamptz  NOT NULL,
    horizon_minutes     integer      NOT NULL,
    unassigned_count    integer      NOT NULL,
    late_minutes        integer      NOT NULL,
    max_load_minutes    integer      NOT NULL,
    objective_value     bigint       NOT NULL,
    wall_time_ms        integer      NOT NULL,
    stale_reasons       text,
    created_by          varchar(64)  NOT NULL,
    created_at          timestamptz  NOT NULL,
    committed_by        varchar(64),
    committed_at        timestamptz,
    CONSTRAINT pk_assignment_plan PRIMARY KEY (plan_id),
    CONSTRAINT ck_assignment_plan_state CHECK (state IN ('PROPOSED', 'COMMITTED', 'NO_SOLUTION', 'STALE'))
);
CREATE INDEX ix_assignment_plan_mfc ON assignment_plan (mfc_id, created_at);

CREATE TABLE assignment_plan_item (
    plan_id             varchar(40)  NOT NULL,
    task_id             varchar(96)  NOT NULL,
    task_type           varchar(16)  NOT NULL,
    task_version        integer      NOT NULL,
    worker_id           varchar(64)  NOT NULL,
    duty_version        integer      NOT NULL,
    start_at            timestamptz  NOT NULL,
    end_at              timestamptz  NOT NULL,
    late_minutes        integer      NOT NULL,
    CONSTRAINT pk_assignment_plan_item PRIMARY KEY (plan_id, task_id),
    CONSTRAINT fk_assignment_plan_item FOREIGN KEY (plan_id) REFERENCES assignment_plan (plan_id)
);

CREATE TABLE assignment_plan_unassigned (
    plan_id             varchar(40)  NOT NULL,
    task_id             varchar(96)  NOT NULL,
    task_type           varchar(16)  NOT NULL,
    reason              varchar(24)  NOT NULL,
    CONSTRAINT pk_assignment_plan_unassigned PRIMARY KEY (plan_id, task_id),
    CONSTRAINT fk_assignment_plan_unassigned FOREIGN KEY (plan_id) REFERENCES assignment_plan (plan_id)
);
