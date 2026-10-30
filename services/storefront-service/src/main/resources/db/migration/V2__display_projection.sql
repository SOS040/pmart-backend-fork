-- 홈 진열용 상품·가격 투영(2026-10-30, 고객앱 우선 범위 ③). 정본이 아니라 사건 사본이다(상품·콘텐츠·판매 상태·가격).
-- 버전·시각을 비교해 늦게 온 옛 사건은 덮어쓰지 않는다.

CREATE TABLE display_product (
    product_id           varchar(40)  NOT NULL,
    product_version      integer,                       -- ProductVersionPublished
    representative_sku   varchar(32),
    sale_state           varchar(24),                   -- ON_SALE / TEMPORARILY_SOLD_OUT / SALE_STOPPED
    sale_state_at        timestamptz,
    name                 varchar(200),                  -- ProductContentPublished → 공개 카드 API 보강
    image_url            varchar(300),
    content_version      integer,
    content_published_at timestamptz,                   -- 처음 게시 시각(최신순 진열 기준)
    updated_at           timestamptz  NOT NULL,
    CONSTRAINT pk_display_product PRIMARY KEY (product_id)
);
CREATE INDEX ix_display_product_published ON display_product (content_published_at DESC, product_id);

CREATE TABLE display_sku_price (
    sku           varchar(32)  NOT NULL,
    price         bigint       NOT NULL,
    price_version integer      NOT NULL,
    effective_at  timestamptz,
    CONSTRAINT pk_display_sku_price PRIMARY KEY (sku)
);
