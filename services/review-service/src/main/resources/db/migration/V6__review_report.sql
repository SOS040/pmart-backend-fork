-- 2026-11-23 계약 0.7.0 BR-90 리뷰 신고: 회원 한 사람이 한 리뷰에 한 번. 신고만으로 숨기지 않고 운영자(CS_AGENT·OPERATOR_ADMIN)가 확인해
-- HIDE(리뷰 state = 'HIDDEN') 또는 KEEP. 처리하면 그때까지의 신고를 RESOLVED 로 닫는다(뒤의 새 신고는 다시 OPEN).
CREATE TABLE review_report (
    report_id            varchar(40)  NOT NULL,
    review_id            varchar(64)  NOT NULL,
    reporter_account_id  varchar(64)  NOT NULL,
    reason               varchar(16)  NOT NULL,
    detail               varchar(300),               -- OTHER 일 때만
    state                varchar(10)  NOT NULL,      -- OPEN / RESOLVED
    resolution           varchar(8),                 -- HIDE / KEEP
    resolved_by          varchar(128),
    resolution_note      varchar(300),
    resolved_at          timestamptz,
    created_at           timestamptz  NOT NULL,
    CONSTRAINT pk_review_report PRIMARY KEY (report_id),
    CONSTRAINT uq_review_report_reporter UNIQUE (review_id, reporter_account_id),
    CONSTRAINT ck_review_report_reason CHECK (reason IN ('ABUSE', 'AD', 'IRRELEVANT', 'PRIVACY', 'OTHER')),
    CONSTRAINT ck_review_report_state CHECK (state IN ('OPEN', 'RESOLVED'))
);
CREATE INDEX ix_review_report_state ON review_report (state, created_at DESC);
CREATE INDEX ix_review_report_reporter ON review_report (reporter_account_id, review_id);
