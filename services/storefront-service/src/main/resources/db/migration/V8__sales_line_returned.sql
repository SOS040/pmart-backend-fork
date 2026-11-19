-- 판매 원장 반품 수(2026-11-20 리뷰 지적 3): order 는 OrderLineQuantityChanged 의 returnedQty 를 반품(RETURN) 사건에만 싣는다.
-- 반품 뒤 재배송·인계 사건이 오면 반품이 0 으로 계산돼 순 수량이 되살아났다 → 마지막으로 받은 반품 수를 두고, 사건에 없으면 그것을 쓴다.
ALTER TABLE sku_sales_line ADD COLUMN returned_qty integer NOT NULL DEFAULT 0;
