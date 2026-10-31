-- 클레임 원장(2026-10-31, 슬라이스 8 첫 단계: 고객 전체 취소). RETURN·EXCHANGE 는 같은 표에 이후 추가 컬럼으로 넓힌다.
-- 상태(CANCEL): STOP_PENDING(출고 중지 확인 중) → IN_PROGRESS(취소 확정·환불 실행) → CLOSED(PG 환불 성공) / REJECTED(인계됨).
CREATE TABLE claim (
    claim_id         varchar(64)  NOT NULL,
    order_id         varchar(64)  NOT NULL,
    account_id       varchar(64)  NOT NULL,
    type             varchar(12)  NOT NULL,
    state            varchar(16)  NOT NULL,
    reason           varchar(32)  NOT NULL,
    reason_detail    varchar(1000),
    channel          varchar(16)  NOT NULL,
    stop_request_id  varchar(128),
    version          integer      NOT NULL DEFAULT 1,
    -- 접수 때 order 원장에서 읽은 환불 근거(결제 시도·승인액·금액 구성). 이후 가격 변경과 무관(원주문 스냅샷)
    payment_attempt_id varchar(64) NOT NULL,
    approved_amount  bigint       NOT NULL,
    item_amount      bigint       NOT NULL,
    bag_fee          bigint       NOT NULL,
    delivery_tip     bigint       NOT NULL,
    reject_reason    varchar(64),
    opened_at        timestamptz  NOT NULL,
    decided_at       timestamptz,
    closed_at        timestamptz,
    updated_at       timestamptz  NOT NULL,
    CONSTRAINT pk_claim PRIMARY KEY (claim_id)
);
CREATE INDEX ix_claim_account ON claim (account_id, opened_at DESC);
CREATE INDEX ix_claim_order ON claim (order_id);
CREATE UNIQUE INDEX ux_claim_stop_request ON claim (stop_request_id) WHERE stop_request_id IS NOT NULL;
-- 주문 하나에 진행 중인 취소 클레임은 하나(중복 접수 방지)
CREATE UNIQUE INDEX ux_claim_active_cancel ON claim (order_id)
    WHERE type = 'CANCEL' AND state NOT IN ('CLOSED', 'REJECTED', 'WITHDRAWN');

CREATE TABLE claim_line (
    claim_line_id  varchar(80)  NOT NULL,
    claim_id       varchar(64)  NOT NULL,
    order_line_id  varchar(80)  NOT NULL,
    sku            varchar(32)  NOT NULL,
    requested_qty  integer      NOT NULL,
    approved_qty   integer,
    decision       varchar(12)  NOT NULL,       -- PENDING / APPROVED / REJECTED
    line_amount    bigint       NOT NULL,       -- 원주문 행 금액(단가 × 요청 수량)
    CONSTRAINT pk_claim_line PRIMARY KEY (claim_line_id),
    CONSTRAINT fk_claim_line_claim FOREIGN KEY (claim_id) REFERENCES claim (claim_id)
);
CREATE INDEX ix_claim_line_claim ON claim_line (claim_id);

-- 환불 의도. PG 실행은 payment-service(멱등키 = cancellation_request_id). PG 결과 확인 전에는 COMPLETED 가 아니다.
CREATE TABLE refund_intent (
    refund_intent_id        varchar(64)  NOT NULL,
    claim_id                varchar(64)  NOT NULL,
    order_id                varchar(64)  NOT NULL,
    payment_attempt_id      varchar(64)  NOT NULL,
    scope                   varchar(8)   NOT NULL,
    reason                  varchar(24)  NOT NULL,
    amount                  bigint       NOT NULL,
    item_amount             bigint       NOT NULL,
    bag_fee                 bigint       NOT NULL,
    delivery_tip            bigint       NOT NULL,
    state                   varchar(20)  NOT NULL,  -- PENDING_EXECUTION / PG_PENDING / COMPLETED / FAILED / UNKNOWN
    cancellation_request_id varchar(128) NOT NULL,
    pg_result               varchar(8),
    pg_transaction_id       varchar(128),
    failure_code            varchar(64),
    attempts                integer      NOT NULL DEFAULT 0,
    requested_at            timestamptz,
    completed_at            timestamptz,
    created_at              timestamptz  NOT NULL,
    updated_at              timestamptz  NOT NULL,
    CONSTRAINT pk_refund_intent PRIMARY KEY (refund_intent_id),
    CONSTRAINT fk_refund_intent_claim FOREIGN KEY (claim_id) REFERENCES claim (claim_id)
);
CREATE UNIQUE INDEX ux_refund_intent_claim ON refund_intent (claim_id);  -- 1차: 클레임당 환불 1건(전체 취소)
CREATE INDEX ix_refund_intent_open ON refund_intent (state, updated_at)
    WHERE state IN ('PENDING_EXECUTION', 'PG_PENDING', 'UNKNOWN');
