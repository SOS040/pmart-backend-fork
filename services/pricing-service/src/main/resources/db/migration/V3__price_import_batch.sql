-- 가격 일괄 등록(seed-v1 기준파일 basePrice → 운영자 설정 → PRICE 승인 요청 한 건). pricing-service.yaml /price-import-batches.
-- 가격 버전은 여전히 ApprovalDecided(PRICE) 로만 만든다. 이 표는 승인 요청을 만들기 전의 설정과 승인 결과 연결만 담는다.

CREATE TABLE price_import_batch (
    batch_id             varchar(40)  NOT NULL,          -- PB-<uuid>
    format               varchar(16)  NOT NULL,          -- SEED_V1
    source_system        varchar(64)  NOT NULL,          -- 묶음 source.system (원천 키 조회 기준)
    source_name          varchar(255),                   -- 올린 파일 이름
    state                varchar(20)  NOT NULL,
    version              integer      NOT NULL,
    effective_from       timestamptz,                    -- 제출 때 정한 공통 효력 시각(null = 승인 반영 즉시)
    approval_target_ref  varchar(80),                    -- 제출 뒤 priceImportBatch:{batchId}
    approval_id          varchar(64),                    -- ApprovalDecided 를 받은 뒤
    checked_at           timestamptz  NOT NULL,          -- SKU·현재 가격 마지막 대조
    created_by           varchar(128) NOT NULL,
    created_at           timestamptz  NOT NULL,
    submitted_at         timestamptz,
    CONSTRAINT pk_price_import_batch PRIMARY KEY (batch_id),
    CONSTRAINT ck_price_import_batch_state
        CHECK (state IN ('DRAFT', 'SUBMITTED', 'APPLIED', 'PARTIALLY_APPLIED', 'REJECTED'))
);

-- 행 = 묶음 상품 하나(seedProductId). sku·current_price 는 대조 시각의 사본(정본은 product-service·price_version).
CREATE TABLE price_import_line (
    batch_id             varchar(40)  NOT NULL,
    seed_product_id      varchar(128) NOT NULL,
    line_no              integer      NOT NULL,          -- 묶음 순서
    product_name         varchar(300),
    product_id           varchar(64),
    sku                  varchar(10),
    bundle_price         bigint,                         -- 묶음 price.basePrice 원래 값
    base_price           bigint,                         -- 제출할 가격(운영자가 고쳤으면 그 값)
    edited               boolean      NOT NULL,
    excluded             boolean      NOT NULL,
    current_price        bigint,
    change_type          varchar(12),                    -- NEW / CHANGE / UNCHANGED
    status               varchar(16)  NOT NULL,          -- READY / EXCLUDED / UNCHANGED / SKU_NOT_FOUND / PRICE_REQUIRED / DUPLICATE_SKU
    CONSTRAINT pk_price_import_line PRIMARY KEY (batch_id, seed_product_id),
    CONSTRAINT fk_price_import_line_batch FOREIGN KEY (batch_id) REFERENCES price_import_batch (batch_id)
);
CREATE INDEX ix_price_import_line_order ON price_import_line (batch_id, line_no);
