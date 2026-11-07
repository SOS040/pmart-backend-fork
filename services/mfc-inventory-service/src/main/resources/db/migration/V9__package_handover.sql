-- 라이더 인계 전기(IV-08 인계, 슬라이스 6c, 2026-11-08, ADR-0047).
-- 포장 하나 = 인계 한 번: 포장 위치의 NORMAL·PACKED 단위 −q(H−q, E−q) + 그 줄·LOT 의 확정 조각 R−q, 한 확정 단위(commit).
-- 같은 packageId 는 한 번만(다른 요청 ID 여도) — 처음 반영의 commitId 를 돌려준다.

ALTER TABLE reservation_part
    ADD COLUMN handed_qty integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_reservation_part_handed CHECK (handed_qty >= 0 AND handed_qty <= packed_qty);

CREATE TABLE package_handover_posting (
    package_id                varchar(64)  NOT NULL,
    handover_id               varchar(64)  NOT NULL,
    handover_authorization_id varchar(64)  NOT NULL,
    fence_version             integer      NOT NULL,
    reservation_id            varchar(64)  NOT NULL,
    mfc_id                    varchar(32)  NOT NULL,
    commit_id                 varchar(64)  NOT NULL,
    request_hash              varchar(64)  NOT NULL,
    qty                       integer      NOT NULL,
    lines                     jsonb        NOT NULL,   -- [{orderLineId, sku, lotId, qty}]
    posted_at                 timestamptz  NOT NULL,
    CONSTRAINT pk_package_handover_posting PRIMARY KEY (package_id)
);
CREATE INDEX ix_package_handover_posting_reservation ON package_handover_posting (reservation_id);
