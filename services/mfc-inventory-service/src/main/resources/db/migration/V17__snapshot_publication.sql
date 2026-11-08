-- InventorySnapshotPublished 발행 버전(2026-11-08 남은 일 17). 센터마다 스냅샷 버전을 1씩 올린다(구독자가 오래된 스냅샷을 버리게).
CREATE TABLE inventory_snapshot_publication (
    mfc_id            varchar(32)  NOT NULL,
    version           integer      NOT NULL,
    snapshot_id       varchar(48)  NOT NULL,
    line_count        integer      NOT NULL,
    published_at      timestamptz  NOT NULL,
    CONSTRAINT pk_inventory_snapshot_publication PRIMARY KEY (mfc_id)
);
