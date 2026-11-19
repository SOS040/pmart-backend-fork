-- 휴대폰 번호 확인(2026-11-19, 계약 0.9.0 — 사용자 "옥토모로"). 고객이 OCTOMO 대표번호로 code 를 문자로 보내고, 확인 때 OCTOMO 에 그 문자가 왔는지 묻는다.
-- 번호 원문은 두지 않는다(phone_hash — 그 사이 프로필 번호가 바뀌었는지만 본다. 조회는 customer.phone 으로).
CREATE TABLE phone_verification (
    verification_id  varchar(64)  NOT NULL,
    account_id       varchar(64)  NOT NULL,
    phone_hash       char(64)     NOT NULL,
    code             varchar(16)  NOT NULL,
    state            varchar(16)  NOT NULL, -- PENDING / VERIFIED / FAILED / EXPIRED
    attempts         integer      NOT NULL DEFAULT 0,
    failure_reason   varchar(32),
    expires_at       timestamptz  NOT NULL,
    created_at       timestamptz  NOT NULL,
    finished_at      timestamptz,
    CONSTRAINT pk_phone_verification PRIMARY KEY (verification_id)
);
CREATE INDEX ix_phone_verification_account ON phone_verification (account_id, created_at);
