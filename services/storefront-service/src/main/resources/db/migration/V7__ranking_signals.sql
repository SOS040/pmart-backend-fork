-- 상품 순위 신호(2026-11-19 ADR-0072 — 사용자 "추천대로 5번 6번": 별점·리뷰 수 정렬, 인기순).
-- 주문 줄 판매 원장: 주문 확정(OrderConfirmed)으로 줄·확정 시각·수량, 줄 수량 변경(OrderLineQuantityChanged — 누적 값, 주문 버전)으로
-- 순 수량 = 원수량 − 출고 전 취소 − 반품. 주문 버전이 큰 것만. 대표 SKU 로 상품에 묶는다(display_product.representative_sku).
CREATE TABLE sku_sales_line (
    order_line_id  varchar(80)  NOT NULL,
    order_id       varchar(64)  NOT NULL,
    sku            varchar(32)  NOT NULL,
    confirmed_at   timestamptz,
    qty            integer      NOT NULL,
    version        integer      NOT NULL,
    updated_at     timestamptz  NOT NULL,
    CONSTRAINT pk_sku_sales_line PRIMARY KEY (order_line_id)
);
CREATE INDEX ix_sku_sales_line_sku ON sku_sales_line (sku, confirmed_at);

-- 마지막으로 낸 신호(바뀌었을 때만 다시 낸다). version = 상품마다 1부터.
CREATE TABLE product_ranking_signal (
    product_id      varchar(40)   NOT NULL,
    rating_count    integer       NOT NULL,
    rating_average  numeric(3,1),
    rating_score    numeric(6,4),
    sales_window    integer       NOT NULL,
    window_days     integer       NOT NULL,
    version         integer       NOT NULL,
    published_at    timestamptz   NOT NULL,
    CONSTRAINT pk_product_ranking_signal PRIMARY KEY (product_id)
);
