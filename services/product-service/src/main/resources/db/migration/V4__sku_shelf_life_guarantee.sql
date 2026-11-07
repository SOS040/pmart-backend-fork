-- 상품별 소비기한 보장(ADR-0041). 상품 고시 "소비기한 또는 품질유지기한" 의 고객 약속을 구조화한 값.
-- NULL = 그 보장 문구가 없음(0 으로 채우지 않는다). 둘 다 있을 수 있다.
ALTER TABLE sku
    ADD COLUMN shelf_min_remaining_days      integer,   -- 주문일 기준 소비기한이 N일 이상 남은 상품
    ADD COLUMN shelf_max_days_since_mfg      integer,   -- 주문일 기준 N일 이내 제조된 상품
    ADD CONSTRAINT ck_sku_shelf_min_remaining CHECK (shelf_min_remaining_days IS NULL OR shelf_min_remaining_days BETWEEN 0 AND 3650),
    ADD CONSTRAINT ck_sku_shelf_max_since_mfg CHECK (shelf_max_days_since_mfg IS NULL OR shelf_max_days_since_mfg BETWEEN 0 AND 3650);
