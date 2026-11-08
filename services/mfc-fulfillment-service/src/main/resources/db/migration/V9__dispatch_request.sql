-- 배차 요청(2026-11-08 사용자 결정 D-5): 점원이 포장 중 예상 포장 시간과 함께 요청, 포장 끝날 때까지 안 했으면 출하 대기 때 자동. 출고당 한 번.
ALTER TABLE shipment ADD COLUMN dispatch_requested_at timestamptz;
ALTER TABLE shipment ADD COLUMN dispatch_trigger varchar(16);
ALTER TABLE shipment ADD COLUMN dispatch_pack_minutes integer;
ALTER TABLE shipment ADD COLUMN dispatch_estimated_ready_at timestamptz;
ALTER TABLE shipment ADD COLUMN dispatch_requested_by varchar(64);
