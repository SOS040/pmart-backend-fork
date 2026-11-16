-- 라이더 앱 요청 §6·§7(2026-11-16 사용자 결정): 배달 수단 E_BIKE(전기자전거)·KICKBOARD(전동킥보드) 추가(수행료는 자전거와 같음),
-- 운전면허 번호 — 오토바이·자동차·킥보드는 필수, 진위 확인 없이 중복만. 원문은 저장하지 않는다(고유식별정보):
-- license_hash = HMAC-SHA256(서버 키 pmart.rider.license-hash-key, 숫자 12자리) — 중복 확인용, license_masked = 표시용 가린 값.
ALTER TABLE rider DROP CONSTRAINT ck_rider_vehicle;
ALTER TABLE rider ADD CONSTRAINT ck_rider_vehicle CHECK (vehicle_type IN ('MOTORCYCLE', 'BICYCLE', 'E_BIKE', 'KICKBOARD', 'CAR', 'WALK'));
ALTER TABLE rider ADD COLUMN license_hash   varchar(64);
ALTER TABLE rider ADD COLUMN license_masked varchar(20);
CREATE UNIQUE INDEX ux_rider_license ON rider (license_hash) WHERE license_hash IS NOT NULL;
