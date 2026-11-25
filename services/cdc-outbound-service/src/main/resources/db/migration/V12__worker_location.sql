-- 작업자 마지막 스캔 위치(2026-11-25 DC 백오피스 M-1 — 사용자 "서버에 작업 남기는 방식은 PDA로 스캔한 위치로 해"). 적용된 파일은 수정하지 않는다.
-- PDA(시뮬레이터 가상 작업자 포함)가 보낸 위치 코드의 마지막 한 줄만 둔다(이력 없음). 피킹 실적 줄 위치 + 위치 스캔 경로.
CREATE TABLE worker_location (
    cdc_id         varchar(32)  NOT NULL,
    worker_id      varchar(128) NOT NULL,
    location_code  varchar(40)  NOT NULL,
    zone           varchar(4),
    floor          varchar(4),
    source         varchar(16)  NOT NULL,          -- PICK_RESULT / SCAN
    task_id        varchar(64),
    scanned_at     timestamptz  NOT NULL,
    CONSTRAINT pk_worker_location PRIMARY KEY (cdc_id, worker_id)
);
CREATE INDEX ix_worker_location_floor ON worker_location (cdc_id, floor);
