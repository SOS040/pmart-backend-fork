-- 리뷰 1차(2026-11-16, 계약 0.2.0, ADR-0063).
-- 자격: DeliveryCompleted 의 품목마다 한 행(첫 수령 시각·받은 수량 누계). 주문 원장 사본(계정·SKU·상품·상품명)은 order 내부 조회로 채운다.
CREATE TABLE review_eligibility (
    order_line_id  varchar(80)  NOT NULL,
    order_id       varchar(64)  NOT NULL,
    account_id     varchar(64)  NOT NULL,
    sku            varchar(32),
    product_id     varchar(40),
    product_name   varchar(200),
    delivered_qty  integer      NOT NULL,
    delivered_at   timestamptz  NOT NULL, -- 그 품목의 첫 수령 시각(14일 기산)
    delivery_id    varchar(80),
    created_at     timestamptz  NOT NULL,
    CONSTRAINT pk_review_eligibility PRIMARY KEY (order_line_id)
);
CREATE INDEX ix_review_eligibility_account ON review_eligibility (account_id, delivered_at DESC);

-- 리뷰: 품목당 한 건(삭제해도 행은 남아 재작성을 막는다). LOCKED·EXPIRED 는 저장하지 않고 시각으로 계산한다.
CREATE TABLE review (
    review_id      varchar(64)  NOT NULL,
    order_line_id  varchar(80)  NOT NULL,
    order_id       varchar(64)  NOT NULL,
    account_id     varchar(64)  NOT NULL,
    product_id     varchar(40)  NOT NULL,
    sku            varchar(32),
    author_name    varchar(40)  NOT NULL, -- 가린 표시 이름(작성 때 customer 에서 한 번)
    rating         smallint     NOT NULL CHECK (rating BETWEEN 1 AND 5),
    body           text,
    state          varchar(16)  NOT NULL, -- PUBLISHED / DELETED
    version        integer      NOT NULL,
    created_at     timestamptz  NOT NULL, -- 최초 등록(수정 기한 기산 — 수정으로 바뀌지 않는다)
    updated_at     timestamptz,
    deleted_at     timestamptz,
    CONSTRAINT pk_review PRIMARY KEY (review_id),
    CONSTRAINT uq_review_order_line UNIQUE (order_line_id)
);
CREATE INDEX ix_review_product ON review (product_id, state, created_at DESC);

-- 사진: 올린 사람만 붙일 수 있다. 24시간 안에 붙지 않거나 수정으로 떨어진 사진은 정리 작업이 지운다.
CREATE TABLE review_photo (
    photo_id       varchar(64)  NOT NULL,
    account_id     varchar(64)  NOT NULL,
    object_key     varchar(200) NOT NULL,
    content_type   varchar(32)  NOT NULL,
    size_bytes     bigint       NOT NULL,
    sha256         varchar(64)  NOT NULL,
    uploaded_at    timestamptz  NOT NULL,
    review_id      varchar(64),
    sort_order     integer,
    detached_at    timestamptz, -- 수정으로 리뷰에서 떨어진 시각(정리 대상)
    CONSTRAINT pk_review_photo PRIMARY KEY (photo_id)
);
CREATE INDEX ix_review_photo_review ON review_photo (review_id, sort_order);
CREATE INDEX ix_review_photo_orphan ON review_photo (uploaded_at) WHERE review_id IS NULL;
