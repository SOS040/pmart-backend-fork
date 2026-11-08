-- 피킹 시작 표시(2026-11-08 #5 안 C). fulfillment 가 PICK 시작 때 위치 배정을 부르면 기록 — 그 뒤 보류분은 결품 사가가 처리하므로
-- 자동 재확보(BlockedResecurer)는 피킹 시작 전 예약만 한다(같은 보류를 사가와 재확보가 동시에 다루지 않게).
ALTER TABLE reservation ADD COLUMN picking_started_at timestamptz;
