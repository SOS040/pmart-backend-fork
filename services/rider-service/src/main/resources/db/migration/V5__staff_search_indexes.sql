-- 직원 라이더 찾기(2026-11-15, 계약 0.4.0 — BOD-3): 최근 가입·최근 운행 상태 변경 순 목록. 전화번호는 uq_rider_phone 으로 이미 찾는다.
CREATE INDEX ix_rider_joined ON rider (joined_at DESC, rider_id);
CREATE INDEX ix_rider_state_changed ON rider (state_changed_at DESC, rider_id);
