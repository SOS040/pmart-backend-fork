-- 수량 행사 1+1·2+1(2026-11-26 ADR-0090). 같은 SKU 유상 offer_buy 개마다 offer_free 개를 더 준다.
-- 이 종류는 할인값이 없다 — discount_type/value 는 NOT NULL 이라 AMOUNT·0 을 넣고 응답에서는 discount 를 null 로 준다.
ALTER TABLE promotion DROP CONSTRAINT ck_promotion_kind;
ALTER TABLE promotion ADD CONSTRAINT ck_promotion_kind CHECK (kind IN ('PRICE_DISCOUNT', 'COUPON', 'QUANTITY_OFFER'));
ALTER TABLE promotion ADD COLUMN offer_buy integer;
ALTER TABLE promotion ADD COLUMN offer_free integer;
-- 초안은 덜 채운 채 저장할 수 있어(제출 때 검사) 수량 행사의 offer 는 비어 있을 수 있다. 다른 종류에는 두지 않는다.
ALTER TABLE promotion ADD CONSTRAINT ck_promotion_offer CHECK (
    kind = 'QUANTITY_OFFER' OR (offer_buy IS NULL AND offer_free IS NULL));
