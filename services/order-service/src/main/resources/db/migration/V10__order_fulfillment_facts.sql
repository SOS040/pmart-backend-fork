-- 이행 사실 반영 기록(O-1, 2026-11-16): MFC 포장 인계(PackageHandedOver — 키 handoverId)·배송 전달 완료(DeliveryCompleted — 키
-- deliveryId)를 업무 키로 한 번만 반영한다(사건 ID 가 다른 재발행도 막는다). 수량이 맞지 않아 반영하지 못한 사실도 버리지 않고 MISMATCH 로 남긴다(대사).
CREATE TABLE order_fulfillment_fact (
    kind            varchar(16)  NOT NULL,          -- HANDOVER · DELIVERY
    source_ref      varchar(80)  NOT NULL,          -- handoverId · deliveryId
    order_id        varchar(64)  NOT NULL,
    delivery_id     varchar(64),
    source_event_id varchar(64)  NOT NULL,
    state           varchar(16)  NOT NULL,
    detail          varchar(500),
    occurred_at     timestamptz  NOT NULL,          -- handedAt · deliveredAt
    applied_at      timestamptz  NOT NULL,
    CONSTRAINT pk_order_fulfillment_fact PRIMARY KEY (kind, source_ref),
    CONSTRAINT ck_order_fulfillment_fact_kind CHECK (kind IN ('HANDOVER', 'DELIVERY')),
    CONSTRAINT ck_order_fulfillment_fact_state CHECK (state IN ('APPLIED', 'MISMATCH'))
);
CREATE INDEX ix_order_fulfillment_fact_order ON order_fulfillment_fact (order_id, kind);
