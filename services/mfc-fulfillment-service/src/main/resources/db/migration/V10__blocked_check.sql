-- 피킹 중 생긴 예약 보류 점검 표시(2026-11-08 #5 남은 빈틈): ReservationBlocked 를 받으면 그 예약의 진행 중 PICK 에 표시 →
-- 주기 작업이 pick-plan 을 보고 결품으로 넘긴다(사건 처리 트랜잭션 안에서 외부 호출 안 함). 마지막 집품 뒤 생긴 보류도 잡힌다.
ALTER TABLE task ADD COLUMN blocked_check_due_at timestamptz;
CREATE INDEX ix_task_blocked_check_due ON task (blocked_check_due_at) WHERE blocked_check_due_at IS NOT NULL;
