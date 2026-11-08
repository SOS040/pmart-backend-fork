-- 출하 부적격 LOT 정기 알림(2026-11-08 남은 일 16). 카테고리 칸에 남은 부적격 LOT 을 처음 본 시각과 함께 남기고, 기타 칸으로 회수되거나
-- 없어지면 CLEARED. 계산 정본은 MoveService.unshippableAt(오늘 출하 기준) — 이 표는 "얼마나 오래 안 거뒀나" 를 보기 위한 기록.
CREATE TABLE unshippable_lot_alert (
    alert_id        bigserial    NOT NULL,
    mfc_id          varchar(32)  NOT NULL,
    sku             varchar(32)  NOT NULL,
    lot_id          varchar(64)  NOT NULL,
    location_code   varchar(32)  NOT NULL,
    category_id     varchar(64),
    expiry_date     date,
    qty             integer      NOT NULL,
    state           varchar(8)   NOT NULL,
    first_seen_at   timestamptz  NOT NULL,
    last_seen_at    timestamptz  NOT NULL,
    cleared_at      timestamptz,
    CONSTRAINT pk_unshippable_lot_alert PRIMARY KEY (alert_id),
    CONSTRAINT ck_unshippable_lot_alert_state CHECK (state IN ('OPEN', 'CLEARED'))
);
CREATE UNIQUE INDEX uq_unshippable_lot_alert_open
    ON unshippable_lot_alert (mfc_id, sku, lot_id, location_code) WHERE state = 'OPEN';
