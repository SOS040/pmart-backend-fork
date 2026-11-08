-- 재고 위치 이동(IV-03, 2026-11-08 사용자 결정): 출발 칸 → 카트(MOVING) → 도착 칸, 두 단계.
-- 이동 배정 = 출발 물량(출발 칸 MOVE 보류 — E 에서 뺀다) + 도착 공간(들어올 예정량). 실제로 옮긴 만큼만 단위 행이 바뀐다. H 불변.
ALTER TABLE stock_unit DROP CONSTRAINT ck_stock_unit_work;
ALTER TABLE stock_unit ADD CONSTRAINT ck_stock_unit_work CHECK (work_state IN ('UNSTOWED', 'STORED', 'PICKED', 'PACKED', 'CANCEL_RETURN_PENDING', 'MOVING'));

-- 위치 투영: 고회전 선반·칸 치수(이동 용량 계산)
ALTER TABLE location_projection ADD COLUMN high_turnover boolean NOT NULL DEFAULT false;
ALTER TABLE location_projection ADD COLUMN width_mm integer;
ALTER TABLE location_projection ADD COLUMN depth_mm integer;
ALTER TABLE location_projection ADD COLUMN height_mm integer;
ALTER TABLE location_projection ADD COLUMN max_weight_g integer;

CREATE TABLE move_allocation (
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
    size_class          varchar(2),
    destination_capacity integer,
    content_hash        varchar(64)  NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_move_allocation PRIMARY KEY (move_id),
    CONSTRAINT ck_move_qty CHECK (dropped_qty >= 0 AND dropped_qty <= picked_qty AND picked_qty <= qty AND qty >= 0),
    CONSTRAINT ck_move_state CHECK (state IN ('ACTIVE', 'CANCEL_PENDING', 'COMPLETED', 'CANCELLED'))
);
CREATE INDEX ix_move_from ON move_allocation (mfc_id, from_location) WHERE state IN ('ACTIVE', 'CANCEL_PENDING');
CREATE INDEX ix_move_to ON move_allocation (mfc_id, to_location) WHERE state IN ('ACTIVE', 'CANCEL_PENDING');
