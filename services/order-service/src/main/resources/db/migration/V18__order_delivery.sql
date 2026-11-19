-- 고객 주문의 배송 짝(2026-11-19, 계약 0.15.0 — 고객 앱 BR-61). delivery-dispatch DeliveryCreated 로 만든다(고객에게 가는 FORWARD·RESHIPMENT 만),
-- 배정·픽업·전달·실패·픽업 전 취소 사건으로 상태를 앞으로만 옮긴다. 고객 주문 조회 shipments[] 의 원천(배송 현황 화면이 deliveryId 로 tracking 을 연다).
CREATE TABLE order_delivery (
    delivery_id  varchar(64)  NOT NULL,
    order_id     varchar(64)  NOT NULL,
    shipment_id  varchar(80)  NOT NULL,
    type         varchar(16)  NOT NULL,
    state        varchar(16)  NOT NULL,
    state_rank   smallint     NOT NULL,
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    CONSTRAINT pk_order_delivery PRIMARY KEY (delivery_id)
);
CREATE INDEX ix_order_delivery_order ON order_delivery (order_id, created_at);
