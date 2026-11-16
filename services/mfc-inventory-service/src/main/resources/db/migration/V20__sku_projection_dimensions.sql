-- 상품 실물 치수(2026-11-16, 계약 0.18.0). ProductVersionPublished.skus[].dimensions(widthMm·depthMm·heightMm)·dimensionsSource 사본.
-- NULL = 치수 미등록(지어내지 않는다). 셋 다 있으면 적치·이동·보충 칸 용량을 이 치수로 계산하고, 없으면 더미 크기(설정 시) 또는 CONFIG_REQUIRED.
-- 열 추가 전에 받은 상품은 다음 상품 버전 발행까지 비어 있다.
ALTER TABLE sku_projection
    ADD COLUMN width_mm           integer,
    ADD COLUMN depth_mm           integer,
    ADD COLUMN height_mm          integer,
    ADD COLUMN dimensions_source  varchar(16);   -- MEASURED / ESTIMATED (모르면 NULL)

-- 이동 행의 용량 계산 크기 표시: 더미 S·M·L 에 상품 치수 MEASURED·ESTIMATED·PRODUCT(출처 모름)가 더해진다.
ALTER TABLE move_allocation ALTER COLUMN size_class TYPE varchar(16);
