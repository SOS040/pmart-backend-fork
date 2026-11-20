-- 가입 축하 쿠폰·배차 불가 보상 쿠폰(2026-11-21 ADR-0082 5단계).

-- 가입 쿠폰을 준 번호 키(customer-service CustomerIdentityVerified.signupKey — 번호의 HMAC, 되돌릴 수 없음).
-- 사용자 "계속 막기": 탈퇴 뒤에도 지우지 않는다(처리방침 2026-11-21). 같은 키면 가입 쿠폰을 다시 주지 않는다.
CREATE TABLE signup_grant (
    signup_key          char(64)     NOT NULL,
    account_id          varchar(64)  NOT NULL,
    granted_at          timestamptz  NOT NULL,
    CONSTRAINT pk_signup_grant PRIMARY KEY (signup_key)
);

-- 회사 사유 보상(CL-5 배차 불가 취소 → 2,000원 쿠폰). 주문마다 한 번. 보상 행사가 설정·진행 중이 아니면 PENDING 으로 남아 주기 작업이 다시 시도.
CREATE TABLE compensation_grant (
    order_id            varchar(64)  NOT NULL,
    account_id          varchar(64)  NOT NULL,
    reason              varchar(32)  NOT NULL,      -- DISPATCH_UNAVAILABLE
    state               varchar(8)   NOT NULL,      -- PENDING / ISSUED
    promotion_id        varchar(40),
    coupon_id           varchar(40),
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_compensation_grant PRIMARY KEY (order_id, reason),
    CONSTRAINT ck_compensation_grant_state CHECK (state IN ('PENDING', 'ISSUED'))
);
CREATE INDEX ix_compensation_grant_pending ON compensation_grant (created_at) WHERE state = 'PENDING';
