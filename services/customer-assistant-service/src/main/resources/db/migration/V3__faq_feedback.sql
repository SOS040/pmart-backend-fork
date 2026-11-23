-- 2026-11-23 계약 0.4.0 BR-91: FAQ 답 의견 — 한 고객 한 FAQ 하나(마지막 값). 글은 받지 않는다. faqId 는 재색인해도 같은 질문이면 같다.
CREATE TABLE faq_feedback (
    account_id  varchar(64)  NOT NULL,
    faq_id      varchar(80)  NOT NULL,
    helpful     boolean      NOT NULL,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    CONSTRAINT pk_faq_feedback PRIMARY KEY (account_id, faq_id)
);
CREATE INDEX ix_faq_feedback_faq ON faq_feedback (faq_id);
