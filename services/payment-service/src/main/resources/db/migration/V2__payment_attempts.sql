-- 결제 시도·취소·토스 호출 기록·원거래 조회·웹훅 — ADR-0005, 결제 명세(docs/architecture/payment-system-spec.md) §4.
-- 결제 시도 ID = 토스 orderId(명세 R5). paymentKey 는 받은 그대로 저장(유일). 카드 정보·시크릿 키는 저장하지 않는다.
-- 적용된 파일은 수정하지 않는다.

CREATE TABLE payment_attempt (
    payment_attempt_id      varchar(64)  NOT NULL,      -- = 토스 orderId (pay-<32 hex>)
    order_ref               varchar(64)  NOT NULL,      -- OMS 주문/초안 참조(주문 1 : 시도 N)
    draft_ref               varchar(64),
    saga_id                 varchar(64)  NOT NULL,
    customer_ref            varchar(64)  NOT NULL,      -- 고객 계정(auth accountId) — 고객 API 는 본인 것만
    source_system           varchar(64)  NOT NULL,
    entry_request_id        varchar(128) NOT NULL,      -- 결제 시작 Idempotency-Key
    amount                  bigint       NOT NULL,      -- 확정 청구액(원). 결제 모듈은 계산하지 않는다
    currency                varchar(3)   NOT NULL,
    order_name              varchar(100),
    requires_adult          boolean      NOT NULL,
    stage                   varchar(24)  NOT NULL,      -- 명세 §5
    payment_key             varchar(200),               -- 인증 후. 받은 그대로
    confirm_idempotency_key varchar(64)  NOT NULL,      -- 시도 생성 때 1회 발급, 불변
    confirm_called_at       timestamptz,                -- NULL 이면 승인 API 를 부른 적 없음(=결제 안 됨)
    resource_expires_at     timestamptz  NOT NULL,
    price_valid_until       timestamptz,
    toss_status             varchar(32),
    method                  varchar(32),
    approved_at             timestamptz,
    total_amount            bigint,
    balance_amount          bigint,
    is_partial_cancelable   boolean,
    fail_stage              varchar(16),
    fail_code               varchar(100),
    fail_message            varchar(500),
    mismatch_field          varchar(16),
    unknown_retry_count     integer      NOT NULL,
    next_check_at           timestamptz,
    last_inquiry_at         timestamptz,
    version                 integer      NOT NULL,
    created_at              timestamptz  NOT NULL,
    updated_at              timestamptz  NOT NULL,
    CONSTRAINT pk_payment_attempt PRIMARY KEY (payment_attempt_id),
    CONSTRAINT ux_payment_attempt_entry UNIQUE (source_system, entry_request_id),
    CONSTRAINT ck_payment_attempt_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_attempt_currency CHECK (currency = 'KRW'),
    CONSTRAINT ck_payment_attempt_stage CHECK (stage IN ('READY', 'AUTH_FAILED', 'CANCELED_BY_USER', 'CONFIRMING',
        'CONFIRM_UNKNOWN', 'APPROVED', 'CONFIRM_FAILED', 'EXPIRED', 'MISMATCH_HOLD', 'MANUAL_REVIEW',
        'PARTIAL_CANCELED', 'CANCELED'))
);
CREATE UNIQUE INDEX ux_payment_attempt_payment_key ON payment_attempt (payment_key) WHERE payment_key IS NOT NULL;
CREATE INDEX ix_payment_attempt_order ON payment_attempt (order_ref);
CREATE INDEX ix_payment_attempt_due ON payment_attempt (next_check_at) WHERE next_check_at IS NOT NULL;
CREATE INDEX ix_payment_attempt_ready ON payment_attempt (resource_expires_at) WHERE stage = 'READY';

-- 취소·환불 실행(토스 취소 요청 1건 = 1행). request_id = Idempotency-Key(토스 취소 멱등키로도 쓴다)
CREATE TABLE payment_cancel (
    request_id              varchar(128) NOT NULL,
    payment_attempt_id      varchar(64)  NOT NULL,
    kind                    varchar(16)  NOT NULL,      -- REFUND / COMPENSATION
    refund_intent_id        varchar(64),
    claim_ref               varchar(64),
    saga_id                 varchar(64),
    amount                  bigint       NOT NULL,
    scope                   varchar(8)   NOT NULL,      -- FULL / PARTIAL
    reason                  varchar(200) NOT NULL,
    refundable_amount_sent  bigint,
    state                   varchar(16)  NOT NULL,      -- REQUESTED / SUCCEEDED / FAILED / UNKNOWN
    transaction_key         varchar(64),
    pg_result_code          varchar(100),
    canceled_at             timestamptz,
    resolved_by             varchar(16),
    unknown_retry_count     integer      NOT NULL,
    next_check_at           timestamptz,
    requested_at            timestamptz  NOT NULL,
    resolved_at             timestamptz,
    CONSTRAINT pk_payment_cancel PRIMARY KEY (request_id),
    CONSTRAINT fk_payment_cancel_attempt FOREIGN KEY (payment_attempt_id) REFERENCES payment_attempt (payment_attempt_id),
    CONSTRAINT ck_payment_cancel_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_cancel_state CHECK (state IN ('REQUESTED', 'SUCCEEDED', 'FAILED', 'UNKNOWN'))
);
CREATE UNIQUE INDEX ux_payment_cancel_refund_intent ON payment_cancel (refund_intent_id) WHERE refund_intent_id IS NOT NULL;
CREATE UNIQUE INDEX ux_payment_cancel_transaction ON payment_cancel (transaction_key) WHERE transaction_key IS NOT NULL;
CREATE INDEX ix_payment_cancel_attempt ON payment_cancel (payment_attempt_id);
CREATE INDEX ix_payment_cancel_due ON payment_cancel (next_check_at) WHERE next_check_at IS NOT NULL;

-- 토스 호출·상태 전이 감사(명세 §4.3). Authorization 헤더·시크릿 키·카드 정보는 남기지 않는다
CREATE TABLE payment_event_log (
    id                      bigserial    PRIMARY KEY,
    payment_attempt_id      varchar(64)  NOT NULL,
    action                  varchar(32)  NOT NULL,      -- CONFIRM / INQUIRY / CANCEL / TRANSITION / WEBHOOK
    http_status             integer,
    toss_code               varchar(100),
    from_stage              varchar(24),
    to_stage                varchar(24),
    actor                   varchar(64),
    detail                  varchar(500),
    occurred_at             timestamptz  NOT NULL
);
CREATE INDEX ix_payment_event_log_attempt ON payment_event_log (payment_attempt_id, id);

-- 원거래 조회 이력
CREATE TABLE payment_inquiry (
    request_id              varchar(128) NOT NULL,
    payment_attempt_id      varchar(64)  NOT NULL,
    reason                  varchar(32)  NOT NULL,
    result                  varchar(32)  NOT NULL,      -- 조회 뒤 단계
    toss_status             varchar(32),
    inquired_at             timestamptz  NOT NULL,
    CONSTRAINT pk_payment_inquiry PRIMARY KEY (payment_attempt_id, request_id)
);

-- 웹훅 수신(본문 해시로 중복 제거). 웹훅은 원거래 조회를 앞당기는 신호일 뿐이다
CREATE TABLE toss_webhook_event (
    body_hash               char(64)     NOT NULL,
    event_type              varchar(64)  NOT NULL,
    payment_attempt_id      varchar(64),
    received_at             timestamptz  NOT NULL,
    CONSTRAINT pk_toss_webhook_event PRIMARY KEY (body_hash)
);
