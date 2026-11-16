-- S4 예약 시간 변경 — 교체 점유(2026-11-16, OMS §4 "새 시간대 정원을 검증하고 새 슬롯을 임시 확보", 실패하면 새 임시 슬롯만 해제·기존 유지).
-- 새 점유(HELD)는 replaces_hold_id = 기존 확정 점유. 새 점유를 확정할 때 같은 트랜잭션에서 기존 점유를 RELEASED(TIME_CHANGED).
-- 주문당 활성 1건 → "HELD 1건 + CONFIRMED 1건": 교체 중에는 기존 CONFIRMED 와 새 HELD 가 함께 있고 정원도 둘 다 잡는다.
-- S1(결제 전) 점유는 서비스 판정(활성 점유가 있으면 409 HOLD_ALREADY_ACTIVE)이 그대로 막는다.
ALTER TABLE slot_hold ADD COLUMN replaces_hold_id varchar(64);
DROP INDEX ux_slot_hold_active_order;
CREATE UNIQUE INDEX ux_slot_hold_held_order ON slot_hold (order_id) WHERE state = 'HELD';
CREATE UNIQUE INDEX ux_slot_hold_confirmed_order ON slot_hold (order_id) WHERE state = 'CONFIRMED';
