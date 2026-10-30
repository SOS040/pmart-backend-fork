-- S1 결제 진입 사가(2026-10-31 단계: 결제 시작까지). 사가 진행 사본이며 자원·결제 성공의 정본은 각 서비스다.
CREATE TABLE checkout_saga (
    saga_id                VARCHAR(64)  PRIMARY KEY,
    draft_id               VARCHAR(64)  NOT NULL,
    order_id               VARCHAR(64)  NOT NULL,
    purchase_intent_id     VARCHAR(128) NOT NULL,
    origin_request_id      VARCHAR(128) NOT NULL,
    customer_ref           VARCHAR(64)  NOT NULL,
    mfc_id                 VARCHAR(64)  NOT NULL,
    delivery_mode          VARCHAR(16)  NOT NULL,
    arrival_date           DATE,
    arrival_start          VARCHAR(5),
    arrival_end            VARCHAR(5),
    amount                 BIGINT       NOT NULL,
    order_name             VARCHAR(100),
    contains_alcohol       BOOLEAN      NOT NULL DEFAULT FALSE,
    accepted_quote_version INT          NOT NULL,
    lines                  JSONB        NOT NULL,
    demand                 JSONB        NOT NULL,
    state                  VARCHAR(32)  NOT NULL,
    current_step           VARCHAR(32)  NOT NULL,
    version                INT          NOT NULL DEFAULT 0,
    quote_expires_at       TIMESTAMPTZ  NOT NULL,
    resource_expires_at    TIMESTAMPTZ,
    payment                JSONB,
    last_error_step        VARCHAR(32),
    last_error_code        VARCHAR(64),
    last_error_message     VARCHAR(500),
    started_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    finished_at            TIMESTAMPTZ
);

-- 같은 초안에 진행 중 사가는 하나(종료 상태는 제외 — 실패 뒤 다시 결제 진입 가능)
CREATE UNIQUE INDEX ux_checkout_saga_active_draft ON checkout_saga (draft_id)
    WHERE state IN ('RUNNING', 'WAITING_RESULT', 'UNKNOWN_TRACKING', 'COMPENSATING');
CREATE INDEX ix_checkout_saga_order ON checkout_saga (order_id);
CREATE INDEX ix_checkout_saga_open ON checkout_saga (state, updated_at)
    WHERE state IN ('RUNNING', 'WAITING_RESULT', 'UNKNOWN_TRACKING', 'COMPENSATING');

CREATE TABLE saga_step (
    saga_id            VARCHAR(64)  NOT NULL REFERENCES checkout_saga (saga_id),
    step               VARCHAR(32)  NOT NULL,
    seq                INT          NOT NULL,
    state              VARCHAR(16)  NOT NULL,
    target_system      VARCHAR(64),
    request_id         VARCHAR(200),
    result_id          VARCHAR(128),
    attempt            INT          NOT NULL DEFAULT 0,
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,
    timeout_at         TIMESTAMPTZ,
    last_error_code    VARCHAR(64),
    last_error_message VARCHAR(500),
    PRIMARY KEY (saga_id, step)
);

-- 이번 시도에서 새로 확보한 자원만(보상 대상). 해제는 원예약 조회로 확인한 뒤에만 RELEASED.
CREATE TABLE saga_compensation (
    saga_id             VARCHAR(64)  NOT NULL REFERENCES checkout_saga (saga_id),
    resource_type       VARCHAR(32)  NOT NULL,
    resource_id         VARCHAR(128) NOT NULL,
    owner_system        VARCHAR(64)  NOT NULL,
    acquired_at         TIMESTAMPTZ  NOT NULL,
    release_state       VARCHAR(20)  NOT NULL DEFAULT 'NOT_REQUESTED',
    release_request_id  VARCHAR(200),
    release_verified_at TIMESTAMPTZ,
    PRIMARY KEY (saga_id, resource_type)
);
