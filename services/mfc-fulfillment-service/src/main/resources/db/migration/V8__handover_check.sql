-- 인계 허가 만료 뒤 점원 확인(2026-11-08 사용자 결정 D-1 안 C). 만료됐지만 확정 요청이 없는 허가는 점원이 포장대 실물을 확인한다:
-- AT_STATION(포장대에 있음 → 허가 해제) / TAKEN_BY_RIDER(없음 → 그 라이더에게 인계된 것으로 확정).
ALTER TABLE package ADD COLUMN fence_check_result varchar(16);
ALTER TABLE package ADD COLUMN fence_checked_by varchar(64);
ALTER TABLE package ADD COLUMN fence_checked_at timestamptz;
ALTER TABLE package ADD COLUMN fence_check_note varchar(500);
