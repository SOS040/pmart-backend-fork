-- rider-auth-service(ADR-0054 E·F, ADR-0055): 라이더 Google 로그인 → 라이더 주체 RD-… → 라이더 토큰.
-- Google 토큰·이메일·이름은 저장하지 않는다(Google 계정 식별자 sub 만). 라이더 프로필·가입·승인·정지는 rider-service 정본.
-- 적용된 파일은 수정하지 않는다.

-- 라이더 주체(인증 관점). ACTIVE 가 아니면 로그인·갱신 불가.
CREATE TABLE rider_account (
    rider_id            varchar(40)  NOT NULL,
    state               varchar(16)  NOT NULL,          -- ACTIVE / DISABLED
    created_at          timestamptz  NOT NULL,
    last_login_at       timestamptz,
    CONSTRAINT pk_rider_account PRIMARY KEY (rider_id),
    CONSTRAINT ck_rider_account_state CHECK (state IN ('ACTIVE', 'DISABLED'))
);

-- Google 계정 ↔ 라이더 주체. 같은 이메일이라는 이유로 다른 계정을 합치지 않는다.
CREATE TABLE rider_identity (
    provider            varchar(16)  NOT NULL,          -- GOOGLE
    provider_subject    varchar(255) NOT NULL,          -- Google sub
    rider_id            varchar(40)  NOT NULL,
    linked_at           timestamptz  NOT NULL,
    last_login_at       timestamptz,
    CONSTRAINT pk_rider_identity PRIMARY KEY (provider, provider_subject),
    CONSTRAINT fk_rider_identity_account FOREIGN KEY (rider_id) REFERENCES rider_account (rider_id)
);
CREATE INDEX ix_rider_identity_rider ON rider_identity (rider_id);

-- 원인증 요청(같은 Idempotency-Key 재전송·결과 조회). 토큰은 저장하지 않는다.
CREATE TABLE rider_auth_request (
    auth_request_id     varchar(64)  NOT NULL,
    source_system       varchar(64)  NOT NULL,
    idempotency_key     varchar(128) NOT NULL,
    device_id           varchar(128) NOT NULL,
    provider            varchar(16)  NOT NULL,
    state               varchar(24)  NOT NULL,          -- AUTH_MEMBER / REJECTED
    failure_code        varchar(48),
    rider_id            varchar(40),
    new_account         boolean      NOT NULL,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_rider_auth_request PRIMARY KEY (auth_request_id),
    CONSTRAINT ux_rider_auth_request_key UNIQUE (source_system, idempotency_key)
);
CREATE INDEX ix_rider_auth_request_failures ON rider_auth_request (device_id, created_at)
    WHERE failure_code = 'PROVIDER_REJECTED';

-- 기기별 로그인 제한(15분 내 5회 실패 → 10분). 제한 중 시도는 기간을 늘리지 않는다.
CREATE TABLE rider_auth_restriction (
    device_id           varchar(128) NOT NULL,
    restricted_until    timestamptz  NOT NULL,
    CONSTRAINT pk_rider_auth_restriction PRIMARY KEY (device_id)
);

-- 로그인 세션(기기 하나). 만료는 마지막 갱신 기준으로 연장한다.
CREATE TABLE rider_session (
    session_id          varchar(64)  NOT NULL,
    rider_id            varchar(40)  NOT NULL,
    device_id           varchar(128) NOT NULL,
    source_system       varchar(64)  NOT NULL,
    state               varchar(16)  NOT NULL,          -- ACTIVE / REVOKED
    created_at          timestamptz  NOT NULL,
    expires_at          timestamptz  NOT NULL,
    last_refreshed_at   timestamptz,
    revoked_at          timestamptz,
    revoke_reason       varchar(32),
    CONSTRAINT pk_rider_session PRIMARY KEY (session_id),
    CONSTRAINT ck_rider_session_state CHECK (state IN ('ACTIVE', 'REVOKED'))
);
CREATE INDEX ix_rider_session_rider ON rider_session (rider_id) WHERE state = 'ACTIVE';

-- refresh token(SHA-256 만. 한 번만 쓴다 — 쓴 토큰이 다시 오면 탈취로 보고 세션 폐기)
CREATE TABLE rider_refresh_token (
    token_hash          char(64)     NOT NULL,
    session_id          varchar(64)  NOT NULL,
    issued_at           timestamptz  NOT NULL,
    expires_at          timestamptz  NOT NULL,
    used_at             timestamptz,
    CONSTRAINT pk_rider_refresh_token PRIMARY KEY (token_hash),
    CONSTRAINT fk_rider_refresh_token_session FOREIGN KEY (session_id) REFERENCES rider_session (session_id)
);
CREATE INDEX ix_rider_refresh_token_session ON rider_refresh_token (session_id);
