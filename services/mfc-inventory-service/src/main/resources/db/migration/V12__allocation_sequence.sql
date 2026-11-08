-- 위치 배정 순서(V11 의 created_at 은 한 명령 안에서 같아 순서가 흔들렸다) — 넣은 순서 그대로 읽는다. 2026-11-08
ALTER TABLE reservation_location_allocation ADD COLUMN seq bigint GENERATED ALWAYS AS IDENTITY;
