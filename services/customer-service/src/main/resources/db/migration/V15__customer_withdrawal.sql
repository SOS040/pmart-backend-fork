-- 고객 탈퇴(2026-11-20 ADR-0077 — 사용자 "고객 탈퇴 만들어줘"). 접수(재인증 뒤) → 의무 확인(order·claim) → 완료 또는 대기·철회.
-- state: PENDING(접수, 아직 확인 전) / BLOCKED_BY_OBLIGATIONS(미결 의무) / UNKNOWN(확인 실패 — 다시 확인) / COMPLETED / REVOKED.
-- 진행 중(PENDING·BLOCKED·UNKNOWN)인 탈퇴는 계정마다 하나.
CREATE TABLE customer_withdrawal (
    withdrawal_id     varchar(64)   NOT NULL,
    account_id        varchar(64)   NOT NULL,
    state             varchar(24)   NOT NULL,
    reason_code       varchar(40),
    reason_text       varchar(500),
    open_obligations  jsonb         NOT NULL DEFAULT '[]'::jsonb, -- 마지막 확인의 미결 목록 사본 [{kind, ref}]
    requested_at      timestamptz   NOT NULL,
    last_checked_at   timestamptz,
    completed_at      timestamptz,
    revoked_at        timestamptz,
    version           integer       NOT NULL DEFAULT 1,
    updated_at        timestamptz   NOT NULL,
    CONSTRAINT pk_customer_withdrawal PRIMARY KEY (withdrawal_id),
    CONSTRAINT ck_customer_withdrawal_state
        CHECK (state IN ('PENDING', 'BLOCKED_BY_OBLIGATIONS', 'UNKNOWN', 'COMPLETED', 'REVOKED'))
);
CREATE UNIQUE INDEX ux_customer_withdrawal_open ON customer_withdrawal (account_id)
    WHERE state IN ('PENDING', 'BLOCKED_BY_OBLIGATIONS', 'UNKNOWN');
CREATE INDEX ix_customer_withdrawal_account ON customer_withdrawal (account_id, requested_at DESC);
CREATE INDEX ix_customer_withdrawal_check ON customer_withdrawal (last_checked_at NULLS FIRST)
    WHERE state IN ('PENDING', 'BLOCKED_BY_OBLIGATIONS', 'UNKNOWN');
