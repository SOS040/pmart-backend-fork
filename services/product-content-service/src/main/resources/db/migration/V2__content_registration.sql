-- 상품 콘텐츠 등록(임시 백오피스) — docs/architecture/product-registration-design.md §5.2, ADR-0014·0016.
-- 초안·이미지는 운영자 입력, 게시본(product_content)은 DISPLAY 승인(ApprovalDecided) 결과로만 만든다. 적용된 파일은 수정하지 않는다.
-- 이미지 본체는 S3 호환 저장소에 있고 여기에는 버킷·키·메타만 둔다.

-- ---------- product-service 사본 (ProductVersionPublished 투영) ----------
CREATE TABLE product_projection (
    product_id              varchar(40)  NOT NULL,
    latest_product_version  integer      NOT NULL,
    sale_state              varchar(24),
    primary_category_id     varchar(64),
    category_paths          jsonb        NOT NULL,      -- categoryId 경로 (루트→리프, / 구분)
    skus                    jsonb        NOT NULL,      -- [{sku, unitType, unitsPerSku}]
    effective_at            timestamptz,
    received_at             timestamptz  NOT NULL,
    CONSTRAINT pk_product_projection PRIMARY KEY (product_id)
);

-- 원천 키(seed-v1 등) → 상품. 초안을 상품에 연결하는 기준(ProductVersionPublished.sources)
CREATE TABLE product_source_ref (
    source_system           varchar(64)  NOT NULL,
    source_key              varchar(128) NOT NULL,
    product_id              varchar(40)  NOT NULL,
    updated_at              timestamptz  NOT NULL,
    CONSTRAINT pk_product_source_ref PRIMARY KEY (source_system, source_key)
);

-- ---------- 초안·이미지 ----------
CREATE TABLE content_draft (
    draft_id                varchar(40)  NOT NULL,
    source_system           varchar(64),
    source_key              varchar(128),
    product_id              varchar(40),                -- 상품 승인 후 연결(NULL 이면 제출 불가)
    state                   varchar(16)  NOT NULL,      -- DRAFT / SUBMITTED / PUBLISHED / REJECTED
    name                    varchar(200),
    description             text,
    storage_guide           text,
    return_guide            text,
    notices                 jsonb        NOT NULL,      -- ["항목: 값", ...]
    options                 jsonb        NOT NULL,      -- [{label, capacityLabel}]
    content_hash            varchar(64)  NOT NULL,      -- 입력+이미지(sha256) 해시. 재적재 시 바뀐 것이 없는지 판단
    draft_hash              varchar(64),                -- 제출 시 잠금(승인 행과 대조)
    approval_id             varchar(40),
    content_version         integer,                    -- 게시된 버전
    problems                jsonb        NOT NULL,      -- 반려 사유
    version                 integer      NOT NULL,
    created_by              varchar(128) NOT NULL,
    created_at              timestamptz  NOT NULL,
    updated_at              timestamptz  NOT NULL,
    CONSTRAINT pk_content_draft PRIMARY KEY (draft_id),
    CONSTRAINT ck_content_draft_state CHECK (state IN ('DRAFT', 'SUBMITTED', 'PUBLISHED', 'REJECTED'))
);
-- 원천 키 upsert 기준: 열린 초안은 원천 키당 하나
CREATE UNIQUE INDEX ux_content_draft_source_open ON content_draft (source_system, source_key)
    WHERE source_key IS NOT NULL AND state IN ('DRAFT', 'SUBMITTED');
CREATE INDEX ix_content_draft_source ON content_draft (source_system, source_key, created_at);
CREATE INDEX ix_content_draft_product ON content_draft (product_id);

CREATE TABLE content_image (
    image_id                varchar(40)  NOT NULL,
    draft_id                varchar(40)  NOT NULL,
    role                    varchar(12)  NOT NULL,      -- MAIN / DESCRIPTION / DETAIL
    sort_order              integer      NOT NULL,
    bucket                  varchar(63)  NOT NULL,
    object_key              varchar(400) NOT NULL,
    content_type            varchar(32)  NOT NULL,
    width                   integer,
    height                  integer,
    bytes                   bigint       NOT NULL,
    sha256                  char(64)     NOT NULL,      -- 같은 해시는 저장소에 다시 올리지 않는다
    ocr_text                text,
    created_at              timestamptz  NOT NULL,
    CONSTRAINT pk_content_image PRIMARY KEY (image_id),
    CONSTRAINT fk_content_image_draft FOREIGN KEY (draft_id) REFERENCES content_draft (draft_id),
    CONSTRAINT ux_content_image_slot UNIQUE (draft_id, role, sort_order),
    CONSTRAINT ck_content_image_role CHECK (role IN ('MAIN', 'DESCRIPTION', 'DETAIL')),
    CONSTRAINT ck_content_image_main CHECK (role <> 'MAIN' OR sort_order = 0),
    CONSTRAINT ck_content_image_sort CHECK (sort_order >= 0)
);
CREATE INDEX ix_content_image_sha ON content_image (sha256);

-- ---------- 게시본 (불변, 상품별 버전) ----------
CREATE TABLE product_content (
    product_id              varchar(40)  NOT NULL,
    content_version         integer      NOT NULL,
    product_version         integer,                    -- 게시 시점에 받은 product 버전
    draft_id                varchar(40)  NOT NULL,
    approval_id             varchar(40)  NOT NULL,
    name                    varchar(200) NOT NULL,
    description             text,
    storage_guide           text,
    return_guide            text,
    notices                 jsonb        NOT NULL,
    options                 jsonb        NOT NULL,
    images                  jsonb        NOT NULL,      -- [{imageId, role, sortOrder}] (content_image 행을 가리킨다)
    published_at            timestamptz  NOT NULL,
    CONSTRAINT pk_product_content PRIMARY KEY (product_id, content_version),
    CONSTRAINT fk_product_content_draft FOREIGN KEY (draft_id) REFERENCES content_draft (draft_id)
);

-- ---------- 배치 ----------
CREATE TABLE content_import_batch (
    batch_id                varchar(40)  NOT NULL,
    format                  varchar(16)  NOT NULL,      -- SEED_V1
    source_system           varchar(64)  NOT NULL,
    source_name             varchar(200),
    bundle_sha256           char(64)     NOT NULL,
    state                   varchar(24)  NOT NULL,      -- UPLOADED / SUBMITTED / PUBLISHED / PARTIALLY_PUBLISHED / REJECTED
    product_count           integer      NOT NULL,
    created_count           integer      NOT NULL,
    updated_count           integer      NOT NULL,
    unchanged_count         integer      NOT NULL,
    image_count             integer      NOT NULL,
    uploaded_image_count    integer      NOT NULL,
    created_by              varchar(128) NOT NULL,
    created_at              timestamptz  NOT NULL,
    CONSTRAINT pk_content_import_batch PRIMARY KEY (batch_id)
);

-- 배치의 상품별 결과(같은 초안이 여러 배치에 나올 수 있다)
CREATE TABLE content_import_batch_item (
    batch_id                varchar(40)  NOT NULL,
    source_key              varchar(128) NOT NULL,
    draft_id                varchar(40)  NOT NULL,
    outcome                 varchar(12)  NOT NULL,      -- CREATED / UPDATED / UNCHANGED / LOCKED
    CONSTRAINT pk_content_import_batch_item PRIMARY KEY (batch_id, source_key),
    CONSTRAINT fk_content_import_batch_item_batch FOREIGN KEY (batch_id) REFERENCES content_import_batch (batch_id),
    CONSTRAINT fk_content_import_batch_item_draft FOREIGN KEY (draft_id) REFERENCES content_draft (draft_id)
);

-- 같은 승인의 재적용 방지
CREATE TABLE approval_application (
    approval_id             varchar(40)  NOT NULL,
    target_ref              varchar(128) NOT NULL,
    result                  varchar(24)  NOT NULL,      -- PUBLISHED / PARTIALLY_PUBLISHED / REJECTED / NOTHING_PUBLISHED / IGNORED
    detail                  jsonb        NOT NULL,
    applied_at              timestamptz  NOT NULL,
    CONSTRAINT pk_approval_application PRIMARY KEY (approval_id)
);
