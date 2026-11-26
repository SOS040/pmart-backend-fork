-- 2026-11-26 요청 MI-5(사용자 "구현해"·"전부 여기서"): CDC 이관(TRF) 입고 종결 — "이 TRF 는 더 받을 것 없음".
-- 수령은 TRF 하나에 한 번이라 재고 반영(TIN_POSTED) = 자동 종결(AUTO). 물건이 오지 않은 이관은 센터 관리자가 종결(MANUAL, 예정 CLOSED).
-- 종결은 cdc-inventory POST /internal/transit-closures 로 알린다(트랜잭션 밖, 실패하면 next_try_at 에 다시). TRF 하나에 한 줄.
CREATE TABLE transit_closure (
    trf_id           varchar(128) PRIMARY KEY,
    mfc_id           varchar(64)  NOT NULL,
    from_cdc         varchar(64)  NOT NULL,
    departure_id     varchar(128) NOT NULL,
    kind             varchar(16)  NOT NULL,
    closed_at        timestamptz  NOT NULL,
    closed_by        varchar(128) NOT NULL,
    reason           varchar(500),
    notified_at      timestamptz,
    unreconciled_qty integer,
    attempts         integer      NOT NULL DEFAULT 0,
    last_error       varchar(1000),
    next_try_at      timestamptz,
    CONSTRAINT transit_closure_kind CHECK (kind IN ('AUTO', 'MANUAL'))
);
CREATE INDEX transit_closure_due ON transit_closure (next_try_at) WHERE notified_at IS NULL;
-- 관리자 종결 상태(오지 않은 이관)
ALTER TABLE inbound_promise DROP CONSTRAINT ck_inbound_promise_state;
ALTER TABLE inbound_promise ADD CONSTRAINT ck_inbound_promise_state
    CHECK (state IN ('EXPECTED', 'RECEIVING', 'TIN_POSTED', 'CANCELLED', 'CLOSED'));
