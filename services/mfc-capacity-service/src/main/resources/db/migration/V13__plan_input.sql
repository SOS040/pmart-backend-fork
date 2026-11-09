-- 배정 추천 계산 입력(2026-11-09, 계약 0.12.0, 원문 WK-09 "동일 입력·모델/정책을 재현 가능하게 저장", OR-Tools 보완 A4):
-- 엔진(CP-SAT)에 넘긴 문제 그대로(JSON) + 지문(SHA-256). 기간으로 지우지 않고, 총량이 설정 상한(LAN 2GB)을 넘으면 오래된 것부터 지운다
-- (확정 안 된 계획 먼저). 계획 결과(assignment_plan 등)는 지우지 않는다.
CREATE TABLE assignment_plan_input (
    plan_id     varchar(40)  NOT NULL,
    input_json  text         NOT NULL,
    input_hash  char(64)     NOT NULL,
    bytes       integer      NOT NULL,
    created_at  timestamptz  NOT NULL,
    CONSTRAINT pk_assignment_plan_input PRIMARY KEY (plan_id),
    CONSTRAINT fk_assignment_plan_input FOREIGN KEY (plan_id) REFERENCES assignment_plan (plan_id)
);
CREATE INDEX ix_assignment_plan_input_created ON assignment_plan_input (created_at);
