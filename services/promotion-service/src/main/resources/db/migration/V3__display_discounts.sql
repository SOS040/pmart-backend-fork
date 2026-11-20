-- 할인가 표시(2026-11-21 ADR-0082 4단계). 화면(검색·홈·상세·장바구니)에 보일 SKU 별 현재 기간 할인.
-- 가장 큰 할인 1개를 고르려면 기본가가 있어야 해서(정률·정액 비교) pricing PriceVersionEffective 사본을 둔다.

CREATE TABLE sku_price (
    sku                 varchar(10)  NOT NULL,
    price               bigint       NOT NULL,
    price_version       integer      NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_sku_price PRIMARY KEY (sku)
);

-- 마지막으로 알린(SkuDisplayDiscountChanged) 표시 할인. 할인이 없어지면 unit_discount = 0 행으로 남긴다(seq 이어 가기).
CREATE TABLE sku_display_discount (
    sku                 varchar(10)  NOT NULL,
    seq                 integer      NOT NULL,
    price_version       integer      NOT NULL,
    base_price          bigint       NOT NULL,
    unit_discount       bigint       NOT NULL,
    sale_price          bigint       NOT NULL,
    rate_percent        integer      NOT NULL,
    promotion_id        varchar(40),
    promotion_name      varchar(60),
    ends_at             timestamptz,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_sku_display_discount PRIMARY KEY (sku),
    CONSTRAINT ck_sku_display_discount_amount CHECK (unit_discount >= 0 AND sale_price = base_price - unit_discount)
);
CREATE INDEX ix_sku_display_discount_live ON sku_display_discount (sku) WHERE unit_discount > 0;
