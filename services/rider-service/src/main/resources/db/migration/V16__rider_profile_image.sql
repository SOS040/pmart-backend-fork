-- 소셜 프로필 사진 사본(2026-11-25 — 사용자 "프로필 사진도 해줘", 고객 customer 와 같게). 적용된 파일은 수정하지 않는다.
-- 제공자 사진 주소(RiderAuthenticated.pictureUrl)를 앱에 그대로 주지 않고, 커밋 뒤 받아 S3(pmart-rider) profile-images/<riderId>/<sha256> 에 둔다.
ALTER TABLE rider_provider_profile ADD COLUMN picture_source_url varchar(1000);  -- 제공자 원본 주소(개인정보 — 로그 금지)
ALTER TABLE rider_provider_profile ADD COLUMN image_key varchar(200);
ALTER TABLE rider_provider_profile ADD COLUMN image_content_type varchar(40);
ALTER TABLE rider_provider_profile ADD COLUMN image_sha256 char(64);
ALTER TABLE rider_provider_profile ADD COLUMN image_copied_from varchar(1000); -- 사본을 만든 원본 주소(같으면 다시 받지 않음)
ALTER TABLE rider_provider_profile ADD COLUMN image_updated_at timestamptz;
