-- 네이버 로그인 이름(2026-11-25 — 사용자 "네이버 프로필이 안 들어가 있는 것 같아"). 적용된 파일은 수정하지 않는다.

-- 네이버 ID 토큰에는 이름도 없다. 교환 때 같은 프로필 API 응답의 response.name(없으면 nickname)을 이메일과 같은 행에 묶어 두고
-- 로그인 때 CustomerAuthenticated.name 에 싣는다. 이메일 없이 이름만 있을 수 있어 email 을 비울 수 있게 한다.
ALTER TABLE naver_profile_email ALTER COLUMN email DROP NOT NULL;
ALTER TABLE naver_profile_email ADD COLUMN name varchar(100);  -- 네이버 프로필 이름(개인정보 — 로그 금지)
COMMENT ON TABLE naver_profile_email IS
    'BR-77 네이버 교환 때 받은 이메일·이름(2026-11-25) — 같은 계정 로그인 때 한 번 쓰고 지움, 만료 10분';
