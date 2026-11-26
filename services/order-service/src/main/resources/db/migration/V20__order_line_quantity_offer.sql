-- 수량 행사 1+1·2+1(2026-11-26 ADR-0090). quantity 는 총 수량(유상 + 무료 — 예약·피킹·인계 기준), 무료분은 discount_share 에 들어 있다.
ALTER TABLE customer_order_line ADD COLUMN free_quantity integer NOT NULL DEFAULT 0;
ALTER TABLE customer_order_line ADD COLUMN offer_promotion_id varchar(40);
ALTER TABLE customer_order_line ADD CONSTRAINT ck_order_line_free_quantity CHECK (free_quantity >= 0 AND free_quantity <= quantity);
