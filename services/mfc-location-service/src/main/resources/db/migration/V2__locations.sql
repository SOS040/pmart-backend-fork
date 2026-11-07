-- 위치 마스터(ADR-0040 2단계, 계약 mfc-location-service.yaml 0.2.0). location_id 는 불변, 코드는 센터 안에서 유일(문법 Q-03 미결 — 자유 문자열).
-- 상태: ACTIVE / CLOSED / COUNT_LOCKED / RETIRED. 적용된 파일은 수정하지 않는다.
CREATE TABLE location (
    location_id         varchar(40)  NOT NULL,
    mfc_id              varchar(32)  NOT NULL,
    location_code       varchar(32)  NOT NULL,
    area_code           varchar(32),
    zone                varchar(20)  NOT NULL,
    temperature_zone    varchar(10)  NOT NULL,
    state               varchar(16)  NOT NULL,
    state_reason        varchar(200),
    width_mm            integer,
    depth_mm            integer,
    height_mm           integer,
    slot_count          integer,
    max_weight_g        integer,
    pick_sequence       integer,
    row_version         integer      NOT NULL,
    created_by          varchar(128) NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_location PRIMARY KEY (location_id),
    CONSTRAINT uq_location_code UNIQUE (mfc_id, location_code),
    CONSTRAINT ck_location_zone CHECK (zone IN ('RECEIVING', 'STORAGE', 'PICKING', 'PACKING', 'QUARANTINE', 'DISPATCH_STAGING', 'RETURN', 'DISPOSAL')),
    CONSTRAINT ck_location_temp CHECK (temperature_zone IN ('AMBIENT', 'CHILLED', 'FROZEN')),
    CONSTRAINT ck_location_state CHECK (state IN ('ACTIVE', 'CLOSED', 'COUNT_LOCKED', 'RETIRED')),
    CONSTRAINT ck_location_dims CHECK (
        coalesce(width_mm, 0) >= 0 AND coalesce(depth_mm, 0) >= 0 AND coalesce(height_mm, 0) >= 0
        AND coalesce(slot_count, 0) >= 0 AND coalesce(max_weight_g, 0) >= 0)
);
CREATE INDEX ix_location_mfc ON location (mfc_id, zone, temperature_zone, state);

-- 상태 변경 요청 기록(GET /locations/{id}/state-changes/{requestId})
CREATE TABLE location_state_change (
    location_id         varchar(40)  NOT NULL,
    request_id          varchar(128) NOT NULL,
    source_system       varchar(64)  NOT NULL,
    from_state          varchar(16)  NOT NULL,
    to_state            varchar(16)  NOT NULL,
    new_version         integer      NOT NULL,
    reason              varchar(200) NOT NULL,
    count_session_id    varchar(64),
    changed_by          varchar(128) NOT NULL,
    changed_at          timestamptz  NOT NULL,
    CONSTRAINT pk_location_state_change PRIMARY KEY (location_id, request_id),
    CONSTRAINT fk_location_state_change FOREIGN KEY (location_id) REFERENCES location (location_id)
);
