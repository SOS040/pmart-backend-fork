-- 오전 입고 수령창·하차면 예약(2026-11-08). 창·상한·하차면 수는 설정(pmart.capacity.receiving.*, 승인 전 가정값) — 없으면 CONFIG_REQUIRED.
CREATE TABLE unloading_bay_reservation (
    bay_reservation_id  varchar(40)  NOT NULL,
    mfc_id              varchar(32)  NOT NULL,
    business_date       date         NOT NULL,
    trip_id             varchar(64)  NOT NULL,
    trf_ids             text,
    vehicle_type        varchar(32),
    window_start        timestamptz  NOT NULL,
    window_end          timestamptz  NOT NULL,
    load_units          integer      NOT NULL,
    bay_code            varchar(16)  NOT NULL,
    state               varchar(12)  NOT NULL,
    row_version         integer      NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_unloading_bay_reservation PRIMARY KEY (bay_reservation_id),
    CONSTRAINT ck_unloading_bay_state CHECK (state IN ('RESERVED', 'ARRIVED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_unloading_bay_units CHECK (load_units > 0)
);
CREATE UNIQUE INDEX uq_unloading_bay_trip ON unloading_bay_reservation (mfc_id, business_date, trip_id) WHERE state <> 'CANCELLED';
CREATE INDEX ix_unloading_bay_day ON unloading_bay_reservation (mfc_id, business_date) WHERE state <> 'CANCELLED';
