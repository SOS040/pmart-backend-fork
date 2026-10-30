-- 상품 SKU 투영과 센터 취급 ON/OFF — docs/architecture/product-registration-design.md §5.3.
-- 설계 문서와 달리 센터별 OFF 레코드를 미리 만들지 않는다: center-master 미구현이라 센터 목록이 없다. **레코드 없음 = OFF**,
-- 운영자가 취급을 바꿀 때 레코드가 생긴다(기본 OFF·센터 운영자가 켬, 사용자 결정 2026-10-30). 이벤트로는 취급 레코드를 만들지 않는다.
-- 적용된 파일은 수정하지 않는다.

-- product-service 이벤트(SkuIssued·ProductVersionPublished, 토픽 commerce.product.v1)의 조회 사본. 정본은 product-service.
-- product_version 보다 오래된 이벤트는 반영하지 않는다(순서 뒤바뀜 대비).
CREATE TABLE sku_projection (
    sku                 varchar(10)  NOT NULL,          -- E-00000001 / B-00000001
    product_id          varchar(64),
    base_product_id     varchar(64),
    unit_type           varchar(1),                     -- E / B
    units_per_sku       integer,
    weight_g            integer,                        -- 미등록을 0 으로 채우지 않는다(SkuIssued 만 받은 동안 NULL)
    volume_ml           integer,
    storage_type        varchar(16),                    -- AMBIENT / CHILLED / FROZEN
    alcohol             boolean,
    state               varchar(16)  NOT NULL,          -- ACTIVE / INACTIVE
    product_version     integer      NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_sku_projection PRIMARY KEY (sku),
    CONSTRAINT ck_sku_projection_state CHECK (state IN ('ACTIVE', 'INACTIVE'))
);

-- 센터 취급 ON/OFF(센터별 정본). 레코드가 없으면 OFF.
CREATE TABLE center_sku_handling (
    center_id           varchar(32)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    handling_enabled    boolean      NOT NULL DEFAULT false,
    changed_by          varchar(128) NOT NULL,
    reason              varchar(500),
    updated_at          timestamptz  NOT NULL,
    row_version         integer      NOT NULL,          -- 첫 변경 = 1 (레코드 없음 = 0 으로 X-Expected-Version 비교)
    CONSTRAINT pk_center_sku_handling PRIMARY KEY (center_id, sku)
);
CREATE INDEX ix_center_sku_handling_enabled ON center_sku_handling (center_id, handling_enabled, sku);

CREATE TABLE center_sku_handling_history (
    history_id          bigint       GENERATED ALWAYS AS IDENTITY,
    center_id           varchar(32)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    from_enabled        boolean      NOT NULL,          -- 레코드가 없던 경우 false
    to_enabled          boolean      NOT NULL,
    changed_by          varchar(128) NOT NULL,
    reason              varchar(500),
    request_id          varchar(128) NOT NULL,          -- Idempotency-Key
    row_version         integer      NOT NULL,          -- 변경 후 version
    changed_at          timestamptz  NOT NULL,
    CONSTRAINT pk_center_sku_handling_history PRIMARY KEY (history_id)
);
CREATE INDEX ix_center_sku_handling_history_key ON center_sku_handling_history (center_id, sku, changed_at);
