-- 진열 카테고리(2026-11-08 사용자 결정): 칸 카테고리(위치 투영) + 상품 카테고리 경로(ProductVersionPublished.categoryPaths).
-- 카테고리 칸에는 그 카테고리(또는 하위) 상품만, 카테고리 없는 칸 = 기타(아무 상품 — 출하 기준 지난 세일 상품 등).
ALTER TABLE location_projection ADD COLUMN category_id varchar(64);

CREATE TABLE sku_category (
    sku                 varchar(10)  NOT NULL,
    category_paths      text         NOT NULL,   -- 줄바꿈으로 구분한 경로(루트→리프, '/' 구분)
    product_version     integer      NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_sku_category PRIMARY KEY (sku)
);
