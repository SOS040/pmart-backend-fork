-- 진열 편성(2026-11-20 ADR-0075 ② — BO-05 일반 배너·상품 테마). 초안 → 제출(해시 잠금) → approval DISPLAY 승인 → 새 진열 버전.
-- 본문(body)은 배너·테마 목록 jsonb(LayoutRules.Body). 게시된 버전은 바꾸지 않는다(고치면 새 초안·새 버전).
CREATE TABLE layout_draft (
    draft_id          varchar(48)  NOT NULL,
    state             varchar(16)  NOT NULL, -- DRAFT / SUBMITTED / PUBLISHED / REJECTED
    body              jsonb        NOT NULL,
    version           integer      NOT NULL,
    draft_hash        char(64),
    approval_id       varchar(64),
    layout_version    integer,
    rejection_reason  varchar(64),
    created_by        varchar(64)  NOT NULL,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz,
    CONSTRAINT pk_layout_draft PRIMARY KEY (draft_id),
    CONSTRAINT ck_layout_draft_state CHECK (state IN ('DRAFT', 'SUBMITTED', 'PUBLISHED', 'REJECTED'))
);
CREATE INDEX ix_layout_draft_state ON layout_draft (state, created_at DESC);

-- 게시된 진열 버전(1부터, 0 = 기본 진열). 지금 버전 = 가장 큰 번호. 승인 하나에 버전 하나.
CREATE TABLE layout_version (
    layout_version  integer      NOT NULL,
    draft_id        varchar(48)  NOT NULL,
    approval_id     varchar(64)  NOT NULL,
    body            jsonb        NOT NULL,
    published_at    timestamptz  NOT NULL,
    CONSTRAINT pk_layout_version PRIMARY KEY (layout_version),
    CONSTRAINT ux_layout_version_approval UNIQUE (approval_id)
);

-- 배너 이미지(본문은 S3 버킷 pmart-storefront, 키 banner-images/<imageId>). 이미지 ID 마다 본문이 바뀌지 않는다.
CREATE TABLE banner_image (
    image_id      varchar(48)  NOT NULL,
    object_key    varchar(120) NOT NULL,
    content_type  varchar(32)  NOT NULL,
    size_bytes    bigint       NOT NULL,
    sha256        char(64)     NOT NULL,
    width         integer,
    height        integer,
    uploaded_by   varchar(64)  NOT NULL,
    uploaded_at   timestamptz  NOT NULL,
    CONSTRAINT pk_banner_image PRIMARY KEY (image_id)
);
