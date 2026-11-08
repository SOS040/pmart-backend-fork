-- 진열 카테고리(2026-11-08 사용자 결정): 마트 진열대처럼 칸에 카테고리를 두고 그 카테고리 상품을 모은다(칸에 여러 상품 가능, 같은 상품은 한 자리).
-- 카테고리가 없는 칸 = 기타 칸(출하 기준 지나 세일 상품으로 따로 올린 것 등 — 아무 상품이나). 값은 상품 카테고리 ID(commerce categoryPaths 의 한 마디).
ALTER TABLE location ADD COLUMN category_id varchar(64);
