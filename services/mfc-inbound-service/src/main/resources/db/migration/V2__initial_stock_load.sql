-- 초기 보유 반입(결정 D2, ADR-0014): approval-service INITIAL_STOCK 승인(ApprovalDecided) → 센터별 반입 기록 → mfc-inventory
-- ConfirmReceipt 전기. 승인 반영(inbox 트랜잭션)과 전기 호출(HTTP)을 나눈다 — 기록은 PENDING 으로 만들고 주기 작업이 같은 receiptId 로
-- 전기를 재시도한다(inventory 가 receiptId 로 중복 계상을 막는다). 적용된 파일은 수정하지 않는다.

CREATE TABLE initial_stock_load (
    receipt_id          varchar(64)  NOT NULL,             -- 이 서비스가 발급(RCV-INIT-…). inventory 중복 계상 방지 키
    approval_id         varchar(128) NOT NULL,
    mfc_id              varchar(32),                       -- 승인 내용 오류로 센터를 정할 수 없으면 NULL
    state               varchar(16)  NOT NULL,             -- PENDING / APPLIED / REJECTED
    reject_reason       varchar(1000),
    inventory_commit_id varchar(64),
    attempts            integer      NOT NULL DEFAULT 0,
    last_error          varchar(500),                      -- 마지막 일시 실패(재시도 중)
    next_attempt_at     timestamptz,                       -- PENDING 일 때 다음 전기 시도 시각(선점 시 lease 로 미룸)
    approver_id         varchar(128),
    created_at          timestamptz  NOT NULL,             -- = ConfirmReceipt.receivedAt (재시도에도 같은 본문)
    updated_at          timestamptz  NOT NULL,
    applied_at          timestamptz,
    CONSTRAINT pk_initial_stock_load PRIMARY KEY (receipt_id),
    CONSTRAINT uq_initial_stock_load_approval_mfc UNIQUE (approval_id, mfc_id),
    CONSTRAINT ck_initial_stock_load_state CHECK (state IN ('PENDING', 'APPLIED', 'REJECTED'))
);
CREATE INDEX ix_initial_stock_load_approval ON initial_stock_load (approval_id);
CREATE INDEX ix_initial_stock_load_pending ON initial_stock_load (next_attempt_at) WHERE state = 'PENDING';

CREATE TABLE initial_stock_load_line (
    receipt_id          varchar(64)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    qty                 integer      NOT NULL,
    CONSTRAINT pk_initial_stock_load_line PRIMARY KEY (receipt_id, sku),
    CONSTRAINT fk_initial_stock_load_line FOREIGN KEY (receipt_id) REFERENCES initial_stock_load (receipt_id),
    CONSTRAINT ck_initial_stock_load_line_qty CHECK (qty > 0)
);
