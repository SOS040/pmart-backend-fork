-- 2026-11-23 CU-7: customer CustomerRestricted 사본 — 정지(SUSPENDED) 기한. 기한 안이면 로그인·토큰 연장을 막는다(지나면 저절로 풀림).
-- restriction_version = 마지막으로 반영한 사건 차수(늦게 온 옛 사건은 버린다).
ALTER TABLE customer_account
    ADD COLUMN suspended_until     timestamptz,
    ADD COLUMN restriction_version bigint NOT NULL DEFAULT 0;
