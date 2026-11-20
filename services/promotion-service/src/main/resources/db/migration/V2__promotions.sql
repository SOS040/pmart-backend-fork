-- 쿠폰·기간 할인(2026-11-20 ADR-0082). 행사(promotion)는 기간 할인(PRICE_DISCOUNT)과 장바구니 쿠폰(COUPON) 둘.
-- 시각은 UTC timestamptz. 날짜 경계(쿠폰 "받은 날부터 N일")만 Asia/Seoul(설정 pmart.promotion.zone).

CREATE TABLE promotion (
    promotion_id        varchar(40)  NOT NULL,
    kind                varchar(16)  NOT NULL,      -- PRICE_DISCOUNT / COUPON
    name                varchar(60)  NOT NULL,
    state               varchar(12)  NOT NULL,      -- DRAFT / SUBMITTED / APPROVED / REJECTED / ENDED
    starts_at           timestamptz  NOT NULL,
    ends_at             timestamptz  NOT NULL,
    ended_at            timestamptz,                -- 조기 종료 승인 반영 시각
    discount_type       varchar(8)   NOT NULL,      -- RATE / AMOUNT
    discount_value      integer      NOT NULL,
    discount_max        integer,                    -- 정률 쿠폰 최대 할인액
    issue_mode          varchar(8),                 -- COUPON: DOWNLOAD / ADMIN / SIGNUP
    min_order_amount    bigint,
    total_quantity      integer,
    per_customer_limit  integer,
    use_days            integer,
    use_until           timestamptz,
    issued_count        integer      NOT NULL DEFAULT 0,
    used_count          integer      NOT NULL DEFAULT 0,
    input               jsonb        NOT NULL,      -- 받은 입력 그대로(해시·화면 되돌려주기)
    draft_hash          varchar(64),                -- 제출 때 잠금(승인 행과 대조)
    approval_id         varchar(64),
    end_hash            varchar(64),                -- 조기 종료 제출 잠금
    end_reason          varchar(200),
    version             integer      NOT NULL,
    created_by          varchar(128) NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_promotion PRIMARY KEY (promotion_id),
    CONSTRAINT ck_promotion_kind CHECK (kind IN ('PRICE_DISCOUNT', 'COUPON')),
    CONSTRAINT ck_promotion_state CHECK (state IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'ENDED')),
    CONSTRAINT ck_promotion_issued CHECK (issued_count >= 0 AND (total_quantity IS NULL OR issued_count <= total_quantity))
);
CREATE INDEX ix_promotion_updated ON promotion (updated_at DESC, promotion_id DESC);
CREATE INDEX ix_promotion_live ON promotion (kind, state, starts_at, ends_at);

-- 기간 할인 대상(SKU 또는 카테고리 — 카테고리는 그 아래 상품 전부)
CREATE TABLE promotion_target (
    promotion_id        varchar(40)  NOT NULL,
    target_type         varchar(8)   NOT NULL,      -- SKU / CATEGORY
    target_id           varchar(64)  NOT NULL,
    CONSTRAINT pk_promotion_target PRIMARY KEY (promotion_id, target_type, target_id)
);
CREATE INDEX ix_promotion_target_lookup ON promotion_target (target_type, target_id);

-- 상품 사본: SKU → 노출 카테고리(상위 포함). product-service ProductVersionPublished 를 받아 채운다(카테고리 행사 판정용)
CREATE TABLE sku_category (
    sku                 varchar(10)  NOT NULL,
    category_id         varchar(64)  NOT NULL,
    CONSTRAINT pk_sku_category PRIMARY KEY (sku, category_id)
);
CREATE INDEX ix_sku_category_category ON sku_category (category_id);
CREATE TABLE sku_product (
    sku                 varchar(10)  NOT NULL,
    product_id          varchar(40)  NOT NULL,
    product_version     integer      NOT NULL,
    CONSTRAINT pk_sku_product PRIMARY KEY (sku)
);

-- 고객 쿠폰
CREATE TABLE coupon (
    coupon_id           varchar(40)  NOT NULL,
    promotion_id        varchar(40)  NOT NULL,
    account_id          varchar(64)  NOT NULL,
    state               varchar(10)  NOT NULL,      -- AVAILABLE / HELD / USED (만료는 valid_until 로 판정)
    issued_at           timestamptz  NOT NULL,
    valid_until         timestamptz  NOT NULL,
    issue_channel       varchar(8)   NOT NULL,      -- DOWNLOAD / ADMIN / SIGNUP
    issued_by           varchar(128),               -- 지정 발급 직원
    issue_reason        varchar(200),
    order_id            varchar(64),                -- 점유·사용 중인 주문
    used_at             timestamptz,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_coupon PRIMARY KEY (coupon_id),
    CONSTRAINT fk_coupon_promotion FOREIGN KEY (promotion_id) REFERENCES promotion (promotion_id),
    CONSTRAINT ck_coupon_state CHECK (state IN ('AVAILABLE', 'HELD', 'USED'))
);
CREATE INDEX ix_coupon_account ON coupon (account_id, issued_at DESC);
CREATE INDEX ix_coupon_promotion_account ON coupon (promotion_id, account_id);

-- 쿠폰 점유(주문 1건 = 1개). 할인액은 점유 때 정해 그대로 쓴다
CREATE TABLE coupon_hold (
    hold_id             varchar(40)  NOT NULL,
    coupon_id           varchar(40)  NOT NULL,
    order_id            varchar(64)  NOT NULL,
    account_id          varchar(64)  NOT NULL,
    state               varchar(10)  NOT NULL,      -- HELD / CONFIRMED / RELEASED / RESTORED
    item_amount         bigint       NOT NULL,
    discount_amount     bigint       NOT NULL,
    coupon_usable       boolean,                    -- RESTORED 때 원래 기한이 남았는가
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_coupon_hold PRIMARY KEY (hold_id),
    CONSTRAINT fk_coupon_hold_coupon FOREIGN KEY (coupon_id) REFERENCES coupon (coupon_id),
    CONSTRAINT ck_coupon_hold_state CHECK (state IN ('HELD', 'CONFIRMED', 'RELEASED', 'RESTORED'))
);
-- 한 쿠폰의 살아 있는(HELD·CONFIRMED) 점유는 하나
CREATE UNIQUE INDEX ux_coupon_hold_live ON coupon_hold (coupon_id) WHERE state IN ('HELD', 'CONFIRMED');
CREATE INDEX ix_coupon_hold_order ON coupon_hold (order_id);
