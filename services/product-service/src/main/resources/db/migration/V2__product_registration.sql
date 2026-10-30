-- 상품 등록(임시 백오피스) — docs/architecture/product-registration-design.md §5.1, ADR-0001·0014·0015.
-- 카테고리 트리·필터는 버전 단위로 저장하고, 상품·SKU·바코드는 승인(ApprovalDecided PRODUCT) 결과로만 만든다. 적용된 파일은 수정하지 않는다.

-- ---------- 카테고리 트리·필터 (4단계, 노드별 필터 패널) ----------
CREATE TABLE category_tree_version (
    version             integer      NOT NULL,
    state               varchar(16)  NOT NULL,          -- DRAFT / SUBMITTED / EFFECTIVE / SUPERSEDED / REJECTED
    source_note         varchar(500),
    draft_hash          varchar(64)  NOT NULL,          -- 제출 시 잠근 내용 해시(승인 행과 대조)
    approval_id         varchar(40),
    effective_from      timestamptz,
    created_by          varchar(128) NOT NULL,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_category_tree_version PRIMARY KEY (version)
);
-- 발효 버전은 하나
CREATE UNIQUE INDEX ux_category_tree_effective ON category_tree_version ((true)) WHERE state = 'EFFECTIVE';

CREATE TABLE category_node (
    version             integer      NOT NULL,
    category_id         varchar(64)  NOT NULL,
    parent_id           varchar(64),
    level               varchar(8)   NOT NULL,          -- TOP / SUB / CHIP1 / CHIP2
    section             varchar(32),                    -- TOP 만
    name                varchar(100) NOT NULL,
    path                varchar(400) NOT NULL,          -- 이름 경로 "국·탕·찌개 > 국·국밥" (자동 매핑 기준)
    sort_order          integer      NOT NULL,
    exposed             boolean      NOT NULL,
    CONSTRAINT pk_category_node PRIMARY KEY (version, category_id),
    CONSTRAINT fk_category_node_version FOREIGN KEY (version) REFERENCES category_tree_version (version),
    CONSTRAINT ck_category_node_level CHECK (level IN ('TOP', 'SUB', 'CHIP1', 'CHIP2'))
);
CREATE INDEX ix_category_node_path ON category_node (version, path);

CREATE TABLE filter_group (
    version             integer      NOT NULL,
    filter_group_id     varchar(64)  NOT NULL,
    name                varchar(100) NOT NULL,
    selection_mode      varchar(8)   NOT NULL,          -- MULTIPLE / TOGGLE / MIXED
    common              boolean      NOT NULL,
    CONSTRAINT pk_filter_group PRIMARY KEY (version, filter_group_id),
    CONSTRAINT fk_filter_group_version FOREIGN KEY (version) REFERENCES category_tree_version (version)
);

CREATE TABLE filter_option (
    version             integer      NOT NULL,
    filter_group_id     varchar(64)  NOT NULL,
    option_seq          integer      NOT NULL,          -- 화면 순서
    name                varchar(200) NOT NULL,
    CONSTRAINT pk_filter_option PRIMARY KEY (version, filter_group_id, option_seq),
    CONSTRAINT fk_filter_option_group FOREIGN KEY (version, filter_group_id) REFERENCES filter_group (version, filter_group_id)
);

CREATE TABLE category_filter (
    version             integer      NOT NULL,
    category_id         varchar(64)  NOT NULL,
    filter_group_id     varchar(64)  NOT NULL,
    sort_order          integer      NOT NULL,
    CONSTRAINT pk_category_filter PRIMARY KEY (version, category_id, filter_group_id),
    CONSTRAINT fk_category_filter_node FOREIGN KEY (version, category_id) REFERENCES category_node (version, category_id),
    CONSTRAINT fk_category_filter_group FOREIGN KEY (version, filter_group_id) REFERENCES filter_group (version, filter_group_id)
);

-- ---------- 상품·SKU·바코드 (승인 결과로만 생성) ----------
CREATE TABLE base_product (
    base_product_id     varchar(40)  NOT NULL,
    base_product_key    varchar(200),                   -- 초안에서 같은 기초상품을 묶는 키
    internal_name       varchar(200) NOT NULL,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_base_product PRIMARY KEY (base_product_id)
);
CREATE UNIQUE INDEX ux_base_product_key ON base_product (base_product_key) WHERE base_product_key IS NOT NULL;

CREATE TABLE product (
    product_id          varchar(40)  NOT NULL,
    base_product_id     varchar(40)  NOT NULL,
    internal_name       varchar(200) NOT NULL,
    primary_category_id varchar(64)  NOT NULL,
    sale_state          varchar(24)  NOT NULL,          -- ON_SALE / TEMPORARILY_SOLD_OUT / SALE_STOPPED
    current_version     integer      NOT NULL,
    row_version         integer      NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_product PRIMARY KEY (product_id),
    CONSTRAINT fk_product_base FOREIGN KEY (base_product_id) REFERENCES base_product (base_product_id)
);
CREATE INDEX ix_product_category ON product (primary_category_id, product_id);

-- 상품의 노출 카테고리(어느 단계 노드든). 카테고리 트리 버전이 바뀌어도 id 로 연결한다.
CREATE TABLE product_category (
    product_id          varchar(40)  NOT NULL,
    category_id         varchar(64)  NOT NULL,
    is_primary          boolean      NOT NULL,
    CONSTRAINT pk_product_category PRIMARY KEY (product_id, category_id),
    CONSTRAINT fk_product_category_product FOREIGN KEY (product_id) REFERENCES product (product_id)
);
CREATE UNIQUE INDEX ux_product_category_primary ON product_category (product_id) WHERE is_primary;
CREATE INDEX ix_product_category_category ON product_category (category_id, product_id);

CREATE TABLE product_filter_option (
    product_id          varchar(40)  NOT NULL,
    filter_group_id     varchar(64)  NOT NULL,
    option_name         varchar(200) NOT NULL,
    CONSTRAINT pk_product_filter_option PRIMARY KEY (product_id, filter_group_id, option_name),
    CONSTRAINT fk_product_filter_option_product FOREIGN KEY (product_id) REFERENCES product (product_id)
);

-- E/B 공유 전역 일련번호(Commerce 2.7 [확정], BO-03). 시퀀스는 반환·재사용하지 않는다.
CREATE SEQUENCE sku_serial_seq START WITH 1 INCREMENT BY 1 NO CYCLE;

CREATE TABLE sku (
    sku                 varchar(10)  NOT NULL,          -- E-00000001
    serial_no           bigint       NOT NULL,
    unit_type           char(1)      NOT NULL,          -- E / B
    product_id          varchar(40)  NOT NULL,
    base_product_id     varchar(40)  NOT NULL,
    units_per_sku       integer      NOT NULL,
    weight_g            integer      NOT NULL,          -- 미등록을 0 으로 채우지 않는다(초안 검증에서 막음)
    volume_ml           integer,
    storage_type        varchar(8)   NOT NULL,          -- AMBIENT / CHILLED / FROZEN
    alcohol             boolean      NOT NULL,
    state               varchar(8)   NOT NULL,          -- ACTIVE / INACTIVE (삭제 없음)
    issued_approval_id  varchar(40)  NOT NULL,
    issued_at           timestamptz  NOT NULL,
    CONSTRAINT pk_sku PRIMARY KEY (sku),
    CONSTRAINT ux_sku_serial UNIQUE (serial_no),
    CONSTRAINT fk_sku_product FOREIGN KEY (product_id) REFERENCES product (product_id),
    CONSTRAINT ck_sku_unit_type CHECK (unit_type IN ('E', 'B')),
    CONSTRAINT ck_sku_format CHECK (sku = unit_type || '-' || lpad(serial_no::text, 8, '0')),
    CONSTRAINT ck_sku_units CHECK (units_per_sku >= 1),
    CONSTRAINT ck_sku_weight CHECK (weight_g >= 0)
);
CREATE INDEX ix_sku_product ON sku (product_id);
CREATE INDEX ix_sku_base_product ON sku (base_product_id);

-- 제조사 바코드 ↔ SKU. 선행 0 보존(text). 활성 바코드 하나는 SKU 하나만 가리킨다(충돌은 자동 선택하지 않고 거절).
CREATE TABLE sku_barcode (
    barcode_id          varchar(40)  NOT NULL,
    barcode             varchar(64)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    barcode_type        varchar(8)   NOT NULL,          -- GTIN_8 / GTIN_12 / GTIN_13 / GTIN_14 / OTHER (길이로 분류)
    state               varchar(8)   NOT NULL,          -- ACTIVE / RETIRED
    valid_from          timestamptz  NOT NULL,
    valid_to            timestamptz,
    approval_id         varchar(40)  NOT NULL,
    CONSTRAINT pk_sku_barcode PRIMARY KEY (barcode_id),
    CONSTRAINT fk_sku_barcode_sku FOREIGN KEY (sku) REFERENCES sku (sku)
);
CREATE UNIQUE INDEX ux_sku_barcode_active ON sku_barcode (barcode) WHERE state = 'ACTIVE';
CREATE INDEX ix_sku_barcode_sku ON sku_barcode (sku);

-- BOX/PLT 환산(ADR-0001). 과거 버전 보존.
CREATE TABLE sku_logistics_spec (
    sku                 varchar(10)  NOT NULL,
    spec_version        integer      NOT NULL,
    units_per_box       integer,
    boxes_per_pallet    integer,
    ti                  integer,
    hi                  integer,
    approval_id         varchar(40)  NOT NULL,
    effective_from      timestamptz  NOT NULL,
    CONSTRAINT pk_sku_logistics_spec PRIMARY KEY (sku, spec_version),
    CONSTRAINT fk_sku_logistics_spec_sku FOREIGN KEY (sku) REFERENCES sku (sku)
);

-- 승인 버전 스냅샷(불변). /internal/products/{id}/versions/{v} 가 읽는다.
CREATE TABLE product_version (
    product_id          varchar(40)  NOT NULL,
    version             integer      NOT NULL,
    approval_id         varchar(40)  NOT NULL,
    snapshot            jsonb        NOT NULL,
    published_at        timestamptz  NOT NULL,
    CONSTRAINT pk_product_version PRIMARY KEY (product_id, version),
    CONSTRAINT fk_product_version_product FOREIGN KEY (product_id) REFERENCES product (product_id)
);

-- ---------- 등록 초안·배치 ----------
CREATE TABLE product_import_batch (
    batch_id            varchar(40)  NOT NULL,
    format              varchar(16)  NOT NULL,          -- SEED_V1
    source_system       varchar(64)  NOT NULL,
    source_name         varchar(200),
    state               varchar(24)  NOT NULL,          -- VALIDATED / SUBMITTED / APPLIED / PARTIALLY_APPLIED / REJECTED
    product_count       integer      NOT NULL,
    valid_count         integer      NOT NULL,
    invalid_count       integer      NOT NULL,
    created_by          varchar(128) NOT NULL,
    created_at          timestamptz  NOT NULL,
    CONSTRAINT pk_product_import_batch PRIMARY KEY (batch_id)
);

CREATE TABLE product_draft (
    draft_id            varchar(40)  NOT NULL,
    batch_id            varchar(40),
    source_system       varchar(64),
    source_key          varchar(128),
    state               varchar(16)  NOT NULL,          -- DRAFT / SUBMITTED / APPLIED / REJECTED
    validation_state    varchar(8)   NOT NULL,          -- VALID / INVALID
    problems            jsonb        NOT NULL,
    input               jsonb        NOT NULL,          -- ProductDraftInput 원문
    resolved_categories jsonb        NOT NULL,          -- 매핑된 카테고리 id 배열(대표가 첫 번째)
    draft_hash          varchar(64),                    -- 제출 시 잠금
    approval_id         varchar(40),
    result_product_id   varchar(40),
    result_skus         jsonb        NOT NULL,
    version             integer      NOT NULL,
    created_by          varchar(128) NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_product_draft PRIMARY KEY (draft_id),
    CONSTRAINT fk_product_draft_batch FOREIGN KEY (batch_id) REFERENCES product_import_batch (batch_id)
);
-- 원천 키 upsert 기준: 아직 반영되지 않은 초안은 원천 키당 하나
CREATE UNIQUE INDEX ux_product_draft_source_open ON product_draft (source_system, source_key)
    WHERE source_key IS NOT NULL AND state IN ('DRAFT', 'SUBMITTED');
CREATE INDEX ix_product_draft_batch ON product_draft (batch_id);

-- 원천 키 → 발급 결과 (id-map.json, 재적재 시 같은 상품 갱신)
CREATE TABLE product_source_link (
    source_system       varchar(64)  NOT NULL,
    source_key          varchar(128) NOT NULL,
    product_id          varchar(40)  NOT NULL,
    skus                jsonb        NOT NULL,
    last_draft_id       varchar(40)  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_product_source_link PRIMARY KEY (source_system, source_key),
    CONSTRAINT fk_product_source_link_product FOREIGN KEY (product_id) REFERENCES product (product_id)
);

-- 같은 승인의 재적용 방지(ApprovalDecided 재수신은 inbox 가 먼저 막고, 이것은 다른 경로의 이중 반영을 막는다)
CREATE TABLE approval_application (
    approval_id         varchar(40)  NOT NULL,
    target_ref          varchar(128) NOT NULL,
    result              varchar(24)  NOT NULL,          -- APPLIED / REJECTED_SCOPE_MISMATCH / IGNORED
    detail              jsonb        NOT NULL,
    applied_at          timestamptz  NOT NULL,
    CONSTRAINT pk_approval_application PRIMARY KEY (approval_id)
);
