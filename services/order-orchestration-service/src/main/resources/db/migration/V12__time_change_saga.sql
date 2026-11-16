-- S4 예약 시간 변경 사가(2026-11-16 — OMS §4, Commerce §9.4). order 의 시간 변경 요청마다 하나(request_id 유일), 주문당 진행 중 하나.
-- 단계: FIND_RESOURCES → HOLD_SLOT → HOLD_CAPACITY → MOVE_LOT_DATE(날짜가 바뀔 때만) → RESCHEDULE_WORK(확정 지점) → CONFIRM_SLOT →
--       CONFIRM_CAPACITY → REPORT_ORDER → 끝. 확정 지점 전 거절: RELEASE_NEW(새 점유 해제·약속일 되돌림) → REPORT_ORDER(REJECTED).
CREATE TABLE time_change_saga (
    saga_id              VARCHAR(64)  PRIMARY KEY,
    request_id           VARCHAR(64)  NOT NULL UNIQUE,
    order_id             VARCHAR(64)  NOT NULL,
    mfc_id               VARCHAR(40)  NOT NULL,
    old_date             DATE         NOT NULL,
    old_start            VARCHAR(5)   NOT NULL,
    old_end              VARCHAR(5)   NOT NULL,
    new_date             DATE         NOT NULL,
    new_start            VARCHAR(5)   NOT NULL,
    new_end              VARCHAR(5)   NOT NULL,
    state                VARCHAR(16)  NOT NULL,       -- RUNNING / COMPLETED / ESCALATED
    current_step         VARCHAR(24)  NOT NULL,
    outcome              VARCHAR(16),                 -- CONFIRMED / REJECTED
    reason               VARCHAR(64),
    checkout_saga_id     VARCHAR(64),
    reservation_id       VARCHAR(64),
    fulfillment_id       VARCHAR(64),
    old_slot_hold_id     VARCHAR(64),
    new_slot_hold_id     VARCHAR(64),
    old_capacity_hold_id VARCHAR(64),
    new_capacity_hold_id VARCHAR(64),
    lot_moved            BOOLEAN      NOT NULL DEFAULT false,
    last_error_step      VARCHAR(24),
    last_error_code      VARCHAR(64),
    last_error_message   VARCHAR(500),
    started_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    finished_at          TIMESTAMPTZ,
    CONSTRAINT ck_time_change_saga_state CHECK (state IN ('RUNNING', 'COMPLETED', 'ESCALATED'))
);
CREATE UNIQUE INDEX ux_time_change_saga_running ON time_change_saga (order_id) WHERE state = 'RUNNING';
CREATE INDEX ix_time_change_saga_order ON time_change_saga (order_id, finished_at);
CREATE INDEX ix_time_change_saga_open ON time_change_saga (updated_at) WHERE state = 'RUNNING';
