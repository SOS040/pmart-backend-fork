-- 작업 접수(슬라이스 6a, 2026-11-07). docs/business/flows/order-checkout-saga.md §2.0.
-- 주문 하나에 작업 지시(work_order) 하나. ConfirmOrder 요청마다 작업 버전(work_order_version) 하나 — 거절 뒤 같은 MFC 재시도(D6-5)는
-- 새 버전으로 온다. 접수된 버전만 출고(shipment)·작업(task)을 만든다.
-- 수량 사본은 출고 행(shipment_line) 한 곳. 작업 행(task_line)은 수량 실적만 두고 상품 정보는 출고 행을 참조한다.
-- 잔량은 저장하지 않는다(계획 − 완료 − 취소).

CREATE TABLE work_order (
    fulfillment_id          varchar(64)  NOT NULL,
    order_id                varchar(64)  NOT NULL,
    mfc_id                  varchar(32)  NOT NULL,
    reservation_id          varchar(64)  NOT NULL,
    delivery_mode           varchar(16),
    arrival_start           timestamptz,             -- 예약 주문 슬롯 시작(PICK 준비 시각 기준)
    arrival_end             timestamptz,
    out_of_stock_preference varchar(32),
    last_work_version       integer      NOT NULL,
    acceptance              varchar(8)   NOT NULL,   -- 마지막 버전 결정 ACCEPTED / REJECTED
    version                 integer      NOT NULL DEFAULT 1,
    created_at              timestamptz  NOT NULL,
    updated_at              timestamptz  NOT NULL,
    CONSTRAINT pk_work_order PRIMARY KEY (fulfillment_id),
    CONSTRAINT ux_work_order_order UNIQUE (order_id),
    CONSTRAINT ck_work_order_acceptance CHECK (acceptance IN ('ACCEPTED', 'REJECTED'))
);

CREATE TABLE work_order_version (
    fulfillment_id varchar(64)  NOT NULL,
    work_version   integer      NOT NULL,
    saga_id        varchar(64),
    decision       varchar(8)   NOT NULL,
    reject_reason  varchar(32),
    retryable      boolean      NOT NULL DEFAULT false,
    message        varchar(300),
    decided_at     timestamptz  NOT NULL,
    CONSTRAINT pk_work_order_version PRIMARY KEY (fulfillment_id, work_version),
    CONSTRAINT fk_work_order_version FOREIGN KEY (fulfillment_id) REFERENCES work_order (fulfillment_id),
    CONSTRAINT ck_work_order_version_decision CHECK (decision IN ('ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_work_order_version_reason CHECK (
        (decision = 'ACCEPTED' AND reject_reason IS NULL)
        OR (decision = 'REJECTED' AND reject_reason IN
            ('INVENTORY_MISMATCH', 'OPERATION_RESTRICTED', 'CAPACITY_EXCEEDED', 'LOT_INELIGIBLE', 'ALREADY_STOPPED')))
);

CREATE TABLE shipment (
    shipment_id    varchar(80)  NOT NULL,
    fulfillment_id varchar(64)  NOT NULL,
    work_version   integer      NOT NULL,           -- 이 출고를 만든(접수된) 작업 버전
    state          varchar(24)  NOT NULL,           -- PREPARING / CANCELLED (6a)
    weight_g       integer,
    version        integer      NOT NULL DEFAULT 1,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    CONSTRAINT pk_shipment PRIMARY KEY (shipment_id),
    CONSTRAINT fk_shipment_version FOREIGN KEY (fulfillment_id, work_version)
        REFERENCES work_order_version (fulfillment_id, work_version)
);
CREATE INDEX ix_shipment_fulfillment ON shipment (fulfillment_id);

CREATE TABLE shipment_line (
    shipment_id   varchar(80)  NOT NULL,
    order_line_id varchar(80)  NOT NULL,
    sku           varchar(32)  NOT NULL,
    qty           integer      NOT NULL,            -- 접수 때 작업 대상 수량(이미 중지된 수량 제외)
    product_name  varchar(200),
    units_per_sku integer,
    weight_g      integer,
    CONSTRAINT pk_shipment_line PRIMARY KEY (shipment_id, order_line_id),
    CONSTRAINT fk_shipment_line FOREIGN KEY (shipment_id) REFERENCES shipment (shipment_id),
    CONSTRAINT ck_shipment_line_qty CHECK (qty > 0)
);

CREATE TABLE task (
    task_id      varchar(96)  NOT NULL,
    shipment_id  varchar(80)  NOT NULL,
    task_type    varchar(8)   NOT NULL,
    state        varchar(16)  NOT NULL,
    ready_at     timestamptz,                       -- PICK 준비 시각(지나면 WAITING → READY)
    hold_reason  varchar(32),                       -- WAITING 사유. CONFIG_REQUIRED = 센터 준비 시각 설정 없음
    version      integer      NOT NULL DEFAULT 1,
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    CONSTRAINT pk_task PRIMARY KEY (task_id),
    CONSTRAINT fk_task_shipment FOREIGN KEY (shipment_id) REFERENCES shipment (shipment_id),
    CONSTRAINT ux_task_shipment_type UNIQUE (shipment_id, task_type),
    CONSTRAINT ck_task_type CHECK (task_type IN ('PICK', 'PACK', 'DISPATCH')),
    CONSTRAINT ck_task_state CHECK (state IN
        ('WAITING', 'READY', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'BLOCKED', 'FAILED', 'CANCELLED', 'CLOSED_PARTIAL'))
);
-- 준비 시각 승격 대상
CREATE INDEX ix_task_waiting ON task (ready_at) WHERE state = 'WAITING';

CREATE TABLE task_line (
    task_id       varchar(96)  NOT NULL,
    shipment_id   varchar(80)  NOT NULL,
    order_line_id varchar(80)  NOT NULL,
    planned_qty   integer      NOT NULL,
    completed_qty integer      NOT NULL DEFAULT 0,
    cancelled_qty integer      NOT NULL DEFAULT 0,
    CONSTRAINT pk_task_line PRIMARY KEY (task_id, order_line_id),
    CONSTRAINT fk_task_line_task FOREIGN KEY (task_id) REFERENCES task (task_id),
    CONSTRAINT fk_task_line_shipment_line FOREIGN KEY (shipment_id, order_line_id)
        REFERENCES shipment_line (shipment_id, order_line_id),
    -- 계획 = 완료 + 취소 + 잔량, 잔량 ≥ 0
    CONSTRAINT ck_task_line_qty CHECK (
        planned_qty > 0 AND completed_qty >= 0 AND cancelled_qty >= 0
        AND completed_qty + cancelled_qty <= planned_qty)
);
