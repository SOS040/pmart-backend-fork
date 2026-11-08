-- 재고 위치 이동 지시(IV-03, 2026-11-08 사용자 결정: MFC 는 감독자가 지시, 작업자가 카트를 거쳐 두 단계 실행).
-- 수량 정본은 mfc-inventory 이동 배정(move_allocation) — 여기는 지시·실행 사본(마지막 inventory 응답).
CREATE TABLE move_order (
    move_id             varchar(64)  NOT NULL,
    mfc_id              varchar(32)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot_id              varchar(40)  NOT NULL,
    from_location       varchar(64)  NOT NULL,
    to_location         varchar(64)  NOT NULL,
    cart_location       varchar(64),
    qty                 integer      NOT NULL,
    picked_qty          integer      NOT NULL DEFAULT 0,
    dropped_qty         integer      NOT NULL DEFAULT 0,
    state               varchar(16)  NOT NULL,
    reason              varchar(200),
    created_by          varchar(64)  NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_move_order PRIMARY KEY (move_id)
);
CREATE INDEX ix_move_order_mfc ON move_order (mfc_id, state, created_at);
