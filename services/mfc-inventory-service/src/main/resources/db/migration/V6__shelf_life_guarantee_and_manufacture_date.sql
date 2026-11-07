-- 상품별 소비기한 보장(ADR-0041): product-service SKU 의 shelfLifeGuarantee 사본, LOT 제조일.

-- SKU 투영: ProductVersionPublished.skus[].shelfLifeGuarantee. NULL = 그 보장 문구 없음(0 으로 채우지 않는다)
ALTER TABLE sku_projection
    ADD COLUMN shelf_min_remaining_days  integer,   -- 주문일 기준 소비기한 N일 이상 남은 상품
    ADD COLUMN shelf_max_days_since_mfg  integer;   -- 주문일 기준 N일 이내 제조된 상품

-- LOT 제조일: 검수(INSPECT)·정정(CORRECTION)에서 확인한 값. 없으면 NULL(추정하지 않는다)
ALTER TABLE stock_lot ADD COLUMN manufacture_date date;

ALTER TABLE stock_lot DROP CONSTRAINT ck_stock_lot_untracked;
ALTER TABLE stock_lot ADD CONSTRAINT ck_stock_lot_untracked
    CHECK (tracking = 'TRACKED' OR (source_lot_ref IS NULL AND expiry_date IS NULL AND manufacture_date IS NULL));

-- 같은 SKU·원문·소비기한·제조일이면 같은 LOT(추적 LOT 만). 기존 LOT 은 제조일 NULL 이라 정체성이 그대로다.
DROP INDEX uq_stock_lot_identity;
CREATE UNIQUE INDEX uq_stock_lot_identity ON stock_lot (sku, source_lot_ref, expiry_date, manufacture_date) NULLS NOT DISTINCT
    WHERE tracking = 'TRACKED';
