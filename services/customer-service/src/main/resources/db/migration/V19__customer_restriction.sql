-- 2026-11-23 계약 0.16.0 CU-7: 계정 제한 — NONE·RESTRICTED(문의·리뷰 작성 막기)·SUSPENDED(주문·로그인까지). 늘 기한(7일)이 있고
-- 지나면 NONE 으로 본다(자동 해제 — 행은 그대로 두고 읽을 때 판단). restriction_version 은 CustomerRestricted 사건 차수(프로필 version 과 따로).
ALTER TABLE customer
    ADD COLUMN restriction         varchar(12) NOT NULL DEFAULT 'NONE',
    ADD COLUMN restriction_until   timestamptz,
    ADD COLUMN restriction_version integer     NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_customer_restriction CHECK (restriction IN ('NONE', 'RESTRICTED', 'SUSPENDED'));

CREATE TABLE customer_restriction_history (
    record_id    varchar(80)  NOT NULL,
    account_id   varchar(64)  NOT NULL,
    restriction  varchar(12)  NOT NULL,
    previous     varchar(12)  NOT NULL,
    until_at     timestamptz,
    source       varchar(16)  NOT NULL,   -- STAFF / AUTO_PROFANITY
    reason       varchar(300),            -- 직원 사유(로그·사건에 넣지 않음)
    actor        varchar(128) NOT NULL,
    created_at   timestamptz  NOT NULL,
    CONSTRAINT pk_customer_restriction_history PRIMARY KEY (record_id)
);
CREATE INDEX ix_customer_restriction_history_account ON customer_restriction_history (account_id, created_at DESC);
