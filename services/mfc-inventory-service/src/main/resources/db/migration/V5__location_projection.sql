-- mfc-location 위치 투영(MfcLocationStateChanged, 토픽 mfc.location.v1, ADR-0040 2단계). 정본이 아니다 — 수령·적치 목적지 검증에만 쓴다.
-- 같은 location_id 는 version 이 큰(같거나) 것만 반영한다. 적용된 파일은 수정하지 않는다.
CREATE TABLE location_projection (
    location_id         varchar(40)  NOT NULL,
    mfc_id              varchar(32)  NOT NULL,
    location_code       varchar(32)  NOT NULL,
    zone                varchar(20)  NOT NULL,
    temperature_zone    varchar(10)  NOT NULL,
    state               varchar(16)  NOT NULL,
    location_version    integer      NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_location_projection PRIMARY KEY (location_id)
);
CREATE INDEX ix_location_projection_code ON location_projection (mfc_id, location_code);
