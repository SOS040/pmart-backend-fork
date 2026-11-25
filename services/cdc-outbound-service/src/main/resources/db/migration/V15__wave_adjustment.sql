-- Wave 조작(2026-11-26, 0.22.0 — DC 백오피스, 사용자 "큰 순서 전부 고치고" 4번). WMS CDC-F-017 "Wave 보류는 단계와 별도 사유/영향 범위",
-- "CANCELLED = 실행 전 또는 배포 뒤 미착수·무실적 범위", "PLANNED 배포 전 변경은 재검증". 적용된 파일은 수정하지 않는다.
ALTER TABLE wave ADD COLUMN held_at timestamptz;
ALTER TABLE wave ADD COLUMN held_by varchar(128);
ALTER TABLE wave ADD COLUMN held_reason varchar(300);
ALTER TABLE wave ADD COLUMN cancel_reason varchar(300);
CREATE TABLE wave_adjustment (
    adjustment_id   varchar(64)  NOT NULL,
    wave_id         varchar(64)  NOT NULL,
    cdc_id          varchar(32)  NOT NULL,
    action          varchar(16)  NOT NULL,          -- HOLD / RESUME / CANCEL / MERGE_IN / MERGED / SPLIT_OUT / SPLIT_FROM
    from_state      varchar(16)  NOT NULL,
    to_state        varchar(16)  NOT NULL,
    other_wave_id   varchar(64),                    -- 병합·분할 상대
    trf_ids         text         NOT NULL DEFAULT '',  -- 옮긴 TRF(쉼표)
    reason          varchar(300),
    actor_id        varchar(128) NOT NULL,
    adjusted_at     timestamptz  NOT NULL,
    CONSTRAINT pk_wave_adjustment PRIMARY KEY (adjustment_id)
);
CREATE INDEX ix_wave_adjustment_wave ON wave_adjustment (wave_id, adjusted_at);
