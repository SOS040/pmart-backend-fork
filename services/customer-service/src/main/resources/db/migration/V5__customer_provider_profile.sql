-- 로그인 제공자(Google)가 준 프로필(2026-10-31, ADR-0024). CustomerAuthenticated 에 실려 오고 로그인마다 최신 값으로 갱신한다
-- (값이 없으면 기존 값 유지). name·email 은 V4 컬럼을 쓴다. 개인정보 — 로그 금지.
-- 프로필 사진은 제공자 URL 을 그대로 내려주지 않는다: 로그인 뒤 한 번 받아 S3(버킷 pmart-customer)에 두고 GET /me/profile-image 로 준다.
ALTER TABLE customer
    ADD COLUMN email_verified             boolean,
    ADD COLUMN profile_image_source_url   varchar(1000),   -- 제공자가 마지막으로 준 사진 URL(받을 대상)
    ADD COLUMN profile_image_key          varchar(200),    -- 저장한 사본의 S3 키(profile-images/<accountId>/<sha256>)
    ADD COLUMN profile_image_content_type varchar(32),
    ADD COLUMN profile_image_sha256       char(64),        -- 사본 내용 지문(ETag·주소 버전)
    ADD COLUMN profile_image_copied_from  varchar(1000),   -- 사본을 받은 원본 URL(= source 면 다시 받지 않음)
    ADD COLUMN profile_image_updated_at   timestamptz;
