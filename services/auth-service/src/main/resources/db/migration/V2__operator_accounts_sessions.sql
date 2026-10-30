-- 운영자 계정·로그인 세션·refresh token (ADR-0018). 비밀번호·refresh token 원문은 저장하지 않는다(bcrypt / SHA-256).
-- 적용된 파일은 수정하지 않는다.

CREATE TABLE operator_account (
    operator_id              varchar(40)  NOT NULL,
    login_id                 varchar(64)  NOT NULL,
    display_name             varchar(100) NOT NULL,
    password_hash            varchar(200) NOT NULL,     -- {bcrypt}... (DelegatingPasswordEncoder)
    roles                    jsonb        NOT NULL,     -- ["MD", "PRODUCT_APPROVER", ...]
    centers                  jsonb        NOT NULL,     -- ["MFC-BSN-01", ...]
    state                    varchar(16)  NOT NULL,     -- ACTIVE / DISABLED
    failed_count             integer      NOT NULL,
    locked_until             timestamptz,
    password_change_required boolean      NOT NULL,
    last_login_at            timestamptz,
    version                  integer      NOT NULL,
    created_by               varchar(128) NOT NULL,
    created_at               timestamptz  NOT NULL,
    updated_at               timestamptz  NOT NULL,
    CONSTRAINT pk_operator_account PRIMARY KEY (operator_id),
    CONSTRAINT ux_operator_account_login UNIQUE (login_id),
    CONSTRAINT ck_operator_account_state CHECK (state IN ('ACTIVE', 'DISABLED'))
);

-- 로그인 세션(운영자. 고객·라이더 세션도 같은 표를 쓸 수 있게 주체 유형을 둔다)
CREATE TABLE auth_session (
    session_id          varchar(40)  NOT NULL,
    principal_id        varchar(64)  NOT NULL,
    principal_type      varchar(16)  NOT NULL,          -- OPERATOR
    device_id           varchar(128),
    source_system       varchar(64)  NOT NULL,
    state               varchar(16)  NOT NULL,          -- ACTIVE / REVOKED
    created_at          timestamptz  NOT NULL,
    expires_at          timestamptz  NOT NULL,          -- 세션 절대 만료(갱신해도 늘지 않음)
    last_refreshed_at   timestamptz,
    revoked_at          timestamptz,
    revoke_reason       varchar(32),
    CONSTRAINT pk_auth_session PRIMARY KEY (session_id),
    CONSTRAINT ck_auth_session_state CHECK (state IN ('ACTIVE', 'REVOKED'))
);
CREATE INDEX ix_auth_session_principal ON auth_session (principal_id) WHERE state = 'ACTIVE';

-- refresh token(한 번만 쓴다. 쓴 토큰이 다시 오면 탈취로 보고 세션 폐기)
CREATE TABLE refresh_token (
    token_hash          char(64)     NOT NULL,          -- SHA-256 hex
    session_id          varchar(40)  NOT NULL,
    issued_at           timestamptz  NOT NULL,
    expires_at          timestamptz  NOT NULL,
    used_at             timestamptz,
    CONSTRAINT pk_refresh_token PRIMARY KEY (token_hash),
    CONSTRAINT fk_refresh_token_session FOREIGN KEY (session_id) REFERENCES auth_session (session_id)
);
CREATE INDEX ix_refresh_token_session ON refresh_token (session_id);
