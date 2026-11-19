-- 가입·로그인 휴대폰 확인(2026-11-20 ADR-0074 — 고객 BR-64): 계정마다 확인된 번호. 유효한(풀리지 않은) 번호는 한 계정만 — 부분 고유 색인.
-- released_at = 탈퇴로 풀린 때(고객 탈퇴는 아직 없음 — 만들 때 채운다). 풀린 지 rejoin-block(30일) 안의 번호는 다른 계정이 확인하지 못한다.
CREATE TABLE customer_identity (
    account_id   varchar(64)  NOT NULL,
    phone_hash   char(64)     NOT NULL,
    phone        varchar(20)  NOT NULL,
    verified_at  timestamptz  NOT NULL,
    released_at  timestamptz,
    CONSTRAINT pk_customer_identity PRIMARY KEY (account_id)
);
CREATE UNIQUE INDEX ux_customer_identity_phone ON customer_identity (phone_hash) WHERE released_at IS NULL;
CREATE INDEX ix_customer_identity_released ON customer_identity (phone_hash, released_at) WHERE released_at IS NOT NULL;

-- 가입 단계 확인은 프로필이 없을 수 있어 번호를 확인 행에 둔다(OCTOMO 조회에 원문이 필요). /me 확인은 프로필 번호를 쓰므로 NULL.
ALTER TABLE phone_verification ADD COLUMN phone varchar(20);

-- 0.9.0 으로 이미 확인된 회원(phone_verified)을 옮긴다. 해시 = PhoneVerificationRules.phoneHash("phone-verification:" + 번호).
-- 같은 번호가 둘 이상이면 먼저 바뀐(updated_at) 계정만(나머지는 다음 로그인 때 다시 확인 — ADR-0074 (가)).
INSERT INTO customer_identity (account_id, phone_hash, phone, verified_at)
SELECT DISTINCT ON (h) account_id, h, phone, updated_at
FROM (
    SELECT account_id, phone, updated_at,
           encode(sha256(convert_to('phone-verification:' || phone, 'UTF8')), 'hex') AS h
    FROM customer
    WHERE phone_verified AND phone IS NOT NULL AND phone <> ''
) c
ORDER BY h, updated_at, account_id
ON CONFLICT DO NOTHING;
