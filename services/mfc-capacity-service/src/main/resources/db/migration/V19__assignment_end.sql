-- 배정 이력(2026-11-20, 계약 0.31.0 — MFC 시뮬레이터 R-29 2단계, ADR-0077): 배정이 끝날 때 누가·왜. 배정 행은 원래 지우지 않으므로
-- 이력은 이 표 그대로(새 표 없음). 그 전에 끝난 행은 null(RELEASED 는 ended_at 도 안 남겼다).
ALTER TABLE task_assignment
    ADD COLUMN ended_by   varchar(64),
    ADD COLUMN end_reason varchar(200);
CREATE INDEX ix_task_assignment_task ON task_assignment (task_id, assignment_version);
