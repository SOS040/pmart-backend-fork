-- SKU 실물 치수(2026-11-16, 계약 0.2.0). 판매 단위(E 낱개 / B 묶음 전체) 하나의 외곽 세 변(mm) — MFC 칸 용량 계산에 쓴다.
-- NULL = 치수 미등록(0·기본값으로 채우지 않는다). 셋 다 있거나 셋 다 없다. 출처는 MEASURED(실측)·ESTIMATED(추정), 모르면 NULL.
ALTER TABLE sku
    ADD COLUMN width_mm           integer,
    ADD COLUMN depth_mm           integer,
    ADD COLUMN height_mm          integer,
    ADD COLUMN dimensions_source  varchar(16),
    ADD CONSTRAINT ck_sku_dimensions_range CHECK (
        (width_mm IS NULL OR width_mm BETWEEN 1 AND 5000)
        AND (depth_mm IS NULL OR depth_mm BETWEEN 1 AND 5000)
        AND (height_mm IS NULL OR height_mm BETWEEN 1 AND 5000)),
    ADD CONSTRAINT ck_sku_dimensions_all_or_none CHECK (
        (width_mm IS NULL AND depth_mm IS NULL AND height_mm IS NULL AND dimensions_source IS NULL)
        OR (width_mm IS NOT NULL AND depth_mm IS NOT NULL AND height_mm IS NOT NULL)),
    ADD CONSTRAINT ck_sku_dimensions_source CHECK (
        dimensions_source IS NULL OR dimensions_source IN ('MEASURED', 'ESTIMATED'));
