-- 프로필 사진 출처(2026-11-21 BR-80, customer 0.15.0): PROVIDER 로그인 계정 사진 사본(로그인마다 최신), CUSTOM 고객이 올린 사진, NONE 기본 이미지.
-- CUSTOM·NONE 이면 로그인해도 제공자 사진으로 덮지 않는다(profile_image_source_url 은 계속 최신으로 받아 두어 되돌리기에 쓴다).
-- 지금 보이는 사진은 그대로 profile_image_key·content_type·sha256 (CUSTOM 이면 올린 사진).
ALTER TABLE customer
    ADD COLUMN profile_photo_source varchar(10) NOT NULL DEFAULT 'PROVIDER'
        CHECK (profile_photo_source IN ('PROVIDER', 'CUSTOM', 'NONE'));
