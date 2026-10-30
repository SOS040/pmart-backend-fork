-- 고객 소셜 로그인(Google 먼저, D6 2026-10-30). 제공자 토큰·이메일은 저장하지 않는다(제공자 계정 식별자 sub 만).
-- 적용된 파일은 수정하지 않는다.

-- 내부 고객 계정(인증 관점). 프로필·주소·동의는 customer-service 정본.
CREATE TABLE customer_account (
    account_id          varchar(40)  NOT NULL,
    state               varchar(16)  NOT NULL,          -- ACTIVE / RESTRICTED / WITHDRAWN
    created_at          timestamptz  NOT NULL,
    last_login_at       timestamptz,
    CONSTRAINT pk_customer_account PRIMARY KEY (account_id),
    CONSTRAINT ck_customer_account_state CHECK (state IN ('ACTIVE', 'RESTRICTED', 'WITHDRAWN'))
);

-- 제공자 계정 ↔ 내부 계정. 같은 이메일이라는 이유로 다른 제공자 계정을 합치지 않는다(Commerce 4.9).
CREATE TABLE social_identity (
    provider            varchar(16)  NOT NULL,          -- GOOGLE / KAKAO / NAVER / APPLE
    provider_subject    varchar(255) NOT NULL,          -- 제공자 계정 식별자(Google sub)
    account_id          varchar(40)  NOT NULL,
    linked_at           timestamptz  NOT NULL,
    last_login_at       timestamptz,
    CONSTRAINT pk_social_identity PRIMARY KEY (provider, provider_subject),
    CONSTRAINT fk_social_identity_account FOREIGN KEY (account_id) REFERENCES customer_account (account_id)
);
CREATE INDEX ix_social_identity_account ON social_identity (account_id);

-- 원인증 요청(같은 Idempotency-Key 재전송·결과 조회). 토큰은 저장하지 않는다.
CREATE TABLE social_auth_request (
    auth_request_id     varchar(40)  NOT NULL,
    source_system       varchar(64)  NOT NULL,
    idempotency_key     varchar(128) NOT NULL,
    device_id           varchar(128) NOT NULL,
    provider            varchar(16)  NOT NULL,
    state               varchar(24)  NOT NULL,          -- AUTH_MEMBER / AUTH_CONSENT / AUTH_GUEST(거절) / AUTH_UNKNOWN
    failure_code        varchar(48),
    account_id          varchar(40),
    new_account         boolean      NOT NULL,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_social_auth_request PRIMARY KEY (auth_request_id),
    CONSTRAINT ux_social_auth_request_key UNIQUE (source_system, idempotency_key)
);
-- 실패 횟수 집계(기기별 15분 창)
CREATE INDEX ix_social_auth_request_failures ON social_auth_request (device_id, created_at)
    WHERE failure_code = 'PROVIDER_REJECTED';

-- 기기별 인증 제한(15분 내 5회 실패 → 10분). 제한 중 시도는 기간을 늘리지 않는다.
CREATE TABLE social_auth_restriction (
    device_id           varchar(128) NOT NULL,
    restricted_until    timestamptz  NOT NULL,
    CONSTRAINT pk_social_auth_restriction PRIMARY KEY (device_id)
);
