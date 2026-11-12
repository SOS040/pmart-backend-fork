-- employee-auth-service(ADR-0056): Keycloak(직원 신원) → 운영자 ID(OP-…) → PMart 직원 토큰.
-- Keycloak 토큰·비밀번호·이메일·이름은 저장하지 않는다(Keycloak sub 와 운영자 ID 연결만). 티켓·refresh token·state 는 SHA-256 만.
-- 적용된 파일은 수정하지 않는다.

-- 직원(로그인 관점)과 직원 역할·권한 센터의 정본(ADR-0056 결정 4). ACTIVE 가 아니면 로그인·갱신 불가.
CREATE TABLE employee_account (
    operator_id         varchar(64)   NOT NULL,
    state               varchar(16)   NOT NULL,          -- ACTIVE / DISABLED / LEFT
    roles               varchar(64)[] NOT NULL,
    centers             varchar(64)[] NOT NULL,
    row_version         bigint        NOT NULL,
    created_at          timestamptz   NOT NULL,
    updated_at          timestamptz   NOT NULL,
    last_login_at       timestamptz,
    CONSTRAINT pk_employee_account PRIMARY KEY (operator_id),
    CONSTRAINT ck_employee_account_state CHECK (state IN ('ACTIVE', 'DISABLED', 'LEFT'))
);

-- 직원 변경 기록(누가·왜·전후 값). 5단계 전까지 관리 경로(PUT)의 감사 기록.
CREATE TABLE employee_account_change (
    change_id           bigserial     NOT NULL,
    operator_id         varchar(64)   NOT NULL,
    changed_by          varchar(64)   NOT NULL,
    source_system       varchar(64)   NOT NULL,
    reason              varchar(500)  NOT NULL,
    before_state        varchar(16),
    before_roles        varchar(64)[],
    before_centers      varchar(64)[],
    after_state         varchar(16)   NOT NULL,
    after_roles         varchar(64)[] NOT NULL,
    after_centers       varchar(64)[] NOT NULL,
    changed_at          timestamptz   NOT NULL,
    CONSTRAINT pk_employee_account_change PRIMARY KEY (change_id)
);
CREATE INDEX ix_employee_account_change_operator ON employee_account_change (operator_id, changed_at);

-- Keycloak 사용자(sub) ↔ 운영자 ID. 처음 로그인할 때 ID 토큰의 pmart_operator_id 로 연결하고 그 뒤로는 sub 로 찾는다.
CREATE TABLE employee_identity (
    keycloak_subject    varchar(64)   NOT NULL,
    operator_id         varchar(64)   NOT NULL,
    linked_at           timestamptz   NOT NULL,
    last_login_at       timestamptz,
    CONSTRAINT pk_employee_identity PRIMARY KEY (keycloak_subject),
    CONSTRAINT ux_employee_identity_operator UNIQUE (operator_id),
    CONSTRAINT fk_employee_identity_account FOREIGN KEY (operator_id) REFERENCES employee_account (operator_id)
);

-- 로그인 시작 → 콜백 사이의 상태(state·nonce·Keycloak PKCE verifier, 앱 쪽 티켓 challenge). 콜백에서 한 번만 쓴다.
CREATE TABLE login_attempt (
    state_hash          char(64)      NOT NULL,
    app                 varchar(32)   NOT NULL,
    return_uri          varchar(512)  NOT NULL,
    device_id           varchar(128)  NOT NULL,
    ticket_challenge    varchar(128)  NOT NULL,
    code_verifier       varchar(128),                    -- Keycloak 쪽 PKCE. 콜백에서 지운다
    nonce               varchar(64)   NOT NULL,
    created_at          timestamptz   NOT NULL,
    expires_at          timestamptz   NOT NULL,
    consumed_at         timestamptz,
    CONSTRAINT pk_login_attempt PRIMARY KEY (state_hash)
);
CREATE INDEX ix_login_attempt_expires ON login_attempt (expires_at);

-- 로그인 티켓(60초·1회, 시작한 기기·앱 code_verifier 로만 바꾼다)
CREATE TABLE login_ticket (
    ticket_hash         char(64)      NOT NULL,
    operator_id         varchar(64)   NOT NULL,
    app                 varchar(32)   NOT NULL,
    device_id           varchar(128)  NOT NULL,
    ticket_challenge    varchar(128)  NOT NULL,
    issued_at           timestamptz   NOT NULL,
    expires_at          timestamptz   NOT NULL,
    used_at             timestamptz,
    CONSTRAINT pk_login_ticket PRIMARY KEY (ticket_hash)
);
CREATE INDEX ix_login_ticket_expires ON login_ticket (expires_at);

-- 로그인 세션(기기 하나). 만료 = min(마지막 갱신 + 앱 무활동 한도, 최대 만료)
CREATE TABLE employee_session (
    session_id          varchar(64)   NOT NULL,
    operator_id         varchar(64)   NOT NULL,
    device_id           varchar(128)  NOT NULL,
    app                 varchar(32)   NOT NULL,
    source_system       varchar(64)   NOT NULL,
    state               varchar(16)   NOT NULL,          -- ACTIVE / REVOKED
    created_at          timestamptz   NOT NULL,
    max_expires_at      timestamptz   NOT NULL,
    expires_at          timestamptz   NOT NULL,
    last_refreshed_at   timestamptz,
    revoked_at          timestamptz,
    revoke_reason       varchar(32),
    CONSTRAINT pk_employee_session PRIMARY KEY (session_id),
    CONSTRAINT ck_employee_session_state CHECK (state IN ('ACTIVE', 'REVOKED'))
);
CREATE INDEX ix_employee_session_operator ON employee_session (operator_id) WHERE state = 'ACTIVE';

-- refresh token(SHA-256 만. 한 번만 쓴다 — 쓴 토큰이 다시 오면 탈취로 보고 세션 폐기)
CREATE TABLE employee_refresh_token (
    token_hash          char(64)      NOT NULL,
    session_id          varchar(64)   NOT NULL,
    issued_at           timestamptz   NOT NULL,
    expires_at          timestamptz   NOT NULL,
    used_at             timestamptz,
    CONSTRAINT pk_employee_refresh_token PRIMARY KEY (token_hash),
    CONSTRAINT fk_employee_refresh_token_session FOREIGN KEY (session_id) REFERENCES employee_session (session_id)
);
CREATE INDEX ix_employee_refresh_token_session ON employee_refresh_token (session_id);
