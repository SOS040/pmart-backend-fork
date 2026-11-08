-- 예약의 위치 배정(IV-05 "위치 할당은 기존 예약을 세분화", WK-10 "고객 예약은 위치 세분화하며 추가 예약하지 않음", 2026-11-08).
-- 확정 조각(LOT 단위)의 남은 집품량을 선반 위치별로 나눠 적는다. R·E·H 는 바꾸지 않는다 — 위치 가용 = 선반 잔량 − 보류 − 남은 배정.
-- 남은 배정 = qty − picked_qty (state ACTIVE, 조각이 CONFIRMED 일 때만 센다). PLAN = 피킹 시작 때 배정, DEVIATION = 배정과 다른 칸에서 집은 기록.
CREATE TABLE reservation_location_allocation (
    allocation_id       varchar(40)  NOT NULL,
    part_id             varchar(64)  NOT NULL,
    reservation_id      varchar(40)  NOT NULL,
    mfc_id              varchar(32)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot_id              varchar(40)  NOT NULL,
    location_code       varchar(64)  NOT NULL,
    qty                 integer      NOT NULL,
    picked_qty          integer      NOT NULL DEFAULT 0,
    state               varchar(10)  NOT NULL,
    source              varchar(10)  NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_reservation_location_allocation PRIMARY KEY (allocation_id),
    CONSTRAINT fk_rla_part FOREIGN KEY (part_id) REFERENCES reservation_part (part_id),
    CONSTRAINT ck_rla_qty CHECK (picked_qty >= 0 AND picked_qty <= qty),
    CONSTRAINT ck_rla_state CHECK (state IN ('ACTIVE', 'RELEASED')),
    CONSTRAINT ck_rla_source CHECK (source IN ('PLAN', 'DEVIATION'))
);
CREATE INDEX ix_rla_part ON reservation_location_allocation (part_id) WHERE state = 'ACTIVE';
CREATE INDEX ix_rla_lot_location ON reservation_location_allocation (mfc_id, lot_id, location_code) WHERE state = 'ACTIVE';
CREATE INDEX ix_rla_location ON reservation_location_allocation (mfc_id, location_code) WHERE state = 'ACTIVE';
