-- 재고 모델 v2(ADR-0040): 위치·LOT·품질·업무상태별 단위 행(stock_unit) + LOT 요약(stock_lot_balance) + 센터 요약(stock_balance, V3).
-- 수량 기호: H = 단위 행 합, Q = QUARANTINED+DISPOSAL_PENDING, E = NORMAL 이고 STORED/PICKED/PACKED, R = 예약 조각 HAND HELD+CONFIRMED.
-- 세 표는 한 사건에서 함께 바뀐다. 잠금 순서: stock_balance(SKU 오름차순) → stock_lot_balance(SKU, lot_id) → stock_unit.
-- 기존 잔량·예약은 센터·SKU 별 UNTRACKED LOT 하나와 이관 위치 MIGRATION 으로 옮긴다(합계 불변). 적용된 파일은 수정하지 않는다.

CREATE TABLE stock_lot (
    lot_id              varchar(40)  NOT NULL,             -- inventory 발급(LOT-…). 형식에서 날짜를 추정하지 않는다
    sku                 varchar(10)  NOT NULL,
    source_lot_ref      varchar(128),                      -- 상류(제조사·WMS) LOT 표기 원문. 덮어쓰지 않는다
    expiry_date         date,                              -- 소비기한. 없으면 NULL
    tracking            varchar(10)  NOT NULL,             -- TRACKED / UNTRACKED(추적 미확인 참조)
    origin_ref          varchar(128) NOT NULL,             -- 처음 만든 사건(receiptId·MIGRATION 등)
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_stock_lot PRIMARY KEY (lot_id),
    CONSTRAINT ck_stock_lot_tracking CHECK (tracking IN ('TRACKED', 'UNTRACKED')),
    CONSTRAINT ck_stock_lot_untracked CHECK (tracking = 'TRACKED' OR (source_lot_ref IS NULL AND expiry_date IS NULL))
);
-- 같은 SKU·원문·소비기한이면 같은 LOT(추적 LOT 만). 원문·기한 중 하나가 없어도 같은 조합이면 같다.
CREATE UNIQUE INDEX uq_stock_lot_identity ON stock_lot (sku, source_lot_ref, expiry_date) NULLS NOT DISTINCT
    WHERE tracking = 'TRACKED';

CREATE TABLE stock_lot_balance (
    mfc_id              varchar(32)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot_id              varchar(40)  NOT NULL,
    on_hand             integer      NOT NULL DEFAULT 0,   -- H
    quarantined         integer      NOT NULL DEFAULT 0,   -- Q
    sellable            integer      NOT NULL DEFAULT 0,   -- E
    reserved            integer      NOT NULL DEFAULT 0,   -- R
    first_normal_at     timestamptz,                       -- 이 센터 최초 정상 인수 시각(FEFO 동률 기준). 이동·추가 수령으로 바꾸지 않는다
    row_version         bigint       NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_stock_lot_balance PRIMARY KEY (mfc_id, sku, lot_id),
    CONSTRAINT fk_stock_lot_balance_lot FOREIGN KEY (lot_id) REFERENCES stock_lot (lot_id),
    CONSTRAINT ck_stock_lot_balance_invariant CHECK (
        reserved >= 0 AND reserved <= sellable AND sellable <= on_hand
        AND quarantined >= 0 AND quarantined <= on_hand AND sellable + quarantined <= on_hand)
);

CREATE TABLE stock_unit (
    mfc_id              varchar(32)  NOT NULL,
    location_code       varchar(64)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot_id              varchar(40)  NOT NULL,
    quality_state       varchar(20)  NOT NULL,
    work_state          varchar(24)  NOT NULL,
    qty                 integer      NOT NULL,
    row_version         bigint       NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_stock_unit PRIMARY KEY (mfc_id, location_code, sku, lot_id, quality_state, work_state),
    CONSTRAINT fk_stock_unit_lot FOREIGN KEY (lot_id) REFERENCES stock_lot (lot_id),
    CONSTRAINT ck_stock_unit_qty CHECK (qty >= 0),
    CONSTRAINT ck_stock_unit_quality CHECK (quality_state IN ('PENDING_INSPECTION', 'NORMAL', 'QUARANTINED', 'DISPOSAL_PENDING')),
    CONSTRAINT ck_stock_unit_work CHECK (work_state IN ('UNSTOWED', 'STORED', 'PICKED', 'PACKED', 'CANCEL_RETURN_PENDING'))
);
CREATE INDEX ix_stock_unit_lot ON stock_unit (mfc_id, sku, lot_id);

-- 센터 요약에도 Q·E 가 H 를 넘지 않게(V3 CHECK 보완)
ALTER TABLE stock_balance ADD CONSTRAINT ck_stock_balance_partition CHECK (sellable + quarantined <= on_hand);

-- 원장에 단위 키(요약 수준 기록 — 예약 R — 은 위치·상태가 없다)
ALTER TABLE stock_ledger_entry
    ADD COLUMN lot_id        varchar(40),
    ADD COLUMN location_code varchar(64),
    ADD COLUMN quality_state varchar(20),
    ADD COLUMN work_state    varchar(24);
ALTER TABLE stock_commit ALTER COLUMN commit_type TYPE varchar(24);

-- 수령 LOT 결과(inbound 가 검수·적치에 쓸 lot_id 를 조회한다)
CREATE TABLE stock_receipt_line (
    receipt_id          varchar(128) NOT NULL,
    line_no             integer      NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot_id              varchar(40)  NOT NULL,
    location_code       varchar(64)  NOT NULL,
    normal_qty          integer      NOT NULL,
    quarantined_qty     integer      NOT NULL,
    pending_qty         integer      NOT NULL,
    CONSTRAINT pk_stock_receipt_line PRIMARY KEY (receipt_id, line_no),
    CONSTRAINT fk_stock_receipt_line FOREIGN KEY (receipt_id) REFERENCES stock_receipt (receipt_id)
);

-- 작업 실행 중복 방지(IV-13: 같은 execution_id 는 요청 ID 가 달라도 한 번)
CREATE TABLE stock_execution (
    execution_id        varchar(128) NOT NULL,
    mfc_id              varchar(32)  NOT NULL,
    execution_type      varchar(16)  NOT NULL,
    content_hash        char(64)     NOT NULL,
    commit_id           varchar(40)  NOT NULL,
    applied_qty         integer      NOT NULL,
    posted_at           timestamptz  NOT NULL,
    CONSTRAINT pk_stock_execution PRIMARY KEY (execution_id)
);

-- 예약 조각(ReservationPart). 상태가 다른 수량은 조각을 나눠 합계를 보존한다.
CREATE TABLE reservation_part (
    part_id             varchar(64)  NOT NULL,
    reservation_id      varchar(40)  NOT NULL,
    order_line_id       varchar(64),
    sku                 varchar(10)  NOT NULL,
    lot_id              varchar(40)  NOT NULL,
    kind                varchar(8)   NOT NULL,
    qty                 integer      NOT NULL,
    state               varchar(16)  NOT NULL,
    blocked_reason      varchar(64),
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_reservation_part PRIMARY KEY (part_id),
    CONSTRAINT fk_reservation_part FOREIGN KEY (reservation_id) REFERENCES reservation (reservation_id),
    CONSTRAINT fk_reservation_part_lot FOREIGN KEY (lot_id) REFERENCES stock_lot (lot_id),
    CONSTRAINT ck_reservation_part_qty CHECK (qty > 0),
    CONSTRAINT ck_reservation_part_kind CHECK (kind IN ('HAND', 'FUTURE')),
    CONSTRAINT ck_reservation_part_state CHECK (state IN ('HELD', 'CONFIRMED', 'RELEASED', 'EXPIRED', 'BLOCKED', 'CONSUMED'))
);
CREATE INDEX ix_reservation_part_reservation ON reservation_part (reservation_id);
CREATE INDEX ix_reservation_part_lot ON reservation_part (lot_id) WHERE state IN ('HELD', 'CONFIRMED');

ALTER TABLE reservation DROP CONSTRAINT ck_reservation_state;
ALTER TABLE reservation ADD CONSTRAINT ck_reservation_state
    CHECK (state IN ('HELD', 'CONFIRMED', 'RELEASED', 'EXPIRED', 'BLOCKED', 'CONSUMED'));
ALTER TABLE reservation ADD COLUMN promised_date date;  -- 소비기한 적격 기준일(없으면 예약 날짜)

-- ---------- 기존 데이터 이관 ----------
INSERT INTO stock_lot (lot_id, sku, source_lot_ref, expiry_date, tracking, origin_ref, created_at)
SELECT DISTINCT 'LOT-MIG-' || b.mfc_id || '-' || b.sku, b.sku, NULL::varchar, NULL::date, 'UNTRACKED', 'MIGRATION', now()
FROM stock_balance b;

INSERT INTO stock_lot_balance (mfc_id, sku, lot_id, on_hand, quarantined, sellable, reserved, first_normal_at, row_version, updated_at)
SELECT b.mfc_id, b.sku, 'LOT-MIG-' || b.mfc_id || '-' || b.sku, b.on_hand, b.quarantined, b.sellable, b.reserved,
       CASE WHEN b.sellable > 0 THEN b.updated_at END, 1, now()
FROM stock_balance b;

-- 정상 판매 가능분: NORMAL/STORED, 격리분: QUARANTINED/UNSTOWED, 나머지(미판정): PENDING_INSPECTION/UNSTOWED
INSERT INTO stock_unit (mfc_id, location_code, sku, lot_id, quality_state, work_state, qty, row_version, updated_at)
SELECT b.mfc_id, 'MIGRATION', b.sku, 'LOT-MIG-' || b.mfc_id || '-' || b.sku, s.q, s.w, s.qty, 1, now()
FROM stock_balance b
CROSS JOIN LATERAL (VALUES
    ('NORMAL', 'STORED', b.sellable),
    ('QUARANTINED', 'UNSTOWED', b.quarantined),
    ('PENDING_INSPECTION', 'UNSTOWED', b.on_hand - b.sellable - b.quarantined)) AS s(q, w, qty)
WHERE s.qty > 0;

INSERT INTO reservation_part (part_id, reservation_id, order_line_id, sku, lot_id, kind, qty, state, created_at, updated_at)
SELECT l.reservation_id || ':' || l.sku, l.reservation_id, NULL, l.sku, 'LOT-MIG-' || r.mfc_id || '-' || l.sku, 'HAND', l.qty,
       r.state, r.created_at, r.updated_at
FROM reservation_line l JOIN reservation r ON r.reservation_id = l.reservation_id;
-- 예약은 됐는데 잔량 행이 없던 SKU 는 없다(V3 예약은 잔량 행 잠금 뒤 R+q). 있으면 FK 오류로 이관이 멈춘다 — 조용히 넘기지 않는다.
