-- 2026-11-23 CU-7: customer CustomerRestricted 사본 — 이용제한·정지 고객은 문의를 새로 쓰지 못한다(기한 지나면 저절로 풀림).
CREATE TABLE customer_restriction_copy (
    account_id   varchar(64) NOT NULL,
    restriction  varchar(12) NOT NULL,   -- NONE / RESTRICTED / SUSPENDED
    until_at     timestamptz,
    version      bigint      NOT NULL,
    CONSTRAINT pk_customer_restriction_copy PRIMARY KEY (account_id)
);
