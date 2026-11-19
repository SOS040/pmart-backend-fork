-- SKU 내용량(2026-11-19, 계약 0.4.0 — 클라이언트 BR-19 "100g당 가격"). 판매 단위 전체에 든 양(g·mL 정수).
-- 포장 무게 weight_g(물류)와 다르다. NULL = 미등록(개수로 파는 상품 등 — 단위 가격을 내지 않는다). 둘 다 있거나 둘 다 없다.
ALTER TABLE sku
    ADD COLUMN net_content_amount integer,
    ADD COLUMN net_content_unit   varchar(4),
    ADD CONSTRAINT ck_sku_net_content CHECK (
        (net_content_amount IS NULL AND net_content_unit IS NULL)
        OR (net_content_amount BETWEEN 1 AND 1000000 AND net_content_unit IN ('G', 'ML')));
