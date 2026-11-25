-- 작업 조작(2026-11-26 DC 백오피스 — 사용자 "큰 순서 전부 고치고", 작업 재배정·보류·재개·우선순위). 적용된 파일은 수정하지 않는다.
-- 우선순위: 높을수록 PDA 다음 작업 받기에서 먼저(같으면 기한·만든 순). 보류(HELD)는 다음 작업 받기에서 빠진다.
ALTER TABLE task ADD COLUMN priority integer NOT NULL DEFAULT 0;
ALTER TABLE task ADD CONSTRAINT ck_task_priority CHECK (priority BETWEEN -100 AND 100);
-- 누가 언제 무엇을 바꿨는지(백오피스 이벤트 타임라인·감사용).
CREATE TABLE task_adjustment (
    adjustment_id   varchar(64)  NOT NULL,
    task_id         varchar(64)  NOT NULL,
    cdc_id          varchar(32)  NOT NULL,
    action          varchar(16)  NOT NULL,          -- REASSIGN / HOLD / RESUME / PRIORITY
    from_state      varchar(16)  NOT NULL,
    to_state        varchar(16)  NOT NULL,
    from_assignee   varchar(128),
    to_assignee     varchar(128),
    from_priority   integer      NOT NULL,
    to_priority     integer      NOT NULL,
    reason          varchar(300),
    actor_id        varchar(128) NOT NULL,
    adjusted_at     timestamptz  NOT NULL,
    CONSTRAINT pk_task_adjustment PRIMARY KEY (adjustment_id)
);
CREATE INDEX ix_task_adjustment_task ON task_adjustment (task_id, adjusted_at);
CREATE INDEX ix_task_adjustment_cdc ON task_adjustment (cdc_id, adjusted_at);
