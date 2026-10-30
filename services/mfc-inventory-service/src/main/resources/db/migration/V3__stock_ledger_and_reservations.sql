-- 재고 원장·예약(슬라이스 4, S1 a~d). 최소 범위: 센터·SKU 한 행(LOT·위치·품질 차원 없음 — 후속 슬라이스에서 확장).
-- 수량 기호: H(on_hand 현물), Q(quarantined 격리), E(sellable 판매가능 실물), R(reserved = HELD+CONFIRMED), A = E − R.
-- 불변식 0 ≤ R ≤ E ≤ H, Q ≤ H 는 CHECK 로도 막는다(애플리케이션 검증이 뚫려도 음수·초과 예약이 저장되지 않게).
-- 적용된 파일은 수정하지 않는다.

CREATE TABLE stock_balance (
    mfc_id              varchar(32)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    on_hand             integer      NOT NULL DEFAULT 0,   -- H
    quarantined         integer      NOT NULL DEFAULT 0,   -- Q
    sellable            integer      NOT NULL DEFAULT 0,   -- E
    reserved            integer      NOT NULL DEFAULT 0,   -- R
    row_version         bigint       NOT NULL,             -- 변경마다 +1 (첫 입고 = 1)
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_stock_balance PRIMARY KEY (mfc_id, sku),
    CONSTRAINT ck_stock_balance_invariant CHECK (
        reserved >= 0 AND reserved <= sellable AND sellable <= on_hand
        AND quarantined >= 0 AND quarantined <= on_hand)
);

-- 확정 단위(commitId). 한 사건의 수량 변화(stock_ledger_entry)를 묶는다. 삭제·수정하지 않는다.
CREATE TABLE stock_commit (
    commit_id           varchar(40)  NOT NULL,
    commit_type         varchar(16)  NOT NULL,             -- TIN / RESERVE / CONFIRM / RELEASE / EXPIRE
    mfc_id              varchar(32)  NOT NULL,
    request_id          varchar(128),                      -- Idempotency-Key (만료 작업은 null)
    source_system       varchar(64)  NOT NULL,
    source_ref          varchar(128),                      -- receiptId / reservationId
    applied_qty         integer      NOT NULL,
    occurred_at         timestamptz  NOT NULL,
    CONSTRAINT pk_stock_commit PRIMARY KEY (commit_id)
);
CREATE INDEX ix_stock_commit_request ON stock_commit (source_system, request_id);

CREATE TABLE stock_ledger_entry (
    entry_id            bigint       GENERATED ALWAYS AS IDENTITY,
    commit_id           varchar(40)  NOT NULL,
    mfc_id              varchar(32)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    symbol              varchar(1)   NOT NULL,             -- H / Q / E / R
    delta               integer      NOT NULL,
    balance_version     bigint       NOT NULL,             -- 반영 후 stock_balance.row_version
    CONSTRAINT pk_stock_ledger_entry PRIMARY KEY (entry_id),
    CONSTRAINT fk_stock_ledger_entry_commit FOREIGN KEY (commit_id) REFERENCES stock_commit (commit_id)
);
CREATE INDEX ix_stock_ledger_entry_commit ON stock_ledger_entry (commit_id);
CREATE INDEX ix_stock_ledger_entry_key ON stock_ledger_entry (mfc_id, sku);

-- 수령 중복 계상 방지(receiptId 유일). 같은 receiptId·같은 내용(content_hash)이면 처음 commitId 를 돌려준다.
CREATE TABLE stock_receipt (
    receipt_id          varchar(128) NOT NULL,
    mfc_id              varchar(32)  NOT NULL,
    content_hash        char(64)     NOT NULL,
    commit_id           varchar(40)  NOT NULL,
    applied_qty         integer      NOT NULL,
    posted_at           timestamptz  NOT NULL,
    CONSTRAINT pk_stock_receipt PRIMARY KEY (receipt_id)
);

-- 예약(ReservationPart 의 최소형: 예약 하나에 SKU 별 합산 행). HELD → CONFIRMED / RELEASED / EXPIRED, CONFIRMED → RELEASED.
CREATE TABLE reservation (
    reservation_id      varchar(40)  NOT NULL,
    order_id            varchar(64)  NOT NULL,
    mfc_id              varchar(32)  NOT NULL,
    state               varchar(16)  NOT NULL,
    expires_at          timestamptz,                       -- HELD 일 때만. 확정되면 null
    release_reason      varchar(32),
    row_version         integer      NOT NULL,             -- 생성 = 1, 상태 전이마다 +1
    source_system       varchar(64)  NOT NULL,
    request_id          varchar(128) NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_reservation PRIMARY KEY (reservation_id),
    CONSTRAINT ck_reservation_state CHECK (state IN ('HELD', 'CONFIRMED', 'RELEASED', 'EXPIRED'))
);
CREATE INDEX ix_reservation_order ON reservation (order_id);
-- 만료 작업: HELD 이고 만료 시각이 지난 행을 오래된 순으로(SKIP LOCKED)
CREATE INDEX ix_reservation_held_expiry ON reservation (expires_at) WHERE state = 'HELD';

CREATE TABLE reservation_line (
    reservation_id      varchar(40)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    qty                 integer      NOT NULL,
    CONSTRAINT pk_reservation_line PRIMARY KEY (reservation_id, sku),
    CONSTRAINT fk_reservation_line FOREIGN KEY (reservation_id) REFERENCES reservation (reservation_id),
    CONSTRAINT ck_reservation_line_qty CHECK (qty > 0)
);
