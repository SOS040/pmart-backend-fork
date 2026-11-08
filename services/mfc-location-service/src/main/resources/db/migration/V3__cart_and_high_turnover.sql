-- 재고 이동(IV-03, 2026-11-08 사용자 결정): 옮기는 바구니(카트)도 식별번호가 있는 위치(구역 CART, 코드 CRT-{온도대}-{번호}).
-- 칸당 한 상품·한 상품은 한 자리가 원칙이고 고회전 선반만 예외(high_turnover) — 판정은 mfc-inventory 이동 배정이 한다.
ALTER TABLE location DROP CONSTRAINT ck_location_zone;
ALTER TABLE location ADD CONSTRAINT ck_location_zone CHECK (zone IN ('RECEIVING', 'STORAGE', 'PICKING', 'PACKING', 'QUARANTINE', 'DISPATCH_STAGING', 'RETURN', 'DISPOSAL', 'CART'));
ALTER TABLE location ADD COLUMN high_turnover boolean NOT NULL DEFAULT false;
