-- 이행 확인 필요(2026-11-08 사용자 결정, 외부 검토 #5): MFC 재고 보류(ReservationBlocked)를 주문에 표시한다 — 자동 취소·환불·고객 안내 없음, 주문 상태 불변.
CREATE TABLE order_fulfillment_issue (
    issue_id            varchar(40)  NOT NULL,
    source_event_id     varchar(64)  NOT NULL,
    order_id            varchar(64)  NOT NULL,
    mfc_id              varchar(32)  NOT NULL,
    reservation_id      varchar(64)  NOT NULL,
    sku                 varchar(16)  NOT NULL,
    lot_id              varchar(40),
    blocked_qty         integer      NOT NULL,
    reason              varchar(32)  NOT NULL,
    state               varchar(16)  NOT NULL,
    note                varchar(500),
    acknowledged_by     varchar(64),
    acknowledged_at     timestamptz,
    blocked_at          timestamptz  NOT NULL,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_order_fulfillment_issue PRIMARY KEY (issue_id),
    CONSTRAINT uq_order_fulfillment_issue_event UNIQUE (source_event_id),
    CONSTRAINT ck_order_fulfillment_issue_state CHECK (state IN ('OPEN', 'ACKNOWLEDGED'))
);
CREATE INDEX ix_order_fulfillment_issue_mfc ON order_fulfillment_issue (mfc_id, state, created_at);
CREATE INDEX ix_order_fulfillment_issue_order ON order_fulfillment_issue (order_id);
