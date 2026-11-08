-- 자동 선반 보충(IV-04, 2026-11-08 사용자 결정: 시스템이 만든다, 재배치는 감독자). 칸·상품별 최소·목표·이동 단위(Q-07 값 — 없으면 보충 안 함).
-- 보충 이동은 일반 이동 배정(move_allocation)과 같고 kind 만 REPLENISH. 실제 이동은 IV-03(카트 경유).
CREATE TABLE replenishment_policy (
    mfc_id              varchar(32)  NOT NULL,
    location_code       varchar(64)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    min_qty             integer      NOT NULL,
    target_qty          integer      NOT NULL,
    unit_qty            integer      NOT NULL,
    updated_by          varchar(64)  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_replenishment_policy PRIMARY KEY (mfc_id, location_code, sku),
    CONSTRAINT ck_replenishment_policy CHECK (min_qty >= 0 AND target_qty >= min_qty AND unit_qty > 0)
);

ALTER TABLE move_allocation ADD COLUMN kind varchar(10) NOT NULL DEFAULT 'MOVE';
ALTER TABLE move_allocation ADD CONSTRAINT ck_move_kind CHECK (kind IN ('MOVE', 'REPLENISH'));
