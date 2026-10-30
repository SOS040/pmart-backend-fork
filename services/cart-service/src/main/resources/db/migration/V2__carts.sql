-- 장바구니(2026-10-30, 고객앱 우선 범위 ④). 회원(MEMBER=accountId)·비회원(GUEST=비회원 토큰 주체) 공통.
-- 수량은 절대값, cart.version(=cartVersion)으로 충돌 판정. 마지막 변경 후 30일 보존(지나면 EXPIRED, 새 장바구니).
-- 담기는 재고 확보·가격 보장이 아니다(가격·판매 상태·이름은 조회 때 각 정본에서 읽는다).

CREATE TABLE cart (
    cart_id             varchar(64)  NOT NULL,
    owner_type          varchar(8)   NOT NULL,          -- MEMBER / GUEST
    owner_id            varchar(64)  NOT NULL,
    state               varchar(12)  NOT NULL,          -- ACTIVE / EXPIRED
    device_id           varchar(128),                   -- 기록용
    shopping_address_id varchar(64),
    mfc_id              varchar(64),
    version             integer      NOT NULL DEFAULT 0,
    created_at          timestamptz  NOT NULL,
    last_changed_at     timestamptz  NOT NULL,
    CONSTRAINT pk_cart PRIMARY KEY (cart_id)
);
-- 주체마다 진행 중 장바구니 하나
CREATE UNIQUE INDEX ux_cart_active_owner ON cart (owner_type, owner_id) WHERE state = 'ACTIVE';

CREATE TABLE cart_item (
    cart_item_id varchar(64)  NOT NULL,
    cart_id      varchar(64)  NOT NULL,
    sku          varchar(32)  NOT NULL,
    product_id   varchar(40),
    quantity     integer      NOT NULL,
    selected     boolean      NOT NULL DEFAULT true,
    added_at     timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    CONSTRAINT pk_cart_item PRIMARY KEY (cart_item_id),
    CONSTRAINT fk_cart_item_cart FOREIGN KEY (cart_id) REFERENCES cart (cart_id),
    CONSTRAINT uq_cart_item_sku UNIQUE (cart_id, sku),
    CONSTRAINT ck_cart_item_qty CHECK (quantity > 0)
);

-- 변경 요청 원결과(REQUEST_RESULT_UNKNOWN 해소용 GET change-requests/{requestId})
CREATE TABLE cart_change (
    cart_id    varchar(64)  NOT NULL,
    request_id varchar(128) NOT NULL,
    result     jsonb        NOT NULL,
    created_at timestamptz  NOT NULL,
    CONSTRAINT pk_cart_change PRIMARY KEY (cart_id, request_id)
);

-- 비회원 → 회원 병합 결과(항목별)
CREATE TABLE cart_merge (
    merge_id       varchar(64)  NOT NULL,
    target_cart_id varchar(64)  NOT NULL,
    source_cart_id varchar(64)  NOT NULL,
    state          varchar(16)  NOT NULL,          -- MERGE_CONFIRMED / MERGE_PARTIAL / MERGE_REJECTED
    result         jsonb        NOT NULL,
    created_at     timestamptz  NOT NULL,
    CONSTRAINT pk_cart_merge PRIMARY KEY (merge_id)
);
CREATE INDEX ix_cart_merge_target ON cart_merge (target_cart_id);
