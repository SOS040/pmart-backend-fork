-- 판매 상태를 시각이 아니라 상품 행 버전으로 비교(2026-11-04). ProductVersionPublished.rowVersion · ProductSaleStateChanged.version.
-- 행 버전 없는 옛 사건은 아직 행 버전 기준 상태가 없을 때만 시각으로 비교한다(재생 호환). 기존 행은 NULL.
ALTER TABLE display_product ADD COLUMN IF NOT EXISTS sale_state_row_version integer;
