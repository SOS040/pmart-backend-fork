-- 초기 보유 반입 = 입고 예정(사용자 결정 2026-11-07, ADR-0040 3단계). 승인 → EXPECTED → 현장 수령(수량·수령 위치) → PENDING(전기 대기)
-- → APPLIED(inventory TIN) → 품질검사(INSPECT 작업, LOT 분리) → 적치(PUTAWAY 작업). 적용된 파일은 수정하지 않는다.
ALTER TABLE initial_stock_load DROP CONSTRAINT ck_initial_stock_load_state;
ALTER TABLE initial_stock_load ADD CONSTRAINT ck_initial_stock_load_state
    CHECK (state IN ('EXPECTED', 'PENDING', 'APPLIED', 'REJECTED'));
ALTER TABLE initial_stock_load
    ADD COLUMN receiving_location varchar(64),
    ADD COLUMN received_by        varchar(128),
    ADD COLUMN received_at        timestamptz;
ALTER TABLE initial_stock_load_line ADD COLUMN received_qty integer;
ALTER TABLE initial_stock_load_line ADD CONSTRAINT ck_initial_stock_load_line_received
    CHECK (received_qty IS NULL OR (received_qty >= 0 AND received_qty <= qty));

-- 입고 작업(INSPECT·PUTAWAY). 계획 = 완료 + 잔량(취소는 아직 없음)
CREATE TABLE inbound_task (
    task_id             varchar(64)  NOT NULL,
    task_type           varchar(10)  NOT NULL,
    mfc_id              varchar(32)  NOT NULL,
    receipt_id          varchar(64)  NOT NULL,
    parent_task_id      varchar(64),
    sku                 varchar(10)  NOT NULL,
    lot_id              varchar(40)  NOT NULL,             -- mfc-inventory lotId(검수 전 = 수령 LOT)
    location_code       varchar(64)  NOT NULL,             -- 출발 위치(수령 위치)
    planned_qty         integer      NOT NULL,
    done_qty            integer      NOT NULL DEFAULT 0,
    state               varchar(12)  NOT NULL,             -- READY / COMPLETED
    row_version         integer      NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_inbound_task PRIMARY KEY (task_id),
    CONSTRAINT ck_inbound_task_type CHECK (task_type IN ('INSPECT', 'PUTAWAY')),
    CONSTRAINT ck_inbound_task_state CHECK (state IN ('READY', 'COMPLETED')),
    CONSTRAINT ck_inbound_task_qty CHECK (planned_qty > 0 AND done_qty >= 0 AND done_qty <= planned_qty)
);
CREATE INDEX ix_inbound_task_mfc ON inbound_task (mfc_id, state, task_type);
CREATE INDEX ix_inbound_task_receipt ON inbound_task (receipt_id);

-- 작업 실행(IV-13): 현장 사실을 먼저 남기고(CAPTURED) → inventory 반영 → APPLIED / REJECTED / UNKNOWN_RESULT(같은 execution_id 로 다시)
CREATE TABLE inbound_task_execution (
    execution_id        varchar(128) NOT NULL,             -- inventory executionId
    task_id             varchar(64)  NOT NULL,
    request_id          varchar(128) NOT NULL,             -- Idempotency-Key
    body                jsonb        NOT NULL,             -- 현장 입력(검수 결과·적치 위치·수량)
    apply_status        varchar(16)  NOT NULL,
    inventory_commit_id varchar(40),
    reject_reason       varchar(500),
    executed_by         varchar(128) NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_inbound_task_execution PRIMARY KEY (execution_id),
    CONSTRAINT uq_inbound_task_execution_request UNIQUE (task_id, request_id),
    CONSTRAINT fk_inbound_task_execution FOREIGN KEY (task_id) REFERENCES inbound_task (task_id),
    CONSTRAINT ck_inbound_task_execution_status
        CHECK (apply_status IN ('CAPTURED', 'APPLIED', 'REJECTED', 'UNKNOWN_RESULT'))
);
