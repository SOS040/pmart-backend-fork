-- 자동 선반 보충(IV-04): 시스템이 만든 보충 이동도 이동 지시로 남긴다(kind REPLENISH, created_by SYSTEM). 2026-11-08
ALTER TABLE move_order ADD COLUMN kind varchar(10) NOT NULL DEFAULT 'MOVE';
