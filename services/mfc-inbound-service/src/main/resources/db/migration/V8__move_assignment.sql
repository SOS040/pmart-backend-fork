-- 작업자 쪽 4단계(2026-11-08 사용자 결정): 이동·보충 작업도 배정(capacity)을 거친다. 담당은 AssignmentPublished 투영,
-- 카트에 실은 이동은 실은 사람만 내려놓는다. 감독자 긴급 표시는 다음 작업 받기에서 보충과 같은 순위.
ALTER TABLE move_order ADD COLUMN worker_id varchar(64);
ALTER TABLE move_order ADD COLUMN assignment_version integer NOT NULL DEFAULT 0;
ALTER TABLE move_order ADD COLUMN urgent boolean NOT NULL DEFAULT false;
ALTER TABLE move_order ADD COLUMN picked_by varchar(64);
