-- 결품 사가(IV-09 2부, ADR-0045, 2026-11-07). MFC 가 재확보하지 못한 결품(LineShortageReported)마다 하나.
-- 고객 사전 선택 CANCEL_OOS_ONLY: CHECK_ORDER → RESOLVE_WORK(fulfillment) → RELEASE_SHORTFALL(inventory) → APPLY_ORDER_CHANGE(order SHORTAGE)
-- → REQUEST_REFUND(claim 부분 환불) → COMPLETED. CANCEL_ALL·선택 없음·주문 전체 결품은 ESCALATED(수동 — 집품 뒤 전체 취소 IV-08 전).
CREATE TABLE shortage_saga (
    saga_id            VARCHAR(64)  PRIMARY KEY,
    event_id           VARCHAR(64)  NOT NULL,       -- 원 LineShortageReported 사건 ID(같은 사건 재전달에 사가 하나)
    order_id           VARCHAR(64)  NOT NULL,
    fulfillment_id     VARCHAR(64)  NOT NULL,
    reservation_id     VARCHAR(64),
    order_line_id      VARCHAR(80)  NOT NULL,
    sku                VARCHAR(32),
    short_qty          INT          NOT NULL,
    reason             VARCHAR(16),
    preference         VARCHAR(24),
    state              VARCHAR(16)  NOT NULL,       -- RUNNING / COMPLETED / ESCALATED
    current_step       VARCHAR(24)  NOT NULL,
    claim_id           VARCHAR(64),
    last_error_step    VARCHAR(24),
    last_error_code    VARCHAR(64),
    last_error_message VARCHAR(500),
    started_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    finished_at        TIMESTAMPTZ,
    CONSTRAINT uq_shortage_saga_event UNIQUE (event_id),
    CONSTRAINT ck_shortage_saga_state CHECK (state IN ('RUNNING', 'COMPLETED', 'ESCALATED')),
    CONSTRAINT ck_shortage_saga_qty CHECK (short_qty > 0)
);
CREATE INDEX ix_shortage_saga_open ON shortage_saga (updated_at) WHERE state = 'RUNNING';
CREATE INDEX ix_shortage_saga_order ON shortage_saga (order_id);
