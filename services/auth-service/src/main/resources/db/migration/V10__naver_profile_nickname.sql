-- 네이버 별명(2026-11-25 — 사용자 "실제 이름과 닉네임 이렇게 다르게 관리해야할것같은데"). 적용된 파일은 수정하지 않는다.
-- 교환 때 프로필 응답의 response.nickname 을 이름과 따로 묶어 두고 로그인 때 CustomerAuthenticated.providerNickname 에 싣는다.
ALTER TABLE naver_profile_email ADD COLUMN nickname varchar(100);  -- 네이버 별명(개인정보 — 로그 금지)
