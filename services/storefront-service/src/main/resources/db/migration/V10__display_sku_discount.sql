-- 할인가 표시(2026-11-21 ADR-0082 4단계): promotion-service SkuDisplayDiscountChanged(commerce.sku-display-discount.v1) 사본.
-- seq 가 큰 것만. 진열은 가격 사본과 price_version 이 같고 끝 시각 전일 때만 할인가를 보인다(가격이 막 바뀐 사이에는 정가).
CREATE TABLE display_sku_discount (
    sku                 varchar(10)  NOT NULL,
    seq                 integer      NOT NULL,
    price_version       integer      NOT NULL,
    unit_discount       bigint       NOT NULL,
    sale_price          bigint       NOT NULL,
    rate_percent        integer      NOT NULL,
    promotion_id        varchar(40),
    promotion_name      varchar(60),
    ends_at             timestamptz,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_display_sku_discount PRIMARY KEY (sku)
);
