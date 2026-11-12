-- 승인된 직원 권한(ADR-0056 5-4, BO-01 "유효 grant"). approval-service ApprovalDecided(type GRANT)의 승인 행 하나 = 한 줄.
-- 토큰 역할·센터 = employee_account.roles·centers(관리 경로·이전 계정) ∪ 지금 유효한 grant(valid_from ≤ 지금 < valid_until).
-- 적용된 파일은 수정하지 않는다.
CREATE TABLE employee_grant (
    grant_id            varchar(128)  NOT NULL,          -- <approvalId>:<lineId>
    operator_id         varchar(64)   NOT NULL,          -- 계정보다 먼저 올 수 있다(FK 없음 — 사건 순서가 서로 다른 토픽)
    role                varchar(64)   NOT NULL,
    centers             varchar(64)[] NOT NULL,
    valid_from          timestamptz   NOT NULL,          -- 시작일 00:00 Asia/Seoul
    valid_until         timestamptz,                     -- 마지막 날 다음 날 00:00 Asia/Seoul(이 시각부터 무효). 없으면 기한 없음
    approval_id         varchar(64)   NOT NULL,
    line_id             varchar(64)   NOT NULL,
    approver_id         varchar(128)  NOT NULL,          -- 운영자 ID 또는 RULE:<id>@v<버전>(자동 승인)
    reason              varchar(500),
    applied_at          timestamptz   NOT NULL,
    CONSTRAINT pk_employee_grant PRIMARY KEY (grant_id),
    CONSTRAINT ux_employee_grant_line UNIQUE (approval_id, line_id)
);
CREATE INDEX ix_employee_grant_operator ON employee_grant (operator_id, valid_from);
