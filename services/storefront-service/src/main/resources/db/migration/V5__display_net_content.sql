-- 대표 SKU 내용량 사본(2026-11-19, 계약 0.3.0 — 클라이언트 BR-19 "100g당 가격"). ProductVersionPublished.skus[0].netContent.
-- 단위 가격은 조회 때 가격 사본과 계산한다. NULL = 내용량 없음(단위 가격을 내지 않는다).
ALTER TABLE display_product
    ADD COLUMN net_content_amount integer,
    ADD COLUMN net_content_unit   varchar(4);
