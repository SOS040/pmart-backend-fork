-- 주문 원장(2026-10-31, 결제 확정 단계 S1 g~j). orchestration 이 PG 승인 대사·자원 확정 뒤 confirmation 을 부르면
-- 초안 스냅샷(품목·배송·견적)을 그대로 옮겨 주문을 만든다. 금액은 초안의 동결 견적 — 승인 금액과 다르면 확정하지 않는다.
CREATE TABLE customer_order (
    order_id               varchar(64)  NOT NULL,
    account_id             varchar(64)  NOT NULL,
    draft_id               varchar(64)  NOT NULL,
    mfc_id                 varchar(40)  NOT NULL,
    status                 varchar(24)  NOT NULL,          -- CONFIRMED (이후 단계: PREPARING …)
    version                integer      NOT NULL DEFAULT 1,
    delivery_mode          varchar(12)  NOT NULL,
    arrival_date           date,                           -- 예약배달 도착 구간(Asia/Seoul)
    arrival_start          varchar(5),
    arrival_end            varchar(5),
    out_of_stock_preference varchar(20) NOT NULL,
    delivery_note          varchar(200),
    shopping_address_id    varchar(64)  NOT NULL,
    quote_version          integer      NOT NULL,
    price_version          integer      NOT NULL,
    item_amount            bigint       NOT NULL,
    delivery_tip_base      bigint       NOT NULL,
    delivery_tip           bigint       NOT NULL,
    delivery_tip_reason    varchar(20)  NOT NULL,
    bag_fee                bigint       NOT NULL,
    total_amount           bigint       NOT NULL,
    first_purchase_applied boolean      NOT NULL,          -- delivery_tip_reason = FIRST_PURCHASE
    weight_g               integer      NOT NULL,
    planned_shipments      integer      NOT NULL,
    payment_attempt_id     varchar(64)  NOT NULL,
    pg_transaction_id      varchar(128) NOT NULL,
    approved_amount        bigint       NOT NULL,
    reservation_id         varchar(64)  NOT NULL,
    capacity_hold_id       varchar(64),
    slot_hold_id           varchar(64),
    confirmed_at           timestamptz  NOT NULL,
    CONSTRAINT pk_customer_order PRIMARY KEY (order_id),
    CONSTRAINT uq_customer_order_draft UNIQUE (draft_id)     -- 초안 하나에 확정 주문 하나
);
CREATE INDEX ix_customer_order_account ON customer_order (account_id, confirmed_at DESC, order_id DESC);

-- 주문행. orderLineId = <orderId>-L<n>(초안 품목 순서, 결제 진입 때 사가에 보낸 값과 같다).
CREATE TABLE customer_order_line (
    order_line_id             varchar(80)  NOT NULL,
    order_id                  varchar(64)  NOT NULL,
    line_no                   integer      NOT NULL,
    cart_item_id              varchar(64),
    sku                       varchar(32)  NOT NULL,
    product_id                varchar(40),
    units_per_sku             integer      NOT NULL,
    quantity                  integer      NOT NULL,
    unit_price                bigint       NOT NULL,
    line_amount               bigint       NOT NULL,
    discount_share            bigint       NOT NULL DEFAULT 0,
    weight_g                  integer      NOT NULL,        -- 행 전체 중량(단위 중량 × 수량)
    original_qty              integer      NOT NULL,
    unfulfilled_qty           integer      NOT NULL,
    handed_over_qty           integer      NOT NULL DEFAULT 0,
    cancelled_before_ship_qty integer      NOT NULL DEFAULT 0,
    delivered_qty             integer      NOT NULL DEFAULT 0,
    returned_qty              integer      NOT NULL DEFAULT 0,
    CONSTRAINT pk_customer_order_line PRIMARY KEY (order_line_id),
    CONSTRAINT fk_customer_order_line_order FOREIGN KEY (order_id) REFERENCES customer_order (order_id),
    CONSTRAINT uq_customer_order_line_no UNIQUE (order_id, line_no)
);

-- 주문 확정·초안 조회가 주문 ID 로 초안을 찾는다
CREATE INDEX ix_order_draft_order ON order_draft (order_id);
