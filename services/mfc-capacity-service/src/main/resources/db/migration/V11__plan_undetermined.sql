-- 배정 추천 상태 세분(2026-11-09, 계약 0.9.4, 원문 WK-09): 시간 안에 해를 못 찾음(solver UNKNOWN)은 불가능 증명이 아니므로 UNDETERMINED,
-- 모델 입력 오류(MODEL_INVALID)는 INVALID, 불가능 증명(INFEASIBLE)만 NO_SOLUTION.
ALTER TABLE assignment_plan DROP CONSTRAINT ck_assignment_plan_state;
ALTER TABLE assignment_plan ADD CONSTRAINT ck_assignment_plan_state
    CHECK (state IN ('PROPOSED', 'COMMITTED', 'NO_SOLUTION', 'STALE', 'UNDETERMINED', 'INVALID'));
