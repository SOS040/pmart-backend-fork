-- 피킹·포장 실적의 예약 연결(IV-07, 슬라이스 6b 2026-11-07). 조각마다 집품·포장한 양. 현물은 단위 행(STORED→PICKED→PACKED)에 있고,
-- 고객 물량의 이동은 같은 예약에 연결한다 — 일반 이동으로 새로 빼지 않는다. 출고 차감(R·H 감소)은 인계(IV-08) 때.
ALTER TABLE reservation_part
    ADD COLUMN picked_qty integer NOT NULL DEFAULT 0,
    ADD COLUMN packed_qty integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_reservation_part_pick CHECK (packed_qty >= 0 AND packed_qty <= picked_qty AND picked_qty <= qty);
