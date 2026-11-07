-- 로컬 공급 요청(WK-14, 2026-11-08). 요청 원장만 — 승인·이송(TRF)은 replenishment(SCM). 제출 즉시 SUBMITTED + SupplyRequestSubmitted,
-- replenishment 수신 확인(SupplyRequestReceived)이 오면 ACKNOWLEDGED(아직 생산자 없음). 변경은 ACK 전까지 새 버전, 철회는 ACK 전까지.
CREATE TABLE supply_request (
    supply_request_id   varchar(64)  NOT NULL,
    mfc_id              varchar(32)  NOT NULL,
    state               varchar(16)  NOT NULL,
    version             integer      NOT NULL,
    lines               jsonb        NOT NULL,   -- [{sku, qty}]
    requested_ready_at  timestamptz  NOT NULL,
    urgency             varchar(8)   NOT NULL,
    reason              varchar(500) NOT NULL,
    recommendation_ref  varchar(128),
    submitted_by        varchar(64)  NOT NULL,
    submitted_at        timestamptz  NOT NULL,
    acknowledged_at     timestamptz,
    linked_trf_id       varchar(64),
    withdraw_reason     varchar(200),
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_supply_request PRIMARY KEY (supply_request_id),
    CONSTRAINT ck_supply_request_state CHECK (state IN ('DRAFT', 'SUBMITTED', 'ACKNOWLEDGED', 'WITHDRAWN')),
    CONSTRAINT ck_supply_request_urgency CHECK (urgency IN ('NORMAL', 'URGENT'))
);
CREATE INDEX ix_supply_request_open ON supply_request (mfc_id) WHERE state IN ('SUBMITTED', 'ACKNOWLEDGED');
