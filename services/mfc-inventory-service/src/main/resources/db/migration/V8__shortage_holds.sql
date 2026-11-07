-- 피킹 결품·파손 보고(IV-09, ADR-0044, 2026-11-07).

-- 분실 조사 보류: 실물을 찾지 못한 양을 판매 가능(E)에서만 뺀다. 격리 현물(Q)을 만들지 않고, H·단위 행은 그대로(MFC 문서 §2 "분실조사"는
-- 품질·업무 상태와 다른 제외 축). 같은 단위 키의 OPEN 보류 합 ≤ 단위 행 수량. 확인된 실제 손실은 실사(IV-11)에서 정리(RESOLVED).
CREATE TABLE stock_hold (
    hold_id         varchar(64)  NOT NULL,
    mfc_id          varchar(32)  NOT NULL,
    location_code   varchar(64)  NOT NULL,
    sku             varchar(10)  NOT NULL,
    lot_id          varchar(40)  NOT NULL,
    quality_state   varchar(24)  NOT NULL,
    work_state      varchar(24)  NOT NULL,
    qty             integer      NOT NULL,
    reason          varchar(16)  NOT NULL,    -- SHORT / BLOCKED
    state           varchar(10)  NOT NULL,    -- OPEN / RESOLVED
    source_ref      varchar(128) NOT NULL,    -- 결품 보고 ID
    created_at      timestamptz  NOT NULL,
    resolved_at     timestamptz,
    CONSTRAINT pk_stock_hold PRIMARY KEY (hold_id),
    CONSTRAINT ck_stock_hold_qty CHECK (qty > 0),
    CONSTRAINT ck_stock_hold_state CHECK (state IN ('OPEN', 'RESOLVED'))
);
CREATE INDEX ix_stock_hold_unit ON stock_hold (mfc_id, location_code, sku, lot_id, quality_state, work_state)
    WHERE state = 'OPEN';

-- 결품 보고(멱등·감사). 같은 report_id 는 한 번만.
CREATE TABLE shortage_report (
    report_id       varchar(128) NOT NULL,
    content_hash    varchar(64)  NOT NULL,
    mfc_id          varchar(32)  NOT NULL,
    reservation_id  varchar(40)  NOT NULL,
    order_line_id   varchar(64)  NOT NULL,
    sku             varchar(10)  NOT NULL,
    lot_id          varchar(40)  NOT NULL,
    location_code   varchar(64)  NOT NULL,
    reason          varchar(16)  NOT NULL,
    qty             integer      NOT NULL,
    quarantined_qty integer      NOT NULL,
    held_qty        integer      NOT NULL,
    resecured_qty   integer      NOT NULL,
    blocked_qty     integer      NOT NULL,
    resecured_parts jsonb        NOT NULL,
    commit_id       varchar(64)  NOT NULL,
    created_at      timestamptz  NOT NULL,
    CONSTRAINT pk_shortage_report PRIMARY KEY (report_id)
);
